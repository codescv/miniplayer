package com.miniplayer.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miniplayer.app.core.CoreRegistry
import com.miniplayer.app.core.MiniPlayerCoreProvider
import com.miniplayer.app.core.db.MediaRepository
import com.miniplayer.app.core.metadata.DefaultMetadataEngine
import com.miniplayer.app.core.metadata.SmartFilenameParser
import com.miniplayer.app.core.model.*
import com.miniplayer.app.player.PlayerController
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MainTab {
    LIBRARY,
    PLAYLISTS,
}

sealed class UiUpdateState {
    data object Idle : UiUpdateState()
    data object Checking : UiUpdateState()
    data class Checked(val info: UpdateInfo) : UiUpdateState()
    data class Downloading(
        val info: UpdateInfo,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UiUpdateState()
    data class ReadyToInstall(val info: UpdateInfo, val apkFile: File) : UiUpdateState()
    data class Error(val message: String) : UiUpdateState()
}

@OptIn(ExperimentalCoroutinesApi::class)
class MiniPlayerViewModel(application: Application) : AndroidViewModel(application) {
    val core: MiniPlayerCoreProvider = CoreRegistry.get(application)

    val playerController = PlayerController(
        context = application,
        mainScope = viewModelScope,
        loadPracticeState = { itemId -> core.library.getPracticeState(itemId) },
        persistPracticeState = { state ->
            viewModelScope.launch(Dispatchers.IO) {
                core.library.savePracticeState(state)
            }
        },
        persistPlaybackPosition = { itemId, posMs ->
            viewModelScope.launch(Dispatchers.IO) {
                core.library.savePlaybackPosition(itemId, posMs)
            }
        },
    )

    // Library & Settings Flows from Core
    val safRoots: StateFlow<List<SafRoot>> = core.library.safRoots
    val allMediaItems: StateFlow<List<MediaItem>> = core.library.allMediaItems
    val videoItems: StateFlow<List<MediaItem>> = core.library.videoItems
    val albums: StateFlow<List<AlbumSummary>> = core.library.albums
    val artists: StateFlow<List<ArtistSummary>> = core.library.artists
    val playlists: StateFlow<List<PlaylistSummary>> = core.library.playlists
    val bookmarkedTracks: StateFlow<List<BookmarkedTrackItem>> = core.library.bookmarkedTracks
    val settings: StateFlow<AppSettings> = core.library.settings
    val scanProgress: StateFlow<ScanProgress> = core.scanner.scanProgress

    // Player & Practice Engine Flows
    val playbackSnapshot = playerController.snapshot
    val practiceState = playerController.loopEngine.practiceState
    val isPracticeModeActive = playerController.loopEngine.isPracticeModeActive
    val speedTrainer = playerController.loopEngine.speedTrainer
    val completedLoopCount = playerController.loopEngine.completedLoopCount
    val usingFastVideoSeek = playerController.loopEngine.usingFastVideoSeek
    val activeBookmarkId = playerController.loopEngine.activeBookmarkId
    val countInProgress = playerController.metronome.progress
    val waveformBars = playerController.waveformBars
    val detectedBpm: StateFlow<Int?> = playerController.detectedBpm
    val isDetectingBpm: StateFlow<Boolean> = playerController.isDetectingBpm
    val hapticTicks = playerController.hapticTicks

    // Navigation & View States
    private val _selectedTab = MutableStateFlow(MainTab.LIBRARY)
    val selectedTab: StateFlow<MainTab> = _selectedTab.asStateFlow()

    private val _libraryView = MutableStateFlow(LibraryView.FOLDERS)
    val libraryView: StateFlow<LibraryView> = _libraryView.asStateFlow()

    private val _folderDisplayMode = MutableStateFlow(FolderDisplayMode.TREE)
    val folderDisplayMode: StateFlow<FolderDisplayMode> = _folderDisplayMode.asStateFlow()

    private val _sortOrders = MutableStateFlow(
        mapOf(
            LibraryView.FOLDERS to SortOrder.NAME_ASC,
            LibraryView.ALBUMS to SortOrder.NAME_ASC,
            LibraryView.ARTISTS to SortOrder.NAME_ASC,
            LibraryView.VIDEOS to SortOrder.NAME_ASC,
        ),
    )
    val sortOrders: StateFlow<Map<LibraryView, SortOrder>> = _sortOrders.asStateFlow()

