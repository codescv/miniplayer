package com.miniplayer.app.player.beat

import com.miniplayer.app.core.model.BeatGrid
import com.miniplayer.app.core.model.BeatGridConfidence
import com.miniplayer.app.core.model.BeatGridSource
import com.miniplayer.app.core.model.StemType
import com.miniplayer.app.player.BpmDetector
import com.miniplayer.app.player.StemSeparatedTrackCache
import com.miniplayer.app.player.WaveformExtractor
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-function unit tests for the BeatGrid v2 `player.beat` package and its helpers. */
class BeatPackageUnitTest {

    // ---------------------------------------------------------------- RealFft

    private fun naiveDftMagnitudes(x: FloatArray, n: Int): DoubleArray = DoubleArray(n / 2 + 1) { k ->
        var re = 0.0
        var im = 0.0
        for (t in 0 until n) {
            val a = -2.0 * PI * k * t / n
            re += x[t] * cos(a)
            im += x[t] * sin(a)
        }
        sqrt(re * re + im * im)
    }

    private fun assertSpectraClose(expected: DoubleArray, actual: FloatArray, label: String) {
        val peak = expected.maxOrNull() ?: 1.0
        for (k in expected.indices) {
            assertEquals("$label bin $k", expected[k], actual[k].toDouble(), 1e-4 * peak + 1e-4)
        }
    }

    @Test
    fun realFft_pairedAndSingleMatchNaiveDft() {
        val rnd = Random(7)
        for (n in intArrayOf(1024, 2048)) {
            val fft = RealFft(n)
            assertEquals(n / 2 + 1, fft.bins)
            val win = RealFft.hann(n)
            // x: two tones + noise; y: different tone + DC + noise (asymmetric content on purpose).
            val x = FloatArray(n) { (sin(2 * PI * 37.0 * it / n) + 0.4 * cos(2 * PI * 211.5 * it / n) + 0.1 * rnd.nextGaussian()).toFloat() * win[it] }
            val y = FloatArray(n) { (0.3 + 0.8 * sin(2 * PI * 5.25 * it / n) + 0.1 * rnd.nextGaussian()).toFloat() * win[it] }
            val outX = FloatArray(fft.bins)
            val outY = FloatArray(fft.bins)
            fft.magnitudesPair(x, y, outX, outY)
            assertSpectraClose(naiveDftMagnitudes(x, n), outX, "paired X n=$n")
            assertSpectraClose(naiveDftMagnitudes(y, n), outY, "paired Y n=$n")

            val single = FloatArray(fft.bins)
            fft.magnitudesPair(y, null, single, null)
            assertSpectraClose(naiveDftMagnitudes(y, n), single, "single n=$n")
            // Re-use of scratch buffers must not leak state between calls.
            fft.magnitudesPair(x, null, single, null)
            assertSpectraClose(naiveDftMagnitudes(x, n), single, "single reuse n=$n")
        }
    }

    // ---------------------------------------------------------------- PolyphaseDecimator

    private fun decimatedRms(dec: PolyphaseDecimator, cyclesPerSample: Double, inputSamples: Int, skipOutputs: Int): Double {
        var sumSq = 0.0
        var count = 0
        var outIdx = 0
        for (i in 0 until inputSamples) {
            if (dec.push(sin(2.0 * PI * cyclesPerSample * i).toFloat())) {
                if (outIdx >= skipOutputs) {
                    sumSq += dec.lastOutput.toDouble() * dec.lastOutput
                    count++
                }
                outIdx++
            }
        }
        return sqrt(sumSq / count.coerceAtLeast(1))
    }

