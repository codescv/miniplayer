package com.miniplayer.app.player

import android.media.AudioFormat
import com.miniplayer.app.core.model.BeatGrid
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BpmDetectorTest {
    @Test
    fun detectBpmFromPcm_syntheticPulseTrainsAt80_100_120_140_160Bpm_areAccurate() {
        val targetBpms = listOf(80, 100, 120, 140, 160)
        for (expectedBpm in targetBpms) {
            val pcmMono = synthesizeDrumPulseTrack(
                bpm = expectedBpm,
                durationSeconds = 12.0,
                sampleRate = 44_100,
                channels = 1,
                includeEighthHiHat = false,
            )
            val detected = BpmDetector.detectBpmFromPcm(
                pcmInterleaved = pcmMono,
                frameCount = pcmMono.size,
                sampleRate = 44_100,
                channelCount = 1,
            )
            assertNotNull("Expected non-null BPM for $expectedBpm BPM pulse track", detected)
            assertTrue(
                "Expected ~$expectedBpm BPM (±1), got $detected",
                abs(detected!! - expectedBpm) <= 1,
            )
        }
    }

    @Test
    fun detectBpmFromPcm_stereoBackingDrumGroovesWithEighthHiHats_resolveTrueQuarterNoteBpm() {
        val targetBpms = listOf(80, 100, 120, 140, 160)
        for (expectedBpm in targetBpms) {
            val pcmStereo = synthesizeDrumPulseTrack(
                bpm = expectedBpm,
                durationSeconds = 14.0,
                sampleRate = 48_000,
                channels = 2,
                includeEighthHiHat = true,
            )
            val detected = BpmDetector.detectBpmFromPcm(
                pcmInterleaved = pcmStereo,
                frameCount = pcmStereo.size / 2,
                sampleRate = 48_000,
                channelCount = 2,
            )
            assertNotNull("Expected non-null BPM for $expectedBpm BPM stereo groove", detected)
            assertTrue(
                "Expected ~$expectedBpm BPM (±2) for stereo groove with 8th hi-hats, got $detected",
                abs(detected!! - expectedBpm) <= 2,
            )
        }
    }

    @Test
    fun onsetFluxAccumulator_streamingUnalignedByteBuffersAndFloatPcm_matchesBatchResult() {
        val stereo120 = synthesizeDrumPulseTrack(
            bpm = 120,
            durationSeconds = 10.0,
            sampleRate = 44_100,
            channels = 2,
            includeEighthHiHat = true,
        )
        val accumulator = OnsetFluxAccumulator(sampleRate = 44_100, channelCount = 2)
        // Feed in odd 513-short chunks (splitting stereo frames across buffer boundaries!)
        var offset = 0
        while (offset < stereo120.size) {
            val count = minOf(513, stereo120.size - offset)
            val byteBuf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                byteBuf.putShort(stereo120[offset + i])
            }
            accumulator.feedPcmByteBuffer(
                buffer = byteBuf,
                offset = 0,
                size = count * 2,
                pcmEncoding = AudioFormat.ENCODING_PCM_16BIT,
            )
            offset += count
        }
        val detected = accumulator.computeBpm()
        assertNotNull(detected)
        assertTrue("Expected ~120 BPM from unaligned streaming chunks, got $detected", abs(detected!! - 120) <= 1)
    }

    @Test
    fun silenceConstantToneAndShortClip_returnNullCleanly() {
        val silence = ShortArray(44_100 * 6)
        assertNull(BpmDetector.detectBpmFromPcm(silence, silence.size, 44_100, 1))

        val constantSine = ShortArray(44_100 * 6) { idx ->
            (sin(2.0 * PI * 440.0 * idx / 44_100.0) * 12_000.0).toInt().toShort()
        }
        assertNull(BpmDetector.detectBpmFromPcm(constantSine, constantSine.size, 44_100, 1))

        val tooShort = synthesizeDrumPulseTrack(120, durationSeconds = 1.0, sampleRate = 44_100, channels = 1, includeEighthHiHat = false)
        assertNull(BpmDetector.detectBpmFromPcm(tooShort, tooShort.size, 44_100, 1))
    }

    @Test
    fun embeddedHeaderBpmTags_parseId3v2TbpmAndFlacVorbisBpmAndRejectInvalidValues() {
        assertEquals(128, BpmDetector.parseBpmTagString("128"))
        assertEquals(96, BpmDetector.parseBpmTagString("96.2 BPM"))
        assertNull(BpmDetector.parseBpmTagString("0"))
        assertNull(BpmDetector.parseBpmTagString("999"))
        assertNull(BpmDetector.parseBpmTagString(""))

        // Construct minimal ID3v2.4 header with TBPM = "132"
        val id3Out = ByteArrayOutputStream()
        id3Out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0))
        val payload = byteArrayOf(3) + "132".toByteArray(StandardCharsets.UTF_8) // encoding 3 = UTF-8
        val tagSize = 10 + payload.size
        id3Out.write(byteArrayOf(0, 0, 0, tagSize.toByte())) // synchsafe size (<128)
        id3Out.write("TBPM".toByteArray(StandardCharsets.ISO_8859_1))
        id3Out.write(byteArrayOf(0, 0, 0, payload.size.toByte()))
        id3Out.write(byteArrayOf(0, 0)) // flags
        id3Out.write(payload)
        assertEquals(132, BpmDetector.extractEmbeddedBpmFromHeader(id3Out.toByteArray()))

        // Construct minimal FLAC header with VORBIS_COMMENT block (type 4, isLast=true -> 0x84) containing BPM=108
        val vendor = "MiniPlayer".toByteArray(StandardCharsets.UTF_8)
        val comment = "BPM=108".toByteArray(StandardCharsets.UTF_8)
        val blockBuf = ByteBuffer.allocate(4 + vendor.size + 4 + 4 + comment.size).order(ByteOrder.LITTLE_ENDIAN)
        blockBuf.putInt(vendor.size)
        blockBuf.put(vendor)
        blockBuf.putInt(1)
        blockBuf.putInt(comment.size)
        blockBuf.put(comment)
        val blockBytes = blockBuf.array()

        val flacOut = ByteArrayOutputStream()
        flacOut.write("fLaC".toByteArray(StandardCharsets.ISO_8859_1))
        flacOut.write(0x84)
        flacOut.write((blockBytes.size shr 16) and 0xFF)
        flacOut.write((blockBytes.size shr 8) and 0xFF)
        flacOut.write(blockBytes.size and 0xFF)
        flacOut.write(blockBytes)
        assertEquals(108, BpmDetector.extractEmbeddedBpmFromHeader(flacOut.toByteArray()))
    }

    @Test
    fun computeBeatGrid_detectsBpmFirstBeatAndDownbeatWithPickupSilence() {
        val sampleRate = 44_100
        val bpm = 120 // beat = 500ms, 4/4 bar = 2000ms
        val pickupSilenceMs = 820L
        val durationSec = 14.0
        val totalFrames = (durationSec * sampleRate).toInt()
        val pcm = ShortArray(totalFrames)
        val beatPeriodSec = 60.0 / bpm
        val pickupSec = pickupSilenceMs / 1000.0
        val totalBeats = ((durationSec - pickupSec) / beatPeriodSec).toInt()

        for (beat in 0 until totalBeats) {
            val beatInBar = beat % 4
            val startFrame = ((pickupSec + beat * beatPeriodSec) * sampleRate).toInt()
            val freq = when (beatInBar) {
                0 -> 88.0   // Strong Beat 1 downbeat kick
                2 -> 215.0  // Beat 3 snare backbeat
                else -> 160.0
            }
            val amp = when (beatInBar) {
                0 -> 28_000.0
                2 -> 22_000.0
                else -> 13_500.0
            }
            val hitLen = (0.045 * sampleRate).toInt()
            for (i in 0 until hitLen) {
                val f = startFrame + i
                if (f >= totalFrames) break
                val env = exp(-i.toDouble() / (0.011 * sampleRate))
                pcm[f] = (sin(2.0 * PI * freq * i / sampleRate) * amp * env)
                    .toInt().coerceIn(-32767, 32767).toShort()
            }
        }

        val grid = BpmDetector.detectBeatGridFromPcm(
            pcmInterleaved = pcm,
            frameCount = totalFrames,
            sampleRate = sampleRate,
            channelCount = 1,
        )
        assertNotNull("Expected valid BeatGrid for 120 BPM 4/4 groove", grid)
        assertTrue("Expected ~120 BPM (±1), got ${grid!!.bpm}", abs(grid.bpm - 120) <= 1)
        assertEquals(4, grid.beatsPerBar)
        // 820ms is inside [0, 2000ms), so folded firstDownbeatMs should align near 820ms (±75ms)
        assertTrue(
            "Expected firstDownbeatMs near 820ms (±75ms), got ${grid.firstDownbeatMs}ms",
            abs(grid.firstDownbeatMs - pickupSilenceMs) <= 75L,
        )
    }

    @Test
    fun beatGridBarSnappingAndWaveformCacheCodec_behaveDeterministically() {
        val grid = BeatGrid(bpm = 120, firstBeatMs = 200L, firstDownbeatMs = 200L, beatsPerBar = 4)
        assertEquals(500.0, grid.beatDurationMs, 0.001)
        assertEquals(2000.0, grid.barDurationMs, 0.001)

        val bars = grid.generateBarBoundaries(10_500L)
        assertEquals(listOf(200L, 2_200L, 4_200L, 6_200L, 8_200L, 10_200L), bars)

        // snapPointA: within first 75% of measure [2200..4200] -> snaps to start of current measure (2200L);
        // in last 25% (>= 3700L) -> snaps forward to next measure boundary (4200L)
        assertEquals(2_200L, grid.snapPointA(positionMs = 2_900L, durationMs = 20_000L))
        assertEquals(4_200L, grid.snapPointA(positionMs = 3_920L, durationMs = 20_000L))

        // snapPointB: enforces >= 1 full bar after A, and snaps to nearest bar boundary
        assertEquals(4_200L, grid.snapPointB(aMs = 2_200L, positionMs = 2_600L, durationMs = 20_000L))
        assertEquals(10_200L, grid.snapPointB(aMs = 2_200L, positionMs = 10_050L, durationMs = 20_000L))
        assertEquals(4, grid.countBarsBetween(2_200L, 10_200L))

        // snapDraggedLoopPoints: dragging A or B snaps to bar boundaries while keeping >= 1 full bar span
        assertEquals(4_200L to 10_200L, grid.snapDraggedLoopPoints(4_080L, 10_200L, "A", 20_000L))
        assertEquals(8_200L to 10_200L, grid.snapDraggedLoopPoints(9_900L, 10_200L, "A", 20_000L))
        assertEquals(4_200L to 6_200L, grid.snapDraggedLoopPoints(4_200L, 4_600L, "B", 20_000L))

        // WaveformExtractor backward-compatible cache parser ("120" vs "128,180,180,4")
        val legacyParsed = WaveformExtractor.parseCachedBeatGrid("120")
        assertEquals(BeatGrid(bpm = 120, firstBeatMs = 0L, firstDownbeatMs = 0L, beatsPerBar = 4), legacyParsed)

        val serialized = WaveformExtractor.formatCachedBeatGrid(
            BeatGrid(bpm = 128, firstBeatMs = 180L, firstDownbeatMs = 180L, beatsPerBar = 4),
        )
        assertEquals("128,180,180,4", serialized)
        assertEquals(
            BeatGrid(bpm = 128, firstBeatMs = 180L, firstDownbeatMs = 180L, beatsPerBar = 4),
            WaveformExtractor.parseCachedBeatGrid(serialized),
        )
    }

    private fun synthesizeDrumPulseTrack(
        bpm: Int,
        durationSeconds: Double,
        sampleRate: Int,
        channels: Int,
        includeEighthHiHat: Boolean,
    ): ShortArray {
        val totalFrames = (durationSeconds * sampleRate).toInt()
        val out = ShortArray(totalFrames * channels)
        val beatPeriodSec = 60.0 / bpm.toDouble()
        val totalBeats = (durationSeconds / beatPeriodSec).toInt()

        for (beat in 0..totalBeats) {
            val beatStartFrame = (beat * beatPeriodSec * sampleRate).toInt()
            val isKick = (beat % 2 == 0)
            val freq = if (isKick) 95.0 else 210.0
            val amp = if (isKick) 26_000.0 else 22_000.0
            val hitLen = (0.045 * sampleRate).toInt()
            for (i in 0 until hitLen) {
                val frame = beatStartFrame + i
                if (frame >= totalFrames) break
                val env = exp(-i.toDouble() / (0.012 * sampleRate))
                val sample = (sin(2.0 * PI * freq * i / sampleRate) * amp * env).toInt().coerceIn(-32767, 32767).toShort()
                for (c in 0 until channels) {
                    out[frame * channels + c] = sample
                }
            }

            if (includeEighthHiHat) {
                val offBeatFrame = ((beat + 0.5) * beatPeriodSec * sampleRate).toInt()
                val hatLen = (0.020 * sampleRate).toInt()
                for (i in 0 until hatLen) {
                    val frame = offBeatFrame + i
                    if (frame >= totalFrames) break
                    val env = exp(-i.toDouble() / (0.005 * sampleRate))
                    val metallic = sin(2.0 * PI * 3_400.0 * i / sampleRate) * 0.6 +
                        sin(2.0 * PI * 5_100.0 * i / sampleRate) * 0.4
                    val sample = (metallic * 6_500.0 * env).toInt().coerceIn(-32767, 32767).toShort()
                    for (c in 0 until channels) {
                        val existing = out[frame * channels + c].toInt()
                        out[frame * channels + c] = (existing + sample).coerceIn(-32767, 32767).toShort()
                    }
                }
            }
        }
        return out
    }
}