    private val _librarySearchQuery = MutableStateFlow("")
    val librarySearchQuery: StateFlow<String> = _librarySearchQuery.asStateFlow()

    private val _playlistFilter = MutableStateFlow(PlaylistFilter.ALL)
    val playlistFilter: StateFlow<PlaylistFilter> = _playlistFilter.asStateFlow()

    private val _playlistSearchQuery = MutableStateFlow("")
    val playlistSearchQuery: StateFlow<String> = _playlistSearchQuery.asStateFlow()

    // Secondary Drill-Down States
    private val _activeFolderRootId = MutableStateFlow<Long?>(null)
    private val _activeFolderRelPath = MutableStateFlow("")
    private val _folderBrowseState = MutableStateFlow<FolderBrowseState?>(null)
    val folderBrowseState: StateFlow<FolderBrowseState?> = _folderBrowseState.asStateFlow()

    private val _activeAlbum = MutableStateFlow<AlbumSummary?>(null)
    val activeAlbum: StateFlow<AlbumSummary?> = _activeAlbum.asStateFlow()
    private val _activeAlbumTracks = MutableStateFlow<List<MediaItem>>(emptyList())
    val activeAlbumTracks: StateFlow<List<MediaItem>> = _activeAlbumTracks.asStateFlow()

    private val _activeArtistDetail = MutableStateFlow<ArtistDetail?>(null)
    val activeArtistDetail: StateFlow<ArtistDetail?> = _activeArtistDetail.asStateFlow()

    private val _activePlaylistDetail = MutableStateFlow<PlaylistDetail?>(null)
    val activePlaylistDetail: StateFlow<PlaylistDetail?> = _activePlaylistDetail.asStateFlow()

    // Modal & Sheet Visibility States
    private val _showNowPlaying = MutableStateFlow(false)
    val showNowPlaying: StateFlow<Boolean> = _showNowPlaying.asStateFlow()

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    private val _showLanStudio = MutableStateFlow(false)
    val showLanStudio: StateFlow<Boolean> = _showLanStudio.asStateFlow()

    private val _playlistPickerTargetItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val playlistPickerTargetItems: StateFlow<List<MediaItem>> = _playlistPickerTargetItems.asStateFlow()

