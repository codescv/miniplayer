package com.miniplayer.app.core.metadata

import com.miniplayer.app.core.AppUpdateContract
import com.miniplayer.app.core.FolderScannerContract
import com.miniplayer.app.core.MediaLibraryContract
import com.miniplayer.app.core.MetadataEngineContract
import com.miniplayer.app.core.MiniPlayerCoreProvider
import com.miniplayer.app.core.db.InMemoryMediaStoreBackend
import com.miniplayer.app.core.db.MediaItemRow
import com.miniplayer.app.core.db.MediaRepository
import com.miniplayer.app.core.model.AddRootResult
import com.miniplayer.app.core.model.LyricsSource
import com.miniplayer.app.core.model.MetadataDraft
import com.miniplayer.app.core.model.MetadataField
import com.miniplayer.app.core.model.MetadataSaveOutcome
import com.miniplayer.app.core.model.MountState
import com.miniplayer.app.core.model.ScanProgress
import com.miniplayer.app.core.model.UpdateInfo
import com.miniplayer.app.lan.JsonValue
import com.miniplayer.app.lan.LanJson
import com.miniplayer.app.lan.LanServerConfig
import com.miniplayer.app.lan.LanStudioServer
import com.miniplayer.app.lan.LanStudioWebApp
import com.miniplayer.app.lan.LocalLanStorageGateway
import android.content.Intent
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataAndLanTest {

    private class FakeOnlineHttpClient(
        private val handler: suspend (url: String, headOnly: Boolean) -> RawHttpResponse,
    ) : OnlineHttpClient {
        val requestedUrls = CopyOnWriteArrayList<String>()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
            maxBytes: Int,
            headOnly: Boolean,
            totalTimeoutMs: Long,
        ): RawHttpResponse {
            requestedUrls.add(url)
            return handler(url, headOnly)
        }
    }

    @Test
    fun `saveMetadata persists staged candidate coverRemoteUrl syncedLrc and tags when user clicks save`() = runBlocking {
        val remoteCoverBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 11, 22, 33, 44,
        )
        val remoteCoverUrl = "https://img.example.org/covers/constellation-1000.png"
        val fakeHttp = FakeOnlineHttpClient { url, headOnly ->
            when {
                url == remoteCoverUrl && !headOnly -> RawHttpResponse(
                    statusCode = 200,
                    finalUrl = url,
                    contentType = "image/png",
                    body = remoteCoverBytes,
                )
                url.contains("musicbrainz.org/ws/2/recording") -> RawHttpResponse(
                    statusCode = 200,
                    finalUrl = url,
                    contentType = "application/json",
                    body = """
                        {
                          "recordings": [
                            {
                              "id": "rec-constellation",
                              "title": "星座になれたら",
                              "length": 258000,
                              "artist-credit": [{"name": "結束バンド"}],
                              "releases": [
                                {
                                  "id": "rel-kessoku",
                                  "title": "結束バンド",
                                  "status": "Official",
                                  "date": "2022-12-28",
                                  "media": [{"track": [{"number": "11"}]}]
                                }
                              ]
                            }
                          ]
                        }
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                )
                url.contains("coverartarchive.org/release/rel-kessoku/front-500") && headOnly -> RawHttpResponse(
                    statusCode = 200,
                    finalUrl = remoteCoverUrl,
                    contentType = "image/png",
                )
                url.contains("itunes.apple.com/search") -> RawHttpResponse(
                    statusCode = 200,
                    finalUrl = url,
                    contentType = "application/json",
                    body = """{"resultCount":0,"results":[]}""".toByteArray(Charsets.UTF_8),
                )
                url.contains("lrclib.net/api/get") -> RawHttpResponse(
                    statusCode = 200,
                    finalUrl = url,
                    contentType = "application/json",
                    body = """
                        {
                          "syncedLyrics": "[00:12.00]もうすぐ時計の針が重なる\n[00:18.50]君に会えるかな",
                          "plainLyrics": "もうすぐ時計の針が重なる\n君に会えるかな"
                        }
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                )
                else -> RawHttpResponse(statusCode = 404, finalUrl = url)
            }
        }

        val tempDir = Files.createTempDirectory("mp_staged_meta_test").toFile()
        try {
            val backend = InMemoryMediaStoreBackend()
            val rootId = backend.insertSafRoot(
                treeUri = "content://saf/tree/primary%3AMusic",
                treeDocId = "primary:Music",
                displayName = "Music",
                isWritable = true,
                mountState = MountState.MOUNTED,
            )
            val fileName = "11 - Constellation (Backing Track).flac"
            val parsed = SmartFilenameParser.parse("JRock", fileName)
            backend.reconcileRootScan(
                rootId = rootId,
                isWritable = true,
                scannedAtMs = 1_000L,
                scannedFolders = emptyList(),
                unchangedCoverUpdates = emptyMap(),
                movedItems = emptyList(),
                upsertedItems = listOf(
                    MediaItemRow(
                        documentUri = "content://saf/doc/constellation",
                        rootId = rootId,
                        parentRelPath = "JRock",
                        fileName = fileName,
                        mimeType = "audio/flac",
                        isVideo = false,
                        sizeBytes = 20_000_000L,
                        lastModified = 1_000L,
                        durationMs = 258_000L,
                        parsedTitle = parsed.cleanedTitle,
                        parsedArtist = parsed.artist,
                        parsedAlbum = null,
                        isBackingTrack = parsed.isBackingTrack,
                        fingerprintKey = MediaRepository.buildFingerprintKey(fileName, 20_000_000L, 258_000L),
                    ),
                ),
                deletedItemIds = emptySet(),
                lyricsUpdates = emptyList(),
                artistSplitter = { a, aa -> ArtistSplitter.split(a, aa) },
            )

            val repo = MediaRepository(
                backend = backend,
                filesRootDir = tempDir,
                ioDispatcher = Dispatchers.Unconfined,
            )
            val capturedSpec = AtomicReference<TagWriteSpec?>(null)
            val tagWriter = PhysicalTagWriter { row, spec ->
                capturedSpec.set(spec)
                (row.sizeBytes + 128L) to (row.lastModified + 500L)
            }
            val completer = OnlineMetadataCompleter(
                http = fakeHttp,
                musicBrainz = MusicBrainzClient(fakeHttp, minIntervalMs = 0L),
                coverArtArchive = CoverArtArchiveClient(fakeHttp, timeoutMs = 500L),
                itunes = ItunesSearchClient(fakeHttp),
                lrclib = LrclibClient(fakeHttp),
            )
            val engine = DefaultMetadataEngine(
                context = null,
                repository = repo,
                onlineCompleter = completer,
                lrclibClient = LrclibClient(fakeHttp),
                coversDir = File(tempDir, "covers"),
                physicalTagWriter = tagWriter,
                ioDispatcher = Dispatchers.Unconfined,
            )

            val initialItem = repo.allMediaItems.value.single()
            val candidates = engine.searchOnlineCandidates(initialItem, null, null)
            assertTrue(candidates.isNotEmpty())
            val selected = candidates.first()

            // Searching candidates MUST NOT auto-save metadata, cover, or lyrics!
            val afterSearch = repo.allMediaItems.value.single()
            assertFalse(afterSearch.hasMetadataOverride)
            assertNull(afterSearch.coverPathOrUri)
            assertNull(engine.getLyrics(afterSearch, fetchOnlineIfMissing = false))

            // User clicks candidate to stage into MetadataDraft, edits title slightly if desired, then clicks "保存"
            val stagedTitle = SmartFilenameParser.applyBackingSuffix(selected.title, initialItem.fileName)
            val draft = MetadataDraft(
                title = stagedTitle,
                artist = selected.artist,
                album = selected.album,
                albumArtist = selected.albumArtist ?: selected.artist,
                trackNumber = selected.trackNumber,
                year = selected.year,
                coverRemoteUrl = selected.coverUrl,
                syncedLrc = selected.syncedLrc,
                plainLyrics = selected.plainLyrics,
                dirtyFields = setOf(
                    MetadataField.TITLE,
                    MetadataField.ARTIST,
                    MetadataField.ALBUM,
                    MetadataField.ALBUM_ARTIST,
                    MetadataField.TRACK_NUMBER,
                    MetadataField.YEAR,
                    MetadataField.COVER,
                ),
                writeBackToFile = true,
            )

            val outcome = engine.saveMetadata(
                itemIds = listOf(initialItem.itemId),
                draft = draft,
                currentlyPlayingItemId = null,
            )
            assertEquals(MetadataSaveOutcome.SAVED_DB_AND_FILE, outcome)

            val savedItem = repo.allMediaItems.value.single()
            assertEquals("星座になれたら (Backing Track)", savedItem.title)
            assertEquals("結束バンド", savedItem.artist)
            assertEquals("結束バンド", savedItem.album)
            assertEquals(11, savedItem.trackNumber)
            assertEquals(2022, savedItem.year)
            assertNotNull(savedItem.coverPathOrUri)
            val savedCoverFile = File(savedItem.coverPathOrUri!!)
            assertTrue(savedCoverFile.isFile)
            assertArrayEquals(remoteCoverBytes, savedCoverFile.readBytes())

            // Physical tag writer also received the downloaded cover bytes & PNG mime
            val writtenSpec = capturedSpec.get()
            assertNotNull(writtenSpec)
            assertTrue(writtenSpec!!.cover is OptionalField.Set)
            val writtenCover = (writtenSpec.cover as OptionalField.Set).value!!
            assertEquals("image/png", writtenCover.mimeType)
            assertArrayEquals(remoteCoverBytes, writtenCover.bytes)

            // Synced LRCLIB lyrics were persisted into LyricsCacheRow and are immediately readable offline
            val savedLyrics = engine.getLyrics(savedItem, fetchOnlineIfMissing = false)
            assertNotNull(savedLyrics)
            assertEquals(LyricsSource.LRCLIB, savedLyrics!!.source)
            assertEquals(2, savedLyrics.lines.size)
            assertEquals(12_000L, savedLyrics.lines[0].timeMs)

            // If user subsequently picks a local cover image (`coverBytes`), local `coverBytes` takes precedence over `coverRemoteUrl`
            fakeHttp.requestedUrls.clear()
            val localJpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9, 8, 7)
            engine.saveMetadata(
                itemIds = listOf(initialItem.itemId),
                draft = MetadataDraft(
                    coverBytes = localJpegBytes,
                    coverMimeType = "image/jpeg",
                    coverRemoteUrl = remoteCoverUrl,
                    dirtyFields = setOf(MetadataField.COVER),
                    writeBackToFile = false,
                ),
                currentlyPlayingItemId = null,
            )
            assertFalse("Local coverBytes must take precedence without downloading coverRemoteUrl", fakeHttp.requestedUrls.contains(remoteCoverUrl))
            val localUpdated = repo.allMediaItems.value.single()
            assertArrayEquals(localJpegBytes, File(localUpdated.coverPathOrUri!!).readBytes())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `lan web studio candidates endpoint and candidatesOnly mode stage metadata without auto-saving until save`() = runBlocking {
        val remoteCoverBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 42, 43, 44)
        val remoteCoverUrl = "https://img.example.org/covers/neon-1000.jpg"
        val fakeHttp = FakeOnlineHttpClient { url, headOnly ->
            when {
                url == remoteCoverUrl && !headOnly -> RawHttpResponse(
                    statusCode = 200,
                    finalUrl = url,
                    contentType = "image/jpeg",
                    body = remoteCoverBytes,
                )
                url.contains("musicbrainz.org/ws/2/recording") -> RawHttpResponse(
                    statusCode = 200,
                    finalUrl = url,
                    contentType = "application/json",
                    body = """
                        {
                          "recordings": [
                            {
                              "id": "rec-neon",
                              "title": "Neon",
                              "length": 262000,
                              "artist-credit": [{"name": "John Mayer"}],
                              "releases": [
                                {
                                  "id": "rel-rfs",
                                  "title": "Room for Squares",
                                  "status": "Official",
                                  "date": "2001-06-05",
                                  "media": [{"track": [{"number": "4"}]}]
                                }
                              ]
                            }
                          ]
                        }
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                )
                url.contains("coverartarchive.org/release/rel-rfs/front-500") && headOnly -> RawHttpResponse(
                    statusCode = 200,
                    finalUrl = remoteCoverUrl,
                    contentType = "image/jpeg",
                )
                url.contains("itunes.apple.com/search") -> RawHttpResponse(
                    statusCode = 200,
                    finalUrl = url,
                    contentType = "application/json",
                    body = """{"resultCount":0,"results":[]}""".toByteArray(Charsets.UTF_8),
                )
                url.contains("lrclib.net/api/get") -> RawHttpResponse(
                    statusCode = 200,
                    finalUrl = url,
                    contentType = "application/json",
                    body = """
                        {
                          "syncedLyrics": "[00:19.00]When sky blue gets dark enough",
                          "plainLyrics": "When sky blue gets dark enough"
                        }
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                )
                else -> RawHttpResponse(statusCode = 404, finalUrl = url)
            }
        }

        val tempDir = Files.createTempDirectory("mp_lan_staged_test").toFile()
        var server: LanStudioServer? = null
        try {
            val audioFile = File(tempDir, "04 - Neon.mp3").apply { writeBytes(ByteArray(2048) { 1 }) }
            val backend = InMemoryMediaStoreBackend()
            val rootId = backend.insertSafRoot(
                treeUri = tempDir.toURI().toString(),
                treeDocId = "primary:LanMusic",
                displayName = "LanMusic",
                isWritable = false,
                mountState = MountState.MOUNTED,
            )
            backend.reconcileRootScan(
                rootId = rootId,
                isWritable = false,
                scannedAtMs = 1_000L,
                scannedFolders = emptyList(),
                unchangedCoverUpdates = emptyMap(),
                movedItems = emptyList(),
                upsertedItems = listOf(
                    MediaItemRow(
                        documentUri = audioFile.toURI().toString(),
                        rootId = rootId,
                        parentRelPath = "",
                        fileName = audioFile.name,
                        mimeType = "audio/mpeg",
                        isVideo = false,
                        sizeBytes = audioFile.length(),
                        lastModified = 1_000L,
                        durationMs = 262_000L,
                        parsedTitle = "Neon",
                        parsedArtist = null,
                        parsedAlbum = null,
                        isBackingTrack = false,
                        fingerprintKey = MediaRepository.buildFingerprintKey(audioFile.name, audioFile.length(), 262_000L),
                    ),
                ),
                deletedItemIds = emptySet(),
                lyricsUpdates = emptyList(),
                artistSplitter = { a, aa -> ArtistSplitter.split(a, aa) },
            )

            val repo = MediaRepository(backend = backend, filesRootDir = tempDir, ioDispatcher = Dispatchers.Unconfined)
            val completer = OnlineMetadataCompleter(
                http = fakeHttp,
                musicBrainz = MusicBrainzClient(fakeHttp, minIntervalMs = 0L),
                coverArtArchive = CoverArtArchiveClient(fakeHttp, timeoutMs = 500L),
                itunes = ItunesSearchClient(fakeHttp),
                lrclib = LrclibClient(fakeHttp),
            )
            val engine = DefaultMetadataEngine(
                context = null,
                repository = repo,
                onlineCompleter = completer,
                lrclibClient = LrclibClient(fakeHttp),
                coversDir = File(tempDir, "covers"),
                ioDispatcher = Dispatchers.Unconfined,
            )

            val core = object : MiniPlayerCoreProvider {
                override val library: MediaLibraryContract = repo
                override val scanner: FolderScannerContract = object : FolderScannerContract {
                    override val scanProgress: StateFlow<ScanProgress> = MutableStateFlow(ScanProgress())
                    override suspend fun addSafRoot(treeUri: Uri): AddRootResult = error("unused")
                    override suspend fun rescanAll(incremental: Boolean) {}
                    override suspend fun rescanRoot(rootId: Long, incremental: Boolean) {}
                    override suspend fun removeSafRoot(rootId: Long) {}
                    override suspend fun setFolderPracticeRule(rootId: Long, relPath: String, folderUri: String, isPracticeFolder: Boolean) {}
                    override suspend fun setFolderExcludeFromGroupingRule(rootId: Long, relPath: String, folderUri: String, isExcludedFromAutoGrouping: Boolean) {}
                    override suspend fun setFolderNomediaRule(rootId: Long, relPath: String, folderUri: String, respectNomedia: Boolean?) {}
                }
                override val metadata: MetadataEngineContract = engine
                override val updater: AppUpdateContract = object : AppUpdateContract {
                    override suspend fun checkForUpdates(includePreRelease: Boolean): UpdateInfo = error("unused")
                    override suspend fun downloadAndVerifyUpdate(info: UpdateInfo, onProgress: (Long, Long) -> Unit): File = error("unused")
                    override fun canRequestPackageInstalls(): Boolean = false
                    override fun buildInstallIntent(apkFile: File): Intent = Intent()
                }
            }

            server = LanStudioServer(
                config = LanServerConfig(port = 0, bindAddress = "127.0.0.1"),
                core = core,
                storage = LocalLanStorageGateway(mapOf(rootId to tempDir)),
            )
            server.start()
            val baseUrl = "http://127.0.0.1:${server.boundPort}"
            val itemId = repo.allMediaItems.value.single().itemId

            // 1. Check WebApp HTML has staging UI & zero forbidden terminology
            val html = LanStudioWebApp.renderHtml()
            assertTrue(html.contains("🌐 在线自动补全当前曲目"))
            assertTrue(html.contains("ed-candidates-box"))
            assertTrue(html.contains("保存更改"))
            assertFalse(html.contains("练琴"))

            // 2. GET /api/metadata/candidates?itemId=... returns candidates WITHOUT modifying DB
            val getCandJson = LanJson.parseObject(httpJson(baseUrl, "GET", "/api/metadata/candidates?itemId=$itemId", null))
            assertTrue(getCandJson.bool("ok"))
            val candArr = getCandJson.arr("candidates")!!.items
            assertTrue(candArr.isNotEmpty())
            val firstCand = candArr.first() as JsonValue.Obj
            assertEquals("Neon", firstCand.str("title"))
            assertEquals("John Mayer", firstCand.str("artist"))
            assertEquals("Room for Squares", firstCand.str("album"))
            assertEquals(remoteCoverUrl, firstCand.str("coverUrl"))
            assertNotNull(firstCand.str("syncedLrc"))
            assertTrue((firstCand.intOrNull("confidence") ?: 0) > 0)
            assertFalse("GET candidates must NOT auto-save override", repo.allMediaItems.value.single().hasMetadataOverride)

            // 3. POST /api/metadata/auto-match with candidatesOnly=true also does NOT auto-save
            val postCandJson = LanJson.parseObject(
                httpJson(
                    baseUrl,
                    "POST",
                    "/api/metadata/auto-match",
                    """{"itemId":$itemId,"candidatesOnly":true}""",
                ),
            )
            assertTrue(postCandJson.bool("ok"))
            assertFalse(postCandJson.bool("matched"))
            assertTrue(postCandJson.arr("candidates")!!.items.isNotEmpty())
            assertFalse("candidatesOnly auto-match must NOT auto-save override", repo.allMediaItems.value.single().hasMetadataOverride)

            // 4. POST /api/metadata/update with staged coverRemoteUrl + syncedLrc + plainLyrics saves everything!
            val updateResp = LanJson.parseObject(
                httpJson(
                    baseUrl,
                    "POST",
                    "/api/metadata/update",
                    """
                    {
                      "itemIds": [$itemId],
                      "title": "Neon",
                      "artist": "John Mayer",
                      "album": "Room for Squares",
                      "albumArtist": "John Mayer",
                      "trackNumber": 4,
                      "year": 2001,
                      "coverRemoteUrl": "$remoteCoverUrl",
                      "syncedLrc": "[00:19.00]When sky blue gets dark enough",
                      "plainLyrics": "When sky blue gets dark enough",
                      "dirtyFields": ["TITLE", "ARTIST", "ALBUM", "ALBUM_ARTIST", "TRACK_NUMBER", "YEAR", "COVER"],
                      "writeBackToFile": false
                    }
                    """.trimIndent(),
                ),
            )
            assertTrue(updateResp.bool("ok"))

            val updatedItem = repo.allMediaItems.value.single()
            assertTrue(updatedItem.hasMetadataOverride)
            assertEquals("John Mayer", updatedItem.artist)
            assertEquals("Room for Squares", updatedItem.album)
            assertNotNull(updatedItem.coverPathOrUri)
            assertArrayEquals(remoteCoverBytes, File(updatedItem.coverPathOrUri!!).readBytes())
            val savedLyrics = engine.getLyrics(updatedItem, fetchOnlineIfMissing = false)
            assertNotNull(savedLyrics)
            assertEquals(LyricsSource.LRCLIB, savedLyrics!!.source)
        } finally {
            server?.stop()
            tempDir.deleteRecursively()
        }
    }

    private fun httpJson(baseUrl: String, method: String, path: String, body: String?): String {
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 5_000
            if (body != null) {
                doOutput = true
                val bytes = body.toByteArray(Charsets.UTF_8)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setFixedLengthStreamingMode(bytes.size)
                outputStream.use { it.write(bytes) }
            }
        }
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        assertEquals("Unexpected HTTP $code: $text", 200, code)
        return text
    }
}
