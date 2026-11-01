package com.miniplayer.app.player

import com.miniplayer.app.core.model.StemMixConfig
import com.miniplayer.app.core.model.StemType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time Zero-Phase Overlap-Add STFT processor (`N = 1024`, `hop = 256`, `75%` overlap,
 * periodic Hann analysis + synthesis window with exact COLA normalization `1 / 1.5`) providing:
 *
 * 1. **Formant-Preserving Pitch Shifting** (`preserveFormants = true`):
 *    Restores the original vocal spectral envelope $E_{\text{orig}}[k] = E_{\text{shifted}}[k \cdot p]$
 *    after `SonicAudioProcessor` time-domain pitch scaling so pitched-down/up vocals retain natural
 *    human timbre without chipmunk or gender-shift artifacts, while keeping 100% of Sonic's phase
 *    coherence and drum/guitar transient attacks.
 *
 * 2. **Hybrid 5-Stem Real-Time Soft-Mask Wiener Mixer** (`vocals`, `guitar`, `bass`, `drums`, `other`):
 *    Combines instantaneous stereo Mid/Side center-affinity $C[k]$, harmonic-percussive transient
 *    anisotropy $(H_{\text{harm}}[k], P_{\text{perc}}[k])$, and optional pre-analyzed neural-spectral
 *    frame priors ([StemTrackMaskGrid]) into 5 non-negative soft masks summing strictly to `1.0`
 *    at every frequency bin, applying separate Mid/Side gains $(G_M[k], G_S[k])$.
 */
class FormantStemDspProcessor {