    // Bookmarks & Lyrics for Current Track
    val currentTrackBookmarks: StateFlow<List<PracticeBookmark>> = playbackSnapshot
        .map { it.currentItem?.itemId }
        .distinctUntilChanged()
        .flatMapLatest { itemId ->
            if (itemId == null) flowOf(emptyList()) else core.library.observeBookmarks(itemId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _currentCoverBytes = MutableStateFlow<ByteArray?>(null)
    val currentCoverBytes: StateFlow<ByteArray?> = _currentCoverBytes.asStateFlow()

    private val _currentLyrics = MutableStateFlow<LyricsData?>(null)
    val currentLyrics: StateFlow<LyricsData?> = _currentLyrics.asStateFlow()

    private val _isLoadingLyrics = MutableStateFlow(false)
    val isLoadingLyrics: StateFlow<Boolean> = _isLoadingLyrics.asStateFlow()

    private var lastForegroundScanAtMs: Long = 0L

    // Metadata Editor & Online Auto-Match States
    private val _metadataEditTargets = MutableStateFlow<List<MediaItem>>(emptyList())
    val metadataEditTargets: StateFlow<List<MediaItem>> = _metadataEditTargets.asStateFlow()

    private val _onlineCandidates = MutableStateFlow<List<MetadataCandidate>>(emptyList())
    val onlineCandidates: StateFlow<List<MetadataCandidate>> = _onlineCandidates.asStateFlow()

    private val _isSearchingCandidates = MutableStateFlow(false)
    val isSearchingCandidates: StateFlow<Boolean> = _isSearchingCandidates.asStateFlow()

    private val _batchMatchPreview = MutableStateFlow<List<BatchMatchItem>?>(null)
    val batchMatchPreview: StateFlow<List<BatchMatchItem>?> = _batchMatchPreview.asStateFlow()

    private val _batchProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val batchProgress: StateFlow<Pair<Int, Int>?> = _batchProgress.asStateFlow()

    private val _isCommittingBatch = MutableStateFlow(false)
    val isCommittingBatch: StateFlow<Boolean> = _isCommittingBatch.asStateFlow()

    // Settings Cache & Auto-Update States
    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    private val _updateState = MutableStateFlow<UiUpdateState>(UiUpdateState.Idle)
    val updateState: StateFlow<UiUpdateState> = _updateState.asStateFlow()

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    init {
        // Count-in click sound is always enabled by default
        playerController.loopEngine.setClickSoundEnabled(true)
        // Keep queue metadata fresh when overrides or scans update MediaItems
        viewModelScope.launch {
            allMediaItems.collect { items ->
                if (items.isNotEmpty()) {
                    val map = items.associateBy { it.itemId }
                    playerController.refreshMetadataInQueue(map)
                }
                refreshActiveDrillDowns()
            }
        }
        // Refresh active folder/album/artist/playlist drill-downs whenever snapshotVersion increments
        // (including empty subfolder or case-only folder renames like `rock` -> `Rock`)
        viewModelScope.launch {
            core.library.snapshotVersion
                .drop(1)
                .collect {
                    refreshActiveDrillDowns()
                }
        }
        // Load cached/local lyrics automatically & flush any deferred physical tag writes when track changes (even while paused)
        viewModelScope.launch {
            playbackSnapshot
                .map { it.currentItem }
                .distinctUntilChangedBy { it?.itemId }
                .collect { item ->
                    (core.metadata as? DefaultMetadataEngine)?.let { engine ->
                        launch(Dispatchers.IO) {
                            runCatching { engine.flushDeferredFileWrites(currentlyPlayingItemId = item?.itemId) }
                        }
                    }
                    _currentCoverBytes.value = loadLocalCoverBytes(item?.coverPathOrUri)
                    _currentLyrics.value = null
                    if (item != null && !item.isVideo) {
                        _currentLyrics.value = core.metadata.getLyrics(item, fetchOnlineIfMissing = false)
                    }
                }
        }
        // Launch startup incremental rescan if enabled
        viewModelScope.launch {
            if (settings.value.autoRescanOnLaunch && safRoots.value.isNotEmpty()) {
                lastForegroundScanAtMs = System.currentTimeMillis()
                core.scanner.rescanAllRoots(incremental = true)
            }
        }
    }

    fun emitMessage(msg: String) {
        _snackbarMessages.tryEmit(msg)
    }

    // --- Top-Level Navigation ---

    fun selectTab(tab: MainTab) {
        _selectedTab.value = tab
    }

    /** Mandatory single-selection: tapping the already-active chip keeps it selected. */
    fun selectLibraryView(view: LibraryView) {
        _libraryView.value = view
    }

    fun selectPlaylistFilter(filter: PlaylistFilter) {
        _playlistFilter.value = filter
    }

    fun setSortOrder(view: LibraryView, order: SortOrder) {
        _sortOrders.update { it + (view to order) }
    }

    fun setLibrarySearchQuery(query: String) {
        _librarySearchQuery.value = query
    }

    fun setPlaylistSearchQuery(query: String) {
        _playlistSearchQuery.value = query
    }

    fun toggleFolderDisplayMode() {
        val next = if (_folderDisplayMode.value == FolderDisplayMode.TREE) {
            FolderDisplayMode.FLAT
        } else {
            FolderDisplayMode.TREE
        }
        _folderDisplayMode.value = next
        val rootId = _activeFolderRootId.value
        if (rootId != null) {
            openFolder(rootId, _activeFolderRelPath.value)
        }
    }

    fun setNowPlayingVisible(visible: Boolean) {
        _showNowPlaying.value = visible
    }

    fun setSettingsVisible(visible: Boolean) {
        _showSettings.value = visible
        if (visible) {
            refreshCacheSize()
        }
    }

    fun setLanStudioVisible(visible: Boolean) {
        _showLanStudio.value = visible
    }

    // --- SAF Folder Management & Drill-Down ---

    fun handlePickedSafTreeUri(uri: Uri) {
        val app = getApplication<Application>()
        val cr = app.contentResolver
        try {
            cr.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            try {
                cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {
            }
        }
        viewModelScope.launch {
            when (val result = core.scanner.addSafRoot(uri)) {
                is AddRootResult.Added -> {
                    emitMessage("已添加文件夹「${result.root.displayName}」")
                }
                is AddRootResult.AlreadyCoveredByParent -> {
                    emitMessage("该目录已包含在「${result.parentRoot.displayName}」中，已为你直接定位")
                    openFolder(result.parentRoot.rootId, result.childRelPath)
                }
                is AddRootResult.MergedChildRoots -> {
                    emitMessage("已添加父目录「${result.root.displayName}」并无损归并 ${result.mergedCount} 个旧子目录")
                }
                is AddRootResult.Error -> {
                    emitMessage(result.message)
                }
            }
        }
    }

    fun onAppForegrounded() {
        val now = System.currentTimeMillis()
        if (scanProgress.value.isScanning || now - lastForegroundScanAtMs < 1_500L) return
        if (safRoots.value.isEmpty()) return
        lastForegroundScanAtMs = now
        viewModelScope.launch {
            core.scanner.rescanAllRoots(incremental = true)
            refreshActiveDrillDowns()
        }
    }

    fun rescanAll() {
        lastForegroundScanAtMs = System.currentTimeMillis()
        viewModelScope.launch {
            core.scanner.rescanAllRoots(incremental = true)
            refreshActiveDrillDowns()
            emitMessage("已完成增量刷新")
        }
    }

    fun rescanRoot(rootId: Long) {
        lastForegroundScanAtMs = System.currentTimeMillis()
        viewModelScope.launch {
            core.scanner.rescanRoot(rootId, incremental = true)
            refreshActiveDrillDowns()
            emitMessage("目录增量扫描完成")
        }
    }

    fun removeSafRoot(rootId: Long) {
        viewModelScope.launch {
            if (_activeFolderRootId.value == rootId) {
                closeFolderBrowse()
            }
            core.scanner.removeSafRoot(rootId)
            emitMessage("已从媒体库移除该文件夹（未删除本地文件）")
        }
    }

    fun openFolder(rootId: Long, relPath: String = "") {
        _activeFolderRootId.value = rootId
        _activeFolderRelPath.value = relPath
        viewModelScope.launch {
            _folderBrowseState.value = core.library.browseFolder(
                rootId = rootId,
                relPath = relPath,
                flat = _folderDisplayMode.value == FolderDisplayMode.FLAT,
            )
        }
    }

    fun navigateFolderUp(): Boolean {
        val currentState = _folderBrowseState.value ?: return false
        val rel = currentState.currentRelPath
        return if (rel.isEmpty()) {
            closeFolderBrowse()
            true
        } else {
            val parentRel = rel.substringBeforeLast('/', missingDelimiterValue = "")
            openFolder(currentState.root.rootId, if (rel.contains('/')) parentRel else "")
            true
        }
    }

    fun closeFolderBrowse() {
        _activeFolderRootId.value = null
        _activeFolderRelPath.value = ""
        _folderBrowseState.value = null
    }

    fun setFolderPracticeFlag(
        rootId: Long,
        relPath: String,
        folderUri: String,
        isPractice: Boolean,
    ) {
        viewModelScope.launch {
            core.scanner.setFolderPracticeRule(rootId, relPath, folderUri, isPractice)
            refreshActiveDrillDowns()
            emitMessage(if (isPractice) "已设为练习文件夹（点击曲目自动开启练习模式）" else "已恢复为普通文件夹")
        }
    }

    fun setFolderExcludeFromGroupingFlag(
        rootId: Long,
        relPath: String,
        folderUri: String,
        isExcluded: Boolean,
    ) {
        viewModelScope.launch {
            core.scanner.setFolderExcludeFromGroupingRule(rootId, relPath, folderUri, isExcluded)
            refreshActiveDrillDowns()
            emitMessage(if (isExcluded) "已设为「不自动归类」（该目录下的文件不显示在专辑、歌手、视频中）" else "已取消「不自动归类」")
        }
    }

    fun openAddFolderToPlaylistPicker(rootId: Long, relPath: String = "") {
        val normRel = MediaRepository.normalizeRelPath(relPath)
        val folderItems = allMediaItems.value
            .filter { item ->
                item.rootId == rootId &&
                    (normRel.isEmpty() || item.parentRelPath == normRel || item.parentRelPath.startsWith("$normRel/"))
            }
            .sortedWith { a, b -> SmartFilenameParser.naturalCompare(a.title, b.title) }
        if (folderItems.isEmpty()) {
            emitMessage("该文件夹暂无可添加的媒体文件")
        } else {
            openAddToPlaylistPicker(folderItems)
        }
    }

    // --- Album & Artist Drill-Downs ---

    fun openAlbum(album: AlbumSummary) {
        _activeAlbum.value = album
        viewModelScope.launch {
            _activeAlbumTracks.value = core.library.getAlbumTracks(album.albumKey)
        }
    }

    fun closeAlbum() {
        _activeAlbum.value = null
        _activeAlbumTracks.value = emptyList()
    }

    fun openArtist(artist: ArtistSummary) {
        viewModelScope.launch {
            _activeArtistDetail.value = core.library.getArtistDetail(artist.artistNorm)
        }
    }

    fun closeArtist() {
        _activeArtistDetail.value = null
    }

    // --- Playlists & Practice Playlists ---

    fun openPlaylist(playlistId: Long) {
        viewModelScope.launch {
            _activePlaylistDetail.value = core.library.getPlaylistDetail(playlistId)
        }
    }

    fun closePlaylist() {
        _activePlaylistDetail.value = null
    }

    fun createPlaylist(name: String, isPracticePlaylist: Boolean, initialItems: List<Long> = emptyList()) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = core.library.createPlaylist(trimmed, isPracticePlaylist)
            if (initialItems.isNotEmpty()) {
                core.library.addItemsToPlaylist(id, initialItems)
            }
            emitMessage(
                if (isPracticePlaylist) "已创建练习列表「$trimmed」" else "已创建播放列表「$trimmed」",
            )
        }
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            core.library.renamePlaylist(playlistId, trimmed)
            refreshActiveDrillDowns()
        }
    }

