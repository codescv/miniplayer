package com.miniplayer.app.player

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Exact mobile-side STFT (`_spec`), iSTFT (`_ispec`), reflect-padding (`pad1d`), segment chunking
 * (`TensorChunk.padded` + `center_trim`), and triangular overlap-add windowing for Meta Research's
 * `HTDemucs` (`htdemucs_6s`, `cac = True`, `44,100 Hz`, `nfft = 4096`, `hop_length = 1024`).
 *
 * Avoids `torch.stft` / `torch.istft` / `complex64` inside ONNX Runtime Mobile while matching
 * PyTorch `HTDemucs._spec` and `HTDemucs._ispec` to `< 2e-6` max error.
 */
object HtdemucsStft {
    const val SAMPLE_RATE = 44_100
    const val NFFT = 4096
    const val HOP_LENGTH = 1024
    const val FREQ_BINS = 2048 // NFFT / 2 (Nyquist bin 2048 is dropped in HTDemucs)
    const val SEGMENT_SAMPLES = 343_980 // int(7.8 * 44100)
    const val SEGMENT_FRAMES = 336 // ceil(343980 / 1024)
    const val PAD_LEFT = 1536 // (HOP_LENGTH / 2) * 3
    const val DEFAULT_OVERLAP = 0.25f
    const val DEFAULT_STRIDE = 257_985 // int((1 - 0.25) * 343980)
    const val NUM_APP_STEMS = 5 // [VOCALS, GUITAR, BASS, DRUMS, OTHER]

    private const val LOG2_NFFT = 12
    private val INV_SQRT_NFFT = (1.0 / sqrt(NFFT.toDouble())).toFloat() // 1 / 64.0f

    /** Periodic Hann window matching `torch.hann_window(4096, periodic=True)`. */
    val PERIODIC_HANN_4096: FloatArray = FloatArray(NFFT) { n ->
        (0.5 * (1.0 - cos(2.0 * PI * n.toDouble() / NFFT.toDouble()))).toFloat()
    }

    private val HANN_SQ_4096: FloatArray = FloatArray(NFFT) { n ->
        val w = PERIODIC_HANN_4096[n]
        w * w
    }

    private val BIT_REV_4096: IntArray = IntArray(NFFT) { i ->
        var x = i
        var rev = 0
        for (b in 0 until LOG2_NFFT) {
            rev = (rev shl 1) or (x and 1)
            x = x ushr 1
        }
        rev
    }

    // Precomputed radix-2 butterfly twiddle factors for stages len = 2, 4, ..., 4096 (total 4095 entries)
    private val TWIDDLE_COS: FloatArray
    private val TWIDDLE_SIN_FWD: FloatArray

    init {
        val cosArr = FloatArray(NFFT)
        val sinArr = FloatArray(NFFT)
        var offset = 0
        var len = 2
        while (len <= NFFT) {
            val half = len ushr 1
            val angleStep = -2.0 * PI / len.toDouble()
            for (j in 0 until half) {
                val angle = angleStep * j.toDouble()
                cosArr[offset + j] = cos(angle).toFloat()
                sinArr[offset + j] = sin(angle).toFloat()
            }
            offset += half
            len = len shl 1
        }
        TWIDDLE_COS = cosArr
        TWIDDLE_SIN_FWD = sinArr
    }

    // Precomputed WOLA squared-window envelope for standard segment length (340 frames -> 351,232 samples)
    private const val STD_TOTAL_FRAMES = SEGMENT_FRAMES + 4 // 340
    private const val STD_OLA_LEN = (STD_TOTAL_FRAMES - 1) * HOP_LENGTH + NFFT // 351,232
    private const val OLA_TRIM_OFFSET = (NFFT / 2) + PAD_LEFT // 2048 + 1536 = 3584

    private val STD_WIN_SUM: FloatArray = buildWinSum(STD_TOTAL_FRAMES)

    private fun buildWinSum(totalFrames: Int): FloatArray {
        val olaLen = (totalFrames - 1) * HOP_LENGTH + NFFT
        val ws = FloatArray(olaLen)
        for (k in 0 until totalFrames) {
            val base = k * HOP_LENGTH
            for (n in 0 until NFFT) {
                ws[base + n] += HANN_SQ_4096[n]
            }
        }
        return ws
    }

    /**
     * Reusable per-worker scratch buffers so segment STFT/iSTFT performs zero large heap allocations
     * across segments.
     */
    class ScratchBuffers {
        val paddedL = FloatArray(SEGMENT_FRAMES * HOP_LENGTH + 2 * PAD_LEFT)
        val paddedR = FloatArray(SEGMENT_FRAMES * HOP_LENGTH + 2 * PAD_LEFT)
        val fftRe = FloatArray(NFFT)
        val fftIm = FloatArray(NFFT)
        val olaL = FloatArray(STD_OLA_LEN)
        val olaR = FloatArray(STD_OLA_LEN)
    }

