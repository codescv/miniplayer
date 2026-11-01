package com.miniplayer.app.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import com.google.common.collect.ImmutableList
import com.miniplayer.app.core.model.PracticeMath
import com.miniplayer.app.core.model.StemMixConfig
import com.miniplayer.app.core.model.StemType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RealTimeSonicAudioProcessorTest {
    private val stereo44k = AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)
    private val stereo48k = AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)

    @Test
    fun configure_requires16BitPcm_andIsAlwaysActiveAcrossAllPitchAndSpeedSettings() {
        val processor = RealTimeSonicAudioProcessor()
        assertFalse(processor.isActive())

        try {
            processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
            fail("Expected UnhandledAudioFormatException for float PCM")
        } catch (_: UnhandledAudioFormatException) {
            // Expected
        }

        val outFmt = processor.configure(stereo44k)
        assertEquals(stereo44k, outFmt)
        assertTrue(processor.isActive())
        processor.flush()
        assertTrue(processor.isActive())

        // Must stay active at identity (1.0x, 0st), pitch-shifted, slowed, and back to identity
        processor.setTargetSpeedAndPitch(1.0f, PracticeMath.computePitchFactor(3, 0))
        assertTrue(processor.isActive())
        processor.setTargetSpeedAndPitch(0.75f, PracticeMath.computePitchFactor(-4, 0))
        assertTrue(processor.isActive())
        processor.setTargetSpeedAndPitch(1.0f, 1.0f)
        assertTrue(processor.isActive())

        // reset() clears active format while preserving target speed/pitch across track switches
        processor.setTargetSpeedAndPitch(0.85f, PracticeMath.computePitchFactor(2, 0))
        processor.reset()
        assertFalse(processor.isActive())
        assertEquals(0.85f, processor.currentTargetSpeed, 0.0001f)
        assertEquals(PracticeMath.computePitchFactor(2, 0), processor.currentTargetPitch, 0.0001f)
    }

    @Test
    fun identityPassThrough_outputsExactBitForBitSamples() {
        val processor = RealTimeSonicAudioProcessor()
        processor.configure(stereo44k)
        processor.flush()

        val expectedShorts = ShortArray(2048) { idx -> ((idx * 17) - 16000).toShort() }
        val inBuf = shortsToNativeBuffer(expectedShorts)
        processor.queueInput(inBuf)
        assertFalse(inBuf.hasRemaining())

        val outBuf = processor.getOutput()
        val actualShorts = readShortsFromBuffer(outBuf)
        assertArrayEquals(expectedShorts, actualShorts)
        assertFalse(processor.hasActiveDelegate)
    }

    @Test
    fun rapidMultiClickPitchShift_60ConsecutiveChangesInPipeline_neverDropsChunksOrStalls() {
        val processor = RealTimeSonicAudioProcessor()
        val pipeline = AudioProcessingPipeline(ImmutableList.of(processor))
        pipeline.configure(stereo44k)
        pipeline.flush()
        assertTrue(pipeline.isOperational)

        val framesPerChunk = 1024 // Standard AAC decoder frame size
        val totalChunks = 64
        val totalInputFrames = framesPerChunk * totalChunks
        var totalOutputBytes = 0
        var nonZeroSamples = 0
        var phase = 0.0

        val semitonePattern = intArrayOf(0, 1, 2, 3, 4, 5, 6, 5, 3, 1, 0, -1, -2, -3, -4, -6, -2, 0)

        for (chunkIdx in 0 until totalChunks) {
            val semitone = semitonePattern[chunkIdx % semitonePattern.size]
            val pitch = PracticeMath.computePitchFactor(semitone, 0)
            processor.setTargetSpeedAndPitch(1.0f, pitch)

            val chunkShorts = ShortArray(framesPerChunk * 2)
            for (f in 0 until framesPerChunk) {
                val sample = (sin(phase) * 18_000.0).toInt().toShort()
                chunkShorts[f * 2] = sample
                chunkShorts[f * 2 + 1] = sample
                phase += 2.0 * PI * 220.0 / 44_100.0
            }

            val inBuf = shortsToNativeBuffer(chunkShorts)
            while (inBuf.hasRemaining()) {
                pipeline.queueInput(inBuf)
                val out = pipeline.output
                if (out.hasRemaining()) {
                    val shorts = readShortsFromBuffer(out)
                    totalOutputBytes += shorts.size * 2
                    nonZeroSamples += shorts.count { it.toInt() != 0 }
                }
            }
            assertTrue("Pipeline must remain operational on click #$chunkIdx", pipeline.isOperational)
        }

        // Idempotent queueEndOfStream (simulating repeated DefaultAudioSink.drainToEndOfStream calls)
        pipeline.queueEndOfStream()
        processor.queueEndOfStream()
        processor.queueEndOfStream()

        var safety = 0
        while (!pipeline.isEnded && safety++ < 100) {
            val out = pipeline.output
            if (out.hasRemaining()) {
                val shorts = readShortsFromBuffer(out)
                totalOutputBytes += shorts.size * 2
                nonZeroSamples += shorts.count { it.toInt() != 0 }
            }
        }

        assertTrue("Pipeline must reach ended state cleanly after EOS", pipeline.isEnded)
        val totalOutputFrames = totalOutputBytes / 4 // 16-bit stereo = 4 bytes/frame
        // At speed = 1.0f across all 60+ pitch changes, drained tails guarantee ~1:1 frame preservation
        val frameRatio = totalOutputFrames.toDouble() / totalInputFrames.toDouble()
        assertTrue("Expected ~1.0x output frames without chunk loss, got ratio=$frameRatio", frameRatio in 0.94..1.06)
        assertTrue("Expected continuous non-silent audio waveform", nonZeroSamples > totalInputFrames)
    }

    @Test
    fun speedScalingAndAudioProcessorChain_preservePitchAndScaleMediaDuration() {
        val sonic = RealTimeSonicAudioProcessor()
        val chain = RealTimeAudioProcessorChain(sonic)
        sonic.configure(stereo48k)
        sonic.flush()

        val customPitch = PracticeMath.computePitchFactor(4, 0)
        sonic.setTargetSpeedAndPitch(0.50f, customPitch)

        // DefaultAudioSink calls applyPlaybackParameters(PlaybackParameters(speed, 1.0f))
        chain.applyPlaybackParameters(PlaybackParameters(0.50f, 1.0f))
        assertEquals(0.50f, sonic.currentTargetSpeed, 0.0001f)
        assertEquals(customPitch, sonic.currentTargetPitch, 0.0001f)

        // Feed 1 second of 48kHz stereo tone at 0.50x speed -> ~2 seconds of output
        val inputFrames = 48_000
        val shorts = ShortArray(inputFrames * 2) { i ->
            (sin(2.0 * PI * 330.0 * (i / 2) / 48_000.0) * 14_000.0).toInt().toShort()
        }
        var outBytes = 0
        val chunkSizeShorts = 2048
        var offset = 0
        while (offset < shorts.size) {
            val len = minOf(chunkSizeShorts, shorts.size - offset)
            val buf = shortsToNativeBuffer(shorts.copyOfRange(offset, offset + len))
            sonic.queueInput(buf)
            val out = sonic.output
            outBytes += out.remaining()
            out.position(out.limit())
            offset += len
        }

        val mediaDurUs = chain.getMediaDuration(1_000_000L)
        assertTrue("Expected ~500_000us media duration at 0.50x speed, got $mediaDurUs", mediaDurUs in 450_000L..550_000L)

        sonic.queueEndOfStream()
        val tail = sonic.output
        outBytes += tail.remaining()
        tail.position(tail.limit())
        assertTrue(sonic.isEnded)

        val outFrames = outBytes / 4
        assertTrue("Expected ~96_000 output frames at 0.50x speed, got $outFrames", abs(outFrames - 96_000) < 4_000)
    }

    @Test
    fun fiveStemTimeDomainMixer_isolatesIndividualStemsMuteSoloBoost_andZeroLatencyPassthroughAtUnity() {
        val totalFrames = 8_192
        val sr = 44_100.0
        val freqs = doubleArrayOf(523.25, 329.63, 98.0, 196.0, 783.99) // [VOCALS, GUITAR, BASS, DRUMS, OTHER]
        val stems = Array(5) { s ->
            val l = FloatArray(totalFrames)
            val r = FloatArray(totalFrames)
            val f = freqs[s]
            for (i in 0 until totalFrames) {
                val env = 0.14 + 0.04 * sin(2.0 * PI * (3.0 + s) * i / sr)
                l[i] = (env * sin(2.0 * PI * f * i / sr + s * 0.4)).toFloat()
                r[i] = (env * cos(2.0 * PI * f * i / sr - s * 0.3)).toFloat()
            }
            arrayOf(l, r)
        }

        val cache = StemSeparatedTrackCache.fromFloatStemsInMemory(
            itemId = 501L,
            sampleRate = 44_100,
            stems5Stereo = stems,
        )

        val mixShorts = ShortArray(totalFrames * 2)
        for (i in 0 until totalFrames) {
            var sl = 0f
            var srVal = 0f
            for (s in 0 until 5) {
                sl += stems[s][0][i]
                srVal += stems[s][1][i]
            }
            mixShorts[i * 2] = (sl * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
            mixShorts[i * 2 + 1] = (srVal * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }

        val processor = RealTimeSonicAudioProcessor()
        processor.configure(stereo44k)
        processor.flush()
        processor.setSeparatedStemsContext(cache) { 0L }

        // 1. Unity stemMix -> bit-for-bit passthrough even when cache is bound
        val chunk0 = mixShorts.copyOfRange(0, 1024 * 2)
        processor.queueInput(shortsToNativeBuffer(chunk0))
        val unityOut = readShortsFromBuffer(processor.output)
        assertArrayEquals(chunk0, unityOut)

        // 2. Solo GUITAR -> zero-latency (exact 1024 frames out on first 1024-frame input!) matching isolated guitar stem
        processor.flush()
        processor.setStemMix(StemMixConfig(soloStem = StemType.GUITAR))
        processor.queueInput(shortsToNativeBuffer(chunk0))
        val guitarOut = readShortsFromBuffer(processor.output)
        assertEquals("Zero-latency time-domain stem mixer must emit exact frame count immediately", chunk0.size, guitarOut.size)

        var corrDot = 0.0
        var eExp = 0.0
        var eAct = 0.0
        for (i in 0 until 1024) {
            val expL = stems[1][0][i].toDouble()
            val actL = guitarOut[i * 2].toDouble() / 32768.0
            corrDot += expL * actL
            eExp += expL * expL
            eAct += actL * actL
        }
        val ncc = corrDot / sqrt(eExp * eAct)
        assertTrue("Isolated GUITAR output must match ground-truth GUITAR stem (ncc=$ncc)", ncc > 0.999)

        // 3. Mute VOCALS (karaoke backing mode) -> vocals removed while other 4 stems remain intact
        processor.flush()
        processor.setStemMix(StemMixConfig(mutedStems = setOf(StemType.VOCALS)))
        processor.queueInput(shortsToNativeBuffer(chunk0))
        val backingOut = readShortsFromBuffer(processor.output)
        for (i in 0 until 1024 step 31) {
            val expectedNoVocalsL = stems[1][0][i] + stems[2][0][i] + stems[3][0][i] + stems[4][0][i]
            assertEquals(expectedNoVocalsL, backingOut[i * 2] / 32768f, 0.015f)
        }

        // 4. Boost all stems to 150% on hot signal -> soft-clipper prevents harsh wrap-around
        val hotStems = Array(5) { Array(2) { FloatArray(256) { 0.35f } } }
        val hotCache = StemSeparatedTrackCache.fromFloatStemsInMemory(502L, 44_100, hotStems)
        processor.setSeparatedStemsContext(hotCache) { 0L }
        processor.flush()
        processor.setStemMix(
            StemMixConfig(
                vocals = 1.5f,
                guitar = 1.5f,
                bass = 1.5f,
                drums = 1.5f,
                other = 1.5f,
            ),
        )
        val hotIn = ShortArray(256 * 2) { 28_000 }
        processor.queueInput(shortsToNativeBuffer(hotIn))
        val hotOut = readShortsFromBuffer(processor.output)
        assertTrue(hotOut.all { it in 29_500..32_767 })
    }

    @Test
    fun playheadCrossCorrelationAlignment_recoversExactSampleOffsetFrom180msDrift_advancesContiguouslyAndResetsOnFlush() {
        val sr = 44_100
        val totalFrames = sr * 3 // 3.0 seconds
        // Synthesize realistic broadband non-periodic stems so cross-correlation has an unambiguous sharp peak
        var rng = 0x12345678L
        fun nextNoise(): Float {
            rng = rng * 6364136223846793005L + 1442695040888963407L
            return ((rng ushr 40).toInt() / 8388608.0f) - 1.0f
        }

        val stems = Array(5) { s ->
            val l = FloatArray(totalFrames)
            val r = FloatArray(totalFrames)
            var lpL = 0f
            var lpR = 0f
            val alpha = 0.08f + s * 0.06f
            for (i in 0 until totalFrames) {
                lpL += alpha * (nextNoise() * 0.28f - lpL)
                lpR += alpha * (nextNoise() * 0.28f - lpR)
                l[i] = lpL
                r[i] = lpR
            }
            arrayOf(l, r)
        }
        val cache = StemSeparatedTrackCache.fromFloatStemsInMemory(601L, sr, stems)

        val mixShorts = ShortArray(totalFrames * 2)
        for (i in 0 until totalFrames) {
            var sl = 0f
            var srVal = 0f
            for (s in 0 until 5) {
                sl += stems[s][0][i]
                srVal += stems[s][1][i]
            }
            mixShorts[i * 2] = (sl * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
            mixShorts[i * 2 + 1] = (srVal * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }

        var reportedPlayheadMs = 0L
        val processor = RealTimeSonicAudioProcessor()
        processor.configure(stereo44k)
        processor.flush()
        processor.setSeparatedStemsContext(cache) { reportedPlayheadMs }
        processor.setStemMix(StemMixConfig(soloStem = StemType.VOCALS))

        // Case A: True buffer starts at frame 50,000 (1133.79 ms), playhead supplier drifts -180 ms (954 ms)
        val trueStartA = 50_000
        val chunkFrames = 1_024
        reportedPlayheadMs = 954L
        processor.queueInput(
            shortsToNativeBuffer(mixShorts.copyOfRange(trueStartA * 2, (trueStartA + chunkFrames) * 2)),
        )
        readShortsFromBuffer(processor.output)
        assertEquals(
            "Cross-correlation must lock onto exact sample frame 50,000 + 1,024 despite -180ms playhead drift",
            (trueStartA + chunkFrames).toDouble(),
            processor.currentAlignedStemCursor,
            1.0,
        )

        // Contiguous next buffer advances cursor by exact 1,024 frames without re-searching
        processor.queueInput(
            shortsToNativeBuffer(mixShorts.copyOfRange((trueStartA + chunkFrames) * 2, (trueStartA + 2 * chunkFrames) * 2)),
        )
        readShortsFromBuffer(processor.output)
        assertEquals(
            (trueStartA + 2 * chunkFrames).toDouble(),
            processor.currentAlignedStemCursor,
            1.0,
        )

        // Case B: Seek / A-B loop flush -> first buffer is pure silence, second buffer is at frame 80,000 (+175 ms drift)
        processor.flush()
        assertFalse(processor.isStemCursorAligned)
        val trueStartB = 80_000 // 1814.06 ms
        reportedPlayheadMs = 1_989L // +175 ms drift
        val silentChunk = ShortArray(chunkFrames * 2)
        processor.queueInput(shortsToNativeBuffer(silentChunk))
        readShortsFromBuffer(processor.output)

        // Next non-silent chunk must trigger re-alignment to exact trueStartB!
        processor.queueInput(
            shortsToNativeBuffer(mixShorts.copyOfRange(trueStartB * 2, (trueStartB + chunkFrames) * 2)),
        )
        val outB = readShortsFromBuffer(processor.output)
        assertEquals(
            (trueStartB + chunkFrames).toDouble(),
            processor.currentAlignedStemCursor,
            1.0,
        )
        for (i in 0 until chunkFrames step 29) {
            assertEquals(stems[0][0][trueStartB + i], outB[i * 2] / 32768f, 0.015f)
        }
    }

    @Test
    fun stemMixingAt48kHzInputRateAndChainedPureSonicPitchSpeed_worksCleanly() {
        // Cache is always 44,100 Hz while video tracks often play at 48,000 Hz
        val cacheSr = 44_100
        val cacheFrames = cacheSr * 2
        val stems = Array(5) { s ->
            val l = FloatArray(cacheFrames)
            val r = FloatArray(cacheFrames)
            if (s == 2) { // BASS = 110 Hz
                for (i in 0 until cacheFrames) {
                    val v = (0.5 * sin(2.0 * PI * 110.0 * i / cacheSr)).toFloat()
                    l[i] = v
                    r[i] = v
                }
            }
            arrayOf(l, r)
        }
        val cache = StemSeparatedTrackCache.fromFloatStemsInMemory(701L, cacheSr, stems)

        val processor = RealTimeSonicAudioProcessor()
        processor.configure(stereo48k)
        processor.flush()
        processor.setSeparatedStemsContext(cache) { 0L }
        processor.setStemMix(StemMixConfig(soloStem = StemType.BASS))

        // 1. At 1.0x speed / 0st, 4,800 input frames @ 48 kHz -> exact 4,800 output frames advancing cursor by ~4,410 frames
        val in48k = ShortArray(4_800 * 2) { i ->
            (0.5 * sin(2.0 * PI * 110.0 * (i / 2) / 48_000.0) * 32767.0).roundToInt().toShort()
        }
        processor.queueInput(shortsToNativeBuffer(in48k))
        val out48k = readShortsFromBuffer(processor.output)
        assertEquals(4_800 * 2, out48k.size)
        assertEquals(4_410.0, processor.currentAlignedStemCursor, 2.0)

        // 2. Chained with pure Sonic (0.80x speed, -2 semitones): continuous non-silent time-stretched + pitch-shifted output
        processor.flush()
        processor.setTargetSpeedAndPitch(0.80f, PracticeMath.computePitchFactor(-2, 0))
        var totalOutShorts = 0
        for (chunk in 0 until 10) {
            processor.queueInput(shortsToNativeBuffer(in48k))
            totalOutShorts += readShortsFromBuffer(processor.output).size
        }
        processor.queueEndOfStream()
        totalOutShorts += readShortsFromBuffer(processor.output).size
        val outFrames = totalOutShorts / 2
        // 48,000 input frames at 0.80x speed -> ~60,000 output frames
        assertTrue("Expected ~60,000 output frames at 0.80x speed, got $outFrames", abs(outFrames - 60_000) < 3_500)
    }

    private fun shortsToNativeBuffer(shorts: ShortArray): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(shorts.size * 2).order(ByteOrder.nativeOrder())
        buf.asShortBuffer().put(shorts)
        buf.limit(shorts.size * 2)
        buf.position(0)
        return buf
    }

    private fun readShortsFromBuffer(buf: ByteBuffer): ShortArray {
        val dup = buf.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
        val out = ShortArray(dup.remaining())
        dup.get(out)
        buf.position(buf.limit())
        return out
    }
}
