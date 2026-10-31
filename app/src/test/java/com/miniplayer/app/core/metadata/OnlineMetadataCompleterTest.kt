package com.miniplayer.app.core.metadata

import com.miniplayer.app.core.db.InMemoryMediaStoreBackend
import com.miniplayer.app.core.db.MediaItemRow
import com.miniplayer.app.core.db.MediaRepository
import com.miniplayer.app.core.model.LyricsSource
import com.miniplayer.app.core.model.MediaItem
import com.miniplayer.app.core.model.MetadataSaveOutcome
import com.miniplayer.app.core.model.MountState
import com.miniplayer.app.core.model.OnlineSource
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineMetadataCompleterTest {

    private data class RecordedRequest(
        val url: String,
        val headers: Map<String, String>,
        val headOnly: Boolean,
        val timestampMs: Long,
    )

    private class FakeOnlineHttpClient(
        private val handler: suspend (url: String, headers: Map<String, String>, headOnly: Boolean) -> RawHttpResponse,
    ) : OnlineHttpClient {
        val requests = CopyOnWriteArrayList<RecordedRequest>()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
            maxBytes: Int,
            headOnly: Boolean,
            totalTimeoutMs: Long,
        ): RawHttpResponse {
            requests.add(RecordedRequest(url, headers, headOnly, System.currentTimeMillis()))
            return handler(url, headers, headOnly)
        }
    }

    @Test
    fun `backing track search strips backing suffix for query and preserves suffix on apply`() = runBlocking {
        val fakeHttp = FakeOnlineHttpClient { url, _, headOnly ->
            when {
                url.contains("musicbrainz.org/ws/2/recording") -> RawHttpResponse(
                    statusCode = 200,
                    finalUrl = url,
                    contentType = "application/json",
                    body = """
                        {
                          "recordings": [
                            {
                              "id": "rec-hotel",
                              "title": "Hotel California",
                              "length": 391000,
                              "artist-credit": [{"name": "Eagles"}],
                              "releases": [
                                {
                                  "id": "rel-mbid-hc",
                                  "title": "Hotel California",
                                  "status": "Official",
                                  "date": "1976-12-08",
                                  "media": [{"track": [{"number": "1"}]}]
                                }
                              ]
                            }
                          ]
                        }
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                )
                url.contains("coverartarchive.org/release/rel-mbid-hc/front-500") && headOnly -> RawHttpResponse(
                    statusCode = 200,
                    finalUrl = "https://archive.org/download/mbid-rel-mbid-hc/front-500.jpg",
                    contentType = "image/jpeg",
                )
                url.contains("archive.org/download/mbid-rel-mbid-hc/front-500.jpg") -> RawHttpResponse(
                    statusCode = 200,
                    finalUrl = url,
                    contentType = "image/jpeg",
                    body = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4),
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
                          "syncedLyrics": "[00:14.50]On a dark desert highway\n[00:21.10]Cool wind in my hair",
                          "plainLyrics": "On a dark desert highway\nCool wind in my hair"
                        }
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                )
                else -> RawHttpResponse(statusCode = 404, finalUrl = url)
            }
        }

        val backend = InMemoryMediaStoreBackend()
        val rootId = backend.insertSafRoot(
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3ABacking",
            treeDocId = "primary:Backing",
            displayName = "Backing",
            isWritable = false,
            mountState = MountState.MOUNTED,
        )
        val fileName = "[FLAC] 01 - Eagles - Hotel California (无吉他伴奏).flac"
        val parsed = SmartFilenameParser.parse("Rock", fileName)
        backend.reconcileRootScan(
            rootId = rootId,
            isWritable = false,
            scannedAtMs = 1_000L,
            scannedFolders = emptyList(),
            unchangedCoverUpdates = emptyMap(),
            movedItems = emptyList(),
            upsertedItems = listOf(
                MediaItemRow(
                    documentUri = "content://doc/backing1",
                    rootId = rootId,
                    parentRelPath = "Rock",
                    fileName = fileName,
                    mimeType = "audio/flac",
                    isVideo = false,
                    sizeBytes = 24_000_000L,
                    lastModified = 1_000L,
                    durationMs = 398_000L, // +7s count-in vs studio recording
                    parsedTitle = parsed.cleanedTitle,
                    parsedArtist = parsed.artist,
                    parsedAlbum = null,
                    isBackingTrack = parsed.isBackingTrack,
                    fingerprintKey = MediaRepository.buildFingerprintKey(fileName, 24_000_000L, 398_000L),
                ),
            ),
            deletedItemIds = emptySet(),
            lyricsUpdates = emptyList(),
            artistSplitter = { a, aa -> ArtistSplitter.split(a, aa) },
        )

        val tempDir = Files.createTempDirectory("mp_online_test").toFile()
        try {
            val repo = MediaRepository(backend = backend, filesRootDir = tempDir)
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
            )

            val item = repo.allMediaItems.value.single()
            assertTrue(item.isBackingTrack)

            val candidates = engine.searchOnlineCandidates(item, null, null)
            assertTrue(candidates.isNotEmpty())
            val top = candidates.first()
            assertEquals(OnlineSource.MUSICBRAINZ, top.source)
            assertEquals("Hotel California", top.title)
            assertEquals("Eagles", top.artist)
            assertEquals("https://archive.org/download/mbid-rel-mbid-hc/front-500.jpg", top.coverUrl)
            assertTrue(top.confidenceScore >= 85)
            assertNotNull(top.syncedLrc)

            // Verify MusicBrainz received clean query WITHOUT "(无吉他伴奏)" or "[FLAC]" AND mandatory User-Agent
            val mbReq = fakeHttp.requests.first { it.url.contains("musicbrainz.org") }
            val decodedUrl = URLDecoder.decode(mbReq.url, "UTF-8")
            assertTrue(decodedUrl.contains("recording:\"Hotel California\""))
            assertFalse(decodedUrl.contains("伴奏"))
            assertFalse(decodedUrl.contains("FLAC"))
            assertEquals(MusicBrainzClient.USER_AGENT, mbReq.headers["User-Agent"])

            // Apply candidate with preserveBackingSuffix = true -> retains "(无吉他伴奏)" in DB override!
            val outcome = engine.applyCandidate(
                itemId = item.itemId,
                candidate = top,
                preserveBackingSuffix = true,
                writeBackToFile = false,
                currentlyPlayingItemId = null,
            )
            assertEquals(MetadataSaveOutcome.SAVED_DB_ONLY, outcome)

            val updatedItem = repo.allMediaItems.value.single()
            assertEquals("Hotel California (无吉他伴奏)", updatedItem.title)
            assertEquals("Eagles", updatedItem.artist)
            assertEquals("Hotel California", updatedItem.album)
            assertEquals(1976, updatedItem.year)
            assertNotNull(updatedItem.coverPathOrUri)
            assertTrue(File(updatedItem.coverPathOrUri!!).exists())

            val lyrics = engine.getLyrics(updatedItem, fetchOnlineIfMissing = false)
            assertNotNull(lyrics)
            assertEquals(LyricsSource.LRCLIB, lyrics!!.source)
            assertEquals(2, lyrics.lines.size)
            assertEquals(14_500L, lyrics.lines[0].timeMs)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `cover art archive slow timeout races cleanly to iTunes 1000x1000bb CDN fallback`() = runBlocking {
        val fakeHttp = FakeOnlineHttpClient { url, _, _ ->
            when {
                url.contains("musicbrainz.org/ws/2/recording") -> RawHttpResponse(
                    statusCode = 200,
                    finalUrl = url,
                    contentType = "application/json",
                    body = """
                        {
                          "recordings": [
                            {
                              "id": "rec-1",
                              "title": "Neon",
                              "length": 262000,
                              "artist-credit": [{"name": "John Mayer"}],
                              "releases": [
                                {
                                  "id": "slow-caa-mbid",
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
                url.contains("coverartarchive.org") -> {
                    // Simulate hung CoverArtArchive endpoint exceeding timeoutMs
                    delay(1_500L)
                    RawHttpResponse(statusCode = 200, finalUrl = "https://archive.org/never-reached.jpg")
                }
                url.contains("itunes.apple.com/search") -> RawHttpResponse(
                    statusCode = 200,
                    finalUrl = url,
                    contentType = "application/json",
                    body = """
                        {
                          "resultCount": 1,
                          "results": [
                            {
                              "trackName": "Neon",
                              "artistName": "John Mayer",
                              "collectionName": "Room for Squares",
                              "trackNumber": 4,
                              "releaseDate": "2001-06-05T07:00:00Z",
                              "trackTimeMillis": 262100,
                              "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Music125/v4/aa/bb/100x100bb.jpg"
                            }
                          ]
                        }
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                )
                else -> RawHttpResponse(statusCode = 404, finalUrl = url)
            }
        }

        // Verify production default is strictly 3,000 ms (`DESIGN.md` §6.4 #2)
        assertEquals(3_000L, CoverArtArchiveClient.STRICT_CAA_TIMEOUT_MS)
        assertEquals(3_000L, CoverArtArchiveClient(fakeHttp).timeoutMs)

        // Use 200ms timeout in unit test to prove deterministic timeout cancellation & fallback without slowing suite
        val completer = OnlineMetadataCompleter(
            http = fakeHttp,
            musicBrainz = MusicBrainzClient(fakeHttp, minIntervalMs = 0L),
            coverArtArchive = CoverArtArchiveClient(fakeHttp, timeoutMs = 200L),
            itunes = ItunesSearchClient(fakeHttp),
            lrclib = LrclibClient(fakeHttp),
        )

        val item = sampleItem(
            id = 1L,
            fileName = "04 - John Mayer - Neon.mp3",
            title = "Neon",
            artist = "John Mayer",
            durationMs = 262_000L,
        )

        val startMs = System.currentTimeMillis()
        val candidates = completer.searchCandidates(item)
        val elapsedMs = System.currentTimeMillis() - startMs

        assertTrue("CAA timeout should abort well before 1.5s hang (took ${elapsedMs}ms)", elapsedMs < 1_000L)
        val mbCand = candidates.first { it.source == OnlineSource.MUSICBRAINZ }
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music125/v4/aa/bb/1000x1000bb.jpg",
            mbCand.coverUrl,
        )
    }

    @Test
    fun `musicBrainz rate limiter enforces minimum interval and batch preview auto-checks 85 percent threshold`() = runBlocking {
        val fakeHttp = FakeOnlineHttpClient { url, _, _ ->
            when {
                url.contains("musicbrainz.org/ws/2/recording") -> {
                    val decoded = URLDecoder.decode(url, "UTF-8")
                    if (decoded.contains("Autumn Leaves")) {
                        RawHttpResponse(
                            statusCode = 200,
                            finalUrl = url,
                            contentType = "application/json",
                            body = """
                                {
                                  "recordings": [
                                    {
                                      "id": "rec-autumn",
                                      "title": "Autumn Leaves",
                                      "length": 310000,
                                      "artist-credit": [{"name": "Bill Evans Trio"}],
                                      "releases": [{"id": "rel-1", "title": "Portrait in Jazz", "status": "Official", "date": "1960", "media": [{"track": [{"number": "2"}]}]}]
                                    }
                                  ]
                                }
                            """.trimIndent().toByteArray(Charsets.UTF_8),
                        )
                    } else {
                        RawHttpResponse(
                            statusCode = 200,
                            finalUrl = url,
                            contentType = "application/json",
                            body = """
                                {
                                  "recordings": [
                                    {
                                      "id": "rec-mismatch",
                                      "title": "Completely Different Song Name XYZ",
                                      "length": 95000,
                                      "artist-credit": [{"name": "Other Artist"}],
                                      "releases": []
                                    }
                                  ]
                                }
                            """.trimIndent().toByteArray(Charsets.UTF_8),
                        )
                    }
                }
                else -> RawHttpResponse(statusCode = 404, finalUrl = url)
            }
        }

        val mbClient = MusicBrainzClient(fakeHttp, minIntervalMs = 120L)
        mbClient.searchRecordings("Autumn Leaves", "Bill Evans Trio")
        mbClient.searchRecordings("Mystery Jam", null)

        val mbRequests = fakeHttp.requests.filter { it.url.contains("musicbrainz.org") }
        assertEquals(2, mbRequests.size)
        val delta = mbRequests[1].timestampMs - mbRequests[0].timestampMs
        assertTrue("Expected rate-limit spacing >= 100ms, got ${delta}ms", delta >= 100L)

        // Batch preview: high-confidence item (>=85%) auto-selected (0), low-confidence (<85%) left unchecked (-1)
        val completer = OnlineMetadataCompleter(
            http = fakeHttp,
            musicBrainz = MusicBrainzClient(fakeHttp, minIntervalMs = 0L),
            coverArtArchive = CoverArtArchiveClient(fakeHttp, timeoutMs = 100L),
            itunes = ItunesSearchClient(fakeHttp),
            lrclib = LrclibClient(fakeHttp),
        )

        val highConfidenceItem = sampleItem(
            id = 10L,
            fileName = "02 - Bill Evans Trio - Autumn Leaves.flac",
            title = "Autumn Leaves",
            artist = "Bill Evans Trio",
            durationMs = 310_500L,
        )
        val lowConfidenceItem = sampleItem(
            id = 11L,
            fileName = "jam_take_03.wav",
            title = "jam take 03",
            artist = ArtistSplitter.UNKNOWN_ARTIST_DISPLAY,
            durationMs = 240_000L,
        )

        val progressEvents = ArrayList<Pair<Int, Int>>()
        val batch = completer.previewBatch(listOf(highConfidenceItem, lowConfidenceItem)) { done, total ->
            progressEvents.add(done to total)
        }

        assertEquals(2, batch.size)
        assertEquals(listOf(1 to 2, 2 to 2), progressEvents)
        assertTrue(batch[0].candidates.first().confidenceScore >= 85)
        assertEquals(0, batch[0].selectedCandidateIndex)
        assertTrue(batch[1].candidates.first().confidenceScore < 85)
        assertEquals(-1, batch[1].selectedCandidateIndex)
    }

    @Test
    fun `urlConnectionOnlineHttpClient blocks plain http by default`() = runBlocking {
        val strictClient = UrlConnectionOnlineHttpClient(allowInsecureLoopback = false)
        val caught = runCatching {
            strictClient.get("http://127.0.0.1:9999/test")
        }.exceptionOrNull()
        assertNotNull(caught)
        assertTrue(caught!!.message.orEmpty().contains("non-HTTPS"))
        assertNull(ItunesSearchClient.upgradeArtworkTo1000x1000("   "))
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/a/b/1000x1000bb.jpg",
            ItunesSearchClient.upgradeArtworkTo1000x1000("https://is1-ssl.mzstatic.com/image/thumb/a/b/60x60bb.jpg"),
        )

        // 1. Trusted `http://*.archive.org` redirect from `https://coverartarchive.org` auto-upgrades to `https://`
        val visitedUrls = CopyOnWriteArrayList<String>()
        val archiveRedirectClient = UrlConnectionOnlineHttpClient(
            allowInsecureLoopback = false,
            openConnection = { url ->
                visitedUrls.add(url.toString())
                object : java.net.HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy(): Boolean = false
                    override fun getResponseCode(): Int =
                        if (url.host == "coverartarchive.org") 307 else 200
                    override fun getHeaderField(name: String?): String? =
                        if (url.host == "coverartarchive.org" && name.equals("Location", ignoreCase = true)) {
                            "http://ia800500.us.archive.org/12/items/mbid-abc/front-500.jpg"
                        } else {
                            null
                        }
                }
            },
        )
        val caaResolved = CoverArtArchiveClient(archiveRedirectClient, timeoutMs = 1_000L)
            .resolveFrontCoverUrl("mbid-abc")
        assertEquals("https://ia800500.us.archive.org/12/items/mbid-abc/front-500.jpg", caaResolved)
        assertEquals(
            listOf(
                "https://coverartarchive.org/release/mbid-abc/front-500",
                "https://ia800500.us.archive.org/12/items/mbid-abc/front-500.jpg",
            ),
            visitedUrls.toList(),
        )

        // 2. Untrusted `https -> http` redirect to non-archive host is strictly rejected
        val evilRedirectClient = UrlConnectionOnlineHttpClient(
            allowInsecureLoopback = false,
            openConnection = { url ->
                object : java.net.HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy(): Boolean = false
                    override fun getResponseCode(): Int = 302
                    override fun getHeaderField(name: String?): String? =
                        if (name.equals("Location", ignoreCase = true)) "http://evil.example.com/front.jpg" else null
                }
            },
        )
        assertNull(CoverArtArchiveClient(evilRedirectClient, timeoutMs = 1_000L).resolveFrontCoverUrl("mbid-evil"))

        // 3. Multi-hop slow redirect chain respects total wall-clock timeout and triggers `conn.disconnect()` on cancel
        val disconnectedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val stalledMultiHopClient = UrlConnectionOnlineHttpClient(
            allowInsecureLoopback = false,
            openConnection = { url ->
                object : java.net.HttpURLConnection(url) {
                    @Volatile private var aborted = false
                    override fun connect() {}
                    override fun disconnect() {
                        aborted = true
                        disconnectedCount.incrementAndGet()
                    }
                    override fun usingProxy(): Boolean = false
                    override fun getResponseCode(): Int {
                        val hop = url.path.substringAfterLast('/').toIntOrNull() ?: 0
                        for (tick in 0 until 20) {
                            if (aborted) throw java.io.IOException("Disconnected on timeout")
                            Thread.sleep(10L) // 200ms per hop; 5 hops = 1000ms without total deadline!
                        }
                        return 302
                    }
                    override fun getHeaderField(name: String?): String? {
                        val hop = url.path.substringAfterLast('/').toIntOrNull() ?: 0
                        return if (name.equals("Location", ignoreCase = true)) "https://coverartarchive.org/hop/${hop + 1}" else null
                    }
                }
            },
        )
        val t0 = System.currentTimeMillis()
        val timedOutUrl = CoverArtArchiveClient(stalledMultiHopClient, timeoutMs = 280L).resolveFrontCoverUrl("mbid-slow")
        val dt = System.currentTimeMillis() - t0
        assertNull(timedOutUrl)
        assertTrue("Multi-hop CAA deadline should abort around 280ms, took ${dt}ms", dt < 700L)
        assertTrue(disconnectedCount.get() >= 1)
    }

    private fun sampleItem(
        id: Long,
        fileName: String,
        title: String,
        artist: String,
        durationMs: Long,
    ): MediaItem = MediaItem(
        itemId = id,
        documentUri = "content://doc/$id",
        rootId = 1L,
        parentRelPath = "",
        fileName = fileName,
        mimeType = "audio/mpeg",
        isVideo = false,
        sizeBytes = 5_000_000L,
        lastModified = 1_000L,
        durationMs = durationMs,
        title = title,
        artist = artist,
    )
}