    companion object {
        const val FFT_SIZE = 1024
        const val HOP_SIZE = 256
        const val NUM_BINS = FFT_SIZE / 2 + 1 // 513
        const val WARMUP_HOPS = FFT_SIZE / HOP_SIZE - 1 // 3 hops = 768 samples
        const val COLA_NORM = 1.0f / 1.5f
        const val PITCH_ACTIVE_EPSILON = 0.005f
        private const val LOG_FLOOR = 1e-7f
        private const val MAX_FORMANT_LOG_GAIN = 2.2f // ~ +/-9.5 dB
        private const val FORMANT_SMOOTH_PREV = 0.65f
        private const val FORMANT_SMOOTH_CUR = 0.35f
        private const val MAX_CHANNELS = 2

        /** 16 perceptual Bark/Mel band edges (Hz) shared with [StemSeparationEngine]. */
        val BARK_BAND_EDGES_HZ: FloatArray = floatArrayOf(
            0f, 60f, 115f, 180f, 260f, 370f, 510f, 700f, 960f,
            1300f, 1750f, 2350f, 3150f, 4200f, 5600f, 8000f, 24000f,
        )
        const val NUM_BARK_BANDS = 16
        const val NUM_STEMS = 5

        // Mid/Side spatial emphasis per stem: [VOCALS, GUITAR, BASS, DRUMS, OTHER]
        private val MID_STEM_EMPHASIS = floatArrayOf(1.45f, 0.72f, 1.40f, 1.00f, 0.85f)
        private val SIDE_STEM_EMPHASIS = floatArrayOf(0.14f, 1.55f, 0.18f, 0.95f, 1.35f)

        // Periodic Hann window w[n] = 0.5 * (1 - cos(2*pi*n / N))
        internal val HANN_WINDOW: FloatArray = FloatArray(FFT_SIZE) { n ->
            (0.5 * (1.0 - cos(2.0 * PI * n / FFT_SIZE))).toFloat()
        }

        // Precomputed bit-reversal permutation and twiddle factors for radix-2 N=1024 FFT
        private val BIT_REV_1024: IntArray = IntArray(FFT_SIZE).also { rev ->
            val bits = 10 // 2^10 = 1024
            for (i in 0 until FFT_SIZE) {
                var x = i
                var r = 0
                for (b in 0 until bits) {
                    r = (r shl 1) or (x and 1)
                    x = x ushr 1
                }
                rev[i] = r
            }
        }

        private val TWIDDLE_COS: FloatArray = FloatArray(FFT_SIZE / 2) { k ->
            cos(2.0 * PI * k / FFT_SIZE).toFloat()
        }
        private val TWIDDLE_SIN: FloatArray = FloatArray(FFT_SIZE / 2) { k ->
            (-sin(2.0 * PI * k / FFT_SIZE)).toFloat()
        }

        /**
         * In-place radix-2 Cooley-Tukey complex FFT (`N = 1024`).
         * If [inverse] is true, computes unnormalized inverse before scaling by `1 / N`.
         */
        internal fun fft1024(re: FloatArray, im: FloatArray, inverse: Boolean) {
            // Bit-reversal permutation
            for (i in 0 until FFT_SIZE) {
                val j = BIT_REV_1024[i]
                if (i < j) {
                    val tr = re[i]
                    re[i] = re[j]
                    re[j] = tr
                    val ti = im[i]
                    im[i] = im[j]
                    im[j] = ti
                }
            }

            var len = 2
            while (len <= FFT_SIZE) {
                val half = len ushr 1
                val step = FFT_SIZE / len
                var i = 0
                while (i < FFT_SIZE) {
                    var twIdx = 0
                    for (j in i until i + half) {
                        val wr = TWIDDLE_COS[twIdx]
                        val wi = if (inverse) -TWIDDLE_SIN[twIdx] else TWIDDLE_SIN[twIdx]
                        val k = j + half
                        val tr = wr * re[k] - wi * im[k]
                        val ti = wr * im[k] + wi * re[k]
                        re[k] = re[j] - tr
                        im[k] = im[j] - ti
                        re[j] += tr
                        im[j] += ti
                        twIdx += step
                    }
                    i += len
                }
                len = len shl 1
            }

            if (inverse) {
                val invN = 1.0f / FFT_SIZE
                for (i in 0 until FFT_SIZE) {
                    re[i] *= invN
                    im[i] *= invN
                }
            }
        }

        fun bandIndexForFrequency(freqHz: Float): Int {
            for (b in 0 until NUM_BARK_BANDS - 1) {
                if (freqHz < BARK_BAND_EDGES_HZ[b + 1]) return b
            }
            return NUM_BARK_BANDS - 1
        }

        /**
         * Raised-cosine vocal formant band weight alpha(f) in [0, 1]:
         * 130..220 Hz fade-in, 220..4500 Hz = 1.0, 4500..6200 Hz fade-out.
         */
        fun computeFormantBandWeight(freqHz: Float): Float = when {
            freqHz <= 130f -> 0f
            freqHz < 220f -> (0.5 * (1.0 - cos(PI * (freqHz - 130f) / 90.0))).toFloat()
            freqHz <= 4500f -> 1.0f
            freqHz < 6200f -> (0.5 * (1.0 + cos(PI * (freqHz - 4500f) / 1700.0))).toFloat()
            else -> 0f
        }

        private fun raisedBandMask(
            freqHz: Float,
            fadeInStart: Float,
            fullStart: Float,
            fullEnd: Float,
            fadeOutEnd: Float,
        ): Float = when {
            freqHz <= fadeInStart || freqHz >= fadeOutEnd -> 0f
            freqHz < fullStart -> {
                val span = (fullStart - fadeInStart).coerceAtLeast(1f)
                (0.5 * (1.0 - cos(PI * (freqHz - fadeInStart) / span))).toFloat()
            }
            freqHz <= fullEnd -> 1.0f
            else -> {
                val span = (fadeOutEnd - fullEnd).coerceAtLeast(1f)
                (0.5 * (1.0 + cos(PI * (freqHz - fullEnd) / span))).toFloat()
            }
        }

        /**
         * Computes the 5 instantaneous spectral-spatial-HPSS raw stem affinities for a single bin,
         * optionally fused with offline neural band priors (`neuralPriors5`, length 5), and writes
         * normalized soft Wiener masks summing strictly to `1.0` into [outMasks5]
         * (`[VOCALS, GUITAR, BASS, DRUMS, OTHER]`).
         */
        fun computeBinSoftStemMasks(
            freqHz: Float,
            centerAffinity: Float,
            percussiveRatio: Float,
            neuralPriors5: FloatArray?,
            neuralOffset: Int,
            outMasks5: FloatArray,
        ) {
            val c = centerAffinity.coerceIn(0f, 1f)
            val perc = percussiveRatio.coerceIn(0f, 1f)
            val harm = 1.0f - perc

            // 1. Bass: concentrated 20..260 Hz (with harmonic taper 260..420 Hz), high center-affinity
            val bassBand = when {
                freqHz < 18f -> 0.25f
                freqHz <= 260f -> 1.0f
                freqHz < 420f -> (0.5 * (1.0 + cos(PI * (freqHz - 260f) / 160.0))).toFloat()
                else -> 0f
            }
            var wBass = bassBand * (0.35f + 0.65f * c) * (0.35f + 0.65f * harm) * 1.45f

            // 2. Drums: proportional to percussive transient ratio across kick, snare & hi-hat/cymbals
            val kickZone = raisedBandMask(freqHz, 32f, 48f, 120f, 165f)
            val snareBodyZone = raisedBandMask(freqHz, 135f, 160f, 350f, 460f)
            val snareCrackZone = raisedBandMask(freqHz, 1200f, 1600f, 4800f, 5800f)
            val cymbalZone = when {
                freqHz <= 4600f -> 0f
                freqHz < 5600f -> ((freqHz - 4600f) / 1000f)
                else -> 1.0f
            }
            var wDrums = perc * (1.40f * kickZone + 1.10f * snareBodyZone + 1.25f * snareCrackZone) +
                cymbalZone * (0.25f + 1.45f * perc)

            // 3. Vocals: harmonic energy in 180..4200 Hz with high center-affinity (C^2) & formant presence
            val vocalBand = raisedBandMask(freqHz, 145f, 200f, 4000f, 5200f)
            val formantCore = 0.70f + 0.45f * raisedBandMask(freqHz, 280f, 420f, 3200f, 4000f)
            var wVocals = vocalBand * harm * (c * c) * formantCore * 1.55f

            // 4. Guitar: harmonic + plucked-attack energy in 110..4800 Hz weighted by stereo/side presence
            val guitarBand = raisedBandMask(freqHz, 95f, 125f, 4400f, 5400f)
            val guitarBody = 0.65f + 0.50f * raisedBandMask(freqHz, 155f, 210f, 3400f, 4200f)
            val sidePresence = (1.0f - 0.65f * c).coerceIn(0.15f, 1.0f)
            var wGuitar = guitarBand * guitarBody * (0.72f * harm + 0.28f * perc) * sidePresence * 1.45f

            // 5. Other: residual harmonic/ambient pad & air energy ensuring positive partition of unity
            val highHarmAir = if (freqHz > 3800f) 0.22f * harm else 0.0f
            var wOther = 0.08f + (0.20f * harm * (1.0f - 0.35f * c)) + highHarmAir

            if (neuralPriors5 != null) {
                // Blend instantaneous bin physics with neural 16-band track prior
                val nv = neuralPriors5[neuralOffset].coerceAtLeast(0.02f)
                val ng = neuralPriors5[neuralOffset + 1].coerceAtLeast(0.02f)
                val nb = neuralPriors5[neuralOffset + 2].coerceAtLeast(0.02f)
                val nd = neuralPriors5[neuralOffset + 3].coerceAtLeast(0.02f)
                val no = neuralPriors5[neuralOffset + 4].coerceAtLeast(0.02f)
                wVocals *= (0.40f + 3.0f * nv)
                wGuitar *= (0.40f + 3.0f * ng)
                wBass *= (0.40f + 3.0f * nb)
                wDrums *= (0.40f + 3.0f * nd)
                wOther *= (0.40f + 3.0f * no)
            }

            val sum = wVocals + wGuitar + wBass + wDrums + wOther
            if (sum > 1e-9f) {
                val inv = 1.0f / sum
                outMasks5[0] = wVocals * inv
                outMasks5[1] = wGuitar * inv
                outMasks5[2] = wBass * inv
                outMasks5[3] = wDrums * inv
                outMasks5[4] = 1.0f - (outMasks5[0] + outMasks5[1] + outMasks5[2] + outMasks5[3])
                if (outMasks5[4] < 0f) {
                    outMasks5[4] = 0f
                    val renorm = 1.0f / (outMasks5[0] + outMasks5[1] + outMasks5[2] + outMasks5[3]).coerceAtLeast(1e-9f)
                    outMasks5[0] *= renorm
                    outMasks5[1] *= renorm
                    outMasks5[2] *= renorm
                    outMasks5[3] *= renorm
                }
            } else {
                outMasks5[0] = 0.2f
                outMasks5[1] = 0.2f
                outMasks5[2] = 0.2f
                outMasks5[3] = 0.2f
                outMasks5[4] = 0.2f
            }
        }
    }