    /**
     * Unnormalized in-place radix-2 4096-point complex FFT / IFFT:
     * - `inverse = false`: $X[k] = \sum_{n=0}^{4095} x[n] e^{-i 2\pi k n / 4096}$
     * - `inverse = true`:  $y[n] = \sum_{k=0}^{4095} X[k] e^{+i 2\pi k n / 4096}$ (caller scales as needed)
     */
    fun fft4096Unnormalized(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val bitRev = BIT_REV_4096
        for (i in 0 until NFFT) {
            val j = bitRev[i]
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }

        val cosTab = TWIDDLE_COS
        val sinTab = TWIDDLE_SIN_FWD
        var twOffset = 0
        var len = 2
        if (!inverse) {
            while (len <= NFFT) {
                val half = len ushr 1
                var i = 0
                while (i < NFFT) {
                    for (j in 0 until half) {
                        val wr = cosTab[twOffset + j]
                        val wi = sinTab[twOffset + j]
                        val uIdx = i + j
                        val vIdx = uIdx + half
                        val vr = re[vIdx] * wr - im[vIdx] * wi
                        val vi = re[vIdx] * wi + im[vIdx] * wr
                        val ur = re[uIdx]
                        val ui = im[uIdx]
                        re[uIdx] = ur + vr
                        im[uIdx] = ui + vi
                        re[vIdx] = ur - vr
                        im[vIdx] = ui - vi
                    }
                    i += len
                }
                twOffset += half
                len = len shl 1
            }
        } else {
            while (len <= NFFT) {
                val half = len ushr 1
                var i = 0
                while (i < NFFT) {
                    for (j in 0 until half) {
                        val wr = cosTab[twOffset + j]
                        val wi = -sinTab[twOffset + j]
                        val uIdx = i + j
                        val vIdx = uIdx + half
                        val vr = re[vIdx] * wr - im[vIdx] * wi
                        val vi = re[vIdx] * wi + im[vIdx] * wr
                        val ur = re[uIdx]
                        val ui = im[uIdx]
                        re[uIdx] = ur + vr
                        im[uIdx] = ui + vi
                        re[vIdx] = ur - vr
                        im[vIdx] = ui - vi
                    }
                    i += len
                }
                twOffset += half
                len = len shl 1
            }
        }
    }

    /**
     * Exact port of `demucs.hdemucs.pad1d(x, (paddingLeft, paddingRight), mode = "reflect")`,
     * including small-input zero pre-padding guard when `length <= max(paddingLeft, paddingRight)`.
     */
    fun reflectPad1d(
        src: FloatArray,
        length: Int = src.size,
        paddingLeft: Int,
        paddingRight: Int,
        dst: FloatArray = FloatArray(length + paddingLeft + paddingRight),
    ): FloatArray {
        require(length > 0 && length <= src.size) { "Invalid input length $length" }
        val totalOut = length + paddingLeft + paddingRight
        require(dst.size >= totalOut) { "Destination buffer too small: ${dst.size} < $totalOut" }

        var workSrc = src
        var workLen = length
        var pLeft = paddingLeft
        var pRight = paddingRight

        val maxPad = max(pLeft, pRight)
        if (workLen <= maxPad) {
            val extraPad = maxPad - workLen + 1
            val extraPadRight = min(pRight, extraPad)
            val extraPadLeft = extraPad - extraPadRight
            val prePadded = FloatArray(workLen + extraPadLeft + extraPadRight)
            System.arraycopy(workSrc, 0, prePadded, extraPadLeft, workLen)
            workSrc = prePadded
            workLen = prePadded.size
            pLeft -= extraPadLeft
            pRight -= extraPadRight
        }

        System.arraycopy(workSrc, 0, dst, pLeft, workLen)
        for (k in 0 until pLeft) {
            dst[pLeft - 1 - k] = workSrc[1 + k]
        }
        for (k in 0 until pRight) {
            dst[pLeft + workLen + k] = workSrc[workLen - 2 - k]
        }
        return dst
    }

