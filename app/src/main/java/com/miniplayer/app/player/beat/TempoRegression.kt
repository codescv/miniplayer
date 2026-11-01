package com.miniplayer.app.player.beat

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Stage 3 output: robust tempo fit `t_m ≈ φ + T·n_m`. */
data class TempoFit(
    val periodMs: Double,
    val phaseMs: Double,
    /** Robust residual scale (1.4826·MAD of inlier residuals, ms). */
    val residualMad: Double,
    val tempoStable: Boolean,
    val inliers: Int,
    /** Standard error of [periodMs] (ms/beat). */
    val slopeStdErr: Double,
) {
    val bpm: Double get() = 60_000.0 / periodMs
    fun beatTimeMs(n: Double): Double = phaseMs + periodMs * n
}

/** Result of the guarded long-baseline merge (§3.3). */
data class LongBaselineMerge(
    val periodMs: Double,
    val phaseLocked: Boolean,
    val tempoStable: Boolean,
    /** `|T_mid − T_start|·span / T²`: must be < 0.25 for a phase lock. */
    val slipMetric: Double,
    /** Fractional beat phase deviation of the mid window vs the start grid. */
    val deltaBeats: Double,
)

/** Theil–Sen + one IRLS refit (§3.3), and the guarded long-baseline merge. */
object TempoRegression {
    private const val MAX_THEIL_SEN_POINTS = 120

    fun fit(index: IntArray, timesMs: DoubleArray, strength: FloatArray): TempoFit? {
        val m = index.size
        if (m < 4) return null
        val medStrength = Stats.median(DoubleArray(m) { strength[it].toDouble() })
        var sel = (0 until m).filter { strength[it] >= 0.2 * medStrength }
        if (sel.size < 4) sel = (0 until m).toList()
        // Theil–Sen slope over (subsampled) pairs.
        val pts = if (sel.size > MAX_THEIL_SEN_POINTS) {
            List(MAX_THEIL_SEN_POINTS) { sel[(it.toLong() * (sel.size - 1) / (MAX_THEIL_SEN_POINTS - 1)).toInt()] }
        } else {
            sel
        }
        val slopes = ArrayList<Double>(pts.size * pts.size / 2)
        for (a in pts.indices) {
            for (b in a + 1 until pts.size) {
                val dn = index[pts[b]] - index[pts[a]]
                if (dn != 0) slopes.add((timesMs[pts[b]] - timesMs[pts[a]]) / dn)
            }
        }
        if (slopes.isEmpty()) return null
        val slope0 = Stats.median(slopes.toDoubleArray())
        val phase0 = Stats.median(DoubleArray(sel.size) { timesMs[sel[it]] - slope0 * index[sel[it]] })
        val res0 = DoubleArray(sel.size) { timesMs[sel[it]] - (phase0 + slope0 * index[sel[it]]) }
        val mad0 = Stats.mad(res0, 0.0).coerceAtLeast(1.0)
        // Inliers: |res| < 2.5·σ̂ with σ̂ = 1.4826·MAD (raw MAD ≈ 0.67σ would drop ~10 % of good beats).
        val inl = sel.indices.filter { abs(res0[it]) < 2.5 * 1.4826 * mad0 }.map { sel[it] }
        val use = if (inl.size >= 3) inl else sel
        // Weighted LS refit, w = sqrt(strength).
        var sw = 0.0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (i in use) {
            val w = sqrt(strength[i].toDouble().coerceAtLeast(1e-3))
            val x = index[i].toDouble()
            val y = timesMs[i]
            sw += w
            sx += w * x
            sy += w * y
            sxx += w * x * x
            sxy += w * x * y
        }
        val den = sw * sxx - sx * sx
        val (slope, phase) = if (abs(den) > 1e-9) {
            val s = (sw * sxy - sx * sy) / den
            s to (sy - s * sx) / sw
        } else {
            slope0 to phase0
        }
        if (!(slope > 0.0)) return null
        val res = DoubleArray(use.size) { timesMs[use[it]] - (phase + slope * index[use[it]]) }
        val sigma = 1.4826 * Stats.mad(res, 0.0)
        val xs = DoubleArray(use.size) { index[use[it]].toDouble() }
        val xMean = xs.average()
        var sxxC = 0.0
        for (x in xs) sxxC += (x - xMean) * (x - xMean)
        val stdErr = if (sxxC > 0.0) sigma.coerceAtLeast(0.5) / sqrt(sxxC) else slope
        return TempoFit(
            periodMs = slope,
            phaseMs = phase,
            residualMad = sigma,
            tempoStable = sigma < 0.04 * slope,
            inliers = use.size,
            slopeStdErr = stdErr,
        )
    }