    private var sampleRateHz: Int = 44_100
    private var channelCount: Int = 2
    private var envelopeHalfWidthBins: Int = 7

    // Precomputed per-bin frequency, formant weight alpha[k], and Bark band index
    private val binFreqHz = FloatArray(NUM_BINS)
    private val formantAlpha = FloatArray(NUM_BINS)
    private val binToBarkBand = IntArray(NUM_BINS)

    // Streaming WOLA ring/shift buffers per channel (normalized float [-1, 1])
    private val analysisFrames = Array(MAX_CHANNELS) { FloatArray(FFT_SIZE) }
    private val olaFrames = Array(MAX_CHANNELS) { FloatArray(FFT_SIZE) }
    private val inHopStaging = Array(MAX_CHANNELS) { FloatArray(HOP_SIZE) }
    private var stagedHopFrames: Int = 0
    private var warmupHopsRemaining: Int = WARMUP_HOPS
    private var unflushedLatencyFrames: Int = 0

    // Working FFT & spectral arrays (zero per-hop heap allocations)
    private val fftRe = Array(MAX_CHANNELS) { FloatArray(FFT_SIZE) }
    private val fftIm = Array(MAX_CHANNELS) { FloatArray(FFT_SIZE) }
    private val binMag = FloatArray(NUM_BINS)
    private val logMag = FloatArray(NUM_BINS)
    private val prefixSum = DoubleArray(NUM_BINS + 1)
    private val envShifted = FloatArray(NUM_BINS)
    private val smoothedFormantGain = FloatArray(NUM_BINS) { 1.0f }

