package com.miniplayer.app.player.beat

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming polyphase anti-alias FIR decimator. Only output samples are evaluated (cost
 * `taps / factor` MACs per input sample).
 */
internal class PolyphaseDecimator(
    private val factor: Int,
    taps: Int,
    /** Cutoff as a fraction of the *input* Nyquist rate (0..1). */
    cutoffOfNyquist: Double,
    blackman: Boolean = false,
) {
    private val coeffs: FloatArray = designLowpass(taps, cutoffOfNyquist, blackman)
    private val history = FloatArray(coeffs.size)
    private var writePos = 0
    private var phase = 0

    /** Pushes one input sample; returns true when [lastOutput] holds a fresh decimated sample. */
    fun push(x: Float): Boolean {
        history[writePos] = x
        writePos++
        if (writePos == history.size) writePos = 0
        phase++
        if (phase < factor) return false
        phase = 0
        var acc = 0f
        var idx = writePos - 1
        for (c in coeffs) {
            if (idx < 0) idx += history.size
            acc += c * history[idx]
            idx--
        }
        lastOutput = acc
        return true
    }

    var lastOutput: Float = 0f
        private set

    /** Group delay in input samples ((taps - 1) / 2). */
    val groupDelaySamples: Double = (coeffs.size - 1) / 2.0

    companion object {
        fun designLowpass(taps: Int, cutoffOfNyquist: Double, blackman: Boolean): FloatArray {
            val m = taps - 1
            val fc = cutoffOfNyquist.coerceIn(0.01, 0.99) / 2.0 // cycles per sample
            val h = DoubleArray(taps) { i ->
                val x = i - m / 2.0
                val sinc = if (abs(x) < 1e-9) 2.0 * fc else sin(2.0 * PI * fc * x) / (PI * x)
                val w = if (blackman) {
                    0.42 - 0.5 * cos(2.0 * PI * i / m) + 0.08 * cos(4.0 * PI * i / m)
                } else {
                    0.54 - 0.46 * cos(2.0 * PI * i / m)
                }
                sinc * w
            }
            val sum = h.sum()
            return FloatArray(taps) { (h[it] / sum).toFloat() }
        }
    }
}

/** Growable float buffer. */
internal class FloatSink(initial: Int = 16_384, private val maxSize: Int = Int.MAX_VALUE) {
    var data = FloatArray(initial)
        private set
    var size = 0
        private set

