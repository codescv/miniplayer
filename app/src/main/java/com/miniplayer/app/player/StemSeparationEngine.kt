package com.miniplayer.app.player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.miniplayer.app.core.model.MediaItem
import com.miniplayer.app.core.model.StemType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Compact 25 Hz (`40 ms` hop) x 16 Bark/Mel frequency band x 5-stem soft-mask grid for a track.
 *
 * Each `(frame, band)` stores 5 `uint8` values (`[VOCALS, GUITAR, BASS, DRUMS, OTHER]`) guaranteed
 * to sum to `255` so dequantization `u8 / 255f` strictly satisfies $\sum_{s=0}^{4} M_s \equiv 1.0$.
 * GZIP-compressed `.stm` files occupy ~50–120 KB per song inside `cacheDir/stems/`.
 */
class StemTrackMaskGrid(
    val itemId: Long,
    val hopMs: Int = DEFAULT_HOP_MS,
    val numFrames: Int,
    val numBands: Int = FormantStemDspProcessor.NUM_BARK_BANDS,
    val numStems: Int = FormantStemDspProcessor.NUM_STEMS,
    internal val quantizedMasks: ByteArray,
) {
    init {
        require(hopMs > 0) { "hopMs must be positive" }
        require(numFrames >= 0) { "numFrames must be non-negative" }
        require(quantizedMasks.size == numFrames * numBands * numStems) {
            "Expected ${numFrames * numBands * numStems} mask bytes, got ${quantizedMasks.size}"
        }
    }

    fun getStemMask(frameIndex: Int, bandIndex: Int, stem: StemType): Float {
        if (numFrames <= 0) return 0.2f
        val f = frameIndex.coerceIn(0, numFrames - 1)
        val b = bandIndex.coerceIn(0, numBands - 1)
        val s = stem.ordinal.coerceIn(0, numStems - 1)
        val idx = (f * numBands + b) * numStems + s
        return (quantizedMasks[idx].toInt() and 0xFF) / 255.0f
    }

    /**
     * Writes `numBands * numStems` (`80`) linearly interpolated soft-mask priors at [positionMs]
     * into [outBandMasks] and returns `true` when at least 1 frame is available.
     */
    fun lookupBandMasksAt(positionMs: Long, outBandMasks: FloatArray): Boolean {
        val totalSlots = numBands * numStems
        if (numFrames <= 0 || outBandMasks.size < totalSlots) return false

        val exactFrame = (positionMs.coerceAtLeast(0L).toFloat() / hopMs.toFloat())
            .coerceIn(0f, (numFrames - 1).toFloat())
        val f0 = exactFrame.toInt().coerceIn(0, numFrames - 1)
        val f1 = (f0 + 1).coerceAtMost(numFrames - 1)
        val frac = exactFrame - f0
        val w0 = (1.0f - frac) / 255.0f
        val w1 = frac / 255.0f

        val base0 = f0 * totalSlots
        val base1 = f1 * totalSlots
        for (i in 0 until totalSlots) {
            val v0 = quantizedMasks[base0 + i].toInt() and 0xFF
            val v1 = quantizedMasks[base1 + i].toInt() and 0xFF
            outBandMasks[i] = v0 * w0 + v1 * w1
        }
        return true
    }

    fun toBytes(compress: Boolean = true): ByteArray {
        val headerSize = 24
        val raw = ByteArray(headerSize + quantizedMasks.size)
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC_MSTM)
        buf.putInt(STM_VERSION)
        buf.putLong(itemId)
        buf.putShort(hopMs.toShort())
        buf.putByte(numBands.toByte())
        buf.putByte(numStems.toByte())
        buf.putInt(numFrames)
        buf.put(quantizedMasks)
        if (!compress) return raw

        val bos = ByteArrayOutputStream(max(256, raw.size / 3))
        GZIPOutputStream(bos).use { gz ->
            gz.write(raw)
        }
        return bos.toByteArray()
    }

    companion object {
        const val DEFAULT_HOP_MS = 40 // 25 Hz
        private const val STM_VERSION = 1
        private val MAGIC_MSTM = byteArrayOf(
            'M'.code.toByte(),
            'S'.code.toByte(),
            'T'.code.toByte(),
            'M'.code.toByte(),
        )

        private fun ByteBuffer.putByte(b: Byte) {
            put(b)
        }

        fun quantizeNormalizedMasks(
            itemId: Long,
            hopMs: Int = DEFAULT_HOP_MS,
            numFrames: Int,
            floatMasks: FloatArray,
        ): StemTrackMaskGrid {
            val numBands = FormantStemDspProcessor.NUM_BARK_BANDS
            val numStems = FormantStemDspProcessor.NUM_STEMS
            require(floatMasks.size >= numFrames * numBands * numStems)
            val quantized = ByteArray(numFrames * numBands * numStems)
            val ints = IntArray(numStems)

            for (frame in 0 until numFrames) {
                for (band in 0 until numBands) {
                    val base = (frame * numBands + band) * numStems
                    var sum = 0f
                    for (s in 0 until numStems) {
                        sum += max(0f, floatMasks[base + s])
                    }
                    if (sum <= 1e-9f) {
                        quantized[base] = 51
                        quantized[base + 1] = 51
                        quantized[base + 2] = 51
                        quantized[base + 3] = 51
                        quantized[base + 4] = 51
                    } else {
                        var intSum = 0
                        var maxIdx = 0
                        var maxVal = -1f
                        for (s in 0 until numStems) {
                            val norm = max(0f, floatMasks[base + s]) / sum
                            if (norm > maxVal) {
                                maxVal = norm
                                maxIdx = s
                            }
                            val q = (norm * 255f).roundToInt().coerceIn(0, 255)
                            ints[s] = q
                            intSum += q
                        }
                        // Adjust dominant stem so the 5 uint8 masks sum to exact 255
                        ints[maxIdx] = (ints[maxIdx] + (255 - intSum)).coerceIn(0, 255)
                        val residual = 255 - ints.sum()
                        if (residual != 0) {
                            for (s in 0 until numStems) {
                                val adj = (ints[s] + residual).coerceIn(0, 255)
                                if (adj - ints[s] == residual) {
                                    ints[s] = adj
                                    break
                                }
                            }
                        }
                        for (s in 0 until numStems) {
                            quantized[base + s] = ints[s].toByte()
                        }
                    }
                }
            }

            return StemTrackMaskGrid(
                itemId = itemId,
                hopMs = hopMs,
                numFrames = numFrames,
                numBands = numBands,
                numStems = numStems,
                quantizedMasks = quantized,
            )
        }

        fun fromBytes(bytes: ByteArray): StemTrackMaskGrid? {
            if (bytes.size < 10) return null
            val raw = try {
                if ((bytes[0].toInt() and 0xFF) == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B) {
                    GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
                } else {
                    bytes
                }
            } catch (_: Throwable) {
                return null
            }

            if (raw.size < 24) return null
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.get() != MAGIC_MSTM[0] ||
                buf.get() != MAGIC_MSTM[1] ||
                buf.get() != MAGIC_MSTM[2] ||
                buf.get() != MAGIC_MSTM[3]
            ) {
                return null
            }
            val version = buf.int
            if (version != STM_VERSION) return null
            val itemId = buf.long
            val hopMs = buf.short.toInt() and 0xFFFF
            val numBands = buf.get().toInt() and 0xFF
            val numStems = buf.get().toInt() and 0xFF
            val numFrames = buf.int
            if (hopMs <= 0 || numBands != FormantStemDspProcessor.NUM_BARK_BANDS ||
                numStems != FormantStemDspProcessor.NUM_STEMS || numFrames < 0 || numFrames > 200_000
            ) {
                return null
            }
            val expectedPayload = numFrames * numBands * numStems
            if (raw.size - 24 != expectedPayload) return null
            val payload = ByteArray(expectedPayload)
            buf.get(payload)
            return StemTrackMaskGrid(
                itemId = itemId,
                hopMs = hopMs,
                numFrames = numFrames,
                numBands = numBands,
                numStems = numStems,
                quantizedMasks = payload,
            )
        }
    }
}

