package com.miniplayer.app.player

import android.media.AudioFormat
import com.miniplayer.app.core.model.PracticeMath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Streaming 100 Hz onset-energy accumulator and autocorrelation tempo estimator optimized for
 * guitar backing tracks and drum/music recordings (`60–180 BPM` primary range, clamped `30–300 BPM`).
 */
class OnsetFluxAccumulator(
    sampleRate: Int,
    channelCount: Int,
) {
    val safeSampleRate: Int = sampleRate.coerceAtLeast(8_000)
    val safeChannelCount: Int = channelCount.coerceAtLeast(1)
    val hopSamples: Int = (safeSampleRate / BpmDetector.TARGET_ENVELOPE_RATE_HZ).coerceAtLeast(1)
    val envelopeRateHz: Double = safeSampleRate.toDouble() / hopSamples.toDouble()

    private var energyFrames = FloatArray(2_560)
    private var energyCount = 0

    private var currentHopFrames = 0
    private var currentHopAbsSum = 0.0
    private var partialChannelIndex = 0
    private var partialFrameChannelSum = 0.0

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
                    currentHopAbsSum += abs(pcmInterleaved[idx].toInt()) * invScale
                    currentHopFrames++
                    if (currentHopFrames == hopSamples) {
                        pushEnergyFrame((currentHopAbsSum / hopSamples).toFloat())
                        currentHopFrames = 0
                        currentHopAbsSum = 0.0
                    }
                    idx++
                }
            } else if (safeChannelCount == 2) {
                while (idx < end) {
                    val l = abs(pcmInterleaved[idx].toInt())
                    val r = abs(pcmInterleaved[idx + 1].toInt())
                    currentHopAbsSum += (l + r) * invScale
                    currentHopFrames++
                    if (currentHopFrames == hopSamples) {
                        pushEnergyFrame((currentHopAbsSum / hopSamples).toFloat())
                        currentHopFrames = 0
                        currentHopAbsSum = 0.0
                    }
                    idx += 2
                }
            } else {
                while (idx < end) {
                    var chSum = 0L
                    for (c in 0 until safeChannelCount) {
                        chSum += abs(pcmInterleaved[idx + c].toInt())
                    }
                    currentHopAbsSum += chSum * invScale
                    currentHopFrames++
                    if (currentHopFrames == hopSamples) {
                        pushEnergyFrame((currentHopAbsSum / hopSamples).toFloat())
                        currentHopFrames = 0
                        currentHopAbsSum = 0.0
                    }
                    idx += safeChannelCount
                }
            }
            return
        }

        for (i in sampleOffset until (sampleOffset + maxSamples)) {
            feedNormalizedSample(abs(pcmInterleaved[i].toInt()) / 32768.0)
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
            feedNormalizedSample(abs(shortBuffer.get().toInt()) / 32768.0)
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
                    val norm = if (v.isFinite()) abs(v).coerceIn(0.0, 1.0) else 0.0
                    feedNormalizedSample(norm)
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                while (slice.hasRemaining()) {
                    val unsigned = slice.get().toInt() and 0xFF
                    val norm = abs(unsigned - 128) / 128.0
                    feedNormalizedSample(norm)
                }
            }
            else -> {
                if (size < 2) return
                feedShortBuffer(slice.asShortBuffer())
            }
        }
    }

    private fun feedNormalizedSample(absSample: Double) {
        partialFrameChannelSum += absSample
        partialChannelIndex++
        if (partialChannelIndex >= safeChannelCount) {
            currentHopAbsSum += partialFrameChannelSum / safeChannelCount
            partialFrameChannelSum = 0.0
            partialChannelIndex = 0
            currentHopFrames++
            if (currentHopFrames == hopSamples) {
                pushEnergyFrame((currentHopAbsSum / hopSamples).toFloat())
                currentHopFrames = 0
                currentHopAbsSum = 0.0
            }
        }
    }

    private fun pushEnergyFrame(energy: Float) {
        if (energyCount == energyFrames.size) {
            energyFrames = energyFrames.copyOf(energyFrames.size * 2)
        }
        energyFrames[energyCount++] = energy
    }

    fun computeBpm(): Int? {
        val n = energyCount
        val minRequiredHops = (envelopeRateHz * BpmDetector.MIN_DURATION_SECONDS).toInt().coerceAtLeast(180)
        if (n < minRequiredHops) return null

        var peakEnergy = 0f
        for (i in 0 until n) {
            if (energyFrames[i] > peakEnergy) peakEnergy = energyFrames[i]
        }
        if (peakEnergy < BpmDetector.SILENCE_ENERGY_FLOOR) return null

        // 1. Half-wave rectified first-order difference O[k] = max(0f, E[k] - E[k - 1])
        val rawOnset = FloatArray(n)
        var peakOnset = 0f
        for (k in 1 until n) {
            val diff = max(0f, energyFrames[k] - energyFrames[k - 1])
            rawOnset[k] = diff
            if (diff > peakOnset) peakOnset = diff
        }
        // Reject steady unmodulated tones (e.g. continuous sine waves) lacking rhythmic transients
        if (peakOnset < peakEnergy * BpmDetector.MIN_ONSET_MODULATION_RATIO) return null

        // 2. 3-tap smoothing [0.25, 0.50, 0.25]
        val smoothed = FloatArray(n)
        smoothed[0] = 0.50f * rawOnset[0] + 0.50f * rawOnset[1]
        for (k in 1 until n - 1) {
            smoothed[k] = 0.25f * rawOnset[k - 1] + 0.50f * rawOnset[k] + 0.25f * rawOnset[k + 1]
        }
        smoothed[n - 1] = 0.50f * rawOnset[n - 2] + 0.50f * rawOnset[n - 1]

        // 3. Zero-mean centering and zero-lag variance R[0]
        var meanAcc = 0.0
        for (k in 0 until n) {
            meanAcc += smoothed[k]
        }
        val mean = (meanAcc / n).toFloat()

        val centered = FloatArray(n)
        var r0Acc = 0.0
        for (k in 0 until n) {
            val v = smoothed[k] - mean
            centered[k] = v
            r0Acc += v * v
        }
        val r0 = r0Acc / n
        if (r0 <= 1e-12) return null

        // 4. Autocorrelation over lags spanning 45..220 BPM (plus 2*tau harmonic range)
        val minLag = floor(60.0 * envelopeRateHz / BpmDetector.MAX_SEARCH_BPM).toInt().coerceAtLeast(2)
        val maxLag = ceil(60.0 * envelopeRateHz / BpmDetector.MIN_SEARCH_BPM).toInt().coerceAtMost(n / 2 - 1)
        if (maxLag <= minLag) return null

        val minComputeLag = (minLag / 2).coerceAtLeast(1)
        val maxCorrLag = minOf(maxLag * 2 + 2, n - 16)
        if (maxCorrLag <= minLag) return null

        val corr = FloatArray(maxCorrLag + 1)
        corr[0] = 1.0f
        for (lag in minComputeLag..maxCorrLag) {
            val count = n - lag
            var sum = 0.0
            var k = 0
            while (k < count) {
                sum += centered[k] * centered[k + lag]
                k++
            }
            corr[lag] = ((sum / count) / r0).toFloat()
        }

        var maxPrimaryCorr = 0f
        for (lag in minLag..maxLag) {
            if (corr[lag] > maxPrimaryCorr) {
                maxPrimaryCorr = corr[lag]
            }
        }
        if (maxPrimaryCorr < BpmDetector.MIN_AUTOCORR_CLARITY) return null

        // 5. Log-Gaussian tempo weight centered at 120 BPM + octave harmonic scoring (R[tau] + 0.35 * R[2*tau])
        var bestLag = minLag
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            val bpmCandidate = 60.0 * envelopeRateHz / lag.toDouble()
            val octaveOffset = ln(bpmCandidate / BpmDetector.TEMPO_PRIOR_CENTER_BPM) / BpmDetector.LN_2
            val tempoWeight = exp(-0.5 * (octaveOffset / BpmDetector.TEMPO_PRIOR_SIGMA_OCTAVES).pow(2))

            val doubleLag = lag * 2
            val r2 = if (doubleLag <= maxCorrLag) {
                val c0 = corr[doubleLag]
                val cm = if (doubleLag - 1 >= minComputeLag) corr[doubleLag - 1] else c0
                val cp = if (doubleLag + 1 <= maxCorrLag) corr[doubleLag + 1] else c0
                maxOf(0f, maxOf(cm, c0, cp))
            } else {
                0f
            }

            val harmonicSum = corr[lag].toDouble() + BpmDetector.OCTAVE_HARMONIC_WEIGHT * r2.toDouble()
            val weightedScore = harmonicSum * tempoWeight
            if (weightedScore > bestScore) {
                bestScore = weightedScore
                bestLag = lag
            }
        }

        // 6. Parabolic sub-hop interpolation around bestLag for exact fractional-lag BPM (e.g. 160 BPM = 37.5 hops)
        var refinedLag = bestLag.toDouble()
        if (bestLag - 1 >= minComputeLag && bestLag + 1 <= maxCorrLag) {
            val yMinus = corr[bestLag - 1].toDouble()
            val yZero = corr[bestLag].toDouble()
            val yPlus = corr[bestLag + 1].toDouble()
            val denom = 2.0 * (yMinus - 2.0 * yZero + yPlus)
            if (abs(denom) > 1e-6 && yZero >= yMinus - 1e-4 && yZero >= yPlus - 1e-4) {
                val delta = ((yMinus - yPlus) / denom).coerceIn(-0.6, 0.6)
                refinedLag = bestLag + delta
            }
        }

        var detectedBpm = 60.0 * envelopeRateHz / refinedLag

        // 7. Resolve remaining double/half-time ambiguities into [65, 170] BPM
        if (detectedBpm < BpmDetector.PRIMARY_RANGE_MIN_BPM) {
            val halfCenter = refinedLag * 0.5
            val halfFloor = floor(halfCenter).toInt()
            val halfCeil = ceil(halfCenter).toInt()
            val rHalf = maxOf(
                if (halfFloor in minComputeLag..maxCorrLag) corr[halfFloor] else 0f,
                if (halfCeil in minComputeLag..maxCorrLag) corr[halfCeil] else 0f,
            )
            if (rHalf > 0.30f * corr[bestLag]) {
                detectedBpm *= 2.0
            }
        } else if (detectedBpm * 2.0 <= BpmDetector.PRIMARY_RANGE_MAX_BPM) {
            // If every half-period (backbeat) has near-equal quarter-note energy, prefer double-time inside [65, 170]
            val halfCenter = refinedLag * 0.5
            val halfFloor = floor(halfCenter).toInt()
            val halfCeil = ceil(halfCenter).toInt()
            val rHalf = maxOf(
                if (halfFloor in minComputeLag..maxCorrLag) corr[halfFloor] else 0f,
                if (halfCeil in minComputeLag..maxCorrLag) corr[halfCeil] else 0f,
            )
            if (rHalf >= BpmDetector.SUBHARMONIC_PROMOTE_RATIO * corr[bestLag]) {
                detectedBpm *= 2.0
            }
        } else if (detectedBpm > BpmDetector.PRIMARY_RANGE_MAX_BPM) {
            val dblLag = (refinedLag * 2.0).roundToInt()
            if (dblLag in minComputeLag..maxCorrLag && corr[dblLag] > 0.45f * corr[bestLag]) {
                detectedBpm *= 0.5
            }
        }

        return PracticeMath.clampBpm(detectedBpm.roundToInt())
    }
}