    // Stem separation temporal state
    private val prevLogMag = FloatArray(NUM_BINS)
    private val harmLogEma = FloatArray(NUM_BINS)
    private var percHistoryPrimed: Boolean = false
    private val smoothedMidGain = FloatArray(NUM_BINS) { 1.0f }
    private val smoothedSideGain = FloatArray(NUM_BINS) { 1.0f }
    private val binMasks5 = FloatArray(NUM_STEMS)
    private val frameNeuralPriors = FloatArray(NUM_BARK_BANDS * NUM_STEMS)
    private val effectiveStemGains = FloatArray(NUM_STEMS) { 1.0f }

    // Reusable PCM output buffer (interleaved shorts)
    private var scratchOutShorts = ShortArray(4096)

    val hasBufferedFrames: Boolean
        get() = stagedHopFrames > 0 || unflushedLatencyFrames > 0

    fun configure(sampleRateHz: Int, channelCount: Int) {
        val safeSr = sampleRateHz.coerceAtLeast(8_000)
        val safeCh = channelCount.coerceIn(1, MAX_CHANNELS)
        if (this.sampleRateHz != safeSr || this.channelCount != safeCh) {
            this.sampleRateHz = safeSr
            this.channelCount = safeCh
            recomputeBinTables()
            resetState()
        } else if (binFreqHz[1] == 0f) {
            recomputeBinTables()
        }
    }

    init {
        recomputeBinTables()
    }

    private fun recomputeBinTables() {
        envelopeHalfWidthBins = max(6, (320.0 * FFT_SIZE / sampleRateHz).roundToInt())
        val hzPerBin = sampleRateHz.toFloat() / FFT_SIZE.toFloat()
        for (k in 0 until NUM_BINS) {
            val f = k * hzPerBin
            binFreqHz[k] = f
            formantAlpha[k] = computeFormantBandWeight(f)
            binToBarkBand[k] = bandIndexForFrequency(f)
        }
    }

    fun resetState() {
        for (ch in 0 until MAX_CHANNELS) {
            analysisFrames[ch].fill(0f)
            olaFrames[ch].fill(0f)
            inHopStaging[ch].fill(0f)
        }
        stagedHopFrames = 0
        warmupHopsRemaining = WARMUP_HOPS
        unflushedLatencyFrames = 0
        smoothedFormantGain.fill(1.0f)
        smoothedMidGain.fill(1.0f)
        smoothedSideGain.fill(1.0f)
        prevLogMag.fill(0f)
        harmLogEma.fill(0f)
        percHistoryPrimed = false
    }

