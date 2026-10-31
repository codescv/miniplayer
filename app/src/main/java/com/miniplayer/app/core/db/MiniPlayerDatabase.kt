package com.miniplayer.app.core.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.miniplayer.app.core.metadata.SplitArtist
import com.miniplayer.app.core.model.AppSettings
import com.miniplayer.app.core.model.CountInMode
import com.miniplayer.app.core.model.LyricsSource
import com.miniplayer.app.core.model.MountState
import com.miniplayer.app.core.model.PracticeBookmark
import com.miniplayer.app.core.model.PracticeState
import com.miniplayer.app.core.model.VideoSeekMode
import java.util.concurrent.atomic.AtomicLong

data class SafRootRow(
    val rootId: Long,
    val treeUri: String,
    val treeDocId: String,
    val displayName: String,
    val isWritable: Boolean,
    val mountState: MountState,
    val lastScanAt: Long,
)

data class FolderRuleRow(
    val folderUri: String,
    val rootId: Long,
    val relPath: String,
    val isPracticeFolder: Boolean,
    val respectNomedia: Boolean?,
    val isExcludedFromAutoGrouping: Boolean = false,
)

data class ScannedFolderRow(
    val rootId: Long,
    val relPath: String,
    val folderUri: String,
    val hasNomedia: Boolean,
)

data class MediaItemRow(
    val itemId: Long = 0L,
    val documentUri: String,
    val rootId: Long,
    val parentRelPath: String,
    val fileName: String,
    val mimeType: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val durationMs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val rawTitle: String? = null,
    val rawArtist: String? = null,
    val rawAlbum: String? = null,
    val rawAlbumArtist: String? = null,
    val rawTrackNum: Int? = null,
    val rawYear: Int? = null,
    val parsedTitle: String,
    val parsedArtist: String? = null,
    val parsedAlbum: String? = null,
    val isBackingTrack: Boolean = false,
    val embeddedCoverPath: String? = null,
    val folderCoverUri: String? = null,
    val fingerprintKey: String,
    val lastPositionMs: Long = 0L,
    val lastPlayedAt: Long = 0L,
)

data class MetadataOverrideRow(
    val itemId: Long,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNum: Int? = null,
    val year: Int? = null,
    val coverLocalPath: String? = null,
    val updatedAt: Long,
    val syncedToFile: Boolean = true,
) {
    companion object {
        /** Stored in TEXT columns when a field is explicitly cleared (`null` = not overridden). */
        const val CLEARED_TEXT = ""

        /** Stored in INTEGER columns (`track_num`, `year`) when explicitly cleared (`null` = not overridden). */
        const val CLEARED_INT = -1
    }
}

data class MediaItemArtistRow(
    val itemId: Long,
    val artistNorm: String,
    val artistDisplay: String,
    val ord: Int,
)

