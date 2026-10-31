package com.miniplayer.app.player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.miniplayer.app.core.model.MediaItem
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Generates and caches a 240-bar normalized RMS amplitude envelope (`cacheDir/waveforms/<id>.bin`)
 * using Android `MediaExtractor` + `MediaCodec`, with a deterministic non-blocking fallback preview
 * so playback and UI rendering never stall.
 */
class WaveformExtractor(private val context: Context) {
    private val decodeSemaphore = Semaphore(permits = 2)

    fun buildFallbackPreview(item: MediaItem): List<Float> {
        val seed = (item.fileName.hashCode() * 31 + item.durationMs.toInt())
        return List(BAR_COUNT) { idx ->
            val phase1 = sin(idx * 0.23 + (seed and 0xFF) * 0.05)
            val phase2 = sin(idx * 0.07 + ((seed shr 8) and 0xFF) * 0.09)
            val phase3 = sin(idx * 0.61 + ((seed shr 16) and 0xFF) * 0.03)
            val fade = when {
                idx < 8 -> (idx + 2) / 10f
                idx > BAR_COUNT - 10 -> (BAR_COUNT - idx + 2) / 12f
                else -> 1f
            }
            val raw = (0.46 + 0.24 * phase1 + 0.18 * phase2 + 0.12 * abs(phase3)).toFloat() * fade
            raw.coerceIn(MIN_BAR_HEIGHT, 0.95f)
        }
    }

    suspend fun loadOrExtract(item: MediaItem): List<Float> = withContext(Dispatchers.IO) {
        val cacheFile = cacheFileFor(item)
        readCachedBars(cacheFile)?.let { return@withContext it }

        val extracted = decodeSemaphore.withPermit {
            readCachedBars(cacheFile) ?: extractPcmRmsBars(item)?.also { bars ->
                writeCachedBars(cacheFile, bars)
            }
        }
        extracted ?: buildFallbackPreview(item)
    }

    suspend fun loadOrDetectBpm(item: MediaItem): Int? = withContext(Dispatchers.IO) {
        val cacheFile = bpmCacheFileFor(item)
        readCachedBpm(cacheFile)?.let { return@withContext it }

        decodeSemaphore.withPermit {
            readCachedBpm(cacheFile)?.let { return@withPermit it }
            val detected = extractEmbeddedBpm(item) ?: decodeAndDetectBpm(item)
            if (detected != null) {
                writeCachedBpm(cacheFile, detected)
            }
            detected
        }
    }