    /**
     * Processes interleaved 16-bit PCM [inputShorts] (`offsetShorts` .. `offsetShorts + lengthShorts`)
     * and returns the number of interleaved 16-bit samples written into [outputShorts].
     */
    fun processInterleavedPcm16(
        inputShorts: ShortArray,
        offsetShorts: Int = 0,
        lengthShorts: Int = inputShorts.size - offsetShorts,
        pitchFactor: Float,
        preserveFormants: Boolean,
        stemMix: StemMixConfig,
        stemMaskGrid: StemTrackMaskGrid? = null,
        playheadPositionMs: Long = 0L,
    ): ShortArray {
        val chCount = channelCount
        val inputFrames = lengthShorts / chCount
        if (inputFrames <= 0) return ShortArray(0)

        val maxPossibleOutFrames = (stagedHopFrames + inputFrames)
        ensureOutCapacity(maxPossibleOutFrames * chCount)

        var outShortPos = 0
        var inFrameIdx = 0
        val hopDurationMs = (HOP_SIZE * 1000L) / sampleRateHz.coerceAtLeast(1)
        var currentHopMs = playheadPositionMs

        while (inFrameIdx < inputFrames) {
            val spaceInHop = HOP_SIZE - stagedHopFrames
            val toCopy = min(spaceInHop, inputFrames - inFrameIdx)
            var srcIdx = offsetShorts + inFrameIdx * chCount
            for (f in 0 until toCopy) {
                val dstIdx = stagedHopFrames + f
                for (c in 0 until chCount) {
                    inHopStaging[c][dstIdx] = inputShorts[srcIdx++] * (1.0f / 32768.0f)
                }
            }
            stagedHopFrames += toCopy
            inFrameIdx += toCopy

            if (stagedHopFrames == HOP_SIZE) {
                stagedHopFrames = 0
                unflushedLatencyFrames += HOP_SIZE
                processSingleHop(
                    pitchFactor = pitchFactor,
                    preserveFormants = preserveFormants,
                    stemMix = stemMix,
                    stemMaskGrid = stemMaskGrid,
                    playheadMs = currentHopMs,
                )
                currentHopMs += hopDurationMs

                if (warmupHopsRemaining > 0) {
                    warmupHopsRemaining--
                    shiftOlaWithoutEmit()
                } else {
                    outShortPos = emitOlaFrames(HOP_SIZE, scratchOutShorts, outShortPos)
                    unflushedLatencyFrames -= HOP_SIZE
                }
            }
        }

        return scratchOutShorts.copyOfRange(0, outShortPos)
    }

    /**
     * Drains all remaining WOLA latency frames (`<= 768 + 255` frames) so total output frame count
     * matches total input frame count 1:1 on stream end or transition back to bit-exact bypass.
     */
    fun drainRemaining(
        pitchFactor: Float,
        preserveFormants: Boolean,
        stemMix: StemMixConfig,
        stemMaskGrid: StemTrackMaskGrid? = null,
        playheadPositionMs: Long = 0L,
    ): ShortArray {
        val chCount = channelCount
        val totalFramesOwed = unflushedLatencyFrames + stagedHopFrames
        if (totalFramesOwed <= 0) {
            resetState()
            return ShortArray(0)
        }

        ensureOutCapacity((totalFramesOwed + HOP_SIZE) * chCount)
        var outShortPos = 0
        var remainingOwed = totalFramesOwed

        if (stagedHopFrames > 0) {
            for (c in 0 until chCount) {
                inHopStaging[c].fill(0f, stagedHopFrames, HOP_SIZE)
            }
            stagedHopFrames = 0
            processSingleHop(
                pitchFactor = pitchFactor,
                preserveFormants = preserveFormants,
                stemMix = stemMix,
                stemMaskGrid = stemMaskGrid,
                playheadMs = playheadPositionMs,
            )
            if (warmupHopsRemaining > 0) {
                warmupHopsRemaining--
                shiftOlaWithoutEmit()
            } else {
                val emitCount = min(HOP_SIZE, remainingOwed)
                outShortPos = emitOlaFrames(emitCount, scratchOutShorts, outShortPos)
                remainingOwed -= emitCount
            }
        }

        var safety = 0
        while (remainingOwed > 0 && safety++ < WARMUP_HOPS + 4) {
            for (c in 0 until chCount) {
                inHopStaging[c].fill(0f)
            }
            processSingleHop(
                pitchFactor = pitchFactor,
                preserveFormants = preserveFormants,
                stemMix = stemMix,
                stemMaskGrid = stemMaskGrid,
                playheadMs = playheadPositionMs,
            )
            if (warmupHopsRemaining > 0) {
                warmupHopsRemaining--
                shiftOlaWithoutEmit()
            } else {
                val emitCount = min(HOP_SIZE, remainingOwed)
                outShortPos = emitOlaFrames(emitCount, scratchOutShorts, outShortPos)
                remainingOwed -= emitCount
            }
        }

        resetState()
        return scratchOutShorts.copyOfRange(0, outShortPos)
    }