    @Test
    fun polyphaseDecimator_unitDcGainAndAliasRejection() {
        // Coefficients are normalised: DC gain is exactly 1 for every design.
        for (blackman in listOf(false, true)) {
            val dec = PolyphaseDecimator(4, 47, 0.2, blackman)
            var last = 0f
            var outputs = 0
            repeat(4_000) { if (dec.push(1f)) { last = dec.lastOutput; outputs++ } }
            assertEquals(1_000, outputs)
            assertEquals(1.0, last.toDouble(), 1e-4)
            assertEquals(23.0, dec.groupDelaySamples, 0.0)
        }
        assertEquals(1.0, PolyphaseDecimator.designLowpass(15, 0.2, true).sumOf { it.toDouble() }, 1e-5)

        // Correctly-specified ÷4 anti-alias filter (cutoff below the output Nyquist = 0.25 of input Nyquist).
        val passRms = decimatedRms(PolyphaseDecimator(4, 47, 0.2), 0.02, 40_000, 50)
        assertEquals(sqrt(0.5), passRms, 0.03)
        // 0.30 cycles/sample would alias to 0.05 cycles/sample after ÷4; it must be strongly attenuated.
        val aliasRms = decimatedRms(PolyphaseDecimator(4, 47, 0.2), 0.30, 40_000, 50)
        assertTrue("alias leak rms=$aliasRms", aliasRms < 0.01 * sqrt(0.5))
        val aliasRmsBlackman = decimatedRms(PolyphaseDecimator(4, 47, 0.2, blackman = true), 0.30, 40_000, 50)
        assertTrue("alias leak (blackman) rms=$aliasRmsBlackman", aliasRmsBlackman < 0.002 * sqrt(0.5))
    }

    // ---------------------------------------------------------------- Stats

    @Test
    fun stats_robustZFlooredAndClippedAndZmIsZeroMeanUnitNorm() {
        // Constant input: MAD = 0 and p90 - p50 = 0, so the absolute floor sets the scale (no blow-up).
        val flat = Stats.robustZ(DoubleArray(20) { 0.2 }, absFloor = 0.5)
        assertTrue(flat.all { it == 0.0 })
        // Sparse spike: floor keeps a single tiny bump from turning into a huge z.
        val sparse = DoubleArray(40) { if (it == 7) 0.01 else 0.0 }
        assertEquals(0.01 / 0.5, Stats.robustZ(sparse, absFloor = 0.5)[7], 1e-12)
        // floorFrac scales with the 99th percentile of |x|.
        val withFrac = Stats.robustZ(DoubleArray(40) { if (it == 7) 10.0 else 0.0 }, absFloor = 0.0, floorFrac = 1.0)
        assertTrue(withFrac[7] <= 3.0)
        val wide = DoubleArray(101) { (it - 50).toDouble() }
        val z = Stats.robustZ(wide, absFloor = 1.0)
        assertEquals(0.0, z[50], 1e-12) // median maps to 0
        // Scale = max(1.4826*MAD = 37.07, p90-p50 = 40, floor = 1) = 40.
        assertEquals(10.0 / 40.0, z[60], 1e-9)
        assertEquals(-50.0 / 40.0, z[0], 1e-9)
        // Clip range is [-2, 3].
        val outliers = DoubleArray(23) { if (it < 21) (it - 10).toDouble() else if (it == 21) 1_000.0 else -1_000.0 }
        val zo = Stats.robustZ(outliers, absFloor = 1.0)
        assertEquals(3.0, zo[21], 0.0)
        assertEquals(-2.0, zo[22], 0.0)
        assertTrue(Stats.robustZ(DoubleArray(0), 1.0).isEmpty())

        val raw = doubleArrayOf(1.0, -1.0, 0.0, 0.0)
        val zm = Stats.zm(raw)
        assertEquals(0.0, zm.sum(), 1e-12)
        assertEquals(1.0, zm.sumOf { it * it }, 1e-12)
        assertArrayEquals(doubleArrayOf(1 / sqrt(2.0), -1 / sqrt(2.0), 0.0, 0.0), zm, 1e-12)
        val shifted = Stats.zm(doubleArrayOf(5.0, 5.0, 6.0, 4.0))
        assertEquals(0.0, shifted.sum(), 1e-12)
        assertEquals(1.0, shifted.sumOf { it * it }, 1e-12)
        // Degenerate (constant) input stays all-zero instead of NaN.
        assertTrue(Stats.zm(DoubleArray(4) { 3.0 }).all { it == 0.0 })
    }

    // ---------------------------------------------------------------- TempoRegression

