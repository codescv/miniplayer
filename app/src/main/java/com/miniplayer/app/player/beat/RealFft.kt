package com.miniplayer.app.player.beat

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small generic radix-2 FFT for real frames (BeatGrid v2 §3.1c).
 *
 * Two real frames `x`, `y` of length [n] are packed into one complex frame `z = x + i·y`, transformed
 * with a single n-point complex FFT (precomputed twiddles + bit-reversal table), then separated:
 * `X[k] = (Z[k] + conj Z[n−k]) / 2`, `Y[k] = (Z[k] − conj Z[n−k]) / 2i`.
 *
 * Not thread-safe (scratch buffers are reused); allocate one instance per analysis.
 */
class RealFft(val n: Int) {
    init {
        require(n >= 4 && (n and (n - 1)) == 0) { "RealFft size must be a power of two >= 4, was $n" }
    }

    val bins: Int = n / 2 + 1

    private val cosTable = DoubleArray(n / 2) { cos(2.0 * PI * it / n) }
    private val sinTable = DoubleArray(n / 2) { -sin(2.0 * PI * it / n) }
    private val bitRev = IntArray(n).also { table ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) {
            table[i] = Integer.reverse(i) ushr (32 - bits)
        }
    }
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)

    /** In-place complex FFT over [re]/[im]. */
    private fun transform() {
        for (i in 0 until n) {
            val j = bitRev[i]
            if (j > i) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val l = j + half
                    val xr = re[l] * wr - im[l] * wi
                    val xi = re[l] * wi + im[l] * wr
                    re[l] = re[j] - xr
                    im[l] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }

    /**
     * Computes magnitude spectra (bins `0..n/2`) of real frames [x] and (optionally) [y], each of
     * length >= [n], already windowed by the caller.
     */
    fun magnitudesPair(x: FloatArray, y: FloatArray?, outX: FloatArray, outY: FloatArray?) {
        for (i in 0 until n) {
            re[i] = x[i].toDouble()
            im[i] = y?.get(i)?.toDouble() ?: 0.0
        }
        transform()
        for (k in 0 until bins) {
            val nk = (n - k) and (n - 1)
            val zr = re[k]
            val zi = im[k]
            val cr = re[nk]
            val ci = -im[nk]
            // X = (Z + conj Z[n-k]) / 2
            val xr = 0.5 * (zr + cr)
            val xi = 0.5 * (zi + ci)
            outX[k] = sqrt(xr * xr + xi * xi).toFloat()
            if (y != null && outY != null) {
                // Y = (Z - conj Z[n-k]) / 2i
                val dr = 0.5 * (zr - cr)
                val di = 0.5 * (zi - ci)
                outY[k] = sqrt(dr * dr + di * di).toFloat()
            }
        }
    }

    companion object {
        fun hann(n: Int): FloatArray = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / n)).toFloat() }
    }
}