    private fun processSingleHop(
        pitchFactor: Float,
        preserveFormants: Boolean,
        stemMix: StemMixConfig,
        stemMaskGrid: StemTrackMaskGrid?,
        playheadMs: Long,
    ) {
        val chCount = channelCount
        val keepLen = FFT_SIZE - HOP_SIZE

        // 1. Shift analysis buffer left by HOP_SIZE, append new hop, apply periodic Hann window, run FFT
        for (c in 0 until chCount) {
            val ana = analysisFrames[c]
            System.arraycopy(ana, HOP_SIZE, ana, 0, keepLen)
            System.arraycopy(inHopStaging[c], 0, ana, keepLen, HOP_SIZE)

            val re = fftRe[c]
            val im = fftIm[c]
            for (n in 0 until FFT_SIZE) {
                re[n] = ana[n] * HANN_WINDOW[n]
                im[n] = 0f
            }
            fft1024(re, im, inverse = false)
        }

        val applyFormant = preserveFormants && abs(pitchFactor - 1.0f) >= PITCH_ACTIVE_EPSILON
        val applyStems = !stemMix.isUnity

        if (applyFormant || applyStems) {
            // Compute RMS magnitude & log-magnitude across channels for bins 0..N/2
            val invCh = 1.0f / chCount.toFloat()
            for (k in 0 until NUM_BINS) {
                var magSqSum = 0f
                for (c in 0 until chCount) {
                    val r = fftRe[c][k]
                    val i = fftIm[c][k]
                    magSqSum += r * r + i * i
                }
                val mag = sqrt(magSqSum * invCh)
                binMag[k] = mag
                logMag[k] = ln(mag + LOG_FLOOR)
            }

            if (applyFormant) {
                applyZeroPhaseFormantCompensation(pitchFactor)
            }

            if (applyStems) {
                applySoftMaskStemMixing(stemMix, stemMaskGrid, playheadMs)
            } else {
                // Keep percussive EMA warm even while stems are unity
                updatePercussiveHistoryOnly()
            }

            // Reconstruct conjugate symmetry for bins N/2+1 .. N-1 so IFFT produces exact real output
            for (c in 0 until chCount) {
                val re = fftRe[c]
                val im = fftIm[c]
                im[0] = 0f
                im[FFT_SIZE / 2] = 0f
                for (k in 1 until FFT_SIZE / 2) {
                    val mirror = FFT_SIZE - k
                    re[mirror] = re[k]
                    im[mirror] = -im[k]
                }
            }
        }

        // 2. Inverse FFT + synthesis periodic Hann window + COLA 1/1.5 overlap-add
        for (c in 0 until chCount) {
            val re = fftRe[c]
            val im = fftIm[c]
            fft1024(re, im, inverse = true)
            val ola = olaFrames[c]
            for (n in 0 until FFT_SIZE) {
                ola[n] += re[n] * HANN_WINDOW[n] * COLA_NORM
            }
        }
    }