data class PlaylistRow(
    val playlistId: Long,
    val name: String,
    val isPracticePlaylist: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class PlaylistItemRow(
    val playlistId: Long,
    val itemId: Long,
    val sortOrder: Int,
    val addedAt: Long,
)

data class LyricsCacheRow(
    val itemId: Long,
    val syncedLrc: String?,
    val plainLyrics: String?,
    val source: LyricsSource,
    val sourceModifiedAt: Long,
)

data class ChildRootMergeSpec(
    val oldRootId: Long,
    val relPathPrefix: String,
    val rebindDocumentUri: (oldDocUri: String) -> String,
)

data class MovedMediaUpdate(
    val existingItemId: Long,
    val newDocumentUri: String,
    val newRootId: Long,
    val newParentRelPath: String,
    val newFileName: String,
    val newSizeBytes: Long,
    val newLastModified: Long,
    val newFolderCoverUri: String?,
    val newFingerprintKey: String,
    val parsedTitle: String,
    val parsedArtist: String?,
    val parsedAlbum: String?,
    val isBackingTrack: Boolean,
)

data class DbSnapshot(
    val safRoots: List<SafRootRow>,
    val folderRules: List<FolderRuleRow>,
    val scannedFolders: List<ScannedFolderRow>,
    val mediaItems: List<MediaItemRow>,
    val overridesByItemId: Map<Long, MetadataOverrideRow>,
    val artistsByItemId: Map<Long, List<MediaItemArtistRow>>,
    val playlists: List<PlaylistRow>,
    val playlistItems: List<PlaylistItemRow>,
    val practiceStatesByItemId: Map<Long, PracticeState>,
    val bookmarksByItemId: Map<Long, List<PracticeBookmark>>,
    val settings: AppSettings,
)

interface MediaStoreBackend {
    fun readSnapshot(): DbSnapshot
    fun getMediaItemRow(itemId: Long): MediaItemRow?
    fun getOverrideRow(itemId: Long): MetadataOverrideRow?
    fun getSafRootRow(rootId: Long): SafRootRow?

    fun insertSafRoot(
        treeUri: String,
        treeDocId: String,
        displayName: String,
        isWritable: Boolean,
        mountState: MountState,
    ): Long

    fun mergeChildRootsIntoParent(
        treeUri: String,
        treeDocId: String,
        displayName: String,
        isWritable: Boolean,
        merges: List<ChildRootMergeSpec>,
    ): Long

    fun updateSafRootStatus(
        rootId: Long,
        mountState: MountState,
        isWritable: Boolean? = null,
        lastScanAt: Long? = null,
    )

    fun deleteSafRoot(rootId: Long)

    fun upsertFolderRule(rule: FolderRuleRow)

    fun reconcileRootScan(
        rootId: Long,
        isWritable: Boolean,
        scannedAtMs: Long,
        scannedFolders: List<ScannedFolderRow>,
        unchangedCoverUpdates: Map<Long, String?>,
        movedItems: List<MovedMediaUpdate>,
        upsertedItems: List<MediaItemRow>,
        deletedItemIds: Set<Long>,
        lyricsUpdates: List<Pair<String, LyricsCacheRow>>, // keyed by documentUri
        artistSplitter: (effectiveArtist: String, effectiveAlbumArtist: String?) -> List<SplitArtist>,
    )

    fun upsertMetadataOverrides(
        entries: List<MetadataOverrideRow>,
        artistSplitter: (effectiveArtist: String, effectiveAlbumArtist: String?) -> List<SplitArtist>,
    )

    fun deleteMetadataOverrides(
        itemIds: List<Long>,
        artistSplitter: (effectiveArtist: String, effectiveAlbumArtist: String?) -> List<SplitArtist>,
    )

    fun markOverrideSyncedAndUpdateStat(
        itemId: Long,
        sizeBytes: Long,
        lastModified: Long,
        fingerprintKey: String,
    )

    fun getUnsyncedOverrides(excludeItemId: Long?): List<Pair<MediaItemRow, MetadataOverrideRow>>

    fun createPlaylist(name: String, isPracticePlaylist: Boolean, nowMs: Long): Long
    fun renamePlaylist(playlistId: Long, newName: String, nowMs: Long)
    fun setPlaylistPracticeMode(playlistId: Long, isPracticePlaylist: Boolean, nowMs: Long)
    fun deletePlaylist(playlistId: Long)
    fun addItemsToPlaylist(playlistId: Long, itemIds: List<Long>, nowMs: Long)
    fun removeItemFromPlaylist(playlistId: Long, itemId: Long, nowMs: Long)
    fun reorderPlaylistItems(playlistId: Long, orderedItemIds: List<Long>, nowMs: Long)

    fun getPracticeState(itemId: Long): PracticeState
    fun upsertPracticeState(state: PracticeState, nowMs: Long)
    fun getBookmarks(itemId: Long): List<PracticeBookmark>
    fun upsertBookmark(
        itemId: Long,
        label: String,
        aMs: Long,
        bMs: Long,
        savedSpeed: Float?,
        bookmarkId: Long?,
        nowMs: Long,
    ): PracticeBookmark
    fun deleteBookmark(bookmarkId: Long)
    fun updatePlaybackPosition(itemId: Long, positionMs: Long, nowMs: Long)

    fun getLyricsCache(itemId: Long): LyricsCacheRow?
    fun upsertLyricsCache(row: LyricsCacheRow)

    fun saveSettings(settings: AppSettings)
    fun clearEmbeddedCoverRefs()
}

/**
 * SQLite database engine implementing all 10 tables from `ARCHITECTURE.md` §2 (`DESIGN.md` §7.2)
 * with WAL concurrency and `PRAGMA foreign_keys = ON`.
 *
 * Automatically falls back to [InMemoryMediaStoreBackend] when running under stubbed `android.jar`
 * unit test environments (`writableDatabase == null`) so both unit and integration tests execute seamlessly.
 */
class MiniPlayerDatabase(
    context: Context,
    databaseName: String? = DATABASE_NAME,
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "miniplayer.db"
        const val DATABASE_VERSION = 2

        val SCHEMA_STATEMENTS: List<String> = listOf(
            """
            CREATE TABLE IF NOT EXISTS saf_roots (
                root_id INTEGER PRIMARY KEY AUTOINCREMENT,
                tree_uri TEXT NOT NULL UNIQUE,
                tree_doc_id TEXT NOT NULL,
                display_name TEXT NOT NULL,
                is_writable INTEGER NOT NULL DEFAULT 0,
                mount_state TEXT NOT NULL DEFAULT 'MOUNTED',
                last_scan_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS folder_rules (
                folder_uri TEXT PRIMARY KEY,
                root_id INTEGER NOT NULL REFERENCES saf_roots(root_id) ON DELETE CASCADE,
                rel_path TEXT NOT NULL,
                is_practice_folder INTEGER NOT NULL DEFAULT 0,
                exclude_from_grouping INTEGER NOT NULL DEFAULT 0,
                respect_nomedia INTEGER
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS idx_folder_rules_root_path ON folder_rules(root_id, rel_path)",
            """
            CREATE TABLE IF NOT EXISTS scanned_folders (
                root_id INTEGER NOT NULL REFERENCES saf_roots(root_id) ON DELETE CASCADE,
                rel_path TEXT NOT NULL,
                folder_uri TEXT NOT NULL,
                has_nomedia INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (root_id, rel_path)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS media_items (
                item_id INTEGER PRIMARY KEY AUTOINCREMENT,
                document_uri TEXT NOT NULL UNIQUE,
                root_id INTEGER NOT NULL REFERENCES saf_roots(root_id) ON DELETE CASCADE,
                parent_rel_path TEXT NOT NULL DEFAULT '',
                file_name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                is_video INTEGER NOT NULL DEFAULT 0,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                last_modified INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0,
                raw_title TEXT,
                raw_artist TEXT,
                raw_album TEXT,
                raw_album_artist TEXT,
                raw_track_num INTEGER,
                raw_year INTEGER,
                parsed_title TEXT NOT NULL,
                parsed_artist TEXT,
                parsed_album TEXT,
                is_backing_track INTEGER NOT NULL DEFAULT 0,
                embedded_cover_path TEXT,
                folder_cover_uri TEXT,
                fingerprint_key TEXT NOT NULL,
                last_position_ms INTEGER NOT NULL DEFAULT 0,
                last_played_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS idx_media_root_parent ON media_items(root_id, parent_rel_path)",
            "CREATE INDEX IF NOT EXISTS idx_media_fingerprint ON media_items(fingerprint_key)",
            "CREATE INDEX IF NOT EXISTS idx_media_is_video ON media_items(is_video)",
            """
            CREATE TABLE IF NOT EXISTS media_metadata_overrides (
                item_id INTEGER PRIMARY KEY REFERENCES media_items(item_id) ON DELETE CASCADE,
                title TEXT,
                artist TEXT,
                album TEXT,
                album_artist TEXT,
                track_num INTEGER,
                year INTEGER,
                cover_local_path TEXT,
                updated_at INTEGER NOT NULL,
                synced_to_file INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS media_item_artists (
                item_id INTEGER NOT NULL REFERENCES media_items(item_id) ON DELETE CASCADE,
                artist_norm TEXT NOT NULL,
                artist_display TEXT NOT NULL,
                ord INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (item_id, artist_norm)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS idx_artist_norm ON media_item_artists(artist_norm)",
            """
            CREATE TABLE IF NOT EXISTS playlists (
                playlist_id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                is_practice_playlist INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS playlist_items (
                playlist_id INTEGER NOT NULL REFERENCES playlists(playlist_id) ON DELETE CASCADE,
                item_id INTEGER NOT NULL REFERENCES media_items(item_id) ON DELETE CASCADE,
                sort_order INTEGER NOT NULL,
                added_at INTEGER NOT NULL,
                PRIMARY KEY (playlist_id, item_id)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS idx_playlist_sort ON playlist_items(playlist_id, sort_order)",
            """
            CREATE TABLE IF NOT EXISTS track_practice_states (
                item_id INTEGER PRIMARY KEY REFERENCES media_items(item_id) ON DELETE CASCADE,
                practice_speed REAL NOT NULL DEFAULT 1.0,
                pitch_semitones INTEGER NOT NULL DEFAULT 0,
                pitch_cents INTEGER NOT NULL DEFAULT 0,
                bpm INTEGER NOT NULL DEFAULT 120,
                count_in_mode TEXT NOT NULL DEFAULT 'OFF',
                pre_roll_enabled INTEGER NOT NULL DEFAULT 0,
                loop_enabled INTEGER NOT NULL DEFAULT 0,
                active_a_ms INTEGER,
                active_b_ms INTEGER,
                video_mirror INTEGER NOT NULL DEFAULT 0,
                video_zoom REAL NOT NULL DEFAULT 1.0,
                hud_dock_top INTEGER NOT NULL DEFAULT 0,
                video_seek_mode TEXT NOT NULL DEFAULT 'AUTO',
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS practice_bookmarks (
                bookmark_id INTEGER PRIMARY KEY AUTOINCREMENT,
                item_id INTEGER NOT NULL REFERENCES media_items(item_id) ON DELETE CASCADE,
                label TEXT NOT NULL,
                a_ms INTEGER NOT NULL,
                b_ms INTEGER NOT NULL,
                saved_speed REAL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS idx_bookmarks_item_a ON practice_bookmarks(item_id, a_ms)",
            """
            CREATE TABLE IF NOT EXISTS lyrics_cache (
                item_id INTEGER PRIMARY KEY REFERENCES media_items(item_id) ON DELETE CASCADE,
                synced_lrc TEXT,
                plain_lyrics TEXT,
                source TEXT NOT NULL,
                source_modified_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS app_settings (
                pref_key TEXT PRIMARY KEY,
                pref_value TEXT NOT NULL
            )
            """.trimIndent(),
        )

        fun createInMemoryBackend(): MediaStoreBackend = InMemoryMediaStoreBackend()
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        if (!db.isReadOnly && db.path != null && db.path != ":memory:") {
            runCatching { db.enableWriteAheadLogging() }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        for (sql in SCHEMA_STATEMENTS) {
            db.execSQL(sql)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
        if (oldVersion < 2) {
            runCatching {
                db.execSQL("ALTER TABLE folder_rules ADD COLUMN exclude_from_grouping INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) {
            val hasExcludeCol = runCatching {
                db.rawQuery("PRAGMA table_info(folder_rules)", null).use { c ->
                    val nameIdx = c.getColumnIndex("name")
                    var found = false
                    while (c.moveToNext()) {
                        if (nameIdx >= 0 && c.getString(nameIdx) == "exclude_from_grouping") {
                            found = true
                            break
                        }
                    }
                    found
                }
            }.getOrDefault(true)
            if (!hasExcludeCol) {
                runCatching {
                    db.execSQL("ALTER TABLE folder_rules ADD COLUMN exclude_from_grouping INTEGER NOT NULL DEFAULT 0")
                }
            }
        }
    }

    fun openBackend(): MediaStoreBackend {
        val sqliteDb = runCatching { writableDatabase }.getOrNull()
        return if (sqliteDb != null) {
            SqliteMediaStoreBackend(this)
        } else {
            InMemoryMediaStoreBackend()
        }
    }
}

internal fun computeEffectiveArtistAndAlbumArtist(
    item: MediaItemRow,
    override: MetadataOverrideRow?,
): Pair<String, String?> {
    val effArtist = when (val ovArtist = override?.artist) {
        null -> item.rawArtist?.takeIf { it.isNotBlank() }
            ?: item.parsedArtist?.takeIf { it.isNotBlank() }
            ?: "未知歌手"
        else -> ovArtist.takeIf { it.isNotBlank() } ?: "未知歌手"
    }
    val effAlbumArtist = when (val ovAlbumArtist = override?.albumArtist) {
        null -> item.rawAlbumArtist?.takeIf { it.isNotBlank() }
        else -> ovAlbumArtist.takeIf { it.isNotBlank() }
    }
    return effArtist to effAlbumArtist
}

internal fun joinRelSegments(prefix: String, child: String): String {
    val p = prefix.trim('/')
    val c = child.trim('/')
    return when {
        p.isEmpty() -> c
        c.isEmpty() -> p
        else -> "$p/$c"
    }
}

/**
 * Production SQLite backend executing ACID transactions against [MiniPlayerDatabase].
 */
class SqliteMediaStoreBackend(
    private val helper: MiniPlayerDatabase,
) : MediaStoreBackend {

    private val writeLock = Any()

    private inline fun <T> inTransaction(block: (SQLiteDatabase) -> T): T = synchronized(writeLock) {
        val db = helper.writableDatabase
        db.beginTransactionNonExclusive()
        try {
            val res = block(db)
            db.setTransactionSuccessful()
            res
        } finally {
            db.endTransaction()
        }
    }

    override fun readSnapshot(): DbSnapshot = synchronized(writeLock) {
        val db = helper.readableDatabase
        db.beginTransactionNonExclusive()
        try {
            val roots = queryAllRoots(db)
            val rules = queryAllFolderRules(db)
            val scannedFolders = queryAllScannedFolders(db)
            val items = queryAllMediaItems(db)
            val overrides = queryAllOverrides(db)
            val itemArtists = queryAllItemArtists(db)
            val playlists = queryAllPlaylists(db)
            val playlistItems = queryAllPlaylistItems(db)
            val practiceStates = queryAllPracticeStates(db)
            val bookmarks = queryAllBookmarks(db)
            val settings = querySettings(db)
            db.setTransactionSuccessful()
            DbSnapshot(
                safRoots = roots,
                folderRules = rules,
                scannedFolders = scannedFolders,
                mediaItems = items,
                overridesByItemId = overrides,
                artistsByItemId = itemArtists,
                playlists = playlists,
                playlistItems = playlistItems,
                practiceStatesByItemId = practiceStates,
                bookmarksByItemId = bookmarks,
                settings = settings,
            )
        } finally {
            db.endTransaction()
        }
    }

    override fun getMediaItemRow(itemId: Long): MediaItemRow? {
        val db = helper.readableDatabase
        db.rawQuery("SELECT * FROM media_items WHERE item_id = ?", arrayOf(itemId.toString())).use { c ->
            return if (c.moveToFirst()) readMediaItemCursor(c) else null
        }
    }

    override fun getOverrideRow(itemId: Long): MetadataOverrideRow? {
        val db = helper.readableDatabase
        db.rawQuery("SELECT * FROM media_metadata_overrides WHERE item_id = ?", arrayOf(itemId.toString())).use { c ->
            return if (c.moveToFirst()) readOverrideCursor(c) else null
        }
    }

    override fun getSafRootRow(rootId: Long): SafRootRow? {
        val db = helper.readableDatabase
        db.rawQuery("SELECT * FROM saf_roots WHERE root_id = ?", arrayOf(rootId.toString())).use { c ->
            return if (c.moveToFirst()) readRootCursor(c) else null
        }
    }

    override fun insertSafRoot(
        treeUri: String,
        treeDocId: String,
        displayName: String,
        isWritable: Boolean,
        mountState: MountState,
    ): Long = inTransaction { db ->
        val cv = ContentValues().apply {
            put("tree_uri", treeUri)
            put("tree_doc_id", treeDocId)
            put("display_name", displayName)
            put("is_writable", if (isWritable) 1 else 0)
            put("mount_state", mountState.name)
            put("last_scan_at", 0L)
        }
        db.insertOrThrow("saf_roots", null, cv)
    }

    override fun mergeChildRootsIntoParent(
        treeUri: String,
        treeDocId: String,
        displayName: String,
        isWritable: Boolean,
        merges: List<ChildRootMergeSpec>,
    ): Long = inTransaction { db ->
        val parentCv = ContentValues().apply {
            put("tree_uri", treeUri)
            put("tree_doc_id", treeDocId)
            put("display_name", displayName)
            put("is_writable", if (isWritable) 1 else 0)
            put("mount_state", MountState.MOUNTED.name)
            put("last_scan_at", 0L)
        }
        val newRootId = db.insertOrThrow("saf_roots", null, parentCv)

        for (spec in merges) {
            // 1. Migrate media_items preserving item_id (bookmarks, practice states, playlists untouched!)
            val childItems = ArrayList<MediaItemRow>()
            db.rawQuery("SELECT * FROM media_items WHERE root_id = ?", arrayOf(spec.oldRootId.toString())).use { c ->
                while (c.moveToNext()) childItems.add(readMediaItemCursor(c))
            }
            for (item in childItems) {
                val reboundUri = spec.rebindDocumentUri(item.documentUri)
                val reboundCoverUri = item.folderCoverUri?.let { spec.rebindDocumentUri(it) }
                val newRel = joinRelSegments(spec.relPathPrefix, item.parentRelPath)
                val itemCv = ContentValues().apply {
                    put("document_uri", reboundUri)
                    put("root_id", newRootId)
                    put("parent_rel_path", newRel)
                    if (reboundCoverUri != null) put("folder_cover_uri", reboundCoverUri) else putNull("folder_cover_uri")
                }
                db.updateWithOnConflict(
                    "media_items",
                    itemCv,
                    "item_id = ?",
                    arrayOf(item.itemId.toString()),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }

            // 2. Migrate folder_rules
            val childRules = ArrayList<FolderRuleRow>()
            db.rawQuery("SELECT * FROM folder_rules WHERE root_id = ?", arrayOf(spec.oldRootId.toString())).use { c ->
                while (c.moveToNext()) childRules.add(readFolderRuleCursor(c))
            }
            for (rule in childRules) {
                val ruleCv = ContentValues().apply {
                    put("folder_uri", spec.rebindDocumentUri(rule.folderUri))
                    put("root_id", newRootId)
                    put("rel_path", joinRelSegments(spec.relPathPrefix, rule.relPath))
                    put("is_practice_folder", if (rule.isPracticeFolder) 1 else 0)
                    put("exclude_from_grouping", if (rule.isExcludedFromAutoGrouping) 1 else 0)
                    if (rule.respectNomedia != null) put("respect_nomedia", if (rule.respectNomedia) 1 else 0) else putNull("respect_nomedia")
                }
                db.insertWithOnConflict("folder_rules", null, ruleCv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            // 3. Migrate scanned_folders
            val childScanned = ArrayList<ScannedFolderRow>()
            db.rawQuery("SELECT * FROM scanned_folders WHERE root_id = ?", arrayOf(spec.oldRootId.toString())).use { c ->
                while (c.moveToNext()) childScanned.add(readScannedFolderCursor(c))
            }
            for (sf in childScanned) {
                val sfCv = ContentValues().apply {
                    put("root_id", newRootId)
                    put("rel_path", joinRelSegments(spec.relPathPrefix, sf.relPath))
                    put("folder_uri", spec.rebindDocumentUri(sf.folderUri))
                    put("has_nomedia", if (sf.hasNomedia) 1 else 0)
                }
                db.insertWithOnConflict("scanned_folders", null, sfCv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            // 4. Delete superseded child root
            db.delete("saf_roots", "root_id = ?", arrayOf(spec.oldRootId.toString()))
        }
        newRootId
    }

    override fun updateSafRootStatus(
        rootId: Long,
        mountState: MountState,
        isWritable: Boolean?,
        lastScanAt: Long?,
    ) = inTransaction { db ->
        val cv = ContentValues().apply {
            put("mount_state", mountState.name)
            if (isWritable != null) put("is_writable", if (isWritable) 1 else 0)
            if (lastScanAt != null) put("last_scan_at", lastScanAt)
        }
        db.update("saf_roots", cv, "root_id = ?", arrayOf(rootId.toString()))
        Unit
    }

    override fun deleteSafRoot(rootId: Long) = inTransaction { db ->
        db.delete("saf_roots", "root_id = ?", arrayOf(rootId.toString()))
        Unit
    }

    override fun upsertFolderRule(rule: FolderRuleRow) = inTransaction { db ->
        db.delete(
            "folder_rules",
            "root_id = ? AND rel_path = ?",
            arrayOf(rule.rootId.toString(), rule.relPath),
        )
        val cv = ContentValues().apply {
            put("folder_uri", rule.folderUri)
            put("root_id", rule.rootId)
            put("rel_path", rule.relPath)
            put("is_practice_folder", if (rule.isPracticeFolder) 1 else 0)
            put("exclude_from_grouping", if (rule.isExcludedFromAutoGrouping) 1 else 0)
            if (rule.respectNomedia != null) put("respect_nomedia", if (rule.respectNomedia) 1 else 0) else putNull("respect_nomedia")
        }
        db.insertWithOnConflict("folder_rules", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    override fun reconcileRootScan(
        rootId: Long,
        isWritable: Boolean,
        scannedAtMs: Long,
        scannedFolders: List<ScannedFolderRow>,
        unchangedCoverUpdates: Map<Long, String?>,
        movedItems: List<MovedMediaUpdate>,
        upsertedItems: List<MediaItemRow>,
        deletedItemIds: Set<Long>,
        lyricsUpdates: List<Pair<String, LyricsCacheRow>>,
        artistSplitter: (effectiveArtist: String, effectiveAlbumArtist: String?) -> List<SplitArtist>,
    ) = inTransaction { db ->
        // 1. Update saf_roots state
        val rootCv = ContentValues().apply {
            put("mount_state", MountState.MOUNTED.name)
            put("is_writable", if (isWritable) 1 else 0)
            put("last_scan_at", scannedAtMs)
        }
        db.update("saf_roots", rootCv, "root_id = ?", arrayOf(rootId.toString()))

        // 2. Replace scanned_folders for rootId
        db.delete("scanned_folders", "root_id = ?", arrayOf(rootId.toString()))
        for (sf in scannedFolders) {
            val sfCv = ContentValues().apply {
                put("root_id", sf.rootId)
                put("rel_path", sf.relPath)
                put("folder_uri", sf.folderUri)
                put("has_nomedia", if (sf.hasNomedia) 1 else 0)
            }
            db.insertWithOnConflict("scanned_folders", null, sfCv, SQLiteDatabase.CONFLICT_REPLACE)
        }

        // 3. Delete truly removed items FIRST so moved items can claim any reused path cleanly
        for (delId in deletedItemIds) {
            db.delete("media_items", "item_id = ? AND root_id = ?", arrayOf(delId.toString(), rootId.toString()))
        }

        // 4. Update unchanged items' folder_cover_uri if changed
        for ((itemId, coverUri) in unchangedCoverUpdates) {
            val cv = ContentValues().apply {
                if (coverUri != null) put("folder_cover_uri", coverUri) else putNull("folder_cover_uri")
            }
            db.update("media_items", cv, "item_id = ?", arrayOf(itemId.toString()))
        }

        // 5. Migrate moved items in-place preserving item_id!
        val uriToItemId = HashMap<String, Long>()
        for (mv in movedItems) {
            val cv = ContentValues().apply {
                put("document_uri", mv.newDocumentUri)
                put("root_id", mv.newRootId)
                put("parent_rel_path", mv.newParentRelPath)
                put("file_name", mv.newFileName)
                put("size_bytes", mv.newSizeBytes)
                put("last_modified", mv.newLastModified)
                if (mv.newFolderCoverUri != null) put("folder_cover_uri", mv.newFolderCoverUri) else putNull("folder_cover_uri")
                put("fingerprint_key", mv.newFingerprintKey)
                put("parsed_title", mv.parsedTitle)
                putNullableString("parsed_artist", mv.parsedArtist)
                putNullableString("parsed_album", mv.parsedAlbum)
                put("is_backing_track", if (mv.isBackingTrack) 1 else 0)
            }
            db.update("media_items", cv, "item_id = ?", arrayOf(mv.existingItemId.toString()))
            uriToItemId[mv.newDocumentUri] = mv.existingItemId
            rebuildArtistsForItemSqlite(db, mv.existingItemId, artistSplitter)
        }

        // 6. Upsert new / modified items
        for (row in upsertedItems) {
            val existingId = findItemIdByUri(db, row.documentUri)
            val cv = mediaItemContentValues(row, includePositionFields = existingId == null)
            val finalId = if (existingId != null) {
                db.update("media_items", cv, "item_id = ?", arrayOf(existingId.toString()))
                existingId
            } else {
                db.insertOrThrow("media_items", null, cv)
            }
            uriToItemId[row.documentUri] = finalId
            rebuildArtistsForItemSqlite(db, finalId, artistSplitter)
        }

        // 7. Upsert sidecar / embedded lyrics cache
        for ((docUri, lrcRow) in lyricsUpdates) {
            val itemId = uriToItemId[docUri] ?: findItemIdByUri(db, docUri) ?: continue
            // Do not overwrite LRCLIB or LOCAL_LRC with EMBEDDED
            if (lrcRow.source == LyricsSource.EMBEDDED) {
                val existingSource = findLyricsSource(db, itemId)
                if (existingSource == LyricsSource.LOCAL_LRC || existingSource == LyricsSource.LRCLIB) {
                    continue
                }
            }
            upsertLyricsSqlite(db, lrcRow.copy(itemId = itemId))
        }
    }

    override fun upsertMetadataOverrides(
        entries: List<MetadataOverrideRow>,
        artistSplitter: (effectiveArtist: String, effectiveAlbumArtist: String?) -> List<SplitArtist>,
    ) = inTransaction { db ->
        for (ov in entries) {
            val cv = ContentValues().apply {
                put("item_id", ov.itemId)
                putNullableString("title", ov.title)
                putNullableString("artist", ov.artist)
                putNullableString("album", ov.album)
                putNullableString("album_artist", ov.albumArtist)
                if (ov.trackNum != null) put("track_num", ov.trackNum) else putNull("track_num")
                if (ov.year != null) put("year", ov.year) else putNull("year")
                putNullableString("cover_local_path", ov.coverLocalPath)
                put("updated_at", ov.updatedAt)
                put("synced_to_file", if (ov.syncedToFile) 1 else 0)
            }
            db.insertWithOnConflict("media_metadata_overrides", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            rebuildArtistsForItemSqlite(db, ov.itemId, artistSplitter)
        }
    }

    override fun deleteMetadataOverrides(
        itemIds: List<Long>,
        artistSplitter: (effectiveArtist: String, effectiveAlbumArtist: String?) -> List<SplitArtist>,
    ) = inTransaction { db ->
        for (id in itemIds) {
            db.delete("media_metadata_overrides", "item_id = ?", arrayOf(id.toString()))
            rebuildArtistsForItemSqlite(db, id, artistSplitter)
        }
    }

    override fun markOverrideSyncedAndUpdateStat(
        itemId: Long,
        sizeBytes: Long,
        lastModified: Long,
        fingerprintKey: String,
    ) = inTransaction { db ->
        val ovCv = ContentValues().apply { put("synced_to_file", 1) }
        db.update("media_metadata_overrides", ovCv, "item_id = ?", arrayOf(itemId.toString()))
        val itemCv = ContentValues().apply {
            put("size_bytes", sizeBytes)
            put("last_modified", lastModified)
            put("fingerprint_key", fingerprintKey)
        }
        db.update("media_items", itemCv, "item_id = ?", arrayOf(itemId.toString()))
        Unit
    }

    override fun getUnsyncedOverrides(excludeItemId: Long?): List<Pair<MediaItemRow, MetadataOverrideRow>> {
        val db = helper.readableDatabase
        val out = ArrayList<Pair<MediaItemRow, MetadataOverrideRow>>()
        db.rawQuery("SELECT * FROM media_metadata_overrides WHERE synced_to_file = 0", null).use { c ->
            while (c.moveToNext()) {
                val ov = readOverrideCursor(c)
                if (excludeItemId != null && ov.itemId == excludeItemId) continue
                val item = getMediaItemRow(ov.itemId) ?: continue
                out.add(item to ov)
            }
        }
        return out
    }

    override fun createPlaylist(name: String, isPracticePlaylist: Boolean, nowMs: Long): Long = inTransaction { db ->
        val cv = ContentValues().apply {
            put("name", name)
            put("is_practice_playlist", if (isPracticePlaylist) 1 else 0)
            put("created_at", nowMs)
            put("updated_at", nowMs)
        }
        db.insertOrThrow("playlists", null, cv)
    }

    override fun renamePlaylist(playlistId: Long, newName: String, nowMs: Long) = inTransaction { db ->
        val cv = ContentValues().apply {
            put("name", newName)
            put("updated_at", nowMs)
        }
        db.update("playlists", cv, "playlist_id = ?", arrayOf(playlistId.toString()))
        Unit
    }

    override fun setPlaylistPracticeMode(playlistId: Long, isPracticePlaylist: Boolean, nowMs: Long) = inTransaction { db ->
        val cv = ContentValues().apply {
            put("is_practice_playlist", if (isPracticePlaylist) 1 else 0)
            put("updated_at", nowMs)
        }
        db.update("playlists", cv, "playlist_id = ?", arrayOf(playlistId.toString()))
        Unit
    }

    override fun deletePlaylist(playlistId: Long) = inTransaction { db ->
        db.delete("playlists", "playlist_id = ?", arrayOf(playlistId.toString()))
        Unit
    }

    override fun addItemsToPlaylist(playlistId: Long, itemIds: List<Long>, nowMs: Long) = inTransaction { db ->
        var maxOrder = -1
        db.rawQuery(
            "SELECT COALESCE(MAX(sort_order), -1) FROM playlist_items WHERE playlist_id = ?",
            arrayOf(playlistId.toString()),
        ).use { c ->
            if (c.moveToFirst()) maxOrder = c.getInt(0)
        }
        for (itemId in itemIds) {
            val cv = ContentValues().apply {
                put("playlist_id", playlistId)
                put("item_id", itemId)
                put("sort_order", ++maxOrder)
                put("added_at", nowMs)
            }
            db.insertWithOnConflict("playlist_items", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        }
        touchPlaylist(db, playlistId, nowMs)
    }

    override fun removeItemFromPlaylist(playlistId: Long, itemId: Long, nowMs: Long) = inTransaction { db ->
        db.delete(
            "playlist_items",
            "playlist_id = ? AND item_id = ?",
            arrayOf(playlistId.toString(), itemId.toString()),
        )
        touchPlaylist(db, playlistId, nowMs)
    }

    override fun reorderPlaylistItems(playlistId: Long, orderedItemIds: List<Long>, nowMs: Long) = inTransaction { db ->
        orderedItemIds.forEachIndexed { idx, itemId ->
            val cv = ContentValues().apply { put("sort_order", idx) }
            db.update(
                "playlist_items",
                cv,
                "playlist_id = ? AND item_id = ?",
                arrayOf(playlistId.toString(), itemId.toString()),
            )
        }
        touchPlaylist(db, playlistId, nowMs)
    }

    override fun getPracticeState(itemId: Long): PracticeState {
        val db = helper.readableDatabase
        db.rawQuery("SELECT * FROM track_practice_states WHERE item_id = ?", arrayOf(itemId.toString())).use { c ->
            return if (c.moveToFirst()) readPracticeStateCursor(c) else PracticeState(itemId = itemId)
        }
    }

    override fun upsertPracticeState(state: PracticeState, nowMs: Long) = inTransaction { db ->
        val cv = ContentValues().apply {
            put("item_id", state.itemId)
            put("practice_speed", state.practiceSpeed.toDouble())
            put("pitch_semitones", state.pitchSemitones)
            put("pitch_cents", state.pitchCents)
            put("bpm", state.bpm)
            put("count_in_mode", state.countInMode.name)
            put("pre_roll_enabled", if (state.preRollEnabled) 1 else 0)
            put("loop_enabled", if (state.loopEnabled) 1 else 0)
            if (state.activeAMs != null) put("active_a_ms", state.activeAMs) else putNull("active_a_ms")
            if (state.activeBMs != null) put("active_b_ms", state.activeBMs) else putNull("active_b_ms")
            put("video_mirror", if (state.videoMirror) 1 else 0)
            put("video_zoom", state.videoZoom.toDouble())
            put("hud_dock_top", if (state.hudDockTop) 1 else 0)
            put("video_seek_mode", state.videoSeekMode.name)
            put("updated_at", nowMs)
        }
        db.insertWithOnConflict("track_practice_states", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    override fun getBookmarks(itemId: Long): List<PracticeBookmark> {
        val db = helper.readableDatabase
        val out = ArrayList<PracticeBookmark>()
        db.rawQuery(
            "SELECT * FROM practice_bookmarks WHERE item_id = ? ORDER BY a_ms ASC, bookmark_id ASC",
            arrayOf(itemId.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(readBookmarkCursor(c))
        }
        return out
    }

    override fun upsertBookmark(
        itemId: Long,
        label: String,
        aMs: Long,
        bMs: Long,
        savedSpeed: Float?,
        bookmarkId: Long?,
        nowMs: Long,
    ): PracticeBookmark = inTransaction { db ->
        val cv = ContentValues().apply {
            put("item_id", itemId)
            put("label", label)
            put("a_ms", aMs)
            put("b_ms", bMs)
            if (savedSpeed != null) put("saved_speed", savedSpeed.toDouble()) else putNull("saved_speed")
        }
        val finalId = if (bookmarkId != null && bookmarkId > 0L) {
            db.update("practice_bookmarks", cv, "bookmark_id = ?", arrayOf(bookmarkId.toString()))
            bookmarkId
        } else {
            cv.put("created_at", nowMs)
            db.insertOrThrow("practice_bookmarks", null, cv)
        }
        PracticeBookmark(
            bookmarkId = finalId,
            itemId = itemId,
            label = label,
            aMs = aMs,
            bMs = bMs,
            savedSpeed = savedSpeed,
            createdAt = nowMs,
        )
    }

    override fun deleteBookmark(bookmarkId: Long) = inTransaction { db ->
        db.delete("practice_bookmarks", "bookmark_id = ?", arrayOf(bookmarkId.toString()))
        Unit
    }

    override fun updatePlaybackPosition(itemId: Long, positionMs: Long, nowMs: Long) = inTransaction { db ->
        val cv = ContentValues().apply {
            put("last_position_ms", positionMs.coerceAtLeast(0L))
            put("last_played_at", nowMs)
        }
        db.update("media_items", cv, "item_id = ?", arrayOf(itemId.toString()))
        Unit
    }

    override fun getLyricsCache(itemId: Long): LyricsCacheRow? {
        val db = helper.readableDatabase
        db.rawQuery("SELECT * FROM lyrics_cache WHERE item_id = ?", arrayOf(itemId.toString())).use { c ->
            return if (c.moveToFirst()) readLyricsCursor(c) else null
        }
    }

    override fun upsertLyricsCache(row: LyricsCacheRow) = inTransaction { db ->
        upsertLyricsSqlite(db, row)
    }

    override fun saveSettings(settings: AppSettings) = inTransaction { db ->
        val entries = mapOf(
            "autoRescanOnLaunch" to settings.autoRescanOnLaunch.toString(),
            "respectSubfolderNomedia" to settings.respectSubfolderNomedia.toString(),
            "writeTagsToFileDefault" to settings.writeTagsToFileDefault.toString(),
            "countInClickSound" to settings.countInClickSound.toString(),
            "keepScreenOnInPractice" to settings.keepScreenOnInPractice.toString(),
        )
        for ((k, v) in entries) {
            val cv = ContentValues().apply {
                put("pref_key", k)
                put("pref_value", v)
            }
            db.insertWithOnConflict("app_settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override fun clearEmbeddedCoverRefs() = inTransaction { db ->
        val cv = ContentValues().apply { putNull("embedded_cover_path") }
        db.update("media_items", cv, null, null)
        Unit
    }

    // --- SQLite Private Helpers ---

    private fun touchPlaylist(db: SQLiteDatabase, playlistId: Long, nowMs: Long) {
        val cv = ContentValues().apply { put("updated_at", nowMs) }
        db.update("playlists", cv, "playlist_id = ?", arrayOf(playlistId.toString()))
    }

    private fun findItemIdByUri(db: SQLiteDatabase, docUri: String): Long? {
        db.rawQuery("SELECT item_id FROM media_items WHERE document_uri = ?", arrayOf(docUri)).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    private fun findLyricsSource(db: SQLiteDatabase, itemId: Long): LyricsSource? {
        db.rawQuery("SELECT source FROM lyrics_cache WHERE item_id = ?", arrayOf(itemId.toString())).use { c ->
            if (!c.moveToFirst()) return null
            return runCatching { LyricsSource.valueOf(c.getString(0)) }.getOrNull()
        }
    }

    private fun upsertLyricsSqlite(db: SQLiteDatabase, row: LyricsCacheRow) {
        val cv = ContentValues().apply {
            put("item_id", row.itemId)
            putNullableString("synced_lrc", row.syncedLrc)
            putNullableString("plain_lyrics", row.plainLyrics)
            put("source", row.source.name)
            put("source_modified_at", row.sourceModifiedAt)
        }
        db.insertWithOnConflict("lyrics_cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun rebuildArtistsForItemSqlite(
        db: SQLiteDatabase,
        itemId: Long,
        artistSplitter: (effectiveArtist: String, effectiveAlbumArtist: String?) -> List<SplitArtist>,
    ) {
        val item = db.rawQuery("SELECT * FROM media_items WHERE item_id = ?", arrayOf(itemId.toString())).use { c ->
            if (c.moveToFirst()) readMediaItemCursor(c) else null
        } ?: return
        val ov = db.rawQuery("SELECT * FROM media_metadata_overrides WHERE item_id = ?", arrayOf(itemId.toString())).use { c ->
            if (c.moveToFirst()) readOverrideCursor(c) else null
        }
        db.delete("media_item_artists", "item_id = ?", arrayOf(itemId.toString()))
        if (item.isVideo) return

        val (effArtist, effAlbumArtist) = computeEffectiveArtistAndAlbumArtist(item, ov)
        val split = artistSplitter(effArtist, effAlbumArtist)
        split.forEachIndexed { idx, sp ->
            val cv = ContentValues().apply {
                put("item_id", itemId)
                put("artist_norm", sp.norm)
                put("artist_display", sp.display)
                put("ord", idx)
            }
            db.insertWithOnConflict("media_item_artists", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private fun mediaItemContentValues(row: MediaItemRow, includePositionFields: Boolean): ContentValues =
        ContentValues().apply {
            put("document_uri", row.documentUri)
            put("root_id", row.rootId)
            put("parent_rel_path", row.parentRelPath)
            put("file_name", row.fileName)
            put("mime_type", row.mimeType)
            put("is_video", if (row.isVideo) 1 else 0)
            put("size_bytes", row.sizeBytes)
            put("last_modified", row.lastModified)
            put("duration_ms", row.durationMs)
            put("width", row.width)
            put("height", row.height)
            putNullableString("raw_title", row.rawTitle)
            putNullableString("raw_artist", row.rawArtist)
            putNullableString("raw_album", row.rawAlbum)
            putNullableString("raw_album_artist", row.rawAlbumArtist)
            if (row.rawTrackNum != null) put("raw_track_num", row.rawTrackNum) else putNull("raw_track_num")
            if (row.rawYear != null) put("raw_year", row.rawYear) else putNull("raw_year")
            put("parsed_title", row.parsedTitle)
            putNullableString("parsed_artist", row.parsedArtist)
            putNullableString("parsed_album", row.parsedAlbum)
            put("is_backing_track", if (row.isBackingTrack) 1 else 0)
            putNullableString("embedded_cover_path", row.embeddedCoverPath)
            putNullableString("folder_cover_uri", row.folderCoverUri)
            put("fingerprint_key", row.fingerprintKey)
            if (includePositionFields) {
                put("last_position_ms", row.lastPositionMs)
                put("last_played_at", row.lastPlayedAt)
            }
        }

    private fun ContentValues.putNullableString(key: String, value: String?) {
        if (value != null) put(key, value) else putNull(key)
    }

    private fun queryAllRoots(db: SQLiteDatabase): List<SafRootRow> {
        val out = ArrayList<SafRootRow>()
        db.rawQuery("SELECT * FROM saf_roots ORDER BY root_id ASC", null).use { c ->
            while (c.moveToNext()) out.add(readRootCursor(c))
        }
        return out
    }

    private fun queryAllFolderRules(db: SQLiteDatabase): List<FolderRuleRow> {
        val out = ArrayList<FolderRuleRow>()
        db.rawQuery("SELECT * FROM folder_rules", null).use { c ->
            while (c.moveToNext()) out.add(readFolderRuleCursor(c))
        }
        return out
    }

    private fun queryAllScannedFolders(db: SQLiteDatabase): List<ScannedFolderRow> {
        val out = ArrayList<ScannedFolderRow>()
        db.rawQuery("SELECT * FROM scanned_folders", null).use { c ->
            while (c.moveToNext()) out.add(readScannedFolderCursor(c))
        }
        return out
    }

    private fun queryAllMediaItems(db: SQLiteDatabase): List<MediaItemRow> {
        val out = ArrayList<MediaItemRow>()
        db.rawQuery("SELECT * FROM media_items ORDER BY item_id ASC", null).use { c ->
            while (c.moveToNext()) out.add(readMediaItemCursor(c))
        }
        return out
    }

    private fun queryAllOverrides(db: SQLiteDatabase): Map<Long, MetadataOverrideRow> {
        val out = HashMap<Long, MetadataOverrideRow>()
        db.rawQuery("SELECT * FROM media_metadata_overrides", null).use { c ->
            while (c.moveToNext()) {
                val r = readOverrideCursor(c)
                out[r.itemId] = r
            }
        }
        return out
    }

    private fun queryAllItemArtists(db: SQLiteDatabase): Map<Long, List<MediaItemArtistRow>> {
        val out = HashMap<Long, MutableList<MediaItemArtistRow>>()
        db.rawQuery("SELECT * FROM media_item_artists ORDER BY item_id ASC, ord ASC", null).use { c ->
            while (c.moveToNext()) {
                val r = MediaItemArtistRow(
                    itemId = c.getLong(c.getColumnIndexOrThrow("item_id")),
                    artistNorm = c.getString(c.getColumnIndexOrThrow("artist_norm")),
                    artistDisplay = c.getString(c.getColumnIndexOrThrow("artist_display")),
                    ord = c.getInt(c.getColumnIndexOrThrow("ord")),
                )
                out.getOrPut(r.itemId) { ArrayList() }.add(r)
            }
        }
        return out
    }

    private fun queryAllPlaylists(db: SQLiteDatabase): List<PlaylistRow> {
        val out = ArrayList<PlaylistRow>()
        db.rawQuery("SELECT * FROM playlists ORDER BY is_practice_playlist DESC, updated_at DESC, playlist_id DESC", null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    PlaylistRow(
                        playlistId = c.getLong(c.getColumnIndexOrThrow("playlist_id")),
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        isPracticePlaylist = c.getInt(c.getColumnIndexOrThrow("is_practice_playlist")) != 0,
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
                    ),
                )
            }
        }
        return out
    }

    private fun queryAllPlaylistItems(db: SQLiteDatabase): List<PlaylistItemRow> {
        val out = ArrayList<PlaylistItemRow>()
        db.rawQuery("SELECT * FROM playlist_items ORDER BY playlist_id ASC, sort_order ASC", null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    PlaylistItemRow(
                        playlistId = c.getLong(c.getColumnIndexOrThrow("playlist_id")),
                        itemId = c.getLong(c.getColumnIndexOrThrow("item_id")),
                        sortOrder = c.getInt(c.getColumnIndexOrThrow("sort_order")),
                        addedAt = c.getLong(c.getColumnIndexOrThrow("added_at")),
                    ),
                )
            }
        }
        return out
    }

    private fun queryAllPracticeStates(db: SQLiteDatabase): Map<Long, PracticeState> {
        val out = HashMap<Long, PracticeState>()
        db.rawQuery("SELECT * FROM track_practice_states", null).use { c ->
            while (c.moveToNext()) {
                val ps = readPracticeStateCursor(c)
                out[ps.itemId] = ps
            }
        }
        return out
    }

    private fun queryAllBookmarks(db: SQLiteDatabase): Map<Long, List<PracticeBookmark>> {
        val out = HashMap<Long, MutableList<PracticeBookmark>>()
        db.rawQuery("SELECT * FROM practice_bookmarks ORDER BY item_id ASC, a_ms ASC, bookmark_id ASC", null).use { c ->
            while (c.moveToNext()) {
                val bm = readBookmarkCursor(c)
                out.getOrPut(bm.itemId) { ArrayList() }.add(bm)
            }
        }
        return out
    }

    private fun querySettings(db: SQLiteDatabase): AppSettings {
        val map = HashMap<String, String>()
        db.rawQuery("SELECT pref_key, pref_value FROM app_settings", null).use { c ->
            while (c.moveToNext()) {
                map[c.getString(0)] = c.getString(1)
            }
        }
        val defaults = AppSettings()
        return AppSettings(
            autoRescanOnLaunch = map["autoRescanOnLaunch"]?.toBooleanStrictOrNull() ?: defaults.autoRescanOnLaunch,
            respectSubfolderNomedia = map["respectSubfolderNomedia"]?.toBooleanStrictOrNull() ?: defaults.respectSubfolderNomedia,
            writeTagsToFileDefault = map["writeTagsToFileDefault"]?.toBooleanStrictOrNull() ?: defaults.writeTagsToFileDefault,
            countInClickSound = map["countInClickSound"]?.toBooleanStrictOrNull() ?: defaults.countInClickSound,
            keepScreenOnInPractice = map["keepScreenOnInPractice"]?.toBooleanStrictOrNull() ?: defaults.keepScreenOnInPractice,
        )
    }

    private fun readRootCursor(c: Cursor): SafRootRow = SafRootRow(
        rootId = c.getLong(c.getColumnIndexOrThrow("root_id")),
        treeUri = c.getString(c.getColumnIndexOrThrow("tree_uri")),
        treeDocId = c.getString(c.getColumnIndexOrThrow("tree_doc_id")),
        displayName = c.getString(c.getColumnIndexOrThrow("display_name")),
        isWritable = c.getInt(c.getColumnIndexOrThrow("is_writable")) != 0,
        mountState = runCatching { MountState.valueOf(c.getString(c.getColumnIndexOrThrow("mount_state"))) }.getOrDefault(MountState.MOUNTED),
        lastScanAt = c.getLong(c.getColumnIndexOrThrow("last_scan_at")),
    )

    private fun readFolderRuleCursor(c: Cursor): FolderRuleRow {
        val nomediaIdx = c.getColumnIndexOrThrow("respect_nomedia")
        val exIdx = c.getColumnIndex("exclude_from_grouping")
        return FolderRuleRow(
            folderUri = c.getString(c.getColumnIndexOrThrow("folder_uri")),
            rootId = c.getLong(c.getColumnIndexOrThrow("root_id")),
            relPath = c.getString(c.getColumnIndexOrThrow("rel_path")),
            isPracticeFolder = c.getInt(c.getColumnIndexOrThrow("is_practice_folder")) != 0,
            respectNomedia = if (c.isNull(nomediaIdx)) null else c.getInt(nomediaIdx) != 0,
            isExcludedFromAutoGrouping = exIdx >= 0 && !c.isNull(exIdx) && c.getInt(exIdx) != 0,
        )
    }

    private fun readScannedFolderCursor(c: Cursor): ScannedFolderRow = ScannedFolderRow(
        rootId = c.getLong(c.getColumnIndexOrThrow("root_id")),
        relPath = c.getString(c.getColumnIndexOrThrow("rel_path")),
        folderUri = c.getString(c.getColumnIndexOrThrow("folder_uri")),
        hasNomedia = c.getInt(c.getColumnIndexOrThrow("has_nomedia")) != 0,
    )

    private fun readMediaItemCursor(c: Cursor): MediaItemRow = MediaItemRow(
        itemId = c.getLong(c.getColumnIndexOrThrow("item_id")),
        documentUri = c.getString(c.getColumnIndexOrThrow("document_uri")),
        rootId = c.getLong(c.getColumnIndexOrThrow("root_id")),
        parentRelPath = c.getString(c.getColumnIndexOrThrow("parent_rel_path")),
        fileName = c.getString(c.getColumnIndexOrThrow("file_name")),
        mimeType = c.getString(c.getColumnIndexOrThrow("mime_type")),
        isVideo = c.getInt(c.getColumnIndexOrThrow("is_video")) != 0,
        sizeBytes = c.getLong(c.getColumnIndexOrThrow("size_bytes")),
        lastModified = c.getLong(c.getColumnIndexOrThrow("last_modified")),
        durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms")),
        width = c.getInt(c.getColumnIndexOrThrow("width")),
        height = c.getInt(c.getColumnIndexOrThrow("height")),
        rawTitle = c.optString("raw_title"),
        rawArtist = c.optString("raw_artist"),
        rawAlbum = c.optString("raw_album"),
        rawAlbumArtist = c.optString("raw_album_artist"),
        rawTrackNum = c.optInt("raw_track_num"),
        rawYear = c.optInt("raw_year"),
        parsedTitle = c.getString(c.getColumnIndexOrThrow("parsed_title")),
        parsedArtist = c.optString("parsed_artist"),
        parsedAlbum = c.optString("parsed_album"),
        isBackingTrack = c.getInt(c.getColumnIndexOrThrow("is_backing_track")) != 0,
        embeddedCoverPath = c.optString("embedded_cover_path"),
        folderCoverUri = c.optString("folder_cover_uri"),
        fingerprintKey = c.getString(c.getColumnIndexOrThrow("fingerprint_key")),
        lastPositionMs = c.getLong(c.getColumnIndexOrThrow("last_position_ms")),
        lastPlayedAt = c.getLong(c.getColumnIndexOrThrow("last_played_at")),
    )

    private fun readOverrideCursor(c: Cursor): MetadataOverrideRow = MetadataOverrideRow(
        itemId = c.getLong(c.getColumnIndexOrThrow("item_id")),
        title = c.optString("title"),
        artist = c.optString("artist"),
        album = c.optString("album"),
        albumArtist = c.optString("album_artist"),
        trackNum = c.optInt("track_num"),
        year = c.optInt("year"),
        coverLocalPath = c.optString("cover_local_path"),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
        syncedToFile = c.getInt(c.getColumnIndexOrThrow("synced_to_file")) != 0,
    )

    private fun readPracticeStateCursor(c: Cursor): PracticeState = PracticeState(
        itemId = c.getLong(c.getColumnIndexOrThrow("item_id")),
        practiceSpeed = c.getDouble(c.getColumnIndexOrThrow("practice_speed")).toFloat(),
        pitchSemitones = c.getInt(c.getColumnIndexOrThrow("pitch_semitones")),
        pitchCents = c.getInt(c.getColumnIndexOrThrow("pitch_cents")),
        bpm = c.getInt(c.getColumnIndexOrThrow("bpm")),
        countInMode = runCatching { CountInMode.valueOf(c.getString(c.getColumnIndexOrThrow("count_in_mode"))) }.getOrDefault(CountInMode.OFF),
        preRollEnabled = c.getInt(c.getColumnIndexOrThrow("pre_roll_enabled")) != 0,
        loopEnabled = c.getInt(c.getColumnIndexOrThrow("loop_enabled")) != 0,
        activeAMs = c.optLong("active_a_ms"),
        activeBMs = c.optLong("active_b_ms"),
        videoMirror = c.getInt(c.getColumnIndexOrThrow("video_mirror")) != 0,
        videoZoom = c.getDouble(c.getColumnIndexOrThrow("video_zoom")).toFloat(),
        hudDockTop = c.getInt(c.getColumnIndexOrThrow("hud_dock_top")) != 0,
        videoSeekMode = runCatching { VideoSeekMode.valueOf(c.getString(c.getColumnIndexOrThrow("video_seek_mode"))) }.getOrDefault(VideoSeekMode.AUTO),
    )

    private fun readBookmarkCursor(c: Cursor): PracticeBookmark {
        val speedIdx = c.getColumnIndexOrThrow("saved_speed")
        return PracticeBookmark(
            bookmarkId = c.getLong(c.getColumnIndexOrThrow("bookmark_id")),
            itemId = c.getLong(c.getColumnIndexOrThrow("item_id")),
            label = c.getString(c.getColumnIndexOrThrow("label")),
            aMs = c.getLong(c.getColumnIndexOrThrow("a_ms")),
            bMs = c.getLong(c.getColumnIndexOrThrow("b_ms")),
            savedSpeed = if (c.isNull(speedIdx)) null else c.getDouble(speedIdx).toFloat(),
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        )
    }

    private fun readLyricsCursor(c: Cursor): LyricsCacheRow = LyricsCacheRow(
        itemId = c.getLong(c.getColumnIndexOrThrow("item_id")),
        syncedLrc = c.optString("synced_lrc"),
        plainLyrics = c.optString("plain_lyrics"),
        source = runCatching { LyricsSource.valueOf(c.getString(c.getColumnIndexOrThrow("source"))) }.getOrDefault(LyricsSource.EMBEDDED),
        sourceModifiedAt = c.getLong(c.getColumnIndexOrThrow("source_modified_at")),
    )

    private fun Cursor.optString(col: String): String? {
        val idx = getColumnIndexOrThrow(col)
        return if (isNull(idx)) null else getString(idx)
    }

    private fun Cursor.optInt(col: String): Int? {
        val idx = getColumnIndexOrThrow(col)
        return if (isNull(idx)) null else getInt(idx)
    }

    private fun Cursor.optLong(col: String): Long? {
        val idx = getColumnIndexOrThrow(col)
        return if (isNull(idx)) null else getLong(idx)
    }
}

/**
 * Pure-JVM relational backend mirroring all SQLite foreign-key cascade and uniqueness semantics
 * for fast JUnit 4 unit & integration tests without Robolectric.
 */
internal class InMemoryMediaStoreBackend : MediaStoreBackend {

    private val lock = Any()
    private val rootSeq = AtomicLong(1L)
    private val itemSeq = AtomicLong(1L)
    private val playlistSeq = AtomicLong(1L)
    private val bookmarkSeq = AtomicLong(1L)

    private val roots = LinkedHashMap<Long, SafRootRow>()
    private val folderRules = LinkedHashMap<String, FolderRuleRow>()
    private val scannedFolders = LinkedHashMap<Pair<Long, String>, ScannedFolderRow>()
    private val items = LinkedHashMap<Long, MediaItemRow>()
    private val overrides = LinkedHashMap<Long, MetadataOverrideRow>()
    private val itemArtists = LinkedHashMap<Long, List<MediaItemArtistRow>>()
    private val playlists = LinkedHashMap<Long, PlaylistRow>()
    private val playlistItems = LinkedHashMap<Long, MutableList<PlaylistItemRow>>()
    private val practiceStates = LinkedHashMap<Long, PracticeState>()
    private val bookmarks = LinkedHashMap<Long, PracticeBookmark>()
    private val lyricsCache = LinkedHashMap<Long, LyricsCacheRow>()
    private var appSettings = AppSettings()

    override fun readSnapshot(): DbSnapshot = synchronized(lock) {
        val bmGrouped = bookmarks.values
            .sortedWith(compareBy<PracticeBookmark> { it.aMs }.thenBy { it.bookmarkId })
            .groupBy { it.itemId }

        val sortedPlaylists = playlists.values.sortedWith(
            compareByDescending<PlaylistRow> { it.isPracticePlaylist }
                .thenByDescending { it.updatedAt }
                .thenByDescending { it.playlistId },
        )
        val allPlItems = playlistItems.values.flatten()
            .sortedWith(compareBy<PlaylistItemRow> { it.playlistId }.thenBy { it.sortOrder })

        DbSnapshot(
            safRoots = roots.values.sortedBy { it.rootId },
            folderRules = folderRules.values.toList(),
            scannedFolders = scannedFolders.values.toList(),
            mediaItems = items.values.sortedBy { it.itemId },
            overridesByItemId = HashMap(overrides),
            artistsByItemId = itemArtists.mapValues { it.value.toList() },
            playlists = sortedPlaylists,
            playlistItems = allPlItems,
            practiceStatesByItemId = HashMap(practiceStates),
            bookmarksByItemId = bmGrouped,
            settings = appSettings,
        )
    }

    override fun getMediaItemRow(itemId: Long): MediaItemRow? = synchronized(lock) { items[itemId] }

    override fun getOverrideRow(itemId: Long): MetadataOverrideRow? = synchronized(lock) { overrides[itemId] }

    override fun getSafRootRow(rootId: Long): SafRootRow? = synchronized(lock) { roots[rootId] }

    override fun insertSafRoot(
        treeUri: String,
        treeDocId: String,
        displayName: String,
        isWritable: Boolean,
        mountState: MountState,
    ): Long = synchronized(lock) {
        val existing = roots.values.firstOrNull { it.treeUri == treeUri }
        if (existing != null) return existing.rootId
        val id = rootSeq.getAndIncrement()
        roots[id] = SafRootRow(id, treeUri, treeDocId, displayName, isWritable, mountState, 0L)
        id
    }

    override fun mergeChildRootsIntoParent(
        treeUri: String,
        treeDocId: String,
        displayName: String,
        isWritable: Boolean,
        merges: List<ChildRootMergeSpec>,
    ): Long = synchronized(lock) {
        val newRootId = rootSeq.getAndIncrement()
        roots[newRootId] = SafRootRow(newRootId, treeUri, treeDocId, displayName, isWritable, MountState.MOUNTED, 0L)

        for (spec in merges) {
            val childItems = items.values.filter { it.rootId == spec.oldRootId }
            for (item in childItems) {
                val reboundUri = spec.rebindDocumentUri(item.documentUri)
                val reboundCover = item.folderCoverUri?.let { spec.rebindDocumentUri(it) }
                items[item.itemId] = item.copy(
                    documentUri = reboundUri,
                    rootId = newRootId,
                    parentRelPath = joinRelSegments(spec.relPathPrefix, item.parentRelPath),
                    folderCoverUri = reboundCover,
                )
            }

            val childRules = folderRules.values.filter { it.rootId == spec.oldRootId }
            for (rule in childRules) {
                folderRules.remove(rule.folderUri)
                val newUri = spec.rebindDocumentUri(rule.folderUri)
                folderRules[newUri] = rule.copy(
                    folderUri = newUri,
                    rootId = newRootId,
                    relPath = joinRelSegments(spec.relPathPrefix, rule.relPath),
                )
            }

            val childScanned = scannedFolders.values.filter { it.rootId == spec.oldRootId }
            for (sf in childScanned) {
                scannedFolders.remove(sf.rootId to sf.relPath)
                val newRel = joinRelSegments(spec.relPathPrefix, sf.relPath)
                scannedFolders[newRootId to newRel] = sf.copy(
                    rootId = newRootId,
                    relPath = newRel,
                    folderUri = spec.rebindDocumentUri(sf.folderUri),
                )
            }

            roots.remove(spec.oldRootId)
        }
        newRootId
    }

    override fun updateSafRootStatus(
        rootId: Long,
        mountState: MountState,
        isWritable: Boolean?,
        lastScanAt: Long?,
    ) = synchronized(lock) {
        val cur = roots[rootId] ?: return@synchronized
        roots[rootId] = cur.copy(
            mountState = mountState,
            isWritable = isWritable ?: cur.isWritable,
            lastScanAt = lastScanAt ?: cur.lastScanAt,
        )
    }

    override fun deleteSafRoot(rootId: Long) = synchronized(lock) {
        roots.remove(rootId)
        folderRules.entries.removeIf { it.value.rootId == rootId }
        scannedFolders.keys.removeIf { it.first == rootId }
        val toCascade = items.values.filter { it.rootId == rootId }.map { it.itemId }
        for (itemId in toCascade) {
            cascadeDeleteItemLocked(itemId)
        }
    }

    override fun upsertFolderRule(rule: FolderRuleRow) = synchronized(lock) {
        folderRules.entries.removeIf { it.value.rootId == rule.rootId && it.value.relPath == rule.relPath }
        folderRules[rule.folderUri] = rule
    }

    override fun reconcileRootScan(
        rootId: Long,
        isWritable: Boolean,
        scannedAtMs: Long,
        scannedFolders: List<ScannedFolderRow>,
        unchangedCoverUpdates: Map<Long, String?>,
        movedItems: List<MovedMediaUpdate>,
        upsertedItems: List<MediaItemRow>,
        deletedItemIds: Set<Long>,
        lyricsUpdates: List<Pair<String, LyricsCacheRow>>,
        artistSplitter: (effectiveArtist: String, effectiveAlbumArtist: String?) -> List<SplitArtist>,
    ) = synchronized(lock) {
        roots[rootId]?.let {
            roots[rootId] = it.copy(mountState = MountState.MOUNTED, isWritable = isWritable, lastScanAt = scannedAtMs)
        }

        this.scannedFolders.keys.removeIf { it.first == rootId }
        for (sf in scannedFolders) {
            this.scannedFolders[sf.rootId to sf.relPath] = sf
        }

        for (delId in deletedItemIds) {
            if (items[delId]?.rootId == rootId) {
                cascadeDeleteItemLocked(delId)
            }
        }

        for ((itemId, coverUri) in unchangedCoverUpdates) {
            items[itemId]?.let { items[itemId] = it.copy(folderCoverUri = coverUri) }
        }

        val uriToItemId = HashMap<String, Long>()
        for (mv in movedItems) {
            val cur = items[mv.existingItemId] ?: continue
            val updated = cur.copy(
                documentUri = mv.newDocumentUri,
                rootId = mv.newRootId,
                parentRelPath = mv.newParentRelPath,
                fileName = mv.newFileName,
                sizeBytes = mv.newSizeBytes,
                lastModified = mv.newLastModified,
                folderCoverUri = mv.newFolderCoverUri,
                fingerprintKey = mv.newFingerprintKey,
                parsedTitle = mv.parsedTitle,
                parsedArtist = mv.parsedArtist,
                parsedAlbum = mv.parsedAlbum,
                isBackingTrack = mv.isBackingTrack,
            )
            items[mv.existingItemId] = updated
            uriToItemId[mv.newDocumentUri] = mv.existingItemId
            rebuildArtistsLocked(mv.existingItemId, artistSplitter)
        }

        for (row in upsertedItems) {
            val existing = items.values.firstOrNull { it.documentUri == row.documentUri }
            val finalId = if (existing != null) {
                items[existing.itemId] = row.copy(
                    itemId = existing.itemId,
                    lastPositionMs = existing.lastPositionMs,
                    lastPlayedAt = existing.lastPlayedAt,
                )
                existing.itemId
            } else {
                val id = itemSeq.getAndIncrement()
                items[id] = row.copy(itemId = id)
                id
            }
            uriToItemId[row.documentUri] = finalId
            rebuildArtistsLocked(finalId, artistSplitter)
        }

        for ((docUri, lrcRow) in lyricsUpdates) {
            val itemId = uriToItemId[docUri] ?: items.values.firstOrNull { it.documentUri == docUri }?.itemId ?: continue
            if (lrcRow.source == LyricsSource.EMBEDDED) {
                val existingSrc = lyricsCache[itemId]?.source
                if (existingSrc == LyricsSource.LOCAL_LRC || existingSrc == LyricsSource.LRCLIB) continue
            }
            lyricsCache[itemId] = lrcRow.copy(itemId = itemId)
        }
    }

    override fun upsertMetadataOverrides(
        entries: List<MetadataOverrideRow>,
        artistSplitter: (effectiveArtist: String, effectiveAlbumArtist: String?) -> List<SplitArtist>,
    ) = synchronized(lock) {
        for (ov in entries) {
            if (ov.itemId !in items) continue
            overrides[ov.itemId] = ov
            rebuildArtistsLocked(ov.itemId, artistSplitter)
        }
    }

    override fun deleteMetadataOverrides(
        itemIds: List<Long>,
        artistSplitter: (effectiveArtist: String, effectiveAlbumArtist: String?) -> List<SplitArtist>,
    ) = synchronized(lock) {
        for (id in itemIds) {
            overrides.remove(id)
            rebuildArtistsLocked(id, artistSplitter)
        }
    }

    override fun markOverrideSyncedAndUpdateStat(
        itemId: Long,
        sizeBytes: Long,
        lastModified: Long,
        fingerprintKey: String,
    ) = synchronized(lock) {
        overrides[itemId]?.let { overrides[itemId] = it.copy(syncedToFile = true) }
        items[itemId]?.let {
            items[itemId] = it.copy(sizeBytes = sizeBytes, lastModified = lastModified, fingerprintKey = fingerprintKey)
        }
        Unit
    }

    override fun getUnsyncedOverrides(excludeItemId: Long?): List<Pair<MediaItemRow, MetadataOverrideRow>> = synchronized(lock) {
        overrides.values
            .filter { !it.syncedToFile && (excludeItemId == null || it.itemId != excludeItemId) }
            .mapNotNull { ov -> items[ov.itemId]?.let { it to ov } }
    }

    override fun createPlaylist(name: String, isPracticePlaylist: Boolean, nowMs: Long): Long = synchronized(lock) {
        val id = playlistSeq.getAndIncrement()
        playlists[id] = PlaylistRow(id, name, isPracticePlaylist, nowMs, nowMs)
        id
    }

    override fun renamePlaylist(playlistId: Long, newName: String, nowMs: Long) = synchronized(lock) {
        playlists[playlistId]?.let { playlists[playlistId] = it.copy(name = newName, updatedAt = nowMs) }
        Unit
    }

    override fun setPlaylistPracticeMode(playlistId: Long, isPracticePlaylist: Boolean, nowMs: Long) = synchronized(lock) {
        playlists[playlistId]?.let { playlists[playlistId] = it.copy(isPracticePlaylist = isPracticePlaylist, updatedAt = nowMs) }
        Unit
    }

    override fun deletePlaylist(playlistId: Long) = synchronized(lock) {
        playlists.remove(playlistId)
        playlistItems.remove(playlistId)
        Unit
    }

    override fun addItemsToPlaylist(playlistId: Long, itemIds: List<Long>, nowMs: Long) = synchronized(lock) {
        if (playlistId !in playlists) return@synchronized
        val list = playlistItems.getOrPut(playlistId) { ArrayList() }
        var maxOrder = list.maxOfOrNull { it.sortOrder } ?: -1
        for (itemId in itemIds) {
            if (itemId !in items) continue
            if (list.any { it.itemId == itemId }) continue
            list.add(PlaylistItemRow(playlistId, itemId, ++maxOrder, nowMs))
        }
        playlists[playlistId]?.let { playlists[playlistId] = it.copy(updatedAt = nowMs) }
        Unit
    }

    override fun removeItemFromPlaylist(playlistId: Long, itemId: Long, nowMs: Long) = synchronized(lock) {
        playlistItems[playlistId]?.removeIf { it.itemId == itemId }
        playlists[playlistId]?.let { playlists[playlistId] = it.copy(updatedAt = nowMs) }
        Unit
    }

    override fun reorderPlaylistItems(playlistId: Long, orderedItemIds: List<Long>, nowMs: Long) = synchronized(lock) {
        val list = playlistItems[playlistId] ?: return@synchronized
        val byId = list.associateBy { it.itemId }
        val reordered = ArrayList<PlaylistItemRow>(list.size)
        var ord = 0
        for (id in orderedItemIds) {
            byId[id]?.let { reordered.add(it.copy(sortOrder = ord++)) }
        }
        for (rem in list) {
            if (rem.itemId !in orderedItemIds) {
                reordered.add(rem.copy(sortOrder = ord++))
            }
        }
        playlistItems[playlistId] = reordered
        playlists[playlistId]?.let { playlists[playlistId] = it.copy(updatedAt = nowMs) }
        Unit
    }

    override fun getPracticeState(itemId: Long): PracticeState = synchronized(lock) {
        practiceStates[itemId] ?: PracticeState(itemId = itemId)
    }

    override fun upsertPracticeState(state: PracticeState, nowMs: Long) = synchronized(lock) {
        if (state.itemId in items) {
            practiceStates[state.itemId] = state
        }
    }

    override fun getBookmarks(itemId: Long): List<PracticeBookmark> = synchronized(lock) {
        bookmarks.values
            .filter { it.itemId == itemId }
            .sortedWith(compareBy<PracticeBookmark> { it.aMs }.thenBy { it.bookmarkId })
    }

    override fun upsertBookmark(
        itemId: Long,
        label: String,
        aMs: Long,
        bMs: Long,
        savedSpeed: Float?,
        bookmarkId: Long?,
        nowMs: Long,
    ): PracticeBookmark = synchronized(lock) {
        val id = if (bookmarkId != null && bookmarkId > 0L && bookmarkId in bookmarks) {
            bookmarkId
        } else {
            bookmarkSeq.getAndIncrement()
        }
        val created = bookmarks[id]?.createdAt ?: nowMs
        val bm = PracticeBookmark(id, itemId, label, aMs, bMs, savedSpeed, created)
        bookmarks[id] = bm
        bm
    }

    override fun deleteBookmark(bookmarkId: Long) = synchronized(lock) {
        bookmarks.remove(bookmarkId)
        Unit
    }

    override fun updatePlaybackPosition(itemId: Long, positionMs: Long, nowMs: Long) = synchronized(lock) {
        items[itemId]?.let {
            items[itemId] = it.copy(lastPositionMs = positionMs.coerceAtLeast(0L), lastPlayedAt = nowMs)
        }
        Unit
    }

    override fun getLyricsCache(itemId: Long): LyricsCacheRow? = synchronized(lock) { lyricsCache[itemId] }

    override fun upsertLyricsCache(row: LyricsCacheRow) = synchronized(lock) {
        if (row.itemId in items) {
            lyricsCache[row.itemId] = row
        }
    }

    override fun saveSettings(settings: AppSettings) = synchronized(lock) {
        appSettings = settings
    }

    override fun clearEmbeddedCoverRefs() = synchronized(lock) {
        for (id in items.keys.toList()) {
            items[id]?.let { items[id] = it.copy(embeddedCoverPath = null) }
        }
    }

    private fun cascadeDeleteItemLocked(itemId: Long) {
        items.remove(itemId)
        overrides.remove(itemId)
        itemArtists.remove(itemId)
        practiceStates.remove(itemId)
        lyricsCache.remove(itemId)
        bookmarks.entries.removeIf { it.value.itemId == itemId }
        for (list in playlistItems.values) {
            list.removeIf { it.itemId == itemId }
        }
    }

    private fun rebuildArtistsLocked(
        itemId: Long,
        artistSplitter: (effectiveArtist: String, effectiveAlbumArtist: String?) -> List<SplitArtist>,
    ) {
        val item = items[itemId] ?: return
        if (item.isVideo) {
            itemArtists.remove(itemId)
            return
        }
        val ov = overrides[itemId]
        val (effArtist, effAlbumArtist) = computeEffectiveArtistAndAlbumArtist(item, ov)
        val split = artistSplitter(effArtist, effAlbumArtist)
        itemArtists[itemId] = split.mapIndexed { idx, s ->
            MediaItemArtistRow(itemId, s.norm, s.display, idx)
        }
    }
}
