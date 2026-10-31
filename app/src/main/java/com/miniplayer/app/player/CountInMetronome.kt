package com.miniplayer.app.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.miniplayer.app.core.model.CountInMode
import com.miniplayer.app.core.model.PracticeMath
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CountInProgress(
    val mode: CountInMode,
    val currentBeat: Int,
    val totalBeats: Int,
    val remainingMs: Long,
    val totalMs: Long,
) {
    val fractionRemaining: Float
        get() = if (totalMs > 0L) (remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * Synthesizes crisp, zero-pop PCM woodblock clicks and runs exact `1s / 2s / 4-Beat` count-in
 * countdowns on the main coroutine scope. Cancelling (on pause, manual seek, loop clear, or track
 * switch) guarantees `onComplete` is NEVER invoked.
 */
class CountInMetronome(
    private val mainScope: CoroutineScope,
) {
    private val _progress = MutableStateFlow<CountInProgress?>(null)
    val progress: StateFlow<CountInProgress?> = _progress.asStateFlow()

    private var activeJob: Job? = null
    private var pendingOnComplete: (() -> Unit)? = null
    private val tapTimestampsMs = ArrayDeque<Long>()

    private val highClickPcm: ShortArray by lazy {
        synthesizeWoodblockClick(primaryHz = 1180.0, overtoneHz = 1790.0, amplitude = 0.78)
    }
    private val lowClickPcm: ShortArray by lazy {
        synthesizeWoodblockClick(primaryHz = 860.0, overtoneHz = 1290.0, amplitude = 0.62)
    }

    val isRunning: Boolean
        get() = activeJob?.isActive == true

    fun start(
        mode: CountInMode,
        bpm: Int,
        practiceSpeed: Float,
        playClickSound: Boolean,
        onComplete: () -> Unit,
    ) {
        cancel()
        if (mode == CountInMode.OFF) {
            onComplete()
            return
        }

        pendingOnComplete = onComplete
        activeJob = mainScope.launch {
            try {
                when (mode) {
                    CountInMode.SEC_1 -> runFixedSecondsCountdown(
                        mode = mode,
                        seconds = 1,
                        playClickSound = playClickSound,
                    )
                    CountInMode.SEC_2 -> runFixedSecondsCountdown(
                        mode = mode,
                        seconds = 2,
                        playClickSound = playClickSound,
                    )
                    CountInMode.BEAT_4 -> runFourBeatCountdown(
                        bpm = bpm,
                        practiceSpeed = practiceSpeed,
                        playClickSound = playClickSound,
                    )
                    CountInMode.OFF -> Unit
                }
                if (isActive) {
                    val finishCallback = pendingOnComplete
                    pendingOnComplete = null
                    _progress.value = null
                    finishCallback?.invoke()
                }
            } catch (e: CancellationException) {
                _progress.value = null
                throw e
            }
        }
    }

    /**
     * Immediately skips remaining countdown wait and invokes `onComplete` so the pre-primed `A`
     * frame starts playing right away.
     */
    fun skipImmediately() {
        val callback = pendingOnComplete
        cancel()
        callback?.invoke()
    }

    /**
     * Aborts any active countdown without triggering playback resume. Must be called on pause,
     * manual seek, bookmark change, loop clear, or track change.
     */
    fun cancel() {
        pendingOnComplete = null
        activeJob?.cancel()
        activeJob = null
        _progress.value = null
    }

    /**
     * Records a tap timestamp and returns the updated BPM once at least 2 valid taps within 2.5s
     * are collected (using the median of up to the last 4 intervals).
     */
    fun recordTapTempo(nowElapsedMs: Long = monotonicNowMs()): Int? {
        val previous = tapTimestampsMs.lastOrNull()
        if (previous != null && nowElapsedMs - previous > 2500L) {
            tapTimestampsMs.clear()
        }
        tapTimestampsMs.addLast(nowElapsedMs)
        while (tapTimestampsMs.size > 5) {
            tapTimestampsMs.removeFirst()
        }
        return PracticeMath.computeTapTempoBpm(tapTimestampsMs.toList())
    }

    fun resetTapHistory() {
        tapTimestampsMs.clear()
    }

    private suspend fun runFixedSecondsCountdown(
        mode: CountInMode,
        seconds: Int,
        playClickSound: Boolean,
    ) {
        val totalMs = seconds * 1000L
        val startMs = monotonicNowMs()
        var lastSecondIndex = -1
        while (true) {
            val elapsed = monotonicNowMs() - startMs
            val remaining = (totalMs - elapsed).coerceAtLeast(0L)
            if (remaining <= 0L) break

            val secondIndex = (elapsed / 1000L).toInt().coerceIn(0, seconds - 1)
            if (secondIndex != lastSecondIndex) {
                lastSecondIndex = secondIndex
                if (playClickSound) {
                    triggerClickAsync(accent = secondIndex == 0)
                }
            }
            _progress.value = CountInProgress(
                mode = mode,
                currentBeat = secondIndex + 1,
                totalBeats = seconds,
                remainingMs = remaining,
                totalMs = totalMs,
            )
            delay(minOf(32L, remaining))
        }
    }

    private suspend fun runFourBeatCountdown(
        bpm: Int,
        practiceSpeed: Float,
        playClickSound: Boolean,
    ) {
        val intervalMs = PracticeMath.computeClickIntervalMs(bpm, practiceSpeed)
        val totalMs = intervalMs * 4L
        val startMs = monotonicNowMs()
        var lastBeatTriggered = -1

        while (true) {
            val elapsed = monotonicNowMs() - startMs
            val remaining = (totalMs - elapsed).coerceAtLeast(0L)
            if (remaining <= 0L) break

            val beatIndex = (elapsed / intervalMs).toInt().coerceIn(0, 3)
            if (beatIndex != lastBeatTriggered) {
                lastBeatTriggered = beatIndex
                if (playClickSound) {
                    triggerClickAsync(accent = beatIndex == 0)
                }
            }
            _progress.value = CountInProgress(
                mode = CountInMode.BEAT_4,
                currentBeat = beatIndex + 1,
                totalBeats = 4,
                remainingMs = remaining,
                totalMs = totalMs,
            )
            val nextBeatMs = (beatIndex + 1) * intervalMs
            val msToNextBeat = (nextBeatMs - elapsed).coerceAtLeast(1L)
            delay(minOf(24L, msToNextBeat, remaining))
        }
    }

    private fun triggerClickAsync(accent: Boolean) {
        val samples = if (accent) highClickPcm else lowClickPcm
        mainScope.launch(Dispatchers.IO) {
            playStaticPcm(samples)
        }
    }

    private suspend fun playStaticPcm(samples: ShortArray) = withContext(Dispatchers.IO) {
        var track: AudioTrack? = null
        try {
            val byteSize = samples.size * 2
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(byteSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            delay(DURATION_MS + 25L)
        } catch (_: Throwable) {
            // Audio device unavailable in headless/test environment; ignore safely.
        } finally {
            try {
                track?.stop()
            } catch (_: Throwable) {
            }
            try {
                track?.release()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 44_100
        private const val DURATION_MS = 45L

        internal fun monotonicNowMs(): Long {
            val sys = try {
                SystemClock.elapsedRealtime()
            } catch (_: Throwable) {
                0L
            }
            return if (sys > 0L) sys else (System.nanoTime() / 1_000_000L)
        }

        internal fun synthesizeWoodblockClick(
            primaryHz: Double,
            overtoneHz: Double,
            amplitude: Double,
        ): ShortArray {
            val totalSamples = ((SAMPLE_RATE_HZ * DURATION_MS) / 1000L).toInt()
            val attackSamples = (SAMPLE_RATE_HZ * 0.002).toInt().coerceAtLeast(1)
            val out = ShortArray(totalSamples)
            for (i in 0 until totalSamples) {
                val t = i.toDouble() / SAMPLE_RATE_HZ
                val attackEnv = if (i < attackSamples) {
                    0.5 * (1.0 - kotlin.math.cos(PI * i / attackSamples))
                } else {
                    1.0
                }
                val decayEnv = exp(-t * 95.0)
                val wave = 0.76 * sin(2.0 * PI * primaryHz * t) +
                    0.24 * sin(2.0 * PI * overtoneHz * t)
                val sample = (wave * attackEnv * decayEnv * amplitude * Short.MAX_VALUE)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[i] = sample.toShort()
            }
            return out
        }
    }
}