    private fun cacheFileFor(item: MediaItem): File {
        val dir = File(context.cacheDir, "waveforms")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "${item.itemId}_${item.lastModified}.bin")
    }

    private fun bpmCacheFileFor(item: MediaItem): File {
        val dir = File(context.cacheDir, "waveforms")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "bpm_${item.itemId}_${item.lastModified}.dat")
    }

    private fun readCachedBpm(file: File): Int? {
        if (!file.isFile || file.length() !in 1L..16L) return null
        return try {
            file.readText(Charsets.UTF_8).trim().toIntOrNull()?.takeIf { it in 30..300 }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeCachedBpm(file: File, bpm: Int) {
        if (bpm !in 30..300) return
        try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(bpm.toString(), Charsets.UTF_8)
            tmp.renameTo(file)
        } catch (_: Throwable) {
        }
    }

    private fun extractEmbeddedBpm(item: MediaItem): Int? {
        val uri = try {
            Uri.parse(item.documentUri)
        } catch (_: Throwable) {
            return null
        }

        // 1. Check ID3v2 TBPM / TBP and FLAC BPM / TEMPO header tags
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(64 * 1024)
                var totalRead = 0
                while (totalRead < header.size) {
                    val r = input.read(header, totalRead, header.size - totalRead)
                    if (r <= 0) break
                    totalRead += r
                }
                if (totalRead > 0) {
                    BpmDetector.extractEmbeddedBpmFromHeader(header, totalRead)?.let { return it }
                }
            }
        } catch (_: Throwable) {
        }

        // 2. Check MediaMetadataRetriever if the platform exposes an explicit METADATA_KEY_BPM constant
        val bpmKeyCode = VENDOR_METADATA_KEY_BPM
        if (bpmKeyCode != null) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val raw = retriever.extractMetadata(bpmKeyCode)
                BpmDetector.parseBpmTagString(raw)?.let { return it }
            } catch (_: Throwable) {
            } finally {
                try {
                    retriever.release()
                } catch (_: Throwable) {
                }
            }
        }
        return null
    }

    private fun decodeAndDetectBpm(item: MediaItem): Int? {
        val uri = try {
            Uri.parse(item.documentUri)
        } catch (_: Throwable) {
            return null
        }

        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Throwable) {
            null
        } ?: return null

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

            // Check container MediaFormat for embedded bpm/tempo metadata before decoding PCM
            for (key in listOf("bpm", "tempo", "tbpm")) {
                if (audioFormat.containsKey(key)) {
                    val strVal = runCatching { audioFormat.getString(key) }.getOrNull()
                    BpmDetector.parseBpmTagString(strVal)?.let { return it }
                    val intVal = runCatching { audioFormat.getInteger(key) }.getOrNull()
                    if (intVal != null && intVal in 30..300) return intVal
                }
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = when {
                audioFormat.containsKey(MediaFormat.KEY_DURATION) ->
                    audioFormat.getLong(MediaFormat.KEY_DURATION)
                item.durationMs > 0L -> item.durationMs * 1000L
                else -> 0L
            }

            if (durationUs > 30_000_000L) {
                val seekStartUs = minOf(durationUs / 4L, 15_000_000L)
                extractor.seekTo(seekStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            audioFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val initialSampleRate = runCatching { audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                .getOrDefault(44_100)
            val initialChannels = runCatching { audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                .getOrDefault(2)
            var accumulator = OnsetFluxAccumulator(initialSampleRate, initialChannels)
            var outputPcmEncoding = readPcmEncodingOrDefault(codec.outputFormat)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var safetyLoops = 0
            while (!outputDone && accumulator.accumulatedSeconds < BPM_DECODE_WINDOW_SECONDS && safetyLoops < 8_000) {
                safetyLoops++
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
                    outputPcmEncoding = readPcmEncodingOrDefault(outFmt)
                    if (accumulator.accumulatedHopCount == 0) {
                        val realSr = runCatching { outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                            .getOrDefault(initialSampleRate)
                        val realCh = runCatching { outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                            .getOrDefault(initialChannels)
                        if (realSr != accumulator.safeSampleRate || realCh != accumulator.safeChannelCount) {
                            accumulator = OnsetFluxAccumulator(realSr, realCh)
                        }
                    }
                } else if (outIdx >= 0) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        accumulator.feedPcmByteBuffer(
                            buffer = outBuf,
                            offset = bufferInfo.offset,
                            size = bufferInfo.size,
                            pcmEncoding = outputPcmEncoding,
                        )
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
            return accumulator.computeBpm()
        } catch (_: Throwable) {
            return null
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
            try {
                pfd.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun readCachedBars(file: File): List<Float>? {
        if (!file.isFile || file.length() != BAR_COUNT.toLong()) return null
        return try {
            val bytes = file.readBytes()
            if (bytes.size != BAR_COUNT) return null
            bytes.map { b ->
                val unsigned = b.toInt() and 0xFF
                (unsigned / 255f).coerceIn(MIN_BAR_HEIGHT, 1f)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeCachedBars(file: File, bars: List<Float>) {
        if (bars.size != BAR_COUNT) return
        try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            val bytes = ByteArray(BAR_COUNT) { idx ->
                (bars[idx].coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255).toByte()
            }
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
        } catch (_: Throwable) {
        }
    }

    private fun extractPcmRmsBars(item: MediaItem): List<Float>? {
        val uri = try {
            Uri.parse(item.documentUri)
        } catch (_: Throwable) {
            return null
        }

        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Throwable) {
            null
        } ?: return null

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
                audioFormat.containsKey(MediaFormat.KEY_DURATION) ->
                    audioFormat.getLong(MediaFormat.KEY_DURATION)
                item.durationMs > 0L -> item.durationMs * 1000L
                else -> 0L
            }
            if (durationUs <= 0L) return null

            audioFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()
            var outputPcmEncoding = readPcmEncodingOrDefault(codec.outputFormat)

            val bucketSumSq = DoubleArray(BAR_COUNT)
            val bucketSampleCount = IntArray(BAR_COUNT)
            val bufferInfo = MediaCodec.BufferInfo()

            // For long files (> 90s), sample evenly spaced windows via seekTo so extraction stays fast (< 400ms).
            val useWindowSampling = durationUs > 90_000_000L
            if (useWindowSampling) {
                val stepUs = durationUs / BAR_COUNT
                for (barIdx in 0 until BAR_COUNT) {
                    val targetUs = barIdx * stepUs
                    extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                    var collectedSamples = 0
                    var attempts = 0
                    while (collectedSamples < 1024 && attempts < 12) {
                        attempts++
                        val inIdx = codec.dequeueInputBuffer(4_000L)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            val read = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                            if (read < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            } else {
                                val pts = extractor.sampleTime.coerceAtLeast(0L)
                                codec.queueInputBuffer(inIdx, 0, read, pts, 0)
                                extractor.advance()
                            }
                        }
                        val outIdx = codec.dequeueOutputBuffer(bufferInfo, 4_000L)
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            outputPcmEncoding = readPcmEncodingOrDefault(codec.outputFormat)
                        } else if (outIdx >= 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null && bufferInfo.size > 0) {
                                collectedSamples += accumulatePcmRms(
                                    outBuf = outBuf,
                                    offset = bufferInfo.offset,
                                    size = bufferInfo.size,
                                    pcmEncoding = outputPcmEncoding,
                                    targetSamples = 256,
                                    barIdx = barIdx,
                                    bucketSumSq = bucketSumSq,
                                    bucketSampleCount = bucketSampleCount,
                                )
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                        }
                    }
                }
            } else {
                var inputDone = false
                var outputDone = false
                var safetyLoops = 0
                while (!outputDone && safetyLoops < 12_000) {
                    safetyLoops++
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
                        outputPcmEncoding = readPcmEncodingOrDefault(codec.outputFormat)
                    } else if (outIdx >= 0) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            val barIdx = ((bufferInfo.presentationTimeUs.toDouble() / durationUs.toDouble()) * BAR_COUNT)
                                .toInt()
                                .coerceIn(0, BAR_COUNT - 1)
                            accumulatePcmRms(
                                outBuf = outBuf,
                                offset = bufferInfo.offset,
                                size = bufferInfo.size,
                                pcmEncoding = outputPcmEncoding,
                                targetSamples = 128,
                                barIdx = barIdx,
                                bucketSumSq = bucketSumSq,
                                bucketSampleCount = bucketSampleCount,
                            )
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                }
            }

            val rawRms = FloatArray(BAR_COUNT) { idx ->
                val count = bucketSampleCount[idx]
                if (count > 0) sqrt(bucketSumSq[idx] / count).toFloat() else 0f
            }
            // Interpolate any sparse zero buckets from neighbors
            for (i in 0 until BAR_COUNT) {
                if (rawRms[i] <= 0f) {
                    val left = (i - 1 downTo 0).firstOrNull { rawRms[it] > 0f }?.let { rawRms[it] } ?: 0f
                    val right = (i + 1 until BAR_COUNT).firstOrNull { rawRms[it] > 0f }?.let { rawRms[it] } ?: left
                    rawRms[i] = if (left > 0f && right > 0f) (left + right) * 0.5f else maxOf(left, right)
                }
            }
            val peak = rawRms.maxOrNull()?.takeIf { it > 0.0001f } ?: return null
            return rawRms.map { v ->
                val scaled = sqrt((v / peak).toDouble()).toFloat()
                scaled.coerceIn(MIN_BAR_HEIGHT, 1f)
            }
        } catch (_: Throwable) {
            return null
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
            try {
                pfd.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun readPcmEncodingOrDefault(format: MediaFormat?): Int {
        return try {
            if (format != null && format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
        } catch (_: Throwable) {
            AudioFormat.ENCODING_PCM_16BIT
        }
    }

    companion object {
        const val BAR_COUNT = 240
        const val MIN_BAR_HEIGHT = 0.08f
        private const val BPM_DECODE_WINDOW_SECONDS = 20.0

        private val VENDOR_METADATA_KEY_BPM: Int? by lazy {
            runCatching {
                MediaMetadataRetriever::class.java.fields
                    .firstOrNull { it.name == "METADATA_KEY_BPM" }
                    ?.getInt(null)
            }.getOrNull()
        }

        internal fun accumulatePcmRms(
            outBuf: ByteBuffer,
            offset: Int,
            size: Int,
            pcmEncoding: Int,
            targetSamples: Int,
            barIdx: Int,
            bucketSumSq: DoubleArray,
            bucketSampleCount: IntArray,
        ): Int {
            outBuf.position(offset)
            outBuf.limit(offset + size)
            var collected = 0
            when (pcmEncoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    if (size < 4) return 0
                    val floatBuf = outBuf.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val stride = (floatBuf.remaining() / targetSamples).coerceAtLeast(1)
                    var pos = 0
                    while (pos < floatBuf.limit()) {
                        val raw = floatBuf.get(pos).toDouble()
                        val normalized = if (raw.isFinite()) raw.coerceIn(-1.0, 1.0) else 0.0
                        bucketSumSq[barIdx] += normalized * normalized
                        bucketSampleCount[barIdx]++
                        collected++
                        pos += stride
                    }
                }
                AudioFormat.ENCODING_PCM_8BIT -> {
                    if (size < 1) return 0
                    val slice = outBuf.slice()
                    val stride = (slice.remaining() / targetSamples).coerceAtLeast(1)
                    var pos = 0
                    while (pos < slice.limit()) {
                        val unsigned = slice.get(pos).toInt() and 0xFF
                        val normalized = (unsigned - 128) / 128.0
                        bucketSumSq[barIdx] += normalized * normalized
                        bucketSampleCount[barIdx]++
                        collected++
                        pos += stride
                    }
                }
                else -> {
                    if (size < 2) return 0
                    val shortBuf = outBuf.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val stride = (shortBuf.remaining() / targetSamples).coerceAtLeast(1)
                    var pos = 0
                    while (pos < shortBuf.limit()) {
                        val normalized = shortBuf.get(pos) / 32768.0
                        bucketSumSq[barIdx] += normalized * normalized
                        bucketSampleCount[barIdx]++
                        collected++
                        pos += stride
                    }
                }
            }
            return collected
        }
    }
}