object BpmDetector {
    const val TARGET_ENVELOPE_RATE_HZ = 100
    const val MIN_DURATION_SECONDS = 2.5
    const val MIN_SEARCH_BPM = 45.0
    const val MAX_SEARCH_BPM = 220.0
    const val PRIMARY_RANGE_MIN_BPM = 65.0
    const val PRIMARY_RANGE_MAX_BPM = 170.0
    const val TEMPO_PRIOR_CENTER_BPM = 120.0
    const val TEMPO_PRIOR_SIGMA_OCTAVES = 0.62
    const val OCTAVE_HARMONIC_WEIGHT = 0.35
    const val SUBHARMONIC_PROMOTE_RATIO = 0.62f
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
     * Parses a raw embedded `TBPM` / `BPM` metadata value (e.g. `"120"`, `"128.0"`, `"96 BPM"`),
     * returning a valid BPM in `30..300` or `null` if missing/invalid.
     */
    fun parseBpmTagString(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val match = BPM_NUMBER_REGEX.find(raw.trim()) ?: return null
        val numeric = match.value.toDoubleOrNull() ?: return null
        if (!numeric.isFinite()) return null
        val rounded = numeric.roundToInt()
        return rounded.takeIf { it in 30..300 }
    }

    /**
     * Fast pure-JVM check for embedded ID3v2 (`TBPM` / `TBP`) or FLAC (`BPM=` / `TEMPO=`) tags
     * inside the leading header bytes of an audio file.
     */
    fun extractEmbeddedBpmFromHeader(headerBytes: ByteArray, length: Int = headerBytes.size): Int? {
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
                        decodeId3TextPayload(headerBytes, pos + 6, frameSize)?.let(::parseBpmTagString)?.let { return it }
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
                        decodeId3TextPayload(headerBytes, pos + 10, frameSize)?.let(::parseBpmTagString)?.let { return it }
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

    private fun extractVorbisCommentBpm(bytes: ByteArray, offset: Int, length: Int): Int? {
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
                    parseBpmTagString(entry.substring(eq + 1))?.let { return it }
                }
            }
            pos += len
        }
        return null
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
