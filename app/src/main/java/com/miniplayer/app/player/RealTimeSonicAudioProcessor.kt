package com.miniplayer.app.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.miniplayer.app.core.model.StemMixConfig
import com.miniplayer.app.core.model.StemType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Always-active 16-bit PCM [AudioProcessor] combining:
 * 1. **Stage 0 (Zero-Latency 5-Stem Time-Domain Mixer)**: When `!stemMix.isUnity` and the track's
 *    offline `HTDemucs` `.stems` cache ([StemSeparatedTrackCache]) is bound, aligns `sampleFrameCursor`
 *    via [playheadPositionSupplier] refined by fast local cross-correlation around `playheadMs` and
 *    mixes `[VOCALS, GUITAR, BASS, DRUMS, OTHER]` directly in the time domain (`O(1)` memory-mapped
 *    `BFP8` read + linear resampling if track sample rate != `44,100 Hz` + soft-clip limiting).
 * 2. **Stage 1 (Pure Time-Domain `SonicAudioProcessor`)**: Applies crisp, phase-coherent time-domain
 *    WSOLA speed and pitch shifting without any STFT formant/envelope artifacts, and switches
 *    parameters on-the-fly without ever flipping [isActive] or tearing down `AudioTrack`.
 *
 * When speed is `1.0x`, pitch is `1.0x` (`0 st`), and `stemMix.isUnity` (or cache not bound),
 * samples pass through bit-for-bit identically with zero latency.
 */
@OptIn(UnstableApi::class)
class RealTimeSonicAudioProcessor : AudioProcessor {
    private var pendingInputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var activeAudioFormat: AudioFormat = AudioFormat.NOT_SET

    @Volatile
    private var targetSpeed: Float = 1.0f

    @Volatile
    private var targetPitch: Float = 1.0f

    @Volatile
    private var targetStemMix: StemMixConfig = StemMixConfig()

    @Volatile
    private var activeStemsCache: StemSeparatedTrackCache? = null

    @Volatile
    private var playheadPositionSupplier: (() -> Long)? = null

    private var appliedSpeed: Float = 1.0f
    private var appliedPitch: Float = 1.0f
    private var delegate: SonicAudioProcessor? = null

    private var sampleFrameCursor: Double = 0.0
    private var stemCursorAligned: Boolean = false
    private var stemAlignedOnSilence: Boolean = false
    private var lastBoundCacheRef: StemSeparatedTrackCache? = null

    private var mixedShortsScratch = ShortArray(4_096)
    private var corrInputMonoScratch = FloatArray(MAX_CORR_INPUT_FRAMES)
    private var gainsScratch = FloatArray(5)
    private var stemInputBuffer: ByteBuffer = EMPTY_BUFFER
    private var stagingBuffer: ByteBuffer = EMPTY_BUFFER
    private var currentOutput: ByteBuffer = EMPTY_BUFFER
    private var inputEnded: Boolean = false

    val currentTargetSpeed: Float
        get() = targetSpeed

    val currentTargetPitch: Float
        get() = targetPitch

    val currentStemMix: StemMixConfig
        get() = targetStemMix

    val currentAppliedSpeed: Float
        get() = appliedSpeed

    val currentAppliedPitch: Float
        get() = appliedPitch

    internal val hasActiveDelegate: Boolean
        get() = delegate != null

    internal val currentAlignedStemCursor: Double
        get() = sampleFrameCursor

    internal val isStemCursorAligned: Boolean
        get() = stemCursorAligned

    fun setTargetSpeedAndPitch(speed: Float, pitch: Float) {
        targetSpeed = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        targetPitch = pitch.coerceIn(MIN_PITCH, MAX_PITCH)
    }

    fun setTargetSpeed(speed: Float) {
        targetSpeed = speed.coerceIn(MIN_SPEED, MAX_SPEED)
    }

    fun setTargetPitch(pitch: Float) {
        targetPitch = pitch.coerceIn(MIN_PITCH, MAX_PITCH)
    }

    fun setStemMix(stemMix: StemMixConfig) {
        targetStemMix = stemMix.sanitized()
    }

