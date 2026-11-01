package com.miniplayer.app.player

import android.media.AudioFormat
import com.miniplayer.app.core.model.BeatGrid
import com.miniplayer.app.core.model.BeatGridSource
import com.miniplayer.app.core.model.PracticeMath
import com.miniplayer.app.player.beat.BeatFeatureFrames
import com.miniplayer.app.player.beat.BeatGridAnalysis
import com.miniplayer.app.player.beat.BeatGridAnalyzer
import com.miniplayer.app.player.beat.BpmOctave
import com.miniplayer.app.player.beat.DpBeatTracker
import com.miniplayer.app.player.beat.TempoFit
import com.miniplayer.app.player.beat.TempoRegression
import com.miniplayer.app.player.beat.EdgeEnvelopeBuilder
import com.miniplayer.app.player.beat.TonalFrames
import com.miniplayer.app.player.beat.TonalStreamBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.charset.StandardCharsets
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Streaming 100 Hz 3-band (`Low < 155 Hz` Kick/Bass, `Mid 155–2400 Hz` Snare/Chords,
 * `High > 2400 Hz` Hi-Hat/Pick transients) onset-flux accumulator and multi-harmonic bar-aware
 * tempo & downbeat (`BeatGrid`) estimator optimized for guitar backing tracks and polyphonic music.
 */