    private fun applyZeroPhaseFormantCompensation(pitchFactor: Float) {
        // Step 2: Fast O(N) prefix-sum moving average over bins with half-width W
        prefixSum[0] = 0.0
        for (k in 0 until NUM_BINS) {
            prefixSum[k + 1] = prefixSum[k] + logMag[k]
        }
        val w = envelopeHalfWidthBins
        for (k in 0 until NUM_BINS) {
            val lo = max(0, k - w)
            val hi = min(NUM_BINS - 1, k + w)
            envShifted[k] = ((prefixSum[hi + 1] - prefixSum[lo]) / (hi - lo + 1)).toFloat()
        }

        // Steps 3-5: E_orig[k] = E_shifted[clamp(k * p, 0, N/2)], zero-phase gain + RMS energy match
        var preEnergy = 0.0
        var postEnergy = 0.0
        val maxBinF = (NUM_BINS - 1).toFloat()

        for (k in 0 until NUM_BINS) {
            val srcPos = (k * pitchFactor).coerceIn(0f, maxBinF)
            val i0 = srcPos.toInt().coerceIn(0, NUM_BINS - 1)
            val i1 = (i0 + 1).coerceAtMost(NUM_BINS - 1)
            val frac = srcPos - i0
            val envOrig = envShifted[i0] * (1.0f - frac) + envShifted[i1] * frac

            val deltaLog = (formantAlpha[k] * (envOrig - envShifted[k]))
                .coerceIn(-MAX_FORMANT_LOG_GAIN, MAX_FORMANT_LOG_GAIN)
            val instGain = exp(deltaLog)
            val g = FORMANT_SMOOTH_PREV * smoothedFormantGain[k] + FORMANT_SMOOTH_CUR * instGain
            smoothedFormantGain[k] = g

            val weight = if (k == 0 || k == NUM_BINS - 1) 1.0 else 2.0
            val mSq = (binMag[k] * binMag[k]).toDouble()
            preEnergy += weight * mSq
            postEnergy += weight * mSq * (g * g).toDouble()
        }

        val rmsNorm = if (preEnergy > 1e-8 && postEnergy > 1e-8) {
            sqrt(preEnergy / postEnergy).toFloat().coerceIn(0.35f, 2.8f)
        } else {
            1.0f
        }

        val chCount = channelCount
        for (k in 0 until NUM_BINS) {
            val totalGain = smoothedFormantGain[k] * rmsNorm
            for (c in 0 until chCount) {
                fftRe[c][k] *= totalGain
                fftIm[c][k] *= totalGain
            }
            binMag[k] *= totalGain
            logMag[k] = ln(binMag[k] + LOG_FLOOR)
        }
    }

    private fun updatePercussiveHistoryOnly() {
        if (!percHistoryPrimed) {
            System.arraycopy(logMag, 0, harmLogEma, 0, NUM_BINS)
            System.arraycopy(logMag, 0, prevLogMag, 0, NUM_BINS)
            percHistoryPrimed = true
            return
        }
        for (k in 0 until NUM_BINS) {
            val cur = logMag[k]
            harmLogEma[k] = 0.88f * harmLogEma[k] + 0.12f * cur
            prevLogMag[k] = cur
        }
    }