    /**
     * Guarded long-baseline refinement (§3.3). [startFit]/[midFit] are local fits on the same
     * absolute timeline; [midBeatMs] a mid-window beat time. Phase-lock only on sample-accurate
     * containers when the slip guard and `|Δ| < 0.35` hold; otherwise inverse-variance slope merge.
     */
    fun mergeLongBaseline(
        startFit: TempoFit,
        startBeatMs: Double,
        midFit: TempoFit,
        midBeatMs: Double,
        sampleAccurateSeek: Boolean,
    ): LongBaselineMerge {
        val tS = startFit.periodMs
        val span = midBeatMs - startBeatMs
        val slip = abs(midFit.periodMs - tS) * span / (tS * tS)
        val nBeats = (span / tS).roundToLong().coerceAtLeast(1L)
        val predicted = startBeatMs + nBeats * tS
        val delta = (midBeatMs - predicted) / tS
        val disagree = abs(midFit.periodMs - tS) / tS > 0.005
        val stable = startFit.tempoStable && midFit.tempoStable && !disagree
        return if (sampleAccurateSeek && slip < 0.25 && abs(delta) < 0.35 && !disagree) {
            LongBaselineMerge(tS + delta * tS / nBeats, phaseLocked = true, tempoStable = stable, slipMetric = slip, deltaBeats = delta)
        } else {
            val vS = (startFit.slopeStdErr * startFit.slopeStdErr).coerceAtLeast(1e-9)
            val vM = (midFit.slopeStdErr * midFit.slopeStdErr).coerceAtLeast(1e-9)
            val merged = (tS / vS + midFit.periodMs / vM) / (1.0 / vS + 1.0 / vM)
            LongBaselineMerge(merged, phaseLocked = false, tempoStable = stable, slipMetric = slip, deltaBeats = delta)
        }
    }

    /** Fills DP gaps (`Δn > 1`) by linear interpolation so beats are strictly consecutive. */
    fun consecutiveBeats(index: IntArray, timesMs: DoubleArray): DoubleArray {
        if (index.isEmpty()) return DoubleArray(0)
        val out = ArrayList<Double>()
        for (i in index.indices) {
            if (i > 0) {
                val dn = index[i] - index[i - 1]
                for (j in 1 until dn) {
                    out.add(timesMs[i - 1] + (timesMs[i] - timesMs[i - 1]) * j / dn)
                }
            }
            out.add(timesMs[i])
        }
        return out.toDoubleArray()
    }

    /** Uniform grid `φ + n·T` covering `[0, durationMs)`. */
    fun uniformBeats(fit: TempoFit, durationMs: Double): DoubleArray {
        val nStart = kotlin.math.ceil((0.0 - fit.phaseMs) / fit.periodMs - 1e-9)
        val nEnd = floor((durationMs - 30.0 - fit.phaseMs) / fit.periodMs)
        if (nEnd < nStart) return DoubleArray(0)
        val count = (nEnd - nStart + 1).toInt().coerceAtMost(20_000)
        return DoubleArray(count) { fit.beatTimeMs(nStart + it) }
    }
}