    @Test
    fun tempoRegression_exactNoisyAndOutlierBeats() {
        val n = 90
        val idx = IntArray(n) { it }
        val strength = FloatArray(n) { 1f }
        val exact = TempoRegression.fit(idx, DoubleArray(n) { 123.0 + 500.0 * it }, strength)!!
        assertEquals(500.0, exact.periodMs, 1e-9)
        assertEquals(123.0, exact.phaseMs, 1e-6)
        assertEquals(120.0, exact.bpm, 1e-9)
        assertTrue(exact.tempoStable)
        assertEquals(n, exact.inliers)

        // σ = 3 ms jitter: ±0.003 BPM at 120 over 90 beats (averaged over seeds, plus worst-case bound).
        var worst = 0.0
        var sumErr = 0.0
        for (seed in 1..20) {
            val rnd = Random(seed.toLong())
            val noisy = DoubleArray(n) { 40.0 + 500.0 * it + 3.0 * rnd.nextGaussian() }
            val fit = TempoRegression.fit(idx, noisy, strength)!!
            val err = abs(fit.bpm - 120.0)
            worst = maxOf(worst, err)
            sumErr += err
            assertTrue(fit.tempoStable)
            assertTrue(fit.slopeStdErr > 0.0)
        }
        assertTrue("mean |ΔBPM| = ${sumErr / 20}", sumErr / 20 < 0.003)
        // Theoretical OLS 1σ here is ≈ 0.0029 BPM; allow ~5σ for the worst of 20 seeds.
        assertTrue("worst |ΔBPM| = $worst", worst < 0.015)

        // 15 % gross outliers (pushed fills / missed beats) must not move the tempo.
        val rnd = Random(99)
        val withOutliers = DoubleArray(n) { 40.0 + 500.0 * it + 3.0 * rnd.nextGaussian() + if (it % 7 == 3) 140.0 else 0.0 }
        val robust = TempoRegression.fit(idx, withOutliers, strength)!!
        assertEquals(120.0, robust.bpm, 0.01)
        assertTrue(robust.inliers < n)

        // Gaps in the DP index (Δn > 1) are handled by the slope over indices.
        val gapIdx = intArrayOf(0, 1, 3, 4, 7, 8, 9, 12)
        val gapFit = TempoRegression.fit(gapIdx, DoubleArray(gapIdx.size) { 10.0 + 480.0 * gapIdx[it] }, FloatArray(gapIdx.size) { 1f })!!
        assertEquals(480.0, gapFit.periodMs, 1e-9)

        assertNull(TempoRegression.fit(intArrayOf(0, 1, 2), doubleArrayOf(0.0, 500.0, 1000.0), FloatArray(3) { 1f }))
    }

    @Test
    fun tempoRegression_consecutiveAndUniformBeats() {
        assertArrayEquals(
            doubleArrayOf(0.0, 500.0, 1000.0, 1500.0, 2000.0, 2600.0, 3200.0),
            TempoRegression.consecutiveBeats(intArrayOf(0, 1, 3, 4, 6), doubleArrayOf(0.0, 500.0, 1500.0, 2000.0, 3200.0)),
            1e-9,
        )
        assertEquals(0, TempoRegression.consecutiveBeats(IntArray(0), DoubleArray(0)).size)

        val fit = TempoFit(periodMs = 500.0, phaseMs = -200.0, residualMad = 1.0, tempoStable = true, inliers = 10, slopeStdErr = 0.01)
        // Covers [0, duration - 30 ms): first beat >= 0 (300), last <= 2970 (2800).
        assertArrayEquals(
            doubleArrayOf(300.0, 800.0, 1300.0, 1800.0, 2300.0, 2800.0),
            TempoRegression.uniformBeats(fit, 3_000.0),
            1e-9,
        )
        assertArrayEquals(doubleArrayOf(0.0, 500.0), TempoRegression.uniformBeats(fit.copy(phaseMs = 1_000.0), 1_000.0), 1e-9)
        // An anchor after the window still extends the grid backwards (no folding needed).
        assertArrayEquals(
            doubleArrayOf(0.0, 500.0, 1000.0, 1500.0, 2000.0, 2500.0),
            TempoRegression.uniformBeats(fit.copy(phaseMs = 5_000.0), 3_000.0),
            1e-9,
        )
        assertEquals(0, TempoRegression.uniformBeats(fit, 10.0).size)
    }

