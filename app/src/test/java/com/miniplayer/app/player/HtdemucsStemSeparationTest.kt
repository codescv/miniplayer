package com.miniplayer.app.player

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.miniplayer.app.core.model.MediaItem
import com.miniplayer.app.core.model.PracticeMath
import com.miniplayer.app.core.model.StemMixConfig
import com.miniplayer.app.core.model.StemType
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class HtdemucsStemSeparationTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("htdemucs_unit_test_").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun htdemucsStftAndIspec_roundtripMatchesStereoWaveformWithin1e4_andHandlesAddWaveAndShortInputs() {
        // 1. Verify reflectPad1d on standard and short inputs (length <= max(padLeft, padRight))
        val shortSrc = FloatArray(600) { i -> (i + 1) * 0.001f }
        val shortPadded = HtdemucsStft.reflectPad1d(
            src = shortSrc,
            length = shortSrc.size,
            paddingLeft = HtdemucsStft.PAD_LEFT,
            paddingRight = HtdemucsStft.PAD_LEFT,
        )
        assertEquals(600 + 2 * HtdemucsStft.PAD_LEFT, shortPadded.size)
        for (v in shortPadded) {
            assertTrue("Reflected values must stay finite and bounded", v.isFinite() && abs(v) <= 0.601f)
        }

        // 2. Full 7.8s segment (L = 343,980 @ 44.1 kHz, T = 336 frames, F = 2048 CAC bins) STFT -> iSTFT roundtrip
        val segLen = HtdemucsStft.SEGMENT_SAMPLES
        val left = FloatArray(segLen)
        val right = FloatArray(segLen)
        val sr = HtdemucsStft.SAMPLE_RATE.toDouble()
        for (i in 0 until segLen) {
            val t = i.toDouble() / sr
            val env = 0.55 + 0.35 * sin(2.0 * PI * 1.7 * t)
            left[i] = (env * (
                0.45 * sin(2.0 * PI * 110.0 * t) +
                    0.30 * sin(2.0 * PI * 440.0 * t + 0.3) +
                    0.20 * cos(2.0 * PI * 2_450.0 * t) +
                    0.10 * sin(2.0 * PI * 9_200.0 * t)
                )).toFloat()
            right[i] = (env * (
                0.40 * cos(2.0 * PI * 165.0 * t) +
                    0.35 * sin(2.0 * PI * 660.0 * t - 0.4) +
                    0.18 * sin(2.0 * PI * 3_800.0 * t + 0.7) +
                    0.12 * cos(2.0 * PI * 11_000.0 * t)
                )).toFloat()
        }

        val scratch = HtdemucsStft.ScratchBuffers()
        val specCac = HtdemucsStft.specCacStereo(
            left = left,
            right = right,
            length = segLen,
            scratch = scratch,
        )
        assertEquals(4 * HtdemucsStft.FREQ_BINS * HtdemucsStft.SEGMENT_FRAMES, specCac.size)

        val recLeft = FloatArray(segLen)
        val recRight = FloatArray(segLen)
        HtdemucsStft.ispecCacStereoStem(
            specCac = specCac,
            stemSpecOffset = 0,
            length = segLen,
            outLeft = recLeft,
            outRight = recRight,
            scratch = scratch,
        )

        // PyTorch HTDemucs._ispec applies F.pad(z, (2, 2)), so interior [PAD_LEFT .. segLen - PAD_LEFT)
        // achieves exact COLA reconstruction (< 1e-5 max error, ~1e-6 typical), while outer 1536 samples
        // smoothly taper by the exact 2-zero-frame Hann WOLA ratio.
        var interiorMaxErr = 0f
        for (i in HtdemucsStft.PAD_LEFT until (segLen - HtdemucsStft.PAD_LEFT)) {
            interiorMaxErr = max(interiorMaxErr, abs(left[i] - recLeft[i]))
            interiorMaxErr = max(interiorMaxErr, abs(right[i] - recRight[i]))
        }
        assertTrue(
            "Expected exact Demucs 4096-pt STFT -> iSTFT interior max error < 1e-4, got $interiorMaxErr",
            interiorMaxErr < 1e-4f,
        )

        // 3. Verify stemSpecOffset + time-branch out_wave addition in ispecCacStereoStem across all samples
        val strideSpec = specCac.size
        val twoStemSpec = FloatArray(strideSpec * 2)
        System.arraycopy(specCac, 0, twoStemSpec, strideSpec, strideSpec)
        val waveAdd = FloatArray(4 * segLen)
        val stem1WaveOffset = 2 * segLen
        for (i in 0 until segLen) {
            waveAdd[stem1WaveOffset + i] = 0.125f
            waveAdd[stem1WaveOffset + segLen + i] = -0.25f
        }
        val combinedL = FloatArray(segLen)
        val combinedR = FloatArray(segLen)
        HtdemucsStft.ispecCacStereoStem(
            specCac = twoStemSpec,
            stemSpecOffset = strideSpec,
            length = segLen,
            outLeft = combinedL,
            outRight = combinedR,
            addWaveBuffer = waveAdd,
            addWaveStemOffset = stem1WaveOffset,
            scratch = scratch,
        )
        for (i in 0 until segLen step 257) {
            assertEquals(recLeft[i] + 0.125f, combinedL[i], 1e-5f)
            assertEquals(recRight[i] - 0.25f, combinedR[i], 1e-5f)
        }

        // 4. Verify triangular transition weights & centered sub-chunk window math
        val tri = HtdemucsStft.buildTriangularSegmentWeight(segLen)
        assertEquals(segLen, tri.size)
        assertTrue(tri.first() > 0f && tri.last() > 0f)
        assertEquals(1.0f, tri[segLen / 2 - 1], 1e-6f)
        assertEquals(tri[0], tri[segLen - 1], 1e-6f)

        val tailWin = HtdemucsStft.computeCenteredChunkWindow(
            totalFrames = 500_000,
            offset = 257_985,
            chunkLength = 242_015,
            targetLength = segLen,
        )
        assertEquals(segLen, tailWin.padLeftFrames + (tailWin.sourceEndFrame - tailWin.sourceStartFrame) + tailWin.padRightFrames)
        assertEquals((segLen - 242_015) / 2, tailWin.centerTrimOffset)
    }

    @Test
    fun stemSeparatedTrackCache_bfp8MappedFileExceeds50dBSnr_andSupportsInstantRandomFrameAccess() {
        val totalFrames = 44_100 * 2 + 37 // Non-multiple of 64 to exercise partial tail block
        val sr = HtdemucsStft.SAMPLE_RATE.toDouble()
        val freqs = doubleArrayOf(440.0, 330.0, 82.4, 185.0, 784.0)

        val stems = Array(5) { s ->
            val l = FloatArray(totalFrames)
            val r = FloatArray(totalFrames)
            val f0 = freqs[s]
            for (i in 0 until totalFrames) {
                // Include a brief silent block in stem 4 to verify zero-scale block handling
                if (s == 4 && i in 640 until 832) {
                    l[i] = 0f
                    r[i] = 0f
                } else {
                    val t = i.toDouble() / sr
                    val env = 0.25 + 0.65 * (0.5 + 0.5 * sin(2.0 * PI * (1.2 + s * 0.4) * t))
                    l[i] = (env * (0.7 * sin(2.0 * PI * f0 * t) + 0.3 * sin(2.0 * PI * f0 * 2.0 * t + 0.5))).toFloat()
                    r[i] = (env * (0.65 * cos(2.0 * PI * f0 * t - 0.3) + 0.35 * sin(2.0 * PI * f0 * 1.5 * t))).toFloat()
                }
            }
            arrayOf(l, r)
        }

        val stemsFile = File(tempDir, "htdemucs_42_1700000000.stems")
        StemSeparatedTrackCache.writeFromFloatStems(
            file = stemsFile,
            itemId = 42L,
            sampleRate = HtdemucsStft.SAMPLE_RATE,
            stems5Stereo = stems,
        ).close()

        val expectedBlocks = (totalFrames + StemSeparatedTrackCache.BLOCK_FRAMES - 1) / StemSeparatedTrackCache.BLOCK_FRAMES
        val expectedBytes = StemSeparatedTrackCache.HEADER_BYTES.toLong() +
            expectedBlocks.toLong() * StemSeparatedTrackCache.BLOCK_BYTES.toLong()
        assertEquals(expectedBytes, stemsFile.length())

        StemSeparatedTrackCache.openMapped(stemsFile).use { mapped ->
            assertNotNull("Expected openMapped to succeed on valid MPST file", mapped)
            mapped!!
            assertEquals(42L, mapped.itemId)
            assertEquals(HtdemucsStft.SAMPLE_RATE, mapped.sampleRate)
            assertEquals(totalFrames, mapped.totalFrames)

            val out10 = FloatArray(10)
            // Out-of-bounds random access returns clean zeros without throwing
            mapped.readFrameStems(-5, out10)
            assertTrue(out10.all { it == 0f })
            mapped.readFrameStems(totalFrames + 10, out10)
            assertTrue(out10.all { it == 0f })

            // Verify > 50 dB SNR on every single stereo stem across the entire mapped file
            val sigEnergy = DoubleArray(5)
            val errEnergy = DoubleArray(5)

            // Probe in pseudo-random / reverse jump order first to verify stateless O(1) random access
            for (probeIdx in listOf(totalFrames - 1, 0, totalFrames / 2, 700, 12_345)) {
                mapped.readFrameStems(probeIdx, out10)
                for (s in 0 until 5) {
                    assertEquals(stems[s][0][probeIdx], out10[s * 2], 0.02f)
                    assertEquals(stems[s][1][probeIdx], out10[s * 2 + 1], 0.02f)
                }
            }

            for (i in 0 until totalFrames) {
                mapped.readFrameStems(i, out10)
                for (s in 0 until 5) {
                    val origL = stems[s][0][i].toDouble()
                    val origR = stems[s][1][i].toDouble()
                    val errL = origL - out10[s * 2].toDouble()
                    val errR = origR - out10[s * 2 + 1].toDouble()
                    sigEnergy[s] += origL * origL + origR * origR
                    errEnergy[s] += errL * errL + errR * errR
                }
            }

            for (s in 0 until 5) {
                val snrDb = 10.0 * log10(sigEnergy[s] / errEnergy[s].coerceAtLeast(1e-18))
                assertTrue(
                    "Expected BFP8 stem #$s SNR > 50.0 dB, got ${String.format("%.2f", snrDb)} dB",
                    snrDb > 50.0,
                )
            }
        }

        // Corrupt / truncated files must return null cleanly
        val badFile = File(tempDir, "corrupt.stems").apply { writeBytes(ByteArray(64) { 0x42 }) }
        assertNull(StemSeparatedTrackCache.openMapped(badFile))
    }

    @Test
    fun separateMappedPcmWithRunner_multiSegmentOverlapAddAndStreamingBfp8_reconstructsSmoothStems() = runBlocking {
        // 620,000 frames (~14.06 s @ 44.1 kHz) spans 3 overlapping 343,980-sample segments (stride = 257,985)
        val totalFrames = 620_000
        val pcmBytes = ByteBuffer.allocateDirect(totalFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
        val sr = HtdemucsStft.SAMPLE_RATE.toDouble()

        val origL = FloatArray(totalFrames)
        val origR = FloatArray(totalFrames)
        for (i in 0 until totalFrames) {
            val t = i.toDouble() / sr
            val l = (0.45 * sin(2.0 * PI * 220.0 * t) + 0.25 * cos(2.0 * PI * 880.0 * t)).toFloat()
            val r = (0.40 * cos(2.0 * PI * 330.0 * t) + 0.28 * sin(2.0 * PI * 1_320.0 * t)).toFloat()
            val sL = (l * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
            val sR = (r * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
            pcmBytes.putShort(sL)
            pcmBytes.putShort(sR)
            origL[i] = sL / 32768f
            origR[i] = sR / 32768f
        }
        pcmBytes.flip()

        // Deterministic 5-stem partition weights summing to 1.0: [0.35, 0.25, 0.18, 0.12, 0.10]
        val stemShares = floatArrayOf(0.35f, 0.25f, 0.18f, 0.12f, 0.10f)
        val engine = StemSeparationEngine(
            context = null,
            modelManager = StemModelManager(filesRootDir = tempDir),
            cacheRootDir = tempDir,
        )
        val outStemsFile = File(tempDir, "stems/multi_seg_test.stems")
        val reportedSegments = mutableListOf<Pair<Int, Int>>()
        var lastProgress = -1f

        val specCount = 4 * HtdemucsStft.FREQ_BINS * HtdemucsStft.SEGMENT_FRAMES
        val waveCount = 2 * HtdemucsStft.SEGMENT_SAMPLES
        val specTemp = FloatArray(specCount)
        val waveTemp = FloatArray(waveCount)

        engine.separateMappedPcmWithRunner(
            pcmStereo16Le = pcmBytes,
            totalFrames = totalFrames,
            itemId = 99L,
            outputStemsFile = outStemsFile,
            segmentRunner = { xtDirect, xDirect, outSpecFlat, outWaveFlat ->
                // Split 50% via spectral branch (out_spec) and 50% via waveform branch (out_wave) per stem share
                xDirect.rewind()
                xDirect.get(specTemp)
                xtDirect.rewind()
                xtDirect.get(waveTemp)

                for (s in 0 until 5) {
                    val halfShare = 0.5f * stemShares[s]
                    val sSpecBase = s * specCount
                    for (k in 0 until specCount) {
                        outSpecFlat[sSpecBase + k] = specTemp[k] * halfShare
                    }
                    val sWaveBase = s * waveCount
                    for (k in 0 until waveCount) {
                        outWaveFlat[sWaveBase + k] = waveTemp[k] * halfShare
                    }
                }
            },
            onSegmentProgress = { cur, total, prog ->
                reportedSegments += (cur to total)
                assertTrue("Progress must be monotonic ($prog >= $lastProgress)", prog >= lastProgress - 1e-6f)
                lastProgress = prog
            },
        )

        assertEquals(3, reportedSegments.last().second)
        assertEquals(3, reportedSegments.last().first)
        assertEquals(1.0f, lastProgress, 1e-5f)

        StemSeparatedTrackCache.openMapped(outStemsFile).use { cache ->
            assertNotNull(cache)
            cache!!
            assertEquals(totalFrames, cache.totalFrames)
            val stemLR = FloatArray(10)
            var sumSig = 0.0
            var sumErr = 0.0
            // Check across the 14s multi-segment span (including overlap boundaries at 257,985, 343,980, 515,970)
            for (i in HtdemucsStft.PAD_LEFT until (totalFrames - HtdemucsStft.PAD_LEFT) step 13) {
                cache.readFrameStems(i, stemLR)
                for (s in 0 until 5) {
                    val expL = origL[i] * stemShares[s]
                    val expR = origR[i] * stemShares[s]
                    val dl = (expL - stemLR[s * 2]).toDouble()
                    val dr = (expR - stemLR[s * 2 + 1]).toDouble()
                    sumSig += (expL * expL + expR * expR).toDouble()
                    sumErr += dl * dl + dr * dr
                }
            }
            val snrDb = 10.0 * log10(sumSig / sumErr.coerceAtLeast(1e-18))
            assertTrue("Multi-segment triangular overlap-add + streaming BFP10 SNR must exceed 50 dB, got $snrDb dB", snrDb > 50.0)

            // Explicitly verify seamless sample-by-sample continuity across segment overlap boundaries
            for (boundary in intArrayOf(HtdemucsStft.DEFAULT_STRIDE, HtdemucsStft.SEGMENT_SAMPLES, HtdemucsStft.DEFAULT_STRIDE * 2)) {
                for (i in (boundary - 32)..(boundary + 32)) {
                    cache.readFrameStems(i, stemLR)
                    for (s in 0 until 5) {
                        assertEquals(origL[i] * stemShares[s], stemLR[s * 2], 0.005f)
                        assertEquals(origR[i] * stemShares[s], stemLR[s * 2 + 1], 0.005f)
                    }
                }
            }
        }

        // Also verify StreamingStereoResampler44k1 from 48,000 Hz -> 44,100 Hz
        val baos = ByteArrayOutputStream()
        val resampler = StemSeparationEngine.StreamingStereoResampler44k1(48_000, baos)
        val in48kFrames = 48_000 // 1.000 second
        val in48kFloats = FloatArray(in48kFrames * 2) { idx ->
            val f = idx / 2
            val ch = idx and 1
            val freq = if (ch == 0) 440.0 else 880.0
            (0.6 * sin(2.0 * PI * freq * f / 48_000.0)).toFloat()
        }
        resampler.feedInterleavedFloats(in48kFloats, in48kFloats.size, channels = 2)
        resampler.flush()
        assertTrue(
            "Expected ~44,100 resampled frames from 1s of 48kHz audio, got ${resampler.emittedFrames}",
            abs(resampler.emittedFrames - 44_100) <= 2,
        )
    }

    @Test
    fun stemModelManager_strictOnnxValidationNoFakeFallback_redirectProgressAndCancellation() = runBlocking {
        val manager = StemModelManager(context = null, filesRootDir = tempDir)
        val modelsDir = File(tempDir, "models").apply { mkdirs() }

        // 1. Missing model -> strictly false (NO fake bundled fallback!)
        assertFalse(manager.isModelReady())

        // 2. Legacy v1.0.9 64KB heuristic file -> strictly false
        val legacyBin = File(modelsDir, "miniplayer-stem-net-v1.bin").apply {
            writeBytes(ByteArray(65_536) { 0x08 })
        }
        assertFalse(manager.isModelReady())

        // 3. Truncated < 20 MB ONNX file -> strictly false
        val modelFile = File(modelsDir, StemModelManager.MODEL_FILE_NAME)
        modelFile.writeBytes(ByteArray(512_000) { idx -> if (idx == 0) 0x08 else 0x09 })
        assertFalse(manager.isModelReady())

        // 4. > 20 MB HTML gateway error page -> strictly false
        RandomAccessFile(modelFile, "rw").use { raf ->
            raf.setLength(StemModelManager.MIN_VALID_MODEL_BYTES + 100_000L)
            raf.seek(0L)
            raf.write("<!DOCTYPE html><html>Error</html>".toByteArray())
        }
        assertFalse(manager.isModelReady())
        modelFile.delete()

        // 5. StemSeparationEngine refuses to fake separation when model is missing
        val dummyItem = MediaItem(
            itemId = 7L,
            documentUri = "file:///nonexistent.mp3",
            rootId = 1L,
            parentRelPath = "",
            fileName = "song.mp3",
            mimeType = "audio/mpeg",
            isVideo = false,
            sizeBytes = 1000L,
            lastModified = 123L,
            durationMs = 10_000L,
            title = "Song",
            artist = "Artist",
        )
        val engine = StemSeparationEngine(context = null, modelManager = manager, cacheRootDir = tempDir)
        assertEquals(StemAnalysisState.ModelNotInstalled, engine.stemAnalysisState.value)
        assertNull(engine.analyzeTrackStems(dummyItem))
        assertEquals(StemAnalysisState.ModelNotInstalled, engine.stemAnalysisState.value)

        // 6. Real loopback HTTP download with primary 503 -> fallback 302 redirect -> 20.5 MB valid ONNX stream
        val validPayloadSize = (StemModelManager.MIN_VALID_MODEL_BYTES + 500_000L).toInt()
        StreamingModelMockServer(validPayloadSize).use { server ->
            val progressEvents = mutableListOf<Float>()
            val downloaded = manager.downloadModel(
                candidateUrls = listOf("${server.baseUrl}/primary-503", "${server.baseUrl}/release-redirect"),
                onProgress = { p -> synchronized(progressEvents) { progressEvents += p } },
            )
            assertEquals(modelFile.absolutePath, downloaded.absolutePath)
            assertTrue(manager.isModelReady())
            assertFalse("Legacy v1.0.9 bin file must be cleaned up", legacyBin.exists())
            assertFalse("Temporary .tmp file must not linger after commit", File(modelsDir, "${StemModelManager.MODEL_FILE_NAME}.tmp").exists())
            assertTrue("Expected real byte-level progress callbacks", progressEvents.size >= 5)
            assertEquals(1.0f, progressEvents.last(), 1e-5f)

            // 7. Cancellation mid-download cleans up .tmp and keeps isModelReady() == false
            modelFile.delete()
            assertFalse(manager.isModelReady())
            server.stallAfterHalfLatch = CountDownLatch(1)
            val halfReached = CountDownLatch(1)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val job = scope.launch {
                manager.downloadModel(
                    candidateUrls = listOf("${server.baseUrl}/stalled-stream"),
                    onProgress = { p ->
                        if (p >= 0.35f) halfReached.countDown()
                    },
                )
            }
            assertTrue("Download should reach 35% before cancellation", halfReached.await(5, TimeUnit.SECONDS))
            job.cancel(CancellationException("User tapped cancel"))
            server.stallAfterHalfLatch?.countDown()
            job.join()
            scope.cancel()

            assertFalse("Cancelled download must not leave valid model", manager.isModelReady())
            assertFalse(
                "Cancelled download must clean up .tmp file",
                File(modelsDir, "${StemModelManager.MODEL_FILE_NAME}.tmp").exists(),
            )
        }
    }

    private class StreamingModelMockServer(private val payloadSize: Int) : AutoCloseable {
        private val serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val pool = Executors.newCachedThreadPool()

        @Volatile
        var stallAfterHalfLatch: CountDownLatch? = null

        val baseUrl: String
            get() = "http://127.0.0.1:${serverSocket.localPort}"

        init {
            pool.execute {
                while (!serverSocket.isClosed) {
                    val sock = runCatching { serverSocket.accept() }.getOrNull() ?: break
                    pool.execute { handle(sock) }
                }
            }
        }

        private fun handle(socket: Socket) {
            runCatching {
                socket.use { s ->
                    s.soTimeout = 10_000
                    val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val reqLine = reader.readLine() ?: return
                    val path = reqLine.split(' ').getOrElse(1) { "/" }
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val out = s.getOutputStream()
                    when (path) {
                        "/primary-503" -> {
                            out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                        "/release-redirect" -> {
                            val hdr = "HTTP/1.1 302 Found\r\nLocation: $baseUrl/cdn/htdemucs_6s.onnx\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            out.write(hdr.toByteArray())
                        }
                        "/cdn/htdemucs_6s.onnx", "/stalled-stream" -> {
                            val hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: $payloadSize\r\nConnection: close\r\n\r\n"
                            out.write(hdr.toByteArray())
                            val chunk = ByteArray(65_536) { 0x2A }
                            var sent = 0
                            while (sent < payloadSize) {
                                val n = minOf(chunk.size, payloadSize - sent)
                                if (sent == 0) {
                                    // Valid ONNX ModelProto header: tag 0x08 (field 1 varint), ir_version = 8
                                    chunk[0] = 0x08
                                    chunk[1] = 0x08
                                } else {
                                    chunk[0] = 0x2A
                                    chunk[1] = 0x2A
                                }
                                out.write(chunk, 0, n)
                                sent += n
                                if (path == "/stalled-stream" && sent >= payloadSize / 2) {
                                    out.flush()
                                    stallAfterHalfLatch?.await(5, TimeUnit.SECONDS)
                                }
                            }
                        }
                        else -> {
                            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                    }
                    out.flush()
                }
            }
        }

        override fun close() {
            stallAfterHalfLatch?.countDown()
            runCatching { serverSocket.close() }
            pool.shutdownNow()
        }
    }

    @Test
    fun realHtdemucs6sOnnxEndToEnd_separatesFull343980ChunkInto5FiniteNonZeroStems_andMixesViaBfp10AndSonic() {
        val onnxPath = System.getenv("HTDEMUCS_ONNX_PATH")?.takeIf { it.isNotBlank() }
            ?: "/tmp/htdemucs-export/htdemucs_6s.onnx"
        val onnxFile = File(onnxPath)
        assumeTrue("Real HTDemucs ONNX model not present at $onnxPath", onnxFile.isFile)

        // 1. Verify canonical exported model file size, SHA-256, and strict ONNX ModelProto header check
        assertEquals(110_242_365L, onnxFile.length())
        assertEquals(
            "950b278ae3c2520b9b6e9a8ed8b25615db9cd82552f8fded6a5af0280a4016a2",
            sha256Hex(onnxFile),
        )
        assertTrue(StemModelManager.validateOnnxModelFile(onnxFile))

        // 2. Synthesize a full 343,980-sample (7.8s @ 44,100 Hz) stereo multi-instrument test mixture
        val segLen = HtdemucsStft.SEGMENT_SAMPLES
        val sr = HtdemucsStft.SAMPLE_RATE.toDouble()
        val mixL = FloatArray(segLen)
        val mixR = FloatArray(segLen)
        val mixPcmShorts = ShortArray(segLen * 2)
        var rng = 0x5EED_10010L
        fun nextUniformSigned(): Double {
            rng = rng * 6364136223846793005L + 1442695040888963407L
            return ((rng ushr 40).toInt() / 8388608.0) - 1.0
        }
        fun nextGaussianApprox(): Double =
            (nextUniformSigned() + nextUniformSigned() + nextUniformSigned() + nextUniformSigned()) * 0.866

        // Karplus-Strong plucked guitar strings (E3=164.81 Hz, B3=246.94 Hz, E4=329.63 Hz strummed every 0.50s)
        val ksPeriods = intArrayOf(268, 179, 134)
        val ksLines = Array(ksPeriods.size) { pIdx -> DoubleArray(ksPeriods[pIdx]) }
        val ksPos = IntArray(ksPeriods.size)
        val strumIntervalSamples = (0.50 * sr).roundToInt()

        for (i in 0 until segLen) {
            val t = i.toDouble() / sr
            val posInStrum = i % strumIntervalSamples
            var guitar = 0.0
            for (sIdx in ksPeriods.indices) {
                val strumOffset = sIdx * 650 // ~14.7 ms acoustic strum stagger across strings
                val line = ksLines[sIdx]
                val p = ksPos[sIdx]
                val nextP = if (p + 1 == line.size) 0 else p + 1
                if (posInStrum >= strumOffset && posInStrum < strumOffset + line.size) {
                    line[p] = nextUniformSigned() * 0.65
                } else {
                    line[p] = 0.9945 * 0.5 * (line[p] + line[nextP])
                }
                ksPos[sIdx] = nextP
                guitar += line[p]
            }
            guitar = 0.28 * tanh(1.6 * guitar)

            // Vocals-like vibrato formant lead (center)
            val vibPhase = 2.0 * PI * 440.0 * t + 1.8 * sin(2.0 * PI * 5.5 * t)
            val vocalEnv = 0.65 + 0.35 * sin(2.0 * PI * 1.4 * t)
            val vocals = 0.22 * vocalEnv * (
                0.65 * sin(vibPhase) + 0.25 * sin(2.0 * vibPhase) + 0.10 * cos(3.0 * vibPhase)
                )
            // Bass-like warm low fundamental (center)
            val bassEnv = exp(-2.2 * (t % 0.50))
            val bass = 0.26 * bassEnv * (0.80 * sin(2.0 * PI * 82.41 * t) + 0.20 * sin(2.0 * PI * 164.81 * t))
            // Drums-like percussive kick + snare broadband transients every 0.50s
            val beatMod = t % 0.50
            val drumTransient = exp(-32.0 * beatMod)
            val kick = 0.28 * exp(-18.0 * beatMod) * sin(2.0 * PI * (110.0 * exp(-12.0 * beatMod) + 52.0) * beatMod)
            val drums = kick + 0.22 * drumTransient * nextGaussianApprox()
            // Piano/Other-like high bell arpeggio every 0.60s (panned R)
            val bellEnv = exp(-6.0 * (t % 0.60))
            val other = 0.16 * bellEnv * (
                0.70 * sin(2.0 * PI * 1_046.50 * t) + 0.30 * cos(2.0 * PI * 1_567.98 * t)
                )

            val l = (vocals + 0.80 * guitar + bass + drums + 0.35 * other).coerceIn(-0.98, 0.98).toFloat()
            val r = (vocals + 0.35 * guitar + bass + drums + 0.80 * other).coerceIn(-0.98, 0.98).toFloat()
            mixL[i] = l
            mixR[i] = r
            mixPcmShorts[i * 2] = (l * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
            mixPcmShorts[i * 2 + 1] = (r * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }

        // 3. Stage A: HtdemucsStft.specCacStereo -> OrtSession.run -> HtdemucsStft.ispecCacStereoStem + out_wave
        val stftScratch = HtdemucsStft.ScratchBuffers()
        val specInFlat = HtdemucsStft.specCacStereo(
            left = mixL,
            right = mixR,
            length = segLen,
            scratch = stftScratch,
        )
        assertEquals(4 * HtdemucsStft.FREQ_BINS * HtdemucsStft.SEGMENT_FRAMES, specInFlat.size)
        assertTrue("Input CAC STFT must be completely finite", specInFlat.all { it.isFinite() })

        val xtDirect = ByteBuffer.allocateDirect(2 * segLen * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        xtDirect.put(mixL)
        xtDirect.put(mixR)
        xtDirect.rewind()

        val xDirect = ByteBuffer.allocateDirect(specInFlat.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        xDirect.put(specInFlat)
        xDirect.rewind()

        val specStridePerStem = 4 * HtdemucsStft.FREQ_BINS * HtdemucsStft.SEGMENT_FRAMES
        val waveStridePerStem = 2 * segLen
        val outSpecFlat = FloatArray(HtdemucsStft.NUM_APP_STEMS * specStridePerStem)
        val outWaveFlat = FloatArray(HtdemucsStft.NUM_APP_STEMS * waveStridePerStem)

        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val t0Ns = System.nanoTime()
        opts.use { sessionOptions ->
            env.createSession(onnxFile.absolutePath, sessionOptions).use { session ->
                OnnxTensor.createTensor(env, xtDirect, longArrayOf(1L, 2L, segLen.toLong())).use { tensorXt ->
                    OnnxTensor.createTensor(
                        env,
                        xDirect,
                        longArrayOf(1L, 4L, HtdemucsStft.FREQ_BINS.toLong(), HtdemucsStft.SEGMENT_FRAMES.toLong()),
                    ).use { tensorX ->
                        session.run(mapOf("xt" to tensorXt, "x" to tensorX)).use { ortRes ->
                            val specTensor = ortRes.get("out_spec").get() as OnnxTensor
                            val waveTensor = ortRes.get("out_wave").get() as OnnxTensor
                            assertArrayEquals(
                                longArrayOf(1L, 5L, 4L, HtdemucsStft.FREQ_BINS.toLong(), HtdemucsStft.SEGMENT_FRAMES.toLong()),
                                specTensor.info.shape,
                            )
                            assertArrayEquals(
                                longArrayOf(1L, 5L, 2L, segLen.toLong()),
                                waveTensor.info.shape,
                            )
                            val specFb = specTensor.floatBuffer
                            specFb.rewind()
                            specFb.get(outSpecFlat)
                            val waveFb = waveTensor.floatBuffer
                            waveFb.rewind()
                            waveFb.get(outWaveFlat)
                        }
                    }
                }
            }
        }
        val ortElapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L

        val separatedStems = Array(HtdemucsStft.NUM_APP_STEMS) { s ->
            val sL = FloatArray(segLen)
            val sR = FloatArray(segLen)
            HtdemucsStft.ispecCacStereoStem(
                specCac = outSpecFlat,
                stemSpecOffset = s * specStridePerStem,
                length = segLen,
                outLeft = sL,
                outRight = sR,
                addWaveBuffer = outWaveFlat,
                addWaveStemOffset = s * waveStridePerStem,
                scratch = stftScratch,
            )
            arrayOf(sL, sR)
        }

        // 4. Verify all 5 stems are 100% finite, non-zero, distinct, and reconstruct the mixture accurately
        val stemTypes = StemType.entries
        val stemRms = DoubleArray(HtdemucsStft.NUM_APP_STEMS)
        for (s in 0 until HtdemucsStft.NUM_APP_STEMS) {
            var energy = 0.0
            val sL = separatedStems[s][0]
            val sR = separatedStems[s][1]
            for (i in 0 until segLen) {
                val vl = sL[i]
                val vr = sR[i]
                assertTrue("Stem ${stemTypes[s]} sample must be finite", vl.isFinite() && vr.isFinite())
                assertTrue("Stem ${stemTypes[s]} sample must stay bounded, got L=$vl R=$vr", abs(vl) < 2.5f && abs(vr) < 2.5f)
                energy += vl.toDouble() * vl.toDouble() + vr.toDouble() * vr.toDouble()
            }
            stemRms[s] = sqrt(energy / (2.0 * segLen))
            assertTrue(
                "Expected non-zero separated energy for stem ${stemTypes[s]}, got rms=${stemRms[s]}",
                stemRms[s] > 1e-4,
            )
        }

        // Verify pairwise distinctness across all 5 stems
        for (a in 0 until HtdemucsStft.NUM_APP_STEMS) {
            for (b in (a + 1) until HtdemucsStft.NUM_APP_STEMS) {
                var diffEnergy = 0.0
                for (i in HtdemucsStft.PAD_LEFT until (segLen - HtdemucsStft.PAD_LEFT) step 7) {
                    val dl = (separatedStems[a][0][i] - separatedStems[b][0][i]).toDouble()
                    val dr = (separatedStems[a][1][i] - separatedStems[b][1][i]).toDouble()
                    diffEnergy += dl * dl + dr * dr
                }
                assertTrue("Stems ${stemTypes[a]} and ${stemTypes[b]} must be distinct", diffEnergy > 1e-2)
            }
        }

        // Mixture reconstruction check: sum(5 stems) vs input mixture over interior frames
        var dotMix = 0.0
        var eMix = 0.0
        var eSum = 0.0
        var eDiff = 0.0
        for (i in HtdemucsStft.PAD_LEFT until (segLen - HtdemucsStft.PAD_LEFT)) {
            var sumL = 0.0
            var sumR = 0.0
            for (s in 0 until HtdemucsStft.NUM_APP_STEMS) {
                sumL += separatedStems[s][0][i].toDouble()
                sumR += separatedStems[s][1][i].toDouble()
            }
            val inL = mixL[i].toDouble()
            val inR = mixR[i].toDouble()
            dotMix += inL * sumL + inR * sumR
            eMix += inL * inL + inR * inR
            eSum += sumL * sumL + sumR * sumR
            val dl = inL - sumL
            val dr = inR - sumR
            eDiff += dl * dl + dr * dr
        }
        val reconNcc = dotMix / sqrt(eMix * eSum)
        val reconSnrDb = 10.0 * log10(eMix / eDiff.coerceAtLeast(1e-18))
        println(
            "[RealHTDemucsE2E] ORT=${ortElapsedMs}ms, stemRMS=${
                stemTypes.indices.joinToString { "${stemTypes[it]}=${String.format("%.4f", stemRms[it])}" }
            }, reconNCC=${String.format("%.5f", reconNcc)}, reconSNR=${String.format("%.2f", reconSnrDb)}dB",
        )
        assertTrue(
            "Sum of 5 HTDemucs stems must strongly correlate with input mixture (reconNCC=$reconNcc)",
            reconNcc > 0.90,
        )
        assertTrue(
            "Sum of 5 HTDemucs stems must reconstruct input mixture accurately (reconSNR=${reconSnrDb}dB)",
            reconSnrDb > 8.0,
        )

        // 5. Stage B: Write real 5 stems to BFP10 MappedByteBuffer cache & drive RealTimeSonicAudioProcessor
        val stemsFile = File(tempDir, "real_htdemucs_10010.stems")
        StemSeparatedTrackCache.writeFromFloatStems(
            file = stemsFile,
            itemId = 10010L,
            sampleRate = HtdemucsStft.SAMPLE_RATE,
            stems5Stereo = separatedStems,
        ).close()

        StemSeparatedTrackCache.openMapped(stemsFile).use { mappedCache ->
            assertNotNull(mappedCache)
            mappedCache!!
            assertEquals(segLen, mappedCache.totalFrames)

            // Verify linear BFP10 quantization SNR on the real HTDemucs stems
            val out10 = FloatArray(10)
            var bfpSig = 0.0
            var bfpErr = 0.0
            for (i in 0 until segLen step 11) {
                mappedCache.readFrameStems(i, out10)
                for (s in 0 until HtdemucsStft.NUM_APP_STEMS) {
                    val refL = separatedStems[s][0][i].toDouble()
                    val refR = separatedStems[s][1][i].toDouble()
                    val el = refL - out10[s * 2].toDouble()
                    val er = refR - out10[s * 2 + 1].toDouble()
                    bfpSig += refL * refL + refR * refR
                    bfpErr += el * el + er * er
                }
            }
            val bfp10SnrDb = 10.0 * log10(bfpSig / bfpErr.coerceAtLeast(1e-18))
            println("[RealHTDemucsE2E] BFP10 cache SNR across real 5 stems = ${String.format("%.2f", bfp10SnrDb)} dB")
            assertTrue("Expected BFP10 cache SNR > 55 dB on real HTDemucs stems, got $bfp10SnrDb dB", bfp10SnrDb > 55.0)

            // Feed real mixture PCM starting at frame 16,384 (371.52 ms) with deliberate -121 ms playhead drift (250 ms)
            val startFrame = 16_384
            val windowFrames = 8_192
            val inputSliceShorts = mixPcmShorts.copyOfRange(startFrame * 2, (startFrame + windowFrames) * 2)

            val processor = RealTimeSonicAudioProcessor()
            processor.configure(AudioFormat(HtdemucsStft.SAMPLE_RATE, 2, C.ENCODING_PCM_16BIT))
            processor.setSeparatedStemsContext(mappedCache) { 250L }

            // Verify each soloed stem through RealTimeSonicAudioProcessor aligns to frame 16,384 and matches ground-truth ORT stem
            for ((stemIdx, stemType) in stemTypes.withIndex()) {
                processor.flush()
                processor.setTargetSpeedAndPitch(1.0f, 1.0f)
                processor.setStemMix(StemMixConfig(soloStem = stemType))
                processor.queueInput(shortsToNativeBuffer(inputSliceShorts))
                val soloOut = readShortsFromBuffer(processor.output)
                assertEquals(windowFrames * 2, soloOut.size)
                assertEquals(
                    "NCC playhead alignment on real HTDemucs cache must lock onto exact startFrame + windowFrames",
                    (startFrame + windowFrames).toDouble(),
                    processor.currentAlignedStemCursor,
                    2.0,
                )

                var dot = 0.0
                var eRef = 0.0
                var eOut = 0.0
                var maxLsbDiff = 0
                for (f in 0 until windowFrames) {
                    mappedCache.readFrameStems(startFrame + f, out10)
                    val expShortL = (out10[stemIdx * 2] * 32767f).roundToInt().coerceIn(-32768, 32767)
                    val expShortR = (out10[stemIdx * 2 + 1] * 32767f).roundToInt().coerceIn(-32768, 32767)
                    maxLsbDiff = max(maxLsbDiff, abs(expShortL - soloOut[f * 2].toInt()))
                    maxLsbDiff = max(maxLsbDiff, abs(expShortR - soloOut[f * 2 + 1].toInt()))

                    val refL = separatedStems[stemIdx][0][startFrame + f].toDouble()
                    val refR = separatedStems[stemIdx][1][startFrame + f].toDouble()
                    val actL = soloOut[f * 2].toDouble() / 32768.0
                    val actR = soloOut[f * 2 + 1].toDouble() / 32768.0
                    dot += refL * actL + refR * actR
                    eRef += refL * refL + refR * refR
                    eOut += actL * actL + actR * actR
                }
                assertTrue(
                    "Solo $stemType output must match mapped BFP10 stem within 2 LSBs (got maxLsbDiff=$maxLsbDiff)",
                    maxLsbDiff <= 2,
                )
                if (eRef > 1e-4) {
                    val soloNcc = dot / sqrt(eRef * eOut.coerceAtLeast(1e-18))
                    assertTrue("Solo $stemType output must match real ORT stem (ncc=$soloNcc)", soloNcc > 0.99)
                }
            }

            // Chained Vocal-Muted backing mix + pure Sonic (-2 semitones, 0.85x speed)
            processor.flush()
            processor.setStemMix(StemMixConfig(mutedStems = setOf(StemType.VOCALS)))
            processor.setTargetSpeedAndPitch(0.85f, PracticeMath.computePitchFactor(-2, 0))
            val totalChainFrames = 44_100 // 1.0 s slice from frame 16,384
            var emittedShorts = 0
            var nonZeroOut = 0
            var cursor = startFrame
            while (cursor < startFrame + totalChainFrames) {
                val n = minOf(2_048, startFrame + totalChainFrames - cursor)
                val chunk = mixPcmShorts.copyOfRange(cursor * 2, (cursor + n) * 2)
                processor.queueInput(shortsToNativeBuffer(chunk))
                val out = readShortsFromBuffer(processor.output)
                emittedShorts += out.size
                nonZeroOut += out.count { it.toInt() != 0 }
                cursor += n
            }
            processor.queueEndOfStream()
            val tail = readShortsFromBuffer(processor.output)
            emittedShorts += tail.size
            nonZeroOut += tail.count { it.toInt() != 0 }

            val emittedFrames = emittedShorts / 2
            val expectedSonicFrames = (totalChainFrames / 0.85).roundToInt()
            assertTrue(
                "Chained HTDemucs + pure Sonic (0.85x, -2st) expected ~$expectedSonicFrames frames, got $emittedFrames",
                abs(emittedFrames - expectedSonicFrames) < 3_000,
            )
            assertTrue("Chained HTDemucs + Sonic output must be non-silent", nonZeroOut > emittedFrames)
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(65_536).use { ins ->
            val buf = ByteArray(65_536)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun shortsToNativeBuffer(shorts: ShortArray): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(shorts.size * 2).order(ByteOrder.nativeOrder())
        buf.asShortBuffer().put(shorts)
        buf.limit(shorts.size * 2)
        buf.position(0)
        return buf
    }

    private fun readShortsFromBuffer(buf: ByteBuffer): ShortArray {
        val dup = buf.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
        val out = ShortArray(dup.remaining())
        dup.get(out)
        buf.position(buf.limit())
        return out
    }
}