    fun setPlaylistPracticeMode(playlistId: Long, isPractice: Boolean) {
        viewModelScope.launch {
            core.library.setPlaylistPracticeMode(playlistId, isPractice)
            refreshActiveDrillDowns()
            emitMessage(if (isPractice) "已开启练习列表模式" else "已切换为普通播放列表")
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            if (_activePlaylistDetail.value?.summary?.playlistId == playlistId) {
                closePlaylist()
            }
            core.library.deletePlaylist(playlistId)
            emitMessage("已删除播放列表")
        }
    }

    fun openAddToPlaylistPicker(items: List<MediaItem>) {
        _playlistPickerTargetItems.value = items
    }

    fun closeAddToPlaylistPicker() {
        _playlistPickerTargetItems.value = emptyList()
    }

    fun addItemsToPlaylist(playlistId: Long, itemIds: List<Long>) {
        if (itemIds.isEmpty()) return
        viewModelScope.launch {
            core.library.addItemsToPlaylist(playlistId, itemIds)
            refreshActiveDrillDowns()
            _playlistPickerTargetItems.value = emptyList()
            emitMessage("已加入 ${itemIds.size} 首曲目")
        }
    }

    fun removeItemFromPlaylist(playlistId: Long, itemId: Long) {
        viewModelScope.launch {
            core.library.removeItemFromPlaylist(playlistId, itemId)
            refreshActiveDrillDowns()
        }
    }