    // ---------------------------------------------------------------- BeatSyncFeatures.classifyHarmRhythm

    @Test
    fun classifyHarmRhythm_halfBarEveryBeatBarLevelAndNone() {
        val beats = 64
        val tonal = BooleanArray(beats) { true }
        // |C C F F|Am Am G G|: changes on beats 1 and 3.
        val halfBar = DoubleArray(beats) { if (it % 4 == 0 || it % 4 == 2) 0.8 else 0.05 }
        assertEquals(2, BeatSyncFeatures.classifyHarmRhythm(halfBar, tonal))
        // Change on every beat.
        val everyBeat = DoubleArray(beats) { 0.7 + 0.05 * (it % 4) }
        assertEquals(1, BeatSyncFeatures.classifyHarmRhythm(everyBeat, tonal))
        // Bar-level changes only.
        val barLevel = DoubleArray(beats) { if (it % 4 == 1) 0.9 else 0.02 }
        assertEquals(4, BeatSyncFeatures.classifyHarmRhythm(barLevel, tonal))
        // No harmonic movement / no tonal beats at all.
        assertEquals(0, BeatSyncFeatures.classifyHarmRhythm(DoubleArray(beats) { 0.001 }, tonal))
        assertEquals(0, BeatSyncFeatures.classifyHarmRhythm(halfBar, BooleanArray(beats)))
        // Phase-free: the same half-bar rhythm starting on beat 2 is still "2".
        val shifted = DoubleArray(beats) { if (it % 4 == 1 || it % 4 == 3) 0.8 else 0.05 }
        assertEquals(2, BeatSyncFeatures.classifyHarmRhythm(shifted, tonal))
    }

    // ---------------------------------------------------------------- BpmOctave / anchors

    @Test
    fun bpmOctave_tagOnlyBreaksOctaveTies() {
        assertEquals(120.4, BpmOctave.resolve(null, 120.4), 0.0)
        assertEquals(120.4, BpmOctave.resolve(120.0, 120.4), 0.0) // same octave: acoustic wins
        assertEquals(120.8, BpmOctave.resolve(120.0, 60.4), 1e-9) // tag names the double-time octave
        assertEquals(60.2, BpmOctave.resolve(60.0, 120.4), 1e-9) // tag names the half-time octave
        assertEquals(120.4, BpmOctave.resolve(100.0, 120.4), 0.0) // tag disagrees: ignored
        assertEquals(120.4, BpmOctave.resolve(900.0, 120.4), 0.0) // out-of-range tag: ignored
        assertEquals(100.0, BpmOctave.resolve(100.0, 200.0), 0.0) // ×0.5 octave
        assertEquals(150.0, BpmOctave.resolve(100.0, 150.0), 0.0) // 3:2 is not an octave: tag ignored
    }

    @Test
    fun beatGridAnalyzer_firstBeatAtOrAfterZero() {
        assertEquals(100L, BeatGridAnalyzer.firstBeatAtOrAfterZero(1_100.0, 500.0))
        assertEquals(0L, BeatGridAnalyzer.firstBeatAtOrAfterZero(1_000.0, 500.0))
        assertEquals(300L, BeatGridAnalyzer.firstBeatAtOrAfterZero(-200.0, 500.0))
        assertEquals(0L, BeatGridAnalyzer.firstBeatAtOrAfterZero(-1_000.0, 500.0))
        assertEquals(498L, BeatGridAnalyzer.firstBeatAtOrAfterZero(-0.34, 498.34))
        assertEquals(123L, BeatGridAnalyzer.firstBeatAtOrAfterZero(123.0, 0.5))
        assertEquals(0L, BeatGridAnalyzer.firstBeatAtOrAfterZero(-5.0, 0.0))
    }

    // ---------------------------------------------------------------- WaveformExtractor helpers

