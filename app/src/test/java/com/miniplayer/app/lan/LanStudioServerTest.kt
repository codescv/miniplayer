package com.miniplayer.app.lan

import android.content.Intent
import android.net.Uri
import com.miniplayer.app.core.AppUpdateContract
import com.miniplayer.app.core.FolderScannerContract
import com.miniplayer.app.core.MediaLibraryContract
import com.miniplayer.app.core.MetadataEngineContract
import com.miniplayer.app.core.MiniPlayerCoreProvider
import com.miniplayer.app.core.model.AddRootResult
import com.miniplayer.app.core.model.AlbumSummary
import com.miniplayer.app.core.model.AppSettings
import com.miniplayer.app.core.model.ArtistDetail
import com.miniplayer.app.core.model.ArtistSummary
import com.miniplayer.app.core.model.BatchMatchItem
import com.miniplayer.app.core.model.BookmarkedTrackItem
import com.miniplayer.app.core.model.FolderBrowseState
import com.miniplayer.app.core.model.LyricsData
import com.miniplayer.app.core.model.M3uImportResult
import com.miniplayer.app.core.model.MediaItem
import com.miniplayer.app.core.model.MetadataCandidate
import com.miniplayer.app.core.model.MetadataDraft
import com.miniplayer.app.core.model.MetadataField
import com.miniplayer.app.core.model.MetadataSaveOutcome
import com.miniplayer.app.core.model.OnlineSource
import com.miniplayer.app.core.model.PlaylistDetail
import com.miniplayer.app.core.model.PlaylistSummary
import com.miniplayer.app.core.model.PracticeBookmark
import com.miniplayer.app.core.model.PracticeState
import com.miniplayer.app.core.model.SafRoot
import com.miniplayer.app.core.model.ScanProgress
import com.miniplayer.app.core.model.UpdateInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class LanStudioServerTest {

    private lateinit var tempRootDir: File
    private lateinit var fakeCore: FakeTestMiniPlayerCore
    private lateinit var storageGateway: LocalLanStorageGateway
    private lateinit var server: LanStudioServer
    private var baseUrl: String = ""

    @Volatile
    private var activePlayingItemId: Long? = null

    @Before
    fun setUp() {
        activePlayingItemId = null
        tempRootDir = Files.createTempDirectory("miniplayer_lan_test_").toFile()

        // Seed initial audio and video files in the mounted test folder
        val audioBytes = ByteArray(131_072) { idx -> ((idx * 31 + 17) and 0xFF).toByte() }
        File(tempRootDir, "constellation_backing.mp3").writeBytes(audioBytes)

        val subDir = File(tempRootDir, "Lessons").apply { mkdirs() }
        val videoBytes = ByteArray(65_536) { idx -> ((idx * 13 + 7) and 0xFF).toByte() }
        File(subDir, "solo_breakdown_1080p.mp4").writeBytes(videoBytes)

        fakeCore = FakeTestMiniPlayerCore(rootId = 1L, rootDir = tempRootDir)
        fakeCore.scanDiskSync()

        storageGateway = LocalLanStorageGateway(mapOf(1L to tempRootDir))
        server = LanStudioServer(
            config = LanServerConfig(
                port = 0,
                bindAddress = "127.0.0.1",
                currentlyPlayingItemIdProvider = { activePlayingItemId },
            ),
            core = fakeCore,
            storage = storageGateway,
        )
        server.start()
        assertTrue("Server should bind an ephemeral port > 0", server.boundPort > 0)
        baseUrl = "http://127.0.0.1:${server.boundPort}"
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) {
            server.stop()
        }
        if (::tempRootDir.isInitialized) {
            tempRootDir.deleteRecursively()
        }
    }

    @Test
    fun getRootServesSelfContainedWebStudioSpaHtml() {
        val resp = httpRequest("GET", "/")
        assertEquals(200, resp.status)
        assertTrue(resp.contentType.contains("text/html", ignoreCase = true))
        val html = resp.bodyUtf8
        assertTrue(html.contains("MiniPlayer · PC 局域网 Web 管理台"))
        assertTrue(html.contains("ws-folders"))
        assertTrue(html.contains("ws-metadata"))
        assertTrue(html.contains("ws-playlists"))
        assertTrue(html.contains("ws-studio"))
        assertTrue(html.contains("/api/upload"))
        assertTrue(html.contains("/api/stream"))
        assertFalse("SPA must be 100% offline with zero external CDN scripts", html.contains("https://cdn."))
        assertFalse("UI strings must strictly use 练习模式/练习列表/练习伴奏 instead of 练琴", html.contains("练琴"))
        assertTrue("escJs must HTML-escape via esc() to prevent attribute DOM XSS", html.contains("return esc(jsEsc);"))
        assertTrue("SPA must sync ?token= into sessionStorage on load", html.contains("syncUrlToken();"))
    }

    @Test
    fun getItemsSupportsAllVideoUnknownAlbumAndSearchFilters() {
        val allResp = httpRequest("GET", "/api/items?includeFacets=1")
        assertEquals(200, allResp.status)
        val allJson = LanJson.parseObject(allResp.bodyUtf8)
        assertTrue(allJson.bool("ok"))
        assertEquals(2, allJson.int("count"))
        assertNotNull(allJson.arr("albums"))
        assertNotNull(allJson.arr("artists"))

        val videoResp = httpRequest("GET", "/api/items?filter=video")
        assertEquals(200, videoResp.status)
        val videoItems = LanJson.parseObject(videoResp.bodyUtf8).arr("items")!!.items
        assertEquals(1, videoItems.size)
        assertEquals("solo_breakdown_1080p.mp4", (videoItems[0] as JsonValue.Obj).str("fileName"))

        val unknownResp = httpRequest("GET", "/api/items?filter=unknown_album")
        assertEquals(200, unknownResp.status)
        assertEquals(2, LanJson.parseObject(unknownResp.bodyUtf8).int("count"))

        val searchResp = httpRequest("GET", "/api/items?q=constellation")
        assertEquals(200, searchResp.status)
        assertEquals(1, LanJson.parseObject(searchResp.bodyUtf8).int("count"))
    }

    @Test
    fun mkdirFolderRuleAndStreamingUploadsIngestImmediatelyIntoLibrary() {
        // 1. Create subfolder Backing/J-Rock
        val mkdir1 = httpRequest(
            "POST",
            "/api/folders/mkdir",
            jsonBody = """{"rootId":1,"relPath":"","name":"Backing"}""",
        )
        assertEquals(201, mkdir1.status)
        val mkdir2 = httpRequest(
            "POST",
            "/api/folders/mkdir",
            jsonBody = """{"rootId":1,"relPath":"Backing","name":"J-Rock"}""",
        )
        assertEquals(201, mkdir2.status)
        assertTrue(File(tempRootDir, "Backing/J-Rock").isDirectory)

        // 2. Mark Backing/J-Rock as a Practice Folder
        val ruleResp = httpRequest(
            "POST",
            "/api/folders/rule",
            jsonBody = """{"rootId":1,"relPath":"Backing/J-Rock","isPracticeFolder":true}""",
        )
        assertEquals(200, ruleResp.status)
        assertTrue(LanJson.parseObject(ruleResp.bodyUtf8).bool("isPracticeFolder"))

        // 3. Raw octet-stream upload into Backing/J-Rock (using ?fileName= alias)
        val rawAudioPayload = ByteArray(12_345) { (it % 251).toByte() }
        val uploadRawResp = httpRequest(
            method = "POST",
            path = "/api/upload?rootId=1&relPath=Backing%2FJ-Rock&fileName=guitar_backing_track.mp3",
            rawBody = rawAudioPayload,
            headers = mapOf("Content-Type" to "application/octet-stream"),
        )
        assertEquals(201, uploadRawResp.status)
        val uploadedDiskFile = File(tempRootDir, "Backing/J-Rock/guitar_backing_track.mp3")
        assertTrue(uploadedDiskFile.isFile)
        assertArrayEquals(rawAudioPayload, uploadedDiskFile.readBytes())

        // 4. Multipart multi-file upload (.flac + .lrc)
        val boundary = "----MiniPlayerTestBoundary987654"
        val flacBytes = ByteArray(4096) { ((it * 7) and 0xFF).toByte() }
        val lrcBytes = "[00:12.00] Guitar Riff Intro\n".toByteArray(Charsets.UTF_8)
        val multipartPayload = ByteArrayOutputStream().apply {
            write("--$boundary\r\n".toByteArray(Charsets.ISO_8859_1))
            write("Content-Disposition: form-data; name=\"files\"; filename=\"riff_etude.flac\"\r\n".toByteArray(Charsets.UTF_8))
            write("Content-Type: audio/flac\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            write(flacBytes)
            write("\r\n--$boundary\r\n".toByteArray(Charsets.ISO_8859_1))
            write("Content-Disposition: form-data; name=\"files\"; filename=\"riff_etude.lrc\"\r\n".toByteArray(Charsets.UTF_8))
            write("Content-Type: text/plain\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            write(lrcBytes)
            write("\r\n--$boundary--\r\n".toByteArray(Charsets.ISO_8859_1))
        }.toByteArray()

        val uploadMultiResp = httpRequest(
            method = "POST",
            path = "/api/upload?rootId=1&relPath=Backing%2FJ-Rock",
            rawBody = multipartPayload,
            headers = mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"),
        )
        assertEquals(201, uploadMultiResp.status)
        assertArrayEquals(flacBytes, File(tempRootDir, "Backing/J-Rock/riff_etude.flac").readBytes())
        assertArrayEquals(lrcBytes, File(tempRootDir, "Backing/J-Rock/riff_etude.lrc").readBytes())

        // Verify uploaded audio tracks were auto-scanned into MediaLibraryContract with practice folder flag!
        val itemsJson = LanJson.parseObject(httpRequest("GET", "/api/items?filter=folder&rootId=1&relPath=Backing%2FJ-Rock").bodyUtf8)
        assertEquals(2, itemsJson.int("count"))
        val ingestedNames = itemsJson.arr("items")!!.items.map { (it as JsonValue.Obj).str("fileName") }.toSet()
        assertEquals(setOf("guitar_backing_track.mp3", "riff_etude.flac"), ingestedNames)
        val firstIngested = itemsJson.arr("items")!!.items[0] as JsonValue.Obj
        assertTrue(firstIngested.bool("isInPracticeFolder"))

        // 5. Reject path traversal & executable extensions
        val traversalResp = httpRequest(
            method = "POST",
            path = "/api/upload?rootId=1&relPath=..%2Fescape&name=bad.mp3",
            rawBody = ByteArray(16),
            headers = mapOf("Content-Type" to "application/octet-stream"),
        )
        assertEquals(403, traversalResp.status)

        val badExtResp = httpRequest(
            method = "POST",
            path = "/api/upload?rootId=1&relPath=&name=trojan.exe",
            rawBody = ByteArray(16),
            headers = mapOf("Content-Type" to "application/octet-stream"),
        )
        assertEquals(400, badExtResp.status)
    }

    @Test
    fun folderExcludeFromAutoGroupingRuleSerializesAndFiltersAlbumsArtistsAndVideos() {
        // Initially video & album/artist endpoints include items in Tutorials/ and root
        assertEquals(1, LanJson.parseObject(httpRequest("GET", "/api/items?filter=video").bodyUtf8).int("count"))
        assertEquals(1, LanJson.parseObject(httpRequest("GET", "/api/items?filter=albums").bodyUtf8).int("count"))
        assertEquals(1, LanJson.parseObject(httpRequest("GET", "/api/items?filter=artists").bodyUtf8).int("count"))

        // 1. Mark subfolder where video lives (determine its relPath from initial items) as excluded from auto-grouping
        val initialItems = LanJson.parseObject(httpRequest("GET", "/api/items").bodyUtf8).arr("items")!!.items
            .map { it as JsonValue.Obj }
        val videoObj = initialItems.first { it.bool("isVideo") }
        val videoSubRel = videoObj.str("parentRelPath").orEmpty()

        val subRuleResp = httpRequest(
            "POST",
            "/api/folders/rule",
            jsonBody = """{"rootId":1,"relPath":"$videoSubRel","isExcludedFromAutoGrouping":true}""",
        )
        assertEquals(200, subRuleResp.status)
        val subRuleJson = LanJson.parseObject(subRuleResp.bodyUtf8)
        assertTrue(subRuleJson.bool("isExcludedFromAutoGrouping"))
        assertFalse(subRuleJson.bool("isPracticeFolder"))

        // Video item is now excluded from /api/items?filter=video, while still visible in /api/folders
        assertEquals(0, LanJson.parseObject(httpRequest("GET", "/api/items?filter=video").bodyUtf8).int("count"))
        val browseSubJson = LanJson.parseObject(httpRequest("GET", "/api/folders?rootId=1&relPath=$videoSubRel").bodyUtf8)
        assertTrue(browseSubJson.bool("isCurrentExcludedFromAutoGrouping"))
        val browsedVideo = browseSubJson.arr("mediaItems")!!.items.single() as JsonValue.Obj
        assertTrue(browsedVideo.bool("isExcludedFromAutoGrouping"))

        // Browse root shows subfolder with isExcludedFromAutoGrouping = true
        val browseRootJson = LanJson.parseObject(httpRequest("GET", "/api/folders?rootId=1").bodyUtf8)
        val subEntryObj = browseRootJson.arr("subfolders")!!.items
            .map { it as JsonValue.Obj }
            .first { it.str("relPath") == videoSubRel }
        assertTrue(subEntryObj.bool("isExcludedFromAutoGrouping"))

        // 2. Mark root folder as excluded from auto-grouping -> albums & artists filters now return 0 items
        val rootRuleResp = httpRequest(
            "POST",
            "/api/folders/rule",
            jsonBody = """{"rootId":1,"relPath":"","isExcludedFromAutoGrouping":true}""",
        )
        assertEquals(200, rootRuleResp.status)
        assertEquals(0, LanJson.parseObject(httpRequest("GET", "/api/items?filter=albums").bodyUtf8).int("count"))
        assertEquals(0, LanJson.parseObject(httpRequest("GET", "/api/items?filter=artists").bodyUtf8).int("count"))
        val browseRootAfter = LanJson.parseObject(httpRequest("GET", "/api/folders?rootId=1").bodyUtf8)
        assertTrue(browseRootAfter.obj("root")!!.bool("isExcludedFromAutoGrouping"))
        assertTrue(browseRootAfter.bool("isCurrentExcludedFromAutoGrouping"))

        // 3. Unmark root and subfolder -> items reappear in albums, artists, and video
        httpRequest("POST", "/api/folders/rule", jsonBody = """{"rootId":1,"relPath":"","isExcludedFromAutoGrouping":false}""")
        httpRequest("POST", "/api/folders/rule", jsonBody = """{"rootId":1,"relPath":"$videoSubRel","isExcludedFromAutoGrouping":false}""")
        assertEquals(1, LanJson.parseObject(httpRequest("GET", "/api/items?filter=video").bodyUtf8).int("count"))
        assertEquals(1, LanJson.parseObject(httpRequest("GET", "/api/items?filter=albums").bodyUtf8).int("count"))
        assertEquals(1, LanJson.parseObject(httpRequest("GET", "/api/items?filter=artists").bodyUtf8).int("count"))
    }

    @Test
    fun metadataSingleUpdateBatchUpdateAndOnlineAutoMatchPersistCorrectly() {
        val initialItems = LanJson.parseObject(httpRequest("GET", "/api/items").bodyUtf8).arr("items")!!.items
        val firstObj = initialItems[0] as JsonValue.Obj
        val secondObj = initialItems[1] as JsonValue.Obj
        val id1 = firstObj.long("itemId")
        val id2 = secondObj.long("itemId")
        val originalTitle1 = firstObj.str("title")!!

        // 1. Partial single-field inline update (only album; title must NOT be overwritten!)
        val singleResp = httpRequest(
            "POST",
            "/api/metadata/update",
            jsonBody = """{"itemId":$id1,"album":"結束バンド"}""",
        )
        assertEquals(200, singleResp.status)
        val updatedItem1 = LanJson.parseObject(singleResp.bodyUtf8).obj("item")!!
        assertEquals("結束バンド", updatedItem1.str("album"))
        assertEquals(originalTitle1, updatedItem1.str("title"))

        // 2. Batch metadata update across both items
        val batchResp = httpRequest(
            "POST",
            "/api/metadata/batch-update",
            jsonBody = """{"itemIds":[$id1,$id2],"artist":"Kessoku Band","year":2022}""",
        )
        assertEquals(200, batchResp.status)
        assertEquals(2, LanJson.parseObject(batchResp.bodyUtf8).int("updatedCount"))

        val afterBatch = fakeCore.library.allMediaItems.value.associateBy { it.itemId }
        assertEquals("Kessoku Band", afterBatch[id1]?.artist)
        assertEquals(2022, afterBatch[id2]?.year)
        assertEquals("結束バンド", afterBatch[id1]?.album)

        // 3. 1-click online auto-match + cached candidate fast-path (preserving syncedLrc/plainLyrics)
        val previewResp = httpRequest(
            "POST",
            "/api/metadata/auto-match?itemId=$id1",
            jsonBody = """{"itemId":$id1,"previewOnly":true}""",
        )
        assertEquals(200, previewResp.status)
        val previewCand = LanJson.parseObject(previewResp.bodyUtf8).arr("candidates")!!.items.first() as JsonValue.Obj
        assertEquals("[00:10.00] 星座になれたら", previewCand.str("syncedLrc"))
        assertEquals("星座になれたら", previewCand.str("plainLyrics"))

        val autoResp = httpRequest(
            "POST",
            "/api/metadata/auto-match?itemId=$id1",
            jsonBody = LanJson.stringify(
                LanJson.obj(
                    "itemId" to JsonValue.Num(id1),
                    "candidateIndex" to JsonValue.Num(0),
                    "candidate" to previewCand,
                    "previewOnly" to JsonValue.Bool(false),
                ),
            ),
        )
        assertEquals(200, autoResp.status)
        val autoJson = LanJson.parseObject(autoResp.bodyUtf8)
        assertTrue(autoJson.bool("applied"))
        assertEquals("If I Could Be a Constellation", autoJson.obj("item")?.str("title"))
        assertEquals("[00:10.00] 星座になれたら", fakeCore.lastAppliedCandidate?.syncedLrc)
        assertEquals("星座になれたら", fakeCore.lastAppliedCandidate?.plainLyrics)

        // 3b. Active-playback write guard forwards currentlyPlayingItemId
        activePlayingItemId = id1
        val guardedResp = httpRequest(
            "POST",
            "/api/metadata/update",
            jsonBody = """{"itemId":$id1,"title":"12\" Mix <script>alert(1)</script>","writeBackToFile":true}""",
        )
        assertEquals(200, guardedResp.status)
        assertEquals("SAVED_DB_DEFERRED_FILE", LanJson.parseObject(guardedResp.bodyUtf8).str("outcome"))
        activePlayingItemId = null

        // 4. Batch online auto-complete
        val batchAutoResp = httpRequest(
            "POST",
            "/api/metadata/batch-auto-complete",
            jsonBody = """{"itemIds":[$id2],"mode":"auto"}""",
        )
        assertEquals(200, batchAutoResp.status)
        assertEquals(1, LanJson.parseObject(batchAutoResp.bodyUtf8).int("committedCount"))
    }

    @Test
    fun playlistsCreatePracticePlaylistReorderAndRoundTripM3u8() {
        val items = fakeCore.library.allMediaItems.value
        val idA = items[0].itemId
        val idB = items[1].itemId

        // 1. Create a Practice Playlist (🎯 练习列表)
        val createResp = httpRequest(
            "POST",
            "/api/playlists",
            jsonBody = """{"name":"吉他独奏周练","isPracticePlaylist":true,"itemIds":[$idA,$idB]}""",
        )
        assertEquals(201, createResp.status)
        val createJson = LanJson.parseObject(createResp.bodyUtf8)
        val playlistId = createJson.long("playlistId")
        assertTrue(createJson.bool("isPracticePlaylist"))

        // 2. Reorder items ([idB, idA])
        val reorderResp = httpRequest(
            "POST",
            "/api/playlists/items/reorder",
            jsonBody = """{"playlistId":$playlistId,"orderedItemIds":[$idB,$idA]}""",
        )
        assertEquals(200, reorderResp.status)

        val detailJson = LanJson.parseObject(httpRequest("GET", "/api/playlists?id=$playlistId").bodyUtf8)
        val orderedIds = detailJson.obj("playlist")!!.arr("items")!!.items.map { (it as JsonValue.Obj).long("itemId") }
        assertEquals(listOf(idB, idA), orderedIds)

        // 3. Export .m3u8
        val exportResp = httpRequest("GET", "/api/playlists/export?id=$playlistId")
        assertEquals(200, exportResp.status)
        val m3uText = exportResp.bodyUtf8
        assertTrue(m3uText.contains("#EXTM3U"))
        assertTrue(m3uText.contains("#EXT-X-MINIPLAYER-PRACTICE:1"))
        assertTrue(m3uText.contains("constellation_backing.mp3"))

        // 4. Import .m3u8 into a new playlist
        val importBody = LanJson.stringify(
            LanJson.obj(
                "name" to JsonValue.Str("导入复刻练习列表"),
                "content" to JsonValue.Str(m3uText.replace("#PLAYLIST:吉他独奏周练", "#PLAYLIST:导入复刻练习列表")),
            ),
        )
        val importResp = httpRequest("POST", "/api/playlists/import", jsonBody = importBody)
        assertEquals(201, importResp.status)
        val importJson = LanJson.parseObject(importResp.bodyUtf8)
        val importedId = importJson.long("playlistId")
        assertEquals("导入复刻练习列表", importJson.str("playlistName"))
        assertEquals(2, importJson.int("matchedCount"))
        assertTrue(importJson.bool("isPracticePlaylist"))

        // 5. RESTful PUT /api/playlists/{id} and DELETE /api/playlists/{id}
        val putResp = httpRequest(
            "PUT",
            "/api/playlists/$importedId",
            jsonBody = """{"name":"周日舞台排练单","isPracticePlaylist":false,"orderedItemIds":[$idA,$idB]}""",
        )
        assertEquals(200, putResp.status)
        val putPlaylist = LanJson.parseObject(putResp.bodyUtf8).obj("playlist")!!
        assertEquals("周日舞台排练单", putPlaylist.obj("summary")!!.str("name"))
        assertFalse(putPlaylist.obj("summary")!!.bool("isPracticePlaylist"))

        val delPlaylistResp = httpRequest("DELETE", "/api/playlists/$importedId")
        assertEquals(200, delPlaylistResp.status)
        assertEquals(404, httpRequest("GET", "/api/playlists/$importedId").status)
    }

    @Test
    fun practiceStateAndSectionBookmarksPersistAndDeleteCleanly() {
        val itemId = fakeCore.library.allMediaItems.value.first().itemId

        // 1. Save Solo 1 A-B Bookmark
        val bmResp = httpRequest(
            "POST",
            "/api/practice/bookmark",
            jsonBody = """{"itemId":$itemId,"label":"Solo 1","aMs":134000,"bMs":158000,"savedSpeed":0.75}""",
        )
        assertEquals(201, bmResp.status)
        val bmJson = LanJson.parseObject(bmResp.bodyUtf8)
        val createdBm = bmJson.obj("bookmark")!!
        val bookmarkId = createdBm.long("bookmarkId")
        assertEquals("Solo 1", createdBm.str("label"))
        assertEquals(134_000L, createdBm.long("aMs"))
        assertEquals(158_000L, createdBm.long("bMs"))
        assertEquals(0.75f, createdBm.floatOrNull("savedSpeed")!!, 0.001f)

        // 2. Save practice state (speed, pitch semitones, BPM, active loop)
        val stateResp = httpRequest(
            "POST",
            "/api/practice/state",
            jsonBody = """{"itemId":$itemId,"practiceSpeed":0.65,"pitchSemitones":-1,"bpm":138,"countInMode":"BEAT_4","loopEnabled":true,"activeAMs":134000,"activeBMs":158000}""",
        )
        assertEquals(200, stateResp.status)
        val savedState = LanJson.parseObject(stateResp.bodyUtf8).obj("state")!!
        assertEquals(0.65f, savedState.floatOrNull("practiceSpeed")!!, 0.001f)
        assertEquals(-1, savedState.int("pitchSemitones"))
        assertEquals(138, savedState.int("bpm"))
        assertEquals("BEAT_4", savedState.str("countInMode"))

        // 3. Verify via GET /api/practice?itemId=...
        val bundleJson = LanJson.parseObject(httpRequest("GET", "/api/practice?itemId=$itemId").bodyUtf8)
        assertEquals(1, bundleJson.arr("bookmarks")!!.items.size)
        assertEquals(138, bundleJson.obj("state")!!.int("bpm"))

        // 4. Delete bookmark via DELETE /api/practice/bookmark?bookmarkId=...
        val delResp = httpRequest("DELETE", "/api/practice/bookmark?bookmarkId=$bookmarkId")
        assertEquals(200, delResp.status)
        assertEquals(0, fakeCore.getBookmarksSync(itemId).size)
    }

    @Test
    fun streamEndpointSupportsFull200AndHttp206PartialContentByteRanges() {
        val audioItem = fakeCore.library.allMediaItems.value.first { it.fileName == "constellation_backing.mp3" }
        val expectedFull = File(tempRootDir, "constellation_backing.mp3").readBytes()
        assertEquals(131_072, expectedFull.size)

        // 1. Full GET without Range -> 200 OK
        val fullResp = httpRequest("GET", "/api/stream?itemId=${audioItem.itemId}")
        assertEquals(200, fullResp.status)
        assertEquals("bytes", fullResp.headers["accept-ranges"])
        assertArrayEquals(expectedFull, fullResp.bodyBytes)

        // 2. Explicit slice Range: bytes=1024-5119 -> 206 Partial Content
        val sliceResp = httpRequest(
            method = "GET",
            path = "/api/stream?itemId=${audioItem.itemId}",
            headers = mapOf("Range" to "bytes=1024-5119"),
        )
        assertEquals(206, sliceResp.status)
        assertEquals("bytes 1024-5119/131072", sliceResp.headers["content-range"])
        assertEquals("4096", sliceResp.headers["content-length"])
        assertArrayEquals(expectedFull.copyOfRange(1024, 5120), sliceResp.bodyBytes)

        // 3. Open-ended slice Range: bytes=130000- -> 206 Partial Content
        val tailResp = httpRequest(
            method = "GET",
            path = "/api/stream?itemId=${audioItem.itemId}",
            headers = mapOf("Range" to "bytes=130000-"),
        )
        assertEquals(206, tailResp.status)
        assertEquals("bytes 130000-131071/131072", tailResp.headers["content-range"])
        assertArrayEquals(expectedFull.copyOfRange(130000, 131072), tailResp.bodyBytes)

        // 4. Suffix slice Range: bytes=-512 -> 206 Partial Content
        val suffixResp = httpRequest(
            method = "GET",
            path = "/api/stream?itemId=${audioItem.itemId}",
            headers = mapOf("Range" to "bytes=-512"),
        )
        assertEquals(206, suffixResp.status)
        assertEquals("bytes 130560-131071/131072", suffixResp.headers["content-range"])
        assertArrayEquals(expectedFull.copyOfRange(130560, 131072), suffixResp.bodyBytes)

        // 5. Out-of-bounds Range -> 416 Range Not Satisfiable
        val invalidRangeResp = httpRequest(
            method = "GET",
            path = "/api/stream?itemId=${audioItem.itemId}",
            headers = mapOf("Range" to "bytes=200000-200100"),
        )
        assertEquals(416, invalidRangeResp.status)
        assertEquals("bytes */131072", invalidRangeResp.headers["content-range"])

        // 6. Abrupt browser seek socket abort mid-stream does NOT emit server Error or corrupt state
        Socket("127.0.0.1", server.boundPort).use { sock ->
            sock.soTimeout = 3_000
            sock.setSoLinger(true, 0)
            val req = "GET /api/stream?itemId=${audioItem.itemId} HTTP/1.1\r\nHost: 127.0.0.1:${server.boundPort}\r\nRange: bytes=0-\r\n\r\n"
            sock.getOutputStream().write(req.toByteArray(Charsets.UTF_8))
            sock.getOutputStream().flush()
            val buf = ByteArray(256)
            sock.getInputStream().read(buf)
        }
        // Server remains healthy immediately after abrupt client reset
        assertEquals(200, httpRequest("GET", "/api/status").status)
    }

    @Test
    fun rejectsNonLanIpsDnsRebindingCrossOriginAndUnauthorizedSessionPins() {
        // 1. Direct IP firewall matrix verification (IPv4, IPv4-mapped IPv6, IPv6)
        assertTrue(LanStudioServer.isAllowedLanAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(LanStudioServer.isAllowedLanAddress(InetAddress.getByName("10.24.0.9")))
        assertTrue(LanStudioServer.isAllowedLanAddress(InetAddress.getByName("172.16.0.1")))
        assertTrue(LanStudioServer.isAllowedLanAddress(InetAddress.getByName("172.31.255.254")))
        assertTrue(LanStudioServer.isAllowedLanAddress(InetAddress.getByName("192.168.31.100")))
        assertTrue(LanStudioServer.isAllowedLanAddress(InetAddress.getByName("169.254.12.34")))
        assertTrue(LanStudioServer.isAllowedLanAddress(InetAddress.getByName("::1")))
        assertTrue(LanStudioServer.isAllowedLanAddress(InetAddress.getByName("fe80::1")))
        assertTrue(LanStudioServer.isAllowedLanAddress(InetAddress.getByName("fd00::1234")))

        // Must reject public WAN, carrier CGNAT, adjacent non-RFC1918 blocks, multicast, any-local, and mapped WAN
        assertFalse(LanStudioServer.isAllowedLanAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(LanStudioServer.isAllowedLanAddress(InetAddress.getByName("1.1.1.1")))
        assertFalse(LanStudioServer.isAllowedLanAddress(InetAddress.getByName("100.64.0.1")))
        assertFalse(LanStudioServer.isAllowedLanAddress(InetAddress.getByName("172.15.255.255")))
        assertFalse(LanStudioServer.isAllowedLanAddress(InetAddress.getByName("172.32.0.1")))
        assertFalse(LanStudioServer.isAllowedLanAddress(InetAddress.getByName("192.169.1.1")))
        assertFalse(LanStudioServer.isAllowedLanAddress(InetAddress.getByName("0.0.0.0")))
        assertFalse(LanStudioServer.isAllowedLanAddress(InetAddress.getByName("224.0.0.1")))
        assertFalse(LanStudioServer.isAllowedLanAddress(InetAddress.getByName("2001:4860:4860::8888")))
        val mappedWan = InetAddress.getByAddress(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 8, 8, 8, 8),
        )
        assertFalse(LanStudioServer.isAllowedLanAddress(mappedWan))

        // 2. Live socket non-LAN peer rejection test
        val wanBlockedServer = LanStudioServer(
            config = LanServerConfig(
                port = 0,
                bindAddress = "127.0.0.1",
                clientAddressValidator = { false },
            ),
            core = fakeCore,
            storage = storageGateway,
        )
        wanBlockedServer.start()
        try {
            val blockedResp = httpRequest(
                method = "GET",
                path = "/api/items",
                overrideBaseUrl = "http://127.0.0.1:${wanBlockedServer.boundPort}",
            )
            assertEquals(403, blockedResp.status)
            assertEquals(1, wanBlockedServer.rejectedConnections)
            assertEquals(0L, wanBlockedServer.totalRequests)
        } finally {
            wanBlockedServer.stop()
        }

        // 3. Cross-origin CSRF & DNS-rebinding Host protection (raw socket so JDK doesn't strip restricted Origin/Host headers)
        val csrfBody = """{"name":"CSRF","isPracticePlaylist":false}"""
        val csrfStatus = rawSocketStatus(
            port = server.boundPort,
            rawHttp = buildString {
                append("POST /api/playlists HTTP/1.1\r\n")
                append("Host: 127.0.0.1:${server.boundPort}\r\n")
                append("Origin: https://evil.example.com\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${csrfBody.toByteArray(Charsets.UTF_8).size}\r\n")
                append("Connection: close\r\n\r\n")
                append(csrfBody)
            },
        )
        assertEquals(403, csrfStatus)

        // Sandboxed iframe Origin: null must ALSO be rejected with 403 Forbidden
        val nullOriginStatus = rawSocketStatus(
            port = server.boundPort,
            rawHttp = buildString {
                append("POST /api/playlists HTTP/1.1\r\n")
                append("Host: 127.0.0.1:${server.boundPort}\r\n")
                append("Origin: null\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${csrfBody.toByteArray(Charsets.UTF_8).size}\r\n")
                append("Connection: close\r\n\r\n")
                append(csrfBody)
            },
        )
        assertEquals(403, nullOriginStatus)

        val rebindStatus = rawSocketStatus(
            port = server.boundPort,
            rawHttp = "GET /api/items HTTP/1.1\r\nHost: evil-rebind.example.com:8088\r\nConnection: close\r\n\r\n",
        )
        assertEquals(403, rebindStatus)
        assertFalse(LanStudioServer.isAllowedHostAuthority("evil-rebind.example.com:8088"))

        // 4. Optional Session PIN check
        val pinServer = LanStudioServer(
            config = LanServerConfig(
                port = 0,
                bindAddress = "127.0.0.1",
                sessionPin = "4826",
            ),
            core = fakeCore,
            storage = storageGateway,
        )
        pinServer.start()
        try {
            val pinBase = "http://127.0.0.1:${pinServer.boundPort}"
            // Root SPA shell remains reachable so user can type PIN
            assertEquals(200, httpRequest("GET", "/", overrideBaseUrl = pinBase).status)
            // Unauthenticated API call rejected with 401
            assertEquals(401, httpRequest("GET", "/api/items", overrideBaseUrl = pinBase).status)
            // Wrong PIN rejected with 401
            assertEquals(
                401,
                httpRequest(
                    "GET",
                    "/api/items",
                    headers = mapOf("X-Lan-Token" to "9999"),
                    overrideBaseUrl = pinBase,
                ).status,
            )
            // Correct PIN succeeds with 200
            assertEquals(
                200,
                httpRequest(
                    "GET",
                    "/api/items",
                    headers = mapOf("X-Lan-Token" to "4826"),
                    overrideBaseUrl = pinBase,
                ).status,
            )
            // Live in-place PIN rotation / disable without restarting socket
            pinServer.sessionPin = "7711"
            assertEquals(
                401,
                httpRequest(
                    "GET",
                    "/api/items",
                    headers = mapOf("X-Lan-Token" to "4826"),
                    overrideBaseUrl = pinBase,
                ).status,
            )
            assertEquals(
                200,
                httpRequest(
                    "GET",
                    "/api/items",
                    headers = mapOf("X-Lan-Token" to "7711"),
                    overrideBaseUrl = pinBase,
                ).status,
            )
            pinServer.sessionPin = null
            assertEquals(200, httpRequest("GET", "/api/items", overrideBaseUrl = pinBase).status)
        } finally {
            pinServer.stop()
        }
    }

    // ---------------- Test HTTP Client & Fake Core Implementation ----------------

    private data class RawHttpResponse(
        val status: Int,
        val contentType: String,
        val headers: Map<String, String>,
        val bodyBytes: ByteArray,
    ) {
        val bodyUtf8: String
            get() = String(bodyBytes, Charsets.UTF_8)
    }

    private fun httpRequest(
        method: String,
        path: String,
        jsonBody: String? = null,
        rawBody: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        overrideBaseUrl: String? = null,
    ): RawHttpResponse {
        val targetBase = overrideBaseUrl ?: baseUrl
        val conn = (URL("$targetBase$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 5_000
            instanceFollowRedirects = false
            useCaches = false
            for ((k, v) in headers) {
                setRequestProperty(k, v)
            }
        }
        val payloadBytes = rawBody ?: jsonBody?.toByteArray(Charsets.UTF_8)
        if (payloadBytes != null) {
            conn.doOutput = true
            if (jsonBody != null && "Content-Type" !in headers) {
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.setFixedLengthStreamingMode(payloadBytes.size)
            conn.outputStream.use { it.write(payloadBytes) }
        }
        val code = conn.responseCode
        val respHeaders = LinkedHashMap<String, String>()
        for ((k, vals) in conn.headerFields) {
            if (k != null && !vals.isNullOrEmpty()) {
                respHeaders[k.lowercase(Locale.ROOT)] = vals.last()
            }
        }
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val body = stream?.use { it.readBytes() } ?: ByteArray(0)
        return RawHttpResponse(
            status = code,
            contentType = conn.contentType.orEmpty(),
            headers = respHeaders,
            bodyBytes = body,
        )
    }

    private fun rawSocketStatus(port: Int, rawHttp: String): Int {
        Socket("127.0.0.1", port).use { sock ->
            sock.soTimeout = 5_000
            sock.getOutputStream().write(rawHttp.toByteArray(Charsets.UTF_8))
            sock.getOutputStream().flush()
            val responseText = sock.getInputStream().readBytes().toString(Charsets.UTF_8)
            val statusLine = responseText.lineSequence().firstOrNull().orEmpty()
            return statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        }
    }
}

private class FakeTestMiniPlayerCore(
    private val rootId: Long,
    private val rootDir: File,
) : MiniPlayerCoreProvider {

    private val nextItemId = AtomicLong(100L)
    private val nextPlaylistId = AtomicLong(1L)
    private val nextBookmarkId = AtomicLong(1L)

    private val folderPracticeRules = LinkedHashMap<String, Boolean>()
    private val folderExcludeRules = LinkedHashMap<String, Boolean>()
    private val itemsByPath = LinkedHashMap<String, MediaItem>()
    private val practiceStates = LinkedHashMap<Long, PracticeState>()
    private val bookmarksByItem = LinkedHashMap<Long, MutableList<PracticeBookmark>>()
    private val playlistSummaries = LinkedHashMap<Long, PlaylistSummary>()
    private val playlistItemOrders = LinkedHashMap<Long, MutableList<Long>>()

    private val rootsFlow = MutableStateFlow(
        listOf(
            SafRoot(
                rootId = rootId,
                treeUri = rootDir.toURI().toString(),
                treeDocId = "primary:Music",
                displayName = "GuitarLibrary",
                isWritable = true,
            ),
        ),
    )
    private val itemsFlow = MutableStateFlow<List<MediaItem>>(emptyList())
    private val videosFlow = MutableStateFlow<List<MediaItem>>(emptyList())
    private val albumsFlow = MutableStateFlow<List<AlbumSummary>>(emptyList())
    private val artistsFlow = MutableStateFlow<List<ArtistSummary>>(emptyList())
    private val playlistsFlow = MutableStateFlow<List<PlaylistSummary>>(emptyList())
    private val bookmarkedTracksFlow = MutableStateFlow<List<BookmarkedTrackItem>>(emptyList())
    private val settingsFlow = MutableStateFlow(AppSettings())
    private val scanProgressFlow = MutableStateFlow(ScanProgress())

    @Synchronized
    fun scanDiskSync() {
        val supportedMedia = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "mp4", "mkv", "webm", "mov")
        val videoExts = setOf("mp4", "mkv", "webm", "mov")
        val seenKeys = HashSet<String>()

        fun walk(dir: File, relPrefix: String) {
            val kids = dir.listFiles()?.sortedBy { it.name }.orEmpty()
            for (f in kids) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    val childRel = if (relPrefix.isEmpty()) f.name else "$relPrefix/${f.name}"
                    walk(f, childRel)
                } else {
                    val ext = f.extension.lowercase(Locale.ROOT)
                    if (ext !in supportedMedia) continue
                    val key = if (relPrefix.isEmpty()) f.name else "$relPrefix/${f.name}"
                    seenKeys.add(key)
                    val inPractice = isPracticeRelPathSync(relPrefix)
                    val inExcluded = isExcludedRelPathSync(relPrefix)
                    val existing = itemsByPath[key]
                    if (existing != null) {
                        itemsByPath[key] = existing.copy(
                            sizeBytes = f.length(),
                            lastModified = f.lastModified(),
                            isInPracticeFolder = inPractice,
                            isExcludedFromAutoGrouping = inExcluded,
                        )
                    } else {
                        val id = nextItemId.incrementAndGet()
                        val isVid = ext in videoExts
                        itemsByPath[key] = MediaItem(
                            itemId = id,
                            documentUri = f.toURI().toString(),
                            rootId = rootId,
                            parentRelPath = relPrefix,
                            fileName = f.name,
                            mimeType = LanPathUtils.guessMimeType(f.name),
                            isVideo = isVid,
                            sizeBytes = f.length(),
                            lastModified = f.lastModified(),
                            durationMs = 240_000L,
                            width = if (isVid) 1920 else 0,
                            height = if (isVid) 1080 else 0,
                            title = f.nameWithoutExtension,
                            artist = "未知歌手",
                            album = null,
                            isBackingTrack = f.name.contains("backing", ignoreCase = true),
                            isRootWritable = true,
                            isInPracticeFolder = inPractice,
                            isExcludedFromAutoGrouping = inExcluded,
                        )
                    }
                }
            }
        }
        walk(rootDir, "")
        itemsByPath.keys.retainAll(seenKeys)
        refreshDerivedFlows()
    }

    private fun isPracticeRelPathSync(relPath: String): Boolean {
        var cur = relPath
        while (true) {
            folderPracticeRules[cur]?.let { return it }
            if (cur.isEmpty()) break
            cur = cur.substringBeforeLast('/', "")
        }
        return false
    }

    private fun isExcludedRelPathSync(relPath: String): Boolean {
        var cur = relPath
        while (true) {
            folderExcludeRules[cur]?.let { return it }
            if (cur.isEmpty()) break
            cur = cur.substringBeforeLast('/', "")
        }
        return false
    }

    @Synchronized
    private fun refreshDerivedFlows() {
        val list = itemsByPath.values.map { item ->
            val bms = bookmarksByItem[item.itemId].orEmpty()
            val pState = practiceStates[item.itemId]
            item.copy(
                isInPracticeFolder = isPracticeRelPathSync(item.parentRelPath),
                isExcludedFromAutoGrouping = isExcludedRelPathSync(item.parentRelPath),
                bookmarkCount = bms.size,
                hasPracticeRecord = bms.isNotEmpty() || (pState?.isNonDefault == true),
                savedPracticeSpeed = pState?.practiceSpeed ?: 1.0f,
                savedSemitones = pState?.pitchSemitones ?: 0,
            )
        }
        val groupableList = list.filter { !it.isInPracticeFolder && !it.isExcludedFromAutoGrouping }
        itemsFlow.value = list
        videosFlow.value = groupableList.filter { it.isVideo }
        rootsFlow.value = rootsFlow.value.map {
            it.copy(
                itemCount = list.size,
                isPracticeFolder = isPracticeRelPathSync(""),
                isExcludedFromAutoGrouping = isExcludedRelPathSync(""),
            )
        }
        albumsFlow.value = groupableList.groupBy { it.album?.takeIf(String::isNotBlank) ?: AlbumSummary.UNKNOWN_ALBUM_KEY }
            .map { (key, tracks) ->
                val isUnk = key == AlbumSummary.UNKNOWN_ALBUM_KEY
                AlbumSummary(
                    albumKey = key,
                    title = if (isUnk) AlbumSummary.UNKNOWN_ALBUM_TITLE else key,
                    artist = tracks.first().artist,
                    trackCount = tracks.size,
                    isUnknownAlbum = isUnk,
                )
            }
        artistsFlow.value = groupableList.groupBy { it.artist.lowercase(Locale.ROOT) }
            .map { (norm, tracks) ->
                ArtistSummary(
                    artistNorm = norm,
                    displayName = tracks.first().artist,
                    albumCount = 1,
                    trackCount = tracks.size,
                )
            }
        playlistsFlow.value = playlistSummaries.values.map { sum ->
            val ids = playlistItemOrders[sum.playlistId].orEmpty()
            sum.copy(itemCount = ids.size)
        }
        bookmarkedTracksFlow.value = list.mapNotNull { item ->
            val bms = bookmarksByItem[item.itemId].orEmpty()
            if (bms.isEmpty()) null else BookmarkedTrackItem(item, bms.toList())
        }
    }

    override val library = object : MediaLibraryContract {
        override val safRoots = rootsFlow
        override val allMediaItems = itemsFlow
        override val videoItems = videosFlow
        override val albums = albumsFlow
        override val artists = artistsFlow
        override val playlists = playlistsFlow
        override val bookmarkedTracks = bookmarkedTracksFlow
        override val settings = settingsFlow

        override suspend fun browseFolder(rootId: Long, relPath: String, flat: Boolean): FolderBrowseState? = null

        override suspend fun getAlbumTracks(albumKey: String): List<MediaItem> =
            itemsFlow.value.filter {
                !it.isInPracticeFolder && !it.isExcludedFromAutoGrouping &&
                    (if (albumKey == AlbumSummary.UNKNOWN_ALBUM_KEY) it.album.isNullOrBlank() else it.album == albumKey)
            }

        override suspend fun getArtistDetail(artistNorm: String): ArtistDetail? {
            val summary = artistsFlow.value.find { it.artistNorm == artistNorm } ?: return null
            val tracks = itemsFlow.value.filter {
                !it.isInPracticeFolder && !it.isExcludedFromAutoGrouping &&
                    it.artist.lowercase(Locale.ROOT) == artistNorm
            }
            return ArtistDetail(summary, emptyList(), tracks)
        }

        override suspend fun getPlaylistDetail(playlistId: Long): PlaylistDetail? = synchronized(this@FakeTestMiniPlayerCore) {
            val sum = playlistsFlow.value.find { it.playlistId == playlistId } ?: return null
            val byId = itemsFlow.value.associateBy { it.itemId }
            val ordered = playlistItemOrders[playlistId].orEmpty().mapNotNull { byId[it] }
            PlaylistDetail(sum, ordered)
        }

        override suspend fun isPracticeFolder(rootId: Long, relPath: String): Boolean =
            synchronized(this@FakeTestMiniPlayerCore) { isPracticeRelPathSync(relPath) }

        override suspend fun isExcludedFromAutoGrouping(rootId: Long, relPath: String): Boolean =
            synchronized(this@FakeTestMiniPlayerCore) { isExcludedRelPathSync(relPath) }

        override suspend fun createPlaylist(name: String, isPracticePlaylist: Boolean): Long =
            synchronized(this@FakeTestMiniPlayerCore) {
                val id = nextPlaylistId.getAndIncrement()
                playlistSummaries[id] = PlaylistSummary(
                    playlistId = id,
                    name = name,
                    isPracticePlaylist = isPracticePlaylist,
                    itemCount = 0,
                    updatedAt = System.currentTimeMillis(),
                )
                playlistItemOrders[id] = ArrayList()
                refreshDerivedFlows()
                id
            }

        override suspend fun renamePlaylist(playlistId: Long, newName: String) =
            synchronized(this@FakeTestMiniPlayerCore) {
                playlistSummaries[playlistId]?.let {
                    playlistSummaries[playlistId] = it.copy(name = newName)
                    refreshDerivedFlows()
                }
                Unit
            }

        override suspend fun setPlaylistPracticeMode(playlistId: Long, isPracticePlaylist: Boolean) =
            synchronized(this@FakeTestMiniPlayerCore) {
                playlistSummaries[playlistId]?.let {
                    playlistSummaries[playlistId] = it.copy(isPracticePlaylist = isPracticePlaylist)
                    refreshDerivedFlows()
                }
                Unit
            }

        override suspend fun deletePlaylist(playlistId: Long) =
            synchronized(this@FakeTestMiniPlayerCore) {
                playlistSummaries.remove(playlistId)
                playlistItemOrders.remove(playlistId)
                refreshDerivedFlows()
            }

        override suspend fun addItemsToPlaylist(playlistId: Long, itemIds: List<Long>) =
            synchronized(this@FakeTestMiniPlayerCore) {
                val list = playlistItemOrders.getOrPut(playlistId) { ArrayList() }
                for (id in itemIds) {
                    if (id !in list) list.add(id)
                }
                refreshDerivedFlows()
            }

        override suspend fun removeItemFromPlaylist(playlistId: Long, itemId: Long) =
            synchronized(this@FakeTestMiniPlayerCore) {
                playlistItemOrders[playlistId]?.remove(itemId)
                refreshDerivedFlows()
            }

        override suspend fun reorderPlaylistItems(playlistId: Long, orderedItemIds: List<Long>) =
            synchronized(this@FakeTestMiniPlayerCore) {
                playlistItemOrders[playlistId] = ArrayList(orderedItemIds)
                refreshDerivedFlows()
            }

        override suspend fun getPracticeState(itemId: Long): PracticeState =
            synchronized(this@FakeTestMiniPlayerCore) { practiceStates[itemId] ?: PracticeState(itemId = itemId) }

        override fun observeBookmarks(itemId: Long): Flow<List<PracticeBookmark>> =
            bookmarkedTracksFlow.map { list -> list.find { it.mediaItem.itemId == itemId }?.bookmarks.orEmpty() }

        override suspend fun getBookmarks(itemId: Long): List<PracticeBookmark> =
            this@FakeTestMiniPlayerCore.getBookmarksSync(itemId)

        override suspend fun savePracticeState(state: PracticeState) =
            synchronized(this@FakeTestMiniPlayerCore) {
                practiceStates[state.itemId] = state
                refreshDerivedFlows()
            }

        override suspend fun saveBookmark(
            itemId: Long,
            label: String,
            aMs: Long,
            bMs: Long,
            savedSpeed: Float?,
            bookmarkId: Long?,
        ): PracticeBookmark = synchronized(this@FakeTestMiniPlayerCore) {
            val id = bookmarkId ?: nextBookmarkId.getAndIncrement()
            val bm = PracticeBookmark(
                bookmarkId = id,
                itemId = itemId,
                label = label,
                aMs = aMs,
                bMs = bMs,
                savedSpeed = savedSpeed,
                createdAt = System.currentTimeMillis(),
            )
            val list = bookmarksByItem.getOrPut(itemId) { ArrayList() }
            list.removeAll { it.bookmarkId == id }
            list.add(bm)
            list.sortBy { it.aMs }
            refreshDerivedFlows()
            bm
        }

        override suspend fun deleteBookmark(bookmarkId: Long) =
            synchronized(this@FakeTestMiniPlayerCore) {
                for (list in bookmarksByItem.values) {
                    list.removeAll { it.bookmarkId == bookmarkId }
                }
                refreshDerivedFlows()
            }

        override suspend fun savePlaybackPosition(itemId: Long, positionMs: Long) {}
        override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
            settingsFlow.value = transform(settingsFlow.value)
        }

        override suspend fun clearCaches(): Long = 0L
    }

    fun getBookmarksSync(itemId: Long): List<PracticeBookmark> =
        synchronized(this) { bookmarksByItem[itemId]?.toList().orEmpty() }

    @Volatile
    var lastAppliedCandidate: MetadataCandidate? = null

    override val scanner: FolderScannerContract = object : FolderScannerContract {
        override val scanProgress = scanProgressFlow
        override suspend fun addSafRoot(treeUri: Uri): AddRootResult = AddRootResult.Added(rootsFlow.value.first())
        override suspend fun rescanAll(incremental: Boolean) = scanDiskSync()
        override suspend fun rescanRoot(rootId: Long, incremental: Boolean) = scanDiskSync()
        override suspend fun removeSafRoot(rootId: Long) {}
        override suspend fun setFolderPracticeRule(
            rootId: Long,
            relPath: String,
            folderUri: String,
            isPracticeFolder: Boolean,
        ) = synchronized(this@FakeTestMiniPlayerCore) {
            folderPracticeRules[relPath] = isPracticeFolder
            refreshDerivedFlows()
        }

        override suspend fun setFolderExcludeFromGroupingRule(
            rootId: Long,
            relPath: String,
            folderUri: String,
            isExcludedFromAutoGrouping: Boolean,
        ) = synchronized(this@FakeTestMiniPlayerCore) {
            folderExcludeRules[relPath] = isExcludedFromAutoGrouping
            refreshDerivedFlows()
        }

        override suspend fun setFolderNomediaRule(
            rootId: Long,
            relPath: String,
            folderUri: String,
            respectNomedia: Boolean?,
        ) {}
    }

    override val metadata: MetadataEngineContract = object : MetadataEngineContract {
        override suspend fun saveMetadata(
            itemIds: List<Long>,
            draft: MetadataDraft,
            currentlyPlayingItemId: Long?,
        ): MetadataSaveOutcome = synchronized(this@FakeTestMiniPlayerCore) {
            val targetSet = itemIds.toSet()
            for ((pathKey, item) in itemsByPath.entries) {
                if (item.itemId !in targetSet) continue
                itemsByPath[pathKey] = item.copy(
                    title = if (MetadataField.TITLE in draft.dirtyFields) (draft.title ?: item.title) else item.title,
                    artist = if (MetadataField.ARTIST in draft.dirtyFields) (draft.artist ?: item.artist) else item.artist,
                    album = if (MetadataField.ALBUM in draft.dirtyFields) draft.album else item.album,
                    albumArtist = if (MetadataField.ALBUM_ARTIST in draft.dirtyFields) draft.albumArtist else item.albumArtist,
                    trackNumber = if (MetadataField.TRACK_NUMBER in draft.dirtyFields) draft.trackNumber else item.trackNumber,
                    year = if (MetadataField.YEAR in draft.dirtyFields) draft.year else item.year,
                    hasMetadataOverride = true,
                )
            }
            refreshDerivedFlows()
            if (draft.writeBackToFile && currentlyPlayingItemId != null && currentlyPlayingItemId in targetSet) {
                MetadataSaveOutcome.SAVED_DB_DEFERRED_FILE
            } else {
                MetadataSaveOutcome.SAVED_DB_ONLY
            }
        }

        override suspend fun resetMetadataOverride(itemIds: List<Long>) {}

        override suspend fun searchOnlineCandidates(
            item: MediaItem,
            queryTitleOverride: String?,
            queryArtistOverride: String?,
        ): List<MetadataCandidate> = listOf(
            MetadataCandidate(
                source = OnlineSource.MUSICBRAINZ,
                title = "If I Could Be a Constellation",
                artist = "Kessoku Band",
                album = "Kessoku Band",
                year = 2022,
                trackNumber = 11,
                syncedLrc = "[00:10.00] 星座になれたら",
                plainLyrics = "星座になれたら",
                confidenceScore = 95,
            ),
        )

        override suspend fun applyCandidate(
            itemId: Long,
            candidate: MetadataCandidate,
            preserveBackingSuffix: Boolean,
            writeBackToFile: Boolean,
            currentlyPlayingItemId: Long?,
        ): MetadataSaveOutcome {
            lastAppliedCandidate = candidate
            return saveMetadata(
                itemIds = listOf(itemId),
                draft = MetadataDraft(
                    title = candidate.title,
                    artist = candidate.artist,
                    album = candidate.album,
                    year = candidate.year,
                    trackNumber = candidate.trackNumber,
                    dirtyFields = setOf(
                        MetadataField.TITLE,
                        MetadataField.ARTIST,
                        MetadataField.ALBUM,
                        MetadataField.YEAR,
                        MetadataField.TRACK_NUMBER,
                    ),
                    writeBackToFile = writeBackToFile,
                ),
                currentlyPlayingItemId = currentlyPlayingItemId,
            )
        }

        override suspend fun previewBatchCompletion(
            items: List<MediaItem>,
            onProgress: (completed: Int, total: Int) -> Unit,
        ): List<BatchMatchItem> = items.map { item ->
            BatchMatchItem(
                mediaItem = item,
                cleanedTitle = item.title,
                candidates = listOf(
                    MetadataCandidate(
                        source = OnlineSource.ITUNES,
                        title = "${item.title} (Remastered)",
                        artist = "Kessoku Band",
                        album = "Guitar Hero Vol. 1",
                        year = 2024,
                        confidenceScore = 92,
                    ),
                ),
            )
        }

        override suspend fun commitBatchCompletion(
            matches: List<BatchMatchItem>,
            writeBackToFile: Boolean,
            currentlyPlayingItemId: Long?,
        ): Int {
            var count = 0
            for (m in matches) {
                val cand = m.candidates.getOrNull(m.selectedCandidateIndex) ?: continue
                applyCandidate(m.mediaItem.itemId, cand, m.preserveBackingSuffix, writeBackToFile)
                count++
            }
            return count
        }

        override suspend fun getLyrics(item: MediaItem, fetchOnlineIfMissing: Boolean): LyricsData? = null
        override suspend fun exportPlaylistM3u8(playlistId: Long, outputUri: Uri): Boolean = false
        override suspend fun importPlaylistM3u8(inputUri: Uri, fallbackName: String): M3uImportResult =
            M3uImportResult(0L, fallbackName, 0, 0, false)
    }

    override val updater: AppUpdateContract = object : AppUpdateContract {
        override suspend fun checkForUpdates(includePreRelease: Boolean): UpdateInfo =
            UpdateInfo("1.0.0", "v1.0.0", "", "", "", "", 0L, null, false)

        override suspend fun downloadAndVerifyUpdate(
            updateInfo: UpdateInfo,
            onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        ): File = File(rootDir, "noop.apk")

        override fun buildInstallIntent(apkFile: File): Intent = Intent()
        override fun canRequestPackageInstalls(): Boolean = true
    }
}