class OnsetFluxAccumulator(
    sampleRate: Int,
    channelCount: Int,
    /** Also build the ÷4 chroma / ÷16 bass streams (BeatGrid v2 Stage 1c). */
    enableTonal: Boolean = true,
) {
    val safeSampleRate: Int = sampleRate.coerceAtLeast(8_000)
    val safeChannelCount: Int = channelCount.coerceAtLeast(1)
    val hopSamples: Int = (safeSampleRate / BpmDetector.TARGET_ENVELOPE_RATE_HZ).coerceAtLeast(1)
    val envelopeRateHz: Double = safeSampleRate.toDouble() / hopSamples.toDouble()

    // 1-pole / cascaded 2-pole IIR coefficients for Low (< 155 Hz) and Mid (< 2400 Hz) separation
    private val alphaLow: Double =
        (2.0 * PI * BpmDetector.LOW_BAND_CUTOFF_HZ) / (safeSampleRate.toDouble() + 2.0 * PI * BpmDetector.LOW_BAND_CUTOFF_HZ)
    private val alphaMid: Double =
        (2.0 * PI * BpmDetector.MID_BAND_CUTOFF_HZ) / (safeSampleRate.toDouble() + 2.0 * PI * BpmDetector.MID_BAND_CUTOFF_HZ)

    private var lpLow1 = 0.0
    private var lpLow2 = 0.0
    private var lpMid = 0.0

    private var energyFrames = FloatArray(4_096)
    private var lowRmsFrames = FloatArray(4_096)
    private var midRmsFrames = FloatArray(4_096)
    private var highRmsFrames = FloatArray(4_096)
    private var energyCount = 0

    private var currentHopFrames = 0
    private var currentHopAbsSum = 0.0
    private var currentHopLowSqSum = 0.0
    private var currentHopMidSqSum = 0.0
    private var currentHopHighSqSum = 0.0

    private var partialChannelIndex = 0
    private var partialFrameSignedSum = 0.0
    private var partialFrameAbsSum = 0.0

    // BeatGrid v2 Stage 1b/1c front-ends fed from the same per-sample loop.
    private val edgeBuilder = EdgeEnvelopeBuilder(safeSampleRate)
    private val tonalBuilder: TonalStreamBuilder? = if (enableTonal) TonalStreamBuilder(safeSampleRate) else null

    val accumulatedHopCount: Int
        get() = energyCount

    val accumulatedSeconds: Double
        get() = energyCount / envelopeRateHz

    fun feedInterleavedPcm(
        pcmInterleaved: ShortArray,
        frameCount: Int = pcmInterleaved.size / safeChannelCount,
        sampleOffset: Int = 0,
    ) {
        if (frameCount <= 0 || sampleOffset >= pcmInterleaved.size) return
        val maxSamples = minOf(pcmInterleaved.size - sampleOffset, frameCount * safeChannelCount)
        if (maxSamples <= 0) return

        // Fast path when aligned at frame boundary
        if (partialChannelIndex == 0 && maxSamples % safeChannelCount == 0) {
            val invScale = 1.0 / (32768.0 * safeChannelCount)
            var idx = sampleOffset
            val end = sampleOffset + maxSamples
            if (safeChannelCount == 1) {
                while (idx < end) {
                    val raw = pcmInterleaved[idx].toInt()
                    val signed = raw * invScale
                    val absVal = abs(raw) * invScale
                    accumulateFilteredFrame(signed, absVal)
                    idx++
                }
            } else if (safeChannelCount == 2) {
                while (idx < end) {
                    val l = pcmInterleaved[idx].toInt()
                    val r = pcmInterleaved[idx + 1].toInt()
                    val signed = (l + r) * invScale
                    val absVal = (abs(l) + abs(r)) * invScale
                    accumulateFilteredFrame(signed, absVal)
                    idx += 2
                }
            } else {
                while (idx < end) {
                    var signedSum = 0L
                    var absSum = 0L
                    for (c in 0 until safeChannelCount) {
                        val v = pcmInterleaved[idx + c].toInt()
                        signedSum += v
                        absSum += abs(v)
                    }
                    accumulateFilteredFrame(signedSum * invScale, absSum * invScale)
                    idx += safeChannelCount
                }
            }
            return
        }

        for (i in sampleOffset until (sampleOffset + maxSamples)) {
            val signed = pcmInterleaved[i].toInt() / 32768.0
            feedSignedSample(signed)
        }
    }

    fun feedShortBuffer(shortBuffer: ShortBuffer) {
        if (!shortBuffer.hasRemaining()) return
        if (shortBuffer.hasArray() && partialChannelIndex == 0) {
            val arr = shortBuffer.array()
            val offset = shortBuffer.arrayOffset() + shortBuffer.position()
            val remaining = shortBuffer.remaining()
            val fullFrames = remaining / safeChannelCount
            if (fullFrames > 0) {
                feedInterleavedPcm(arr, frameCount = fullFrames, sampleOffset = offset)
                shortBuffer.position(shortBuffer.position() + fullFrames * safeChannelCount)
            }
        }
        while (shortBuffer.hasRemaining()) {
            val signed = shortBuffer.get().toInt() / 32768.0
            feedSignedSample(signed)
        }
    }

    fun feedPcmByteBuffer(
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        pcmEncoding: Int,
    ) {
        if (size <= 0) return
        val dup = buffer.duplicate()
        dup.position(offset)
        dup.limit(offset + size)
        val slice = dup.slice().order(ByteOrder.LITTLE_ENDIAN)
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                if (size < 4) return
                val floatBuf = slice.asFloatBuffer()
                while (floatBuf.hasRemaining()) {
                    val v = floatBuf.get().toDouble()
                    val signed = if (v.isFinite()) v.coerceIn(-1.0, 1.0) else 0.0
                    feedSignedSample(signed)
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                while (slice.hasRemaining()) {
                    val unsigned = slice.get().toInt() and 0xFF
                    val signed = ((unsigned - 128) / 128.0).coerceIn(-1.0, 1.0)
                    feedSignedSample(signed)
                }
            }
            else -> {
                if (size < 2) return
                feedShortBuffer(slice.asShortBuffer())
            }
        }
    }

    private fun feedSignedSample(signedSample: Double) {
        partialFrameSignedSum += signedSample
        partialFrameAbsSum += abs(signedSample)
        partialChannelIndex++
        if (partialChannelIndex >= safeChannelCount) {
            val signedMono = partialFrameSignedSum / safeChannelCount
            val absMono = partialFrameAbsSum / safeChannelCount
            partialFrameSignedSum = 0.0
            partialFrameAbsSum = 0.0
            partialChannelIndex = 0
            accumulateFilteredFrame(signedMono, absMono)
        }
    }

    private fun accumulateFilteredFrame(signedMono: Double, absMono: Double) {
        lpLow1 += alphaLow * (signedMono - lpLow1)
        lpLow2 += alphaLow * (lpLow1 - lpLow2)
        lpMid += alphaMid * (signedMono - lpMid)

        val bLow = lpLow2
        val bMid = lpMid - lpLow2
        val bHigh = signedMono - lpMid

        edgeBuilder.push(signedMono, absMono, bLow)
        tonalBuilder?.push(signedMono.toFloat())

        currentHopAbsSum += absMono
        currentHopLowSqSum += bLow * bLow
        currentHopMidSqSum += bMid * bMid
        currentHopHighSqSum += bHigh * bHigh
        currentHopFrames++

        if (currentHopFrames == hopSamples) {
            val invHop = 1.0 / hopSamples.toDouble()
            pushHopFrame(
                energy = (currentHopAbsSum * invHop).toFloat(),
                lowRms = sqrt(currentHopLowSqSum * invHop).toFloat(),
                midRms = sqrt(currentHopMidSqSum * invHop).toFloat(),
                highRms = sqrt(currentHopHighSqSum * invHop).toFloat(),
            )
            currentHopFrames = 0
            currentHopAbsSum = 0.0
            currentHopLowSqSum = 0.0
            currentHopMidSqSum = 0.0
            currentHopHighSqSum = 0.0
        }
    }

    private fun pushHopFrame(energy: Float, lowRms: Float, midRms: Float, highRms: Float) {
        if (energyCount == energyFrames.size) {
            val newSize = energyFrames.size * 2
            energyFrames = energyFrames.copyOf(newSize)
            lowRmsFrames = lowRmsFrames.copyOf(newSize)
            midRmsFrames = midRmsFrames.copyOf(newSize)
            highRmsFrames = highRmsFrames.copyOf(newSize)
        }
        val idx = energyCount++
        energyFrames[idx] = energy
        lowRmsFrames[idx] = lowRms
        midRmsFrames[idx] = midRms
        highRmsFrames[idx] = highRms
    }

    private class BandEnvelopes(
        val n: Int,
        val peakEnergy: Float,
        val peakBroadOnset: Float,
        val broadOnset: FloatArray,
        val lowOnset: FloatArray,
        val midOnset: FloatArray,
        val highOnset: FloatArray,
        val compositeOnset: FloatArray,
        val pulseOnset: FloatArray,
        val hasLowBand: Boolean,
        val hasMidBand: Boolean,
        val hasHighBand: Boolean,
    )

    private fun buildBandEnvelopes(): BandEnvelopes? {
        val n = energyCount
        if (n < 8) return null

        var peakEnergy = 0f
        var peakLow = 0f
        var peakMid = 0f
        var peakHigh = 0f
        for (i in 0 until n) {
            if (energyFrames[i] > peakEnergy) peakEnergy = energyFrames[i]
            if (lowRmsFrames[i] > peakLow) peakLow = lowRmsFrames[i]
            if (midRmsFrames[i] > peakMid) peakMid = midRmsFrames[i]
            if (highRmsFrames[i] > peakHigh) peakHigh = highRmsFrames[i]
        }
        if (peakEnergy < BpmDetector.SILENCE_ENERGY_FLOOR) return null

        // Check broadband onset modulation to reject pure unmodulated constant sine waves
        val rawLinearOnset = FloatArray(n)
        var peakLinearOnset = 0f
        for (k in 1 until n) {
            val diff = max(0f, energyFrames[k] - energyFrames[k - 1])
            rawLinearOnset[k] = diff
            if (diff > peakLinearOnset) peakLinearOnset = diff
        }
        if (peakLinearOnset < peakEnergy * BpmDetector.MIN_ONSET_MODULATION_RATIO) {
            return null
        }

        fun computeLogOnsetFlux(rms: FloatArray, bandPeak: Float): FloatArray {
            val out = FloatArray(n)
            if (bandPeak < BpmDetector.SILENCE_ENERGY_FLOOR * 0.25f) return out
            val p90 = percentilePositive(rms, n, 0.90f).coerceAtLeast(bandPeak * 0.15f)
            val invScale = 24.0f / p90.coerceAtLeast(1e-6f)
            val logEnv = FloatArray(n)
            for (k in 0 until n) {
                logEnv[k] = ln(1.0f + rms[k] * invScale)
            }
            val raw = FloatArray(n)
            for (k in 1 until n) {
                val d1 = max(0f, logEnv[k] - logEnv[k - 1])
                val prevMean = when {
                    k >= 3 -> (logEnv[k - 1] + logEnv[k - 2] + logEnv[k - 3]) / 3f
                    k == 2 -> (logEnv[1] + logEnv[0]) * 0.5f
                    else -> logEnv[0]
                }
                val dLocal = max(0f, logEnv[k] - prevMean)
                raw[k] = 0.72f * d1 + 0.28f * dLocal
            }
            // Zero-phase 3-tap smoothing [0.25, 0.50, 0.25]
            if (n >= 2) {
                out[0] = 0.50f * raw[0] + 0.50f * raw[1]
                for (k in 1 until n - 1) {
                    out[k] = 0.25f * raw[k - 1] + 0.50f * raw[k] + 0.25f * raw[k + 1]
                }
                out[n - 1] = 0.50f * raw[n - 2] + 0.50f * raw[n - 1]
            }
            val normScale = percentilePositive(out, n, 0.95f)
            if (normScale > 1e-5f) {
                val invNorm = 1.0f / normScale
                for (k in 0 until n) {
                    out[k] = (out[k] * invNorm).coerceAtMost(2.5f)
                }
            }
            return out
        }

        val broadOnset = computeLogOnsetFlux(energyFrames, peakEnergy)
        val lowOnset = computeLogOnsetFlux(lowRmsFrames, peakLow)
        val midOnset = computeLogOnsetFlux(midRmsFrames, peakMid)
        val highOnset = computeLogOnsetFlux(highRmsFrames, peakHigh)

        val hasLow = peakLow >= peakEnergy * 0.04f && lowOnset.any { it > 0.05f }
        val hasMid = peakMid >= peakEnergy * 0.04f && midOnset.any { it > 0.05f }
        val hasHigh = peakHigh >= peakEnergy * 0.03f && highOnset.any { it > 0.05f }

        val compositeOnset = FloatArray(n)
        val pulseOnset = FloatArray(n)

        for (k in 0 until n) {
            var compSum = 0f
            var compWeight = 0f
            if (hasLow) {
                compSum += 0.40f * lowOnset[k]
                compWeight += 0.40f
            }
            if (hasMid) {
                compSum += 0.38f * midOnset[k]
                compWeight += 0.38f
            }
            if (hasHigh) {
                compSum += 0.22f * highOnset[k]
                compWeight += 0.22f
            }
            compositeOnset[k] = if (compWeight > 0f) {
                0.75f * (compSum / compWeight) + 0.25f * broadOnset[k]
            } else {
                broadOnset[k]
            }

            // Pulse onset focuses on Low (< 155 Hz Kick/Bass) + Mid (155..2400 Hz Snare body/Chords),
            // rejecting > 2400 Hz 8th/16th hi-hat subdivisions unless only High band exists.
            var pulseSum = 0f
            var pulseWeight = 0f
            if (hasLow) {
                pulseSum += 0.58f * lowOnset[k]
                pulseWeight += 0.58f
            }
            if (hasMid) {
                pulseSum += 0.42f * midOnset[k]
                pulseWeight += 0.42f
            }
            pulseOnset[k] = if (pulseWeight > 0f) {
                pulseSum / pulseWeight
            } else {
                compositeOnset[k]
            }
        }

        var peakComp = 0f
        for (k in 0 until n) {
            if (compositeOnset[k] > peakComp) peakComp = compositeOnset[k]
        }

        return BandEnvelopes(
            n = n,
            peakEnergy = peakEnergy,
            peakBroadOnset = peakComp.coerceAtLeast(1e-5f),
            broadOnset = broadOnset,
            lowOnset = lowOnset,
            midOnset = midOnset,
            highOnset = highOnset,
            compositeOnset = compositeOnset,
            pulseOnset = pulseOnset,
            hasLowBand = hasLow,
            hasMidBand = hasMid,
            hasHighBand = hasHigh,
        )
    }

    /**
     * Identifies the most rhythmically active contiguous hop span `[startHop, endHop)` so quiet
     * or rubato intros/outros in long windows do not dilute the autocorrelation peak.
     */
    private fun findActiveGrooveRange(env: BandEnvelopes): IntRange {
        val n = env.n
        val winHops = (10.0 * envelopeRateHz).toInt().coerceAtLeast(400)
        if (n < winHops * 16 / 10) {
            return 0 until n
        }
        val stepHops = (2.5 * envelopeRateHz).toInt().coerceAtLeast(100)
        var maxWinScore = 0.0
        val winStarts = ArrayList<Int>()
        val winScores = ArrayList<Double>()

        var start = 0
        while (start + winHops <= n) {
            var sum = 0.0
            for (i in start until (start + winHops)) {
                sum += env.pulseOnset[i] * 0.6 + env.compositeOnset[i] * 0.4
            }
            val avg = sum / winHops
            winStarts.add(start)
            winScores.add(avg)
            if (avg > maxWinScore) maxWinScore = avg
            start += stepHops
        }
        if (maxWinScore <= 1e-6 || winStarts.isEmpty()) return 0 until n

        val activeThreshold = maxWinScore * 0.30
        val firstGoodIdx = winScores.indexOfFirst { it >= activeThreshold }.takeIf { it >= 0 } ?: 0
        val lastGoodIdx = winScores.indexOfLast { it >= activeThreshold }.takeIf { it >= firstGoodIdx } ?: winScores.lastIndex

        val grooveStart = winStarts[firstGoodIdx]
        val grooveEnd = minOf(n, winStarts[lastGoodIdx] + winHops)
        return if (grooveEnd - grooveStart >= (envelopeRateHz * 6.0).toInt()) {
            grooveStart until grooveEnd
        } else {
            0 until n
        }
    }

    internal data class BpmEstimateResult(
        val bpm: Int,
        val exactBpm: Double,
        val clarity: Double,
    )

    fun computeBpm(): Int? = computeBpmInternal()?.bpm

    internal fun computeBpmWithClarity(): BpmEstimateResult? = computeBpmInternal()

    private fun computeBpmInternal( precomputedEnv: BandEnvelopes? = null): BpmEstimateResult? {
        val n = energyCount
        val minRequiredHops = (envelopeRateHz * BpmDetector.MIN_DURATION_SECONDS).toInt().coerceAtLeast(180)
        if (n < minRequiredHops) return null

        val env = precomputedEnv ?: buildBandEnvelopes() ?: return null
        val grooveRange = findActiveGrooveRange(env)
        val gStart = grooveRange.first
        val gLen = grooveRange.last - grooveRange.first + 1
        if (gLen < minRequiredHops) return null

        val minLag = floor(60.0 * envelopeRateHz / BpmDetector.MAX_SEARCH_BPM).toInt().coerceAtLeast(2)
        val maxLag = ceil(60.0 * envelopeRateHz / BpmDetector.MIN_SEARCH_BPM).toInt().coerceAtMost(gLen / 2 - 1)
        if (maxLag <= minLag) return null

        val minComputeLag = (minLag / 2).coerceAtLeast(1)
        val maxCorrLag = minOf(maxLag * 4 + 8, gLen - 16)
        if (maxCorrLag <= minLag) return null

        val corrComp = computeNormalizedAutocorrelation(env.compositeOnset, gStart, gLen, minComputeLag, maxCorrLag)
            ?: return null
        val corrPulse = computeNormalizedAutocorrelation(env.pulseOnset, gStart, gLen, minComputeLag, maxCorrLag)
            ?: corrComp

        var maxPrimaryCorr = 0f
        for (lag in minLag..maxLag) {
            val c = max(corrComp[lag], corrPulse[lag])
            if (c > maxPrimaryCorr) maxPrimaryCorr = c
        }
        if (maxPrimaryCorr < BpmDetector.MIN_AUTOCORR_CLARITY) return null

        // Continuous fractional-lag multi-harmonic bar-aware tempo scan (45..220 BPM in 0.25 BPM steps)
        var bestRoughBpm = 120.0
        var bestRoughScore = Double.NEGATIVE_INFINITY

        val totalSteps = ((BpmDetector.MAX_SEARCH_BPM - BpmDetector.MIN_SEARCH_BPM) * 4.0).roundToInt()
        for (step in 0..totalSteps) {
            val bpmCandidate = BpmDetector.MIN_SEARCH_BPM + step * 0.25
            val tau = (60.0 * envelopeRateHz) / bpmCandidate
            if (tau < minLag || tau > maxLag) continue

            val octaveOffset = ln(bpmCandidate / BpmDetector.TEMPO_PRIOR_CENTER_BPM) / BpmDetector.LN_2
            val tempoWeight = exp(-0.5 * (octaveOffset / BpmDetector.TEMPO_PRIOR_SIGMA_OCTAVES).pow(2))

            val r1Comp = interpCorrPeak(corrComp, tau, minComputeLag, maxCorrLag, radius = 0.65)
            val r1Pulse = interpCorrPeak(corrPulse, tau, minComputeLag, maxCorrLag, radius = 0.65)
            val r1 = 0.54 * r1Comp + 0.46 * r1Pulse

            val r2 = max(
                interpCorrPeak(corrComp, 2.0 * tau, minComputeLag, maxCorrLag, radius = 1.0),
                interpCorrPeak(corrPulse, 2.0 * tau, minComputeLag, maxCorrLag, radius = 1.0),
            ).coerceAtLeast(0.0)

            val r3 = interpCorrPeak(corrComp, 3.0 * tau, minComputeLag, maxCorrLag, radius = 1.2).coerceAtLeast(0.0)

            val r4 = max(
                interpCorrPeak(corrComp, 4.0 * tau, minComputeLag, maxCorrLag, radius = 1.5),
                interpCorrPeak(corrPulse, 4.0 * tau, minComputeLag, maxCorrLag, radius = 1.5),
            ).coerceAtLeast(0.0)

            val r8 = if (8.0 * tau <= maxCorrLag) {
                interpCorrPeak(corrComp, 8.0 * tau, minComputeLag, maxCorrLag, radius = 2.0).coerceAtLeast(0.0)
            } else {
                0.0
            }

            val harmonicSum = r1 + 0.48 * r2 + 0.14 * r3 + 0.40 * r4 + 0.16 * r8
            val weightedScore = harmonicSum * tempoWeight
            if (weightedScore > bestRoughScore) {
                bestRoughScore = weightedScore
                bestRoughBpm = bpmCandidate
            }
        }

        // Resolve octave / subdivision ambiguities (0.5x vs 1.0x vs 2.0x) using Low+Mid pulse vs High subdivisions
        var resolvedBpm = bestRoughBpm
        val tauResolved = (60.0 * envelopeRateHz) / resolvedBpm
        val r1CompCur = interpCorrPeak(corrComp, tauResolved, minComputeLag, maxCorrLag, radius = 0.8)
        val r1PulseCur = interpCorrPeak(corrPulse, tauResolved, minComputeLag, maxCorrLag, radius = 0.8)

        if (resolvedBpm < BpmDetector.PRIMARY_RANGE_MIN_BPM) {
            val halfTau = tauResolved * 0.5
            val rHalfComp = interpCorrPeak(corrComp, halfTau, minComputeLag, maxCorrLag, radius = 0.8)
            if (rHalfComp > 0.30 * r1CompCur) {
                resolvedBpm *= 2.0
            }
        } else if (resolvedBpm > BpmDetector.PRIMARY_RANGE_MAX_BPM) {
            val dblTau = tauResolved * 2.0
            val rDblComp = interpCorrPeak(corrComp, dblTau, minComputeLag, maxCorrLag, radius = 1.0)
            val rDblPulse = interpCorrPeak(corrPulse, dblTau, minComputeLag, maxCorrLag, radius = 1.0)
            if (max(rDblComp, rDblPulse) > 0.42 * r1CompCur) {
                resolvedBpm *= 0.5
            }
        } else if (resolvedBpm > 132.0 && resolvedBpm * 0.5 >= BpmDetector.PRIMARY_RANGE_MIN_BPM) {
            // Guard against 66..85 BPM 8th-note hi-hat grooves being doubled into 132..170 BPM:
            // If the < 2400 Hz Low+Mid pulse correlation at 2*tau (half-tempo) is much stronger than at tau,
            // then tau is an 8th-note hi-hat subdivision and 2*tau is the true quarter-note pulse.
            val dblTau = tauResolved * 2.0
            val rDblPulse = interpCorrPeak(corrPulse, dblTau, minComputeLag, maxCorrLag, radius = 1.0)
            val rDblComp = interpCorrPeak(corrComp, dblTau, minComputeLag, maxCorrLag, radius = 1.0)
            if (rDblPulse > 1.38 * max(0.04, r1PulseCur) && rDblComp >= 0.72 * r1CompCur) {
                resolvedBpm *= 0.5
            }
        } else if (resolvedBpm * 2.0 <= BpmDetector.PRIMARY_RANGE_MAX_BPM) {
            // Only promote 65..85 BPM -> 130..170 BPM if BOTH composite AND < 2400 Hz Low+Mid pulse
            // have near-equal correlation at the half-period (true fast quarter-note Kick/Snare alternation).
            val halfTau = tauResolved * 0.5
            val rHalfComp = interpCorrPeak(corrComp, halfTau, minComputeLag, maxCorrLag, radius = 0.8)
            val rHalfPulse = interpCorrPeak(corrPulse, halfTau, minComputeLag, maxCorrLag, radius = 0.8)
            if (rHalfComp >= 0.80 * r1CompCur && rHalfPulse >= 0.76 * r1PulseCur) {
                resolvedBpm *= 2.0
            }
        }

        // Multi-bar comb fine refinement around resolvedBpm (±2.2 BPM in 0.05 BPM steps)
        val fineLo = (resolvedBpm - 2.2).coerceAtLeast(BpmDetector.MIN_SEARCH_BPM)
        val fineHi = (resolvedBpm + 2.2).coerceAtMost(BpmDetector.MAX_SEARCH_BPM)
        val fineSteps = ((fineHi - fineLo) / 0.05).roundToInt().coerceAtLeast(1)
        val multipliers = intArrayOf(1, 2, 3, 4, 6, 8, 12, 16)
        val multWeights = doubleArrayOf(1.00, 0.85, 0.35, 0.90, 0.40, 0.75, 0.45, 0.55)

        fun evaluateFineMultiBarScore(bpmVal: Double): Double {
            val tau = (60.0 * envelopeRateHz) / bpmVal
            var total = 0.0
            var weightSum = 0.0
            for (idx in multipliers.indices) {
                val m = multipliers[idx]
                val mTau = m * tau
                if (mTau > maxCorrLag - 1) break
                val cComp = interpCorrExact(corrComp, mTau, minComputeLag, maxCorrLag)
                val cPulse = interpCorrExact(corrPulse, mTau, minComputeLag, maxCorrLag)
                val w = multWeights[idx]
                total += w * (0.55 * cComp + 0.45 * cPulse)
                weightSum += w
            }
            return if (weightSum > 0.0) total / weightSum else Double.NEGATIVE_INFINITY
        }

        var refinedBpm = resolvedBpm
        var bestFineScore = Double.NEGATIVE_INFINITY
        for (s in 0..fineSteps) {
            val cand = fineLo + s * 0.05
            val sc = evaluateFineMultiBarScore(cand)
            if (sc > bestFineScore) {
                bestFineScore = sc
                refinedBpm = cand
            }
        }

        val clampedIntBpm = PracticeMath.clampBpm(refinedBpm.roundToInt())
        return BpmEstimateResult(
            bpm = clampedIntBpm,
            exactBpm = refinedBpm.coerceIn(30.0, 300.0),
            clarity = maxPrimaryCorr.toDouble(),
        )
    }

    /**
     * Evaluates how strongly a candidate [candidateBpm] (e.g. from an embedded `TBPM` tag)
     * correlates with the actual audio onsets, returning `0.0..1.0+`.
     */
    internal fun scoreCandidateBpmAgreement(candidateBpm: Int): Double {
        if (candidateBpm !in 30..300) return 0.0
        val env = buildBandEnvelopes() ?: return 0.0
        val grooveRange = findActiveGrooveRange(env)
        val gStart = grooveRange.first
        val gLen = grooveRange.last - grooveRange.first + 1
        if (gLen < 120) return 0.0
        val tau = (60.0 * envelopeRateHz) / candidateBpm.toDouble()
        val maxCorrLag = minOf((tau * 4.5).toInt() + 4, gLen - 8)
        if (maxCorrLag <= tau.toInt()) return 0.0
        val corrComp = computeNormalizedAutocorrelation(env.compositeOnset, gStart, gLen, 1, maxCorrLag) ?: return 0.0
        val r1 = interpCorrPeak(corrComp, tau, 1, maxCorrLag, radius = 0.8).coerceAtLeast(0.0)
        val r2 = interpCorrPeak(corrComp, 2.0 * tau, 1, maxCorrLag, radius = 1.0).coerceAtLeast(0.0)
        val r4 = interpCorrPeak(corrComp, 4.0 * tau, 1, maxCorrLag, radius = 1.5).coerceAtLeast(0.0)
        return 0.50 * r1 + 0.30 * r2 + 0.20 * r4
    }

    /** Start (s) of the most rhythmically active span (window policy, §3.1). */
    internal fun activeGrooveStartSeconds(): Double? {
        val env = buildBandEnvelopes() ?: return null
        return findActiveGrooveRange(env).first / envelopeRateHz
    }

    /**
     * Onset-only local Stage 2–3 fit (long-baseline mid window, §3.3). Times are relative to the
     * first fed sample. The local tempo prior is snapped to the octave of [bpmHint].
     */
    internal fun localTempoFit(bpmHint: Double): TempoFit? {
        val frames = buildFeatureFrames() ?: return null
        val local = computeBpmInternal()?.exactBpm
        val bpm = if (local != null && abs(ln(BpmOctave.resolve(bpmHint, local) / bpmHint)) < 0.03) {
            BpmOctave.resolve(bpmHint, local)
        } else {
            bpmHint
        }
        val dp = DpBeatTracker.track(frames, bpm) ?: return null
        return TempoRegression.fit(dp.index, dp.timesMs, dp.strength)
    }

    /**
     * Exposes all Stage 1 front-end features (100 Hz bands + onsets, 400 Hz edge signal, tonal
     * frames) for BeatGrid v2. [tonalOverride] / [accentOverride] are used by the `.stems` path.
     */
    internal fun buildFeatureFrames(
        tonalOverride: TonalFrames? = null,
        accentOverride: FloatArray? = null,
    ): BeatFeatureFrames? {
        val env = buildBandEnvelopes() ?: return null
        val n = env.n
        val (edge, edgeLow) = edgeBuilder.build()
        val tonal = tonalOverride ?: tonalBuilder?.build() ?: TonalFrames.EMPTY
        val accent = accentOverride?.let { a -> FloatArray(n) { i -> if (i < a.size) a[i] else 0f } }
        return BeatFeatureFrames(
            envelopeRateHz = envelopeRateHz,
            energy = energyFrames.copyOf(n),
            lowRms = lowRmsFrames.copyOf(n),
            midRms = midRmsFrames.copyOf(n),
            highRms = highRmsFrames.copyOf(n),
            broadOnset = env.broadOnset,
            lowOnset = env.lowOnset,
            midOnset = env.midOnset,
            highOnset = env.highOnset,
            compositeOnset = env.compositeOnset,
            pulseOnset = env.pulseOnset,
            hasLowBand = env.hasLowBand,
            hasMidBand = env.hasMidBand,
            hasHighBand = env.hasHighBand,
            peakEnergy = env.peakEnergy,
            edgeRateHz = edgeBuilder.rateHz,
            edge = edge,
            edgeLow = edgeLow,
            tonal = tonal,
            accentEnergy = accent ?: energyFrames.copyOf(n),
        )
    }

    /**
     * BeatGrid v2 (§3): Stage 2 tempo prior (`exactBpm`, or [overrideBpm]) with optional embedded
     * tag octave disambiguation ([embeddedBpm]) → Ellis DP + Theil–Sen/IRLS regression → beat-sync
     * features → PRE|GROOVE phrase Viterbi → attack back-tracking. [beatsPerBar] `null` = auto
     * (4/4 vs 3/4). Returns an **unfolded** downbeat anchor.
     */
    fun computeBeatGrid(
        overrideBpm: Double? = null,
        beatsPerBar: Int? = null,
        embeddedBpm: Double? = null,
        source: BeatGridSource = BeatGridSource.AUTO_MIX,
    ): BeatGrid? = computeBeatGridDetailed(overrideBpm, beatsPerBar, embeddedBpm, source)?.grid

    internal fun computeBeatGridDetailed(
        overrideBpm: Double? = null,
        beatsPerBar: Int? = null,
        embeddedBpm: Double? = null,
        source: BeatGridSource = BeatGridSource.AUTO_MIX,
    ): BeatGridAnalysis? {
        val estimate = computeBpmInternal()
        val acoustic = overrideBpm?.takeIf { it in 30.0..300.0 } ?: estimate?.exactBpm ?: return null
        val bpm = if (overrideBpm == null) BpmOctave.resolve(embeddedBpm, acoustic) else acoustic
        val clarity = estimate?.clarity ?: 0.0
        val frames = buildFeatureFrames()
        val analysis = frames?.let { BeatGridAnalyzer.run(it, bpm, clarity, source, beatsPerBar?.coerceIn(3, 4)) }
        if (analysis != null) return analysis
        // Too short / too sparse for the full pipeline: honest tempo-only grid.
        return BeatGridAnalysis(
            BeatGrid(
                bpmPrecise = bpm.coerceIn(30.0, 300.0),
                firstBeatMs = 0L,
                firstDownbeatMs = 0L,
                beatsPerBar = beatsPerBar?.coerceIn(2, 8) ?: 4,
                source = source,
            ),
            com.miniplayer.app.player.beat.BeatGridDiagnostics(
                dpBeats = 0, tempoFit = null, pattern = null, harmRhythm = 0, drumPresence = 0.0,
                chromaFallbackBeats = 0, emptyChromaBeats = 0, anchorBeatIndex = -1, attackSamples = 0,
                halfBarMargin = 0.0, sectionNoveltyAtAnchor = 0.0,
            ),
        )
    }

    private fun computeNormalizedAutocorrelation(
        series: FloatArray,
        start: Int,
        length: Int,
        minComputeLag: Int,
        maxCorrLag: Int,
    ): FloatArray? {
        if (length <= maxCorrLag + 2) return null
        var meanAcc = 0.0
        for (i in 0 until length) {
            meanAcc += series[start + i]
        }
        val mean = (meanAcc / length).toFloat()

        val centered = FloatArray(length)
        var r0Acc = 0.0
        for (i in 0 until length) {
            val v = series[start + i] - mean
            centered[i] = v
            r0Acc += v * v
        }
        val r0 = r0Acc / length
        if (r0 <= 1e-12) return null

        val corr = FloatArray(maxCorrLag + 1)
        corr[0] = 1.0f
        for (lag in minComputeLag..maxCorrLag) {
            val count = length - lag
            var sum = 0.0
            var k = 0
            while (k < count) {
                sum += centered[k] * centered[k + lag]
                k++
            }
            corr[lag] = ((sum / count) / r0).toFloat()
        }
        return corr
    }

    private fun interpCorrExact(
        corr: FloatArray,
        lag: Double,
        minLag: Int,
        maxLag: Int,
    ): Double {
        if (lag < minLag || lag > maxLag) return 0.0
        val i0 = floor(lag).toInt()
        val frac = lag - i0
        if (frac < 1e-6 || i0 >= maxLag) return corr[i0.coerceIn(minLag, maxLag)].toDouble()
        val y0 = corr[(i0 - 1).coerceIn(minLag, maxLag)].toDouble()
        val y1 = corr[i0.coerceIn(minLag, maxLag)].toDouble()
        val y2 = corr[(i0 + 1).coerceIn(minLag, maxLag)].toDouble()
        val y3 = corr[(i0 + 2).coerceIn(minLag, maxLag)].toDouble()
        // Catmull-Rom cubic interpolation for smooth sub-hop autocorrelation evaluation
        val a0 = -0.5 * y0 + 1.5 * y1 - 1.5 * y2 + 0.5 * y3
        val a1 = y0 - 2.5 * y1 + 2.0 * y2 - 0.5 * y3
        val a2 = -0.5 * y0 + 0.5 * y2
        val a3 = y1
        return ((a0 * frac + a1) * frac + a2) * frac + a3
    }

    private fun interpCorrPeak(
        corr: FloatArray,
        centerLag: Double,
        minLag: Int,
        maxLag: Int,
        radius: Double,
    ): Double {
        if (centerLag < minLag || centerLag > maxLag) return 0.0
        val centerVal = interpCorrExact(corr, centerLag, minLag, maxLag)
        val loVal = interpCorrExact(corr, (centerLag - radius * 0.5).coerceAtLeast(minLag.toDouble()), minLag, maxLag)
        val hiVal = interpCorrExact(corr, (centerLag + radius * 0.5).coerceAtMost(maxLag.toDouble()), minLag, maxLag)
        return maxOf(centerVal, 0.88 * max(loVal, hiVal))
    }

    private fun percentilePositive(values: FloatArray, count: Int, quantile: Float): Float {
        val positive = FloatArray(count)
        var posCount = 0
        for (i in 0 until count) {
            val v = values[i]
            if (v > 1e-6f) {
                positive[posCount++] = v
            }
        }
        if (posCount == 0) return 0f
        positive.sort(0, posCount)
        val idx = ((posCount - 1) * quantile.coerceIn(0f, 1f)).roundToInt().coerceIn(0, posCount - 1)
        return positive[idx]
    }
}