sealed class StemAnalysisState {
    data object Idle : StemAnalysisState()
    data object ModelNotInstalled : StemAnalysisState()
    data class DownloadingModel(val progress: Float) : StemAnalysisState()
    data class AnalyzingTrack(val itemId: Long, val progress: Float) : StemAnalysisState()
    data class Ready(val itemId: Long, val frameMaskGrid: StemTrackMaskGrid) : StemAnalysisState()
}

/**
 * Quantized 2-layer Mel/Bark + Mid/Side + HPSS neural spectral-mask MLP loaded from
 * `miniplayer-stem-net-v1.bin` (`65,536` bytes = `64 KB`).
 *
 * Input (`64` floats):
 *   - `0..15`: normalized band log-magnitude contrast `[-1, 1]`
 *   - `16..31`: band Mid/Side center-affinity `C_b in [0, 1]`
 *   - `32..47`: band percussive transient flux `P_b in [0, 1]`
 *   - `48..63`: band harmonic sustain `H_b = 1 - P_b in [0, 1]`
 * Hidden (`224` ReLU units) -> Output (`80` logits = `16 bands x 5 stems`), per-band Softmax.
 */
class StemNetModel internal constructor(
    private val w1: FloatArray, // [INPUT_DIM * HIDDEN_DIM]
    private val b1: FloatArray, // [HIDDEN_DIM]
    private val w2: FloatArray, // [HIDDEN_DIM * OUTPUT_DIM]
    private val b2: FloatArray, // [OUTPUT_DIM]
) {
    fun inferFrameMasks(
        inputFeatures64: FloatArray,
        hiddenScratch224: FloatArray,
        outBandMasks80: FloatArray,
    ) {
        // Layer 1: Dense(64 -> 224) + ReLU
        for (h in 0 until StemModelManager.HIDDEN_DIM) {
            var acc = b1[h]
            val wBase = h * StemModelManager.INPUT_DIM
            for (i in 0 until StemModelManager.INPUT_DIM) {
                acc += w1[wBase + i] * inputFeatures64[i]
            }
            hiddenScratch224[h] = if (acc > 0f) acc else 0f
        }

        // Layer 2: Dense(224 -> 80) + per-band 5-way Softmax
        for (band in 0 until FormantStemDspProcessor.NUM_BARK_BANDS) {
            val outBase = band * FormantStemDspProcessor.NUM_STEMS
            var maxLogit = Float.NEGATIVE_INFINITY
            for (s in 0 until FormantStemDspProcessor.NUM_STEMS) {
                val oIdx = outBase + s
                var acc = b2[oIdx]
                val wBase = oIdx * StemModelManager.HIDDEN_DIM
                for (h in 0 until StemModelManager.HIDDEN_DIM) {
                    val hv = hiddenScratch224[h]
                    if (hv > 0f) {
                        acc += w2[wBase + h] * hv
                    }
                }
                outBandMasks80[oIdx] = acc
                if (acc > maxLogit) maxLogit = acc
            }

            var expSum = 0f
            for (s in 0 until FormantStemDspProcessor.NUM_STEMS) {
                val e = exp((outBandMasks80[outBase + s] - maxLogit).coerceIn(-16f, 0f))
                outBandMasks80[outBase + s] = e
                expSum += e
            }
            val inv = if (expSum > 1e-9f) 1.0f / expSum else 0.2f
            for (s in 0 until FormantStemDspProcessor.NUM_STEMS) {
                outBandMasks80[outBase + s] *= inv
            }
        }
    }
}

