package com.miniplayer.app.player.beat

import kotlin.math.max
import kotlin.math.sqrt

/**
 * All per-track front-end features consumed by Stages 2–6 of BeatGrid v2 (§3.1). Produced either
 * from the mixed decode (Input A) or from the `.stems` cache (Input B), so later stages are shared.
 *
 * Time conventions: 100 Hz hop `k` covers `[k·hopMs, (k+1)·hopMs)`; 400 Hz edge hop `j` covers
 * `[j·edgeHopMs, (j+1)·edgeHopMs)`; all times are ms on the analysis (decode) timeline.
 */
class BeatFeatureFrames(
    val envelopeRateHz: Double,
    val energy: FloatArray,
    val lowRms: FloatArray,
    val midRms: FloatArray,
    val highRms: FloatArray,
    /** > 6 kHz RMS (cymbals; no chord-harmonic leakage). */
    val airRms: FloatArray,
    val broadOnset: FloatArray,
    val lowOnset: FloatArray,
    val midOnset: FloatArray,
    val highOnset: FloatArray,
    val compositeOnset: FloatArray,
    val pulseOnset: FloatArray,
    val hasLowBand: Boolean,
    val hasMidBand: Boolean,
    val hasHighBand: Boolean,
    val peakEnergy: Float,
    val edgeRateHz: Double,
    /** 400 Hz rising-edge signal `x = 0.65·broad + 0.35·midHigh`, smoothed `[0.25, 0.5, 0.25]`. */
    val edge: FloatArray,
    /** 400 Hz low-band RMS max-pooled over 3 hops (7.5 ms); confirmation only. */
    val edgeLow: FloatArray,
    val tonal: TonalFrames,
    /** Broadband accent energy at 100 Hz (stems: full mix sum; mix path: [energy]). */
    val accentEnergy: FloatArray = energy,
) {
    val n: Int get() = energy.size
    val hopMs: Double get() = 1000.0 / envelopeRateHz
    val edgeHopMs: Double get() = 1000.0 / edgeRateHz
    val durationMs: Double get() = n * hopMs

    fun hopAt(ms: Double): Int = kotlin.math.floor(ms / hopMs).toInt()
    fun edgeAt(ms: Double): Int = kotlin.math.floor(ms / edgeHopMs).toInt()

    /** Max of [series] over hops covering `[fromMs, toMs]`. */
    fun maxOver(series: FloatArray, fromMs: Double, toMs: Double): Float {
        val lo = hopAt(fromMs).coerceAtLeast(0)
        val hi = hopAt(toMs).coerceAtMost(series.size - 1)
        var m = 0f
        for (i in lo..hi) if (series[i] > m) m = series[i]
        return m
    }

    /** Mean of [series] over hops covering `[fromMs, toMs)`. */
    fun meanOver(series: FloatArray, fromMs: Double, toMs: Double): Double {
        val lo = hopAt(fromMs).coerceAtLeast(0)
        val hi = (hopAt(toMs) - 1).coerceAtMost(series.size - 1).coerceAtLeast(lo)
        if (lo >= series.size) return 0.0
        var s = 0.0
        for (i in lo..hi) s += series[i]
        return s / (hi - lo + 1)
    }
}

/**
 * Streaming 400 Hz (2.5 ms hop) attack-envelope accumulator (§3.1b), fed from the same per-sample
 * loop as the 100 Hz band accumulator.
 */
internal class EdgeEnvelopeBuilder(sampleRate: Int) {
    val hopSamples: Int = (sampleRate / EDGE_RATE_HZ).coerceAtLeast(1)
    val rateHz: Double = sampleRate.toDouble() / hopSamples

    private var raw = FloatArray(16_384)
    private var low = FloatArray(16_384)
    private var count = 0
    private var frames = 0
    private var broadAbs = 0.0
    private var midHighSq = 0.0
    private var lowSq = 0.0

    fun push(signedMono: Double, absMono: Double, lowBand: Double) {
        broadAbs += absMono
        val mh = signedMono - lowBand
        midHighSq += mh * mh
        lowSq += lowBand * lowBand
        frames++
        if (frames == hopSamples) {
            val inv = 1.0 / hopSamples
            if (count == raw.size) {
                raw = raw.copyOf(raw.size * 2)
                low = low.copyOf(low.size * 2)
            }
            raw[count] = (0.65 * broadAbs * inv + 0.35 * sqrt(midHighSq * inv)).toFloat()
            low[count] = sqrt(lowSq * inv).toFloat()
            count++
            frames = 0
            broadAbs = 0.0
            midHighSq = 0.0
            lowSq = 0.0
        }
    }

    /** Returns (smoothed edge signal, max-pooled low). */
    fun build(): Pair<FloatArray, FloatArray> {
        val n = count
        val x = FloatArray(n)
        if (n >= 2) {
            x[0] = 0.75f * raw[0] + 0.25f * raw[1]
            for (k in 1 until n - 1) x[k] = 0.25f * raw[k - 1] + 0.5f * raw[k] + 0.25f * raw[k + 1]
            x[n - 1] = 0.25f * raw[n - 2] + 0.75f * raw[n - 1]
        } else if (n == 1) {
            x[0] = raw[0]
        }
        val lp = FloatArray(n)
        for (k in 0 until n) {
            var m = low[k]
            if (k > 0) m = max(m, low[k - 1])
            if (k + 1 < n) m = max(m, low[k + 1])
            lp[k] = m
        }
        return x to lp
    }

    companion object {
        const val EDGE_RATE_HZ = 400
    }
}