object BpmDetector {
    const val TARGET_ENVELOPE_RATE_HZ = 100
    const val LOW_BAND_CUTOFF_HZ = 155.0
    const val MID_BAND_CUTOFF_HZ = 2400.0
    const val MIN_DURATION_SECONDS = 2.5
    const val MIN_SEARCH_BPM = 45.0
    const val MAX_SEARCH_BPM = 220.0
    const val PRIMARY_RANGE_MIN_BPM = 65.0
    const val PRIMARY_RANGE_MAX_BPM = 170.0
    const val TEMPO_PRIOR_CENTER_BPM = 118.0
    const val TEMPO_PRIOR_SIGMA_OCTAVES = 0.72
    const val OCTAVE_HARMONIC_WEIGHT = 0.48
    const val SUBHARMONIC_PROMOTE_RATIO = 0.78f
    const val SILENCE_ENERGY_FLOOR = 1e-4f
    const val MIN_ONSET_MODULATION_RATIO = 0.02f
    const val MIN_AUTOCORR_CLARITY = 0.06f
    internal val LN_2 = ln(2.0)

    /**
     * Pure-JVM helper for detecting BPM directly from interleaved 16-bit PCM frames.
     */
    fun detectBpmFromPcm(
        pcmInterleaved: ShortArray,
        frameCount: Int = pcmInterleaved.size / 1,
        sampleRate: Int,
        channelCount: Int,
    ): Int? {
        if (sampleRate <= 0 || channelCount <= 0 || frameCount <= 0 || pcmInterleaved.isEmpty()) {
            return null
        }
        val safeFrames = minOf(frameCount, pcmInterleaved.size / channelCount)
        if (safeFrames <= 0) return null
        val accumulator = OnsetFluxAccumulator(sampleRate = sampleRate, channelCount = channelCount)
        accumulator.feedInterleavedPcm(pcmInterleaved, frameCount = safeFrames, sampleOffset = 0)
        return accumulator.computeBpm()
    }