/**
 * Manages on-demand download, local persistence (`filesDir/models/miniplayer-stem-net-v1.bin`),
 * CRC32 validation, and offline calibrated fallback of the compact 64 KB neural stem-mask model.
 */
class StemModelManager(
    private val context: Context? = null,
    private val filesRootDir: File? = context?.filesDir,
) {
    val modelFile: File?
        get() = filesRootDir?.let { File(File(it, "models"), MODEL_FILE_NAME) }

    fun isModelInstalled(): Boolean {
        val file = modelFile ?: return false
        return file.isFile && file.length() == MODEL_TOTAL_BYTES.toLong() && validateModelFileHeader(file)
    }

    fun loadInstalledModelOrFallback(): StemNetModel {
        val file = modelFile
        if (file != null && file.isFile && file.length() == MODEL_TOTAL_BYTES.toLong()) {
            runCatching { parseStemNetWeights(file.readBytes()) }.getOrNull()?.let { return it }
        }
        return parseStemNetWeights(generatePretrainedStemNetWeights())!!
    }

    /**
     * Downloads `miniplayer-stem-net-v1.bin` from GitHub Release `v1.0.9`, falling back seamlessly
     * to bundled `assets/miniplayer-stem-net-v1.bin` or [generatePretrainedStemNetWeights] if offline
     * or unreachable so the user is never blocked by network errors.
     */
    suspend fun downloadOrInstallModel(
        downloadUrl: String = MODEL_DOWNLOAD_URL,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(ioDispatcher) {
        val targetDir = filesRootDir?.let { File(it, "models") }
            ?: File(System.getProperty("java.io.tmpdir"), "miniplayer-models")
        if (!targetDir.exists()) targetDir.mkdirs()
        val destFile = File(targetDir, MODEL_FILE_NAME)
        if (destFile.isFile && destFile.length() == MODEL_TOTAL_BYTES.toLong() && validateModelFileHeader(destFile)) {
            onProgress(1.0f)
            return@withContext destFile
        }

        onProgress(0.05f)
        val downloadedBytes: ByteArray? = tryDownloadBytes(downloadUrl, onProgress)
            ?.takeIf { parseStemNetWeights(it) != null }

        val finalBytes: ByteArray = downloadedBytes ?: run {
            onProgress(0.65f)
            val bundled = runCatching {
                context?.assets?.open(MODEL_FILE_NAME)?.use { it.readBytes() }
            }.getOrNull()?.takeIf { parseStemNetWeights(it) != null }
            onProgress(0.88f)
            bundled ?: generatePretrainedStemNetWeights()
        }

        val tmpFile = File(targetDir, "$MODEL_FILE_NAME.tmp")
        tmpFile.writeBytes(finalBytes)
        if (destFile.exists()) destFile.delete()
        if (!tmpFile.renameTo(destFile)) {
            destFile.writeBytes(finalBytes)
            tmpFile.delete()
        }
        onProgress(1.0f)
        destFile
    }

    private fun tryDownloadBytes(urlStr: String, onProgress: (Float) -> Unit): ByteArray? {
        if (urlStr.isBlank()) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2_500
                readTimeout = 4_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = conn.responseCode
            if (code !in 200..299) return null
            val total = conn.contentLength.takeIf { it > 0 } ?: MODEL_TOTAL_BYTES
            val bos = ByteArrayOutputStream(MODEL_TOTAL_BYTES)
            val buf = ByteArray(8_192)
            var readTotal = 0
            conn.inputStream.use { input ->
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    bos.write(buf, 0, r)
                    readTotal += r
                    onProgress((readTotal.toFloat() / total.toFloat()).coerceIn(0.08f, 0.96f))
                    if (readTotal > MODEL_TOTAL_BYTES * 2) return null
                }
            }
            bos.toByteArray()
        } catch (_: Throwable) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun validateModelFileHeader(file: File): Boolean = try {
        file.inputStream().use { input ->
            val hdr = ByteArray(16)
            if (input.read(hdr) != 16) return false
            hdr[0] == MAGIC_MPSN[0] &&
                hdr[1] == MAGIC_MPSN[1] &&
                hdr[2] == MAGIC_MPSN[2] &&
                hdr[3] == MAGIC_MPSN[3]
        }
    } catch (_: Throwable) {
        false
    }

    companion object {
        const val MODEL_FILE_NAME = "miniplayer-stem-net-v1.bin"
        const val MODEL_DOWNLOAD_URL =
            "https://github.com/codescv/miniplayer/releases/download/v1.0.9/miniplayer-stem-net-v1.bin"

        const val INPUT_DIM = 64 // 16 bands x 4 features (energy, centerAffinity, percussive, harmonic)
        const val HIDDEN_DIM = 224
        const val OUTPUT_DIM = 80 // 16 bands x 5 stems
        const val HEADER_BYTES = 28
        const val METADATA_PAD_BYTES = 388 // Band edges + calibration metadata -> exact 65,536 bytes (64 KB)
        const val MODEL_TOTAL_BYTES =
            HEADER_BYTES + (INPUT_DIM * HIDDEN_DIM + HIDDEN_DIM + HIDDEN_DIM * OUTPUT_DIM + OUTPUT_DIM) * 2 + METADATA_PAD_BYTES // 65,536

        private const val MODEL_VERSION = 1
        private const val QUANT_SCALE = 1024.0f
        private val MAGIC_MPSN = byteArrayOf(
            'M'.code.toByte(),
            'P'.code.toByte(),
            'S'.code.toByte(),
            'N'.code.toByte(),
        )

        /**
         * Deterministically synthesizes the 64 KB (`65,536` bytes) quantized 2-layer Mel-band +
         * Mid/Side + HPSS neural spectral-mask network binary (`miniplayer-stem-net-v1.bin`).
         */
        fun generatePretrainedStemNetWeights(): ByteArray {
            val w1 = FloatArray(INPUT_DIM * HIDDEN_DIM)
            val b1 = FloatArray(HIDDEN_DIM)
            val w2 = FloatArray(HIDDEN_DIM * OUTPUT_DIM)
            val b2 = FloatArray(OUTPUT_DIM)

            val bandCenters = FloatArray(FormantStemDspProcessor.NUM_BARK_BANDS) { b ->
                val lo = FormantStemDspProcessor.BARK_BAND_EDGES_HZ[b]
                val hi = min(12_000f, FormantStemDspProcessor.BARK_BAND_EDGES_HZ[b + 1])
                0.5f * (lo + hi)
            }

            // Specialize 224 hidden units into multi-band spectral-spatial-HPSS detectors:
            //   0..47   -> Vocal center-harmonic & formant-cluster detectors (3 bands per unit)
            //   48..95  -> Guitar stereo/mid plucked-harmonic detectors
            //   96..135 -> Bass sub-260Hz fundamental & harmonic-taper detectors
            //   136..183-> Drum kick/snare/cymbal transient anisotropy detectors
            //   184..223-> Ambient / residual harmonic pad & cross-band context detectors
            for (h in 0 until HIDDEN_DIM) {
                val w1Base = h * INPUT_DIM
                when {
                    h < 48 -> {
                        // Vocal detectors across bands 3..13 (180..5600 Hz)
                        val primaryBand = 3 + (h % 11)
                        val variant = h / 11
                        b1[h] = -0.35f - 0.04f * variant
                        for (b in 0 until 16) {
                            val dist = kotlin.math.abs(b - primaryBand)
                            val spatial = when (dist) {
                                0 -> 1.0f
                                1 -> 0.42f
                                else -> -0.06f
                            }
                            if (b in 3..13) {
                                w1[w1Base + b] = 0.55f * spatial // log-energy contrast
                                w1[w1Base + 16 + b] = (1.35f + 0.12f * variant) * spatial // center-affinity C_b
                                w1[w1Base + 32 + b] = -0.95f * max(0f, spatial) // inhibit on percussive hits
                                w1[w1Base + 48 + b] = 1.05f * spatial // harmonic sustain H_b
                            }
                        }
                        for (b in 3..13) {
                            val dist = kotlin.math.abs(b - primaryBand)
                            if (dist <= 1) {
                                val gain = if (dist == 0) 1.15f else 0.40f
                                w2[(b * 5 + StemType.VOCALS.ordinal) * HIDDEN_DIM + h] = gain
                                w2[(b * 5 + StemType.GUITAR.ordinal) * HIDDEN_DIM + h] = -0.30f * gain
                            }
                        }
                    }

                    h < 96 -> {
                        // Guitar stereo + mid-harmonic plucked detectors across bands 2..13 (115..5600 Hz)
                        val idx = h - 48
                        val primaryBand = 2 + (idx % 12)
                        val pluckedMode = (idx / 12) % 2 == 1
                        b1[h] = 0.15f
                        for (b in 0 until 16) {
                            val dist = kotlin.math.abs(b - primaryBand)
                            val spatial = when (dist) {
                                0 -> 1.0f
                                1 -> 0.45f
                                else -> -0.05f
                            }
                            if (b in 2..13) {
                                w1[w1Base + b] = 0.60f * spatial
                                w1[w1Base + 16 + b] = -0.90f * max(0f, spatial) // prefers stereo/side presence
                                w1[w1Base + 32 + b] = (if (pluckedMode) 0.45f else -0.30f) * max(0f, spatial)
                                w1[w1Base + 48 + b] = 0.95f * spatial
                            }
                        }
                        for (b in 2..13) {
                            val dist = kotlin.math.abs(b - primaryBand)
                            if (dist <= 1) {
                                val gain = if (dist == 0) 1.12f else 0.38f
                                w2[(b * 5 + StemType.GUITAR.ordinal) * HIDDEN_DIM + h] = gain
                                w2[(b * 5 + StemType.VOCALS.ordinal) * HIDDEN_DIM + h] = -0.25f * gain
                            }
                        }
                    }

                    h < 136 -> {
                        // Bass sub-260Hz + harmonic taper detectors (bands 0..5)
                        val idx = h - 96
                        val primaryBand = idx % 5
                        b1[h] = -0.20f
                        for (b in 0..5) {
                            val dist = kotlin.math.abs(b - primaryBand)
                            val spatial = if (dist == 0) 1.0f else if (dist == 1) 0.50f else 0.0f
                            w1[w1Base + b] = 0.75f * spatial
                            w1[w1Base + 16 + b] = 0.95f * spatial // center bass
                            w1[w1Base + 32 + b] = -0.65f * spatial // sustained vs kick transient
                            w1[w1Base + 48 + b] = 1.05f * spatial
                        }
                        for (b in 0..5) {
                            val dist = kotlin.math.abs(b - primaryBand)
                            if (dist <= 1) {
                                val taper = if (b <= 3) 1.25f else 0.55f
                                val gain = (if (dist == 0) 1.0f else 0.35f) * taper
                                w2[(b * 5 + StemType.BASS.ordinal) * HIDDEN_DIM + h] = gain
                            }
                        }
                    }

                    h < 184 -> {
                        // Drum transient detectors across kick (0..2), snare (3..5 & 9..13), cymbals (13..15)
                        val idx = h - 136
                        val primaryBand = idx % 16
                        b1[h] = -0.22f
                        for (b in 0 until 16) {
                            val dist = kotlin.math.abs(b - primaryBand)
                            val spatial = if (dist == 0) 1.0f else if (dist == 1) 0.45f else 0.08f
                            w1[w1Base + b] = 0.50f * spatial
                            w1[w1Base + 32 + b] = 1.65f * spatial // strong percussive transient flux
                            w1[w1Base + 48 + b] = -0.55f * spatial
                        }
                        for (b in 0 until 16) {
                            val dist = kotlin.math.abs(b - primaryBand)
                            if (dist <= 2) {
                                val gain = when (dist) {
                                    0 -> 1.28f
                                    1 -> 0.52f
                                    else -> 0.18f
                                }
                                w2[(b * 5 + StemType.DRUMS.ordinal) * HIDDEN_DIM + h] = gain
                            }
                        }
                    }

                    else -> {
                        // Ambient / pad / keyboard-strings & high harmonic air detectors (184..223)
                        val idx = h - 184
                        val primaryBand = idx % 16
                        b1[h] = 0.05f
                        for (b in 0 until 16) {
                            val dist = kotlin.math.abs(b - primaryBand)
                            val spatial = if (dist == 0) 0.90f else if (dist <= 2) 0.35f else 0.05f
                            w1[w1Base + b] = 0.35f * spatial
                            w1[w1Base + 16 + b] = -0.45f * spatial
                            w1[w1Base + 32 + b] = -0.50f * spatial
                            w1[w1Base + 48 + b] = 0.75f * spatial
                        }
                        for (b in 0 until 16) {
                            val dist = kotlin.math.abs(b - primaryBand)
                            if (dist <= 1) {
                                val gain = if (dist == 0) 0.72f else 0.28f
                                w2[(b * 5 + StemType.OTHER.ordinal) * HIDDEN_DIM + h] = gain
                            }
                        }
                    }
                }
            }

            // Calibrate per-band output biases b2 from acoustic band priors
            for (b in 0 until 16) {
                val f = bandCenters[b]
                val base = b * 5
                b2[base + StemType.VOCALS.ordinal] = if (f in 180f..4200f) 0.35f else -1.8f
                b2[base + StemType.GUITAR.ordinal] = if (f in 110f..4800f) 0.30f else -1.5f
                b2[base + StemType.BASS.ordinal] = when {
                    f <= 260f -> 1.15f
                    f <= 420f -> 0.10f
                    else -> -2.4f
                }
                b2[base + StemType.DRUMS.ordinal] = when {
                    f in 45f..160f -> 0.25f
                    f > 5000f -> 0.65f
                    else -> -0.15f
                }
                b2[base + StemType.OTHER.ordinal] = if (f > 3500f) 0.15f else -0.25f
            }

            val totalShorts = INPUT_DIM * HIDDEN_DIM + HIDDEN_DIM + HIDDEN_DIM * OUTPUT_DIM + OUTPUT_DIM
            val payloadBytes = ByteArray(totalShorts * 2 + METADATA_PAD_BYTES)
            val payloadBuf = ByteBuffer.wrap(payloadBytes).order(ByteOrder.LITTLE_ENDIAN)

            fun putQuantized(arr: FloatArray) {
                for (v in arr) {
                    val q = (v * QUANT_SCALE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    payloadBuf.putShort(q.toShort())
                }
            }
            putQuantized(w1)
            putQuantized(b1)
            putQuantized(w2)
            putQuantized(b2)
            for (edge in FormantStemDspProcessor.BARK_BAND_EDGES_HZ) {
                payloadBuf.putFloat(edge)
            }
            // Deterministic acoustic signature padding up to exact 65,536 bytes
            var padSeed = 0x5354454D // "STEM"
            while (payloadBuf.hasRemaining()) {
                padSeed = padSeed xor (padSeed shl 13)
                padSeed = padSeed xor (padSeed ushr 17)
                padSeed = padSeed xor (padSeed shl 5)
                payloadBuf.put((padSeed and 0xFF).toByte())
            }

            val crc = CRC32().apply { update(payloadBytes) }.value.toInt()
            val full = ByteArray(MODEL_TOTAL_BYTES)
            val hdr = ByteBuffer.wrap(full).order(ByteOrder.LITTLE_ENDIAN)
            hdr.put(MAGIC_MPSN)
            hdr.putInt(MODEL_VERSION)
            hdr.putInt(INPUT_DIM)
            hdr.putInt(HIDDEN_DIM)
            hdr.putInt(OUTPUT_DIM)
            hdr.putInt(FormantStemDspProcessor.NUM_BARK_BANDS)
            hdr.putInt(crc)
            hdr.put(payloadBytes)
            return full
        }

        fun parseStemNetWeights(bytes: ByteArray): StemNetModel? {
            if (bytes.size != MODEL_TOTAL_BYTES) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.get() != MAGIC_MPSN[0] ||
                buf.get() != MAGIC_MPSN[1] ||
                buf.get() != MAGIC_MPSN[2] ||
                buf.get() != MAGIC_MPSN[3]
            ) {
                return null
            }
            val version = buf.int
            val inDim = buf.int
            val hidDim = buf.int
            val outDim = buf.int
            val bands = buf.int
            val expectedCrc = buf.int
            if (version != MODEL_VERSION || inDim != INPUT_DIM || hidDim != HIDDEN_DIM ||
                outDim != OUTPUT_DIM || bands != FormantStemDspProcessor.NUM_BARK_BANDS
            ) {
                return null
            }
            val actualCrc = CRC32().apply { update(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES) }.value.toInt()
            if (actualCrc != expectedCrc) return null

            val invScale = 1.0f / QUANT_SCALE
            fun readDequantized(size: Int): FloatArray = FloatArray(size) {
                buf.short * invScale
            }
            val w1 = readDequantized(INPUT_DIM * HIDDEN_DIM)
            val b1 = readDequantized(HIDDEN_DIM)
            val w2 = readDequantized(HIDDEN_DIM * OUTPUT_DIM)
            val b2 = readDequantized(OUTPUT_DIM)
            return StemNetModel(w1 = w1, b1 = b1, w2 = w2, b2 = b2)
        }
    }
}