    @Test
    fun waveformExtractor_stemsStalenessNegativePtsTrimAndShiftGrid() {
        val cacheMtime = 1_000L
        for (src in listOf(BeatGridSource.AUTO_MIX, BeatGridSource.AUTO_LEGACY)) {
            assertTrue(WaveformExtractor.isCacheStaleVsStems(src, cacheMtime, 2_000L))
            assertTrue(WaveformExtractor.isCacheStaleVsStems(src, cacheMtime, cacheMtime))
            assertFalse(WaveformExtractor.isCacheStaleVsStems(src, cacheMtime, 500L))
            assertFalse(WaveformExtractor.isCacheStaleVsStems(src, cacheMtime, null))
        }
        for (src in listOf(BeatGridSource.MANUAL, BeatGridSource.AUTO_STEMS, BeatGridSource.EMBEDDED_TAG)) {
            assertFalse(WaveformExtractor.isCacheStaleVsStems(src, cacheMtime, 2_000L))
        }

        // -26.122 ms of priming at 44.1 kHz = ceil(1152.0) frames.
        assertEquals(1_152 * 4, WaveformExtractor.negativePtsTrimBytes(-26_122L, 44_100, 4, 1_000_000))
        assertEquals(2, WaveformExtractor.negativePtsTrimBytes(-1L, 44_100, 2, 1_000)) // ceil: partial frame dropped
        assertEquals(4_096, WaveformExtractor.negativePtsTrimBytes(-1_000_000L, 48_000, 4, 4_096)) // capped
        assertEquals(0, WaveformExtractor.negativePtsTrimBytes(0L, 44_100, 4, 4_096))
        assertEquals(0, WaveformExtractor.negativePtsTrimBytes(10_000L, 44_100, 4, 4_096))
        assertEquals(0, WaveformExtractor.negativePtsTrimBytes(-10_000L, 0, 4, 4_096))

        val grid = BeatGrid(
            bpmPrecise = 120.4,
            firstBeatMs = -120L,
            firstDownbeatMs = 380L,
            beatsPerBar = 3,
            phraseOffsetBars = 1,
            confidence = BeatGridConfidence(downbeatMargin = 3f),
            source = BeatGridSource.AUTO_STEMS,
        )
        assertTrue(WaveformExtractor.shiftGrid(grid, 0L) === grid)
        val shifted = WaveformExtractor.shiftGrid(grid, 30_000L)
        assertEquals(29_880L, shifted.firstBeatMs)
        assertEquals(30_380L, shifted.firstDownbeatMs)
        assertEquals(grid.copy(firstBeatMs = 29_880L, firstDownbeatMs = 30_380L), shifted)
        // Negative offsets are not folded either.
        assertEquals(-620L, WaveformExtractor.shiftGrid(grid, -1_000L).firstDownbeatMs)
    }

    // ---------------------------------------------------------------- BpmDetector.parseMp3GaplessInfo