    /**
     * Computes exact `HTDemucs._magnitude(HTDemucs._spec(xt))` (`cac = True`) for stereo input `(left, right)`
     * of length [length] (default `343,980`), writing Complex-As-Channels `[L_re, L_im, R_re, R_im]`
     * in row-major `[1, 4, 2048, le]` layout into [outSpecCac].
     *
     * Uses packed complex stereo DFT (`z[n] = L[n] + i * R[n]`) to compute both stereo channels in a
     * single 4096-point FFT per frame.
     */
    fun specCacStereo(
        left: FloatArray,
        right: FloatArray,
        length: Int = left.size,
        outSpecCac: FloatArray? = null,
        scratch: ScratchBuffers? = null,
    ): FloatArray {
        require(length > 0 && left.size >= length && right.size >= length)
        val le = ceil(length.toDouble() / HOP_LENGTH.toDouble()).toInt()
        val padLeft = PAD_LEFT
        val padRight = PAD_LEFT + le * HOP_LENGTH - length
        val paddedLen = length + padLeft + padRight

        val padL = if (scratch != null && scratch.paddedL.size >= paddedLen) {
            scratch.paddedL
        } else {
            FloatArray(paddedLen)
        }
        val padR = if (scratch != null && scratch.paddedR.size >= paddedLen) {
            scratch.paddedR
        } else {
            FloatArray(paddedLen)
        }
        reflectPad1d(left, length, padLeft, padRight, padL)
        reflectPad1d(right, length, padLeft, padRight, padR)

        val outSize = 4 * FREQ_BINS * le
        val out = outSpecCac?.also { require(it.size >= outSize) } ?: FloatArray(outSize)

        val re = scratch?.fftRe ?: FloatArray(NFFT)
        val im = scratch?.fftIm ?: FloatArray(NFFT)
        val win = PERIODIC_HANN_4096
        val halfScale = 0.5f * INV_SQRT_NFFT
        val dcScale = INV_SQRT_NFFT

        val chStride = FREQ_BINS * le
        val lReBase = 0
        val lImBase = chStride
        val rReBase = chStride * 2
        val rImBase = chStride * 3

        for (t in 0 until le) {
            val timeBase = t * HOP_LENGTH
            for (n in 0 until NFFT) {
                val w = win[n]
                re[n] = padL[timeBase + n] * w
                im[n] = padR[timeBase + n] * w
            }
            fft4096Unnormalized(re, im, inverse = false)

            // f = 0 (DC bin)
            out[lReBase + t] = re[0] * dcScale
            out[lImBase + t] = 0f
            out[rReBase + t] = im[0] * dcScale
            out[rImBase + t] = 0f

            // f = 1 .. 2047 (unpack two real FFTs from one complex FFT)
            for (f in 1 until FREQ_BINS) {
                val conjIdx = NFFT - f
                val rk = re[f]
                val ik = im[f]
                val rc = re[conjIdx]
                val ic = im[conjIdx]

                val rowOffset = f * le + t
                out[lReBase + rowOffset] = (rk + rc) * halfScale
                out[lImBase + rowOffset] = (ik - ic) * halfScale
                out[rReBase + rowOffset] = (ik + ic) * halfScale
                out[rImBase + rowOffset] = (rc - rk) * halfScale
            }
        }
        return out
    }