    fun reorderPlaylistItems(playlistId: Long, orderedItemIds: List<Long>) {
        // Optimistic UI update for smooth drag-and-drop
        _activePlaylistDetail.value?.let { detail ->
            if (detail.summary.playlistId == playlistId) {
                val byId = detail.items.associateBy { it.itemId }
                val reordered = orderedItemIds.mapNotNull { byId[it] }
                _activePlaylistDetail.value = detail.copy(items = reordered)
            }
        }
        viewModelScope.launch {
            core.library.reorderPlaylistItems(playlistId, orderedItemIds)
            refreshActiveDrillDowns()
        }
    }

    fun importM3uPlaylist(uri: Uri) {
        viewModelScope.launch {
            val result = core.metadata.importPlaylistM3u8(uri)
            emitMessage(
                "已导入「${result.playlistName}」：成功匹配 ${result.matchedCount} 首" +
                    if (result.unmatchedCount > 0) "（${result.unmatchedCount} 首未在已添加文件夹中找到）" else "",
            )
        }
    }

    fun exportM3uPlaylist(playlistId: Long, outputUri: Uri) {
        viewModelScope.launch {
            val ok = core.metadata.exportPlaylistM3u8(playlistId, outputUri)
            emitMessage(if (ok) "已导出 .m3u8 播放列表" else "导出播放列表失败")
        }
    }

    // --- Deterministic Playback & Practice Mode State Machine (§5.1) ---

