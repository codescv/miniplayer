package com.miniplayer.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.miniplayer.app.core.model.*
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

interface MediaLibraryContract {
    val safRoots: StateFlow<List<SafRoot>>
    val allMediaItems: StateFlow<List<MediaItem>>
    val videoItems: StateFlow<List<MediaItem>>
    val albums: StateFlow<List<AlbumSummary>>
    val artists: StateFlow<List<ArtistSummary>>
    val playlists: StateFlow<List<PlaylistSummary>>
    val bookmarkedTracks: StateFlow<List<BookmarkedTrackItem>>
    val settings: StateFlow<AppSettings>

    suspend fun browseFolder(rootId: Long, relPath: String = "", flat: Boolean = false): FolderBrowseState?
    suspend fun getAlbumTracks(albumKey: String): List<MediaItem>
    suspend fun getArtistDetail(artistNorm: String): ArtistDetail?
    suspend fun getPlaylistDetail(playlistId: Long): PlaylistDetail?
    suspend fun isPracticeFolder(rootId: Long, relPath: String): Boolean

    suspend fun createPlaylist(name: String, isPracticePlaylist: Boolean): Long
    suspend fun renamePlaylist(playlistId: Long, newName: String)
    suspend fun setPlaylistPracticeMode(playlistId: Long, isPracticePlaylist: Boolean)
    suspend fun deletePlaylist(playlistId: Long)
    suspend fun addItemsToPlaylist(playlistId: Long, itemIds: List<Long>)
    suspend fun removeItemFromPlaylist(playlistId: Long, itemId: Long)
    suspend fun reorderPlaylistItems(playlistId: Long, orderedItemIds: List<Long>)

    suspend fun getPracticeState(itemId: Long): PracticeState
    fun observeBookmarks(itemId: Long): Flow<List<PracticeBookmark>>
    suspend fun getBookmarks(itemId: Long): List<PracticeBookmark>
    suspend fun savePracticeState(state: PracticeState)
    suspend fun saveBookmark(
        itemId: Long,
        label: String,
        aMs: Long,
        bMs: Long,
        savedSpeed: Float? = null,
        bookmarkId: Long? = null,
    ): PracticeBookmark
    suspend fun deleteBookmark(bookmarkId: Long)
    suspend fun savePlaybackPosition(itemId: Long, positionMs: Long)

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings)
    suspend fun clearCaches(): Long
}

interface FolderScannerContract {
    val scanProgress: StateFlow<ScanProgress>

    suspend fun addSafRoot(treeUri: Uri): AddRootResult
    suspend fun rescanAll(incremental: Boolean = true)
    suspend fun rescanRoot(rootId: Long, incremental: Boolean = true)
    suspend fun removeSafRoot(rootId: Long)
    suspend fun setFolderPracticeRule(
        rootId: Long,
        relPath: String,
        folderUri: String,
        isPracticeFolder: Boolean,
    )
    suspend fun setFolderNomediaRule(
        rootId: Long,
        relPath: String,
        folderUri: String,
        respectNomedia: Boolean?,
    )
}

interface MetadataEngineContract {
    suspend fun saveMetadata(
        itemIds: List<Long>,
        draft: MetadataDraft,
        currentlyPlayingItemId: Long? = null,
    ): MetadataSaveOutcome

    suspend fun resetMetadataOverride(itemIds: List<Long>)

    suspend fun searchOnlineCandidates(
        item: MediaItem,
        queryTitleOverride: String? = null,
        queryArtistOverride: String? = null,
    ): List<MetadataCandidate>

    suspend fun applyCandidate(
        itemId: Long,
        candidate: MetadataCandidate,
        preserveBackingSuffix: Boolean = false,
        writeBackToFile: Boolean = false,
        currentlyPlayingItemId: Long? = null,
    ): MetadataSaveOutcome

    suspend fun previewBatchCompletion(
        items: List<MediaItem>,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): List<BatchMatchItem>

    suspend fun commitBatchCompletion(
        matches: List<BatchMatchItem>,
        writeBackToFile: Boolean = false,
        currentlyPlayingItemId: Long? = null,
    ): Int

    suspend fun getLyrics(item: MediaItem, fetchOnlineIfMissing: Boolean = true): LyricsData?

    suspend fun exportPlaylistM3u8(playlistId: Long, outputUri: Uri): Boolean
    suspend fun importPlaylistM3u8(inputUri: Uri, fallbackName: String = "导入列表"): M3uImportResult
}

interface AppUpdateContract {
    suspend fun checkForUpdates(includePreRelease: Boolean = false): UpdateInfo
    suspend fun downloadAndVerifyUpdate(
        updateInfo: UpdateInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File
    fun buildInstallIntent(apkFile: File): Intent
    fun canRequestPackageInstalls(): Boolean
}

interface MiniPlayerCoreProvider {
    val library: MediaLibraryContract
    val scanner: FolderScannerContract
    val metadata: MetadataEngineContract
    val updater: AppUpdateContract
}

/**
 * Decoupled service registry allowing `feat/core` (`DefaultMiniPlayerCore`) and `feat/ui-player`
 * to compile independently on isolated worktrees and bind automatically upon integration.
 */
object CoreRegistry {
    @Volatile
    private var instance: MiniPlayerCoreProvider? = null