    fun setSeparatedStemsContext(
        cache: StemSeparatedTrackCache?,
        positionMsSupplier: (() -> Long)? = null,
    ) {
        val changed = activeStemsCache !== cache
        activeStemsCache = cache
        playheadPositionSupplier = positionMsSupplier
        if (changed) {
            stemCursorAligned = false
            stemAlignedOnSilence = false
        }
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        pendingInputAudioFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean {
        return pendingInputAudioFormat != AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val format = if (activeAudioFormat != AudioFormat.NOT_SET) {
            activeAudioFormat
        } else {
            pendingInputAudioFormat.also { activeAudioFormat = it }
        }
        if (format == AudioFormat.NOT_SET || format.channelCount <= 0 || format.sampleRate <= 0) return

        inputEnded = false

        val bytesPerFrame = format.channelCount * 2
        val inputFrames = inputBuffer.remaining() / bytesPerFrame
        if (inputFrames <= 0) return

        val s = targetSpeed.coerceIn(MIN_SPEED, MAX_SPEED)
        val p = targetPitch.coerceIn(MIN_PITCH, MAX_PITCH)
        val stemMix = targetStemMix
        val cache = activeStemsCache
        if (cache !== lastBoundCacheRef) {
            lastBoundCacheRef = cache
            stemCursorAligned = false
            stemAlignedOnSilence = false
        }

        val isSonicIdentity = abs(s - 1.0f) < IDENTITY_EPSILON && abs(p - 1.0f) < IDENTITY_EPSILON
        val paramsChanged = abs(s - appliedSpeed) >= IDENTITY_EPSILON || abs(p - appliedPitch) >= IDENTITY_EPSILON
        val shouldMixStems = !stemMix.isUnity

        val unreadCarry: ByteArray? = if (currentOutput.hasRemaining()) {
            ByteArray(currentOutput.remaining()).also { currentOutput.get(it) }
        } else {
            null
        }
        currentOutput = EMPTY_BUFFER

        val existingDelegate = delegate
        var drainedTail: ByteBuffer = EMPTY_BUFFER
        if (existingDelegate != null && (isSonicIdentity || paramsChanged)) {
            existingDelegate.queueEndOfStream()
            drainedTail = existingDelegate.getOutput()
        }

        // Stage 0: Optional zero-latency 5-stem time-domain mixing
        val effectiveInput: ByteBuffer
        if (shouldMixStems && cache != null) {
            val stepPerFrame = cache.sampleRate.toDouble() / format.sampleRate.toDouble()
            if (!stemCursorAligned || stemAlignedOnSilence) {
                alignCursorToInputBuffer(
                    inputBuffer = inputBuffer,
                    inputFrames = inputFrames,
                    format = format,
                    cache = cache,
                    stepPerInputFrame = stepPerFrame,
                )
            }

            gainsScratch[0] = stemMix.effectiveGain(StemType.VOCALS)
            gainsScratch[1] = stemMix.effectiveGain(StemType.GUITAR)
            gainsScratch[2] = stemMix.effectiveGain(StemType.BASS)
            gainsScratch[3] = stemMix.effectiveGain(StemType.DRUMS)
            gainsScratch[4] = stemMix.effectiveGain(StemType.OTHER)

            val totalOutShorts = inputFrames * format.channelCount
            if (mixedShortsScratch.size < totalOutShorts) {
                mixedShortsScratch = ShortArray(max(totalOutShorts, mixedShortsScratch.size * 2))
            }

            sampleFrameCursor = cache.mixFramesToPcm16(
                startCursor = sampleFrameCursor,
                stepPerFrame = stepPerFrame,
                frameCount = inputFrames,
                outChannelCount = format.channelCount,
                gains5 = gainsScratch,
                outShorts = mixedShortsScratch,
            )

            // Consume original inputBuffer and expose stem-mixed PCM buffer to Stage 1
            inputBuffer.position(inputBuffer.limit())
            val mixedByteCount = totalOutShorts * 2
            val mixedBuf = ensureStemInputBuffer(mixedByteCount)
            mixedBuf.asShortBuffer().put(mixedShortsScratch, 0, totalOutShorts)
            mixedBuf.limit(mixedByteCount)
            mixedBuf.position(0)
            effectiveInput = mixedBuf
        } else {
            if (cache != null && stemCursorAligned) {
                val stepPerFrame = cache.sampleRate.toDouble() / format.sampleRate.toDouble()
                sampleFrameCursor += inputFrames.toDouble() * stepPerFrame
            }
            effectiveInput = inputBuffer
        }

        // Stage 1: Pure Sonic time-domain speed/pitch OR zero-latency bit-exact passthrough
        if (isSonicIdentity) {
            delegate = null
            appliedSpeed = 1.0f
            appliedPitch = 1.0f

            val carryBytes = unreadCarry?.size ?: 0
            val tailBytes = drainedTail.remaining()
            val inputBytes = effectiveInput.remaining()
            val out = ensureStagingBuffer(carryBytes + tailBytes + inputBytes)
            if (unreadCarry != null) {
                out.put(unreadCarry)
            }
            if (tailBytes > 0) {
                out.put(drainedTail)
            }
            out.put(effectiveInput)
            out.flip()
            currentOutput = out
            existingDelegate?.reset()
            return
        }

        val activeDelegate = if (existingDelegate == null || paramsChanged) {
            SonicAudioProcessor().apply {
                setSpeed(s)
                setPitch(p)
                configure(format)
                flush()
            }.also { created ->
                delegate = created
                appliedSpeed = s
                appliedPitch = p
            }
        } else {
            existingDelegate
        }

        activeDelegate.queueInput(effectiveInput)
        val sonicCurrentOut = activeDelegate.getOutput()

        if (existingDelegate != null && existingDelegate !== activeDelegate) {
            existingDelegate.reset()
        }

        val carryBytes = unreadCarry?.size ?: 0
        val sonicTailBytes = drainedTail.remaining()
        val sonicCurBytes = sonicCurrentOut.remaining()
        val totalBytes = carryBytes + sonicTailBytes + sonicCurBytes
        if (totalBytes == 0) {
            currentOutput = EMPTY_BUFFER
            return
        }
        val out = ensureStagingBuffer(totalBytes)
        if (unreadCarry != null) out.put(unreadCarry)
        if (sonicTailBytes > 0) out.put(drainedTail)
        if (sonicCurBytes > 0) out.put(sonicCurrentOut)
        out.flip()
        currentOutput = out
    }

    override fun queueEndOfStream() {
        if (!inputEnded) {
            inputEnded = true
            delegate?.queueEndOfStream()
        }
    }

    override fun getOutput(): ByteBuffer {
        if (currentOutput.hasRemaining()) {
            val out = currentOutput
            currentOutput = EMPTY_BUFFER
            return out
        }
        val activeDelegate = delegate
        if (activeDelegate != null) {
            return activeDelegate.getOutput()
        }
        return EMPTY_BUFFER
    }

    override fun isEnded(): Boolean {
        return inputEnded &&
            !currentOutput.hasRemaining() &&
            (delegate == null || delegate!!.isEnded())
    }

    override fun flush() {
        if (isActive()) {
            activeAudioFormat = pendingInputAudioFormat
            val s = targetSpeed.coerceIn(MIN_SPEED, MAX_SPEED)
            val p = targetPitch.coerceIn(MIN_PITCH, MAX_PITCH)
            val isIdentity = abs(s - 1.0f) < IDENTITY_EPSILON && abs(p - 1.0f) < IDENTITY_EPSILON
            if (isIdentity) {
                delegate?.reset()
                delegate = null
                appliedSpeed = 1.0f
                appliedPitch = 1.0f
            } else {
                val d = delegate ?: SonicAudioProcessor().also { delegate = it }
                d.setSpeed(s)
                d.setPitch(p)
                d.configure(activeAudioFormat)
                d.flush()
                appliedSpeed = s
                appliedPitch = p
            }
        } else {
            delegate?.reset()
            delegate = null
            appliedSpeed = 1.0f
            appliedPitch = 1.0f
        }
        stemCursorAligned = false
        stemAlignedOnSilence = false
        currentOutput = EMPTY_BUFFER
        inputEnded = false
    }

    override fun reset() {
        delegate?.reset()
        delegate = null
        pendingInputAudioFormat = AudioFormat.NOT_SET
        activeAudioFormat = AudioFormat.NOT_SET
        appliedSpeed = 1.0f
        appliedPitch = 1.0f
        stemCursorAligned = false
        stemAlignedOnSilence = false
        sampleFrameCursor = 0.0
        lastBoundCacheRef = null
        stemInputBuffer = EMPTY_BUFFER
        stagingBuffer = EMPTY_BUFFER
        currentOutput = EMPTY_BUFFER
        inputEnded = false
    }

    fun getMediaDuration(playoutDuration: Long): Long {
        val activeDelegate = delegate
        return if (activeDelegate != null &&
            (abs(appliedSpeed - 1.0f) >= IDENTITY_EPSILON || abs(appliedPitch - 1.0f) >= IDENTITY_EPSILON)
        ) {
            activeDelegate.getMediaDuration(playoutDuration)
        } else {
            playoutDuration
        }
    }

    override fun getDurationAfterProcessorApplied(durationUs: Long): Long {
        val activeDelegate = delegate
        return if (activeDelegate != null &&
            (abs(appliedSpeed - 1.0f) >= IDENTITY_EPSILON || abs(appliedPitch - 1.0f) >= IDENTITY_EPSILON)
        ) {
            activeDelegate.getPlayoutDuration(durationUs)
        } else {
            durationUs
        }
    }

    /**
     * Aligns [sampleFrameCursor] to the exact cached frame corresponding to [inputBuffer] by taking
     * the coarse playhead estimate from [playheadPositionSupplier] and refining it via two-stage
     * normalized cross-correlation against the sum of cached stems around `playheadMs` (`[-250ms, +650ms]`).
     */
    private fun alignCursorToInputBuffer(
        inputBuffer: ByteBuffer,
        inputFrames: Int,
        format: AudioFormat,
        cache: StemSeparatedTrackCache,
        stepPerInputFrame: Double,
    ) {
        val estMs = (playheadPositionSupplier?.invoke() ?: 0L).coerceAtLeast(0L)
        val estCacheFrame = ((estMs.toDouble() * cache.sampleRate.toDouble()) / 1000.0)
            .coerceIn(0.0, max(0, cache.totalFrames - 1).toDouble())

        // Convert up to MAX_CORR_INPUT_FRAMES of inputBuffer into cache-rate mono waveform
        val sb = inputBuffer.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
        val ch = format.channelCount
        val usableInputFrames = min(inputFrames, (MAX_CORR_INPUT_FRAMES / stepPerInputFrame).toInt().coerceAtLeast(1))
        val targetCacheFrames = min(
            MAX_CORR_INPUT_FRAMES,
            floor((usableInputFrames - 1).toDouble() * stepPerInputFrame).toInt() + 1,
        )
        if (targetCacheFrames < 32 || cache.totalFrames < 32) {
            sampleFrameCursor = estCacheFrame
            stemCursorAligned = true
            stemAlignedOnSilence = true
            return
        }

        val monoIn = corrInputMonoScratch
        var inEnergy = 0.0
        val invStep = 1.0 / stepPerInputFrame
        val inv32768 = 1.0f / 32768.0f

        for (k in 0 until targetCacheFrames) {
            val srcPos = k.toDouble() * invStep
            val f0 = floor(srcPos).toInt().coerceIn(0, usableInputFrames - 1)
            val f1 = min(usableInputFrames - 1, f0 + 1)
            val frac = (srcPos - f0.toDouble()).toFloat()

            val m0 = if (ch >= 2) {
                0.5f * (sb.get(f0 * ch) + sb.get(f0 * ch + 1)) * inv32768
            } else {
                sb.get(f0 * ch) * inv32768
            }
            val m1 = if (ch >= 2) {
                0.5f * (sb.get(f1 * ch) + sb.get(f1 * ch + 1)) * inv32768
            } else {
                sb.get(f1 * ch) * inv32768
            }
            val v = m0 + (m1 - m0) * frac
            monoIn[k] = v
            inEnergy += (v * v).toDouble()
        }

        if (inEnergy < 1e-6) {
            if (!stemCursorAligned) {
                sampleFrameCursor = estCacheFrame
                stemCursorAligned = true
                stemAlignedOnSilence = true
            }
            return
        }

        val searchBehindFrames = (0.25 * cache.sampleRate).roundToInt() // -250 ms
        val searchAheadFrames = (0.65 * cache.sampleRate).roundToInt() // +650 ms (covers ±200ms + AudioSink lead)
        val minLag = max(0, estCacheFrame.roundToInt() - searchBehindFrames)
        val maxLag = min(
            max(0, cache.totalFrames - targetCacheFrames),
            estCacheFrame.roundToInt() + searchAheadFrames,
        )

        if (maxLag < minLag) {
            sampleFrameCursor = estCacheFrame
            stemCursorAligned = true
            stemAlignedOnSilence = false
            return
        }

        // Stage A: Coarse decimated search (every 8 samples)
        val coarseLagStep = 8
        val coarseSampleStep = max(4, targetCacheFrames / 160)
        var subInEnergy = 0.0
        run {
            var i = 0
            while (i < targetCacheFrames) {
                val v = monoIn[i].toDouble()
                subInEnergy += v * v
                i += coarseSampleStep
            }
        }

        var bestCoarseLag = estCacheFrame.roundToInt().coerceIn(minLag, maxLag)
        var bestCoarseScore = -1.0
        val invRadius = 1.0 / max(1, searchAheadFrames).toDouble()

        var lag = minLag
        while (lag <= maxLag) {
            var dot = 0.0
            var refEnergy = 0.0
            var i = 0
            while (i < targetCacheFrames) {
                val x = monoIn[i].toDouble()
                val y = cache.readFrameMonoSum(lag + i).toDouble()
                dot += x * y
                refEnergy += y * y
                i += coarseSampleStep
            }
            if (refEnergy > 1e-8 && subInEnergy > 1e-8) {
                val ncc = dot / sqrt(subInEnergy * refEnergy)
                // Gentle proximity tie-breaker prevents periodic sustained notes from locking cycles away
                val score = ncc - 0.015 * abs(lag.toDouble() - estCacheFrame) * invRadius
                if (score > bestCoarseScore) {
                    bestCoarseScore = score
                    bestCoarseLag = lag
                }
            }
            lag += coarseLagStep
        }

        // Stage B: Fine 1-sample search around bestCoarseLag ± 12 samples
        val fineLen = min(targetCacheFrames, 512)
        var fineInEnergy = 0.0
        for (i in 0 until fineLen) {
            val v = monoIn[i].toDouble()
            fineInEnergy += v * v
        }
        val fineLo = max(minLag, bestCoarseLag - 12)
        val fineHi = min(maxLag, bestCoarseLag + 12)
        var bestFineLag = bestCoarseLag
        var bestFineScore = -1.0
        var bestFineNcc = -1.0

        for (candLag in fineLo..fineHi) {
            var dot = 0.0
            var refEnergy = 0.0
            for (i in 0 until fineLen) {
                val x = monoIn[i].toDouble()
                val y = cache.readFrameMonoSum(candLag + i).toDouble()
                dot += x * y
                refEnergy += y * y
            }
            if (refEnergy > 1e-8 && fineInEnergy > 1e-8) {
                val ncc = dot / sqrt(fineInEnergy * refEnergy)
                val score = ncc - 0.015 * abs(candLag.toDouble() - estCacheFrame) * invRadius
                if (score > bestFineScore) {
                    bestFineScore = score
                    bestFineNcc = ncc
                    bestFineLag = candLag
                }
            }
        }

        sampleFrameCursor = if (bestFineNcc >= 0.25) {
            bestFineLag.toDouble()
        } else {
            estCacheFrame
        }
        stemCursorAligned = true
        stemAlignedOnSilence = false
    }

    private fun ensureStemInputBuffer(requiredBytes: Int): ByteBuffer {
        if (stemInputBuffer.capacity() < requiredBytes) {
            stemInputBuffer = ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder())
        } else {
            stemInputBuffer.clear()
        }
        return stemInputBuffer
    }

