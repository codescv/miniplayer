package com.miniplayer.app.player.beat

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/** DP-tracked beats (Stage 3). [index] is the cumulative beat index `n_m`. */
class DpBeats(
    val hops: IntArray,
    /** Sub-hop refined beat times (ms) from the 400 Hz slope peak. */
    val timesMs: DoubleArray,
    val index: IntArray,
    val strength: FloatArray,
    val tau0Hops: Double,
) {
    val size: Int get() = hops.size
}

/**
 * Ellis (2007) dynamic-programming beat tracker with a reliable tempo prior (§3.3):
 * `C*(t) = O(t) + max_Δ [C*(t−Δ) − α·ln²(Δ/τ0)]`, `Δ ∈ [round(0.7τ0), round(1.4τ0)]`.
 */
object DpBeatTracker {
    const val DEFAULT_ALPHA = 30.0
    private const val REFINE_RADIUS_MS = 30.0

    /** `O(t) = 0.6·composite + 0.4·pulse`, p95-normalised (peaks ≈ 1–2). */
    fun onsetStrength(frames: BeatFeatureFrames): FloatArray {
        val n = frames.n
        val o = FloatArray(n) { 0.6f * frames.compositeOnset[it] + 0.4f * frames.pulseOnset[it] }
        val p95 = Stats.percentilePositive(o, 0.95).coerceAtLeast(1e-6)
        for (i in 0 until n) o[i] = (o[i] / p95).toFloat()
        return o
    }

    fun track(frames: BeatFeatureFrames, exactBpm: Double, alpha: Double = DEFAULT_ALPHA): DpBeats? {
        val n = frames.n
        if (n < 16 || !(exactBpm > 0.0)) return null
        val tau0 = 60.0 * frames.envelopeRateHz / exactBpm
        if (tau0 < 4.0) return null
        val o = onsetStrength(frames)
        val lo = (0.7 * tau0).roundToInt().coerceAtLeast(1)
        val hi = (1.4 * tau0).roundToInt().coerceAtLeast(lo + 1)
        val penalty = DoubleArray(hi + 1) { d ->
            if (d < lo) 0.0 else {
                val l = ln(d / tau0)
                alpha * l * l
            }
        }
        val c = DoubleArray(n)
        val back = IntArray(n) { -1 }
        for (t in 0 until n) {
            var best = Double.NEGATIVE_INFINITY
            var arg = -1
            val dMax = minOf(hi, t)
            for (d in lo..dMax) {
                val v = c[t - d] - penalty[d]
                if (v > best) {
                    best = v
                    arg = t - d
                }
            }
            if (arg >= 0) {
                c[t] = o[t] + best
                back[t] = arg
            } else {
                c[t] = o[t].toDouble()
            }
        }
        // Backtrack from the best C* in the last τ0 frames.
        val tailStart = (n - tau0.roundToInt()).coerceAtLeast(0)
        var end = tailStart
        for (t in tailStart until n) if (c[t] > c[end]) end = t
        val rev = ArrayList<Int>()
        var t = end
        while (t >= 0) {
            rev.add(t)
            t = back[t]
        }
        rev.reverse()
        val m = rev.size
        if (m < 4) return null
        val hops = IntArray(m) { rev[it] }
        val index = IntArray(m)
        for (i in 1 until m) {
            val step = max(1, ((hops[i] - hops[i - 1]) / tau0).roundToInt())
            index[i] = index[i - 1] + step
        }
        val strength = FloatArray(m) { o[hops[it]] }
        val times = DoubleArray(m) { refineToEdge(frames, (hops[it] + 0.5) * frames.hopMs) }
        return DpBeats(hops, times, index, strength, tau0)
    }

    /** Locates the 400 Hz slope peak `d(k) = max(0, x[k] − x[k−1])` within ±30 ms. */
    fun refineToEdge(frames: BeatFeatureFrames, centerMs: Double, radiusMs: Double = REFINE_RADIUS_MS): Double {
        val x = frames.edge
        if (x.size < 3) return centerMs
        val lo = frames.edgeAt(centerMs - radiusMs).coerceIn(1, x.size - 1)
        val hi = frames.edgeAt(centerMs + radiusMs).coerceIn(1, x.size - 1)
        var bestK = -1
        var bestD = 0f
        for (k in lo..hi) {
            val d = x[k] - x[k - 1]
            if (d > bestD) {
                bestD = d
                bestK = k
            }
        }
        return if (bestK >= 0) (bestK + 0.5) * frames.edgeHopMs else centerMs
    }
}
