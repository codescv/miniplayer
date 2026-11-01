package com.miniplayer.app.player.beat

import kotlin.math.abs
import kotlin.math.sqrt

/** Small robust-statistics helpers shared by the BeatGrid v2 stages. */
internal object Stats {
    fun percentilePositive(values: FloatArray, q: Double): Double {
        val pos = values.filter { it > 1e-6f }
        if (pos.isEmpty()) return 0.0
        val sorted = pos.sorted()
        return sorted[((sorted.size - 1) * q.coerceIn(0.0, 1.0)).toInt()].toDouble()
    }

    fun percentile(values: DoubleArray, q: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sortedArray()
        val pos = (sorted.size - 1) * q.coerceIn(0.0, 1.0)
        val i = pos.toInt()
        val frac = pos - i
        return if (i + 1 < sorted.size) sorted[i] * (1 - frac) + sorted[i + 1] * frac else sorted[i]
    }

    fun median(values: DoubleArray): Double = percentile(values, 0.5)

    fun mad(values: DoubleArray, center: Double = median(values)): Double {
        if (values.isEmpty()) return 0.0
        return median(DoubleArray(values.size) { abs(values[it] - center) })
    }

    fun weightedMedian(values: DoubleArray, weights: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val order = values.indices.sortedBy { values[it] }
        val total = weights.sum()
        if (total <= 0.0) return median(values)
        var acc = 0.0
        for (i in order) {
            acc += weights[i]
            if (acc >= total * 0.5) return values[i]
        }
        return values[order.last()]
    }

    /**
     * Robust standardisation with a floored scale (§3.4):
     * `s = max(1.4826·MAD, p90 − p50, floor)`, `z = (x − median)/s` clipped to `[−2, 3]`.
     * [floorFrac] adds a floor relative to the 99th percentile of |x| so sparse features cannot
     * explode noise.
     */
    fun robustZ(values: DoubleArray, absFloor: Double, floorFrac: Double = 0.0): DoubleArray {
        if (values.isEmpty()) return values
        val med = median(values)
        val mad = mad(values, med)
        val p90 = percentile(values, 0.90)
        val p99abs = percentile(DoubleArray(values.size) { abs(values[it]) }, 0.99)
        val s = maxOf(1.4826 * mad, p90 - med, absFloor, floorFrac * p99abs, 1e-9)
        return DoubleArray(values.size) { ((values[it] - med) / s).coerceIn(-2.0, 3.0) }
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        return if (na > 1e-12 && nb > 1e-12) dot / sqrt(na * nb) else 0.0
    }

    /** Zero-mean, L2-normalised copy (`zm(·)` in §3.5). */
    fun zm(raw: DoubleArray): DoubleArray {
        val mean = raw.average()
        val out = DoubleArray(raw.size) { raw[it] - mean }
        var norm = 0.0
        for (v in out) norm += v * v
        norm = sqrt(norm)
        if (norm > 1e-12) for (i in out.indices) out[i] /= norm
        return out
    }
}