    fun add(v: Float) {
        if (size >= maxSize) return
        if (size == data.size) data = data.copyOf(minOf(maxSize, data.size * 2).coerceAtLeast(size + 1))
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

/**
 * Builds the two decimated tonal streams of §3.1c from full-rate mono input:
 * chroma stream ÷4 (47-tap polyphase FIR, ≈11.025 kHz) and bass stream ÷16 (a second ÷4, 15-tap
 * < 300 Hz-band FIR, ≈2.756 kHz).
 */
internal class TonalStreamBuilder(val inputSampleRate: Int, maxSeconds: Double = 75.0) {
    private val chromaDecimator = PolyphaseDecimator(4, 47, 0.85)
    private val bassPreDecimator = PolyphaseDecimator(4, 47, 0.85)
    // 15-tap Blackman: passes the 35–260 Hz bass band flat and attenuates the band that would alias
    // into it (> 1.38 kHz); a short kernel keeps the bass timeline tight (< 1 ms group delay).
    private val bassDecimator = PolyphaseDecimator(4, 15, 0.2, blackman = true)

    val chromaRate: Double = inputSampleRate / 4.0
    val bassRate: Double = inputSampleRate / 16.0

    private val chroma = FloatSink(65_536, (maxSeconds * chromaRate).toInt())
    private val bass = FloatSink(16_384, (maxSeconds * bassRate).toInt())

    /**
     * Same mono sample feeds both streams (mix path): the bass stream is the ÷4 chroma stream
     * decimated by another 4, so the 47-tap FIR runs once.
     */
    fun push(x: Float) {
        if (chromaDecimator.push(x)) {
            val c = chromaDecimator.lastOutput
            chroma.add(c)
            if (bassDecimator.push(c)) bass.add(bassDecimator.lastOutput)
        }
    }

    fun pushChroma(x: Float) {
        if (chromaDecimator.push(x)) chroma.add(chromaDecimator.lastOutput)
    }

    fun pushBass(x: Float) {
        if (bassPreDecimator.push(x) && bassDecimator.push(bassPreDecimator.lastOutput)) {
            bass.add(bassDecimator.lastOutput)
        }
    }

    /** FIR group delays (ms) so frame centres map back onto the full-rate timeline. */
    val chromaDelayMs: Double get() = 1000.0 * (23.0 / inputSampleRate)
    val bassDelayMs: Double get() = 1000.0 * (23.0 / inputSampleRate + 7.0 * 4.0 / inputSampleRate)

    fun build(): TonalFrames = TonalFrontEnd.analyze(
        chromaSignal = chroma.toArray(),
        chromaRate = chromaRate,
        chromaDelayMs = chromaDelayMs,
        bassSignal = bass.toArray(),
        bassRate = bassRate,
        bassDelayMs = bassDelayMs,
    )
}

/** Chroma (12-D, L2-normalised) and bass PCP (12-D, L1-normalised) frame sequences. */
class TonalFrames(
    val chroma: Array<FloatArray>,
    val tonalEnergy: FloatArray,
    /** Frame-centre times (ms) on the analysis timeline. */
    val chromaCentersMs: DoubleArray,
    val bassPcp: Array<FloatArray>,
    val bassCentroidHz: FloatArray,
    val bassEnergy: FloatArray,
    val bassCentersMs: DoubleArray,
) {
    val isEmpty: Boolean get() = chroma.isEmpty()

    companion object {
        val EMPTY = TonalFrames(emptyArray(), FloatArray(0), DoubleArray(0), emptyArray(), FloatArray(0), FloatArray(0), DoubleArray(0))
    }
}

object TonalFrontEnd {
    const val CHROMA_FFT = 2048
    const val CHROMA_HOP = 512
    const val BASS_FFT = 1024
    const val BASS_HOP = 256
    private const val CHROMA_MIN_HZ = 82.0
    private const val CHROMA_MAX_HZ = 2100.0
    private const val BASS_MIN_HZ = 35.0
    private const val BASS_MAX_HZ = 260.0
    private const val LOG_GAMMA = 1000.0

    fun analyze(
        chromaSignal: FloatArray,
        chromaRate: Double,
        chromaDelayMs: Double,
        bassSignal: FloatArray,
        bassRate: Double,
        bassDelayMs: Double,
    ): TonalFrames {
        val chroma = computeChroma(chromaSignal, chromaRate, chromaDelayMs)
        val bass = computeBass(bassSignal, bassRate, bassDelayMs)
        return TonalFrames(
            chroma = chroma.first,
            tonalEnergy = chroma.second,
            chromaCentersMs = chroma.third,
            bassPcp = bass.pcp,
            bassCentroidHz = bass.centroid,
            bassEnergy = bass.energy,
            bassCentersMs = bass.centers,
        )
    }

    private fun midiOf(hz: Double): Double = 69.0 + 12.0 * ln(hz / 440.0) / ln(2.0)

    /** Returns (chroma frames, tonalEnergy, frame centres ms). */
    internal fun computeChroma(signal: FloatArray, rate: Double, delayMs: Double): Triple<Array<FloatArray>, FloatArray, DoubleArray> {
        val n = CHROMA_FFT
        val hop = CHROMA_HOP
        if (signal.size < n) return Triple(emptyArray(), FloatArray(0), DoubleArray(0))
        val frames = (signal.size - n) / hop + 1
        val fft = RealFft(n)
        val window = RealFft.hann(n)
        val windowSum = window.sum().toDouble()
        val ampScale = 2.0 / windowSum
        val binHz = rate / n
        val kLo = (CHROMA_MIN_HZ / binHz).toInt().coerceAtLeast(1)
        val kHi = (CHROMA_MAX_HZ / binHz).toInt().coerceAtMost(n / 2 - 1)
        // Pitch range covered including harmonic-sum look-ups (+19 semitones).
        val pLo = midiOf(CHROMA_MIN_HZ).toInt() - 1
        val pHi = midiOf(CHROMA_MAX_HZ).toInt() + 2
        val pitchCount = pHi - pLo + 1
        // Per-bin tables (computed once): semitone index for whitening and the Gaussian weights to
        // the two nearest semitones (σ = 0.35 semitone).
        val binSemi = IntArray(kHi + 1)
        val binQ0 = IntArray(kHi + 1)
        val binW0 = DoubleArray(kHi + 1)
        val binW1 = DoubleArray(kHi + 1)
        for (k in kLo..kHi) {
            val p = midiOf(k * binHz)
            binSemi[k] = (kotlin.math.round(p).toInt() - pLo).coerceIn(0, pitchCount - 1)
            val p0 = kotlin.math.floor(p).toInt()
            binQ0[k] = p0 - pLo
            val d0 = (p - p0) / 0.35
            val d1 = (p - (p0 + 1)) / 0.35
            binW0[k] = kotlin.math.exp(-0.5 * d0 * d0)
            binW1[k] = kotlin.math.exp(-0.5 * d1 * d1)
        }

        val chroma = Array(frames) { FloatArray(12) }
        val energy = FloatArray(frames)
        val centers = DoubleArray(frames) { f -> (f * hop + n / 2.0) * 1000.0 / rate - delayMs }

        val bufA = FloatArray(n)
        val bufB = FloatArray(n)
        val magA = FloatArray(fft.bins)
        val magB = FloatArray(fft.bins)
        val logMag = DoubleArray(kHi + 1)
        val pitchEnergy = DoubleArray(pitchCount + 20)
        val semiPeak = DoubleArray(pitchCount)
        val semiMedian = DoubleArray(pitchCount)
        val medianScratch = DoubleArray(32)

        fun processFrame(f: Int, mag: FloatArray) {
            java.util.Arrays.fill(semiPeak, 0.0)
            for (k in kLo..kHi) {
                val l = ln(1.0 + LOG_GAMMA * mag[k] * ampScale)
                logMag[k] = l
                val si = binSemi[k]
                if (l > semiPeak[si]) semiPeak[si] = l
            }
            // Whitening reference: ±1-octave (±12 semitone) running median of the semitone-pooled
            // log spectrum, applied per bin through its semitone.
            for (si in 0 until pitchCount) {
                val lo = (si - 12).coerceAtLeast(0)
                val hi = (si + 12).coerceAtMost(pitchCount - 1)
                val count = hi - lo + 1
                System.arraycopy(semiPeak, lo, medianScratch, 0, count)
                java.util.Arrays.sort(medianScratch, 0, count)
                semiMedian[si] = medianScratch[count / 2]
            }
            java.util.Arrays.fill(pitchEnergy, 0.0)
            var total = 0.0
            for (k in kLo..kHi) {
                val w = logMag[k] - semiMedian[binSemi[k]]
                if (w <= 0.0) continue
                val q0 = binQ0[k]
                if (q0 in 0 until pitchCount) pitchEnergy[q0] += w * binW0[k]
                if (q0 + 1 in 0 until pitchCount) pitchEnergy[q0 + 1] += w * binW1[k]
                total += w
            }
            val c = chroma[f]
            for (idx in 0 until pitchCount) {
                // Harmonic-sum salience: fundamental + 0.5·octave + 0.33·twelfth.
                val sal = pitchEnergy[idx] +
                    0.5 * (if (idx + 12 < pitchCount) pitchEnergy[idx + 12] else 0.0) +
                    0.33 * (if (idx + 19 < pitchCount) pitchEnergy[idx + 19] else 0.0)
                val pc = Math.floorMod(pLo + idx, 12)
                c[pc] += sal.toFloat()
            }
            var norm = 0.0
            for (v in c) norm += v.toDouble() * v
            norm = sqrt(norm)
            if (norm > 1e-9) for (i in 0 until 12) c[i] = (c[i] / norm).toFloat()
            energy[f] = total.toFloat()
        }

        var f = 0
        while (f < frames) {
            val hasPair = f + 1 < frames
            for (i in 0 until n) {
                bufA[i] = signal[f * hop + i] * window[i]
                if (hasPair) bufB[i] = signal[(f + 1) * hop + i] * window[i]
            }
            fft.magnitudesPair(bufA, if (hasPair) bufB else null, magA, if (hasPair) magB else null)
            processFrame(f, magA)
            if (hasPair) processFrame(f + 1, magB)
            f += 2
        }
        return Triple(chroma, energy, centers)
    }

    internal class BassResult(val pcp: Array<FloatArray>, val centroid: FloatArray, val energy: FloatArray, val centers: DoubleArray)

    internal fun computeBass(signal: FloatArray, rate: Double, delayMs: Double): BassResult {
        val n = BASS_FFT
        val hop = BASS_HOP
        if (signal.size < n) return BassResult(emptyArray(), FloatArray(0), FloatArray(0), DoubleArray(0))
        val frames = (signal.size - n) / hop + 1
        val fft = RealFft(n)
        val window = RealFft.hann(n)
        val ampScale = 2.0 / window.sum().toDouble()
        val binHz = rate / n
        val kLo = (BASS_MIN_HZ / binHz).toInt().coerceAtLeast(1)
        val kHi = (BASS_MAX_HZ / binHz).toInt().coerceAtMost(n / 2 - 1)
        val pcp = Array(frames) { FloatArray(12) }
        val centroid = FloatArray(frames)
        val energy = FloatArray(frames)
        val centers = DoubleArray(frames) { f -> (f * hop + n / 2.0) * 1000.0 / rate - delayMs }
        val bufA = FloatArray(n)
        val bufB = FloatArray(n)
        val magA = FloatArray(fft.bins)
        val magB = FloatArray(fft.bins)

        fun processFrame(f: Int, mag: FloatArray) {
            val out = pcp[f]
            var sum = 0.0
            var wSum = 0.0
            var e = 0.0
            for (k in kLo..kHi) {
                val a = mag[k] * ampScale
                val p = a * a
                if (p <= 0.0) continue
                val hz = k * binHz
                val midi = midiOf(hz)
                val q = kotlin.math.round(midi).toInt()
                val d = (midi - q) / 0.4
                val g = kotlin.math.exp(-0.5 * d * d)
                out[Math.floorMod(q, 12)] += (p * g).toFloat()
                sum += p * g
                wSum += hz * p
                e += p
            }
            if (sum > 1e-12) for (i in 0 until 12) out[i] = (out[i] / sum).toFloat()
            centroid[f] = if (e > 1e-12) (wSum / e).toFloat() else 0f
            energy[f] = sqrt(e).toFloat()
        }

        var f = 0
        while (f < frames) {
            val hasPair = f + 1 < frames
            for (i in 0 until n) {
                bufA[i] = signal[f * hop + i] * window[i]
                if (hasPair) bufB[i] = signal[(f + 1) * hop + i] * window[i]
            }
            fft.magnitudesPair(bufA, if (hasPair) bufB else null, magA, if (hasPair) magB else null)
            processFrame(f, magA)
            if (hasPair) processFrame(f + 1, magB)
            f += 2
        }
        return BassResult(pcp, centroid, energy, centers)
    }

    // ---------------------------------------------------------------------------------------------
    // Tonnetz, key and triad templates
    // ---------------------------------------------------------------------------------------------

    /** 6-D Tonnetz (Harte 2006) of an (L1-normalised internally) chroma vector. */
    fun tonnetz(chroma: FloatArray): DoubleArray {
        var l1 = 0.0
        for (v in chroma) l1 += abs(v.toDouble())
        val out = DoubleArray(6)
        if (l1 < 1e-9) return out
        for (p in 0 until 12) {
            val c = chroma[p] / l1
            val a5 = p * 7.0 * PI / 6.0
            val am3 = p * 3.0 * PI / 2.0
            val aM3 = p * 2.0 * PI / 3.0
            out[0] += c * sin(a5)
            out[1] += c * cos(a5)
            out[2] += c * sin(am3)
            out[3] += c * cos(am3)
            out[4] += c * 0.5 * sin(aM3)
            out[5] += c * 0.5 * cos(aM3)
        }
        return out
    }

    private val KS_MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val KS_MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    /** Krumhansl–Schmuckler key. Returns (tonicPc, isMinor, confidence κ ∈ [0,1]). */
    fun estimateKey(meanChroma: DoubleArray): Triple<Int, Boolean, Double> {
        var best = -2.0
        var second = -2.0
        var bestTonic = 0
        var bestMinor = false
        for (minor in listOf(false, true)) {
            val prof = if (minor) KS_MINOR else KS_MAJOR
            for (t in 0 until 12) {
                val r = pearson(meanChroma) { prof[Math.floorMod(it - t, 12)] }
                if (r > best) {
                    second = best
                    best = r
                    bestTonic = t
                    bestMinor = minor
                } else if (r > second) {
                    second = r
                }
            }
        }
        val kappa = ((best - second) / 0.15).coerceIn(0.0, 1.0) * best.coerceIn(0.0, 1.0)
        return Triple(bestTonic, bestMinor, kappa)
    }

    private inline fun pearson(x: DoubleArray, y: (Int) -> Double): Double {
        var mx = 0.0
        var my = 0.0
        for (i in 0 until 12) {
            mx += x[i]
            my += y(i)
        }
        mx /= 12.0
        my /= 12.0
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in 0 until 12) {
            val dx = x[i] - mx
            val dy = y(i) - my
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        return if (sxx > 1e-12 && syy > 1e-12) sxy / sqrt(sxx * syy) else 0.0
    }

    /** Best of 24 major/minor triads: returns chord id `root + (minor ? 12 : 0)`, or -1 if flat. */
    fun matchTriad(chroma: FloatArray): Int {
        var best = 0.0
        var bestId = -1
        for (minor in 0..1) {
            val third = if (minor == 1) 3 else 4
            for (root in 0 until 12) {
                val s = chroma[root] + chroma[(root + third) % 12] + chroma[(root + 7) % 12]
                val score = s / sqrt(3.0)
                if (score > best) {
                    best = score
                    bestId = root + 12 * minor
                }
            }
        }
        return if (best > 0.55) bestId else -1
    }
}
