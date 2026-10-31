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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Always-active 16-bit PCM [AudioProcessor] wrapper around Media3's [SonicAudioProcessor] that
 * switches speed and pitch on-the-fly per input buffer without ever flipping [isActive],
 * seeking [androidx.media3.exoplayer.ExoPlayer], or forcing [androidx.media3.exoplayer.audio.DefaultAudioSink]
 * to tear down/recreate the hardware `AudioTrack` or enter `afterDrainParameters` on rapid pitch clicks.
 *
 * Whenever speed or pitch changes (including returning to `1.0x, 0st` identity), any buffered
 * PCM samples inside the outgoing [SonicAudioProcessor] instance are drained cleanly into a
 * direct [stagingBuffer] before swapping or clearing the delegate so zero audio frames are lost.
 */
@OptIn(UnstableApi::class)
class RealTimeSonicAudioProcessor : AudioProcessor {
    private var pendingInputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var activeAudioFormat: AudioFormat = AudioFormat.NOT_SET

    @Volatile
    private var targetSpeed: Float = 1.0f

    @Volatile
    private var targetPitch: Float = 1.0f

    private var appliedSpeed: Float = 1.0f
    private var appliedPitch: Float = 1.0f
    private var delegate: SonicAudioProcessor? = null
    private var stagingBuffer: ByteBuffer = EMPTY_BUFFER
    private var currentOutput: ByteBuffer = EMPTY_BUFFER
    private var inputEnded: Boolean = false

    val currentTargetSpeed: Float
        get() = targetSpeed

    val currentTargetPitch: Float
        get() = targetPitch

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
        val isIdentity = abs(s - 1.0f) < IDENTITY_EPSILON && abs(p - 1.0f) < IDENTITY_EPSILON
        val paramsChanged = abs(s - appliedSpeed) >= IDENTITY_EPSILON || abs(p - appliedPitch) >= IDENTITY_EPSILON

        val unreadCarry: ByteArray? = if (currentOutput.hasRemaining()) {
            ByteArray(currentOutput.remaining()).also { currentOutput.get(it) }
        } else {
            null
        }
        currentOutput = EMPTY_BUFFER

        val existingDelegate = delegate
        var drainedTail: ByteBuffer = EMPTY_BUFFER
        if (existingDelegate != null && (isIdentity || paramsChanged)) {
            existingDelegate.queueEndOfStream()
            drainedTail = existingDelegate.getOutput()
        }

        if (isIdentity) {
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
            val delOut = activeDelegate.getOutput()

            val carryBytes = unreadCarry?.size ?: 0
            val tailBytes = drainedTail.remaining()
            if (carryBytes > 0 || tailBytes > 0) {
                val out = ensureStagingBuffer(carryBytes + tailBytes + delOut.remaining())
                if (unreadCarry != null) {
                    out.put(unreadCarry)
                }
                if (tailBytes > 0) {
                    out.put(drainedTail)
                }
                if (delOut.hasRemaining()) {
                    out.put(delOut)
                }
                out.flip()
                currentOutput = out
            } else {
                currentOutput = delOut
            }

            if (existingDelegate != null && existingDelegate !== activeDelegate) {
                existingDelegate.reset()
            }
        }
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