    fun playFromContext(
        items: List<MediaItem>,
        startIndex: Int,
        context: PlayContext,
        targetBookmark: PracticeBookmark? = null,
        openFullPlayer: Boolean = context != PlayContext.CASUAL || items.getOrNull(startIndex)?.isVideo == true,
    ) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            playerController.playQueue(
                items = items,
                startIndex = startIndex,
                context = context,
                targetBookmark = targetBookmark,
            )
            if (openFullPlayer) {
                _showNowPlaying.value = true
            }
        }
    }

    fun savePracticeBookmark(
        label: String,
        aMs: Long,
        bMs: Long,
        includeCurrentSpeed: Boolean = true,
        existingBookmarkId: Long? = null,
    ) {
        val current = playbackSnapshot.value.currentItem ?: return
        val normalized = PracticeMath.normalizeLoopPoints(aMs, bMs, playbackSnapshot.value.durationMs) ?: return
        val speedToSave = if (includeCurrentSpeed) practiceState.value.practiceSpeed else null
        viewModelScope.launch {
            val saved = core.library.saveBookmark(
                itemId = current.itemId,
                label = label.trim().ifEmpty { "段落" },
                aMs = normalized.first,
                bMs = normalized.second,
                savedSpeed = speedToSave,
                bookmarkId = existingBookmarkId,
            )
            playerController.loopEngine.activateBookmark(saved)
            emitMessage("已保存段落书签「${saved.label}」")
        }
    }

    fun deletePracticeBookmark(bookmarkId: Long) {
        viewModelScope.launch {
            core.library.deleteBookmark(bookmarkId)
            emitMessage("已删除段落书签")
        }
    }

    fun autoDetectBpm() {
        playerController.autoDetectBpm()
    }

    fun fetchLyricsOnlineIfNeeded() {
        val item = playbackSnapshot.value.currentItem ?: return
        if (_isLoadingLyrics.value) return
        _isLoadingLyrics.value = true
        viewModelScope.launch {
            try {
                val lyrics = core.metadata.getLyrics(item, fetchOnlineIfMissing = true)
                _currentLyrics.value = lyrics
                if (lyrics == null) {
                    emitMessage("未找到匹配的同步歌词")
                }
            } finally {
                _isLoadingLyrics.value = false
            }
        }
    }

    // --- Metadata Editor & Zero-Key Online Completion ---

    fun openMetadataEditor(targets: List<MediaItem>) {
        if (targets.isEmpty()) return
        _metadataEditTargets.value = targets
        _onlineCandidates.value = emptyList()
        _isSearchingCandidates.value = false
    }

    fun closeMetadataEditor() {
        _metadataEditTargets.value = emptyList()
        _onlineCandidates.value = emptyList()
        _isSearchingCandidates.value = false
    }

    fun searchSingleTrackOnlineCandidates(
        item: MediaItem,
        titleQueryOverride: String? = null,
        artistQueryOverride: String? = null,
    ) {
        _isSearchingCandidates.value = true
        viewModelScope.launch {
            try {
                val results = core.metadata.searchOnlineCandidates(
                    item = item,
                    queryTitleOverride = titleQueryOverride?.takeIf { it.isNotBlank() },
                    queryArtistOverride = artistQueryOverride?.takeIf { it.isNotBlank() },
                )
                _onlineCandidates.value = results
                if (results.isEmpty()) {
                    emitMessage("未找到匹配的在线标签候选，可尝试修改关键词重试")
                }
            } catch (e: Throwable) {
                emitMessage("在线匹配请求失败：${e.message ?: "请检查网络连接"}")
            } finally {
                _isSearchingCandidates.value = false
            }
        }
    }

    fun saveMetadataDraft(draft: MetadataDraft) {
        val targets = _metadataEditTargets.value
        if (targets.isEmpty()) return
        val ids = targets.map { it.itemId }
        val currentPlayingId = playbackSnapshot.value.currentItem?.itemId
        viewModelScope.launch {
            val outcome = core.metadata.saveMetadata(ids, draft, currentPlayingId)
            val latestMap = allMediaItems.value.associateBy { it.itemId }
            if (latestMap.isNotEmpty()) {
                playerController.refreshMetadataInQueue(latestMap)
            }
            if (currentPlayingId != null && currentPlayingId in ids) {
                val updatedCurrent = latestMap[currentPlayingId] ?: playbackSnapshot.value.currentItem
                _currentCoverBytes.value = loadLocalCoverBytes(updatedCurrent?.coverPathOrUri) ?: draft.coverBytes
                _currentLyrics.value = updatedCurrent?.takeIf { !it.isVideo }?.let {
                    core.metadata.getLyrics(it, fetchOnlineIfMissing = false)
                }
            }
            closeMetadataEditor()
            refreshActiveDrillDowns()
            val msg = when (outcome) {
                MetadataSaveOutcome.SAVED_DB_ONLY -> "已更新本地标签覆盖层"
                MetadataSaveOutcome.SAVED_DB_AND_FILE -> "已更新标签并安全写入原音频文件"
                MetadataSaveOutcome.SAVED_DB_DEFERRED_FILE -> "已立即生效，原文件标签将在切歌后静默写回"
                MetadataSaveOutcome.SAVED_DB_READONLY_FALLBACK -> "目录为只读，已保存在本地覆盖层"
            }
            emitMessage(msg)
        }
    }

    fun applySingleCandidateDirectly(
        item: MediaItem,
        candidate: MetadataCandidate,
        preserveBackingSuffix: Boolean,
        writeBackToFile: Boolean,
    ) {
        val currentPlayingId = playbackSnapshot.value.currentItem?.itemId
        viewModelScope.launch {
            core.metadata.applyCandidate(
                itemId = item.itemId,
                candidate = candidate,
                preserveBackingSuffix = preserveBackingSuffix,
                writeBackToFile = writeBackToFile,
                currentlyPlayingItemId = currentPlayingId,
            )
            if (playbackSnapshot.value.currentItem?.itemId == item.itemId) {
                _currentLyrics.value = core.metadata.getLyrics(item, fetchOnlineIfMissing = false)
            }
            closeMetadataEditor()
            refreshActiveDrillDowns()
            emitMessage("已应用「${candidate.title} - ${candidate.artist}」标签与封面")
        }
    }

    fun resetMetadataOverrides(items: List<MediaItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            core.metadata.resetMetadataOverride(items.map { it.itemId })
            closeMetadataEditor()
            refreshActiveDrillDowns()
            emitMessage("已恢复原始文件标签")
        }
    }

    fun startBatchAutoCompletion(items: List<MediaItem>) {
        val audioTargets = items.filter { !it.isVideo }
        if (audioTargets.isEmpty()) {
            emitMessage("没有可联网补全的音频曲目")
            return
        }
        _batchProgress.value = 0 to audioTargets.size
        _batchMatchPreview.value = emptyList()
        viewModelScope.launch {
            try {
                val results = core.metadata.previewBatchCompletion(audioTargets) { done, total ->
                    _batchProgress.value = done to total
                }
                _batchMatchPreview.value = results
            } catch (e: Throwable) {
                _batchMatchPreview.value = null
                emitMessage("批量联网识别失败：${e.message ?: "网络异常"}")
            } finally {
                _batchProgress.value = null
            }
        }
    }

    fun updateBatchMatchSelection(index: Int, candidateIndex: Int) {
        _batchMatchPreview.update { list ->
            list?.mapIndexed { idx, item ->
                if (idx == index) item.copy(selectedCandidateIndex = candidateIndex) else item
            }
        }
    }

    fun toggleBatchPreserveBacking(index: Int) {
        _batchMatchPreview.update { list ->
            list?.mapIndexed { idx, item ->
                if (idx == index) item.copy(preserveBackingSuffix = !item.preserveBackingSuffix) else item
            }
        }
    }

    fun commitBatchAutoCompletion(writeBackToFile: Boolean) {
        val matches = _batchMatchPreview.value ?: return
        if (_isCommittingBatch.value) return
        _isCommittingBatch.value = true
        val currentPlayingId = playbackSnapshot.value.currentItem?.itemId
        viewModelScope.launch {
            try {
                val updatedCount = core.metadata.commitBatchCompletion(
                    matches = matches,
                    writeBackToFile = writeBackToFile,
                    currentlyPlayingItemId = currentPlayingId,
                )
                _batchMatchPreview.value = null
                refreshActiveDrillDowns()
                emitMessage("批量补全完成，已更新 $updatedCount 首曲目")
            } finally {
                _isCommittingBatch.value = false
            }
        }
    }

    fun dismissBatchAutoCompletion() {
        _batchMatchPreview.value = null
        _batchProgress.value = null
    }

    // --- Settings & GitHub Auto-Updater ---

    fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            core.library.updateSettings(transform)
        }
    }

    fun setKeepScreenOnInPractice(enabled: Boolean) {
        updateAppSettings { it.copy(keepScreenOnInPractice = enabled) }
    }

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val waveformsBytes = File(app.cacheDir, "waveforms").walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val thumbsBytes = File(app.cacheDir, "thumbs").walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val coversBytes = File(app.filesDir, "covers").walkTopDown().filter { it.isFile }.sumOf { it.length() }
            _cacheSizeBytes.value = waveformsBytes + thumbsBytes + coversBytes
        }
    }

    fun clearLocalCaches() {
        viewModelScope.launch {
            val thumbsFreed = withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val thumbsDir = File(app.cacheDir, "thumbs")
                val bytes = thumbsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                thumbsDir.deleteRecursively()
                bytes
            }
            val cleared = core.library.clearCaches() + thumbsFreed
            refreshCacheSize()
            emitMessage("已清理本地封面与波形缓存" + if (cleared > 0) "（释放 ${formatByteSize(cleared)}）" else "")
        }
    }

    fun checkForAppUpdates() {
        if (_updateState.value is UiUpdateState.Checking || _updateState.value is UiUpdateState.Downloading) return
        _updateState.value = UiUpdateState.Checking
        viewModelScope.launch {
            try {
                val info = core.updater.checkForUpdates(includePreRelease = false)
                _updateState.value = UiUpdateState.Checked(info)
            } catch (e: Throwable) {
                _updateState.value = UiUpdateState.Error(e.message ?: "检查更新失败，请检查网络连接")
            }
        }
    }

    fun downloadAndVerifyUpdate(info: UpdateInfo) {
        _updateState.value = UiUpdateState.Downloading(info, 0L, info.apkSizeBytes)
        viewModelScope.launch {
            try {
                val apkFile = core.updater.downloadAndVerifyUpdate(info) { downloaded, total ->
                    _updateState.value = UiUpdateState.Downloading(info, downloaded, total)
                }
                _updateState.value = UiUpdateState.ReadyToInstall(info, apkFile)
            } catch (e: Throwable) {
                _updateState.value = UiUpdateState.Error(e.message ?: "下载或安全校验 APK 失败")
            }
        }
    }

    fun buildInstallOrPermissionIntent(apkFile: File): Intent {
        return if (core.updater.canRequestPackageInstalls()) {
            core.updater.buildInstallIntent(apkFile)
        } else {
            val app = getApplication<Application>()
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${app.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private suspend fun loadLocalCoverBytes(coverPathOrUri: String?): ByteArray? {
        if (coverPathOrUri.isNullOrBlank()) return null
        if (coverPathOrUri.startsWith("http://", ignoreCase = true) ||
            coverPathOrUri.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                when {
                    coverPathOrUri.startsWith("content://") || coverPathOrUri.startsWith("file://") -> {
                        getApplication<Application>().contentResolver.openInputStream(Uri.parse(coverPathOrUri))
                            ?.use { it.readBytes() }
                    }
                    else -> {
                        val file = File(coverPathOrUri)
                        if (file.isFile) file.readBytes() else null
                    }
                }
            }.getOrNull()
        }
    }

    private suspend fun refreshActiveDrillDowns() {
        _activeFolderRootId.value?.let { rootId ->
            val refreshed = core.library.browseFolder(
                rootId = rootId,
                relPath = _activeFolderRelPath.value,
                flat = _folderDisplayMode.value == FolderDisplayMode.FLAT,
            )
            _folderBrowseState.value = refreshed
            if (refreshed != null && _activeFolderRelPath.value != refreshed.currentRelPath) {
                _activeFolderRelPath.value = refreshed.currentRelPath
            }
        }
        _activeAlbum.value?.let { alb ->
            _activeAlbumTracks.value = core.library.getAlbumTracks(alb.albumKey)
        }
        _activeArtistDetail.value?.let { art ->
            _activeArtistDetail.value = core.library.getArtistDetail(art.artist.artistNorm)
        }
        _activePlaylistDetail.value?.let { pl ->
            _activePlaylistDetail.value = core.library.getPlaylistDetail(pl.summary.playlistId)
        }
    }

    override fun onCleared() {
        playerController.releaseUiResources()
        super.onCleared()
    }

    companion object {
        fun formatByteSize(bytes: Long): String {
            if (bytes < 1024L) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024.0) return String.format("%.1f KB", kb)
            val mb = kb / 1024.0
            return String.format("%.1f MB", mb)
        }
    }
}