    private fun buildMp3Header(
        withId3: Boolean,
        tag: String,
        encoder: String,
        delay: Int,
        padding: Int,
        flags: Int = 0x0F,
        mono: Boolean = false,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        if (withId3) {
            val payloadSize = 300
            out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0))
            // synchsafe size 300 = 0b10_0101100 -> 0x00 0x00 0x02 0x2C
            out.write(byteArrayOf(0, 0, (payloadSize shr 7).toByte(), (payloadSize and 0x7F).toByte()))
            out.write(ByteArray(payloadSize))
        }
        val frame = ByteArray(417) // 128 kbps / 44.1 kHz MPEG-1 Layer III frame
        frame[0] = 0xFF.toByte()
        frame[1] = 0xFB.toByte() // MPEG-1, Layer III, no CRC
        frame[2] = 0x90.toByte() // 128 kbps, 44.1 kHz
        frame[3] = if (mono) 0xC4.toByte() else 0x44.toByte()
        val sideInfo = if (mono) 17 else 32
        var p = 4 + sideInfo
        tag.forEachIndexed { i, c -> frame[p + i] = c.code.toByte() }
        p += 4
        frame[p] = (flags ushr 24).toByte(); frame[p + 1] = (flags ushr 16).toByte()
        frame[p + 2] = (flags ushr 8).toByte(); frame[p + 3] = flags.toByte()
        p += 4
        if (flags and 0x1 != 0) p += 4 // frames
        if (flags and 0x2 != 0) p += 4 // bytes
        if (flags and 0x4 != 0) p += 100 // TOC
        if (flags and 0x8 != 0) p += 4 // quality
        encoder.forEachIndexed { i, c -> frame[p + i] = c.code.toByte() }
        frame[p + 21] = (delay shr 4).toByte()
        frame[p + 22] = (((delay and 0x0F) shl 4) or (padding shr 8)).toByte()
        frame[p + 23] = (padding and 0xFF).toByte()
        out.write(frame)
        return out.toByteArray()
    }

    @Test
    fun parseMp3GaplessInfo_xingLameInfoAndMissingLame() {
        val xing = BpmDetector.parseMp3GaplessInfo(buildMp3Header(withId3 = true, tag = "Xing", encoder = "LAME", delay = 576, padding = 1234))
        assertNotNull(xing)
        assertEquals(576, xing!!.encoderDelay)
        assertEquals(1234, xing.padding)
        assertEquals(44_100, xing.sampleRate)
        assertEquals(576 * 1000.0 / 44_100, xing.encoderDelayMs, 1e-9)

        val info = BpmDetector.parseMp3GaplessInfo(buildMp3Header(withId3 = false, tag = "Info", encoder = "LAME", delay = 1105, padding = 7, flags = 0x03))
        assertEquals(BpmDetector.Mp3GaplessInfo(1105, 7, 44_100), info)

        val mono = BpmDetector.parseMp3GaplessInfo(buildMp3Header(withId3 = true, tag = "Xing", encoder = "LAME", delay = 576, padding = 0, mono = true))
        assertEquals(576, mono!!.encoderDelay)

        // Xing present but no LAME/Lavc/GOGO extension -> null; no Xing at all -> null.
        assertNull(BpmDetector.parseMp3GaplessInfo(buildMp3Header(withId3 = true, tag = "Xing", encoder = "ABCD", delay = 576, padding = 1234)))
        assertNull(BpmDetector.parseMp3GaplessInfo(buildMp3Header(withId3 = true, tag = "None", encoder = "LAME", delay = 576, padding = 1234)))
        // Truncated buffer -> null, never an exception.
        val full = buildMp3Header(withId3 = true, tag = "Xing", encoder = "LAME", delay = 576, padding = 1234)
        assertNull(BpmDetector.parseMp3GaplessInfo(full, length = 340))
        assertNull(BpmDetector.parseMp3GaplessInfo(ByteArray(0)))
    }

    // ---------------------------------------------------------------- StemBlockReader

    @Test
    fun stemBlockReader_readMonoMatchesReadFrameStemsAcrossBlocksAndOffsets() {
        val frames = 64 * 5 + 37 // not a multiple of 64
        val rnd = Random(3)
        val stems = Array(StemSeparatedTrackCache.NUM_STEMS) { s ->
            Array(2) { ch ->
                FloatArray(frames) { i -> (0.6 * sin(2 * PI * (s + 1) * (ch + 1) * i / 97.0) + 0.05 * rnd.nextGaussian()).toFloat() }
            }
        }
        val cache = StemSeparatedTrackCache.fromFloatStemsInMemory(itemId = 9L, sampleRate = 44_100, stems5Stereo = stems)
        val reader = StemBlockReader(cache)
        assertEquals(frames, reader.totalFrames)
        assertEquals(44_100, reader.sampleRate)

        val lr = FloatArray(StemSeparatedTrackCache.STEM_CHANNELS)
        fun expectedMono(stem: StemType, frame: Int): Float {
            cache.readFrameStems(frame, lr)
            return (lr[2 * stem.ordinal] + lr[2 * stem.ordinal + 1]) * 0.5f
        }
        for (stem in StemType.entries) {
            val all = reader.readMono(stem, 0, frames)
            for (f in 0 until frames) assertEquals("$stem frame $f", expectedMono(stem, f), all[f], 1e-6f)
            // Offset reads straddling block boundaries and the ragged final block.
            for ((start, count) in listOf(1 to 63, 63 to 2, 100 to 200, 64 * 5 - 3 to 40, 5 to 0)) {
                val part = reader.readMono(stem, start, count)
                assertEquals(count, part.size)
                for (i in 0 until count) {
                    val f = start + i
                    val exp = if (f < frames) expectedMono(stem, f) else 0f
                    assertEquals("$stem start=$start i=$i", exp, part[i], 1e-6f)
                }
            }
        }
    }
}
