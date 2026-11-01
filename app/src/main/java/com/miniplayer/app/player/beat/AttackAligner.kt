package com.miniplayer.app.player.beat

import kotlin.math.abs

/** Stage 6 result. */
data class AttackAlignment(
    /** Weighted median of `attack_m − t̂_m` (ms); typically −12..−25 ms on real flux-peak grids. */
    val attackOffsetMs: Double,
    val samples: Int,
)

/**
 * Slope-relative rising-edge back-tracking on the 400 Hz edge signal (§3.6). For every strong
 * GROOVE beat: `k_peak = argmax d` in `[t̂ − 25, t̂ + 25] ms`, then walk back while
 * `d(k) ≥ 0.12·d(k_peak)`; the attack is the first hop of the steep rise.
 */
object AttackAligner {
    const val SEARCH_RADIUS_MS = 25.0
    const val SLOPE_FRACTION = 0.12
    const val OUTLIER_MS = 45.0
    const val PRE_ROLL_MS = 2.0

    /**
     * Attack-start time near [nominalMs], or null if there is no rising edge. Because the edge
     * signal is smoothed with a zero-phase `[0.25, 0.5, 0.25]` kernel the walk-back lands one hop
     * before the hop that holds the first rising sample; the returned time is therefore the start
     * of the hop *after* the last sub-threshold slope, i.e. `(k_first + 1)·hop`.
     */
    fun attackStart(frames: BeatFeatureFrames, nominalMs: Double): Pair<Double, Float>? {
        val x = frames.edge
        if (x.size < 4) return null
        val lo = frames.edgeAt(nominalMs - SEARCH_RADIUS_MS).coerceIn(1, x.size - 1)
        val hi = frames.edgeAt(nominalMs + SEARCH_RADIUS_MS).coerceIn(1, x.size - 1)
        var kPeak = -1
        var dPeak = 0f
        for (k in lo..hi) {
            val d = x[k] - x[k - 1]
            if (d > dPeak) {
                dPeak = d
                kPeak = k
            }
        }
        if (kPeak < 0 || dPeak <= 1e-7f) return null
        var k = kPeak
        val limit = (kPeak - (OUTLIER_MS / frames.edgeHopMs).toInt() - 2).coerceAtLeast(1)
        while (k - 2 >= 0 && k - 1 >= limit && (x[k - 1] - x[k - 2]) >= SLOPE_FRACTION * dPeak) {
            k--
        }
        // k is the first hop whose slope is still >= 12 % of the peak slope; the rise begins in the
        // hop after the smoothing lead (see KDoc).
        val attackMs = (k + 1) * frames.edgeHopMs
        return attackMs to dPeak
    }

    fun align(frames: BeatFeatureFrames, nominalMs: DoubleArray, strong: BooleanArray): AttackAlignment {
        val deltas = ArrayList<Double>()
        val weights = ArrayList<Double>()
        for (i in nominalMs.indices) {
            if (!strong[i]) continue
            val (attack, w) = attackStart(frames, nominalMs[i]) ?: continue
            val d = attack - nominalMs[i]
            if (abs(d) > OUTLIER_MS) continue
            deltas.add(d)
            weights.add(w.toDouble())
        }
        if (deltas.isEmpty()) return AttackAlignment(0.0, 0)
        val off = Stats.weightedMedian(deltas.toDoubleArray(), weights.toDoubleArray())
        return AttackAlignment(off, deltas.size)
    }
}