    /**
     * Pure-JVM helper for detecting a full BeatGrid v2 (`bpmPrecise`, `firstBeatMs`, unfolded
     * `firstDownbeatMs`, `beatsPerBar`, `phraseOffsetBars`, confidence) directly from interleaved
     * 16-bit PCM frames. [beatsPerBar] `null` = automatic 4/4 vs 3/4.
     */
    fun detectBeatGridFromPcm(
        pcmInterleaved: ShortArray,
        frameCount: Int = pcmInterleaved.size / 1,
        sampleRate: Int,
        channelCount: Int,
        beatsPerBar: Int? = null,
        embeddedBpm: Double? = null,
    ): BeatGrid? = detectBeatGridDetailedFromPcm(pcmInterleaved, frameCount, sampleRate, channelCount, beatsPerBar, embeddedBpm)?.grid

    internal fun detectBeatGridDetailedFromPcm(
        pcmInterleaved: ShortArray,
        frameCount: Int = pcmInterleaved.size / 1,
        sampleRate: Int,
        channelCount: Int,
        beatsPerBar: Int? = null,
        embeddedBpm: Double? = null,
    ): BeatGridAnalysis? {
        if (sampleRate <= 0 || channelCount <= 0 || frameCount <= 0 || pcmInterleaved.isEmpty()) {
            return null
        }
        val safeFrames = minOf(frameCount, pcmInterleaved.size / channelCount)
        if (safeFrames <= 0) return null
        val accumulator = OnsetFluxAccumulator(sampleRate = sampleRate, channelCount = channelCount)
        accumulator.feedInterleavedPcm(pcmInterleaved, frameCount = safeFrames, sampleOffset = 0)
        return accumulator.computeBeatGridDetailed(beatsPerBar = beatsPerBar, embeddedBpm = embeddedBpm)
    }

