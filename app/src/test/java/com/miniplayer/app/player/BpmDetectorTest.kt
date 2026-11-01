package com.miniplayer.app.player

import android.media.AudioFormat
import com.miniplayer.app.core.model.BeatGrid
import com.miniplayer.app.core.model.BeatGridConfidence
import com.miniplayer.app.core.model.BeatGridSource
import com.miniplayer.app.core.model.PracticeState
import com.miniplayer.app.player.SyntheticBackingTrack.Companion.A
import com.miniplayer.app.player.SyntheticBackingTrack.Companion.C
import com.miniplayer.app.player.SyntheticBackingTrack.Companion.Ch
import com.miniplayer.app.player.SyntheticBackingTrack.Companion.D
import com.miniplayer.app.player.SyntheticBackingTrack.Companion.F
import com.miniplayer.app.player.SyntheticBackingTrack.Companion.G
import com.miniplayer.app.player.beat.BeatGridAnalysis
import com.miniplayer.app.player.beat.BpmOctave
import com.miniplayer.app.player.beat.RhythmPattern
import com.miniplayer.app.player.beat.TempoFit
import com.miniplayer.app.player.beat.TempoRegression
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // Unfolded anchor: any true downbeat (820 + k·2000 ms) is valid; bar phase must match (±25 ms).
        assertTrue(
            "Expected downbeat bar phase ≡ 820ms (±25ms), got ${grid.firstDownbeatMs}ms",
            barPhaseError(grid.firstDownbeatMs, pickupSilenceMs.toDouble(), grid.barDurationMs) <= 25.0,
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

        // WaveformExtractor backward-compatible cache parser ("120" / "128,180,180,4" -> AUTO_LEGACY)
        val legacyParsed = WaveformExtractor.parseCachedBeatGrid("120")
        assertEquals(
            BeatGrid(bpmPrecise = 120.0, firstBeatMs = 0L, firstDownbeatMs = 0L, source = BeatGridSource.AUTO_LEGACY),
            legacyParsed,
        )
        assertEquals(BeatGridSource.AUTO_LEGACY, WaveformExtractor.parseCachedBeatGrid("128,180,180,4")!!.source)

        // bpm_v4 codec round-trip (fractional tempo, unfolded/negative anchor, meter, phrase, confidence)
        val v4 = BeatGrid(
            bpmPrecise = 120.3871,
            firstBeatMs = 180L,
            firstDownbeatMs = 16_180L,
            beatsPerBar = 3,
            phraseOffsetBars = 2,
            confidence = BeatGridConfidence(
                tempoClarity = 0.5f,
                tempoStable = false,
                downbeatMargin = 3.25f,
                halfBarAmbiguous = true,
                attackOffsetMs = -14.5f,
                meterIrregular = true,
            ),
            source = BeatGridSource.AUTO_STEMS,
        )
        val serialized = WaveformExtractor.formatCachedBeatGrid(v4)
        assertEquals("4,120.3871,180,16180,3,2,0.500,3.250,-14.50,AUTO_STEMS,6", serialized)
        assertEquals(v4, WaveformExtractor.parseCachedBeatGrid(serialized))
    }

    @Test
    fun computeBeatGrid_snareDominantBackbeatWithChordBed_locksOntoKickBeatOneDownbeat() {
        val sampleRate = 44_100
        // Test rock/pop/blues grooves where Snare on Beats 2 & 4 has higher broadband amplitude
        // than Kick on Beats 1 & 3, plus 8th-note hi-hats and a continuous sustained guitar/pad bed.
        for (targetBpm in listOf(96, 116)) {
            val pickupOffsetMs = 640L
            val durationSec = 18.0
            val totalFrames = (durationSec * sampleRate).toInt()
            val pcm = ShortArray(totalFrames)
            val beatPeriodSec = 60.0 / targetBpm.toDouble()
            val pickupSec = pickupOffsetMs / 1000.0
            val totalBeats = ((durationSec - pickupSec) / beatPeriodSec).toInt()

            // Continuous sustained chord bed (starts at pickupSec)
            val startBedFrame = (pickupSec * sampleRate).toInt()
            for (f in startBedFrame until totalFrames) {
                val t = f.toDouble() / sampleRate
                val chord = sin(2.0 * PI * 330.0 * t) * 0.45 +
                    sin(2.0 * PI * 440.0 * t) * 0.35 +
                    sin(2.0 * PI * 659.25 * t) * 0.20
                pcm[f] = (chord * 3_200.0).toInt().toShort()
            }

            for (beat in 0 until totalBeats) {
                val beatInBar = beat % 4
                val startFrame = ((pickupSec + beat * beatPeriodSec) * sampleRate).toInt()
                val hitLen = (0.055 * sampleRate).toInt()

                if (beatInBar == 0 || beatInBar == 2) {
                    // Kick + Bass on Beat 1 (stronger) and Beat 3 (medium)
                    val kickAmp = if (beatInBar == 0) 19_500.0 else 14_500.0
                    val bassAmp = if (beatInBar == 0) 6_500.0 else 4_000.0
                    for (i in 0 until hitLen) {
                        val f = startFrame + i
                        if (f >= totalFrames) break
                        val env = exp(-i.toDouble() / (0.015 * sampleRate))
                        val kick = sin(2.0 * PI * 76.0 * i / sampleRate) * kickAmp * env +
                            sin(2.0 * PI * 110.0 * i / sampleRate) * bassAmp * env
                        pcm[f] = (pcm[f].toInt() + kick.toInt()).coerceIn(-32767, 32767).toShort()
                    }
                } else {
                    // Snare backbeat on Beats 2 & 4 with HIGHER broadband peak than Kick!
                    val snareAmp = 26_000.0
                    for (i in 0 until hitLen) {
                        val f = startFrame + i
                        if (f >= totalFrames) break
                        val env = exp(-i.toDouble() / (0.010 * sampleRate))
                        val snare = (
                            sin(2.0 * PI * 235.0 * i / sampleRate) * 0.35 +
                                sin(2.0 * PI * 1_650.0 * i / sampleRate) * 0.40 +
                                sin(2.0 * PI * 3_900.0 * i / sampleRate) * 0.25
                            ) * snareAmp * env
                        pcm[f] = (pcm[f].toInt() + snare.toInt()).coerceIn(-32767, 32767).toShort()
                    }
                }

                // 8th-note hi-hat on both downbeat and offbeat (&)
                for (sub in 0..1) {
                    val hatStart = ((pickupSec + (beat + sub * 0.5) * beatPeriodSec) * sampleRate).toInt()
                    val hatLen = (0.018 * sampleRate).toInt()
                    val hatAmp = if (sub == 0) 5_200.0 else 4_200.0
                    for (i in 0 until hatLen) {
                        val f = hatStart + i
                        if (f >= totalFrames) break
                        val env = exp(-i.toDouble() / (0.004 * sampleRate))
                        val hat = (
                            sin(2.0 * PI * 4_600.0 * i / sampleRate) * 0.55 +
                                sin(2.0 * PI * 6_900.0 * i / sampleRate) * 0.45
                            ) * hatAmp * env
                        pcm[f] = (pcm[f].toInt() + hat.toInt()).coerceIn(-32767, 32767).toShort()
                    }
                }
            }

            val grid = BpmDetector.detectBeatGridFromPcm(
                pcmInterleaved = pcm,
                frameCount = totalFrames,
                sampleRate = sampleRate,
                channelCount = 1,
            )
            assertNotNull("Expected BeatGrid for $targetBpm BPM snare-backbeat groove", grid)
            assertTrue(
                "Expected ~$targetBpm BPM (±1), got ${grid!!.bpm}",
                abs(grid.bpm - targetBpm) <= 1,
            )
            // Beat 1 is at 640ms; Beat 2 (Snare) is at 640 + beatDurationMs (~1265ms at 96 BPM).
            // Downbeat detector MUST lock onto the Beat 1 bar phase, never Beat 2 or Beat 4!
            assertTrue(
                "Expected downbeat bar phase ≡ Kick Beat 1 (${pickupOffsetMs}ms ±25ms) for $targetBpm BPM, got ${grid.firstDownbeatMs}ms",
                barPhaseError(grid.firstDownbeatMs, pickupOffsetMs.toDouble(), grid.barDurationMs) <= 25.0,
            )
        }
    }

    @Test
    fun detectBpmFromPcm_slowBalladGroovesWithStrongEighthSubdivisions_doNotDoubleBpm() {
        val sampleRate = 44_100
        for (expectedBpm in listOf(72, 82)) {
            val pcm = synthesizeDrumPulseTrack(
                bpm = expectedBpm,
                durationSeconds = 18.0,
                sampleRate = sampleRate,
                channels = 1,
                includeEighthHiHat = true,
            )
            val detected = BpmDetector.detectBpmFromPcm(
                pcmInterleaved = pcm,
                frameCount = pcm.size,
                sampleRate = sampleRate,
                channelCount = 1,
            )
            assertNotNull("Expected non-null BPM for $expectedBpm BPM slow groove", detected)
            assertTrue(
                "Expected ~$expectedBpm BPM (±1) without octave doubling to ${expectedBpm * 2}, got $detected",
                abs(detected!! - expectedBpm) <= 1,
            )
        }
    }

    @Test
    fun computeBeatGrid_syncopatedKickGrooveAndAmbientIntroWithWeakPickup_locksOntoTrueBarOne() {
        val sampleRate = 44_100
        val bpm = 120 // beat = 500ms, bar = 2000ms
        val durationSec = 22.0
        val totalFrames = (durationSec * sampleRate).toInt()
        val pcm = ShortArray(totalFrames)
        val beatPeriodSec = 60.0 / bpm

        // 0.0s .. 5.0s: quiet ambient pad (no drums)
        val fiveSecFrames = (5.0 * sampleRate).toInt()
        for (f in 0 until fiveSecFrames) {
            val t = f.toDouble() / sampleRate
            pcm[f] = (sin(2.0 * PI * 261.63 * t) * 1_600.0).toInt().toShort()
        }

        // 5.0s and 5.5s: 2 weak hi-hat pickup clicks (Beats 3 & 4 of pickup measure before Bar 1 at 6.0s)
        for (pickupBeat in listOf(5.0, 5.5)) {
            val startF = (pickupBeat * sampleRate).toInt()
            val len = (0.020 * sampleRate).toInt()
            for (i in 0 until len) {
                val env = exp(-i.toDouble() / (0.004 * sampleRate))
                pcm[startF + i] = (sin(2.0 * PI * 4_800.0 * i / sampleRate) * 4_500.0 * env).toInt().toShort()
            }
        }

        // 6.0s .. 22.0s: Full-band syncopated 4/4 groove (Bar 1 Beat 1 at 6000ms -> 6000 % 2000 == 0ms phase!)
        // Kick on Beat 0.0 (Beat 1) and syncopated Beat 2.5 (3-&), Snare on Beat 1.0 (Beat 2) and Beat 3.0 (Beat 4).
        val grooveStartSec = 6.0
        val totalBars = ((durationSec - grooveStartSec) / (4.0 * beatPeriodSec)).toInt()
        for (bar in 0 until totalBars) {
            val barStartSec = grooveStartSec + bar * 4.0 * beatPeriodSec
            // Kick hits at 0.0 (Beat 1) and 2.5 (syncopated 3-&)
            for ((beatOffset, amp) in listOf(0.0 to 25_000.0, 2.5 to 17_000.0)) {
                val startF = ((barStartSec + beatOffset * beatPeriodSec) * sampleRate).toInt()
                val len = (0.050 * sampleRate).toInt()
                for (i in 0 until len) {
                    val f = startF + i
                    if (f >= totalFrames) break
                    val env = exp(-i.toDouble() / (0.014 * sampleRate))
                    val sample = (sin(2.0 * PI * 80.0 * i / sampleRate) * amp * env).toInt()
                    pcm[f] = (pcm[f].toInt() + sample).coerceIn(-32767, 32767).toShort()
                }
            }
            // Snare hits at 1.0 (Beat 2) and 3.0 (Beat 4)
            for (beatOffset in listOf(1.0, 3.0)) {
                val startF = ((barStartSec + beatOffset * beatPeriodSec) * sampleRate).toInt()
                val len = (0.045 * sampleRate).toInt()
                for (i in 0 until len) {
                    val f = startF + i
                    if (f >= totalFrames) break
                    val env = exp(-i.toDouble() / (0.010 * sampleRate))
                    val sample = (
                        (sin(2.0 * PI * 220.0 * i / sampleRate) * 0.4 +
                            sin(2.0 * PI * 2_200.0 * i / sampleRate) * 0.6) * 23_000.0 * env
                        ).toInt()
                    pcm[f] = (pcm[f].toInt() + sample).coerceIn(-32767, 32767).toShort()
                }
            }
        }

        val grid = BpmDetector.detectBeatGridFromPcm(
            pcmInterleaved = pcm,
            frameCount = totalFrames,
            sampleRate = sampleRate,
            channelCount = 1,
        )
        assertNotNull("Expected BeatGrid for syncopated groove after ambient intro", grid)
        assertTrue("Expected ~120 BPM (±1), got ${grid!!.bpm}", abs(grid.bpm - 120) <= 1)
        // Bar 1 Beat 1 is at 6000ms: the unfolded anchor must sit on that bar phase, not the 5000ms pickup.
        assertTrue(
            "Expected downbeat bar phase ≡ 6000ms (±25ms) rather than the 5000ms pickup, got ${grid.firstDownbeatMs}ms",
            barPhaseError(grid.firstDownbeatMs, 6_000.0, grid.barDurationMs) <= 25.0,
        )
        assertTrue("Expected anchor at/after the groove entry, got ${grid.firstDownbeatMs}", grid.firstDownbeatMs >= 5_900L)
    }

    @Test
    fun resolveEmbeddedAndAcousticBpm_honorsConsistentTagAndRejectsBogusTag() {
        val pcm92 = synthesizeDrumPulseTrack(
            bpm = 92,
            durationSeconds = 12.0,
            sampleRate = 44_100,
            channels = 1,
            includeEighthHiHat = true,
        )
        val acc92 = OnsetFluxAccumulator(sampleRate = 44_100, channelCount = 1).apply {
            feedInterleavedPcm(pcm92, frameCount = pcm92.size)
        }
        val acoustic = acc92.computeBpmWithClarity()!!.exactBpm
        assertEquals(92.0, acoustic, 0.6)
        // 1. Consistent embedded tag keeps the acoustic (regressed) tempo, never the tag value itself
        assertEquals(acoustic, BpmDetector.resolveEmbeddedAndAcousticBpm(92.0, acoustic)!!, 1e-9)
        // 2. Double-time embedded tag (184) only selects the octave of the acoustic tempo
        assertEquals(acoustic * 2.0, BpmDetector.resolveEmbeddedAndAcousticBpm(184.0, acoustic)!!, 1e-9)
        // 3. Bogus default transcode tag (120) is ignored
        assertEquals(acoustic, BpmDetector.resolveEmbeddedAndAcousticBpm(120.0, acoustic)!!, 1e-9)
        // 4. No acoustic estimate defers to the valid embedded tag
        assertEquals(120.0, BpmDetector.resolveEmbeddedAndAcousticBpm(120.0, null)!!, 1e-9)
    }

    private fun barPhaseError(anchorMs: Long, trueDownbeatMs: Double, barMs: Double): Double =
        phaseErrorMs(anchorMs.toDouble(), trueDownbeatMs, barMs)

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

    // ---------------------------------------------------------------------------------------------
    // BeatGrid v2 synthetic cases (§4 of beatgrid_v2_design.md Rev 3)
    // ---------------------------------------------------------------------------------------------

    private val sr = 44_100

    private fun analyze(track: SyntheticBackingTrack, windowSec: Double = 45.0, stereo: Boolean = false, tag: Double? = null): BeatGridAnalysis {
        val frames = minOf(track.frames, (windowSec * track.sampleRate).toInt())
        val pcm = if (stereo) track.toStereoPcm16() else track.toPcm16()
        val ch = if (stereo) 2 else 1
        val res = BpmDetector.detectBeatGridDetailedFromPcm(pcm, frames, track.sampleRate, ch, embeddedBpm = tag)
        assertNotNull("analysis must succeed", res)
        return res!!
    }

    private fun describe(a: BeatGridAnalysis): String {
        val g = a.grid
        val d = a.diagnostics
        return "bpm=${"%.4f".format(g.bpmPrecise)} anchor=${g.firstDownbeatMs} bpb=${g.beatsPerBar} phraseOff=${g.phraseOffsetBars} " +
            "conf=${g.confidence} pattern=${d.pattern} harm=${d.harmRhythm} drum=${"%.2f".format(d.drumPresence)} " +
            "fallback=${d.chromaFallbackBeats} empty=${d.emptyChromaBeats} anchorBeat=${d.anchorBeatIndex} fit=${d.tempoFit}"
    }

    private fun uniform(startMs: Double, bpm: Double): (Int) -> Double = { b -> startMs + b * 60_000.0 / bpm }

    // ---------------------------------------------------------------------------------------------

    @Test
    fun case01_fractional120_40_loop_kick1EqualsKick3_preciseTempoAnchorPhraseAndDrift() {
        val bpm = 120.40
        val start = 250.0
        val dur = 210.0
        val t = SyntheticBackingTrack(sr, dur)
        val bt = uniform(start, bpm)
        val beats = ((dur * 1000 - start) / (60_000 / bpm)).toInt() - 1
        val w = GrooveWriter(t, bt)
        w.drums(0, beats, kickAmps = mapOf(0 to 0.55, 2 to 0.55))
        w.harmony(0, beats, listOf(Ch(C), Ch(G), Ch(A, true), Ch(F)), beatsPerChord = 4)
        val a = analyze(t)
        val g = a.grid
        val info = describe(a)
        assertEquals(info, bpm, g.bpmPrecise, 0.02)
        assertEquals(4, g.beatsPerBar)
        val barMs = 4 * 60_000.0 / bpm
        assertTrue("anchor bar phase ±8ms: $info", phaseErrorMs(g.firstDownbeatMs.toDouble(), start, barMs) <= 8.0)
        // Phrase (4 bars, starting on C) correct: the C bars are phrase starts.
        val cBar = g.barIndexAt((start + 4 * barMs + 50).toLong()) // bar 4 = C again
        assertTrue("phrase start on C bars: $info", g.isPhraseStart(cBar))
        assertFalse("G bar is not a phrase start: $info", g.isPhraseStart(cBar + 1))
        // Drift at 3:30 < 15 ms.
        val lastTrueDownbeat = start + ((205_000 - start) / barMs).toInt() * barMs
        val k = ((lastTrueDownbeat - g.firstDownbeatMs) / g.barDurationMs).let { Math.round(it) }
        val predicted = g.firstDownbeatMs + k * g.barDurationMs
        assertTrue("drift at 3:30 = ${predicted - lastTrueDownbeat}: $info", abs(predicted - lastTrueDownbeat) < 15.0)
    }

    @Test
    fun case02_hatCountInAndQuietSnarePickup_beat1IsFirstBandHit() {
        val bpm = 95.50
        val start = 400.0
        val t = SyntheticBackingTrack(sr, 40.0)
        val bt = uniform(start, bpm)
        for (b in 0 until 4) t.hat(bt(b), amp = 0.25, decaySec = 0.01) // 4 hi-hat count-in clicks
        for (b in 4 until 6) t.snare(bt(b), amp = 0.35 * 0.25) // 2-beat pickup at 25 % energy
        val bandStart = 6
        val beats = ((40_000 - start) / (60_000 / bpm)).toInt() - 1
        val w = GrooveWriter(t, { b -> bt(b + bandStart) })
        w.drums(0, beats - bandStart)
        w.harmony(0, beats - bandStart, listOf(Ch(A, true), Ch(F), Ch(C), Ch(G)), beatsPerChord = 4)
        t.crash(bt(bandStart))
        val a = analyze(t)
        val info = describe(a)
        val barMs = 4 * 60_000.0 / bpm
        assertTrue("Beat 1 = first band hit: $info", phaseErrorMs(a.grid.firstDownbeatMs.toDouble(), bt(bandStart), barMs) <= 15.0)
    }

    @Test
    fun case03_dawClickCountIn_isAbsorbedByPre() {
        val bpm = 110.0
        val start = 300.0
        val t = SyntheticBackingTrack(sr, 40.0)
        val bt = uniform(start, bpm)
        for (b in 0 until 6) t.dawClick(bt(b), freq = if (b % 4 == 0) 1000.0 else 880.0)
        val bandStart = 6
        val beats = ((40_000 - start) / (60_000 / bpm)).toInt() - 1
        val w = GrooveWriter(t, { b -> bt(b + bandStart) })
        w.drums(0, beats - bandStart)
        w.harmony(0, beats - bandStart, listOf(Ch(C), Ch(F), Ch(G), Ch(C)), beatsPerChord = 4)
        val a = analyze(t)
        val info = describe(a)
        val barMs = 4 * 60_000.0 / bpm
        assertTrue("bar phase on band entry: $info", phaseErrorMs(a.grid.firstDownbeatMs.toDouble(), bt(bandStart), barMs) <= 15.0)
        assertTrue("clicks stay in PRE (anchor not before band): $info", a.grid.firstDownbeatMs >= bt(bandStart) - 15.0)
    }

    private fun halfBarTrack(prog: List<Ch>, fills: Boolean, bpm: Double = 112.0, start: Double = 350.0, dur: Double = 46.0): Pair<SyntheticBackingTrack, (Int) -> Double> {
        val t = SyntheticBackingTrack(sr, dur)
        val bt = uniform(start, bpm)
        val beats = ((dur * 1000 - start) / (60_000 / bpm)).toInt() - 1
        val w = GrooveWriter(t, bt)
        w.drums(0, beats, kickAmps = mapOf(0 to 0.55, 2 to 0.55))
        w.harmony(0, beats, prog, beatsPerChord = 2)
        if (fills) w.fills(0, beats, everyBars = 4)
        return t to bt
    }

    @Test
    fun case04_halfBarHarmony_fillsResolveBeat1_withoutCuesFlaggedAmbiguous() {
        val prog = listOf(Ch(C), Ch(F), Ch(A, true), Ch(G))
        val (t1, bt) = halfBarTrack(prog, fills = true)
        val a1 = analyze(t1)
        val barMs = 4 * 60_000.0 / 112.0
        assertTrue("fills -> correct Beat 1: ${describe(a1)}", phaseErrorMs(a1.grid.firstDownbeatMs.toDouble(), bt(0), barMs) <= 15.0)
        assertEquals(describe(a1), 2, a1.diagnostics.harmRhythm)
        val (t2, _) = halfBarTrack(prog, fills = false)
        val a2 = analyze(t2)
        assertTrue("no fills -> halfBarAmbiguous: ${describe(a2)}", a2.grid.confidence.halfBarAmbiguous)
    }

    @Test
    fun case05_halfTimeBallad_patternBWinsAndBeat1Correct() {
        val bpm = 76.0
        val start = 500.0
        val dur = 46.0
        val t = SyntheticBackingTrack(sr, dur)
        val bt = uniform(start, bpm)
        val beats = ((dur * 1000 - start) / (60_000 / bpm)).toInt() - 1
        val w = GrooveWriter(t, bt)
        // Half-time feel: kick on 1, snare on 3, quarter-note hats (no 8ths, so the quarter is 76 BPM).
        w.drums(0, beats, kickBeats = setOf(0), snareBeats = setOf(2), hats = false)
        for (b in 0 until beats) t.hat(bt(b), 0.06)
        w.harmony(0, beats, listOf(Ch(C), Ch(A, true), Ch(F), Ch(G)), beatsPerChord = 4)
        // A half-time groove's tempo octave (76 vs 152) is inherently ambiguous to Stage 2; the embedded
        // tag resolves the octave only (§3.0). The case under test is the P-B pattern decision.
        val a = analyze(t, tag = 76.0)
        val info = describe(a)
        assertEquals(info, bpm, a.grid.bpmPrecise, 0.05)
        assertEquals(info, RhythmPattern.HALF_TIME, a.diagnostics.pattern)
        assertTrue("Beat 1: $info", phaseErrorMs(a.grid.firstDownbeatMs.toDouble(), start, a.grid.barDurationMs) <= 15.0)
    }

    @Test
    fun case06_drumlessStrummedGuitar_lowDrumPresenceAndBeat1OnChordChange() {
        val bpm = 100.0
        val start = 300.0
        val dur = 46.0
        val t = SyntheticBackingTrack(sr, dur)
        val bt = uniform(start, bpm)
        val beats = ((dur * 1000 - start) / (60_000 / bpm)).toInt() - 1
        val prog = listOf(Ch(G), Ch(D), Ch(A, true), Ch(C))
        for (b in 0 until beats) {
            val bar = b / 4
            val ch = prog[bar % prog.size]
            val bib = b % 4
            if (bib == 0 || bib == 2) {
                t.chord(bt(b), bt(b + 2) - bt(b), ch.pc, ch.minor, amp = 0.06, attackMs = 3.0)
            } else {
                t.snare(bt(b), amp = 0.10) // muted "chuck" on 2 & 4 (noise burst, no kick)
            }
        }
        val a = analyze(t)
        val info = describe(a)
        assertTrue("low drumPresence: $info", a.diagnostics.drumPresence < 0.3)
        assertTrue("Beat 1 on chord change: $info", phaseErrorMs(a.grid.firstDownbeatMs.toDouble(), start, a.grid.barDurationMs) <= 15.0)
    }

    @Test
    fun case07_singleChordFunkVamp_halfBarAmbiguousAndBeat1AtEntrance() {
        val bpm = 104.0
        val start = 600.0
        val dur = 46.0
        val t = SyntheticBackingTrack(sr, dur)
        val bt = uniform(start, bpm)
        val beats = ((dur * 1000 - start) / (60_000 / bpm)).toInt() - 1
        GrooveWriter(t, bt).drums(0, beats, kickAmps = mapOf(0 to 0.55, 2 to 0.55))
        t.chord(bt(0), bt(beats) - bt(0), 2, true, amp = 0.05)
        val a = analyze(t)
        val info = describe(a)
        assertTrue("halfBarAmbiguous: $info", a.grid.confidence.halfBarAmbiguous)
        assertTrue("Beat 1 = band entrance: $info", phaseErrorMs(a.grid.firstDownbeatMs.toDouble(), start, a.grid.barDurationMs) <= 15.0)
    }

    @Test
    fun case08_quietPianoIntroThenBandDrop_anchorOnDropAndBarsExtendBack() {
        val bpm = 120.0
        val t = SyntheticBackingTrack(sr, 46.0)
        val bt = uniform(0.0, bpm)
        val prog = listOf(Ch(C), Ch(A, true), Ch(F), Ch(G))
        GrooveWriter(t, bt).harmony(0, 32, prog, beatsPerChord = 4, padAmp = 0.025, withBass = false)
        val beats = 46_000 / 500 - 1
        val w = GrooveWriter(t, bt)
        w.drums(32, beats)
        w.harmony(32, beats, listOf(Ch(F), Ch(G), Ch(C), Ch(A, true)), beatsPerChord = 4)
        t.crash(bt(32))
        val a = analyze(t)
        val info = describe(a)
        val g = a.grid
        assertTrue("anchor phase on the drop (16 s): $info", phaseErrorMs(g.firstDownbeatMs.toDouble(), 16_000.0, g.barDurationMs) <= 15.0)
        val bars = g.generateBarBoundaries(40_000L)
        assertTrue("bars extend back over the intro: $bars", bars.any { abs(it - 2_000L) <= 15L })
        assertTrue("phrase start at the drop: $info", g.isPhraseStart(g.barIndexAt(16_050L)))
        val d = a.diagnostics
        val dropBeat = d.beatTimesMs.indices.minByOrNull { abs(d.beatTimesMs[it] - 16_000.0) }!!
        val secMax = d.sec.maxOrNull() ?: 0.0
        assertTrue("Sec fires at the drop: sec=${d.sec[dropBeat]} max=$secMax", d.sec[dropBeat] > 0.5 && d.sec[dropBeat] >= 0.8 * secMax)
    }

    @Test
    fun case09_waltz_threeFourDetectedAndPersistedThroughPracticeState() {
        val bpm = 126.0
        val start = 300.0
        val dur = 46.0
        val t = SyntheticBackingTrack(sr, dur)
        val bt = uniform(start, bpm)
        val beats = ((dur * 1000 - start) / (60_000 / bpm)).toInt() - 1
        val w = GrooveWriter(t, bt, beatsPerBar = 3)
        w.drums(0, beats, kickBeats = setOf(0), snareBeats = setOf(1, 2), snareAmp = 0.18)
        w.harmony(0, beats, listOf(Ch(C), Ch(G), Ch(A, true), Ch(F)), beatsPerChord = 3)
        val a = analyze(t)
        val info = describe(a)
        assertEquals(info, 3, a.grid.beatsPerBar)
        assertTrue("waltz Beat 1: $info", phaseErrorMs(a.grid.firstDownbeatMs.toDouble(), start, a.grid.barDurationMs) <= 15.0)
        val state = PracticeState(itemId = 9L)
            .withBpmPrecise(a.grid.bpmPrecise)
            .copy(beatsPerBar = a.grid.beatsPerBar, firstDownbeatMs = a.grid.firstDownbeatMs, beatGridSource = a.grid.source)
        assertEquals(3, state.beatGrid().beatsPerBar)
        assertEquals(a.grid.bpmPrecise, state.beatGrid().bpmPrecise, 1e-9)
    }

    @Test
    fun case10_liveTempoWander_unstableButAnchorAccurateEarly() {
        val base = 110.0
        val start = 400.0
        val dur = 46.0
        // bpm(t) = base·(1 + 0.015·sin(2πt/12s)); integrate beat times.
        val times = ArrayList<Double>()
        var tt = start
        while (tt < dur * 1000 - 600) {
            times.add(tt)
            val bpmNow = base * (1 + 0.015 * kotlin.math.sin(2 * PI * tt / 12_000.0))
            tt += 60_000.0 / bpmNow
        }
        val t = SyntheticBackingTrack(sr, dur)
        val bt: (Int) -> Double = { b -> if (b < times.size) times[b] else times.last() + (b - times.size + 1) * 545.0 }
        val w = GrooveWriter(t, bt)
        w.drums(0, times.size - 1)
        w.harmony(0, times.size - 1, listOf(Ch(C), Ch(G), Ch(F), Ch(G)), beatsPerChord = 4)
        val a = analyze(t)
        val info = describe(a)
        assertFalse("tempoStable=false: $info", a.grid.confidence.tempoStable)
        assertTrue("anchor < 30 s: $info", a.grid.firstDownbeatMs < 30_000)
        val nearestDownbeat = times.indices.filter { it % 4 == 0 }.minOf { abs(times[it] - a.grid.firstDownbeatMs) }
        assertTrue("anchor within ±15 ms of a true downbeat ($nearestDownbeat): $info", nearestDownbeat <= 15.0)
    }

    @Test
    fun case11_punk170_noPhantomH1OnBeat2() {
        val bpm = 170.0
        val start = 200.0
        val dur = 46.0
        val t = SyntheticBackingTrack(sr, dur)
        val bt = uniform(start, bpm)
        val beats = ((dur * 1000 - start) / (60_000 / bpm)).toInt() - 1
        val w = GrooveWriter(t, bt)
        w.drums(0, beats)
        w.harmony(0, beats, listOf(Ch(A), Ch(D), Ch(G), Ch(D)), beatsPerChord = 4)
        val a = analyze(t)
        val info = describe(a)
        val d = a.diagnostics
        assertEquals(info, 0, d.emptyChromaBeats)
        // Fold raw H1 by beat-in-bar relative to the true downbeat: change beats dominate Beat 2.
        val mu = DoubleArray(4)
        val cnt = IntArray(4)
        for (i in 1 until d.beatTimesMs.size) {
            val b = Math.floorMod(Math.round((d.beatTimesMs[i] - start) / (60_000 / bpm)).toInt(), 4)
            mu[b] += d.h1Raw[i]
            cnt[b]++
        }
        for (b in 0 until 4) mu[b] /= cnt[b].coerceAtLeast(1)
        assertTrue("no phantom H1 on Beat 2: ${mu.toList()} $info", mu[1] < 0.35 * mu[0])
        assertTrue("Beat 1: $info", phaseErrorMs(a.grid.firstDownbeatMs.toDouble(), start, a.grid.barDurationMs) <= 15.0)
    }

    @Test
    fun case12_48kStereoInputAndFractionalFlacTagUsedOnlyForOctave() {
        val bpm = 120.4
        val start = 300.0
        val dur = 40.0
        val t = SyntheticBackingTrack(48_000, dur)
        val bt = uniform(start, bpm)
        val beats = ((dur * 1000 - start) / (60_000 / bpm)).toInt() - 1
        val w = GrooveWriter(t, bt)
        w.drums(0, beats)
        w.harmony(0, beats, listOf(Ch(C), Ch(G), Ch(A, true), Ch(F)), beatsPerChord = 4)
        val stereo = analyze(t, stereo = true)
        assertEquals(describe(stereo), bpm, stereo.grid.bpmPrecise, 0.03)
        assertEquals(BeatGridSource.AUTO_MIX, stereo.grid.source)
        val mono = analyze(t)
        assertEquals(describe(mono), bpm, mono.grid.bpmPrecise, 0.03)

        assertEquals(120.4, BpmDetector.parseBpmTagDouble("120.4")!!, 1e-9)
        assertEquals(120, BpmDetector.parseBpmTagString("120.4"))
        // FLAC Vorbis BPM=120.4 parsed as Double
        val vendor = "x".toByteArray()
        val comment = "BPM=120.4".toByteArray()
        val block = java.nio.ByteBuffer.allocate(4 + vendor.size + 8 + comment.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        block.putInt(vendor.size).put(vendor).putInt(1).putInt(comment.size).put(comment)
        val flac = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte(), 0x84.toByte(), 0, 0, block.capacity().toByte()) + block.array()
        assertEquals(120.4, BpmDetector.extractEmbeddedBpmDoubleFromHeader(flac)!!, 1e-9)
        // Tag only chooses the octave of the acoustic tempo; it never replaces it.
        assertEquals(120.42, BpmOctave.resolve(120.4, 60.21), 1e-9)
        assertEquals(97.0, BpmOctave.resolve(120.4, 97.0), 1e-9)
        val tagged = analyze(t, tag = 120.4)
        assertEquals(stereo.grid.bpmPrecise, tagged.grid.bpmPrecise, 0.03)
    }

    @Test
    fun case13_longBaselineSlipGuardRejectsPhaseLockAndMp3NeverPhaseLocks() {
        val tS = 60_000.0 / 120.40
        val start = TempoFit(tS, 250.0, 2.0, true, 80, 0.004)
        // 1.05-beat drift accumulated over ~100 s: local mid tempo differs by 1.05 beats / span.
        val span = 100_000.0
        val nBeats = Math.round(span / tS)
        val tMid = tS * (1 + 1.05 / nBeats)
        val midBeat = 250.0 + nBeats * tS + 1.05 * tS
        val mid = TempoFit(tMid, midBeat, 2.0, true, 25, 0.02)
        val flac = TempoRegression.mergeLongBaseline(start, 250.0, mid, midBeat, sampleAccurateSeek = true)
        assertFalse("slip guard must reject phase-lock (slip=${flac.slipMetric})", flac.phaseLocked)
        assertTrue(flac.slipMetric >= 0.25)
        // Small genuine residual on FLAC -> phase-lock refines T.
        val exactMid = TempoFit(tS, 0.0, 2.0, true, 25, 0.02)
        val lockedBeat = 250.0 + nBeats * tS + 0.1 * tS
        val locked = TempoRegression.mergeLongBaseline(start, 250.0, exactMid, lockedBeat, sampleAccurateSeek = true)
        assertTrue(locked.phaseLocked)
        assertEquals(tS + 0.1 * tS / nBeats, locked.periodMs, 1e-9)
        // MP3 path never phase-locks.
        val mp3 = TempoRegression.mergeLongBaseline(start, 250.0, exactMid, lockedBeat, sampleAccurateSeek = false)
        assertFalse(mp3.phaseLocked)
        assertEquals(tS, mp3.periodMs, 1e-6)
    }

    @Test
    fun case15_halfBarIVIVvi_and_CCDmDmGGCC_harmRhythm2_cadOff_fillsDecide() {
        for (prog in listOf(
            listOf(Ch(F), Ch(C), Ch(G), Ch(A, true)),
            listOf(Ch(C), Ch(D, true), Ch(G), Ch(C)),
        )) {
            val (t1, bt) = halfBarTrack(prog, fills = true, bpm = 108.0)
            val a1 = analyze(t1)
            val barMs = 4 * 60_000.0 / 108.0
            assertEquals(describe(a1), 2, a1.diagnostics.harmRhythm)
            assertTrue("fills -> correct Beat 1: ${describe(a1)}", phaseErrorMs(a1.grid.firstDownbeatMs.toDouble(), bt(0), barMs) <= 15.0)
            val (t2, bt2) = halfBarTrack(prog, fills = false, bpm = 108.0)
            val a2 = analyze(t2)
            val onBeat3 = phaseErrorMs(a2.grid.firstDownbeatMs.toDouble(), bt2(2), barMs) <= 15.0
            assertTrue(
                "without fills: ambiguous, never a confident Beat 3: ${describe(a2)}",
                a2.grid.confidence.halfBarAmbiguous && (!onBeat3 || a2.grid.confidence.halfBarAmbiguous),
            )
        }
    }

    @Test
    fun case16_pushedBeatsAt175_perBeatChromaFallbackNoEmptyBeats() {
        val bpm = 175.0
        val start = 300.0
        val dur = 46.0
        val rnd = Random(3)
        val period = 60_000.0 / bpm
        val n = ((dur * 1000 - start) / period).toInt() - 2
        // Live "pushed" playing: every beat independently pushed/laid back by up to ±60 ms + slow wander.
        val times = DoubleArray(n + 2) { b ->
            val wander = 25.0 * cos(2 * PI * b / 48.0)
            start + b * period + wander + rnd.nextDouble(-60.0, 60.0)
        }
        val t = SyntheticBackingTrack(sr, dur)
        val bt: (Int) -> Double = { b -> times[b.coerceIn(0, times.size - 1)] + (if (b >= times.size) (b - times.size + 1) * period else 0.0) }
        val w = GrooveWriter(t, bt)
        w.drums(0, n)
        w.harmony(0, n, listOf(Ch(4 /* E */), Ch(A), Ch(D), Ch(A)), beatsPerChord = 4)
        val a = analyze(t)
        val info = describe(a)
        assertFalse("tempoStable=false: $info", a.grid.confidence.tempoStable)
        assertTrue("fallback engages: $info", a.diagnostics.chromaFallbackBeats > 0)
        assertEquals(info, 0, a.diagnostics.emptyChromaBeats)
    }

    @Test
    fun stemsFastPath_drumsBassAndHarmonyStems_analyseAsAutoStems() {
        val bpm = 118.6
        val start = 420.0
        val dur = 46.0
        val beats = ((dur * 1000 - start) / (60_000 / bpm)).toInt() - 1
        val bt = uniform(start, bpm)
        val prog = listOf(Ch(A, true), Ch(F), Ch(C), Ch(G))
        val drums = SyntheticBackingTrack(sr, dur, seed = 11).also { GrooveWriter(it, bt).drums(0, beats) }
        val bass = SyntheticBackingTrack(sr, dur).also { t ->
            var b = 0
            var ci = 0
            while (b < beats) {
                t.bass(bt(b), bt(b + 4) - bt(b), prog[ci % 4].pc)
                b += 4
                ci++
            }
        }
        val guitar = SyntheticBackingTrack(sr, dur).also {
            GrooveWriter(it, bt).harmony(0, beats, prog, beatsPerChord = 4, withBass = false)
        }
        val silent = FloatArray(drums.frames)
        fun stereo(x: FloatArray) = arrayOf(x.copyOf(), x.copyOf())
        val cache = com.miniplayer.app.player.StemSeparatedTrackCache.fromFloatStemsInMemory(
            itemId = 42L,
            sampleRate = sr,
            stems5Stereo = arrayOf(stereo(silent), stereo(guitar.buf), stereo(bass.buf), stereo(drums.buf), stereo(silent)),
        )
        val reader = com.miniplayer.app.player.beat.StemBlockReader(cache)
        // Bulk block reader matches per-frame decode (non-block-aligned offset).
        val probe = reader.readMono(com.miniplayer.app.core.model.StemType.DRUMS, 1_000_037, 300)
        val frame = FloatArray(10)
        for (i in 0 until 300) {
            cache.readFrameStems(1_000_037 + i, frame)
            assertEquals(0.5f * (frame[6] + frame[7]), probe[i], 1e-6f)
        }
        val a = reader.analyzeBeatGrid()
        assertNotNull(a)
        val g = a!!.grid
        assertEquals(BeatGridSource.AUTO_STEMS, g.source)
        assertEquals(describe(a), bpm, g.bpmPrecise, 0.03)
        assertTrue("stems Beat 1: ${describe(a)}", phaseErrorMs(g.firstDownbeatMs.toDouble(), start, g.barDurationMs) <= 15.0)
    }
}
