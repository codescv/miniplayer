package com.miniplayer.app.core.db

import com.miniplayer.app.core.MediaLibraryContract
import com.miniplayer.app.core.metadata.ArtistSplitter
import com.miniplayer.app.core.metadata.SmartFilenameParser
import com.miniplayer.app.core.model.*
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Reactive repository implementing [MediaLibraryContract] on top of [MediaStoreBackend] (`DESIGN.md` §6.2, §7.2).
 */
class MediaRepository(
    val backend: MediaStoreBackend,
    private val cacheRootDir: File? = null,
    private val filesRootDir: File? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MediaLibraryContract {

    companion object {
        fun normalizeRelPath(path: String): String =
            path.replace('\\', '/').trim('/').replace(Regex("/+"), "/")

        fun ancestorChainMostSpecificFirst(relPath: String): List<String> {
            val norm = normalizeRelPath(relPath)
            if (norm.isEmpty()) return listOf("")
            val parts = norm.split('/')
            val chain = ArrayList<String>(parts.size + 1)
            for (len in parts.size downTo 1) {
                chain.add(parts.subList(0, len).joinToString("/"))
            }
            chain.add("")
            return chain
        }

        fun resolveIsPracticeFolder(rulesByRelPath: Map<String, FolderRuleRow>, relPath: String): Boolean {
            if (rulesByRelPath.isEmpty()) return false
            for (anc in ancestorChainMostSpecificFirst(relPath)) {
                val rule = rulesByRelPath[anc]
                if (rule != null) return rule.isPracticeFolder
            }
            return false
        }

        fun resolveRespectNomedia(
            rulesByRelPath: Map<String, FolderRuleRow>,
            relPath: String,
            globalDefault: Boolean,
        ): Boolean {
            for (anc in ancestorChainMostSpecificFirst(relPath)) {
                val rule = rulesByRelPath[anc]
                if (rule?.respectNomedia != null) return rule.respectNomedia
            }
            return globalDefault
        }

        fun computeAlbumKey(album: String?, albumArtist: String?, trackArtist: String): String {
            val cleanAlbum = album?.trim()?.takeIf { it.isNotEmpty() } ?: return AlbumSummary.UNKNOWN_ALBUM_KEY
            val groupingArtist = (albumArtist?.trim()?.takeIf { it.isNotEmpty() } ?: trackArtist.trim())
                .ifEmpty { ArtistSplitter.UNKNOWN_ARTIST_DISPLAY }
            return "${cleanAlbum.lowercase(Locale.ROOT)}|${groupingArtist.lowercase(Locale.ROOT)}"
        }

        fun buildFingerprintKey(fileName: String, sizeBytes: Long, durationMs: Long): String =
            "${fileName.lowercase(Locale.ROOT)}|$sizeBytes|$durationMs"
    }

    private data class ComputedSnapshot(
        val safRoots: List<SafRoot> = emptyList(),
        val allItems: List<MediaItem> = emptyList(),
        val itemsById: Map<Long, MediaItem> = emptyMap(),
        val videoItems: List<MediaItem> = emptyList(),
        val albums: List<AlbumSummary> = emptyList(),
        val tracksByAlbumKey: Map<String, List<MediaItem>> = emptyMap(),
        val artists: List<ArtistSummary> = emptyList(),
        val artistDetailsByNorm: Map<String, ArtistDetail> = emptyMap(),
        val playlists: List<PlaylistSummary> = emptyList(),
        val playlistDetailsById: Map<Long, PlaylistDetail> = emptyMap(),
        val bookmarkedTracks: List<BookmarkedTrackItem> = emptyList(),
        val bookmarksByItemId: Map<Long, List<PracticeBookmark>> = emptyMap(),
        val rulesByRootAndPath: Map<Long, Map<String, FolderRuleRow>> = emptyMap(),
        val scannedFoldersByRoot: Map<Long, List<ScannedFolderRow>> = emptyMap(),
        val settings: AppSettings = AppSettings(),
    )

    private val computedFlow = MutableStateFlow(ComputedSnapshot())

    private val _safRoots = MutableStateFlow<List<SafRoot>>(emptyList())
    override val safRoots: StateFlow<List<SafRoot>> = _safRoots.asStateFlow()

    private val _allMediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
    override val allMediaItems: StateFlow<List<MediaItem>> = _allMediaItems.asStateFlow()

    private val _videoItems = MutableStateFlow<List<MediaItem>>(emptyList())
    override val videoItems: StateFlow<List<MediaItem>> = _videoItems.asStateFlow()

    private val _albums = MutableStateFlow<List<AlbumSummary>>(emptyList())
    override val albums: StateFlow<List<AlbumSummary>> = _albums.asStateFlow()

    private val _artists = MutableStateFlow<List<ArtistSummary>>(emptyList())
    override val artists: StateFlow<List<ArtistSummary>> = _artists.asStateFlow()

    private val _playlists = MutableStateFlow<List<PlaylistSummary>>(emptyList())
    override val playlists: StateFlow<List<PlaylistSummary>> = _playlists.asStateFlow()

    private val _bookmarkedTracks = MutableStateFlow<List<BookmarkedTrackItem>>(emptyList())
    override val bookmarkedTracks: StateFlow<List<BookmarkedTrackItem>> = _bookmarkedTracks.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    @Volatile
    var onActiveItemChanged: ((activeItemId: Long) -> Unit)? = null

    private var lastObservedPlayingItemId: Long = -1L

    init {
        refreshNow()
    }

    fun refreshNow() {
        val dbSnap = backend.readSnapshot()
        val computed = buildComputedSnapshot(dbSnap)
        computedFlow.value = computed
        _safRoots.value = computed.safRoots
        _allMediaItems.value = computed.allItems
        _videoItems.value = computed.videoItems
        _albums.value = computed.albums
        _artists.value = computed.artists
        _playlists.value = computed.playlists
        _bookmarkedTracks.value = computed.bookmarkedTracks
        _settings.value = computed.settings
    }

    suspend fun refresh() = withContext(ioDispatcher) {
        refreshNow()
    }

    fun getMediaItemById(itemId: Long): MediaItem? = computedFlow.value.itemsById[itemId]

    fun getFolderRule(rootId: Long, relPath: String): FolderRuleRow? =
        computedFlow.value.rulesByRootAndPath[rootId]?.get(normalizeRelPath(relPath))

    fun resolveRespectNomediaForFolder(rootId: Long, relPath: String): Boolean {
        val rules = computedFlow.value.rulesByRootAndPath[rootId].orEmpty()
        return resolveRespectNomedia(rules, relPath, _settings.value.respectSubfolderNomedia)
    }

    override suspend fun isPracticeFolder(rootId: Long, relPath: String): Boolean = withContext(ioDispatcher) {
        val rules = computedFlow.value.rulesByRootAndPath[rootId].orEmpty()
        resolveIsPracticeFolder(rules, relPath)
    }

    override suspend fun browseFolder(
        rootId: Long,
        relPath: String,
        flat: Boolean,
    ): FolderBrowseState? = withContext(ioDispatcher) {
        val snap = computedFlow.value
        val root = snap.safRoots.firstOrNull { it.rootId == rootId } ?: return@withContext null
        val normPath = normalizeRelPath(relPath)
        val rulesMap = snap.rulesByRootAndPath[rootId].orEmpty()
        val rootItems = snap.allItems.filter { it.rootId == rootId }

        // Build breadcrumb trail: [("RootName", ""), ("Sub1", "Sub1"), ("Sub2", "Sub1/Sub2")]
        val breadcrumbs = ArrayList<Pair<String, String>>()
        breadcrumbs.add(root.displayName to "")
        if (normPath.isNotEmpty()) {
            val segs = normPath.split('/')
            val acc = StringBuilder()
            for (seg in segs) {
                if (acc.isNotEmpty()) acc.append('/')
                acc.append(seg)
                breadcrumbs.add(seg to acc.toString())
            }
        }

        val isCurrentPractice = resolveIsPracticeFolder(rulesMap, normPath)

        if (flat) {
            val matchingItems = rootItems.filter { item ->
                val p = normalizeRelPath(item.parentRelPath)
                normPath.isEmpty() || p == normPath || p.startsWith("$normPath/")
            }.sortedWith { a, b -> SmartFilenameParser.naturalCompare(a.fileName, b.fileName) }

            return@withContext FolderBrowseState(
                root = root,
                currentRelPath = normPath,
                breadcrumbs = breadcrumbs,
                subfolders = emptyList(),
                mediaItems = matchingItems,
                isCurrentPracticeFolder = isCurrentPractice,
            )
        }

        // TREE mode: gather direct subfolders from scanned_folders + media_items + folder_rules
        val scannedByRel = snap.scannedFoldersByRoot[rootId].orEmpty().associateBy { normalizeRelPath(it.relPath) }
        val directChildNames = LinkedHashSet<String>()

        fun collectChildFromPath(candidateRel: String) {
            val clean = normalizeRelPath(candidateRel)
            if (clean.isEmpty()) return
            val remainder = if (normPath.isEmpty()) {
                clean
            } else if (clean.startsWith("$normPath/")) {
                clean.removePrefix("$normPath/")
            } else {
                return
            }
            val firstSeg = remainder.substringBefore('/').trim()
            if (firstSeg.isNotEmpty()) {
                directChildNames.add(firstSeg)
            }
        }

        for (sf in scannedByRel.values) collectChildFromPath(sf.relPath)
        for (item in rootItems) collectChildFromPath(item.parentRelPath)
        for (ruleRel in rulesMap.keys) collectChildFromPath(ruleRel)

        val subfolders = directChildNames
            .sortedWith(SmartFilenameParser.NaturalComparator)
            .map { childName ->
                val childRel = if (normPath.isEmpty()) childName else "$normPath/$childName"
                val scanned = scannedByRel[childRel]
                val explicitRule = rulesMap[childRel]
                val folderUri = scanned?.folderUri
                    ?: explicitRule?.folderUri
                    ?: "${root.treeUri}#rel=${childRel}"
                val recursiveCount = rootItems.count { item ->
                    val p = normalizeRelPath(item.parentRelPath)
                    p == childRel || p.startsWith("$childRel/")
                }
                SubfolderEntry(
                    folderUri = folderUri,
                    rootId = rootId,
                    relPath = childRel,
                    name = childName,
                    itemCount = recursiveCount,
                    isPracticeFolder = resolveIsPracticeFolder(rulesMap, childRel),
                    hasNomedia = scanned?.hasNomedia ?: false,
                    respectNomedia = explicitRule?.respectNomedia,
                )
            }

        val directItems = rootItems
            .filter { normalizeRelPath(it.parentRelPath) == normPath }
            .sortedWith { a, b -> SmartFilenameParser.naturalCompare(a.fileName, b.fileName) }

        FolderBrowseState(
            root = root,
            currentRelPath = normPath,
            breadcrumbs = breadcrumbs,
            subfolders = subfolders,
            mediaItems = directItems,
            isCurrentPracticeFolder = isCurrentPractice,
        )
    }

    override suspend fun getAlbumTracks(albumKey: String): List<MediaItem> = withContext(ioDispatcher) {
        computedFlow.value.tracksByAlbumKey[albumKey].orEmpty()
    }

    override suspend fun getArtistDetail(artistNorm: String): ArtistDetail? = withContext(ioDispatcher) {
        val norm = ArtistSplitter.normalizeKey(artistNorm)
        computedFlow.value.artistDetailsByNorm[norm]
    }

    override suspend fun getPlaylistDetail(playlistId: Long): PlaylistDetail? = withContext(ioDispatcher) {
        computedFlow.value.playlistDetailsById[playlistId]
    }

    override suspend fun createPlaylist(name: String, isPracticePlaylist: Boolean): Long = withContext(ioDispatcher) {
        val cleanName = name.trim().ifEmpty { if (isPracticePlaylist) "新建练习列表" else "新建播放列表" }
        val id = backend.createPlaylist(cleanName, isPracticePlaylist, System.currentTimeMillis())
        refreshNow()
        id
    }

    override suspend fun renamePlaylist(playlistId: Long, newName: String) = withContext(ioDispatcher) {
        val cleanName = newName.trim()
        if (cleanName.isNotEmpty()) {
            backend.renamePlaylist(playlistId, cleanName, System.currentTimeMillis())
            refreshNow()
        }
    }

    override suspend fun setPlaylistPracticeMode(playlistId: Long, isPracticePlaylist: Boolean) = withContext(ioDispatcher) {
        backend.setPlaylistPracticeMode(playlistId, isPracticePlaylist, System.currentTimeMillis())
        refreshNow()
    }

    override suspend fun deletePlaylist(playlistId: Long) = withContext(ioDispatcher) {
        backend.deletePlaylist(playlistId)
        refreshNow()
    }

    override suspend fun addItemsToPlaylist(playlistId: Long, itemIds: List<Long>) = withContext(ioDispatcher) {
        if (itemIds.isNotEmpty()) {
            backend.addItemsToPlaylist(playlistId, itemIds.distinct(), System.currentTimeMillis())
            refreshNow()
        }
    }

    override suspend fun removeItemFromPlaylist(playlistId: Long, itemId: Long) = withContext(ioDispatcher) {
        backend.removeItemFromPlaylist(playlistId, itemId, System.currentTimeMillis())
        refreshNow()
    }

    override suspend fun reorderPlaylistItems(playlistId: Long, orderedItemIds: List<Long>) = withContext(ioDispatcher) {
        backend.reorderPlaylistItems(playlistId, orderedItemIds, System.currentTimeMillis())
        refreshNow()
    }

    override suspend fun getPracticeState(itemId: Long): PracticeState = withContext(ioDispatcher) {
        backend.getPracticeState(itemId)
    }

    override fun observeBookmarks(itemId: Long): Flow<List<PracticeBookmark>> =
        computedFlow
            .map { it.bookmarksByItemId[itemId].orEmpty() }
            .distinctUntilChanged()

    override suspend fun getBookmarks(itemId: Long): List<PracticeBookmark> = withContext(ioDispatcher) {
        backend.getBookmarks(itemId)
    }

    override suspend fun savePracticeState(state: PracticeState) = withContext(ioDispatcher) {
        val clamped = state.copy(
            practiceSpeed = PracticeMath.clampSpeed(state.practiceSpeed),
            pitchSemitones = state.pitchSemitones.coerceIn(-6, 6),
            pitchCents = state.pitchCents.coerceIn(-50, 50),
            bpm = state.bpm.coerceIn(30, 300),
            videoZoom = state.videoZoom.coerceIn(1.0f, 4.0f),
        )
        backend.upsertPracticeState(clamped, System.currentTimeMillis())
        refreshNow()
    }

    override suspend fun saveBookmark(
        itemId: Long,
        label: String,
        aMs: Long,
        bMs: Long,
        savedSpeed: Float?,
        bookmarkId: Long?,
    ): PracticeBookmark = withContext(ioDispatcher) {
        val durationMs = computedFlow.value.itemsById[itemId]?.durationMs ?: 0L
        val (normA, normB) = PracticeMath.normalizeLoopPoints(aMs, bMs, durationMs)
            ?: (minOf(aMs, bMs).coerceAtLeast(0L) to (minOf(aMs, bMs).coerceAtLeast(0L) + PracticeMath.MIN_LOOP_SPAN_MS))
        val cleanLabel = label.trim().ifEmpty { "段落 ${PracticeMath.formatTimeMs(normA)}" }
        val cleanSpeed = savedSpeed?.let { PracticeMath.clampSpeed(it) }
        val saved = backend.upsertBookmark(
            itemId = itemId,
            label = cleanLabel,
            aMs = normA,
            bMs = normB,
            savedSpeed = cleanSpeed,
            bookmarkId = bookmarkId,
            nowMs = System.currentTimeMillis(),
        )
        refreshNow()
        saved
    }

    override suspend fun deleteBookmark(bookmarkId: Long) = withContext(ioDispatcher) {
        backend.deleteBookmark(bookmarkId)
        refreshNow()
    }

    override suspend fun savePlaybackPosition(itemId: Long, positionMs: Long) = withContext(ioDispatcher) {
        backend.updatePlaybackPosition(itemId, positionMs, System.currentTimeMillis())
        // Update in-memory position cleanly and refresh flows
        refreshNow()
        if (lastObservedPlayingItemId != itemId) {
            lastObservedPlayingItemId = itemId
            onActiveItemChanged?.invoke(itemId)
        }
    }

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = withContext(ioDispatcher) {
        val updated = transform(_settings.value)
        backend.saveSettings(updated)
        refreshNow()
    }

    override suspend fun clearCaches(): Long = withContext(ioDispatcher) {
        var freedBytes = 0L
        // 1. Clear waveform cache & temporary staging/update dirs in cacheDir
        cacheRootDir?.let { cache ->
            for (subName in listOf("waveforms", "tag_stage", "updates")) {
                freedBytes += deleteDirContents(File(cache, subName))
            }
        }
        // 2. Clear extracted embedded/video frame covers (preserve override covers referenced in media_metadata_overrides!)
        filesRootDir?.let { files ->
            val coversDir = File(files, "covers")
            if (coversDir.isDirectory) {
                val protectedPaths = backend.readSnapshot().overridesByItemId.values
                    .mapNotNull { it.coverLocalPath }
                    .toSet()
                coversDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.absolutePath !in protectedPaths) {
                        val len = f.length()
                        if (f.delete()) freedBytes += len
                    }
                }
            }
        }
        backend.clearEmbeddedCoverRefs()
        refreshNow()
        freedBytes
    }

    private fun deleteDirContents(dir: File): Long {
        if (!dir.exists()) return 0L
        var bytes = 0L
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                bytes += deleteDirContents(child)
                child.delete()
            } else {
                val len = child.length()
                if (child.delete()) bytes += len
            }
        }
        return bytes
    }

    // --- Pure Reactive Snapshot Aggregation ---

    private fun buildComputedSnapshot(db: DbSnapshot): ComputedSnapshot {
        val rulesByRootAndPath = HashMap<Long, Map<String, FolderRuleRow>>()
        for ((rootId, list) in db.folderRules.groupBy { it.rootId }) {
            rulesByRootAndPath[rootId] = list.associateBy { normalizeRelPath(it.relPath) }
        }
        val scannedByRoot = db.scannedFolders.groupBy { it.rootId }
        val rootsById = db.safRoots.associateBy { it.rootId }

        val itemsById = LinkedHashMap<Long, MediaItem>(db.mediaItems.size)
        val countPerRoot = HashMap<Long, Int>()

        for (row in db.mediaItems) {
            val root = rootsById[row.rootId] ?: continue
            val ov = db.overridesByItemId[row.itemId]
            val pState = db.practiceStatesByItemId[row.itemId]
            val bms = db.bookmarksByItemId[row.itemId].orEmpty()
            val rulesForRoot = rulesByRootAndPath[row.rootId].orEmpty()

            // Effective metadata priority chain: COALESCE(override, embedded, parsedFilename)
            val effectiveTitle = ov?.title?.takeIf { it.isNotBlank() }
                ?: row.rawTitle?.takeIf { it.isNotBlank() }
                ?: row.parsedTitle.ifBlank { row.fileName }

            val effectiveArtist = ov?.artist?.takeIf { it.isNotBlank() }
                ?: row.rawArtist?.takeIf { it.isNotBlank() }
                ?: row.parsedArtist?.takeIf { it.isNotBlank() }
                ?: ArtistSplitter.UNKNOWN_ARTIST_DISPLAY

            val effectiveAlbum = ov?.album?.takeIf { it.isNotBlank() }
                ?: row.rawAlbum?.takeIf { it.isNotBlank() }
                ?: row.parsedAlbum?.takeIf { it.isNotBlank() }

            val effectiveAlbumArtist = ov?.albumArtist?.takeIf { it.isNotBlank() }
                ?: row.rawAlbumArtist?.takeIf { it.isNotBlank() }

            val effectiveTrackNum = ov?.trackNum ?: row.rawTrackNum
            val effectiveYear = ov?.year ?: row.rawYear

            val effectiveCover = ov?.coverLocalPath?.takeIf { it.isNotBlank() }
                ?: row.embeddedCoverPath?.takeIf { it.isNotBlank() }
                ?: row.folderCoverUri?.takeIf { it.isNotBlank() }

            val inPracticeFolder = resolveIsPracticeFolder(rulesForRoot, row.parentRelPath)
            val hasPracticeRec = bms.isNotEmpty() || (pState?.isNonDefault == true)

            val item = MediaItem(
                itemId = row.itemId,
                documentUri = row.documentUri,
                rootId = row.rootId,
                parentRelPath = normalizeRelPath(row.parentRelPath),
                fileName = row.fileName,
                mimeType = row.mimeType,
                isVideo = row.isVideo,
                sizeBytes = row.sizeBytes,
                lastModified = row.lastModified,
                durationMs = row.durationMs,
                width = row.width,
                height = row.height,
                title = effectiveTitle,
                artist = effectiveArtist,
                album = effectiveAlbum,
                albumArtist = effectiveAlbumArtist,
                trackNumber = effectiveTrackNum,
                year = effectiveYear,
                coverPathOrUri = effectiveCover,
                hasMetadataOverride = ov != null,
                isBackingTrack = row.isBackingTrack,
                isRootWritable = root.isWritable,
                isInPracticeFolder = inPracticeFolder,
                lastPositionMs = row.lastPositionMs,
                bookmarkCount = bms.size,
                hasPracticeRecord = hasPracticeRec,
                savedPracticeSpeed = pState?.practiceSpeed ?: 1.0f,
                savedSemitones = pState?.pitchSemitones ?: 0,
            )
            itemsById[item.itemId] = item
            countPerRoot[row.rootId] = (countPerRoot[row.rootId] ?: 0) + 1
        }

        val allItems = itemsById.values.sortedWith { a, b ->
            SmartFilenameParser.naturalCompare(a.title, b.title)
        }

        val safRoots = db.safRoots.map { r ->
            val rootRules = rulesByRootAndPath[r.rootId].orEmpty()
            val rootRule = rootRules[""]
            SafRoot(
                rootId = r.rootId,
                treeUri = r.treeUri,
                treeDocId = r.treeDocId,
                displayName = r.displayName,
                isWritable = r.isWritable,
                mountState = r.mountState,
                itemCount = countPerRoot[r.rootId] ?: 0,
                isPracticeFolder = rootRule?.isPracticeFolder ?: false,
                respectNomedia = rootRule?.respectNomedia,
                lastScanAt = r.lastScanAt,
            )
        }

        val videoItems = allItems
            .filter { it.isVideo }
            .sortedWith { a, b -> SmartFilenameParser.naturalCompare(a.fileName, b.fileName) }

        val audioItems = allItems.filter { !it.isVideo }

        // --- Album Aggregation (`(Album, AlbumArtist)` + trailing `未知专辑`) ---
        val tracksByAlbumKey = LinkedHashMap<String, MutableList<MediaItem>>()
        for (track in audioItems) {
            val key = computeAlbumKey(track.album, track.albumArtist, track.artist)
            tracksByAlbumKey.getOrPut(key) { ArrayList() }.add(track)
        }

        val trackOrderComparator = Comparator<MediaItem> { a, b ->
            val ta = a.trackNumber ?: Int.MAX_VALUE
            val tb = b.trackNumber ?: Int.MAX_VALUE
            val tc = ta.compareTo(tb)
            if (tc != 0) tc else SmartFilenameParser.naturalCompare(a.fileName, b.fileName)
        }

        val sortedAlbumMap = LinkedHashMap<String, List<MediaItem>>()
        val knownAlbumSummaries = ArrayList<AlbumSummary>()
        var unknownAlbumSummary: AlbumSummary? = null

        for ((key, list) in tracksByAlbumKey) {
            val sortedTracks = list.sortedWith(trackOrderComparator)
            sortedAlbumMap[key] = sortedTracks
            if (key == AlbumSummary.UNKNOWN_ALBUM_KEY) {
                unknownAlbumSummary = AlbumSummary(
                    albumKey = AlbumSummary.UNKNOWN_ALBUM_KEY,
                    title = AlbumSummary.UNKNOWN_ALBUM_TITLE,
                    artist = "${sortedTracks.size} 首待整理",
                    year = null,
                    coverPathOrUri = sortedTracks.firstNotNullOfOrNull { it.coverPathOrUri },
                    trackCount = sortedTracks.size,
                    isUnknownAlbum = true,
                )
            } else {
                val repTitle = sortedTracks.firstNotNullOfOrNull { it.album?.trim()?.takeIf { s -> s.isNotEmpty() } }
                    ?: AlbumSummary.UNKNOWN_ALBUM_TITLE
                val explicitAlbArt = sortedTracks.firstNotNullOfOrNull { it.albumArtist?.trim()?.takeIf { s -> s.isNotEmpty() } }
                val distinctTrackArtists = sortedTracks.map { it.artist }.distinct()
                val repArtist = explicitAlbArt
                    ?: if (distinctTrackArtists.size == 1) distinctTrackArtists[0] else "Various Artists"
                val repYear = sortedTracks.mapNotNull { it.year }.maxOrNull()
                val repCover = sortedTracks.firstNotNullOfOrNull { it.coverPathOrUri }
                knownAlbumSummaries.add(
                    AlbumSummary(
                        albumKey = key,
                        title = repTitle,
                        artist = repArtist,
                        year = repYear,
                        coverPathOrUri = repCover,
                        trackCount = sortedTracks.size,
                        isUnknownAlbum = false,
                    ),
                )
            }
        }

        knownAlbumSummaries.sortWith { a, b -> SmartFilenameParser.naturalCompare(a.title, b.title) }
        val allAlbumSummaries = ArrayList<AlbumSummary>(knownAlbumSummaries.size + 1).apply {
            addAll(knownAlbumSummaries)
            if (unknownAlbumSummary != null) add(unknownAlbumSummary)
        }
        val albumSummaryByKey = allAlbumSummaries.associateBy { it.albumKey }

        // --- Multi-Artist Aggregation (`media_item_artists`) ---
        data class ArtistBucket(
            var displayName: String,
            val tracks: MutableList<MediaItem> = ArrayList(),
            val albumKeys: LinkedHashSet<String> = LinkedHashSet(),
        )
        val artistBuckets = LinkedHashMap<String, ArtistBucket>()
        for (track in audioItems) {
            val dbArtists = db.artistsByItemId[track.itemId]
            val splits = if (!dbArtists.isNullOrEmpty()) {
                dbArtists.map { it.artistNorm to it.artistDisplay }
            } else {
                ArtistSplitter.split(track.artist, track.albumArtist).map { it.norm to it.display }
            }
            val albKey = computeAlbumKey(track.album, track.albumArtist, track.artist)
            for ((norm, disp) in splits) {
                val bucket = artistBuckets.getOrPut(norm) { ArtistBucket(disp) }
                bucket.tracks.add(track)
                if (albKey != AlbumSummary.UNKNOWN_ALBUM_KEY) {
                    bucket.albumKeys.add(albKey)
                }
            }
        }

        val artistSummaries = ArrayList<ArtistSummary>(artistBuckets.size)
        val artistDetails = HashMap<String, ArtistDetail>(artistBuckets.size)

        for ((norm, bucket) in artistBuckets) {
            val sortedArtistTracks = bucket.tracks.sortedWith(
                compareBy<MediaItem> { it.album == null }
                    .thenComparator { a, b -> SmartFilenameParser.naturalCompare(a.displayAlbum, b.displayAlbum) }
                    .then(trackOrderComparator),
            )
            val artistAlbums = bucket.albumKeys.mapNotNull { albumSummaryByKey[it] }
                .sortedWith { a, b -> SmartFilenameParser.naturalCompare(a.title, b.title) }

            val summary = ArtistSummary(
                artistNorm = norm,
                displayName = bucket.displayName,
                albumCount = artistAlbums.size,
                trackCount = sortedArtistTracks.size,
                coverPathOrUri = sortedArtistTracks.firstNotNullOfOrNull { it.coverPathOrUri },
            )
            artistSummaries.add(summary)
            artistDetails[norm] = ArtistDetail(
                artist = summary,
                albums = artistAlbums,
                tracks = sortedArtistTracks,
            )
        }
        artistSummaries.sortWith(
            compareBy<ArtistSummary> { it.artistNorm == ArtistSplitter.UNKNOWN_ARTIST_NORM }
                .thenComparator { a, b -> SmartFilenameParser.naturalCompare(a.displayName, b.displayName) },
        )

        // --- Playlists & Practice Playlists ---
        val plItemsByPlaylist = db.playlistItems.groupBy { it.playlistId }
        val playlistSummaries = ArrayList<PlaylistSummary>(db.playlists.size)
        val playlistDetails = HashMap<Long, PlaylistDetail>(db.playlists.size)

        for (pl in db.playlists) {
            val orderedItems = plItemsByPlaylist[pl.playlistId]
                .orEmpty()
                .sortedBy { it.sortOrder }
                .mapNotNull { itemsById[it.itemId] }
            val totalBms = orderedItems.sumOf { it.bookmarkCount }
            val covers = orderedItems.mapNotNull { it.coverPathOrUri }.distinct().take(4)

            val summary = PlaylistSummary(
                playlistId = pl.playlistId,
                name = pl.name,
                isPracticePlaylist = pl.isPracticePlaylist,
                itemCount = orderedItems.size,
                totalBookmarks = totalBms,
                coverPaths = covers,
                updatedAt = pl.updatedAt,
            )
            playlistSummaries.add(summary)
            playlistDetails[pl.playlistId] = PlaylistDetail(summary = summary, items = orderedItems)
        }

        // --- Bookmarked Tracks (`[🔖 含段落书签]`) ---
        val bookmarkedTracks = ArrayList<BookmarkedTrackItem>()
        for ((itemId, bms) in db.bookmarksByItemId) {
            if (bms.isEmpty()) continue
            val item = itemsById[itemId] ?: continue
            bookmarkedTracks.add(BookmarkedTrackItem(mediaItem = item, bookmarks = bms))
        }
        bookmarkedTracks.sortWith { a, b ->
            SmartFilenameParser.naturalCompare(a.mediaItem.title, b.mediaItem.title)
        }

        return ComputedSnapshot(
            safRoots = safRoots,
            allItems = allItems,
            itemsById = itemsById,
            videoItems = videoItems,
            albums = allAlbumSummaries,
            tracksByAlbumKey = sortedAlbumMap,
            artists = artistSummaries,
            artistDetailsByNorm = artistDetails,
            playlists = playlistSummaries,
            playlistDetailsById = playlistDetails,
            bookmarkedTracks = bookmarkedTracks,
            bookmarksByItemId = db.bookmarksByItemId,
            rulesByRootAndPath = rulesByRootAndPath,
            scannedFoldersByRoot = scannedByRoot,
            settings = db.settings,
        )
    }
}
