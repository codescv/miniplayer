package com.miniplayer.app.core.metadata

import android.content.Context
import android.net.Uri
import com.miniplayer.app.core.MetadataEngineContract
import com.miniplayer.app.core.db.LyricsCacheRow
import com.miniplayer.app.core.db.MediaItemRow
import com.miniplayer.app.core.db.MediaRepository
import com.miniplayer.app.core.db.MetadataOverrideRow
import com.miniplayer.app.core.model.*
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun interface PhysicalTagWriter {
    fun writeTags(
        item: MediaItemRow,
        spec: TagWriteSpec,
    ): Pair<Long, Long>
}

/**
 * Dual-Layer Metadata Engine (`DESIGN.md` §6.3, §6.4 & `ARCHITECTURE.md` §4.1, §4.4, §4.5):
 * - Layer 1: Instant `<5ms` SQLite `media_metadata_overrides` + local cover cache + reactive Artist/Album regrouping.
 * - Layer 2: Optional crash-safe temp-staged SAF binary write-back (`AudioTagCodec`), with automatic
 *   deferral (`SAVED_DB_DEFERRED_FILE`) if the target track is currently playing.
 */
class DefaultMetadataEngine(
    private val context: Context?,
    private val repository: MediaRepository,
    private val onlineCompleter: OnlineMetadataCompleter = OnlineMetadataCompleter(),
    private val lrclibClient: LrclibClient = LrclibClient(UrlConnectionOnlineHttpClient()),
    private val coversDir: File? = context?.filesDir?.let { File(it, "covers").apply { mkdirs() } },
    private val physicalTagWriter: PhysicalTagWriter? = if (context != null) {
        PhysicalTagWriter { item, spec ->
            AudioTagCodec.writeTagsToSafUri(
                context = context.applicationContext,
                docUri = Uri.parse(item.documentUri),
                itemId = item.itemId,
                fileName = item.fileName,
                mimeType = item.mimeType,
                spec = spec,
            )
        }
    } else {
        null
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MetadataEngineContract {

    private val backgroundScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    init {
        repository.onActiveItemChanged = { nowPlayingItemId ->
            backgroundScope.launch {
                flushDeferredFileWrites(currentlyPlayingItemId = nowPlayingItemId)
            }
        }
    }

    override suspend fun saveMetadata(
        itemIds: List<Long>,
        draft: MetadataDraft,
        currentlyPlayingItemId: Long?,
    ): MetadataSaveOutcome = withContext(ioDispatcher) {
        val distinctIds = itemIds.distinct()
        if (distinctIds.isEmpty()) return@withContext MetadataSaveOutcome.SAVED_DB_ONLY

        // Batch Edit Dirty-Field Safety (`DESIGN.md` §6.3): never overwrite per-track Title or Track# in multi-select
        val isBatch = distinctIds.size > 1
        val effectiveDirty: Set<MetadataField> = if (isBatch) {
            draft.dirtyFields - MetadataField.TITLE - MetadataField.TRACK_NUMBER
        } else {
            draft.dirtyFields
        }
        if (effectiveDirty.isEmpty()) return@withContext MetadataSaveOutcome.SAVED_DB_ONLY

        val now = System.currentTimeMillis()
        val overridesToSave = ArrayList<MetadataOverrideRow>(distinctIds.size)

        for (itemId in distinctIds) {
            val itemRow = repository.backend.getMediaItemRow(itemId) ?: continue
            val existingOv = repository.backend.getOverrideRow(itemId)

            val savedCoverPath = if (MetadataField.COVER in effectiveDirty) {
                if (draft.coverBytes != null && draft.coverBytes.isNotEmpty()) {
                    persistOverrideCover(itemId, draft.coverBytes, draft.coverMimeType) ?: existingOv?.coverLocalPath
                } else {
                    deleteOverrideCoverFiles(itemId)
                    MetadataOverrideRow.CLEARED_TEXT
                }
            } else {
                existingOv?.coverLocalPath
            }

            val merged = MetadataOverrideRow(
                itemId = itemId,
                title = if (MetadataField.TITLE in effectiveDirty) {
                    draft.title?.trim()?.takeIf { it.isNotEmpty() } ?: MetadataOverrideRow.CLEARED_TEXT
                } else {
                    existingOv?.title
                },
                artist = if (MetadataField.ARTIST in effectiveDirty) {
                    draft.artist?.trim()?.takeIf { it.isNotEmpty() } ?: MetadataOverrideRow.CLEARED_TEXT
                } else {
                    existingOv?.artist
                },
                album = if (MetadataField.ALBUM in effectiveDirty) {
                    draft.album?.trim()?.takeIf { it.isNotEmpty() } ?: MetadataOverrideRow.CLEARED_TEXT
                } else {
                    existingOv?.album
                },
                albumArtist = if (MetadataField.ALBUM_ARTIST in effectiveDirty) {
                    draft.albumArtist?.trim()?.takeIf { it.isNotEmpty() } ?: MetadataOverrideRow.CLEARED_TEXT
                } else {
                    existingOv?.albumArtist
                },
                trackNum = if (MetadataField.TRACK_NUMBER in effectiveDirty) {
                    draft.trackNumber?.takeIf { it > 0 } ?: MetadataOverrideRow.CLEARED_INT
                } else {
                    existingOv?.trackNum
                },
                year = if (MetadataField.YEAR in effectiveDirty) {
                    draft.year?.takeIf { it > 0 } ?: MetadataOverrideRow.CLEARED_INT
                } else {
                    existingOv?.year
                },
                coverLocalPath = savedCoverPath,
                updatedAt = now,
                syncedToFile = !draft.writeBackToFile,
            )
            overridesToSave.add(merged)
        }

        // Layer 1: Commit DB overrides & rebuild multi-artist index immediately
        repository.backend.upsertMetadataOverrides(overridesToSave) { effArtist, effAlbumArtist ->
            ArtistSplitter.split(effArtist, effAlbumArtist)
        }
        repository.refreshNow()

        if (!draft.writeBackToFile) {
            return@withContext MetadataSaveOutcome.SAVED_DB_ONLY
        }

        // Layer 2: Crash-Safe Temp-Staged SAF Physical Write-Back
        val tagSpec = buildTagWriteSpec(draft, effectiveDirty)
        var anyDeferred = false
        var anyReadOnlyOrFailed = false
        var anyWritten = false

        for (ov in overridesToSave) {
            val itemRow = repository.backend.getMediaItemRow(ov.itemId) ?: continue
            val rootRow = repository.backend.getSafRootRow(itemRow.rootId)
            val writableContainer = rootRow?.isWritable == true &&
                AudioTagCodec.supportsWrite(itemRow.fileName, itemRow.mimeType) &&
                physicalTagWriter != null

            if (!writableContainer) {
                anyReadOnlyOrFailed = true
                // Mark synced_to_file = true so we don't endlessly retry unsupported/readonly containers
                repository.backend.markOverrideSyncedAndUpdateStat(
                    itemId = itemRow.itemId,
                    sizeBytes = itemRow.sizeBytes,
                    lastModified = itemRow.lastModified,
                    fingerprintKey = itemRow.fingerprintKey,
                )
                continue
            }

            if (currentlyPlayingItemId != null && itemRow.itemId == currentlyPlayingItemId) {
                // Leave syncedToFile = false; will flush silently when track changes!
                anyDeferred = true
                continue
            }

            val writeOk = runCatching {
                val (newSize, newMod) = physicalTagWriter!!.writeTags(itemRow, tagSpec)
                val newFp = MediaRepository.buildFingerprintKey(itemRow.fileName, newSize, itemRow.durationMs)
                repository.backend.markOverrideSyncedAndUpdateStat(itemRow.itemId, newSize, newMod, newFp)
            }.isSuccess

            if (writeOk) {
                anyWritten = true
            } else {
                anyReadOnlyOrFailed = true
            }
        }

        repository.refreshNow()
        when {
            anyDeferred -> MetadataSaveOutcome.SAVED_DB_DEFERRED_FILE
            anyReadOnlyOrFailed && !anyWritten -> MetadataSaveOutcome.SAVED_DB_READONLY_FALLBACK
            anyWritten -> MetadataSaveOutcome.SAVED_DB_AND_FILE
            else -> MetadataSaveOutcome.SAVED_DB_READONLY_FALLBACK
        }
    }

    suspend fun flushDeferredFileWrites(currentlyPlayingItemId: Long? = null): Int = withContext(ioDispatcher) {
        val writer = physicalTagWriter ?: return@withContext 0
        val pending = repository.backend.getUnsyncedOverrides(excludeItemId = currentlyPlayingItemId)
        if (pending.isEmpty()) return@withContext 0

        var flushed = 0
        for ((itemRow, ov) in pending) {
            val rootRow = repository.backend.getSafRootRow(itemRow.rootId)
            if (rootRow?.isWritable != true || !AudioTagCodec.supportsWrite(itemRow.fileName, itemRow.mimeType)) {
                repository.backend.markOverrideSyncedAndUpdateStat(
                    itemRow.itemId,
                    itemRow.sizeBytes,
                    itemRow.lastModified,
                    itemRow.fingerprintKey,
                )
                continue
            }
            val coverField: OptionalField<CoverImage?> = when (val covPath = ov.coverLocalPath) {
                null -> OptionalField.Unchanged
                MetadataOverrideRow.CLEARED_TEXT -> OptionalField.Set(null)
                else -> {
                    val f = File(covPath)
                    if (f.isFile) {
                        val bytes = f.readBytes()
                        OptionalField.Set(CoverImage(bytes, CoverImage.detectImageMimeType(bytes)))
                    } else {
                        OptionalField.Unchanged
                    }
                }
            }
            val spec = TagWriteSpec(
                title = when (val v = ov.title) {
                    null -> OptionalField.Unchanged
                    MetadataOverrideRow.CLEARED_TEXT -> OptionalField.Set(null)
                    else -> OptionalField.Set(v)
                },
                artist = when (val v = ov.artist) {
                    null -> OptionalField.Unchanged
                    MetadataOverrideRow.CLEARED_TEXT -> OptionalField.Set(null)
                    else -> OptionalField.Set(v)
                },
                album = when (val v = ov.album) {
                    null -> OptionalField.Unchanged
                    MetadataOverrideRow.CLEARED_TEXT -> OptionalField.Set(null)
                    else -> OptionalField.Set(v)
                },
                albumArtist = when (val v = ov.albumArtist) {
                    null -> OptionalField.Unchanged
                    MetadataOverrideRow.CLEARED_TEXT -> OptionalField.Set(null)
                    else -> OptionalField.Set(v)
                },
                trackNumber = when (val v = ov.trackNum) {
                    null -> OptionalField.Unchanged
                    else -> OptionalField.Set(v.takeIf { it > 0 })
                },
                year = when (val v = ov.year) {
                    null -> OptionalField.Unchanged
                    else -> OptionalField.Set(v.takeIf { it > 0 })
                },
                cover = coverField,
            )
            val ok = runCatching {
                val (newSize, newMod) = writer.writeTags(itemRow, spec)
                val newFp = MediaRepository.buildFingerprintKey(itemRow.fileName, newSize, itemRow.durationMs)
                repository.backend.markOverrideSyncedAndUpdateStat(itemRow.itemId, newSize, newMod, newFp)
            }.isSuccess
            if (ok) flushed++
        }
        if (flushed > 0) repository.refreshNow()
        flushed
    }

    override suspend fun resetMetadataOverride(itemIds: List<Long>) = withContext(ioDispatcher) {
        if (itemIds.isEmpty()) return@withContext
        for (id in itemIds) {
            deleteOverrideCoverFiles(id)
        }
        repository.backend.deleteMetadataOverrides(itemIds) { effArtist, effAlbumArtist ->
            ArtistSplitter.split(effArtist, effAlbumArtist)
        }
        repository.refreshNow()
    }

    override suspend fun searchOnlineCandidates(
        item: MediaItem,
        queryTitleOverride: String?,
        queryArtistOverride: String?,
    ): List<MetadataCandidate> = withContext(ioDispatcher) {
        onlineCompleter.searchCandidates(item, queryTitleOverride, queryArtistOverride)
    }

    override suspend fun applyCandidate(
        itemId: Long,
        candidate: MetadataCandidate,
        preserveBackingSuffix: Boolean,
        writeBackToFile: Boolean,
        currentlyPlayingItemId: Long?,
    ): MetadataSaveOutcome = withContext(ioDispatcher) {
        val itemRow = repository.backend.getMediaItemRow(itemId)
            ?: return@withContext MetadataSaveOutcome.SAVED_DB_ONLY

        val resolvedTitle = if (preserveBackingSuffix) {
            SmartFilenameParser.applyBackingSuffix(candidate.title, itemRow.fileName)
        } else {
            candidate.title.trim()
        }

        val downloadedCover = onlineCompleter.downloadCoverBytes(candidate.coverUrl)

        if (!candidate.syncedLrc.isNullOrBlank() || !candidate.plainLyrics.isNullOrBlank()) {
            repository.backend.upsertLyricsCache(
                LyricsCacheRow(
                    itemId = itemId,
                    syncedLrc = candidate.syncedLrc,
                    plainLyrics = candidate.plainLyrics,
                    source = LyricsSource.LRCLIB,
                    sourceModifiedAt = System.currentTimeMillis(),
                ),
            )
        }

        val dirty = LinkedHashSet<MetadataField>().apply {
            add(MetadataField.TITLE)
            add(MetadataField.ARTIST)
            if (!candidate.album.isNullOrBlank()) add(MetadataField.ALBUM)
            if (!candidate.albumArtist.isNullOrBlank()) add(MetadataField.ALBUM_ARTIST)
            if (candidate.trackNumber != null && candidate.trackNumber > 0) add(MetadataField.TRACK_NUMBER)
            if (candidate.year != null && candidate.year > 0) add(MetadataField.YEAR)
            if (downloadedCover != null) add(MetadataField.COVER)
        }

        saveMetadata(
            itemIds = listOf(itemId),
            draft = MetadataDraft(
                title = resolvedTitle,
                artist = candidate.artist,
                album = candidate.album,
                albumArtist = candidate.albumArtist ?: candidate.artist,
                trackNumber = candidate.trackNumber,
                year = candidate.year,
                coverBytes = downloadedCover?.first,
                coverMimeType = downloadedCover?.second,
                dirtyFields = dirty,
                writeBackToFile = writeBackToFile,
            ),
            currentlyPlayingItemId = currentlyPlayingItemId,
        )
    }

    override suspend fun previewBatchCompletion(
        items: List<MediaItem>,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): List<BatchMatchItem> = withContext(ioDispatcher) {
        onlineCompleter.previewBatch(items, onProgress)
    }

    override suspend fun commitBatchCompletion(
        matches: List<BatchMatchItem>,
        writeBackToFile: Boolean,
        currentlyPlayingItemId: Long?,
    ): Int = withContext(ioDispatcher) {
        var committed = 0
        for (match in matches) {
            val idx = match.selectedCandidateIndex
            if (idx in match.candidates.indices) {
                applyCandidate(
                    itemId = match.mediaItem.itemId,
                    candidate = match.candidates[idx],
                    preserveBackingSuffix = match.preserveBackingSuffix,
                    writeBackToFile = writeBackToFile,
                    currentlyPlayingItemId = currentlyPlayingItemId,
                )
                committed++
            }
        }
        committed
    }

    override suspend fun getLyrics(
        item: MediaItem,
        fetchOnlineIfMissing: Boolean,
    ): LyricsData? = withContext(ioDispatcher) {
        val cached = repository.backend.getLyricsCache(item.itemId)
        if (cached != null) {
            val raw = cached.syncedLrc ?: cached.plainLyrics
            val parsed = LrcParser.parse(raw, cached.source)
            if (parsed != null) return@withContext parsed
        }

        if (!fetchOnlineIfMissing || item.isVideo) return@withContext null

        val (cleanTitle, _) = SmartFilenameParser.stripNoiseAndBackingForSearch(item.title)
        val fetched = lrclibClient.fetchLyrics(
            title = cleanTitle.ifBlank { item.title },
            artist = item.artist,
            album = item.album,
            durationMs = item.durationMs,
            isBackingTrack = item.isBackingTrack,
        ) ?: return@withContext null

        repository.backend.upsertLyricsCache(
            LyricsCacheRow(
                itemId = item.itemId,
                syncedLrc = fetched.syncedLrc,
                plainLyrics = fetched.plainLyrics,
                source = LyricsSource.LRCLIB,
                sourceModifiedAt = System.currentTimeMillis(),
            ),
        )
        LrcParser.parse(fetched.syncedLrc ?: fetched.plainLyrics, LyricsSource.LRCLIB)
    }

    override suspend fun exportPlaylistM3u8(playlistId: Long, outputUri: Uri): Boolean = withContext(ioDispatcher) {
        val m3u8Text = exportPlaylistM3u8Text(playlistId) ?: return@withContext false
        val ctx = context ?: return@withContext false
        runCatching {
            ctx.contentResolver.openOutputStream(outputUri, "wt")?.use { out ->
                out.write(m3u8Text.toByteArray(Charsets.UTF_8))
                out.flush()
            } != null
        }.getOrDefault(false)
    }

    suspend fun exportPlaylistM3u8Text(playlistId: Long): String? = withContext(ioDispatcher) {
        val detail = repository.getPlaylistDetail(playlistId) ?: return@withContext null
        val rootsById = repository.safRoots.value.associateBy { it.rootId }
        M3uPlaylistCodec.exportToM3u8(
            playlistName = detail.summary.name,
            isPracticePlaylist = detail.summary.isPracticePlaylist,
            items = detail.items,
            rootsById = rootsById,
        )
    }

    override suspend fun importPlaylistM3u8(inputUri: Uri, fallbackName: String): M3uImportResult = withContext(ioDispatcher) {
        val ctx = context ?: return@withContext M3uImportResult(0L, fallbackName, 0, 0, false)
        val bytes = runCatching {
            ctx.contentResolver.openInputStream(inputUri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext M3uImportResult(0L, fallbackName, 0, 0, false)

        val decoded = LrcParser.decodeBytes(bytes)
        importPlaylistM3u8Text(decoded, fallbackName)
    }

    suspend fun importPlaylistM3u8Text(rawContent: String, fallbackName: String = "导入列表"): M3uImportResult =
        withContext(ioDispatcher) {
            val allItems = repository.allMediaItems.value
            val rootsById = repository.safRoots.value.associateBy { it.rootId }
            val resolved = M3uPlaylistCodec.resolvePlaylist(
                rawContent = rawContent,
                fallbackName = fallbackName,
                allItems = allItems,
                rootsById = rootsById,
            )
            val playlistId = repository.createPlaylist(
                name = resolved.playlistName,
                isPracticePlaylist = resolved.isPracticePlaylist,
            )
            if (resolved.matchedItems.isNotEmpty()) {
                repository.addItemsToPlaylist(
                    playlistId = playlistId,
                    itemIds = resolved.matchedItems.map { it.itemId },
                )
            }
            M3uImportResult(
                playlistId = playlistId,
                playlistName = resolved.playlistName,
                matchedCount = resolved.matchedItems.size,
                unmatchedCount = resolved.unmatchedCount,
                isPracticePlaylist = resolved.isPracticePlaylist,
            )
        }

    private fun buildTagWriteSpec(draft: MetadataDraft, dirty: Set<MetadataField>): TagWriteSpec {
        val coverOpt = if (MetadataField.COVER in dirty) {
            val img = draft.coverBytes?.takeIf { it.isNotEmpty() }?.let {
                CoverImage(it, draft.coverMimeType ?: CoverImage.detectImageMimeType(it))
            }
            OptionalField.Set(img)
        } else {
            OptionalField.Unchanged
        }
        return TagWriteSpec(
            title = if (MetadataField.TITLE in dirty) OptionalField.Set(draft.title?.trim()?.takeIf { it.isNotEmpty() }) else OptionalField.Unchanged,
            artist = if (MetadataField.ARTIST in dirty) OptionalField.Set(draft.artist?.trim()?.takeIf { it.isNotEmpty() }) else OptionalField.Unchanged,
            album = if (MetadataField.ALBUM in dirty) OptionalField.Set(draft.album?.trim()?.takeIf { it.isNotEmpty() }) else OptionalField.Unchanged,
            albumArtist = if (MetadataField.ALBUM_ARTIST in dirty) OptionalField.Set(draft.albumArtist?.trim()?.takeIf { it.isNotEmpty() }) else OptionalField.Unchanged,
            trackNumber = if (MetadataField.TRACK_NUMBER in dirty) OptionalField.Set(draft.trackNumber?.takeIf { it > 0 }) else OptionalField.Unchanged,
            year = if (MetadataField.YEAR in dirty) OptionalField.Set(draft.year?.takeIf { it > 0 }) else OptionalField.Unchanged,
            cover = coverOpt,
        )
    }

    private fun persistOverrideCover(itemId: Long, bytes: ByteArray, mimeType: String?): String? {
        val dir = coversDir ?: return null
        dir.mkdirs()
        deleteOverrideCoverFiles(itemId)
        val ext = if ((mimeType ?: CoverImage.detectImageMimeType(bytes)).contains("png", ignoreCase = true)) "png" else "jpg"
        val out = File(dir, "override_${itemId}.$ext")
        return runCatching {
            FileOutputStream(out).use {
                it.write(bytes)
                it.flush()
            }
            out.absolutePath
        }.getOrNull()
    }

    private fun deleteOverrideCoverFiles(itemId: Long) {
        val dir = coversDir ?: return
        File(dir, "override_${itemId}.jpg").delete()
        File(dir, "override_${itemId}.png").delete()
    }
}
