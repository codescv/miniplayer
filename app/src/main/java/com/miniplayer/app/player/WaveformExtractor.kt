package com.miniplayer.app.player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.miniplayer.app.core.model.BeatGrid
import com.miniplayer.app.core.model.BeatGridConfidence
import com.miniplayer.app.core.model.BeatGridSource
import com.miniplayer.app.core.model.MediaItem
import com.miniplayer.app.player.beat.StemBlockReader
import com.miniplayer.app.player.beat.TempoRegression
import java.io.File
import java.util.Locale
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

    suspend fun loadOrDetectBpm(item: MediaItem): Int? =
        loadOrDetectBeatGrid(item)?.bpm

    /**
     * BeatGrid v2 entry point. Order: `bpm_v4` cache (unless [ignoreCache], or the cached grid is
     * `AUTO_MIX`/`AUTO_LEGACY` and older than an existing `.stems` file) → `.stems` fast path
     * (`AUTO_STEMS`) → MediaCodec mix decode (`AUTO_MIX`).
     */
    suspend fun loadOrDetectBeatGrid(item: MediaItem, ignoreCache: Boolean = false): BeatGrid? = withContext(Dispatchers.IO) {
        val cacheFile = bpmCacheFileFor(item)
        val stemsFile = stemsFileFor(item)
        if (!ignoreCache) {
            readUsableCachedGrid(cacheFile, stemsFile)?.let { return@withContext it }
        }

        decodeSemaphore.withPermit {
            if (!ignoreCache) {
                readUsableCachedGrid(cacheFile, stemsFile)?.let { return@withPermit it }
            }
            val header = readHeaderBytes(item)
            val embeddedBpm = header?.let { BpmDetector.extractEmbeddedBpmDoubleFromHeader(it.first, it.second) }
                ?: extractRetrieverBpm(item)
            lastMp3GaplessInfo = header?.let { BpmDetector.parseMp3GaplessInfo(it.first, it.second) }
            val detected = detectFromStems(stemsFile, embeddedBpm)
                ?: decodeAndDetectBeatGrid(item, embeddedBpm = embeddedBpm)
                ?: embeddedBpm?.let {
                    BeatGrid(bpmPrecise = it, firstBeatMs = 0L, firstDownbeatMs = 0L, source = BeatGridSource.EMBEDDED_TAG)
                }
            if (detected != null) {
                writeCachedBeatGrid(cacheFile, detected)
            }
            detected
        }
    }

    /** Last parsed LAME/Xing gapless header (§3.7 calibration diagnostics; not applied blindly). */
    @Volatile
    var lastMp3GaplessInfo: BpmDetector.Mp3GaplessInfo? = null
        private set

    private fun readUsableCachedGrid(cacheFile: File, stemsFile: File): BeatGrid? {
        val cached = readCachedBeatGrid(cacheFile) ?: return null
        return if (isCacheStaleVsStems(cached.source, cacheFile.lastModified(), stemsFile.takeIf { it.isFile }?.lastModified())) {
            null
        } else {
            cached
        }
    }

    private fun detectFromStems(stemsFile: File, embeddedBpm: Double?): BeatGrid? {
        if (!stemsFile.isFile) return null
        return try {
            StemSeparatedTrackCache.openMapped(stemsFile)?.use { cache ->
                StemBlockReader(cache).analyze(maxSeconds = BPM_DECODE_WINDOW_SECONDS, embeddedBpm = embeddedBpm)
            }
        } catch (_: Throwable) {
            null
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
        return File(dir, "bpm_v4_${item.itemId}_${item.lastModified}.dat")
    }

    private fun stemsFileFor(item: MediaItem): File = StemSeparationEngine.stemCacheFile(context.cacheDir, item)

    private fun readCachedBeatGrid(file: File): BeatGrid? {
        if (!file.isFile || file.length() !in 1L..512L) return null
        return try {
            parseCachedBeatGrid(file.readText(Charsets.UTF_8))
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeCachedBeatGrid(file: File, grid: BeatGrid) {
        if (grid.bpm !in 30..300) return
        try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(formatCachedBeatGrid(grid), Charsets.UTF_8)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(formatCachedBeatGrid(grid), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: Throwable) {
        }
    }

    /** Reads the leading 64 KB (ID3v2 / FLAC tags, MP3 Xing/LAME header). */
    private fun readHeaderBytes(item: MediaItem): Pair<ByteArray, Int>? {
        val uri = try {
            Uri.parse(item.documentUri)
        } catch (_: Throwable) {
            return null
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(64 * 1024)
                var totalRead = 0
                while (totalRead < header.size) {
                    val r = input.read(header, totalRead, header.size - totalRead)
                    if (r <= 0) break
                    totalRead += r
                }
                if (totalRead > 0) header to totalRead else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractRetrieverBpm(item: MediaItem): Double? {
        val uri = try {
            Uri.parse(item.documentUri)
        } catch (_: Throwable) {
            return null
        }
        // MediaMetadataRetriever only if the platform exposes an explicit METADATA_KEY_BPM constant
        val bpmKeyCode = VENDOR_METADATA_KEY_BPM ?: return null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            return BpmDetector.parseBpmTagDouble(retriever.extractMetadata(bpmKeyCode))
        } catch (_: Throwable) {
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun decodeAndDetectBeatGrid(item: MediaItem, embeddedBpm: Double? = null): BeatGrid? {
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
        var knownBpm: Double? = embeddedBpm
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
            if (knownBpm == null) {
                for (key in listOf("bpm", "tempo", "tbpm")) {
                    if (audioFormat.containsKey(key)) {
                        val strVal = runCatching { audioFormat.getString(key) }.getOrNull()
                        knownBpm = BpmDetector.parseBpmTagDouble(strVal)
                        if (knownBpm != null) break
                        val intVal = runCatching { audioFormat.getInteger(key) }.getOrNull()
                        if (intVal != null && intVal in 30..300) {
                            knownBpm = intVal.toDouble()
                            break
                        }
                    }
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

            // Decode from 0:00 first so firstBeatMs and firstDownbeatMs reflect exact track start phase
            audioFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val initialSampleRate = runCatching { audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                .getOrDefault(44_100)
            val initialChannels = runCatching { audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                .getOrDefault(2)

            class DecodedWindow(val accumulator: OnsetFluxAccumulator, val offsetMs: Long)

            fun runWindowDecode(seekStartUs: Long, windowSeconds: Double, enableTonal: Boolean, extendToSeconds: Double?): DecodedWindow {
                if (seekStartUs > 0L) {
                    extractor.seekTo(seekStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                }
                val activeOutFmt = runCatching { codec.outputFormat }.getOrNull()
                val activeSr = runCatching { activeOutFmt?.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrNull()
                    ?: initialSampleRate
                val activeCh = runCatching { activeOutFmt?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrNull()
                    ?: initialChannels
                var accumulator = OnsetFluxAccumulator(activeSr, activeCh, enableTonal)
                var outputPcmEncoding = readPcmEncodingOrDefault(activeOutFmt)
                var firstFedPtsUs: Long? = null
                var targetSeconds = windowSeconds
                var extensionChecked = extendToSeconds == null

                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var safetyLoops = 0
                while (!outputDone && safetyLoops < 40_000) {
                    safetyLoops++
                    if (accumulator.accumulatedSeconds >= targetSeconds) {
                        if (extensionChecked) break
                        extensionChecked = true
                        // Window policy (§3.1): extend to 60 s only when the groove starts late.
                        if ((accumulator.activeGrooveStartSeconds() ?: 0.0) > LATE_GROOVE_START_SECONDS) {
                            targetSeconds = extendToSeconds ?: windowSeconds
                            continue
                        }
                        break
                    }
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(4_000L)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            val read = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                            if (read < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                // Keep negative (priming / edit-list) PTS so the output side can trim it.
                                codec.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
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
                                accumulator = OnsetFluxAccumulator(realSr, realCh, enableTonal)
                            }
                        }
                    } else if (outIdx >= 0) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            // RC8: drop PCM that belongs to presentationTimeUs < 0 instead of clamping.
                            val bytesPerFrame = bytesPerSample(outputPcmEncoding) * accumulator.safeChannelCount
                            val trimBytes = negativePtsTrimBytes(
                                presentationTimeUs = bufferInfo.presentationTimeUs,
                                sampleRate = accumulator.safeSampleRate,
                                bytesPerFrame = bytesPerFrame,
                                bufferSize = bufferInfo.size,
                            )
                            if (trimBytes < bufferInfo.size) {
                                if (firstFedPtsUs == null) {
                                    firstFedPtsUs = if (trimBytes > 0) 0L else bufferInfo.presentationTimeUs.coerceAtLeast(0L)
                                }
                                accumulator.feedPcmByteBuffer(
                                    buffer = outBuf,
                                    offset = bufferInfo.offset + trimBytes,
                                    size = bufferInfo.size - trimBytes,
                                    pcmEncoding = outputPcmEncoding,
                                )
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                }
                val offsetMs = ((firstFedPtsUs ?: seekStartUs.coerceAtLeast(0L)) / 1000L).coerceAtLeast(0L)
                return DecodedWindow(accumulator, offsetMs)
            }

            val startPass = runWindowDecode(0L, BPM_DECODE_WINDOW_SECONDS, enableTonal = true, extendToSeconds = BPM_EXTENDED_WINDOW_SECONDS)
            val analysis = startPass.accumulator.computeBeatGridDetailed(embeddedBpm = knownBpm, source = BeatGridSource.AUTO_MIX)
                ?: return knownBpm?.let { BeatGrid(bpmPrecise = it, firstBeatMs = 0L, firstDownbeatMs = 0L, source = BeatGridSource.EMBEDDED_TAG) }
            var grid = shiftGrid(analysis.grid, startPass.offsetMs)

            // Optional long-baseline refinement (§3.3): onset-only mid window at 50 %.
            val startFit = analysis.diagnostics.tempoFit
            if (durationUs > LONG_BASELINE_MIN_US && startFit != null) {
                runCatching {
                    val midSeekUs = durationUs / 2L - (LONG_BASELINE_WINDOW_SECONDS * 500_000.0).toLong()
                    val midPass = runWindowDecode(midSeekUs, LONG_BASELINE_WINDOW_SECONDS, enableTonal = false, extendToSeconds = null)
                    val midFit = midPass.accumulator.localTempoFit(grid.bpmPrecise)
                    if (midFit != null) {
                        val startBeat = startFit.beatTimeMs(Math.round((BPM_DECODE_WINDOW_SECONDS * 500.0 - startFit.phaseMs) / startFit.periodMs).toDouble()) +
                            startPass.offsetMs
                        val midLocalCenter = LONG_BASELINE_WINDOW_SECONDS * 500.0
                        val midBeat = midFit.beatTimeMs(Math.round((midLocalCenter - midFit.phaseMs) / midFit.periodMs).toDouble()) +
                            midPass.offsetMs
                        val merge = TempoRegression.mergeLongBaseline(
                            startFit = startFit,
                            startBeatMs = startBeat,
                            midFit = midFit,
                            midBeatMs = midBeat,
                            sampleAccurateSeek = isSampleAccurateContainer(mime, item),
                        )
                        grid = grid.copy(
                            bpmPrecise = (60_000.0 / merge.periodMs).coerceIn(30.0, 300.0),
                            confidence = grid.confidence.copy(tempoStable = grid.confidence.tempoStable && merge.tempoStable),
                        )
                    }
                }
            }
            return grid
        } catch (_: Throwable) {
            return knownBpm?.let { BeatGrid(bpmPrecise = it, firstBeatMs = 0L, firstDownbeatMs = 0L, source = BeatGridSource.EMBEDDED_TAG) }
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
        private const val BPM_DECODE_WINDOW_SECONDS = 45.0

        private const val BPM_EXTENDED_WINDOW_SECONDS = 60.0
        private const val LATE_GROOVE_START_SECONDS = 12.0
        private const val LONG_BASELINE_MIN_US = 90_000_000L
        private const val LONG_BASELINE_WINDOW_SECONDS = 14.0
        private const val CACHE_V4_TAG = "4"
        private const val FLAG_TEMPO_STABLE = 1
        private const val FLAG_HALF_BAR_AMBIGUOUS = 2
        private const val FLAG_METER_IRREGULAR = 4

        /**
         * Parses `bpm_v4` (`4,<bpmPrecise %.4f>,<firstBeatMs>,<firstDownbeatMs>,<bpb>,<phraseOffset>,
         * <clarity>,<margin>,<attackOff>,<source>,<flags>`) and, for robustness, the legacy
         * `"120"` / `"bpm,firstBeat,firstDownbeat,bpb"` formats (marked `AUTO_LEGACY`).
         */
        internal fun parseCachedBeatGrid(raw: String?): BeatGrid? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val parts = trimmed.split(',').map { it.trim() }
            if (parts.size >= 11 && parts[0] == CACHE_V4_TAG) {
                val bpm = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() && it in 30.0..300.0 } ?: return null
                val firstBeat = parts[2].toLongOrNull() ?: return null
                val firstDownbeat = parts[3].toLongOrNull() ?: return null
                val bpb = parts[4].toIntOrNull()?.coerceIn(2, 8) ?: 4
                val phraseOffset = parts[5].toIntOrNull()?.let { Math.floorMod(it, 4) } ?: 0
                val clarity = parts[6].toFloatOrNull() ?: 0f
                val margin = parts[7].toFloatOrNull() ?: 0f
                val attackOff = parts[8].toFloatOrNull() ?: 0f
                val source = BeatGridSource.fromNameOrNull(parts[9]) ?: BeatGridSource.AUTO_MIX
                val flags = parts[10].toIntOrNull() ?: FLAG_TEMPO_STABLE
                return BeatGrid(
                    bpmPrecise = bpm,
                    firstBeatMs = firstBeat,
                    firstDownbeatMs = firstDownbeat,
                    beatsPerBar = bpb,
                    phraseOffsetBars = phraseOffset,
                    confidence = BeatGridConfidence(
                        tempoClarity = clarity,
                        tempoStable = (flags and FLAG_TEMPO_STABLE) != 0,
                        downbeatMargin = margin,
                        halfBarAmbiguous = (flags and FLAG_HALF_BAR_AMBIGUOUS) != 0,
                        attackOffsetMs = attackOff,
                        meterIrregular = (flags and FLAG_METER_IRREGULAR) != 0,
                    ),
                    source = source,
                )
            }
            if (parts.size == 1) {
                val bpm = parts[0].toIntOrNull()?.takeIf { it in 30..300 } ?: return null
                return BeatGrid(bpmPrecise = bpm.toDouble(), firstBeatMs = 0L, firstDownbeatMs = 0L, source = BeatGridSource.AUTO_LEGACY)
            }
            val bpm = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 30..300 } ?: return null
            val firstBeatMs = parts.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val firstDownbeatMs = parts.getOrNull(2)?.toLongOrNull()?.coerceAtLeast(0L) ?: firstBeatMs
            val beatsPerBar = parts.getOrNull(3)?.toIntOrNull()?.coerceIn(2, 8) ?: 4
            return BeatGrid(
                bpmPrecise = bpm.toDouble(),
                firstBeatMs = firstBeatMs,
                firstDownbeatMs = firstDownbeatMs,
                beatsPerBar = beatsPerBar,
                source = BeatGridSource.AUTO_LEGACY,
            )
        }

        internal fun formatCachedBeatGrid(grid: BeatGrid): String {
            val c = grid.confidence
            var flags = 0
            if (c.tempoStable) flags = flags or FLAG_TEMPO_STABLE
            if (c.halfBarAmbiguous) flags = flags or FLAG_HALF_BAR_AMBIGUOUS
            if (c.meterIrregular) flags = flags or FLAG_METER_IRREGULAR
            return String.format(
                Locale.US,
                "%s,%.4f,%d,%d,%d,%d,%.3f,%.3f,%.2f,%s,%d",
                CACHE_V4_TAG,
                grid.bpmPrecise.coerceIn(30.0, 300.0),
                grid.firstBeatMs,
                grid.firstDownbeatMs,
                grid.beatsPerBar.coerceIn(2, 8),
                Math.floorMod(grid.phraseOffsetBars, 4),
                c.tempoClarity,
                c.downbeatMargin,
                c.attackOffsetMs,
                grid.source.name,
                flags,
            )
        }

        /**
         * §3.9: a cached `AUTO_MIX` / `AUTO_LEGACY` grid whose cache file predates an existing
         * `.stems` file is a cache miss (re-analysed as `AUTO_STEMS`). `MANUAL`, `AUTO_STEMS` and
         * `EMBEDDED_TAG` grids are kept.
         */
        internal fun isCacheStaleVsStems(cachedSource: BeatGridSource, cacheMtimeMs: Long, stemsMtimeMs: Long?): Boolean {
            if (stemsMtimeMs == null) return false
            if (cachedSource != BeatGridSource.AUTO_MIX && cachedSource != BeatGridSource.AUTO_LEGACY) return false
            return cacheMtimeMs <= stemsMtimeMs
        }

        /**
         * Bytes to drop from the head of a decoded buffer whose PTS is negative (encoder priming /
         * MP4 edit lists): `ceil(−pts · sr / 1e6)` frames, capped at [bufferSize].
         */
        internal fun negativePtsTrimBytes(presentationTimeUs: Long, sampleRate: Int, bytesPerFrame: Int, bufferSize: Int): Int {
            if (presentationTimeUs >= 0L || sampleRate <= 0 || bytesPerFrame <= 0) return 0
            val frames = Math.ceil(-presentationTimeUs * sampleRate / 1_000_000.0).toLong()
            return (frames * bytesPerFrame).coerceAtMost(bufferSize.toLong()).toInt()
        }

        internal fun bytesPerSample(pcmEncoding: Int): Int = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }

        /** Shifts a window-relative grid onto the absolute track timeline (no folding). */
        internal fun shiftGrid(grid: BeatGrid, offsetMs: Long): BeatGrid = if (offsetMs == 0L) {
            grid
        } else {
            grid.copy(firstBeatMs = grid.firstBeatMs + offsetMs, firstDownbeatMs = grid.firstDownbeatMs + offsetMs)
        }

        /** Containers whose `seekTo` lands sample-accurately (phase-lock allowed, §3.3). */
        internal fun isSampleAccurateContainer(mime: String, item: MediaItem): Boolean {
            val m = mime.lowercase(Locale.US)
            val name = item.fileName.lowercase(Locale.US)
            return m.contains("raw") || m.contains("wav") || m.contains("flac") ||
                name.endsWith(".wav") || name.endsWith(".flac") || name.endsWith(".m4a") || name.endsWith(".mp4") ||
                name.endsWith(".stems")
        }

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