    /**
     * Reconciles an optional embedded metadata `TBPM` / `BPM` tag with the acoustic tempo. The tag
     * (parsed as Double) is used **only** to pick the octave (`×0.5 / ×1 / ×2`) of the acoustic
     * estimate; it never overrides the regressed tempo itself.
     */
    internal fun resolveEmbeddedAndAcousticBpm(
        embeddedBpm: Double?,
        acousticBpm: Double?,
    ): Double? {
        val validEmbedded = embeddedBpm?.takeIf { it in 30.0..300.0 }
        if (acousticBpm == null || !(acousticBpm in 30.0..300.0)) return validEmbedded
        return BpmOctave.resolve(validEmbedded, acousticBpm)
    }

    /**
     * Parses a raw embedded `TBPM` / `BPM` metadata value (e.g. `"120"`, `"120.4"`, `"96 BPM"`),
     * returning a fractional BPM in `30..300` or `null` if missing/invalid.
     */
    fun parseBpmTagDouble(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val match = BPM_NUMBER_REGEX.find(raw.trim()) ?: return null
        val numeric = match.value.toDoubleOrNull() ?: return null
        if (!numeric.isFinite()) return null
        return numeric.takeIf { it in 30.0..300.0 }
    }

    /** Integer convenience wrapper over [parseBpmTagDouble] (display / legacy callers). */
    fun parseBpmTagString(raw: String?): Int? = parseBpmTagDouble(raw)?.roundToInt()?.takeIf { it in 30..300 }

