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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Always-active 16-bit PCM [AudioProcessor] wrapper around Media3's [SonicAudioProcessor] and
 * [FormantStemDspProcessor] that switches speed, formant-preserving pitch, and 5-stem soft-mask
 * gains on-the-fly per input buffer without ever flipping [isActive], seeking
 * [androidx.media3.exoplayer.ExoPlayer], or forcing [androidx.media3.exoplayer.audio.DefaultAudioSink]
 * to tear down/recreate the hardware `AudioTrack`.
 *
 * When speed is `1.0x`, pitch is `1.0x` (`0 st`), and `stemMix.isUnity` is `true`, samples pass
 * through bit-for-bit identically with zero STFT latency.
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
    private var targetPreserveFormants: Boolean = true

    @Volatile
    private var targetStemMix: StemMixConfig = StemMixConfig()

    @Volatile
    private var activeStemMaskGrid: StemTrackMaskGrid? = null

    @Volatile
    private var playheadPositionSupplier: (() -> Long)? = null

    private var appliedSpeed: Float = 1.0f
    private var appliedPitch: Float = 1.0f
    private var delegate: SonicAudioProcessor? = null
    private val formantStemDsp = FormantStemDspProcessor()
    private var dspWasActive: Boolean = false
    private var scratchInShorts = ShortArray(4096)
    private var stagingBuffer: ByteBuffer = EMPTY_BUFFER
    private var currentOutput: ByteBuffer = EMPTY_BUFFER
    private var inputEnded: Boolean = false

    val currentTargetSpeed: Float
        get() = targetSpeed

    val currentTargetPitch: Float
        get() = targetPitch

    val currentPreserveFormants: Boolean
        get() = targetPreserveFormants

    val currentStemMix: StemMixConfig
        get() = targetStemMix

    val currentAppliedSpeed: Float
        get() = appliedSpeed

    val currentAppliedPitch: Float
        get() = appliedPitch

    internal val hasActiveDelegate: Boolean
        get() = delegate != null

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

    fun setPreserveFormants(enabled: Boolean) {
        targetPreserveFormants = enabled
    }

    fun setStemMix(stemMix: StemMixConfig) {
        targetStemMix = stemMix.sanitized()
    }

    fun setStemMaskContext(
        maskGrid: StemTrackMaskGrid?,
        positionMsSupplier: (() -> Long)? = null,
    ) {
        activeStemMaskGrid = maskGrid
        playheadPositionSupplier = positionMsSupplier
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
        if (format == AudioFormat.NOT_SET) return

        inputEnded = false

        val s = targetSpeed.coerceIn(MIN_SPEED, MAX_SPEED)
        val p = targetPitch.coerceIn(MIN_PITCH, MAX_PITCH)
        val preserveFormants = targetPreserveFormants
        val stemMix = targetStemMix
        val isSonicIdentity = abs(s - 1.0f) < IDENTITY_EPSILON && abs(p - 1.0f) < IDENTITY_EPSILON
        val paramsChanged = abs(s - appliedSpeed) >= IDENTITY_EPSILON || abs(p - appliedPitch) >= IDENTITY_EPSILON
        val needsSpectralDsp =
            (preserveFormants && abs(p - 1.0f) >= FormantStemDspProcessor.PITCH_ACTIVE_EPSILON) ||
                !stemMix.isUnity

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

        // Fast bit-exact path when Sonic is identity AND spectral DSP is inactive and has no tail
        if (isSonicIdentity && !needsSpectralDsp && !dspWasActive) {
            delegate = null
            appliedSpeed = 1.0f
            appliedPitch = 1.0f

            val carryBytes = unreadCarry?.size ?: 0
            val tailBytes = drainedTail.remaining()
            val inputBytes = inputBuffer.remaining()
            val out = ensureStagingBuffer(carryBytes + tailBytes + inputBytes)
            if (unreadCarry != null) {
                out.put(unreadCarry)
            }
            if (tailBytes > 0) {
                out.put(drainedTail)
            }
            out.put(inputBuffer)
            out.flip()
            currentOutput = out
            existingDelegate?.reset()
            return
        }

        // Stage 1: Produce time-domain PCM from Sonic (or pass-through when Sonic is identity)
        val sonicCurrentOut: ByteBuffer
        if (isSonicIdentity) {
            delegate = null
            appliedSpeed = 1.0f
            appliedPitch = 1.0f
            sonicCurrentOut = inputBuffer
            existingDelegate?.reset()
        } else {
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

            activeDelegate.queueInput(inputBuffer)
            sonicCurrentOut = activeDelegate.getOutput()

            if (existingDelegate != null && existingDelegate !== activeDelegate) {
                existingDelegate.reset()
            }
        }

        // Stage 2: Optional Zero-Phase Formant Corrector + 5-Stem Soft-Mask STFT Mixer
        if (!needsSpectralDsp) {
            // Transitioning out of spectral DSP: drain WOLA tail cleanly before bypassing
            val dspTailShorts = if (dspWasActive || formantStemDsp.hasBufferedFrames) {
                dspWasActive = false
                formantStemDsp.drainRemaining(
                    pitchFactor = p,
                    preserveFormants = preserveFormants,
                    stemMix = stemMix,
                    stemMaskGrid = activeStemMaskGrid,
                    playheadPositionMs = playheadPositionSupplier?.invoke() ?: 0L,
                )
            } else {
                ShortArray(0)
            }

            val carryBytes = unreadCarry?.size ?: 0
            val dspTailBytes = dspTailShorts.size * 2
            val sonicTailBytes = drainedTail.remaining()
            val sonicCurBytes = sonicCurrentOut.remaining()
            val totalBytes = carryBytes + dspTailBytes + sonicTailBytes + sonicCurBytes
            if (totalBytes == 0) {
                currentOutput = EMPTY_BUFFER
                return
            }
            val out = ensureStagingBuffer(totalBytes)
            if (unreadCarry != null) out.put(unreadCarry)
            if (dspTailShorts.isNotEmpty()) putShortsNative(out, dspTailShorts)
            if (sonicTailBytes > 0) out.put(drainedTail)
            if (sonicCurBytes > 0) out.put(sonicCurrentOut)
            out.flip()
            currentOutput = out
        } else {
            formantStemDsp.configure(format.sampleRate, format.channelCount)
            dspWasActive = true
            val playheadMs = playheadPositionSupplier?.invoke() ?: 0L
            val maskGrid = activeStemMaskGrid

            val totalSonicShorts = (drainedTail.remaining() + sonicCurrentOut.remaining()) / 2
            val inShorts = extractShortsFromBuffers(drainedTail, sonicCurrentOut, totalSonicShorts)
            val processedShorts = if (inShorts.isNotEmpty()) {
                formantStemDsp.processInterleavedPcm16(
                    inputShorts = inShorts,
                    offsetShorts = 0,
                    lengthShorts = totalSonicShorts,
                    pitchFactor = p,
                    preserveFormants = preserveFormants,
                    stemMix = stemMix,
                    stemMaskGrid = maskGrid,
                    playheadPositionMs = playheadMs,
                )
            } else {
                ShortArray(0)
            }

            val carryBytes = unreadCarry?.size ?: 0
            val procBytes = processedShorts.size * 2
            val totalBytes = carryBytes + procBytes
            if (totalBytes == 0) {
                currentOutput = EMPTY_BUFFER
                return
            }
            val out = ensureStagingBuffer(totalBytes)
            if (unreadCarry != null) out.put(unreadCarry)
            if (processedShorts.isNotEmpty()) putShortsNative(out, processedShorts)
            out.flip()
            currentOutput = out
        }
    }

    override fun queueEndOfStream() {
        if (!inputEnded) {
            inputEnded = true
            val activeDelegate = delegate
            activeDelegate?.queueEndOfStream()

            if (dspWasActive || formantStemDsp.hasBufferedFrames) {
                val p = targetPitch.coerceIn(MIN_PITCH, MAX_PITCH)
                val preserveFormants = targetPreserveFormants
                val stemMix = targetStemMix
                val playheadMs = playheadPositionSupplier?.invoke() ?: 0L
                val maskGrid = activeStemMaskGrid

                val unreadCarry: ByteArray? = if (currentOutput.hasRemaining()) {
                    ByteArray(currentOutput.remaining()).also { currentOutput.get(it) }
                } else {
                    null
                }
                currentOutput = EMPTY_BUFFER

                // Drain Sonic's EOS tail and feed it through FormantStemDspProcessor before draining WOLA
                val sonicEosChunks = ArrayList<ShortArray>()
                if (activeDelegate != null) {
                    var safety = 0
                    while (!activeDelegate.isEnded && safety++ < 16) {
                        val delOut = activeDelegate.getOutput()
                        if (!delOut.hasRemaining()) break
                        val count = delOut.remaining() / 2
                        val chunk = extractShortsFromBuffers(delOut, EMPTY_BUFFER, count)
                        val proc = formantStemDsp.processInterleavedPcm16(
                            inputShorts = chunk,
                            offsetShorts = 0,
                            lengthShorts = count,
                            pitchFactor = p,
                            preserveFormants = preserveFormants,
                            stemMix = stemMix,
                            stemMaskGrid = maskGrid,
                            playheadPositionMs = playheadMs,
                        )
                        if (proc.isNotEmpty()) sonicEosChunks.add(proc)
                    }
                }

                val dspFinalTail = formantStemDsp.drainRemaining(
                    pitchFactor = p,
                    preserveFormants = preserveFormants,
                    stemMix = stemMix,
                    stemMaskGrid = maskGrid,
                    playheadPositionMs = playheadMs,
                )
                dspWasActive = false

                val carryBytes = unreadCarry?.size ?: 0
                val eosProcBytes = sonicEosChunks.sumOf { it.size * 2 }
                val finalTailBytes = dspFinalTail.size * 2
                val totalBytes = carryBytes + eosProcBytes + finalTailBytes
                if (totalBytes > 0) {
                    val out = ensureStagingBuffer(totalBytes)
                    if (unreadCarry != null) out.put(unreadCarry)
                    for (chunk in sonicEosChunks) putShortsNative(out, chunk)
                    if (dspFinalTail.isNotEmpty()) putShortsNative(out, dspFinalTail)
                    out.flip()
                    currentOutput = out
                }
            }
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
            !formantStemDsp.hasBufferedFrames &&
            (delegate == null || delegate!!.isEnded())
    }

    override fun flush() {
        if (isActive()) {
            activeAudioFormat = pendingInputAudioFormat
            formantStemDsp.configure(activeAudioFormat.sampleRate, activeAudioFormat.channelCount)
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
        formantStemDsp.resetState()
        dspWasActive = false
        currentOutput = EMPTY_BUFFER
        inputEnded = false
    }

    override fun reset() {
        delegate?.reset()
        delegate = null
        formantStemDsp.resetState()
        dspWasActive = false
        pendingInputAudioFormat = AudioFormat.NOT_SET
        activeAudioFormat = AudioFormat.NOT_SET
        appliedSpeed = 1.0f
        appliedPitch = 1.0f
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

    private fun extractShortsFromBuffers(
        first: ByteBuffer,
        second: ByteBuffer,
        totalShorts: Int,
    ): ShortArray {
        if (totalShorts <= 0) return ShortArray(0)
        if (scratchInShorts.size < totalShorts) {
            scratchInShorts = ShortArray(maxOf(totalShorts, scratchInShorts.size * 2))
        }
        var offset = 0
        if (first.hasRemaining()) {
            val sb = first.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
            val n = sb.remaining()
            sb.get(scratchInShorts, offset, n)
            first.position(first.position() + n * 2)
            offset += n
        }
        if (second.hasRemaining()) {
            val sb = second.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
            val n = sb.remaining()
            sb.get(scratchInShorts, offset, n)
            second.position(second.position() + n * 2)
            offset += n
        }
        return scratchInShorts
    }

    private fun putShortsNative(dst: ByteBuffer, shorts: ShortArray) {
        val sb = dst.asShortBuffer()
        sb.put(shorts)
        dst.position(dst.position() + shorts.size * 2)
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