    fun get(context: Context): MiniPlayerCoreProvider {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val appCtx = context.applicationContext
            val created = try {
                val clazz = Class.forName("com.miniplayer.app.core.DefaultMiniPlayerCore")
                val ctor = clazz.getConstructor(Context::class.java)
                ctor.newInstance(appCtx) as MiniPlayerCoreProvider
            } catch (_: Throwable) {
                FallbackStubCore(appCtx)
            }
            instance = created
            return created
        }
    }

    fun overrideForTesting(provider: MiniPlayerCoreProvider?) {
        synchronized(this) {
            instance = provider
        }
    }
}

private class FallbackStubCore(private val context: Context) : MiniPlayerCoreProvider {
    private val settingsState = MutableStateFlow(AppSettings())

    override val library: MediaLibraryContract = object : MediaLibraryContract {
        override val safRoots = MutableStateFlow<List<SafRoot>>(emptyList())
        override val allMediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
        override val videoItems = MutableStateFlow<List<MediaItem>>(emptyList())
        override val albums = MutableStateFlow<List<AlbumSummary>>(emptyList())
        override val artists = MutableStateFlow<List<ArtistSummary>>(emptyList())
        override val playlists = MutableStateFlow<List<PlaylistSummary>>(emptyList())
        override val bookmarkedTracks = MutableStateFlow<List<BookmarkedTrackItem>>(emptyList())
        override val settings = settingsState

        override suspend fun browseFolder(rootId: Long, relPath: String, flat: Boolean): FolderBrowseState? = null
        override suspend fun getAlbumTracks(albumKey: String): List<MediaItem> = emptyList()
        override suspend fun getArtistDetail(artistNorm: String): ArtistDetail? = null
        override suspend fun getPlaylistDetail(playlistId: Long): PlaylistDetail? = null
        override suspend fun isPracticeFolder(rootId: Long, relPath: String): Boolean = false
        override suspend fun createPlaylist(name: String, isPracticePlaylist: Boolean): Long = 1L
        override suspend fun renamePlaylist(playlistId: Long, newName: String) {}
        override suspend fun setPlaylistPracticeMode(playlistId: Long, isPracticePlaylist: Boolean) {}
        override suspend fun deletePlaylist(playlistId: Long) {}
        override suspend fun addItemsToPlaylist(playlistId: Long, itemIds: List<Long>) {}
        override suspend fun removeItemFromPlaylist(playlistId: Long, itemId: Long) {}
        override suspend fun reorderPlaylistItems(playlistId: Long, orderedItemIds: List<Long>) {}
        override suspend fun getPracticeState(itemId: Long): PracticeState = PracticeState(itemId = itemId)
        override fun observeBookmarks(itemId: Long): Flow<List<PracticeBookmark>> = flowOf(emptyList())
        override suspend fun getBookmarks(itemId: Long): List<PracticeBookmark> = emptyList()
        override suspend fun savePracticeState(state: PracticeState) {}
        override suspend fun saveBookmark(
            itemId: Long,
            label: String,
            aMs: Long,
            bMs: Long,
            savedSpeed: Float?,
            bookmarkId: Long?,
        ): PracticeBookmark = PracticeBookmark(bookmarkId ?: 1L, itemId, label, aMs, bMs, savedSpeed)
        override suspend fun deleteBookmark(bookmarkId: Long) {}
        override suspend fun savePlaybackPosition(itemId: Long, positionMs: Long) {}
        override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
            settingsState.value = transform(settingsState.value)
        }
        override suspend fun clearCaches(): Long = 0L
    }

    override val scanner: FolderScannerContract = object : FolderScannerContract {
        override val scanProgress = MutableStateFlow(ScanProgress())
        override suspend fun addSafRoot(treeUri: Uri): AddRootResult =
            AddRootResult.Error("Core engine not initialized")
        override suspend fun rescanAll(incremental: Boolean) {}
        override suspend fun rescanRoot(rootId: Long, incremental: Boolean) {}
        override suspend fun removeSafRoot(rootId: Long) {}
        override suspend fun setFolderPracticeRule(
            rootId: Long,
            relPath: String,
            folderUri: String,
            isPracticeFolder: Boolean,
        ) {}
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
        ): MetadataSaveOutcome = MetadataSaveOutcome.SAVED_DB_ONLY
        override suspend fun resetMetadataOverride(itemIds: List<Long>) {}
        override suspend fun searchOnlineCandidates(
            item: MediaItem,
            queryTitleOverride: String?,
            queryArtistOverride: String?,
        ): List<MetadataCandidate> = emptyList()
        override suspend fun applyCandidate(
            itemId: Long,
            candidate: MetadataCandidate,
            preserveBackingSuffix: Boolean,
            writeBackToFile: Boolean,
            currentlyPlayingItemId: Long?,
        ): MetadataSaveOutcome = MetadataSaveOutcome.SAVED_DB_ONLY
        override suspend fun previewBatchCompletion(
            items: List<MediaItem>,
            onProgress: (completed: Int, total: Int) -> Unit,
        ): List<BatchMatchItem> = emptyList()
        override suspend fun commitBatchCompletion(
            matches: List<BatchMatchItem>,
            writeBackToFile: Boolean,
            currentlyPlayingItemId: Long?,
        ): Int = 0
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
        ): File = File(context.cacheDir, "noop.apk")
        override fun buildInstallIntent(apkFile: File): Intent = Intent(Intent.ACTION_VIEW)
        override fun canRequestPackageInstalls(): Boolean = true
    }
}