    /** Integer convenience wrapper over [extractEmbeddedBpmDoubleFromHeader]. */
    fun extractEmbeddedBpmFromHeader(headerBytes: ByteArray, length: Int = headerBytes.size): Int? =
        extractEmbeddedBpmDoubleFromHeader(headerBytes, length)?.roundToInt()?.takeIf { it in 30..300 }

    /**
     * Fast pure-JVM check for embedded ID3v2 (`TBPM` / `TBP`) or FLAC (`BPM=` / `TEMPO=`) tags
     * inside the leading header bytes of an audio file.
     */
    fun extractEmbeddedBpmDoubleFromHeader(headerBytes: ByteArray, length: Int = headerBytes.size): Double? {
        val limit = minOf(length, headerBytes.size)
        if (limit < 10) return null

        // 1. ID3v2 (`ID3`)
        if (headerBytes[0] == 'I'.code.toByte() &&
            headerBytes[1] == 'D'.code.toByte() &&
            headerBytes[2] == '3'.code.toByte()
        ) {
            val major = headerBytes[3].toInt() and 0xFF
            val flags = headerBytes[5].toInt() and 0xFF
            val tagSize = readSynchsafeInt(headerBytes, 6)
            val tagEnd = minOf(limit, 10 + tagSize)
            var pos = 10
            if (major in 3..4 && (flags and 0x40) != 0 && pos + 4 <= tagEnd) {
                val extSize = if (major == 4) readSynchsafeInt(headerBytes, pos) else readBigEndianInt(headerBytes, pos)
                pos += if (major == 4) extSize.coerceAtLeast(4) else (4 + extSize.coerceAtLeast(0))
            }
            if (major == 2) {
                while (pos + 6 <= tagEnd) {
                    if (headerBytes[pos] == 0.toByte()) break
                    val id = String(headerBytes, pos, 3, StandardCharsets.ISO_8859_1)
                    val frameSize = ((headerBytes[pos + 3].toInt() and 0xFF) shl 16) or
                        ((headerBytes[pos + 4].toInt() and 0xFF) shl 8) or
                        (headerBytes[pos + 5].toInt() and 0xFF)
                    if (frameSize <= 0 || pos + 6 + frameSize > tagEnd) break
                    if (id == "TBP") {
                        decodeId3TextPayload(headerBytes, pos + 6, frameSize)?.let(::parseBpmTagDouble)?.let { return it }
                    }
                    pos += 6 + frameSize
                }
            } else if (major == 3 || major == 4) {
                while (pos + 10 <= tagEnd) {
                    if (headerBytes[pos] == 0.toByte()) break
                    val id = String(headerBytes, pos, 4, StandardCharsets.ISO_8859_1)
                    val frameSize = if (major == 4) {
                        readSynchsafeInt(headerBytes, pos + 4)
                    } else {
                        readBigEndianInt(headerBytes, pos + 4)
                    }
                    if (frameSize <= 0 || pos + 10 + frameSize > tagEnd) break
                    if (id == "TBPM") {
                        decodeId3TextPayload(headerBytes, pos + 10, frameSize)?.let(::parseBpmTagDouble)?.let { return it }
                    }
                    pos += 10 + frameSize
                }
            }
        }

        // 2. FLAC (`fLaC`) Vorbis comment block (type 4)
        if (headerBytes[0] == 'f'.code.toByte() &&
            headerBytes[1] == 'L'.code.toByte() &&
            headerBytes[2] == 'a'.code.toByte() &&
            headerBytes[3] == 'C'.code.toByte()
        ) {
            var pos = 4
            while (pos + 4 <= limit) {
                val headerByte = headerBytes[pos].toInt() and 0xFF
                val isLast = (headerByte and 0x80) != 0
                val blockType = headerByte and 0x7F
                val blockLen = ((headerBytes[pos + 1].toInt() and 0xFF) shl 16) or
                    ((headerBytes[pos + 2].toInt() and 0xFF) shl 8) or
                    (headerBytes[pos + 3].toInt() and 0xFF)
                pos += 4
                if (blockLen < 0 || pos + blockLen > limit) break
                if (blockType == 4 && blockLen >= 8) {
                    extractVorbisCommentBpm(headerBytes, pos, blockLen)?.let { return it }
                }
                pos += blockLen
                if (isLast) break
            }
        }

        return null
    }