    private fun applySoftMaskStemMixing(
        stemMix: StemMixConfig,
        stemMaskGrid: StemTrackMaskGrid?,
        playheadMs: Long,
    ) {
        effectiveStemGains[0] = stemMix.effectiveGain(StemType.VOCALS)
        effectiveStemGains[1] = stemMix.effectiveGain(StemType.GUITAR)
        effectiveStemGains[2] = stemMix.effectiveGain(StemType.BASS)
        effectiveStemGains[3] = stemMix.effectiveGain(StemType.DRUMS)
        effectiveStemGains[4] = stemMix.effectiveGain(StemType.OTHER)

        val hasNeural = if (stemMaskGrid != null) {
            stemMaskGrid.lookupBandMasksAt(playheadMs, frameNeuralPriors)
        } else {
            false
        }

        if (!percHistoryPrimed) {
            System.arraycopy(logMag, 0, harmLogEma, 0, NUM_BINS)
            System.arraycopy(logMag, 0, prevLogMag, 0, NUM_BINS)
            percHistoryPrimed = true
        }

        val isStereo = channelCount >= 2

        for (k in 0 until NUM_BINS) {
            val curLog = logMag[k]
            val flux = max(0f, curLog - harmLogEma[k]) + 0.55f * max(0f, curLog - prevLogMag[k])
            val pPerc = (flux / (flux + 0.80f)).coerceIn(0f, 0.96f)
            harmLogEma[k] = 0.88f * harmLogEma[k] + 0.12f * curLog
            prevLogMag[k] = curLog

            if (isStereo) {
                val xlRe = fftRe[0][k]
                val xlIm = fftIm[0][k]
                val xrRe = fftRe[1][k]
                val xrIm = fftIm[1][k]

                val mRe = 0.5f * (xlRe + xrRe)
                val mIm = 0.5f * (xlIm + xrIm)
                val sRe = 0.5f * (xlRe - xrRe)
                val sIm = 0.5f * (xlIm - xrIm)

                val mSq = mRe * mRe + mIm * mIm
                val sSq = sRe * sRe + sIm * sIm
                val centerAffinity = mSq / (mSq + sSq + 1e-9f)

                val bandOffset = binToBarkBand[k] * NUM_STEMS
                computeBinSoftStemMasks(
                    freqHz = binFreqHz[k],
                    centerAffinity = centerAffinity,
                    percussiveRatio = pPerc,
                    neuralPriors5 = if (hasNeural) frameNeuralPriors else null,
                    neuralOffset = bandOffset,
                    outMasks5 = binMasks5,
                )

                // Mid & Side channel-specific composite gains (both strictly 1.0 when all g_s == 1.0)
                var midWeightSum = 0f
                var midWeightedGain = 0f
                var sideWeightSum = 0f
                var sideWeightedGain = 0f
                for (s in 0 until NUM_STEMS) {
                    val m = binMasks5[s]
                    val gs = effectiveStemGains[s]
                    val mw = m * MID_STEM_EMPHASIS[s]
                    val sw = m * SIDE_STEM_EMPHASIS[s]
                    midWeightSum += mw
                    midWeightedGain += gs * mw
                    sideWeightSum += sw
                    sideWeightedGain += gs * sw
                }
                val targetGM = if (midWeightSum > 1e-9f) midWeightedGain / midWeightSum else 1.0f
                val targetGS = if (sideWeightSum > 1e-9f) sideWeightedGain / sideWeightSum else 1.0f

                val gM = 0.35f * smoothedMidGain[k] + 0.65f * targetGM
                val gS = 0.35f * smoothedSideGain[k] + 0.65f * targetGS
                smoothedMidGain[k] = gM
                smoothedSideGain[k] = gS

                val outMRe = mRe * gM
                val outMIm = mIm * gM
                val outSRe = sRe * gS
                val outSIm = sIm * gS

                fftRe[0][k] = outMRe + outSRe
                fftIm[0][k] = outMIm + outSIm
                fftRe[1][k] = outMRe - outSRe
                fftIm[1][k] = outMIm - outSIm
            } else {
                val bandOffset = binToBarkBand[k] * NUM_STEMS
                computeBinSoftStemMasks(
                    freqHz = binFreqHz[k],
                    centerAffinity = 0.60f,
                    percussiveRatio = pPerc,
                    neuralPriors5 = if (hasNeural) frameNeuralPriors else null,
                    neuralOffset = bandOffset,
                    outMasks5 = binMasks5,
                )
                var compositeGain = 0f
                for (s in 0 until NUM_STEMS) {
                    compositeGain += effectiveStemGains[s] * binMasks5[s]
                }
                val g = 0.35f * smoothedMidGain[k] + 0.65f * compositeGain
                smoothedMidGain[k] = g
                fftRe[0][k] *= g
                fftIm[0][k] *= g
            }
        }
    }

    private fun shiftOlaWithoutEmit() {
        val keepLen = FFT_SIZE - HOP_SIZE
        for (c in 0 until channelCount) {
            val ola = olaFrames[c]
            System.arraycopy(ola, HOP_SIZE, ola, 0, keepLen)
            ola.fill(0f, keepLen, FFT_SIZE)
        }
    }

    private fun emitOlaFrames(frameCount: Int, dst: ShortArray, startPos: Int): Int {
        var pos = startPos
        val chCount = channelCount
        for (f in 0 until frameCount) {
            for (c in 0 until chCount) {
                val sample = (olaFrames[c][f] * 32768.0f).roundToInt().coerceIn(-32768, 32767).toShort()
                dst[pos++] = sample
            }
        }
        val keepLen = FFT_SIZE - HOP_SIZE
        for (c in 0 until chCount) {
            val ola = olaFrames[c]
            System.arraycopy(ola, HOP_SIZE, ola, 0, keepLen)
            ola.fill(0f, keepLen, FFT_SIZE)
        }
        return pos
    }

    private fun ensureOutCapacity(requiredShorts: Int) {
        if (scratchOutShorts.size < requiredShorts) {
            scratchOutShorts = ShortArray(max(requiredShorts, scratchOutShorts.size * 2))
        }
    }
}