    private fun ensureStagingBuffer(requiredBytes: Int): ByteBuffer {
        if (stagingBuffer.capacity() < requiredBytes) {
            stagingBuffer = ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder())
        } else {
            stagingBuffer.clear()
        }
        return stagingBuffer
    }

    companion object {
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 2.0f
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2.0f
        const val IDENTITY_EPSILON = 0.0001f
        private const val MAX_CORR_INPUT_FRAMES = 2_048
    }
}

/**
 * Custom [AudioProcessorChain] wiring [RealTimeSonicAudioProcessor] into `DefaultAudioSink`
 * while preserving real-time pitch targets across ExoPlayer speed-only `PlaybackParameters` updates.
 */
@OptIn(UnstableApi::class)
class RealTimeAudioProcessorChain(
    val sonicProcessor: RealTimeSonicAudioProcessor = RealTimeSonicAudioProcessor(),
) : AudioProcessorChain {
    private val processors: Array<AudioProcessor> = arrayOf(sonicProcessor)

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonicProcessor.setTargetSpeed(playbackParameters.speed)
        if (abs(playbackParameters.pitch - 1.0f) >= RealTimeSonicAudioProcessor.IDENTITY_EPSILON) {
            sonicProcessor.setTargetPitch(playbackParameters.pitch)
        }
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    override fun getMediaDuration(playoutDuration: Long): Long =
        sonicProcessor.getMediaDuration(playoutDuration)

    override fun getSkippedOutputFrameCount(): Long = 0L
}
