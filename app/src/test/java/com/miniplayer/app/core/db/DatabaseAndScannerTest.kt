package com.miniplayer.app.core.db

import android.net.Uri
import android.provider.DocumentsContract
import com.miniplayer.app.core.metadata.ArtistSplitter
import com.miniplayer.app.core.metadata.DefaultMetadataEngine
import com.miniplayer.app.core.metadata.OptionalField
import com.miniplayer.app.core.metadata.PhysicalTagWriter
import com.miniplayer.app.core.metadata.TagWriteSpec
import com.miniplayer.app.core.model.AddRootResult
import com.miniplayer.app.core.model.AlbumSummary
import com.miniplayer.app.core.model.CountInMode
import com.miniplayer.app.core.model.LyricsSource
import com.miniplayer.app.core.model.MetadataDraft
import com.miniplayer.app.core.model.MetadataField
import com.miniplayer.app.core.model.MetadataSaveOutcome
import com.miniplayer.app.core.model.MountState
import com.miniplayer.app.core.model.PracticeState
import com.miniplayer.app.core.model.StemMixConfig
import com.miniplayer.app.core.model.StemType
import com.miniplayer.app.core.scanner.ExtractedMediaProbe
import com.miniplayer.app.core.scanner.SafChildEntry
import com.miniplayer.app.core.scanner.SafFolderScanner
import com.miniplayer.app.core.scanner.SafGrantInfo
import com.miniplayer.app.core.scanner.SafTreeDataSource
import com.miniplayer.app.core.scanner.SafVolumeProbe
import com.miniplayer.app.core.scanner.VolumeDisconnectedException
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseAndScannerTest {

    private class FakeSafTreeDataSource : SafTreeDataSource {
        companion object {
            const val AUTHORITY = "com.android.externalstorage.documents"
        }

        var volumeProbe: SafVolumeProbe = SafVolumeProbe.Mounted(isWritable = true, rootDocUri = "content://$AUTHORITY/root")
        var throwDisconnectOnDirDocId: String? = null
        val childrenByDirDocId = LinkedHashMap<String, MutableList<SafChildEntry>>()
        val smallFilesByUri = LinkedHashMap<String, ByteArray>()
        val probesByUri = LinkedHashMap<String, ExtractedMediaProbe>()
        val releasedTreeUris = CopyOnWriteArrayList<String>()
        val extractCalls = AtomicInteger(0)

        override fun takePersistableGrant(treeUri: Uri): SafGrantInfo {
            throw UnsupportedOperationException("Use addSafRootFromGrant in pure-JVM tests")
        }

        override fun releasePersistableGrant(treeUri: String) {
            releasedTreeUris.add(treeUri)
        }

        override fun probeRootMountState(treeUri: String, treeDocId: String, fallbackWritable: Boolean): SafVolumeProbe {
            return volumeProbe
        }

        override fun listChildren(treeUri: String, dirDocId: String): List<SafChildEntry> {
            if (throwDisconnectOnDirDocId == dirDocId) {
                throw VolumeDisconnectedException("USB OTG volume disconnected mid-scan at $dirDocId")
            }
            return childrenByDirDocId[dirDocId].orEmpty().map { entry ->
                // Bind documentUri to current treeUri dynamically so merged parent roots build matching URIs
                entry.copy(documentUri = buildDocumentUri(treeUri, entry.documentId))
            }
        }

        override fun buildDocumentUri(treeUri: String, docId: String): String =
            "$treeUri/document/$docId"

        override fun rebindDocumentUriToNewTree(newTreeUri: String, oldDocumentUri: String): String {
            val docId = oldDocumentUri.substringAfter("/document/")
            return buildDocumentUri(newTreeUri, docId)
        }

        override fun readSmallFileBytes(documentUri: String, maxBytes: Int): ByteArray? =
            smallFilesByUri[documentUri]

        override fun extractMediaMetadata(entry: SafChildEntry, isVideo: Boolean): ExtractedMediaProbe {
            extractCalls.incrementAndGet()
            return probesByUri[entry.documentId] ?: ExtractedMediaProbe(durationMs = 180_000L)
        }
    }

    @Test
    fun `sqlite schema DDL and foreign key cascades execute cleanly on real sqlite3 engine`() {
        val sqliteBin = File("/usr/bin/sqlite3")
        if (!sqliteBin.canExecute()) return

        val sqlScript = buildString {
            appendLine("PRAGMA foreign_keys = ON;")
            for (stmt in MiniPlayerDatabase.SCHEMA_STATEMENTS) {
                appendLine("$stmt;")
            }
            appendLine("INSERT INTO saf_roots (root_id, tree_uri, tree_doc_id, display_name, is_writable, mount_state, last_scan_at) VALUES (1, 'content://saf/1', 'primary:Music', 'Music', 1, 'MOUNTED', 100);")
            appendLine("INSERT INTO folder_rules (root_id, rel_path, folder_uri, is_practice_folder, exclude_from_grouping) VALUES (1, 'Archive', 'content://saf/1/doc/archive', 0, 1);")
            appendLine("INSERT INTO media_items (item_id, document_uri, root_id, parent_rel_path, file_name, mime_type, is_video, size_bytes, last_modified, duration_ms, parsed_title, fingerprint_key) VALUES (10, 'content://saf/1/doc/a', 1, 'Rock', '01 - Track.mp3', 'audio/mpeg', 0, 1024, 200, 180000, 'Track', '01 - track.mp3|1024|180000');")
            appendLine("INSERT INTO media_metadata_overrides (item_id, title, updated_at, synced_to_file) VALUES (10, 'Custom Title', 300, 1);")
            appendLine("INSERT INTO media_item_artists (item_id, artist_norm, artist_display, ord) VALUES (10, 'ac/dc', 'AC/DC', 0);")
            appendLine("INSERT INTO practice_bookmarks (bookmark_id, item_id, label, a_ms, b_ms, saved_speed, created_at) VALUES (1, 10, 'Solo 1', 1000, 5000, 0.75, 400);")
            appendLine("INSERT INTO track_practice_states (item_id, practice_speed, updated_at) VALUES (10, 0.75, 400);")
            appendLine("INSERT INTO playlists (playlist_id, name, is_practice_playlist, created_at, updated_at) VALUES (5, 'Practice', 1, 400, 400);")
            appendLine("INSERT INTO playlist_items (playlist_id, item_id, sort_order, added_at) VALUES (5, 10, 0, 400);")
            appendLine("SELECT 'BEFORE', (SELECT COUNT(*) FROM media_items), (SELECT COUNT(*) FROM practice_bookmarks), (SELECT COUNT(*) FROM playlist_items);")
            appendLine("DELETE FROM saf_roots WHERE root_id = 1;")
            appendLine("SELECT 'AFTER', (SELECT COUNT(*) FROM media_items), (SELECT COUNT(*) FROM practice_bookmarks), (SELECT COUNT(*) FROM playlist_items), (SELECT COUNT(*) FROM media_metadata_overrides), (SELECT COUNT(*) FROM media_item_artists);")
        }

        val proc = ProcessBuilder(sqliteBin.absolutePath, ":memory:")
            .redirectErrorStream(true)
            .start()
        proc.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(sqlScript) }
        val output = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        val exitCode = proc.waitFor()

        assertEquals("SQLite DDL failed: $output", 0, exitCode)
        assertTrue(output.contains("BEFORE|1|1|1"))
        assertTrue("Foreign key ON DELETE CASCADE must prune child tables: $output", output.contains("AFTER|0|0|0|0|0"))
    }

    @Test
    fun `saf overlap detector rejects child under parent and atomically merges child into new parent`() = runBlocking {
        val backend = InMemoryMediaStoreBackend()
        val repo = MediaRepository(backend = backend, ioDispatcher = Dispatchers.Unconfined)
        val ds = FakeSafTreeDataSource()
        val scanner = SafFolderScanner(repository = repo, dataSource = ds, ioDispatcher = Dispatchers.Unconfined)

        val childTreeUri = "content://${FakeSafTreeDataSource.AUTHORITY}/tree/primary%3AMusic%2FRock"
        val parentTreeUri = "content://${FakeSafTreeDataSource.AUTHORITY}/tree/primary%3AMusic"

        // Populate SAF tree: Music -> [Rock (dir), Intro.mp3] and Music/Rock -> [Solo.mp3]
        ds.childrenByDirDocId["primary:Music"] = mutableListOf(
            SafChildEntry(
                documentId = "primary:Music/Rock",
                documentUri = "",
                displayName = "Rock",
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                sizeBytes = 0L,
                lastModified = 100L,
            ),
            SafChildEntry(
                documentId = "primary:Music/Intro.mp3",
                documentUri = "",
                displayName = "Intro.mp3",
                mimeType = "audio/mpeg",
                sizeBytes = 4_000_000L,
                lastModified = 100L,
            ),
        )
        ds.childrenByDirDocId["primary:Music/Rock"] = mutableListOf(
            SafChildEntry(
                documentId = "primary:Music/Rock/Solo.mp3",
                documentUri = "",
                displayName = "Solo.mp3",
                mimeType = "audio/mpeg",
                sizeBytes = 8_000_000L,
                lastModified = 200L,
            ),
        )
        ds.probesByUri["primary:Music/Rock/Solo.mp3"] = ExtractedMediaProbe(
            durationMs = 240_000L,
            rawTitle = "Eruption",
            rawArtist = "Van Halen",
        )
        ds.probesByUri["primary:Music/Intro.mp3"] = ExtractedMediaProbe(
            durationMs = 60_000L,
            rawTitle = "Intruder",
            rawArtist = "Van Halen",
        )

        // 1. Add child root `primary:Music/Rock` first
        val addChildRes = scanner.addSafRootFromGrant(
            SafGrantInfo(
                treeUri = childTreeUri,
                authority = FakeSafTreeDataSource.AUTHORITY,
                treeDocId = "primary:Music/Rock",
                displayName = "Rock",
                isWritable = true,
            ),
        )
        assertTrue(addChildRes is AddRootResult.Added)
        val childRootId = (addChildRes as AddRootResult.Added).root.rootId
        val soloItemBefore = repo.allMediaItems.value.single()
        assertEquals("", soloItemBefore.parentRelPath)

        // Attach a practice bookmark & practice folder rule on child root before merging
        scanner.setFolderPracticeRule(childRootId, "", "$childTreeUri#root", isPracticeFolder = true)
        val savedBm = repo.saveBookmark(soloItemBefore.itemId, "Tapping Solo", 55_000L, 92_000L, 0.70f, null)

        // 2. Add ancestor parent root `primary:Music` -> must atomically merge child root!
        val mergeRes = scanner.addSafRootFromGrant(
            SafGrantInfo(
                treeUri = parentTreeUri,
                authority = FakeSafTreeDataSource.AUTHORITY,
                treeDocId = "primary:Music",
                displayName = "Music",
                isWritable = true,
            ),
        )
        assertTrue(mergeRes is AddRootResult.MergedChildRoots)
        val merged = mergeRes as AddRootResult.MergedChildRoots
        assertEquals(1, merged.mergedCount)
        assertEquals(1, repo.safRoots.value.size)
        assertTrue(ds.releasedTreeUris.contains(childTreeUri))

        // Verify 0 duplicate tracks, preserved itemId, preserved bookmark, re-prefixed relPath ("Rock"), and inherited practice rule!
        assertEquals(2, repo.allMediaItems.value.size)
        val soloAfter = repo.getMediaItemById(soloItemBefore.itemId)
        assertNotNull("Solo itemId must be preserved across parent root merge", soloAfter)
        assertEquals("Rock", soloAfter!!.parentRelPath)
        assertTrue("Rock subfolder practice rule must survive merge", soloAfter.isInPracticeFolder)
        assertEquals(1, soloAfter.bookmarkCount)
        assertEquals(listOf(savedBm), repo.getBookmarks(soloAfter.itemId))

        // 3. Re-adding `primary:Music/Rock` (or a deeper subfolder `primary:Music/Rock/Live`) now rejects with AlreadyCoveredByParent
        val coveredRes = scanner.addSafRootFromGrant(
            SafGrantInfo(
                treeUri = "content://${FakeSafTreeDataSource.AUTHORITY}/tree/primary%3AMusic%2FRock%2FLive",
                authority = FakeSafTreeDataSource.AUTHORITY,
                treeDocId = "primary:Music/Rock/Live",
                displayName = "Live",
                isWritable = true,
            ),
        )
        assertTrue(coveredRes is AddRootResult.AlreadyCoveredByParent)
        assertEquals("Rock/Live", (coveredRes as AddRootResult.AlreadyCoveredByParent).childRelPath)
    }

    @Test
    fun `offline volume guard incremental 0ms skip nomedia sidecar lrc and moved file fingerprint migration`() = runBlocking {
        val backend = InMemoryMediaStoreBackend()
        val repo = MediaRepository(backend = backend, ioDispatcher = Dispatchers.Unconfined)
        val ds = FakeSafTreeDataSource()
        val scanner = SafFolderScanner(repository = repo, dataSource = ds, ioDispatcher = Dispatchers.Unconfined)

        val otgTreeUri = "content://${FakeSafTreeDataSource.AUTHORITY}/tree/ABCD-1234%3AGuitarBacking"
        val docRoot = "ABCD-1234:GuitarBacking"

        // Root itself contains `.nomedia`, `cover.jpg`, `Jam.mp3`, `Jam.lrc`, and subfolder `HiddenSub` with `.nomedia`
        ds.childrenByDirDocId[docRoot] = mutableListOf(
            SafChildEntry("$docRoot/.nomedia", "", ".nomedia", "application/octet-stream", 0L, 100L),
            SafChildEntry("$docRoot/cover.jpg", "", "cover.jpg", "image/jpeg", 50_000L, 100L),
            SafChildEntry("$docRoot/Jam.mp3", "", "Jam.mp3", "audio/mpeg", 5_000_000L, 500L),
            SafChildEntry("$docRoot/Jam.lrc", "", "Jam.lrc", "text/plain", 512L, 500L),
            SafChildEntry(
                "$docRoot/HiddenSub",
                "",
                "HiddenSub",
                DocumentsContract.Document.MIME_TYPE_DIR,
                0L,
                100L,
            ),
        )
        ds.childrenByDirDocId["$docRoot/HiddenSub"] = mutableListOf(
            SafChildEntry("$docRoot/HiddenSub/.nomedia", "", ".nomedia", "application/octet-stream", 0L, 100L),
            SafChildEntry("$docRoot/HiddenSub/SecretLick.mp3", "", "SecretLick.mp3", "audio/mpeg", 3_000_000L, 500L),
        )
        ds.smallFilesByUri["$otgTreeUri/document/$docRoot/Jam.lrc"] =
            "[00:05.00]Pentatonic lick in A minor".toByteArray(Charsets.UTF_8)
        ds.probesByUri["$docRoot/Jam.mp3"] = ExtractedMediaProbe(durationMs = 200_000L, rawTitle = "Blues Jam")
        ds.probesByUri["$docRoot/HiddenSub/SecretLick.mp3"] = ExtractedMediaProbe(durationMs = 90_000L, rawTitle = "Secret Lick")

        // 1. Initial scan: root `.nomedia` MUST NOT block explicitly picked SAF root; `respectSubfolderNomedia = false` by default
        val added = scanner.addSafRootFromGrant(
            SafGrantInfo(otgTreeUri, FakeSafTreeDataSource.AUTHORITY, docRoot, "GuitarBacking", isWritable = true),
        ) as AddRootResult.Added
        val rootId = added.root.rootId
        assertEquals(2, repo.allMediaItems.value.size)
        assertEquals(2, ds.extractCalls.get())

        val jamItem = repo.allMediaItems.value.first { it.fileName == "Jam.mp3" }
        assertEquals("$otgTreeUri/document/$docRoot/cover.jpg", jamItem.coverPathOrUri)
        val cachedLrc = backend.getLyricsCache(jamItem.itemId)
        assertNotNull(cachedLrc)
        assertEquals(LyricsSource.LOCAL_LRC, cachedLrc!!.source)

        // Add bookmark + playlist to verify offline OTG survival AND moved-file survival
        val playlistId = repo.createPlaylist("OTG Practice", isPracticePlaylist = true)
        repo.addItemsToPlaylist(playlistId, listOf(jamItem.itemId))
        repo.saveBookmark(jamItem.itemId, "Chorus Lick", 12_000L, 28_000L, 0.80f, null)

        // 2. Second incremental scan with identical `(uri, size, lastModified)` -> 0 metadata extractions!
        ds.extractCalls.set(0)
        scanner.rescanRoot(rootId, incremental = true)
        assertEquals("Unchanged files must skip metadata extraction cleanly", 0, ds.extractCalls.get())

        // 3. Unplug USB OTG (`OfflineUnmounted` AND mid-scan `VolumeDisconnectedException`) -> ZERO deletions!
        ds.volumeProbe = SafVolumeProbe.OfflineUnmounted
        scanner.rescanRoot(rootId, incremental = true)
        assertEquals(MountState.OFFLINE_UNMOUNTED, repo.safRoots.value.single().mountState)
        assertEquals("Unmounted OTG must preserve media_items", 2, repo.allMediaItems.value.size)
        assertEquals("Unmounted OTG must preserve bookmarks", 1, repo.getBookmarks(jamItem.itemId).size)
        assertEquals("Unmounted OTG must preserve playlist items", 1, repo.getPlaylistDetail(playlistId)!!.items.size)

        // Mid-scan disconnect simulation
        ds.volumeProbe = SafVolumeProbe.Mounted(isWritable = true, rootDocUri = "$otgTreeUri/document/$docRoot")
        ds.throwDisconnectOnDirDocId = "$docRoot/HiddenSub"
        scanner.rescanRoot(rootId, incremental = true)
        assertEquals(MountState.OFFLINE_UNMOUNTED, repo.safRoots.value.single().mountState)
        assertEquals(2, repo.allMediaItems.value.size)
        ds.throwDisconnectOnDirDocId = null

        // Permission revoked simulation -> also preserves DB records cleanly
        ds.volumeProbe = SafVolumeProbe.PermissionRevoked
        scanner.rescanRoot(rootId, incremental = true)
        assertEquals(MountState.PERMISSION_REVOKED, repo.safRoots.value.single().mountState)
        assertEquals(2, repo.allMediaItems.value.size)

        // 4. Remount OTG, move `Jam.mp3` into `Moved/` subfolder; `.nomedia` in both root and `HiddenSub/.nomedia` MUST ALWAYS be ignored!
        ds.volumeProbe = SafVolumeProbe.Mounted(isWritable = true, rootDocUri = "$otgTreeUri/document/$docRoot")
        repo.updateSettings { it.copy(respectSubfolderNomedia = true) }
        scanner.setFolderNomediaRule(rootId, "HiddenSub", "$otgTreeUri/document/$docRoot/HiddenSub", respectNomedia = true)

        ds.childrenByDirDocId[docRoot] = mutableListOf(
            SafChildEntry("$docRoot/.nomedia", "", ".nomedia", "application/octet-stream", 0L, 100L),
            SafChildEntry("$docRoot/Moved", "", "Moved", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 600L),
            SafChildEntry("$docRoot/HiddenSub", "", "HiddenSub", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 100L),
        )
        ds.childrenByDirDocId["$docRoot/Moved"] = mutableListOf(
            SafChildEntry("$docRoot/Moved/Jam.mp3", "", "Jam.mp3", "audio/mpeg", 5_000_000L, 600L),
        )
        ds.probesByUri["$docRoot/Moved/Jam.mp3"] = ExtractedMediaProbe(durationMs = 200_000L, rawTitle = "Blues Jam")

        scanner.rescanRoot(rootId, incremental = true)

        assertEquals(MountState.MOUNTED, repo.safRoots.value.single().mountState)
        assertFalse(repo.resolveRespectNomediaForFolder(rootId, "HiddenSub"))
        // Both `Moved/Jam.mp3` AND `HiddenSub/SecretLick.mp3` remain scanned because `.nomedia` is always ignored!
        val remaining = repo.allMediaItems.value
        assertEquals(2, remaining.size)
        assertEquals(setOf("Jam.mp3", "SecretLick.mp3"), remaining.map { it.fileName }.toSet())
        val migratedJam = remaining.first { it.fileName == "Jam.mp3" }
        assertEquals("Moved file must retain original itemId via fingerprint migration", jamItem.itemId, migratedJam.itemId)
        assertEquals("Moved", migratedJam.parentRelPath)
        assertEquals(1, repo.getBookmarks(migratedJam.itemId).size)
        assertEquals(1, repo.getPlaylistDetail(playlistId)!!.items.size)
    }

    @Test
    fun `metadata priority chain multi-artist album grouping folder inheritance batch lock and deferred writeback`() = runBlocking {
        val backend = InMemoryMediaStoreBackend()
        val tempDir = Files.createTempDirectory("mp_repo_test").toFile()
        try {
            val repo = MediaRepository(
                backend = backend,
                cacheRootDir = File(tempDir, "cache"),
                filesRootDir = File(tempDir, "files"),
                ioDispatcher = Dispatchers.Unconfined,
            )

            val rootId = backend.insertSafRoot(
                treeUri = "content://saf/tree/primary%3ALibrary",
                treeDocId = "primary:Library",
                displayName = "Library",
                isWritable = true,
                mountState = MountState.MOUNTED,
            )

            // 3 items:
            // #1: Collaboration track `Kenshi Yonezu / Daoko` in `Practice/Guitar/JPop` (no album -> `未知专辑`)
            // #2: Band allowlist `AC/DC` on `Back in Black`
            // #3: Second track on `Back in Black` for batch-edit test + 1080p video item
            backend.reconcileRootScan(
                rootId = rootId,
                isWritable = true,
                scannedAtMs = 1_000L,
                scannedFolders = listOf(
                    ScannedFolderRow(rootId, "", "content://saf/root", false),
                    ScannedFolderRow(rootId, "Practice", "content://saf/Practice", false),
                    ScannedFolderRow(rootId, "Practice/Guitar", "content://saf/Practice/Guitar", false),
                ),
                unchangedCoverUpdates = emptyMap(),
                movedItems = emptyList(),
                upsertedItems = listOf(
                    MediaItemRow(
                        documentUri = "content://saf/doc/1",
                        rootId = rootId,
                        parentRelPath = "Practice/Guitar",
                        fileName = "01 - Uchiage Hanabi.mp3",
                        mimeType = "audio/mpeg",
                        isVideo = false,
                        sizeBytes = 6_000_000L,
                        lastModified = 100L,
                        durationMs = 289_000L,
                        rawTitle = null, // Falls back to parsedTitle until overridden!
                        rawArtist = "DAOKO × 米津玄師",
                        rawAlbum = null,
                        parsedTitle = "Uchiage Hanabi",
                        parsedArtist = null,
                        parsedAlbum = null,
                        fingerprintKey = "01 - uchiage hanabi.mp3|6000000|289000",
                    ),
                    MediaItemRow(
                        documentUri = "content://saf/doc/2",
                        rootId = rootId,
                        parentRelPath = "Rock",
                        fileName = "01 - Hells Bells.mp3",
                        mimeType = "audio/mpeg",
                        isVideo = false,
                        sizeBytes = 7_000_000L,
                        lastModified = 100L,
                        durationMs = 312_000L,
                        rawTitle = "Hells Bells",
                        rawArtist = "AC/DC",
                        rawAlbum = "Back in Black",
                        rawAlbumArtist = "AC/DC",
                        rawTrackNum = 1,
                        rawYear = 1980,
                        parsedTitle = "Hells Bells",
                        fingerprintKey = "01 - hells bells.mp3|7000000|312000",
                    ),
                    MediaItemRow(
                        documentUri = "content://saf/doc/3",
                        rootId = rootId,
                        parentRelPath = "Rock",
                        fileName = "06 - Back in Black.mp3",
                        mimeType = "audio/mpeg",
                        isVideo = false,
                        sizeBytes = 6_500_000L,
                        lastModified = 100L,
                        durationMs = 255_000L,
                        rawTitle = "Back in Black",
                        rawArtist = "AC/DC",
                        rawAlbum = "Back in Black",
                        rawAlbumArtist = "AC/DC",
                        rawTrackNum = 6,
                        rawYear = 1980,
                        parsedTitle = "Back in Black",
                        fingerprintKey = "06 - back in black.mp3|6500000|255000",
                    ),
                    MediaItemRow(
                        documentUri = "content://saf/doc/4",
                        rootId = rootId,
                        parentRelPath = "Practice/Guitar",
                        fileName = "lick_breakdown.mp4",
                        mimeType = "video/mp4",
                        isVideo = true,
                        sizeBytes = 25_000_000L,
                        lastModified = 100L,
                        durationMs = 120_000L,
                        width = 1920,
                        height = 1080,
                        parsedTitle = "lick breakdown",
                        fingerprintKey = "lick_breakdown.mp4|25000000|120000",
                    ),
                ),
                deletedItemIds = emptySet(),
                lyricsUpdates = emptyList(),
                artistSplitter = { a, aa -> ArtistSplitter.split(a, aa) },
            )

            // Nearest-ancestor folder rule inheritance: Practice = true, Practice/Guitar inherits true
            backend.upsertFolderRule(FolderRuleRow("content://saf/Practice", rootId, "Practice", isPracticeFolder = true, respectNomedia = null))
            repo.refreshNow()

            assertTrue(repo.isPracticeFolder(rootId, "Practice"))
            assertTrue(repo.isPracticeFolder(rootId, "Practice/Guitar"))
            assertFalse(repo.isPracticeFolder(rootId, "Rock"))

            // While Practice/Guitar inherits isPracticeFolder = true, its audio & video items must be excluded from albums, artists, and videoItems
            assertTrue(repo.videoItems.value.isEmpty())
            assertEquals(listOf("Back in Black"), repo.albums.value.map { it.title })
            assertEquals(setOf("AC/DC"), repo.artists.value.map { it.displayName }.toSet())
            assertEquals(2, repo.browseFolder(rootId, "Practice/Guitar", flat = false)!!.mediaItems.size)

            // Override child subfolder rule to false -> nearest ancestor wins, restoring items to videoItems, albums, and artists!
            backend.upsertFolderRule(FolderRuleRow("content://saf/Practice/Guitar", rootId, "Practice/Guitar", isPracticeFolder = false, respectNomedia = null))
            repo.refreshNow()
            assertFalse(repo.isPracticeFolder(rootId, "Practice/Guitar"))

            // Video stream & resolution badge
            val videoItem = repo.videoItems.value.single()
            assertEquals("1080p", videoItem.resolutionBadge)

            // Albums: `Back in Black` first, `未知专辑` pinned last
            val albums = repo.albums.value
            assertEquals(2, albums.size)
            assertEquals("Back in Black", albums.first().title)
            assertEquals(AlbumSummary.UNKNOWN_ALBUM_KEY, albums.last().albumKey)
            assertTrue(albums.last().isUnknownAlbum)

            // Multi-artist index: `AC/DC` intact as 1 artist, `DAOKO × 米津玄師` split into 2 artists (`DAOKO` and `米津玄師`)
            val artistNames = repo.artists.value.map { it.displayName }.toSet()
            assertTrue(artistNames.contains("AC/DC"))
            assertTrue(artistNames.contains("DAOKO"))
            assertTrue(artistNames.contains("米津玄師"))
            assertEquals(2, repo.getArtistDetail("ac/dc")!!.tracks.size)

            // Browse folder TREE & FLAT
            val rootBrowse = repo.browseFolder(rootId, "", flat = false)!!
            assertEquals(listOf("Practice", "Rock"), rootBrowse.subfolders.map { it.name })
            val flatBrowse = repo.browseFolder(rootId, "", flat = true)!!
            assertEquals(4, flatBrowse.mediaItems.size)

            // Practice state & `[🔖 含段落书签]` reactive flow
            val item1 = repo.allMediaItems.value.first { it.fileName.startsWith("01 - Uchiage") }
            val item2 = repo.allMediaItems.value.first { it.fileName.startsWith("01 - Hells") }
            val item3 = repo.allMediaItems.value.first { it.fileName.startsWith("06 - Back") }

            repo.savePracticeState(
                PracticeState(
                    itemId = item2.itemId,
                    practiceSpeed = 0.75f,
                    pitchSemitones = -1,
                    bpm = 116,
                    countInMode = CountInMode.BEAT_4,
                ),
            )
            repo.saveBookmark(item2.itemId, "Main Riff", 15_000L, 32_000L, 0.75f, null)
            assertEquals(1, repo.bookmarkedTracks.value.size)
            assertEquals("Main Riff", repo.bookmarkedTracks.value.single().bookmarks.single().label)

            // Batch Edit dirty-field lock + Deferred physical write-back when track is actively playing
            val writtenCalls = CopyOnWriteArrayList<Pair<Long, TagWriteSpec>>()
            val writer = PhysicalTagWriter { row, spec ->
                writtenCalls.add(row.itemId to spec)
                (row.sizeBytes + 128L) to (row.lastModified + 1_000L)
            }
            val engine = DefaultMetadataEngine(
                context = null,
                repository = repo,
                coversDir = File(tempDir, "files/covers"),
                physicalTagWriter = writer,
                ioDispatcher = Dispatchers.Unconfined,
            )

            // Batch edit items #2 & #3: pass TITLE and TRACK_NUMBER in dirtyFields, verify they are LOCKED/ignored!
            engine.saveMetadata(
                itemIds = listOf(item2.itemId, item3.itemId),
                draft = MetadataDraft(
                    title = "SHOULD_NOT_OVERWRITE_BATCH_TITLES",
                    trackNumber = 99,
                    album = "Back in Black (Remastered)",
                    year = 2003,
                    dirtyFields = setOf(MetadataField.TITLE, MetadataField.TRACK_NUMBER, MetadataField.ALBUM, MetadataField.YEAR),
                    writeBackToFile = false,
                ),
                currentlyPlayingItemId = null,
            )
            val afterBatch2 = repo.getMediaItemById(item2.itemId)!!
            val afterBatch3 = repo.getMediaItemById(item3.itemId)!!
            assertEquals("Hells Bells", afterBatch2.title)
            assertEquals("Back in Black", afterBatch3.title)
            assertEquals(1, afterBatch2.trackNumber)
            assertEquals(6, afterBatch3.trackNumber)
            assertEquals("Back in Black (Remastered)", afterBatch2.album)
            assertEquals(2003, afterBatch3.year)

            // Single-track edit with `writeBackToFile = true` while `item1` IS currently playing -> SAVED_DB_DEFERRED_FILE!
            val deferOutcome = engine.saveMetadata(
                itemIds = listOf(item1.itemId),
                draft = MetadataDraft(
                    title = "打上花火",
                    artist = "DAOKO / 米津玄師",
                    album = "Thank You Blue",
                    dirtyFields = setOf(MetadataField.TITLE, MetadataField.ARTIST, MetadataField.ALBUM),
                    writeBackToFile = true,
                ),
                currentlyPlayingItemId = item1.itemId,
            )
            assertEquals(MetadataSaveOutcome.SAVED_DB_DEFERRED_FILE, deferOutcome)
            assertEquals("打上花火", repo.getMediaItemById(item1.itemId)!!.title)
            assertEquals("Physical write must be deferred while item1 is playing", 0, writtenCalls.size)

            // Switch active playback to `item2` -> `onActiveItemChanged` flushes `item1` to file automatically!
            repo.savePlaybackPosition(item2.itemId, 5_000L)
            delay(50L)
            assertEquals(1, writtenCalls.size)
            assertEquals(item1.itemId, writtenCalls.single().first)

            // Reset override restores parsed fallback cleanly
            engine.resetMetadataOverride(listOf(item1.itemId))
            assertEquals("Uchiage Hanabi", repo.getMediaItemById(item1.itemId)!!.title)
            assertFalse(repo.getMediaItemById(item1.itemId)!!.hasMetadataOverride)

            // Explicitly clearing optional fields & cover while item2 is playing:
            // 1) Must NOT snap back to stale `rawAlbum`, `rawAlbumArtist`, `rawTrackNum`, `rawYear`, or `embeddedCoverPath`!
            // 2) Must flush `OptionalField.Set(null)` (NOT `Unchanged`) when playback switches away!
            writtenCalls.clear()
            val clearOutcome = engine.saveMetadata(
                itemIds = listOf(item2.itemId),
                draft = MetadataDraft(
                    album = "",
                    albumArtist = "",
                    trackNumber = null,
                    year = null,
                    coverBytes = null,
                    dirtyFields = setOf(
                        MetadataField.ALBUM,
                        MetadataField.ALBUM_ARTIST,
                        MetadataField.TRACK_NUMBER,
                        MetadataField.YEAR,
                        MetadataField.COVER,
                    ),
                    writeBackToFile = true,
                ),
                currentlyPlayingItemId = item2.itemId,
            )
            assertEquals(MetadataSaveOutcome.SAVED_DB_DEFERRED_FILE, clearOutcome)
            val clearedItem2 = repo.getMediaItemById(item2.itemId)!!
            assertEquals("Hells Bells", clearedItem2.title) // Untouched field kept!
            assertEquals(null, clearedItem2.album)
            assertEquals(null, clearedItem2.albumArtist)
            assertEquals(null, clearedItem2.trackNumber)
            assertEquals(null, clearedItem2.year)
            assertEquals(null, clearedItem2.coverPathOrUri)

            // Transition active playback to item3 -> flushes deferred clear spec to physicalTagWriter!
            repo.savePlaybackPosition(item3.itemId, 1_000L)
            delay(50L)
            assertEquals(1, writtenCalls.size)
            val flushedSpec = writtenCalls.single().second
            assertEquals(OptionalField.Unchanged, flushedSpec.title)
            assertEquals(OptionalField.Set<String?>(null), flushedSpec.album)
            assertEquals(OptionalField.Set<String?>(null), flushedSpec.albumArtist)
            assertEquals(OptionalField.Set<Int?>(null), flushedSpec.trackNumber)
            assertEquals(OptionalField.Set<Int?>(null), flushedSpec.year)
            assertEquals(OptionalField.Set(null), flushedSpec.cover)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `concurrent flushDeferredFileWrites and saveMetadata serialize via fileWriteMutex without duplicate writes`() = runBlocking {
        val backend = InMemoryMediaStoreBackend()
        val tempDir = Files.createTempDirectory("mp_race_test").toFile()
        try {
            val repo = MediaRepository(
                backend = backend,
                cacheRootDir = File(tempDir, "cache"),
                filesRootDir = File(tempDir, "files"),
                ioDispatcher = Dispatchers.IO,
            )
            val rootId = backend.insertSafRoot(
                treeUri = "content://saf/tree/primary%3ARace",
                treeDocId = "primary:Race",
                displayName = "Race",
                isWritable = true,
                mountState = MountState.MOUNTED,
            )
            backend.reconcileRootScan(
                rootId = rootId,
                isWritable = true,
                scannedAtMs = 1_000L,
                scannedFolders = listOf(ScannedFolderRow(rootId, "", "content://saf/race", false)),
                unchangedCoverUpdates = emptyMap(),
                movedItems = emptyList(),
                upsertedItems = listOf(
                    MediaItemRow(
                        documentUri = "content://saf/race/1",
                        rootId = rootId,
                        parentRelPath = "",
                        fileName = "01 - TrackOne.mp3",
                        mimeType = "audio/mpeg",
                        isVideo = false,
                        sizeBytes = 5_000_000L,
                        lastModified = 100L,
                        durationMs = 180_000L,
                        rawTitle = "Track One",
                        parsedTitle = "Track One",
                        fingerprintKey = "01 - trackone.mp3|5000000|180000",
                    ),
                    MediaItemRow(
                        documentUri = "content://saf/race/2",
                        rootId = rootId,
                        parentRelPath = "",
                        fileName = "02 - TrackTwo.mp3",
                        mimeType = "audio/mpeg",
                        isVideo = false,
                        sizeBytes = 5_200_000L,
                        lastModified = 100L,
                        durationMs = 200_000L,
                        rawTitle = "Track Two",
                        parsedTitle = "Track Two",
                        fingerprintKey = "02 - tracktwo.mp3|5200000|200000",
                    ),
                ),
                deletedItemIds = emptySet(),
                lyricsUpdates = emptyList(),
                artistSplitter = { a, aa -> ArtistSplitter.split(a, aa) },
            )
            repo.refreshNow()

            val items = repo.allMediaItems.value.sortedBy { it.fileName }
            val item1 = items[0]
            val item2 = items[1]

            val activeWriters = AtomicInteger(0)
            val maxConcurrentWriters = AtomicInteger(0)
            val item1Writes = AtomicInteger(0)
            val item2Writes = AtomicInteger(0)

            val slowWriter = PhysicalTagWriter { row, _ ->
                val cur = activeWriters.incrementAndGet()
                maxConcurrentWriters.accumulateAndGet(cur, ::maxOf)
                try {
                    Thread.sleep(50L) // Force overlap across Dispatchers.IO worker threads
                    if (row.itemId == item1.itemId) item1Writes.incrementAndGet()
                    if (row.itemId == item2.itemId) item2Writes.incrementAndGet()
                    (row.sizeBytes + 64L) to (row.lastModified + 1_000L)
                } finally {
                    activeWriters.decrementAndGet()
                }
            }

            val engine = DefaultMetadataEngine(
                context = null,
                repository = repo,
                coversDir = File(tempDir, "covers"),
                physicalTagWriter = slowWriter,
                ioDispatcher = Dispatchers.IO,
            )

            // Defer write on item1 while item1 is playing
            val deferred = engine.saveMetadata(
                itemIds = listOf(item1.itemId),
                draft = MetadataDraft(
                    title = "Track One Edited",
                    dirtyFields = setOf(MetadataField.TITLE),
                    writeBackToFile = true,
                ),
                currentlyPlayingItemId = item1.itemId,
            )
            assertEquals(MetadataSaveOutcome.SAVED_DB_DEFERRED_FILE, deferred)
            assertEquals(0, item1Writes.get())

            // Fire `onActiveItemChanged` + 6 concurrent `flushDeferredFileWrites` + concurrent `saveMetadata` on item2!
            repo.savePlaybackPosition(item2.itemId, 1_000L)
            val flushJobs = (1..6).map {
                async(Dispatchers.IO) {
                    engine.flushDeferredFileWrites(currentlyPlayingItemId = item2.itemId)
                }
            }
            val editJob = async(Dispatchers.IO) {
                engine.saveMetadata(
                    itemIds = listOf(item2.itemId),
                    draft = MetadataDraft(
                        title = "Track Two Direct Write",
                        dirtyFields = setOf(MetadataField.TITLE),
                        writeBackToFile = true,
                    ),
                    currentlyPlayingItemId = null, // Direct write on item2 racing with item1 flushes
                )
            }

            val flushCounts = flushJobs.awaitAll()
            assertEquals(MetadataSaveOutcome.SAVED_DB_AND_FILE, editJob.await())
            delay(80L) // Ensure backgroundScope `onActiveItemChanged` coroutine also finishes

            assertEquals("Physical SAF tag writes must be strictly serialized by fileWriteMutex", 1, maxConcurrentWriters.get())
            assertEquals("Deferred item1 must be physically written ONCE across all racing flushers", 1, item1Writes.get())
            assertEquals("Direct item2 write must also complete once cleanly", 1, item2Writes.get())
            assertTrue(flushCounts.sum() <= 1)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `practice folder isolation excludes root and subfolder items from albums artists and videos while keeping folders playlists and bookmarks`() = runBlocking {
        val backend = InMemoryMediaStoreBackend()
        val repo = MediaRepository(backend = backend, ioDispatcher = Dispatchers.Unconfined)
        val ds = FakeSafTreeDataSource()
        val scanner = SafFolderScanner(repository = repo, dataSource = ds, ioDispatcher = Dispatchers.Unconfined)

        val musicTreeUri = "content://${FakeSafTreeDataSource.AUTHORITY}/tree/primary%3AMusic"
        val lessonsTreeUri = "content://${FakeSafTreeDataSource.AUTHORITY}/tree/primary%3ALessons"

        // Root 1 (`Music`): contains casual track `CasualSong.mp3`, casual video `LiveConcert.mp4`,
        // and subfolder `Backing` containing `BackingTrack.mp3` + `DrillVideo.mp4`.
        ds.childrenByDirDocId["primary:Music"] = mutableListOf(
            SafChildEntry("primary:Music/CasualSong.mp3", "", "CasualSong.mp3", "audio/mpeg", 4_000_000L, 100L),
            SafChildEntry("primary:Music/LiveConcert.mp4", "", "LiveConcert.mp4", "video/mp4", 20_000_000L, 100L),
            SafChildEntry("primary:Music/Backing", "", "Backing", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 100L),
        )
        ds.childrenByDirDocId["primary:Music/Backing"] = mutableListOf(
            SafChildEntry("primary:Music/Backing/BackingTrack.mp3", "", "BackingTrack.mp3", "audio/mpeg", 5_000_000L, 100L),
            SafChildEntry("primary:Music/Backing/DrillVideo.mp4", "", "DrillVideo.mp4", "video/mp4", 15_000_000L, 100L),
        )
        ds.probesByUri["primary:Music/CasualSong.mp3"] = ExtractedMediaProbe(
            durationMs = 210_000L,
            rawTitle = "Casual Song",
            rawArtist = "Casual Artist",
            rawAlbum = "Casual Album",
        )
        ds.probesByUri["primary:Music/LiveConcert.mp4"] = ExtractedMediaProbe(
            durationMs = 300_000L,
            width = 1920,
            height = 1080,
        )
        ds.probesByUri["primary:Music/Backing/BackingTrack.mp3"] = ExtractedMediaProbe(
            durationMs = 195_000L,
            rawTitle = "Backing Track",
            rawArtist = "Practice Artist",
            rawAlbum = "Practice Backing Vol 1",
        )
        ds.probesByUri["primary:Music/Backing/DrillVideo.mp4"] = ExtractedMediaProbe(
            durationMs = 150_000L,
            width = 1280,
            height = 720,
        )

        // Root 2 (`Lessons`): dedicated root folder with audio + video
        ds.childrenByDirDocId["primary:Lessons"] = mutableListOf(
            SafChildEntry("primary:Lessons/Etude01.mp3", "", "Etude01.mp3", "audio/mpeg", 3_000_000L, 100L),
            SafChildEntry("primary:Lessons/Etude01.mp4", "", "Etude01.mp4", "video/mp4", 12_000_000L, 100L),
        )
        ds.probesByUri["primary:Lessons/Etude01.mp3"] = ExtractedMediaProbe(
            durationMs = 120_000L,
            rawTitle = "Etude 01",
            rawArtist = "Tutor Artist",
            rawAlbum = "Guitar Etudes",
        )
        ds.probesByUri["primary:Lessons/Etude01.mp4"] = ExtractedMediaProbe(
            durationMs = 120_000L,
            width = 1920,
            height = 1080,
        )

        val musicRootId = (scanner.addSafRootFromGrant(
            SafGrantInfo(musicTreeUri, FakeSafTreeDataSource.AUTHORITY, "primary:Music", "Music", isWritable = true),
        ) as AddRootResult.Added).root.rootId
        val lessonsRootId = (scanner.addSafRootFromGrant(
            SafGrantInfo(lessonsTreeUri, FakeSafTreeDataSource.AUTHORITY, "primary:Lessons", "Lessons", isWritable = true),
        ) as AddRootResult.Added).root.rootId

        // Initially nothing is marked as a Practice Folder -> all 3 albums, 3 artists, and 3 videos appear
        assertEquals(setOf("Casual Album", "Guitar Etudes", "Practice Backing Vol 1"), repo.albums.value.map { it.title }.toSet())
        assertEquals(setOf("Casual Artist", "Practice Artist", "Tutor Artist"), repo.artists.value.map { it.displayName }.toSet())
        assertEquals(setOf("DrillVideo.mp4", "Etude01.mp4", "LiveConcert.mp4"), repo.videoItems.value.map { it.fileName }.toSet())

        // Add practice items to a playlist and attach a bookmark to verify playlist & bookmark preservation
        val backingAudio = repo.allMediaItems.value.first { it.fileName == "BackingTrack.mp3" }
        val etudeVideo = repo.allMediaItems.value.first { it.fileName == "Etude01.mp4" }
        val playlistId = repo.createPlaylist("Daily Routine", isPracticePlaylist = true)
        repo.addItemsToPlaylist(playlistId, listOf(backingAudio.itemId, etudeVideo.itemId))
        repo.saveBookmark(backingAudio.itemId, "Chorus Solo", 30_000L, 55_000L, 0.80f, null)

        // 1. Mark subfolder `Music/Backing` AND root `Lessons` as Practice Folders
        scanner.setFolderPracticeRule(musicRootId, "Backing", "$musicTreeUri/document/primary:Music/Backing", isPracticeFolder = true)
        scanner.setFolderPracticeRule(lessonsRootId, "", lessonsTreeUri, isPracticeFolder = true)

        // Practice audio & video items MUST disappear from albums, artists, and videoItems!
        assertEquals(listOf("Casual Album"), repo.albums.value.map { it.title })
        assertTrue(repo.getAlbumTracks("practice backing vol 1|practice artist").isEmpty())
        assertTrue(repo.getAlbumTracks("guitar etudes|tutor artist").isEmpty())
        assertEquals(listOf("Casual Artist"), repo.artists.value.map { it.displayName })
        assertEquals(null, repo.getArtistDetail("practice artist"))
        assertEquals(null, repo.getArtistDetail("tutor artist"))
        assertEquals(listOf("LiveConcert.mp4"), repo.videoItems.value.map { it.fileName })

        // Meanwhile safRoots, browseFolder (TREE & FLAT), playlists, and bookmarkedTracks MUST still include them!
        assertEquals(4, repo.safRoots.value.first { it.rootId == musicRootId }.itemCount)
        assertEquals(2, repo.safRoots.value.first { it.rootId == lessonsRootId }.itemCount)
        assertEquals(
            setOf("BackingTrack.mp3", "DrillVideo.mp4"),
            repo.browseFolder(musicRootId, "Backing", flat = false)!!.mediaItems.map { it.fileName }.toSet(),
        )
        assertEquals(
            4,
            repo.browseFolder(musicRootId, "", flat = true)!!.mediaItems.size,
        )
        assertEquals(
            setOf("Etude01.mp3", "Etude01.mp4"),
            repo.browseFolder(lessonsRootId, "", flat = false)!!.mediaItems.map { it.fileName }.toSet(),
        )
        assertEquals(
            listOf(backingAudio.itemId, etudeVideo.itemId),
            repo.getPlaylistDetail(playlistId)!!.items.map { it.itemId },
        )
        assertEquals(listOf(backingAudio.itemId), repo.bookmarkedTracks.value.map { it.mediaItem.itemId })

        // 2. Unmark both folders (`isPracticeFolder = false`) -> items immediately reappear in albums, artists, and videoItems!
        scanner.setFolderPracticeRule(musicRootId, "Backing", "$musicTreeUri/document/primary:Music/Backing", isPracticeFolder = false)
        scanner.setFolderPracticeRule(lessonsRootId, "", lessonsTreeUri, isPracticeFolder = false)

        assertEquals(setOf("Casual Album", "Guitar Etudes", "Practice Backing Vol 1"), repo.albums.value.map { it.title }.toSet())
        assertEquals(setOf("Casual Artist", "Practice Artist", "Tutor Artist"), repo.artists.value.map { it.displayName }.toSet())
        assertEquals(setOf("DrillVideo.mp4", "Etude01.mp4", "LiveConcert.mp4"), repo.videoItems.value.map { it.fileName }.toSet())
    }

    @Test
    fun `exclude folder from auto grouping supports ancestor inheritance child override and independent practice flag preservation`() = runBlocking {
        val backend = InMemoryMediaStoreBackend()
        val repo = MediaRepository(backend = backend, ioDispatcher = Dispatchers.Unconfined)
        val ds = FakeSafTreeDataSource()
        val scanner = SafFolderScanner(repository = repo, dataSource = ds, ioDispatcher = Dispatchers.Unconfined)

        val mainTreeUri = "content://${FakeSafTreeDataSource.AUTHORITY}/tree/primary%3AMain"
        val voiceMemosTreeUri = "content://${FakeSafTreeDataSource.AUTHORITY}/tree/primary%3AVoiceMemos"

        // Root 1 (`Main`):
        // - `CasualHit.mp3` + `MusicVideo.mp4` at root
        // - `Archive/RawMemo.mp3` + `Archive/RawClip.mp4`
        // - `Archive/Curated/PolishedDemo.mp3`
        // - `Practice/SubTakes/Take01.mp3`
        ds.childrenByDirDocId["primary:Main"] = mutableListOf(
            SafChildEntry("primary:Main/CasualHit.mp3", "", "CasualHit.mp3", "audio/mpeg", 4_000_000L, 100L),
            SafChildEntry("primary:Main/MusicVideo.mp4", "", "MusicVideo.mp4", "video/mp4", 18_000_000L, 100L),
            SafChildEntry("primary:Main/Archive", "", "Archive", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 100L),
            SafChildEntry("primary:Main/Practice", "", "Practice", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 100L),
        )
        ds.childrenByDirDocId["primary:Main/Archive"] = mutableListOf(
            SafChildEntry("primary:Main/Archive/RawMemo.mp3", "", "RawMemo.mp3", "audio/mpeg", 2_000_000L, 100L),
            SafChildEntry("primary:Main/Archive/RawClip.mp4", "", "RawClip.mp4", "video/mp4", 9_000_000L, 100L),
            SafChildEntry("primary:Main/Archive/Curated", "", "Curated", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 100L),
        )
        ds.childrenByDirDocId["primary:Main/Archive/Curated"] = mutableListOf(
            SafChildEntry("primary:Main/Archive/Curated/PolishedDemo.mp3", "", "PolishedDemo.mp3", "audio/mpeg", 3_500_000L, 100L),
        )
        ds.childrenByDirDocId["primary:Main/Practice"] = mutableListOf(
            SafChildEntry("primary:Main/Practice/SubTakes", "", "SubTakes", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 100L),
        )
        ds.childrenByDirDocId["primary:Main/Practice/SubTakes"] = mutableListOf(
            SafChildEntry("primary:Main/Practice/SubTakes/Take01.mp3", "", "Take01.mp3", "audio/mpeg", 2_500_000L, 100L),
        )

        ds.probesByUri["primary:Main/CasualHit.mp3"] = ExtractedMediaProbe(200_000L, rawTitle = "Casual Hit", rawArtist = "Pop Star", rawAlbum = "Pop Album")
        ds.probesByUri["primary:Main/MusicVideo.mp4"] = ExtractedMediaProbe(240_000L, width = 1920, height = 1080)
        ds.probesByUri["primary:Main/Archive/RawMemo.mp3"] = ExtractedMediaProbe(60_000L, rawTitle = "Raw Memo", rawArtist = "Memo Speaker", rawAlbum = "Memos")
        ds.probesByUri["primary:Main/Archive/RawClip.mp4"] = ExtractedMediaProbe(45_000L, width = 1280, height = 720)
        ds.probesByUri["primary:Main/Archive/Curated/PolishedDemo.mp3"] = ExtractedMediaProbe(180_000L, rawTitle = "Polished Demo", rawArtist = "Demo Band", rawAlbum = "Demos")
        ds.probesByUri["primary:Main/Practice/SubTakes/Take01.mp3"] = ExtractedMediaProbe(120_000L, rawTitle = "Take 01", rawArtist = "Session Player", rawAlbum = "Session Takes")

        // Root 2 (`VoiceMemos`): dedicated root to mark as excluded from auto-grouping
        ds.childrenByDirDocId["primary:VoiceMemos"] = mutableListOf(
            SafChildEntry("primary:VoiceMemos/Idea01.mp3", "", "Idea01.mp3", "audio/mpeg", 1_000_000L, 100L),
        )
        ds.probesByUri["primary:VoiceMemos/Idea01.mp3"] = ExtractedMediaProbe(30_000L, rawTitle = "Idea 01", rawArtist = "Voice Note", rawAlbum = "Voice Ideas")

        val mainRootId = (scanner.addSafRootFromGrant(
            SafGrantInfo(mainTreeUri, FakeSafTreeDataSource.AUTHORITY, "primary:Main", "Main", isWritable = true),
        ) as AddRootResult.Added).root.rootId
        val memosRootId = (scanner.addSafRootFromGrant(
            SafGrantInfo(voiceMemosTreeUri, FakeSafTreeDataSource.AUTHORITY, "primary:VoiceMemos", "VoiceMemos", isWritable = true),
        ) as AddRootResult.Added).root.rootId

        // Attach playlist & bookmark to an item that will be excluded
        val rawMemo = repo.allMediaItems.value.first { it.fileName == "RawMemo.mp3" }
        val playlistId = repo.createPlaylist("Ideas", isPracticePlaylist = false)
        repo.addItemsToPlaylist(playlistId, listOf(rawMemo.itemId))
        repo.saveBookmark(rawMemo.itemId, "Riff Seed", 5_000L, 15_000L, null, null)

        // 1. Mark `Main/Archive` and root `VoiceMemos` as `isExcludedFromAutoGrouping = true`
        scanner.setFolderExcludeFromGroupingRule(mainRootId, "Archive", "$mainTreeUri/document/primary:Main/Archive", isExcludedFromAutoGrouping = true)
        scanner.setFolderExcludeFromGroupingRule(memosRootId, "", voiceMemosTreeUri, isExcludedFromAutoGrouping = true)

        assertTrue(repo.isExcludedFromAutoGrouping(mainRootId, "Archive"))
        assertTrue("Descendant Archive/Curated must inherit isExcludedFromAutoGrouping", repo.isExcludedFromAutoGrouping(mainRootId, "Archive/Curated"))
        assertTrue(repo.safRoots.value.first { it.rootId == memosRootId }.isExcludedFromAutoGrouping)
        assertFalse(repo.isPracticeFolder(mainRootId, "Archive"))
        assertFalse(repo.isPracticeFolder(memosRootId, ""))

        // Excluded audio & video items MUST NOT appear in albums, artists, or videoItems
        assertEquals(setOf("Pop Album", "Session Takes"), repo.albums.value.map { it.title }.toSet())
        assertTrue(repo.getAlbumTracks("memos|memo speaker").isEmpty())
        assertTrue(repo.getAlbumTracks("demos|demo band").isEmpty())
        assertTrue(repo.getAlbumTracks("voice ideas|voice note").isEmpty())
        assertEquals(setOf("Pop Star", "Session Player"), repo.artists.value.map { it.displayName }.toSet())
        assertEquals(null, repo.getArtistDetail("memo speaker"))
        assertEquals(listOf("MusicVideo.mp4"), repo.videoItems.value.map { it.fileName })

        // Yet folder browse, playlists, and bookmarks still retain them with casual flags (`isInPracticeFolder = false`)
        val archiveBrowse = repo.browseFolder(mainRootId, "Archive", flat = false)!!
        assertTrue(archiveBrowse.isCurrentExcludedFromAutoGrouping)
        assertFalse(archiveBrowse.isCurrentPracticeFolder)
        assertTrue(archiveBrowse.subfolders.single { it.name == "Curated" }.isExcludedFromAutoGrouping)
        val refreshedRawMemo = archiveBrowse.mediaItems.first { it.fileName == "RawMemo.mp3" }
        assertTrue(refreshedRawMemo.isExcludedFromAutoGrouping)
        assertFalse(refreshedRawMemo.isInPracticeFolder)
        assertEquals(listOf(rawMemo.itemId), repo.getPlaylistDetail(playlistId)!!.items.map { it.itemId })
        assertEquals(listOf(rawMemo.itemId), repo.bookmarkedTracks.value.map { it.mediaItem.itemId })

        // 2. Child override: explicitly set `Archive/Curated` to `isExcludedFromAutoGrouping = false`
        scanner.setFolderExcludeFromGroupingRule(
            mainRootId,
            "Archive/Curated",
            "$mainTreeUri/document/primary:Main/Archive/Curated",
            isExcludedFromAutoGrouping = false,
        )
        assertFalse(repo.isExcludedFromAutoGrouping(mainRootId, "Archive/Curated"))
        assertTrue(repo.isExcludedFromAutoGrouping(mainRootId, "Archive"))
        assertEquals(setOf("Demos", "Pop Album", "Session Takes"), repo.albums.value.map { it.title }.toSet())
        assertEquals(setOf("Demo Band", "Pop Star", "Session Player"), repo.artists.value.map { it.displayName }.toSet())

        // 3. Independent flag preservation across parent/child:
        // Parent `Practice` has `isPracticeFolder = true` (and `isExcludedFromAutoGrouping = false`).
        // Toggling `isExcludedFromAutoGrouping = true` on child `Practice/SubTakes` MUST preserve inherited `isPracticeFolder = true`!
        scanner.setFolderPracticeRule(mainRootId, "Practice", "$mainTreeUri/document/primary:Main/Practice", isPracticeFolder = true)
        scanner.setFolderExcludeFromGroupingRule(
            mainRootId,
            "Practice/SubTakes",
            "$mainTreeUri/document/primary:Main/Practice/SubTakes",
            isExcludedFromAutoGrouping = true,
        )
        assertTrue("Child must preserve inherited isPracticeFolder = true", repo.isPracticeFolder(mainRootId, "Practice/SubTakes"))
        assertTrue("Child must have isExcludedFromAutoGrouping = true", repo.isExcludedFromAutoGrouping(mainRootId, "Practice/SubTakes"))
        val takeItem = repo.allMediaItems.value.first { it.fileName == "Take01.mp3" }
        assertTrue(takeItem.isInPracticeFolder)
        assertTrue(takeItem.isExcludedFromAutoGrouping)
    }

    @Test
    fun `renaming subfolder rock to Rock with identical or changed SAF document URIs eliminates stale lowercase folder and migrates folder rules in 0ms`() = runBlocking {
        // Case A: SAF keeps child documentIds/documentUris identical (e.g. inode-based or case-insensitive doc IDs)
        run {
            val backend = InMemoryMediaStoreBackend()
            val repo = MediaRepository(backend = backend, ioDispatcher = Dispatchers.Unconfined)
            val ds = FakeSafTreeDataSource()
            val scanner = SafFolderScanner(repository = repo, dataSource = ds, ioDispatcher = Dispatchers.Unconfined)

            val musicTreeUri = "content://${FakeSafTreeDataSource.AUTHORITY}/tree/primary%3AMusic"
            // Stable inode-like doc IDs that stay identical across `rock` -> `Rock`
            val rockDirDocId = "inode:dir_rock_101"
            val subDirDocId = "inode:dir_classic_102"
            val riffDocId = "inode:file_riff_201"
            val soloDocId = "inode:file_solo_202"

            ds.childrenByDirDocId["primary:Music"] = mutableListOf(
                SafChildEntry(rockDirDocId, "", "rock", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 100L),
            )
            ds.childrenByDirDocId[rockDirDocId] = mutableListOf(
                SafChildEntry(riffDocId, "", "01 - Riff.mp3", "audio/mpeg", 4_200_000L, 500L),
                SafChildEntry(subDirDocId, "", "classic", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 100L),
            )
            ds.childrenByDirDocId[subDirDocId] = mutableListOf(
                SafChildEntry(soloDocId, "", "02 - Solo.mp3", "audio/mpeg", 5_100_000L, 500L),
            )
            ds.probesByUri[riffDocId] = ExtractedMediaProbe(durationMs = 180_000L, rawTitle = "Main Riff", rawArtist = "Slash")
            ds.probesByUri[soloDocId] = ExtractedMediaProbe(durationMs = 240_000L, rawTitle = "Outro Solo", rawArtist = "Slash")

            val rootId = (scanner.addSafRootFromGrant(
                SafGrantInfo(musicTreeUri, FakeSafTreeDataSource.AUTHORITY, "primary:Music", "Music", isWritable = true),
            ) as AddRootResult.Added).root.rootId
            assertEquals(2, ds.extractCalls.get())
            assertEquals(listOf("rock"), repo.browseFolder(rootId, "")!!.subfolders.map { it.name })

            // Attach folder rules (`isPracticeFolder` + `isExcludedFromAutoGrouping`), bookmark, and playlist on `rock`
            scanner.setFolderPracticeRule(rootId, "rock", "$musicTreeUri/document/$rockDirDocId", isPracticeFolder = true)
            scanner.setFolderExcludeFromGroupingRule(rootId, "rock", "$musicTreeUri/document/$rockDirDocId", isExcludedFromAutoGrouping = true)
            scanner.setFolderPracticeRule(rootId, "rock/classic", "$musicTreeUri/document/$subDirDocId", isPracticeFolder = true)

            val riffBefore = repo.allMediaItems.value.first { it.fileName == "01 - Riff.mp3" }
            val plId = repo.createPlaylist("Rock Set", isPracticePlaylist = true)
            repo.addItemsToPlaylist(plId, listOf(riffBefore.itemId))
            repo.saveBookmark(riffBefore.itemId, "Intro", 5_000L, 20_000L, 0.85f, null)

            // Rename `rock` -> `Rock` in external file manager while child document URIs, size, and lastModified stay identical!
            ds.childrenByDirDocId["primary:Music"] = mutableListOf(
                SafChildEntry(rockDirDocId, "", "Rock", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 200L),
            )
            ds.extractCalls.set(0)
            val versionBefore = repo.snapshotVersion.value

            scanner.rescanRoot(rootId, incremental = true)

            assertTrue("snapshotVersion must increment on folder rename", repo.snapshotVersion.value > versionBefore)
            assertEquals("Identical-URI folder rename must migrate in 0ms with 0 MediaMetadataRetriever calls", 0, ds.extractCalls.get())

            // Root folder view MUST contain ONLY `Rock` (never both `Rock` and `rock`)!
            val rootBrowse = repo.browseFolder(rootId, "")!!
            assertEquals(listOf("Rock"), rootBrowse.subfolders.map { it.name })
            assertEquals(listOf("Rock"), rootBrowse.subfolders.map { it.relPath })
            assertEquals(2, rootBrowse.subfolders.single().itemCount)
            assertTrue(rootBrowse.subfolders.single().isPracticeFolder)
            assertTrue(rootBrowse.subfolders.single().isExcludedFromAutoGrouping)

            // Both `browseFolder(rootId, "Rock")` and stale breadcrumb `browseFolder(rootId, "rock")` resolve cleanly to `"Rock"`
            val rockBrowse = repo.browseFolder(rootId, "Rock")!!
            assertEquals("Rock", rockBrowse.currentRelPath)
            assertEquals(listOf("01 - Riff.mp3"), rockBrowse.mediaItems.map { it.fileName })
            assertEquals(listOf("classic"), rockBrowse.subfolders.map { it.name })
            assertEquals(listOf("Rock/classic"), rockBrowse.subfolders.map { it.relPath })
            assertTrue(rockBrowse.isCurrentPracticeFolder)
            assertTrue(rockBrowse.isCurrentExcludedFromAutoGrouping)

            val staleCaseBrowse = repo.browseFolder(rootId, "rock")!!
            assertEquals("Rock", staleCaseBrowse.currentRelPath)
            assertEquals(listOf("01 - Riff.mp3"), staleCaseBrowse.mediaItems.map { it.fileName })

            val nestedBrowse = repo.browseFolder(rootId, "Rock/classic")!!
            assertEquals(listOf("02 - Solo.mp3"), nestedBrowse.mediaItems.map { it.fileName })

            // Verify DB `folder_rules` migrated cleanly (`rock` and `rock/classic` deleted, `Rock` and `Rock/classic` present)
            val rulePaths = backend.readSnapshot().folderRules.filter { it.rootId == rootId }.map { it.relPath }.toSet()
            assertEquals(setOf("Rock", "Rock/classic"), rulePaths)

            // Item ID, bookmark, and playlist membership preserved
            val riffAfter = repo.getMediaItemById(riffBefore.itemId)!!
            assertEquals("Rock", riffAfter.parentRelPath)
            assertEquals(1, repo.getBookmarks(riffAfter.itemId).size)
            assertEquals(listOf(riffBefore.itemId), repo.getPlaylistDetail(plId)!!.items.map { it.itemId })
        }

        // Case B: SAF changes path-based child documentIds/documentUris (`primary:Music/rock/...` -> `primary:Music/Rock/...`)
        run {
            val backend = InMemoryMediaStoreBackend()
            val repo = MediaRepository(backend = backend, ioDispatcher = Dispatchers.Unconfined)
            val ds = FakeSafTreeDataSource()
            val scanner = SafFolderScanner(repository = repo, dataSource = ds, ioDispatcher = Dispatchers.Unconfined)

            val musicTreeUri = "content://${FakeSafTreeDataSource.AUTHORITY}/tree/primary%3AMusic"
            ds.childrenByDirDocId["primary:Music"] = mutableListOf(
                SafChildEntry("primary:Music/rock", "", "rock", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 100L),
            )
            ds.childrenByDirDocId["primary:Music/rock"] = mutableListOf(
                SafChildEntry("primary:Music/rock/Anthem.flac", "", "Anthem.flac", "audio/flac", 18_500_000L, 777L),
            )
            ds.probesByUri["primary:Music/rock/Anthem.flac"] = ExtractedMediaProbe(
                durationMs = 215_000L,
                rawTitle = "Rock Anthem",
                rawArtist = "Foo Fighters",
            )

            val rootId = (scanner.addSafRootFromGrant(
                SafGrantInfo(musicTreeUri, FakeSafTreeDataSource.AUTHORITY, "primary:Music", "Music", isWritable = true),
            ) as AddRootResult.Added).root.rootId
            val itemBefore = repo.allMediaItems.value.single()
            scanner.setFolderPracticeRule(rootId, "rock", "$musicTreeUri/document/primary:Music/rock", isPracticeFolder = true)
            repo.saveBookmark(itemBefore.itemId, "Bridge", 60_000L, 95_000L, 0.75f, null)

            // Rename `rock` -> `Rock` where SAF changes all child document IDs & URIs while size & mtime stay unchanged
            ds.childrenByDirDocId.remove("primary:Music/rock")
            ds.childrenByDirDocId["primary:Music"] = mutableListOf(
                SafChildEntry("primary:Music/Rock", "", "Rock", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 888L),
            )
            ds.childrenByDirDocId["primary:Music/Rock"] = mutableListOf(
                SafChildEntry("primary:Music/Rock/Anthem.flac", "", "Anthem.flac", "audio/flac", 18_500_000L, 777L),
            )
            ds.extractCalls.set(0)

            scanner.rescanRoot(rootId, incremental = true)

            assertEquals("Changed-URI folder rename with unchanged size+mtime must migrate in 0ms without re-probing", 0, ds.extractCalls.get())
            assertEquals(listOf("Rock"), repo.browseFolder(rootId, "")!!.subfolders.map { it.name })
            val rockBrowse = repo.browseFolder(rootId, "Rock")!!
            assertEquals(listOf("Anthem.flac"), rockBrowse.mediaItems.map { it.fileName })
            assertTrue(rockBrowse.isCurrentPracticeFolder)

            val itemAfter = repo.allMediaItems.value.single()
            assertEquals("Original itemId must survive folder rename", itemBefore.itemId, itemAfter.itemId)
            assertEquals("Rock", itemAfter.parentRelPath)
            assertEquals("Rock Anthem", itemAfter.title)
            assertEquals(1, repo.getBookmarks(itemAfter.itemId).size)
            assertEquals(
                setOf("Rock"),
                backend.readSnapshot().folderRules.filter { it.rootId == rootId }.map { it.relPath }.toSet(),
            )
        }
    }

    @Test
    fun `renaming subfolder rock to metal and case-renaming SAF root completely removes stale folder names`() = runBlocking {
        val backend = InMemoryMediaStoreBackend()
        val repo = MediaRepository(backend = backend, ioDispatcher = Dispatchers.Unconfined)
        val ds = FakeSafTreeDataSource()
        val scanner = SafFolderScanner(repository = repo, dataSource = ds, ioDispatcher = Dispatchers.Unconfined)

        val musicTreeUri = "content://${FakeSafTreeDataSource.AUTHORITY}/tree/primary%3AMusic"
        ds.childrenByDirDocId["primary:Music"] = mutableListOf(
            SafChildEntry("primary:Music/rock", "", "rock", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 100L),
            SafChildEntry("primary:Music/empty_old", "", "empty_old", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 100L),
        )
        ds.childrenByDirDocId["primary:Music/rock"] = mutableListOf(
            SafChildEntry("primary:Music/rock/Master.mp3", "", "Master.mp3", "audio/mpeg", 9_000_000L, 450L),
        )
        ds.childrenByDirDocId["primary:Music/empty_old"] = mutableListOf()
        ds.probesByUri["primary:Music/rock/Master.mp3"] = ExtractedMediaProbe(
            durationMs = 515_000L,
            rawTitle = "Master of Puppets",
            rawArtist = "Metallica",
        )

        val rootId = (scanner.addSafRootFromGrant(
            SafGrantInfo(musicTreeUri, FakeSafTreeDataSource.AUTHORITY, "primary:Music", "Music", isWritable = true),
        ) as AddRootResult.Added).root.rootId
        val trackBefore = repo.allMediaItems.value.single()

        // Set a folder rule on `rock` AND an orphaned folder rule on `empty_old` so we verify neither creates a ghost folder!
        scanner.setFolderPracticeRule(rootId, "rock", "$musicTreeUri/document/primary:Music/rock", isPracticeFolder = true)
        scanner.setFolderExcludeFromGroupingRule(rootId, "empty_old", "$musicTreeUri/document/primary:Music/empty_old", isExcludedFromAutoGrouping = true)

        assertEquals(setOf("empty_old", "rock"), repo.browseFolder(rootId, "")!!.subfolders.map { it.name }.toSet())

        // Full rename: `rock` -> `metal`, and `empty_old` deleted
        ds.childrenByDirDocId.remove("primary:Music/rock")
        ds.childrenByDirDocId.remove("primary:Music/empty_old")
        ds.childrenByDirDocId["primary:Music"] = mutableListOf(
            SafChildEntry("primary:Music/metal", "", "metal", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 500L),
        )
        ds.childrenByDirDocId["primary:Music/metal"] = mutableListOf(
            SafChildEntry("primary:Music/metal/Master.mp3", "", "Master.mp3", "audio/mpeg", 9_000_000L, 450L),
        )
        ds.extractCalls.set(0)

        scanner.rescanRoot(rootId, incremental = true)

        assertEquals(0, ds.extractCalls.get())
        val rootSubfolders = repo.browseFolder(rootId, "")!!.subfolders.map { it.name }
        assertEquals("Old `rock` and `empty_old` folders must disappear completely", listOf("metal"), rootSubfolders)

        val metalBrowse = repo.browseFolder(rootId, "metal")!!
        assertEquals(listOf("Master.mp3"), metalBrowse.mediaItems.map { it.fileName })
        assertTrue("Practice rule from renamed `rock` must migrate to `metal`", metalBrowse.isCurrentPracticeFolder)
        assertEquals(trackBefore.itemId, metalBrowse.mediaItems.single().itemId)

        val remainingRules = backend.readSnapshot().folderRules.filter { it.rootId == rootId }.map { it.relPath }.toSet()
        assertEquals("Stale `rock` and `empty_old` folder_rules must be migrated/pruned", setOf("metal"), remainingRules)

        // Also verify case-renamed root re-add (`primary:music` -> `primary:Music` or `primary:rock` -> `primary:Rock`)
        val rockRootUriLower = "content://${FakeSafTreeDataSource.AUTHORITY}/tree/primary%3Arock"
        val rockRootUriUpper = "content://${FakeSafTreeDataSource.AUTHORITY}/tree/primary%3ARock"
        ds.childrenByDirDocId["primary:rock"] = mutableListOf()
        ds.childrenByDirDocId["primary:Rock"] = mutableListOf()

        val lowerRoot = (scanner.addSafRootFromGrant(
            SafGrantInfo(rockRootUriLower, FakeSafTreeDataSource.AUTHORITY, "primary:rock", "rock", isWritable = true),
        ) as AddRootResult.Added).root
        assertEquals("rock", lowerRoot.displayName)

        val reAddUpper = scanner.addSafRootFromGrant(
            SafGrantInfo(rockRootUriUpper, FakeSafTreeDataSource.AUTHORITY, "primary:Rock", "Rock", isWritable = true),
        )
        assertTrue(reAddUpper is AddRootResult.AlreadyCoveredByParent)
        val updatedRoot = repo.safRoots.value.first { it.rootId == lowerRoot.rootId }
        assertEquals("Rock", updatedRoot.displayName)
        assertEquals("primary:Rock", updatedRoot.treeDocId)
        assertEquals("Re-adding case-renamed root must update existing root instead of duplicating", 2, repo.safRoots.value.size)
    }

    @Test
    fun `database v3 schema and repository persist preserveFormants barSnap firstDownbeatMs stemMix and legacy countIn`() = runBlocking {
        // 1. Verify MiniPlayerDatabase.SCHEMA_STATEMENTS v3 columns on real sqlite3 engine
        val sqliteBin = File("/usr/bin/sqlite3")
        if (sqliteBin.canExecute()) {
            val stemEncoded = StemMixConfig(
                vocals = 0.30f,
                guitar = 1.35f,
                bass = 1.0f,
                drums = 0.85f,
                other = 1.0f,
                mutedStems = setOf(StemType.VOCALS),
                soloStem = StemType.GUITAR,
            ).toStorageString()
            val sql = buildString {
                appendLine("PRAGMA foreign_keys = ON;")
                for (stmt in MiniPlayerDatabase.SCHEMA_STATEMENTS) {
                    appendLine("$stmt;")
                }
                appendLine("INSERT INTO saf_roots (root_id, tree_uri, tree_doc_id, display_name, is_writable, mount_state, last_scan_at) VALUES (1, 'content://saf/1', 'primary:Music', 'Music', 1, 'MOUNTED', 100);")
                appendLine("INSERT INTO media_items (item_id, document_uri, root_id, parent_rel_path, file_name, mime_type, is_video, size_bytes, last_modified, duration_ms, parsed_title, fingerprint_key) VALUES (10, 'content://saf/1/doc/a', 1, 'Rock', '01.mp3', 'audio/mpeg', 0, 1024, 200, 180000, 'Track', '01.mp3|1024|180000');")
                appendLine("INSERT INTO media_items (item_id, document_uri, root_id, parent_rel_path, file_name, mime_type, is_video, size_bytes, last_modified, duration_ms, parsed_title, fingerprint_key) VALUES (11, 'content://saf/1/doc/b', 1, 'Rock', '02.mp3', 'audio/mpeg', 0, 1024, 200, 180000, 'Track2', '02.mp3|1024|180000');")
                // Item 10 uses v3 defaults
                appendLine("INSERT INTO track_practice_states (item_id, updated_at) VALUES (10, 500);")
                // Item 11 stores explicit v3 custom fields
                appendLine("INSERT INTO track_practice_states (item_id, preserve_formants, bar_snap_enabled, first_downbeat_ms, stem_mix, count_in_mode, updated_at) VALUES (11, 0, 0, 340, '$stemEncoded', 'SEC_2', 600);")
                appendLine("SELECT 'DEFAULTS', preserve_formants, bar_snap_enabled, first_downbeat_ms, stem_mix FROM track_practice_states WHERE item_id = 10;")
                appendLine("SELECT 'CUSTOM', preserve_formants, bar_snap_enabled, first_downbeat_ms, stem_mix, count_in_mode FROM track_practice_states WHERE item_id = 11;")
            }
            val proc = ProcessBuilder(sqliteBin.absolutePath, ":memory:")
                .redirectErrorStream(true)
                .start()
            proc.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(sql) }
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
            assertEquals("SQLite v3 DDL failed: $out", 0, proc.waitFor())
            assertTrue("Expected v3 defaults (1|1|0|), got: $out", out.contains("DEFAULTS|1|1|0|"))
            assertTrue("Expected v3 custom fields persisted, got: $out", out.contains("CUSTOM|0|0|340|$stemEncoded|SEC_2"))
        }

        // 2. Verify MediaRepository round-trip & isNonDefault detection for stemMix / preserveFormants / barSnap
        val backend = InMemoryMediaStoreBackend()
        val repo = MediaRepository(backend = backend, ioDispatcher = Dispatchers.Unconfined)
        val ds = FakeSafTreeDataSource()
        val scanner = SafFolderScanner(repository = repo, dataSource = ds, ioDispatcher = Dispatchers.Unconfined)
        val treeUri = "content://${FakeSafTreeDataSource.AUTHORITY}/tree/primary%3AMusic"
        ds.childrenByDirDocId["primary:Music"] = mutableListOf(
            SafChildEntry("primary:Music/Song.flac", "", "Song.flac", "audio/flac", 5_000_000L, 123L),
        )
        ds.probesByUri["primary:Music/Song.flac"] = ExtractedMediaProbe(durationMs = 200_000L, rawTitle = "Song", rawArtist = "Artist")
        scanner.addSafRootFromGrant(SafGrantInfo(treeUri, FakeSafTreeDataSource.AUTHORITY, "primary:Music", "Music", isWritable = true))
        val item = repo.allMediaItems.value.single()

        val customMix = StemMixConfig(
            vocals = 0.0f,
            guitar = 1.40f,
            bass = 1.10f,
            drums = 0.90f,
            other = 1.0f,
            mutedStems = setOf(StemType.VOCALS),
            soloStem = null,
        )
        val saved = PracticeState(
            itemId = item.itemId,
            pitchSemitones = -2,
            preserveFormants = false,
            barSnapEnabled = false,
            firstDownbeatMs = 420L,
            stemMix = customMix,
            countInMode = CountInMode.fromStoredValue("SEC_1"),
        )
        assertEquals(CountInMode.BEAT_4, saved.countInMode)
        repo.savePracticeState(saved)

        val loaded = repo.getPracticeState(item.itemId)
        assertFalse(loaded.preserveFormants)
        assertFalse(loaded.barSnapEnabled)
        assertEquals(420L, loaded.firstDownbeatMs)
        assertEquals(customMix, loaded.stemMix)
        assertEquals(CountInMode.BEAT_4, loaded.countInMode)
        assertTrue(repo.allMediaItems.value.single().hasPracticeRecord)
    }
}
