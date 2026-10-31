package com.miniplayer.app.core.update

import com.miniplayer.app.core.model.PlayContext
import com.miniplayer.app.core.model.PracticeMath
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppUpdaterAndPracticeMathTest {

    private data class MockHttpResponse(
        val status: Int = 200,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
        val delayMs: Long = 0L,
    )

    private lateinit var serverSocket: ServerSocket
    private val pool = Executors.newCachedThreadPool()
    @Volatile
    private var routeHandler: (pathWithQuery: String, headers: Map<String, String>) -> MockHttpResponse = { _, _ ->
        MockHttpResponse(status = 404)
    }

    private val baseUrl: String
        get() = "http://127.0.0.1:${serverSocket.localPort}"

    @Before
    fun setUp() {
        serverSocket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        pool.execute {
            while (!serverSocket.isClosed) {
                val client = runCatching { serverSocket.accept() }.getOrNull() ?: break
                pool.execute { handleClient(client) }
            }
        }
    }

    @After
    fun tearDown() {
        runCatching { serverSocket.close() }
        pool.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 5_000
            val ins = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = ins.readLine() ?: return
            val parts = requestLine.split(' ')
            val path = parts.getOrElse(1) { "/" }
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = ins.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }

            val resp = routeHandler(path, headers)
            if (resp.delayMs > 0L) {
                Thread.sleep(resp.delayMs)
            }

            val statusText = when (resp.status) {
                200 -> "OK"
                302 -> "Found"
                403 -> "Forbidden"
                404 -> "Not Found"
                429 -> "Too Many Requests"
                else -> "Status"
            }

            val out = s.getOutputStream()
            val headerBlock = buildString {
                append("HTTP/1.1 ${resp.status} $statusText\r\n")
                append("Connection: close\r\n")
                if ("content-length" !in resp.headers.keys.map { it.lowercase() }) {
                    append("Content-Length: ${resp.body.size}\r\n")
                }
                for ((k, v) in resp.headers) {
                    append("$k: $v\r\n")
                }
                append("\r\n")
            }
            out.write(headerBlock.toByteArray(Charsets.ISO_8859_1))
            if (resp.body.isNotEmpty()) {
                out.write(resp.body)
            }
            out.flush()
        }
    }

    private fun buildValidDummyApkBytes(payloadText: String = "MiniPlayer-v1.1.0-DEX"): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("<manifest package=\"com.miniplayer.app\"/>".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(payloadText.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun `semanticVersion and sha256SumsParser handle tags debug suffixes and checksum manifests`() {
        assertEquals("1.0.0", SemanticVersion.stripDebugSuffix("1.0.0-debug"))
        assertTrue(SemanticVersion.isNewerVersion("1.0.0", "v1.0.1"))
        assertTrue(SemanticVersion.isNewerVersion("1.0.0-rc.1", "1.0.0"))
        assertFalse(SemanticVersion.isNewerVersion("1.2.0", "v1.2.0"))
        assertFalse(SemanticVersion.isNewerVersion("1.2.0", "v1.1.9"))

        val hashA = "a".repeat(64)
        val hashB = "B".repeat(64)
        val parsedSums = Sha256SumsParser.parse(
            """
            # Release checksums
            $hashA  MiniPlayer-v1.1.0.apk
            $hashB *dist/other-arm64.apk
            """.trimIndent(),
        )
        assertEquals(hashA, parsedSums["miniplayer-v1.1.0.apk"])
        assertEquals(hashB.lowercase(), parsedSums["other-arm64.apk"])
        assertTrue(Sha256SumsParser.isValidHex64(hashA))
        assertFalse(Sha256SumsParser.isValidHex64("xyz"))
    }

    @Test
    fun `checkLatestRelease falls back to releases list fetches SHA256SUMS and verifies APK download`() {
        val apkBytes = buildValidDummyApkBytes()
        val apkSha = sha256Hex(apkBytes)

        routeHandler = { path, _ ->
            when {
                path == "/releases/latest" -> MockHttpResponse(status = 404)
                path.startsWith("/releases?per_page=5") -> MockHttpResponse(
                    status = 200,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = """
                        [
                          {
                            "tag_name": "v1.2.0-beta.1",
                            "prerelease": true,
                            "draft": false,
                            "assets": []
                          },
                          {
                            "tag_name": "v1.1.0",
                            "name": "MiniPlayer 1.1.0",
                            "body": "- Sample-accurate A-B loop improvements",
                            "published_at": "2026-10-31T10:00:00Z",
                            "prerelease": false,
                            "draft": false,
                            "assets": [
                              {
                                "name": "notes.apk",
                                "browser_download_url": "$baseUrl/dl/notes.apk",
                                "size": 100
                              },
                              {
                                "name": "MiniPlayer-v1.1.0.apk",
                                "browser_download_url": "$baseUrl/redirect/1",
                                "size": ${apkBytes.size}
                              },
                              {
                                "name": "SHA256SUMS.txt",
                                "browser_download_url": "$baseUrl/dl/SHA256SUMS.txt",
                                "size": 96
                              }
                            ]
                          }
                        ]
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                )
                path == "/dl/SHA256SUMS.txt" -> MockHttpResponse(
                    status = 200,
                    headers = mapOf("Content-Type" to "text/plain"),
                    body = "$apkSha  MiniPlayer-v1.1.0.apk\n".toByteArray(Charsets.UTF_8),
                )
                path == "/redirect/1" -> MockHttpResponse(status = 302, headers = mapOf("Location" to "$baseUrl/redirect/2"))
                path == "/redirect/2" -> MockHttpResponse(status = 302, headers = mapOf("Location" to "$baseUrl/dl/MiniPlayer-v1.1.0.apk"))
                path == "/dl/MiniPlayer-v1.1.0.apk" -> MockHttpResponse(
                    status = 200,
                    headers = mapOf("Content-Type" to "application/vnd.android.package-archive"),
                    body = apkBytes,
                )
                else -> MockHttpResponse(status = 404)
            }
        }

        val updater = AppUpdater(
            latestUrl = "$baseUrl/releases/latest",
            listUrl = "$baseUrl/releases?per_page=5",
            allowInsecureLoopback = true,
        )

        val check = updater.checkLatestRelease(currentVersion = "1.0.0-debug", includePre = false)
        assertTrue(check.updateAvailable)
        assertEquals("v1.1.0", check.latest.tagName)
        assertEquals("MiniPlayer-v1.1.0.apk", check.apk!!.name)
        assertEquals(apkSha, check.expectedSha256)

        val tempDir = Files.createTempDirectory("mp_updater_test").toFile()
        try {
            val dest = File(tempDir, "updates/MiniPlayer-1.1.0.apk")
            var validatorInvoked = false
            val downloaded = updater.downloadApk(
                url = check.apk!!.url,
                dest = dest,
                expectedSize = check.apk!!.size,
                expectedSha256 = check.expectedSha256,
                validator = { staged ->
                    assertTrue(staged.exists())
                    validatorInvoked = true
                },
            )
            assertTrue(validatorInvoked)
            assertTrue(downloaded.exists())
            assertFalse(File(dest.path + ".tmp").exists())
            assertEquals(apkSha, AppUpdater.sha256HexOfFile(downloaded))

            // Tampered SHA-256 must fail with CORRUPT_DOWNLOAD and clean up `.tmp`
            val badDest = File(tempDir, "updates/Bad.apk")
            val badEx = runCatching {
                updater.downloadApk(
                    url = check.apk!!.url,
                    dest = badDest,
                    expectedSize = check.apk!!.size,
                    expectedSha256 = "0".repeat(64),
                )
            }.exceptionOrNull() as? UpdateException
            assertNotNull(badEx)
            assertEquals(UpdateErrorCode.CORRUPT_DOWNLOAD, badEx!!.code)
            assertFalse(badDest.exists())
            assertFalse(File(badDest.path + ".tmp").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `redirect hop limit HTTPS downgrade block rate limit and corrupt non-zip checks`() {
        // 1. Default updater blocks plain HTTP even on loopback when allowInsecureLoopback = false
        val strictUpdater = AppUpdater(
            latestUrl = "$baseUrl/releases/latest",
            listUrl = "$baseUrl/releases",
            allowInsecureLoopback = false,
        )
        val insecureEx = runCatching {
            strictUpdater.checkLatestRelease("1.0.0")
        }.exceptionOrNull() as? UpdateException
        assertEquals(UpdateErrorCode.INSECURE_URL, insecureEx?.code)

        // 2. HTTPS -> HTTP downgrade redirect MUST be rejected with INSECURE_URL
        val downgradeUpdater = AppUpdater(
            latestUrl = "https://api.github.com/repos/codescv/miniplayer/releases/latest",
            listUrl = "",
            openConnection = { url: URL ->
                object : HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy(): Boolean = false
                    override fun getResponseCode(): Int = 302
                    override fun getHeaderField(name: String?): String? =
                        if (name.equals("Location", ignoreCase = true)) "http://evil.example.com/MiniPlayer.apk" else null
                }
            },
            allowInsecureLoopback = false,
        )
        val downgradeEx = runCatching {
            downgradeUpdater.checkLatestRelease("1.0.0")
        }.exceptionOrNull() as? UpdateException
        assertEquals(UpdateErrorCode.INSECURE_URL, downgradeEx?.code)

        // 3. > 5 redirect hops rejected
        routeHandler = { path, _ ->
            val hop = path.substringAfterLast('/').toIntOrNull() ?: 0
            MockHttpResponse(status = 302, headers = mapOf("Location" to "$baseUrl/hop/${hop + 1}"))
        }
        val loopUpdater = AppUpdater(
            latestUrl = "$baseUrl/hop/0",
            listUrl = "",
            allowInsecureLoopback = true,
        )
        val tooManyRedirectsEx = runCatching {
            loopUpdater.checkLatestRelease("1.0.0")
        }.exceptionOrNull() as? UpdateException
        assertEquals(UpdateErrorCode.NETWORK, tooManyRedirectsEx?.code)

        // 4. GitHub HTTP 403 rate-limit maps to RATE_LIMITED
        routeHandler = { _, _ ->
            MockHttpResponse(status = 403, headers = mapOf("X-RateLimit-Remaining" to "0"))
        }
        val rateEx = runCatching {
            loopUpdater.checkLatestRelease("1.0.0")
        }.exceptionOrNull() as? UpdateException
        assertEquals(UpdateErrorCode.RATE_LIMITED, rateEx?.code)

        // 5. Non-ZIP / missing AndroidManifest.xml payload rejected as CORRUPT_DOWNLOAD
        routeHandler = { _, _ ->
            MockHttpResponse(
                status = 200,
                headers = mapOf("Content-Type" to "application/octet-stream"),
                body = ByteArray(128) { 0x42 },
            )
        }
        val tempDir = Files.createTempDirectory("mp_corrupt_test").toFile()
        try {
            val dest = File(tempDir, "corrupt.apk")
            val corruptEx = runCatching {
                loopUpdater.downloadApk("$baseUrl/bad.apk", dest)
            }.exceptionOrNull() as? UpdateException
            assertEquals(UpdateErrorCode.CORRUPT_DOWNLOAD, corruptEx?.code)
            assertFalse(dest.exists())
            assertFalse(File(dest.path + ".tmp").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `apkValidator enforces package match anti-downgrade versionCode and signing certificate match`() {
        val certA = ApkValidator.digestSignatureBytes(listOf(byteArrayOf(1, 2, 3, 4, 5)))
        val certB = ApkValidator.digestSignatureBytes(listOf(byteArrayOf(9, 8, 7, 6, 5)))

        // Valid upgrade passes
        ApkValidator.validateParsedMetadata(
            expectedPackageName = "com.miniplayer.app",
            installedVersionCode = 10_000L,
            installedCertDigests = certA,
            candidatePackageName = "com.miniplayer.app",
            candidateVersionCode = 10_100L,
            candidateCertDigests = certA,
        )

        // Package mismatch rejected
        val pkgEx = runCatching {
            ApkValidator.validateParsedMetadata(
                expectedPackageName = "com.miniplayer.app",
                installedVersionCode = 10_000L,
                installedCertDigests = certA,
                candidatePackageName = "com.evil.repack",
                candidateVersionCode = 10_100L,
                candidateCertDigests = certA,
            )
        }.exceptionOrNull() as? UpdateException
        assertEquals(UpdateErrorCode.VERIFY_FAILED, pkgEx?.code)

        // Same or lower versionCode rejected (anti-downgrade)
        val downgradeEx = runCatching {
            ApkValidator.validateParsedMetadata(
                expectedPackageName = "com.miniplayer.app",
                installedVersionCode = 10_000L,
                installedCertDigests = certA,
                candidatePackageName = "com.miniplayer.app",
                candidateVersionCode = 10_000L,
                candidateCertDigests = certA,
            )
        }.exceptionOrNull() as? UpdateException
        assertEquals(UpdateErrorCode.VERIFY_FAILED, downgradeEx?.code)

        // Empty or mismatched signing certificate rejected (fail closed)
        val emptyCertEx = runCatching {
            ApkValidator.validateParsedMetadata(
                expectedPackageName = "com.miniplayer.app",
                installedVersionCode = 10_000L,
                installedCertDigests = certA,
                candidatePackageName = "com.miniplayer.app",
                candidateVersionCode = 10_100L,
                candidateCertDigests = emptySet(),
            )
        }.exceptionOrNull() as? UpdateException
        assertEquals(UpdateErrorCode.VERIFY_FAILED, emptyCertEx?.code)

        val mismatchCertEx = runCatching {
            ApkValidator.validateParsedMetadata(
                expectedPackageName = "com.miniplayer.app",
                installedVersionCode = 10_000L,
                installedCertDigests = certA,
                candidatePackageName = "com.miniplayer.app",
                candidateVersionCode = 10_100L,
                candidateCertDigests = certB,
            )
        }.exceptionOrNull() as? UpdateException
        assertEquals(UpdateErrorCode.VERIFY_FAILED, mismatchCertEx?.code)
    }

    @Test
    fun `practiceMath pitch semitone cents BPM click interval tap tempo and A-B loop normalization`() {
        assertEquals(1.0f, PracticeMath.computePitchFactor(0, 0), 1e-6f)
        // Eb tuning (-1 semitone) = 2^(-1/12) ≈ 0.9438743
        assertTrue(abs(PracticeMath.computePitchFactor(-1, 0) - 0.9438743f) < 1e-5f)
        // +12 semitones clamped to +6 semitones = sqrt(2) ≈ 1.4142135
        assertTrue(abs(PracticeMath.computePitchFactor(12, 0) - 1.4142135f) < 1e-5f)
        // A=432Hz approx -32 cents -> factor < 1.0
        assertTrue(PracticeMath.computePitchFactor(0, -32) in 0.980f..0.985f)

        // 120 BPM @ 0.75x = 90 effective BPM -> 667 ms per click
        assertEquals(667L, PracticeMath.computeClickIntervalMs(bpm = 120, practiceSpeed = 0.75f))
        assertEquals(500L, PracticeMath.computeClickIntervalMs(bpm = 120, practiceSpeed = 1.00f))

        // Tap tempo median calculation (120 BPM = 500ms intervals, ignores outlier)
        assertNull(PracticeMath.computeTapTempoBpm(listOf(1_000L)))
        assertEquals(120, PracticeMath.computeTapTempoBpm(listOf(1_000L, 1_500L, 2_000L, 2_500L, 3_000L)))

        // Loop normalization: swaps reversed A > B and enforces >= 500ms span
        assertEquals(10_000L to 25_000L, PracticeMath.normalizeLoopPoints(25_000L, 10_000L, 180_000L))
        assertEquals(10_000L to 10_500L, PracticeMath.normalizeLoopPoints(10_000L, 10_100L, 180_000L))
        assertNull(PracticeMath.normalizeLoopPoints(0L, 100L, 300L))

        // Deterministic Practice Mode activation matrix (`DESIGN.md` §5.1)
        assertFalse(PracticeMath.shouldAutoActivatePracticeMode(PlayContext.CASUAL))
        assertTrue(PracticeMath.shouldAutoActivatePracticeMode(PlayContext.PRACTICE_PLAYLIST))
        assertTrue(PracticeMath.shouldAutoActivatePracticeMode(PlayContext.PRACTICE_FOLDER))
        assertTrue(PracticeMath.shouldAutoActivatePracticeMode(PlayContext.BOOKMARK_JUMP))

        // Time formatting with/without tenths
        assertEquals("01:05", PracticeMath.formatTimeMs(65_432L, includeTenths = false))
        assertEquals("01:05.4", PracticeMath.formatTimeMs(65_432L, includeTenths = true))
        assertEquals("1:02:03.9", PracticeMath.formatTimeMs(3_723_910L, includeTenths = true))
    }
}