    /**
     * Computes exact `HTDemucs._ispec(HTDemucs._mask(z, m), length)` (`cac = True`) for a single
     * stereo stem stored at `[stemOffset .. stemOffset + 4 * 2048 * le)` inside [specCac],
     * writing reconstructed stereo waveform of [length] samples into [outLeft] and [outRight]
     * (optionally adding `timeWave` directly when [addWaveBuffer] is provided).
     */
    fun ispecCacStereoStem(
        specCac: FloatArray,
        stemSpecOffset: Int = 0,
        length: Int = SEGMENT_SAMPLES,
        outLeft: FloatArray,
        outRight: FloatArray,
        addWaveBuffer: FloatArray? = null,
        addWaveStemOffset: Int = 0,
        scratch: ScratchBuffers? = null,
    ) {
        require(outLeft.size >= length && outRight.size >= length)
        val le = ceil(length.toDouble() / HOP_LENGTH.toDouble()).toInt()
        val totalFrames = le + 4
        val olaLen = (totalFrames - 1) * HOP_LENGTH + NFFT

        val olaL: FloatArray
        val olaR: FloatArray
        if (scratch != null && scratch.olaL.size >= olaLen) {
            olaL = scratch.olaL
            olaR = scratch.olaR
            olaL.fill(0f, 0, olaLen)
            olaR.fill(0f, 0, olaLen)
        } else {
            olaL = FloatArray(olaLen)
            olaR = FloatArray(olaLen)
        }

        val winSum = if (totalFrames == STD_TOTAL_FRAMES) STD_WIN_SUM else buildWinSum(totalFrames)
        val re = scratch?.fftRe ?: FloatArray(NFFT)
        val im = scratch?.fftIm ?: FloatArray(NFFT)
        val win = PERIODIC_HANN_4096
        val normScale = INV_SQRT_NFFT // 1 / 64.0f (ortho IFFT normalization on unnormalized IDFT)

        val chStride = FREQ_BINS * le
        val lReBase = stemSpecOffset
        val lImBase = stemSpecOffset + chStride
        val rReBase = stemSpecOffset + chStride * 2
        val rImBase = stemSpecOffset + chStride * 3

        for (t in 0 until le) {
            // Pack Z[k] = X_L[k] + i * X_R[k] where X_L and X_R are Hermitian symmetric, Nyquist bin 2048 = 0
            re[0] = specCac[lReBase + t]
            im[0] = specCac[rReBase + t]
            re[FREQ_BINS] = 0f
            im[FREQ_BINS] = 0f

            for (f in 1 until FREQ_BINS) {
                val idx = f * le + t
                val lr = specCac[lReBase + idx]
                val li = specCac[lImBase + idx]
                val rr = specCac[rReBase + idx]
                val ri = specCac[rImBase + idx]

                re[f] = lr - ri
                im[f] = li + rr
                val conj = NFFT - f
                re[conj] = lr + ri
                im[conj] = -li + rr
            }

            fft4096Unnormalized(re, im, inverse = true)

            val olaBase = (t + 2) * HOP_LENGTH
            for (n in 0 until NFFT) {
                val scale = win[n] * normScale
                olaL[olaBase + n] += re[n] * scale
                olaR[olaBase + n] += im[n] * scale
            }
        }

        if (addWaveBuffer != null) {
            val waveLBase = addWaveStemOffset
            val waveRBase = addWaveStemOffset + length
            for (i in 0 until length) {
                val invWs = 1.0f / winSum[OLA_TRIM_OFFSET + i]
                outLeft[i] = olaL[OLA_TRIM_OFFSET + i] * invWs + addWaveBuffer[waveLBase + i]
                outRight[i] = olaR[OLA_TRIM_OFFSET + i] * invWs + addWaveBuffer[waveRBase + i]
            }
        } else {
            for (i in 0 until length) {
                val invWs = 1.0f / winSum[OLA_TRIM_OFFSET + i]
                outLeft[i] = olaL[OLA_TRIM_OFFSET + i] * invWs
                outRight[i] = olaR[OLA_TRIM_OFFSET + i] * invWs
            }
        }
    }

    /**
     * Exact port of `demucs.apply.apply_model` triangular transition weight (`transition_power = 1.0`):
     * `[1, 2, ..., segmentLength/2, segmentLength - segmentLength/2, ..., 1] / maxWeight`.
     */
    fun buildTriangularSegmentWeight(segmentLength: Int = SEGMENT_SAMPLES): FloatArray {
        val half = segmentLength / 2
        val rem = segmentLength - half
        val maxVal = max(half, rem).toFloat().coerceAtLeast(1f)
        val w = FloatArray(segmentLength)
        for (i in 0 until half) {
            w[i] = (i + 1).toFloat() / maxVal
        }
        for (i in 0 until rem) {
            w[half + i] = (rem - i).toFloat() / maxVal
        }
        return w
    }

    /**
     * Exact port of `TensorChunk(mix, offset, chunkLength).padded(targetLength)` from `demucs/apply.py`:
     * centers a sub-chunk `[offset .. offset + chunkLength)` inside a `targetLength` window, pulling real
     * neighboring samples from `[0 .. totalFrames)` when available and zero-padding beyond track edges.
     */
    fun computeCenteredChunkWindow(
        totalFrames: Int,
        offset: Int,
        chunkLength: Int,
        targetLength: Int = SEGMENT_SAMPLES,
    ): CenteredChunkWindow {
        val delta = targetLength - chunkLength
        require(delta >= 0) { "targetLength ($targetLength) must be >= chunkLength ($chunkLength)" }
        val start = offset - delta / 2
        val end = start + targetLength
        val correctStart = max(0, start)
        val correctEnd = min(totalFrames, end)
        val padLeft = correctStart - start
        val padRight = end - correctEnd
        val centerTrimStart = delta / 2
        return CenteredChunkWindow(
            sourceStartFrame = correctStart,
            sourceEndFrame = correctEnd,
            padLeftFrames = padLeft,
            padRightFrames = padRight,
            centerTrimOffset = centerTrimStart,
            chunkLength = chunkLength,
            targetLength = targetLength,
        )
    }

    data class CenteredChunkWindow(
        val sourceStartFrame: Int,
        val sourceEndFrame: Int,
        val padLeftFrames: Int,
        val padRightFrames: Int,
        val centerTrimOffset: Int,
        val chunkLength: Int,
        val targetLength: Int,
    )
}