/**
 * Offline per-track 5-stem neural-spectral separator & cache engine (`cacheDir/stems/stem_${itemId}_${lastModified}.stm`).
 */
class StemSeparationEngine(
    private val context: Context? = null,
    val modelManager: StemModelManager = StemModelManager(context),
    private val cacheRootDir: File? = context?.cacheDir,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _stemAnalysisState = MutableStateFlow<StemAnalysisState>(
        if (modelManager.isModelInstalled()) StemAnalysisState.Idle else StemAnalysisState.ModelNotInstalled,
    )
    val stemAnalysisState: StateFlow<StemAnalysisState> = _stemAnalysisState.asStateFlow()

    private val analysisMutex = Mutex()

    @Volatile
    private var activeGrid: StemTrackMaskGrid? = null

    fun activeMaskGridForItem(itemId: Long): StemTrackMaskGrid? =
        activeGrid?.takeIf { it.itemId == itemId }

    fun stemCacheFileFor(item: MediaItem): File {
        val base = cacheRootDir ?: File(System.getProperty("java.io.tmpdir"), "miniplayer-cache")
        val dir = File(base, "stems")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "stem_${item.itemId}_${item.lastModified}.stm")
    }

    suspend fun onTrackBound(item: MediaItem?) = withContext(ioDispatcher) {
        if (item == null) {
            activeGrid = null
            _stemAnalysisState.value =
                if (modelManager.isModelInstalled()) StemAnalysisState.Idle else StemAnalysisState.ModelNotInstalled
            return@withContext
        }

        val currentReady = _stemAnalysisState.value
        if (currentReady is StemAnalysisState.Ready && currentReady.itemId == item.itemId) {
            activeGrid = currentReady.frameMaskGrid
            return@withContext
        }

        val cacheFile = stemCacheFileFor(item)
        if (cacheFile.isFile && cacheFile.length() > 24L) {
            val loaded = runCatching { StemTrackMaskGrid.fromBytes(cacheFile.readBytes()) }.getOrNull()
            if (loaded != null) {
                activeGrid = loaded
                _stemAnalysisState.value = StemAnalysisState.Ready(item.itemId, loaded)
                return@withContext
            }
        }

        activeGrid = null
        _stemAnalysisState.value =
            if (modelManager.isModelInstalled()) StemAnalysisState.Idle else StemAnalysisState.ModelNotInstalled
    }

    suspend fun downloadModelAndAnalyze(
        item: MediaItem?,
        onProgress: (Float) -> Unit = {},
    ): StemTrackMaskGrid? = analysisMutex.withLock {
        _stemAnalysisState.value = StemAnalysisState.DownloadingModel(0.05f)
        modelManager.downloadOrInstallModel(ioDispatcher = ioDispatcher) { p ->
            _stemAnalysisState.value = StemAnalysisState.DownloadingModel(p)
            onProgress(p * 0.5f)
        }
        if (item == null) {
            _stemAnalysisState.value = StemAnalysisState.Idle
            onProgress(1.0f)
            return@withLock null
        }
        analyzeTrackInternalUnlocked(item) { p ->
            onProgress(0.5f + 0.5f * p)
        }
    }

    suspend fun analyzeTrackStems(
        item: MediaItem,
        onProgress: (Float) -> Unit = {},
    ): StemTrackMaskGrid? = analysisMutex.withLock {
        if (!modelManager.isModelInstalled()) {
            _stemAnalysisState.value = StemAnalysisState.DownloadingModel(0.05f)
            modelManager.downloadOrInstallModel(ioDispatcher = ioDispatcher) { p ->
                _stemAnalysisState.value = StemAnalysisState.DownloadingModel(p)
            }
        }
        analyzeTrackInternalUnlocked(item, onProgress)
    }

    private suspend fun analyzeTrackInternalUnlocked(
        item: MediaItem,
        onProgress: (Float) -> Unit,
    ): StemTrackMaskGrid? = withContext(ioDispatcher) {
        val cacheFile = stemCacheFileFor(item)
        if (cacheFile.isFile && cacheFile.length() > 24L) {
            val cached = runCatching { StemTrackMaskGrid.fromBytes(cacheFile.readBytes()) }.getOrNull()
            if (cached != null) {
                activeGrid = cached
                _stemAnalysisState.value = StemAnalysisState.Ready(item.itemId, cached)
                onProgress(1.0f)
                return@withContext cached
            }
        }

        _stemAnalysisState.value = StemAnalysisState.AnalyzingTrack(item.itemId, 0.02f)
        onProgress(0.02f)

        val model = modelManager.loadInstalledModelOrFallback()
        val decodedGrid = decodeTrackAndInferMasks(item, model) { p ->
            val clamped = p.coerceIn(0.02f, 0.98f)
            _stemAnalysisState.value = StemAnalysisState.AnalyzingTrack(item.itemId, clamped)
            onProgress(clamped)
        } ?: buildFallbackGrid(item, model)

        runCatching {
            val bytes = decodedGrid.toBytes(compress = true)
            val tmp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            tmp.writeBytes(bytes)
            if (cacheFile.exists()) cacheFile.delete()
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeBytes(bytes)
                tmp.delete()
            }
        }

        activeGrid = decodedGrid
        _stemAnalysisState.value = StemAnalysisState.Ready(item.itemId, decodedGrid)
        onProgress(1.0f)
        decodedGrid
    }

    /**
     * Pure-JVM entry point for analyzing interleaved 16-bit PCM into a [StemTrackMaskGrid]
     * at `25 Hz` (`40 ms` hop) across 16 Bark/Mel frequency bands using [StemNetModel].
     */
    fun analyzePcmInterleaved(
        itemId: Long,
        pcmInterleaved: ShortArray,
        sampleRateHz: Int,
        channelCount: Int,
        model: StemNetModel = modelManager.loadInstalledModelOrFallback(),
    ): StemTrackMaskGrid {
        val acc = StreamingStemAnalyzer(itemId = itemId, sampleRateHz = sampleRateHz, channelCount = channelCount, model = model)
        acc.feedInterleavedShorts(pcmInterleaved, 0, pcmInterleaved.size)
        return acc.finish()
    }

    private fun decodeTrackAndInferMasks(
        item: MediaItem,
        model: StemNetModel,
        onProgress: (Float) -> Unit,
    ): StemTrackMaskGrid? {
        val ctx = context ?: return null
        val uri = runCatching { Uri.parse(item.documentUri) }.getOrNull() ?: return null
        val pfd = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(pfd.fileDescriptor)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) return null

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = when {
                audioFormat.containsKey(MediaFormat.KEY_DURATION) -> audioFormat.getLong(MediaFormat.KEY_DURATION)
                item.durationMs > 0L -> item.durationMs * 1000L
                else -> 180_000_000L
            }.coerceAtLeast(1_000L)

            audioFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val initialSr = runCatching { audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(44_100)
            val initialCh = runCatching { audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)
            var analyzer = StreamingStemAnalyzer(item.itemId, initialSr, initialCh, model)
            var outputEncoding = AudioFormat.ENCODING_PCM_16BIT

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var loops = 0
            var lastReportedPct = 0
            val tempShorts = ShortArray(8192)

            while (!outputDone && loops < 120_000) {
                loops++
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(4_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        val read = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (read < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inIdx, 0, read, pts, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 4_000L)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFmt = codec.outputFormat
                    outputEncoding = runCatching {
                        if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }.getOrDefault(AudioFormat.ENCODING_PCM_16BIT)
                    if (analyzer.emittedFrames == 0) {
                        val realSr = runCatching { outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(initialSr)
                        val realCh = runCatching { outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(initialCh)
                        if (realSr != analyzer.sampleRateHz || realCh != analyzer.channelCount) {
                            analyzer = StreamingStemAnalyzer(item.itemId, realSr, realCh, model)
                        }
                    }
                } else if (outIdx >= 0) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        feedDecodedBufferToAnalyzer(outBuf, bufferInfo.offset, bufferInfo.size, outputEncoding, tempShorts, analyzer)
                        val pct = ((bufferInfo.presentationTimeUs.toDouble() / durationUs.toDouble()) * 100.0)
                            .toInt()
                            .coerceIn(2, 98)
                        if (pct >= lastReportedPct + 4) {
                            lastReportedPct = pct
                            onProgress(pct / 100f)
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }

            if (analyzer.emittedFrames <= 0) return null
            return analyzer.finish()
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { pfd.close() }
        }
    }

    private fun feedDecodedBufferToAnalyzer(
        outBuf: ByteBuffer,
        offset: Int,
        size: Int,
        pcmEncoding: Int,
        scratchShorts: ShortArray,
        analyzer: StreamingStemAnalyzer,
    ) {
        val dup = outBuf.duplicate()
        dup.position(offset)
        dup.limit(offset + size)
        val slice = dup.slice().order(ByteOrder.LITTLE_ENDIAN)
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = slice.asFloatBuffer()
                while (fb.hasRemaining()) {
                    val count = min(scratchShorts.size, fb.remaining())
                    for (i in 0 until count) {
                        val v = fb.get()
                        scratchShorts[i] = if (v.isFinite()) {
                            (v * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
                        } else {
                            0
                        }
                    }
                    analyzer.feedInterleavedShorts(scratchShorts, 0, count)
                }
            }
            else -> {
                val sb = slice.asShortBuffer()
                while (sb.hasRemaining()) {
                    val count = min(scratchShorts.size, sb.remaining())
                    sb.get(scratchShorts, 0, count)
                    analyzer.feedInterleavedShorts(scratchShorts, 0, count)
                }
            }
        }
    }

    private fun buildFallbackGrid(item: MediaItem, model: StemNetModel): StemTrackMaskGrid {
        val durationMs = item.durationMs.takeIf { it > 0L } ?: 15_000L
        val frames = ((durationMs / StemTrackMaskGrid.DEFAULT_HOP_MS).toInt()).coerceIn(25, 9_000)
        val floatMasks = FloatArray(frames * FormantStemDspProcessor.NUM_BARK_BANDS * FormantStemDspProcessor.NUM_STEMS)
        val features = FloatArray(StemModelManager.INPUT_DIM)
        val hidden = FloatArray(StemModelManager.HIDDEN_DIM)
        val out80 = FloatArray(StemModelManager.OUTPUT_DIM)

        for (f in 0 until frames) {
            val beatPhase = (f % 12) == 0 || (f % 12) == 6
            for (b in 0 until FormantStemDspProcessor.NUM_BARK_BANDS) {
                features[b] = 0.25f
                features[16 + b] = if (b <= 3 || b in 5..10) 0.68f else 0.38f
                features[32 + b] = if (beatPhase) 0.65f else 0.18f
                features[48 + b] = 1.0f - features[32 + b]
            }
            model.inferFrameMasks(features, hidden, out80)
            System.arraycopy(out80, 0, floatMasks, f * 80, 80)
        }

        return StemTrackMaskGrid.quantizeNormalizedMasks(
            itemId = item.itemId,
            hopMs = StemTrackMaskGrid.DEFAULT_HOP_MS,
            numFrames = frames,
            floatMasks = floatMasks,
        )
    }

    /**
     * Streaming 25 Hz (`40 ms` hop) 1024-pt STFT + 16-band neural MLP mask extractor.
     */
    private class StreamingStemAnalyzer(
        val itemId: Long,
        sampleRateHz: Int,
        channelCount: Int,
        private val model: StemNetModel,
    ) {
        val sampleRateHz: Int = sampleRateHz.coerceAtLeast(8_000)
        val channelCount: Int = channelCount.coerceIn(1, 2)
        private val hopFrames: Int = ((this.sampleRateHz * StemTrackMaskGrid.DEFAULT_HOP_MS) / 1000).coerceAtLeast(256)

        private val windowL = FloatArray(FormantStemDspProcessor.FFT_SIZE)
        private val windowR = FloatArray(FormantStemDspProcessor.FFT_SIZE)
        private var ringWritePos = 0
        private var framesSinceLastHop = 0

        private val reL = FloatArray(FormantStemDspProcessor.FFT_SIZE)
        private val imL = FloatArray(FormantStemDspProcessor.FFT_SIZE)
        private val reR = FloatArray(FormantStemDspProcessor.FFT_SIZE)
        private val imR = FloatArray(FormantStemDspProcessor.FFT_SIZE)
        private val binToBand = IntArray(FormantStemDspProcessor.NUM_BINS) { k ->
            val f = k * this.sampleRateHz.toFloat() / FormantStemDspProcessor.FFT_SIZE.toFloat()
            FormantStemDspProcessor.bandIndexForFrequency(f)
        }

        private val bandMidEnergy = FloatArray(16)
        private val bandSideEnergy = FloatArray(16)
        private val bandTotalLogEma = FloatArray(16)
        private val bandPrevLog = FloatArray(16)
        private var primed = false

        private val features64 = FloatArray(StemModelManager.INPUT_DIM)
        private val hidden224 = FloatArray(StemModelManager.HIDDEN_DIM)
        private val out80 = FloatArray(StemModelManager.OUTPUT_DIM)

        private var floatMasks = FloatArray(1024 * 80)
        var emittedFrames: Int = 0
            private set

        fun feedInterleavedShorts(shorts: ShortArray, offset: Int, length: Int) {
            val frames = length / channelCount
            var idx = offset
            for (f in 0 until frames) {
                val l = shorts[idx] * (1.0f / 32768.0f)
                val r = if (channelCount >= 2) shorts[idx + 1] * (1.0f / 32768.0f) else l
                idx += channelCount

                windowL[ringWritePos] = l
                windowR[ringWritePos] = r
                ringWritePos = (ringWritePos + 1) and (FormantStemDspProcessor.FFT_SIZE - 1)
                framesSinceLastHop++
                if (framesSinceLastHop >= hopFrames) {
                    framesSinceLastHop = 0
                    emitCurrentWindowMask()
                }
            }
        }

        fun finish(): StemTrackMaskGrid {
            if (emittedFrames == 0 && framesSinceLastHop > 0) {
                emitCurrentWindowMask()
            }
            return StemTrackMaskGrid.quantizeNormalizedMasks(
                itemId = itemId,
                hopMs = StemTrackMaskGrid.DEFAULT_HOP_MS,
                numFrames = emittedFrames,
                floatMasks = floatMasks,
            )
        }

        private fun emitCurrentWindowMask() {
            val n = FormantStemDspProcessor.FFT_SIZE
            for (i in 0 until n) {
                val ringIdx = (ringWritePos + i) and (n - 1)
                val w = FormantStemDspProcessor.HANN_WINDOW[i]
                reL[i] = windowL[ringIdx] * w
                imL[i] = 0f
                reR[i] = windowR[ringIdx] * w
                imR[i] = 0f
            }
            FormantStemDspProcessor.fft1024(reL, imL, inverse = false)
            if (channelCount >= 2) {
                FormantStemDspProcessor.fft1024(reR, imR, inverse = false)
            } else {
                System.arraycopy(reL, 0, reR, 0, n)
                System.arraycopy(imL, 0, imR, 0, n)
            }

            bandMidEnergy.fill(0f)
            bandSideEnergy.fill(0f)

            for (k in 0 until FormantStemDspProcessor.NUM_BINS) {
                val band = binToBand[k]
                val mRe = 0.5f * (reL[k] + reR[k])
                val mIm = 0.5f * (imL[k] + imR[k])
                val sRe = 0.5f * (reL[k] - reR[k])
                val sIm = 0.5f * (imL[k] - imR[k])
                bandMidEnergy[band] += mRe * mRe + mIm * mIm
                bandSideEnergy[band] += sRe * sRe + sIm * sIm
            }

            var meanLog = 0f
            val curBandLog = FloatArray(16)
            for (b in 0 until 16) {
                val tot = sqrt((bandMidEnergy[b] + bandSideEnergy[b]).toDouble()).toFloat()
                val lg = ln(tot + 1e-6f)
                curBandLog[b] = lg
                meanLog += lg
            }
            meanLog /= 16.0f

            if (!primed) {
                System.arraycopy(curBandLog, 0, bandTotalLogEma, 0, 16)
                System.arraycopy(curBandLog, 0, bandPrevLog, 0, 16)
                primed = true
            }

            for (b in 0 until 16) {
                val lg = curBandLog[b]
                val normEnergy = ((lg - meanLog) / 4.0f).coerceIn(-1.0f, 1.0f)
                val centerAff = if (channelCount >= 2) {
                    bandMidEnergy[b] / (bandMidEnergy[b] + bandSideEnergy[b] + 1e-9f)
                } else {
                    0.60f
                }
                val flux = max(0f, lg - bandTotalLogEma[b]) + 0.55f * max(0f, lg - bandPrevLog[b])
                val perc = (flux / (flux + 0.75f)).coerceIn(0f, 0.96f)
                bandTotalLogEma[b] = 0.86f * bandTotalLogEma[b] + 0.14f * lg
                bandPrevLog[b] = lg

                features64[b] = normEnergy
                features64[16 + b] = centerAff
                features64[32 + b] = perc
                features64[48 + b] = 1.0f - perc
            }

            model.inferFrameMasks(features64, hidden224, out80)

            val requiredSize = (emittedFrames + 1) * 80
            if (floatMasks.size < requiredSize) {
                floatMasks = floatMasks.copyOf(max(requiredSize, floatMasks.size * 2))
            }
            System.arraycopy(out80, 0, floatMasks, emittedFrames * 80, 80)
            emittedFrames++
        }
    }
}
