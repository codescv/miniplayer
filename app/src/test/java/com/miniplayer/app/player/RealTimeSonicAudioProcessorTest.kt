package com.miniplayer.app.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import com.google.common.collect.ImmutableList
import com.miniplayer.app.core.model.PracticeMath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
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