    private fun extractVorbisCommentBpm(bytes: ByteArray, offset: Int, length: Int): Double? {
        val end = offset + length
        if (offset + 4 > end) return null
        val vendorLen = readLittleEndianInt(bytes, offset)
        var pos = offset + 4 + vendorLen
        if (vendorLen < 0 || pos + 4 > end) return null
        val commentCount = readLittleEndianInt(bytes, pos)
        pos += 4
        if (commentCount <= 0 || commentCount > 4096) return null
        for (i in 0 until commentCount) {
            if (pos + 4 > end) break
            val len = readLittleEndianInt(bytes, pos)
            pos += 4
            if (len < 0 || pos + len > end) break
            val entry = String(bytes, pos, len, StandardCharsets.UTF_8)
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).trim().uppercase()
                if (key == "BPM" || key == "TEMPO" || key == "TBPM") {
                    parseBpmTagDouble(entry.substring(eq + 1))?.let { return it }
                }
            }
            pos += len
        }
        return null
    }

    /** LAME/Xing gapless info (§3.1 RC8): encoder delay and padding in samples. */
    data class Mp3GaplessInfo(val encoderDelay: Int, val padding: Int, val sampleRate: Int) {
        val encoderDelayMs: Double get() = if (sampleRate > 0) encoderDelay * 1000.0 / sampleRate else 0.0
    }

    /**
     * Parses the Xing/Info + LAME extension of the first MPEG audio frame in [headerBytes] (after
     * any ID3v2 tag): 24-bit field at LAME + 21 = 12-bit `encoderDelay` + 12-bit `padding`.
     */
    fun parseMp3GaplessInfo(headerBytes: ByteArray, length: Int = headerBytes.size): Mp3GaplessInfo? {
        val limit = minOf(length, headerBytes.size)
        var pos = 0
        if (limit >= 10 && headerBytes[0] == 'I'.code.toByte() && headerBytes[1] == 'D'.code.toByte() &&
            headerBytes[2] == '3'.code.toByte()
        ) {
            val footer = if ((headerBytes[5].toInt() and 0x10) != 0) 10 else 0
            pos = 10 + readSynchsafeInt(headerBytes, 6) + footer
        }
        // Find frame sync.
        val scanEnd = minOf(limit - 4, pos + 8192)
        while (pos < scanEnd) {
            val b0 = headerBytes[pos].toInt() and 0xFF
            val b1 = headerBytes[pos + 1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0 && ((b1 shr 1) and 0x03) == 0x01) break
            pos++
        }
        if (pos >= scanEnd) return null
        val b1 = headerBytes[pos + 1].toInt() and 0xFF
        val b2 = headerBytes[pos + 2].toInt() and 0xFF
        val b3 = headerBytes[pos + 3].toInt() and 0xFF
        val versionBits = (b1 shr 3) and 0x03 // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
        val srIdx = (b2 shr 2) and 0x03
        if (srIdx == 3 || versionBits == 1) return null
        val baseRates = intArrayOf(44_100, 48_000, 32_000)
        val sampleRate = when (versionBits) {
            3 -> baseRates[srIdx]
            2 -> baseRates[srIdx] / 2
            else -> baseRates[srIdx] / 4
        }
        val mono = ((b3 shr 6) and 0x03) == 3
        val sideInfo = if (versionBits == 3) (if (mono) 17 else 32) else (if (mono) 9 else 17)
        val xing = pos + 4 + sideInfo
        if (xing + 8 > limit) return null
        val tag = String(headerBytes, xing, 4, StandardCharsets.ISO_8859_1)
        if (tag != "Xing" && tag != "Info") return null
        val flags = readBigEndianInt(headerBytes, xing + 4)
        var lame = xing + 8
        if ((flags and 0x1) != 0) lame += 4
        if ((flags and 0x2) != 0) lame += 4
        if ((flags and 0x4) != 0) lame += 100
        if ((flags and 0x8) != 0) lame += 4
        if (lame + 24 > limit) return null
        val enc = String(headerBytes, lame, 4, StandardCharsets.ISO_8859_1)
        if (enc != "LAME" && enc != "Lavf" && enc != "Lavc" && enc != "GOGO") return null
        val d0 = headerBytes[lame + 21].toInt() and 0xFF
        val d1 = headerBytes[lame + 22].toInt() and 0xFF
        val d2 = headerBytes[lame + 23].toInt() and 0xFF
        val delay = (d0 shl 4) or (d1 shr 4)
        val padding = ((d1 and 0x0F) shl 8) or d2
        return Mp3GaplessInfo(delay, padding, sampleRate)
    }

    private fun decodeId3TextPayload(bytes: ByteArray, offset: Int, size: Int): String? {
        if (size <= 1 || offset + size > bytes.size) return null
        val encoding = bytes[offset].toInt() and 0xFF
        val textOffset = offset + 1
        val textLen = size - 1
        val decoded = when (encoding) {
            0 -> String(bytes, textOffset, textLen, StandardCharsets.ISO_8859_1)
            1 -> String(bytes, textOffset, textLen, StandardCharsets.UTF_16)
            2 -> String(bytes, textOffset, textLen, StandardCharsets.UTF_16BE)
            3 -> String(bytes, textOffset, textLen, StandardCharsets.UTF_8)
            else -> String(bytes, textOffset, textLen, StandardCharsets.UTF_8)
        }
        return decoded.trim { it <= ' ' || it == '\u0000' }
    }

    private fun readSynchsafeInt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0x7F) shl 21) or
            ((b[offset + 1].toInt() and 0x7F) shl 14) or
            ((b[offset + 2].toInt() and 0x7F) shl 7) or
            (b[offset + 3].toInt() and 0x7F)

    private fun readBigEndianInt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 24) or
            ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or
            (b[offset + 3].toInt() and 0xFF)

    private fun readLittleEndianInt(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)

    private val BPM_NUMBER_REGEX = Regex("""\d+(?:\.\d+)?""")
}
