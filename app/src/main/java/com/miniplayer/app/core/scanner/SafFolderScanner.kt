package com.miniplayer.app.core.scanner

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.miniplayer.app.core.FolderScannerContract
import com.miniplayer.app.core.db.ChildRootMergeSpec
import com.miniplayer.app.core.db.FolderRuleRow
import com.miniplayer.app.core.db.LyricsCacheRow
import com.miniplayer.app.core.db.MediaItemRow
import com.miniplayer.app.core.db.MediaRepository
import com.miniplayer.app.core.db.MovedMediaUpdate
import com.miniplayer.app.core.db.SafRootRow
import com.miniplayer.app.core.db.ScannedFolderRow
import com.miniplayer.app.core.db.joinRelSegments
import com.miniplayer.app.core.metadata.ArtistSplitter
import com.miniplayer.app.core.metadata.AudioTagCodec
import com.miniplayer.app.core.metadata.LrcParser
import com.miniplayer.app.core.metadata.SmartFilenameParser
import com.miniplayer.app.core.model.AddRootResult
import com.miniplayer.app.core.model.LyricsSource
import com.miniplayer.app.core.model.MountState
import com.miniplayer.app.core.model.SafRoot
import com.miniplayer.app.core.model.ScanProgress
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SafGrantInfo(
    val treeUri: String,
    val authority: String,
    val treeDocId: String,
    val displayName: String,
    val isWritable: Boolean,
)

data class SafChildEntry(
    val documentId: String,
    val documentUri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModified: Long,
) {
    val isDirectory: Boolean
        get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
}

data class ExtractedMediaProbe(
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val rawTitle: String? = null,
    val rawArtist: String? = null,
    val rawAlbum: String? = null,
    val rawAlbumArtist: String? = null,
    val rawTrackNum: Int? = null,
    val rawYear: Int? = null,
    val embeddedLyrics: String? = null,
    val cachedCoverPath: String? = null,
)

sealed class SafVolumeProbe {
    data class Mounted(
        val isWritable: Boolean,
        val rootDocUri: String,
        val liveDisplayName: String? = null,
    ) : SafVolumeProbe()
    data object OfflineUnmounted : SafVolumeProbe()
    data object PermissionRevoked : SafVolumeProbe()
}

interface SafTreeDataSource {
    fun takePersistableGrant(treeUri: Uri): SafGrantInfo
    fun releasePersistableGrant(treeUri: String)
    fun probeRootMountState(treeUri: String, treeDocId: String, fallbackWritable: Boolean): SafVolumeProbe
    /**
     * Lists direct children of [dirDocId] under [treeUri].
     * Throws [VolumeDisconnectedException] if the storage volume disappears mid-scan so the scanner
     * enters `OFFLINE_UNMOUNTED` without pruning existing library items or bookmarks!
     */
    fun listChildren(treeUri: String, dirDocId: String): List<SafChildEntry>
    fun buildDocumentUri(treeUri: String, docId: String): String
    fun rebindDocumentUriToNewTree(newTreeUri: String, oldDocumentUri: String): String
    fun readSmallFileBytes(documentUri: String, maxBytes: Int = 2 * 1024 * 1024): ByteArray?
    fun extractMediaMetadata(entry: SafChildEntry, isVideo: Boolean): ExtractedMediaProbe
}

class VolumeDisconnectedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Pure-JVM overlap detector for SAF `tree_doc_id` ancestor/descendant validation (`DESIGN.md` §6.1 #1).
 */
object SafOverlapDetector {

    data class DocLocation(
        val authority: String,
        val volumeId: String,
        val subPath: String,
    )

    sealed class OverlapDecision {
        data object Disjoint : OverlapDecision()
        data class AlreadyAdded(val existing: SafRoot) : OverlapDecision()
        data class CoveredByExistingParent(val parent: SafRoot, val childRelPath: String) : OverlapDecision()
        data class CoversExistingChildren(val children: List<Pair<SafRoot, String>>) : OverlapDecision()
    }

    fun parseLocation(authority: String, treeDocId: String): DocLocation {
        val cleanAuth = authority.trim().lowercase(Locale.ROOT)
        val raw = treeDocId.trim()
        val volume = raw.substringBefore(':', raw).trim().lowercase(Locale.ROOT)
        val sub = if (':' in raw) {
            raw.substringAfter(':').replace('\\', '/').trim('/').replace(Regex("/+"), "/")
        } else {
            ""
        }
        return DocLocation(cleanAuth, volume, sub)
    }

    fun extractAuthorityFromUriString(uriStr: String): String {
        val afterScheme = uriStr.substringAfter("://", "")
        return afterScheme.substringBefore('/').lowercase(Locale.ROOT)
    }

    fun evaluate(
        newAuthority: String,
        newTreeDocId: String,
        existingRoots: List<SafRoot>,
    ): OverlapDecision {
        val newLoc = parseLocation(newAuthority, newTreeDocId)
        val coveredChildren = ArrayList<Pair<SafRoot, String>>()

        for (existing in existingRoots) {
            val exAuth = extractAuthorityFromUriString(existing.treeUri)
            val exLoc = parseLocation(exAuth, existing.treeDocId)
            if (exLoc.authority != newLoc.authority || exLoc.volumeId != newLoc.volumeId) continue

            // Case 1: Exact same folder (case-insensitive on same volume) or new folder is inside existing parent root
            if (newLoc.subPath.equals(exLoc.subPath, ignoreCase = true)) {
                return OverlapDecision.AlreadyAdded(existing)
            }
            if (exLoc.subPath.isEmpty()) {
                return OverlapDecision.CoveredByExistingParent(existing, newLoc.subPath)
            }
            if (newLoc.subPath.startsWith("${exLoc.subPath}/", ignoreCase = true)) {
                val rel = newLoc.subPath.substring(exLoc.subPath.length + 1)
                return OverlapDecision.CoveredByExistingParent(existing, rel)
            }

            // Case 2: New folder is an ancestor of an existing child root
            if (newLoc.subPath.isEmpty() && exLoc.subPath.isNotEmpty()) {
                coveredChildren.add(existing to exLoc.subPath)
            } else if (newLoc.subPath.isNotEmpty() && exLoc.subPath.startsWith("${newLoc.subPath}/", ignoreCase = true)) {
                val prefix = exLoc.subPath.substring(newLoc.subPath.length + 1)
                coveredChildren.add(existing to prefix)
            }
        }

        return if (coveredChildren.isNotEmpty()) {
            OverlapDecision.CoversExistingChildren(coveredChildren)
        } else {
            OverlapDecision.Disjoint
        }
    }
}

/**
 * Production Android SAF `DocumentsContract` data source (strictly ZERO `MediaStore` / `DocumentFile` reflection).
 */
class AndroidSafTreeDataSource(
    private val context: Context,
) : SafTreeDataSource {

    companion object {
        private val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }

    private val appContext = context.applicationContext
    private val coversDir: File get() = File(appContext.filesDir, "covers").apply { mkdirs() }

    override fun takePersistableGrant(treeUri: Uri): SafGrantInfo {
        val resolver = appContext.contentResolver
        val rwFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val writable = try {
            resolver.takePersistableUriPermission(treeUri, rwFlags)
            true
        } catch (_: SecurityException) {
            resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            false
        }

        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val displayName = queryRootDisplayName(treeUri, docId) ?: deriveFallbackDisplayName(docId)
        return SafGrantInfo(
            treeUri = treeUri.toString(),
            authority = treeUri.authority.orEmpty(),
            treeDocId = docId,
            displayName = displayName,
            isWritable = writable,
        )
    }

    override fun releasePersistableGrant(treeUri: String) {
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                Uri.parse(treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    override fun probeRootMountState(
        treeUri: String,
        treeDocId: String,
        fallbackWritable: Boolean,
    ): SafVolumeProbe {
        val resolver = appContext.contentResolver
        val perm = runCatching {
            resolver.persistedUriPermissions.firstOrNull { it.uri.toString() == treeUri && it.isReadPermission }
        }.getOrNull() ?: return SafVolumeProbe.PermissionRevoked

        val parsedTree = Uri.parse(treeUri)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(parsedTree, treeDocId)

        // Verify both root document existence AND child cursor reachability, capturing live root display name
        return try {
            var liveDisplayName: String? = null
            val docExists = resolver.query(
                rootDocUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) {
                        liveDisplayName = c.getString(nameIdx)?.takeIf { it.isNotBlank() }
                    }
                    true
                } else {
                    false
                }
            } ?: false

            if (!docExists) {
                return SafVolumeProbe.OfflineUnmounted
            }

            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parsedTree, treeDocId)
            val childrenCursorOk = resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { true } ?: false

            if (!childrenCursorOk) {
                SafVolumeProbe.OfflineUnmounted
            } else {
                SafVolumeProbe.Mounted(
                    isWritable = perm.isWritePermission,
                    rootDocUri = rootDocUri.toString(),
                    liveDisplayName = liveDisplayName,
                )
            }
        } catch (_: SecurityException) {
            SafVolumeProbe.PermissionRevoked
        } catch (_: Exception) {
            SafVolumeProbe.OfflineUnmounted
        }
    }

    override fun listChildren(treeUri: String, dirDocId: String): List<SafChildEntry> {
        val parsedTree = Uri.parse(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parsedTree, dirDocId)
        val cursor = try {
            appContext.contentResolver.query(childrenUri, CHILD_PROJECTION, null, null, null)
        } catch (e: SecurityException) {
            throw VolumeDisconnectedException("SAF permission revoked during scan", e)
        } catch (e: Exception) {
            throw VolumeDisconnectedException("SAF volume unreachable during scan", e)
        } ?: throw VolumeDisconnectedException("SAF provider returned null cursor for $dirDocId")

        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            val out = ArrayList<SafChildEntry>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) {
                val docId = c.getString(idIdx) ?: continue
                val name = c.getString(nameIdx) ?: docId.substringAfterLast('/')
                val mime = c.getString(mimeIdx) ?: "application/octet-stream"
                val size = if (c.isNull(sizeIdx)) 0L else c.getLong(sizeIdx)
                val lastMod = if (c.isNull(modIdx)) 0L else c.getLong(modIdx)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(parsedTree, docId).toString()
                out.add(SafChildEntry(docId, docUri, name, mime, size, lastMod))
            }
            return out
        }
    }

    override fun buildDocumentUri(treeUri: String, docId: String): String =
        DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), docId).toString()

    override fun rebindDocumentUriToNewTree(newTreeUri: String, oldDocumentUri: String): String {
        return runCatching {
            val docId = DocumentsContract.getDocumentId(Uri.parse(oldDocumentUri))
            DocumentsContract.buildDocumentUriUsingTree(Uri.parse(newTreeUri), docId).toString()
        }.getOrDefault(oldDocumentUri)
    }

    override fun readSmallFileBytes(documentUri: String, maxBytes: Int): ByteArray? {
        return runCatching {
            appContext.contentResolver.openInputStream(Uri.parse(documentUri))?.use { input ->
                val baos = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (baos.size() + n > maxBytes) return null
                    baos.write(buf, 0, n)
                }
                baos.toByteArray()
            }
        }.getOrNull()
    }

    override fun extractMediaMetadata(entry: SafChildEntry, isVideo: Boolean): ExtractedMediaProbe {
        val uri = Uri.parse(entry.documentUri)
        if (!isVideo) {
            val binaryTags = runCatching {
                AudioTagCodec.readTagsFromSafUri(
                    context = appContext,
                    uri = uri,
                    fileNameHint = entry.displayName,
                    mimeTypeHint = entry.mimeType,
                )
            }.getOrNull()

            var durationMs = binaryTags?.durationMs ?: 0L
            var rawTitle = binaryTags?.title
            var rawArtist = binaryTags?.artist
            var rawAlbum = binaryTags?.album
            var rawAlbumArtist = binaryTags?.albumArtist
            var rawTrack = binaryTags?.trackNumber
            var rawYear = binaryTags?.year
            var coverBytes = binaryTags?.coverBytes

            // Fallback to MediaMetadataRetriever when duration or container tags need system extractor (e.g. WAV/AAC/WMA)
            if (durationMs <= 0L || (rawTitle == null && rawArtist == null && coverBytes == null)) {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(appContext, uri)
                    if (durationMs <= 0L) {
                        durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    }
                    if (rawTitle == null) rawTitle = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                    if (rawArtist == null) rawArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
                    if (rawAlbum == null) rawAlbum = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }
                    if (rawAlbumArtist == null) rawAlbumArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.takeIf { it.isNotBlank() }
                    if (rawTrack == null) {
                        rawTrack = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                            ?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                    }
                    if (rawYear == null) {
                        rawYear = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                            ?.take(4)?.toIntOrNull()
                    }
                    if (coverBytes == null) {
                        coverBytes = mmr.embeddedPicture
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { mmr.release() }
                }
            }

            val cachedCover = coverBytes?.takeIf { it.isNotEmpty() }?.let { saveCoverBytes("emb", entry.documentUri, it) }
            return ExtractedMediaProbe(
                durationMs = durationMs.coerceAtLeast(0L),
                rawTitle = rawTitle,
                rawArtist = rawArtist,
                rawAlbum = rawAlbum,
                rawAlbumArtist = rawAlbumArtist,
                rawTrackNum = rawTrack,
                rawYear = rawYear,
                embeddedLyrics = binaryTags?.lyrics,
                cachedCoverPath = cachedCover,
            )
        } else {
            val mmr = MediaMetadataRetriever()
            var durationMs = 0L
            var width = 0
            var height = 0
            var rawTitle: String? = null
            var rawArtist: String? = null
            var thumbPath: String? = null
            try {
                mmr.setDataSource(appContext, uri)
                durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val rawW = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val rawH = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rotation == 90 || rotation == 270) {
                    width = rawH
                    height = rawW
                } else {
                    width = rawW
                    height = rawH
                }
                rawTitle = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                rawArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }

                val frameTimeUs = if (durationMs > 2_000L) (durationMs * 200L).coerceAtMost(15_000_000L) else 0L
                val bmp = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                    mmr.getScaledFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 360)
                } else {
                    mmr.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                if (bmp != null) {
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 82, baos)
                    bmp.recycle()
                    thumbPath = saveCoverBytes("vid", entry.documentUri, baos.toByteArray())
                }
            } catch (_: Exception) {
            } finally {
                runCatching { mmr.release() }
            }
            return ExtractedMediaProbe(
                durationMs = durationMs.coerceAtLeast(0L),
                width = width,
                height = height,
                rawTitle = rawTitle,
                rawArtist = rawArtist,
                cachedCoverPath = thumbPath,
            )
        }
    }

    private fun saveCoverBytes(prefix: String, keyUri: String, bytes: ByteArray): String? {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-1").digest(keyUri.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(20)
            val out = File(coversDir, "${prefix}_$hex.jpg")
            FileOutputStream(out).use {
                it.write(bytes)
                it.flush()
            }
            out.absolutePath
        }.getOrNull()
    }

    private fun queryRootDisplayName(treeUri: Uri, docId: String): String? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return runCatching {
            appContext.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }

    private fun deriveFallbackDisplayName(docId: String): String {
        val sub = docId.substringAfter(':', "").trim('/')
        if (sub.isNotEmpty()) return sub.substringAfterLast('/')
        val vol = docId.substringBefore(':', "")
        return if (vol.equals("primary", ignoreCase = true)) "内置存储" else vol.ifBlank { "本地文件夹" }
    }
}

/**
 * Scoped SAF Folder Scanner (`DESIGN.md` §6.1, `ARCHITECTURE.md` §4.3).
 */
class SafFolderScanner(
    private val repository: MediaRepository,
    private val dataSource: SafTreeDataSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FolderScannerContract {

    companion object {
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "oga", "wma", "ape", "alac", "aiff", "aif",
        )
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "mov", "m4v", "avi", "3gp", "ts", "mts",
        )
        private val FOLDER_COVER_STEMS = listOf("cover", "folder", "front", "album")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        fun classifyMediaFile(displayName: String, mimeType: String): Pair<Boolean, Boolean> {
            // Returns (isMedia, isVideo)
            val ext = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val mime = mimeType.lowercase(Locale.ROOT)
            if (ext == "m4a" || ext in AUDIO_EXTENSIONS && ext != "mp4") {
                return true to false
            }
            if (ext in VIDEO_EXTENSIONS) {
                return if (mime.startsWith("audio/")) (true to false) else (true to true)
            }
            if (mime.startsWith("audio/")) return true to false
            if (mime.startsWith("video/")) return true to true
            return false to false
        }

        fun folderCoverRank(displayName: String): Int? {
            val dot = displayName.lastIndexOf('.')
            if (dot <= 0) return null
            val stem = displayName.substring(0, dot).lowercase(Locale.ROOT)
            val ext = displayName.substring(dot + 1).lowercase(Locale.ROOT)
            if (ext !in IMAGE_EXTENSIONS) return null
            val idx = FOLDER_COVER_STEMS.indexOf(stem)
            return if (idx >= 0) idx else null
        }
    }

    private val scanMutex = Mutex()
    private val _scanProgress = MutableStateFlow(ScanProgress())
    override val scanProgress: StateFlow<ScanProgress> = _scanProgress.asStateFlow()

    override suspend fun addSafRoot(treeUri: Uri): AddRootResult = withContext(ioDispatcher) {
        val grant = try {
            dataSource.takePersistableGrant(treeUri)
        } catch (e: Exception) {
            return@withContext AddRootResult.Error("无法获取目录持久化访问授权: ${e.message ?: "权限被拒绝"}")
        }

        addSafRootFromGrant(grant)
    }

    suspend fun addSafRootFromGrant(grant: SafGrantInfo): AddRootResult = withContext(ioDispatcher) {
        val existingRoots = repository.safRoots.value
        when (val decision = SafOverlapDetector.evaluate(grant.authority, grant.treeDocId, existingRoots)) {
            is SafOverlapDetector.OverlapDecision.AlreadyAdded -> {
                val existing = decision.existing
                val identityChanged = existing.treeUri != grant.treeUri ||
                    existing.treeDocId != grant.treeDocId ||
                    existing.displayName != grant.displayName ||
                    existing.isWritable != grant.isWritable
                if (identityChanged) {
                    repository.backend.updateSafRootIdentity(
                        rootId = existing.rootId,
                        treeUri = grant.treeUri,
                        treeDocId = grant.treeDocId,
                        displayName = grant.displayName,
                        isWritable = grant.isWritable,
                    )
                    if (existing.treeUri != grant.treeUri &&
                        existingRoots.none { it.rootId != existing.rootId && it.treeUri == existing.treeUri }
                    ) {
                        dataSource.releasePersistableGrant(existing.treeUri)
                    }
                    repository.refreshNow()
                } else if (existingRoots.none { it.treeUri == grant.treeUri }) {
                    dataSource.releasePersistableGrant(grant.treeUri)
                }
                rescanRoot(existing.rootId, incremental = false)
                val refreshed = repository.safRoots.value.firstOrNull { it.rootId == existing.rootId } ?: existing
                AddRootResult.AlreadyCoveredByParent(
                    parentRoot = refreshed,
                    childRelPath = "",
                )
            }

            is SafOverlapDetector.OverlapDecision.CoveredByExistingParent -> {
                if (existingRoots.none { it.treeUri == grant.treeUri }) {
                    dataSource.releasePersistableGrant(grant.treeUri)
                }
                rescanRoot(decision.parent.rootId, incremental = false)
                val refreshedParent = repository.safRoots.value.firstOrNull { it.rootId == decision.parent.rootId }
                    ?: decision.parent
                val canonicalRel = repository.browseFolder(refreshedParent.rootId, decision.childRelPath)?.currentRelPath
                    ?: decision.childRelPath
                AddRootResult.AlreadyCoveredByParent(
                    parentRoot = refreshedParent,
                    childRelPath = canonicalRel,
                )
            }

            is SafOverlapDetector.OverlapDecision.CoversExistingChildren -> {
                val mergeSpecs = decision.children.map { (childRoot, relPrefix) ->
                    ChildRootMergeSpec(
                        oldRootId = childRoot.rootId,
                        relPathPrefix = relPrefix,
                        rebindDocumentUri = { oldUri ->
                            dataSource.rebindDocumentUriToNewTree(grant.treeUri, oldUri)
                        },
                    )
                }
                val newRootId = repository.backend.mergeChildRootsIntoParent(
                    treeUri = grant.treeUri,
                    treeDocId = grant.treeDocId,
                    displayName = grant.displayName,
                    isWritable = grant.isWritable,
                    merges = mergeSpecs,
                )
                for ((childRoot, _) in decision.children) {
                    if (childRoot.treeUri != grant.treeUri) {
                        dataSource.releasePersistableGrant(childRoot.treeUri)
                    }
                }
                repository.refreshNow()
                rescanRoot(newRootId, incremental = true)
                val refreshed = repository.safRoots.value.first { it.rootId == newRootId }
                AddRootResult.MergedChildRoots(root = refreshed, mergedCount = decision.children.size)
            }

            SafOverlapDetector.OverlapDecision.Disjoint -> {
                val newRootId = repository.backend.insertSafRoot(
                    treeUri = grant.treeUri,
                    treeDocId = grant.treeDocId,
                    displayName = grant.displayName,
                    isWritable = grant.isWritable,
                    mountState = MountState.MOUNTED,
                )
                repository.refreshNow()
                rescanRoot(newRootId, incremental = true)
                val refreshed = repository.safRoots.value.first { it.rootId == newRootId }
                AddRootResult.Added(refreshed)
            }
        }
    }

    override suspend fun rescanAll(incremental: Boolean) = withContext(ioDispatcher) {
        val rootIds = repository.safRoots.value.map { it.rootId }
        for (id in rootIds) {
            rescanRoot(id, incremental = incremental)
        }
    }

    override suspend fun rescanRoot(rootId: Long, incremental: Boolean) = withContext(ioDispatcher) {
        scanMutex.withLock {
            val rootRow = repository.backend.getSafRootRow(rootId) ?: return@withLock
            performSingleRootScan(rootRow, incremental)
        }
    }

    override suspend fun removeSafRoot(rootId: Long) = withContext(ioDispatcher) {
        scanMutex.withLock {
            val rootRow = repository.backend.getSafRootRow(rootId) ?: return@withLock
            val otherSharesUri = repository.safRoots.value.any { it.rootId != rootId && it.treeUri == rootRow.treeUri }
            repository.backend.deleteSafRoot(rootId)
            if (!otherSharesUri) {
                dataSource.releasePersistableGrant(rootRow.treeUri)
            }
            repository.refreshNow()
        }
    }

    override suspend fun setFolderPracticeRule(
        rootId: Long,
        relPath: String,
        folderUri: String,
        isPracticeFolder: Boolean,
    ) = withContext(ioDispatcher) {
        val normPath = MediaRepository.normalizeRelPath(relPath)
        val existing = repository.getFolderRule(rootId, normPath)
        val currentExcluded = existing?.isExcludedFromAutoGrouping
            ?: repository.isExcludedFromAutoGrouping(rootId, normPath)
        val effectiveUri = folderUri.ifBlank {
            existing?.folderUri ?: "${repository.backend.getSafRootRow(rootId)?.treeUri.orEmpty()}#rel=$normPath"
        }
        repository.backend.upsertFolderRule(
            FolderRuleRow(
                folderUri = effectiveUri,
                rootId = rootId,
                relPath = normPath,
                isPracticeFolder = isPracticeFolder,
                respectNomedia = existing?.respectNomedia,
                isExcludedFromAutoGrouping = currentExcluded,
            ),
        )
        repository.refreshNow()
    }

    override suspend fun setFolderExcludeFromGroupingRule(
        rootId: Long,
        relPath: String,
        folderUri: String,
        isExcludedFromAutoGrouping: Boolean,
    ) = withContext(ioDispatcher) {
        val normPath = MediaRepository.normalizeRelPath(relPath)
        val existing = repository.getFolderRule(rootId, normPath)
        val currentPractice = existing?.isPracticeFolder
            ?: repository.isPracticeFolder(rootId, normPath)
        val effectiveUri = folderUri.ifBlank {
            existing?.folderUri ?: "${repository.backend.getSafRootRow(rootId)?.treeUri.orEmpty()}#rel=$normPath"
        }
        repository.backend.upsertFolderRule(
            FolderRuleRow(
                folderUri = effectiveUri,
                rootId = rootId,
                relPath = normPath,
                isPracticeFolder = currentPractice,
                respectNomedia = existing?.respectNomedia,
                isExcludedFromAutoGrouping = isExcludedFromAutoGrouping,
            ),
        )
        repository.refreshNow()
    }

    override suspend fun setFolderNomediaRule(
        rootId: Long,
        relPath: String,
        folderUri: String,
        respectNomedia: Boolean?,
    ) = withContext(ioDispatcher) {
        val normPath = MediaRepository.normalizeRelPath(relPath)
        val existing = repository.getFolderRule(rootId, normPath)
        val currentPractice = existing?.isPracticeFolder ?: repository.isPracticeFolder(rootId, normPath)
        val currentExcluded = existing?.isExcludedFromAutoGrouping ?: repository.isExcludedFromAutoGrouping(rootId, normPath)
        val effectiveUri = folderUri.ifBlank {
            existing?.folderUri ?: "${repository.backend.getSafRootRow(rootId)?.treeUri.orEmpty()}#rel=$normPath"
        }
        repository.backend.upsertFolderRule(
            FolderRuleRow(
                folderUri = effectiveUri,
                rootId = rootId,
                relPath = normPath,
                isPracticeFolder = currentPractice,
                respectNomedia = respectNomedia,
                isExcludedFromAutoGrouping = currentExcluded,
            ),
        )
        repository.refreshNow()
    }

    // --- Core Single-Pass Incremental Traversal with Offline Volume Guard ---

    private fun performSingleRootScan(root: SafRootRow, incremental: Boolean) {
        // 1. Offline Volume Guard: NEVER delete items/playlists/bookmarks when OTG/SD is unplugged or permission revoked!
        val probe = dataSource.probeRootMountState(root.treeUri, root.treeDocId, root.isWritable)
        when (probe) {
            SafVolumeProbe.PermissionRevoked -> {
                repository.backend.updateSafRootStatus(root.rootId, MountState.PERMISSION_REVOKED)
                repository.refreshNow()
                return
            }
            SafVolumeProbe.OfflineUnmounted -> {
                repository.backend.updateSafRootStatus(root.rootId, MountState.OFFLINE_UNMOUNTED)
                repository.refreshNow()
                return
            }
            is SafVolumeProbe.Mounted -> {
                // Proceed with mounted traversal
            }
        }
        val mounted = probe as SafVolumeProbe.Mounted

        _scanProgress.value = ScanProgress(
            isScanning = true,
            scannedCount = 0,
            currentFolder = root.displayName,
            addedOrUpdatedCount = 0,
        )

        try {
            val dbSnap = repository.backend.readSnapshot()
            val existingForRoot = dbSnap.mediaItems.filter { it.rootId == root.rootId }
            val existingByUri = existingForRoot.associateBy { it.documentUri }

            val scannedFolders = ArrayList<ScannedFolderRow>()
            val seenExistingItemIds = HashSet<Long>()
            val unchangedCoverUpdates = HashMap<Long, String?>()
            val movedUpdates = ArrayList<MovedMediaUpdate>()
            var incrementalSkipped = 0
            var movedRebound = 0

            data class DiscoveredNewFile(
                val entry: SafChildEntry,
                val parentRelPath: String,
                val isVideo: Boolean,
                val folderCoverUri: String?,
            )
            val discoveredNewOrModified = ArrayList<DiscoveredNewFile>()
            val pendingLrcSidecars = ArrayList<Triple<String, String, Long>>() // (mediaDocUri, lrcDocUri, lrcLastMod)

            data class DirWork(val docId: String, val relPath: String, val folderUri: String)
            val queue = ArrayDeque<DirWork>()
            queue.add(DirWork(root.treeDocId, "", mounted.rootDocUri))

            var scannedFiles = 0

            while (queue.isNotEmpty()) {
                val dir = queue.removeFirst()
                _scanProgress.value = _scanProgress.value.copy(currentFolder = dir.relPath.ifEmpty { root.displayName })

                val children = dataSource.listChildren(root.treeUri, dir.docId)
                val hasNomedia = children.any { !it.isDirectory && it.displayName.equals(".nomedia", ignoreCase = true) }

                if (dir.relPath.isNotEmpty()) {
                    scannedFolders.add(
                        ScannedFolderRow(
                            rootId = root.rootId,
                            relPath = dir.relPath,
                            folderUri = dir.folderUri,
                            hasNomedia = hasNomedia,
                        ),
                    )
                }

                // Identify best folder cover image & sidecar .lrc files in this directory in the same single pass
                var bestCoverRank = Int.MAX_VALUE
                var folderCoverUri: String? = null
                val lrcByStemLower = HashMap<String, SafChildEntry>()
                val mediaEntries = ArrayList<Pair<SafChildEntry, Boolean>>()

                for (child in children) {
                    val name = child.displayName
                    if (name.startsWith(".")) continue // Ignore hidden files & .Trash / .thumbnails

                    if (child.isDirectory) {
                        val childRel = joinRelSegments(dir.relPath, name)
                        queue.addLast(DirWork(child.documentId, childRel, child.documentUri))
                        continue
                    }

                    val coverRank = folderCoverRank(name)
                    if (coverRank != null && coverRank < bestCoverRank) {
                        bestCoverRank = coverRank
                        folderCoverUri = child.documentUri
                        continue
                    }

                    if (name.endsWith(".lrc", ignoreCase = true)) {
                        val stem = SmartFilenameParser.stripExtension(name).lowercase(Locale.ROOT)
                        if (stem.isNotEmpty()) {
                            lrcByStemLower[stem] = child
                        }
                        continue
                    }

                    val (isMedia, isVideo) = classifyMediaFile(name, child.mimeType)
                    if (isMedia) {
                        mediaEntries.add(child to isVideo)
                    }
                }

                for ((entry, isVideo) in mediaEntries) {
                    scannedFiles++
                    if (scannedFiles % 10 == 0) {
                        _scanProgress.value = _scanProgress.value.copy(scannedCount = scannedFiles)
                    }

                    val stemLower = SmartFilenameParser.stripExtension(entry.displayName).lowercase(Locale.ROOT)
                    val matchingLrc = lrcByStemLower[stemLower]
                    if (matchingLrc != null) {
                        pendingLrcSidecars.add(Triple(entry.documentUri, matchingLrc.documentUri, matchingLrc.lastModified))
                    }

                    val existing = existingByUri[entry.documentUri]
                    if (incremental &&
                        existing != null &&
                        existing.sizeBytes == entry.sizeBytes &&
                        existing.lastModified == entry.lastModified &&
                        existing.lastModified > 0L
                    ) {
                        seenExistingItemIds.add(existing.itemId)
                        if (existing.parentRelPath == dir.relPath && existing.fileName == entry.displayName) {
                            // 0 ms incremental cache hit!
                            incrementalSkipped++
                            if (existing.folderCoverUri != folderCoverUri) {
                                unchangedCoverUpdates[existing.itemId] = folderCoverUri
                            }
                        } else {
                            // Parent folder or display name renamed while documentUri stayed identical (e.g. `rock` -> `Rock`):
                            // migrate parent_rel_path & file_name in 0 ms without re-probing MediaMetadataRetriever!
                            val parsed = SmartFilenameParser.parse(dir.relPath, entry.displayName)
                            val fpKey = MediaRepository.buildFingerprintKey(
                                fileName = entry.displayName,
                                sizeBytes = entry.sizeBytes,
                                durationMs = existing.durationMs,
                            )
                            movedUpdates.add(
                                MovedMediaUpdate(
                                    existingItemId = existing.itemId,
                                    newDocumentUri = entry.documentUri,
                                    newRootId = root.rootId,
                                    newParentRelPath = dir.relPath,
                                    newFileName = entry.displayName,
                                    newSizeBytes = entry.sizeBytes,
                                    newLastModified = entry.lastModified,
                                    newFolderCoverUri = folderCoverUri,
                                    newFingerprintKey = fpKey,
                                    parsedTitle = parsed.cleanedTitle,
                                    parsedArtist = parsed.artist,
                                    parsedAlbum = parsed.album,
                                    isBackingTrack = parsed.isBackingTrack,
                                ),
                            )
                            movedRebound++
                        }
                    } else {
                        if (existing != null) {
                            seenExistingItemIds.add(existing.itemId)
                        }
                        discoveredNewOrModified.add(
                            DiscoveredNewFile(
                                entry = entry,
                                parentRelPath = dir.relPath,
                                isVideo = isVideo,
                                folderCoverUri = folderCoverUri,
                            ),
                        )
                    }
                }
            }

            // Pool of unseen old items in this root eligible for moved-file fingerprint migration
            val unseenPool = existingForRoot
                .filter { it.itemId !in seenExistingItemIds }
                .toMutableList()

            val upsertedItems = ArrayList<MediaItemRow>()
            val lyricsUpdates = ArrayList<Pair<String, LyricsCacheRow>>()
            var addedOrUpdated = movedRebound

            for (disc in discoveredNewOrModified) {
                val entry = disc.entry
                val parsed = SmartFilenameParser.parse(disc.parentRelPath, entry.displayName)
                val existingSameUri = existingByUri[entry.documentUri]

                // Fast 0 ms folder-rename migration when SAF changed child document URIs (`rock` -> `Rock` or `metal`)
                // while file display name, size, and mtime (> 0) stayed unchanged!
                if (incremental && existingSameUri == null && entry.lastModified > 0L && unseenPool.isNotEmpty()) {
                    val fastMatches = unseenPool.filter {
                        it.fileName.equals(entry.displayName, ignoreCase = true) &&
                            it.sizeBytes == entry.sizeBytes &&
                            it.lastModified > 0L &&
                            it.lastModified == entry.lastModified
                    }
                    val fastMatched = fastMatches.singleOrNull()
                    if (fastMatched != null) {
                        unseenPool.remove(fastMatched)
                        seenExistingItemIds.add(fastMatched.itemId)
                        val fpKey = MediaRepository.buildFingerprintKey(
                            fileName = entry.displayName,
                            sizeBytes = entry.sizeBytes,
                            durationMs = fastMatched.durationMs,
                        )
                        movedUpdates.add(
                            MovedMediaUpdate(
                                existingItemId = fastMatched.itemId,
                                newDocumentUri = entry.documentUri,
                                newRootId = root.rootId,
                                newParentRelPath = disc.parentRelPath,
                                newFileName = entry.displayName,
                                newSizeBytes = entry.sizeBytes,
                                newLastModified = entry.lastModified,
                                newFolderCoverUri = disc.folderCoverUri,
                                newFingerprintKey = fpKey,
                                parsedTitle = parsed.cleanedTitle,
                                parsedArtist = parsed.artist,
                                parsedAlbum = parsed.album,
                                isBackingTrack = parsed.isBackingTrack,
                            ),
                        )
                        movedRebound++
                        addedOrUpdated++
                        _scanProgress.value = _scanProgress.value.copy(
                            scannedCount = scannedFiles,
                            addedOrUpdatedCount = addedOrUpdated,
                        )
                        continue
                    }
                }

                // Extract tags & duration
                val extracted = dataSource.extractMediaMetadata(entry, disc.isVideo)
                val fpKey = MediaRepository.buildFingerprintKey(
                    fileName = entry.displayName,
                    sizeBytes = entry.sizeBytes,
                    durationMs = extracted.durationMs,
                )

                // Check moved-file fingerprint migration ONLY when this URI is brand-new
                val migratedTarget = if (existingSameUri == null && unseenPool.isNotEmpty()) {
                    unseenPool.firstOrNull { it.fingerprintKey == fpKey }
                        ?: unseenPool.firstOrNull {
                            // Secondary match if duration wasn't previously known (0) and (name + non-zero size) is unique
                            entry.sizeBytes > 0L &&
                                it.fileName.equals(entry.displayName, ignoreCase = true) &&
                                it.sizeBytes == entry.sizeBytes &&
                                unseenPool.count { c ->
                                    c.fileName.equals(entry.displayName, ignoreCase = true) && c.sizeBytes == entry.sizeBytes
                                } == 1
                        }
                } else {
                    null
                }

                if (migratedTarget != null) {
                    unseenPool.remove(migratedTarget)
                    movedUpdates.add(
                        MovedMediaUpdate(
                            existingItemId = migratedTarget.itemId,
                            newDocumentUri = entry.documentUri,
                            newRootId = root.rootId,
                            newParentRelPath = disc.parentRelPath,
                            newFileName = entry.displayName,
                            newSizeBytes = entry.sizeBytes,
                            newLastModified = entry.lastModified,
                            newFolderCoverUri = disc.folderCoverUri,
                            newFingerprintKey = fpKey,
                            parsedTitle = parsed.cleanedTitle,
                            parsedArtist = parsed.artist,
                            parsedAlbum = parsed.album,
                            isBackingTrack = parsed.isBackingTrack,
                        ),
                    )
                } else {
                    upsertedItems.add(
                        MediaItemRow(
                            itemId = existingSameUri?.itemId ?: 0L,
                            documentUri = entry.documentUri,
                            rootId = root.rootId,
                            parentRelPath = disc.parentRelPath,
                            fileName = entry.displayName,
                            mimeType = entry.mimeType,
                            isVideo = disc.isVideo,
                            sizeBytes = entry.sizeBytes,
                            lastModified = entry.lastModified,
                            durationMs = extracted.durationMs,
                            width = extracted.width,
                            height = extracted.height,
                            rawTitle = extracted.rawTitle,
                            rawArtist = extracted.rawArtist,
                            rawAlbum = extracted.rawAlbum,
                            rawAlbumArtist = extracted.rawAlbumArtist,
                            rawTrackNum = extracted.rawTrackNum ?: parsed.trackNumber,
                            rawYear = extracted.rawYear,
                            parsedTitle = parsed.cleanedTitle,
                            parsedArtist = parsed.artist,
                            parsedAlbum = parsed.album,
                            isBackingTrack = parsed.isBackingTrack,
                            embeddedCoverPath = extracted.cachedCoverPath,
                            folderCoverUri = disc.folderCoverUri,
                            fingerprintKey = fpKey,
                        ),
                    )
                }

                if (!extracted.embeddedLyrics.isNullOrBlank()) {
                    val parsedLrc = LrcParser.parse(extracted.embeddedLyrics, LyricsSource.EMBEDDED)
                    lyricsUpdates.add(
                        entry.documentUri to LyricsCacheRow(
                            itemId = 0L,
                            syncedLrc = if (parsedLrc != null && parsedLrc.lines.isNotEmpty()) extracted.embeddedLyrics else null,
                            plainLyrics = parsedLrc?.plainText ?: extracted.embeddedLyrics,
                            source = LyricsSource.EMBEDDED,
                            sourceModifiedAt = entry.lastModified,
                        ),
                    )
                }

                addedOrUpdated++
                _scanProgress.value = _scanProgress.value.copy(
                    scannedCount = scannedFiles,
                    addedOrUpdatedCount = addedOrUpdated,
                )
            }

            // Process sidecar .lrc files (skipping unchanged sidecars on incremental scan!)
            val movedItemIdByNewUri = movedUpdates.associate { it.newDocumentUri to it.existingItemId }
            for ((mediaDocUri, lrcDocUri, lrcLastMod) in pendingLrcSidecars) {
                val existingItemId = existingByUri[mediaDocUri]?.itemId ?: movedItemIdByNewUri[mediaDocUri]
                if (incremental && existingItemId != null) {
                    val cachedLrc = repository.backend.getLyricsCache(existingItemId)
                    if (cachedLrc != null &&
                        cachedLrc.source == LyricsSource.LOCAL_LRC &&
                        cachedLrc.sourceModifiedAt == lrcLastMod &&
                        lrcLastMod > 0L
                    ) {
                        continue
                    }
                }
                val lrcBytes = dataSource.readSmallFileBytes(lrcDocUri) ?: continue
                val lrcText = LrcParser.decodeBytes(lrcBytes).trim()
                if (lrcText.isEmpty()) continue
                val parsedLrc = LrcParser.parse(lrcText, LyricsSource.LOCAL_LRC)
                lyricsUpdates.add(
                    mediaDocUri to LyricsCacheRow(
                        itemId = 0L,
                        syncedLrc = if (parsedLrc != null && parsedLrc.lines.isNotEmpty()) lrcText else null,
                        plainLyrics = parsedLrc?.plainText ?: lrcText,
                        source = LyricsSource.LOCAL_LRC,
                        sourceModifiedAt = lrcLastMod,
                    ),
                )
            }

            // Remaining items in unseenPool were genuinely deleted while volume was MOUNTED
            val deletedIds = unseenPool.map { it.itemId }.toSet()

            repository.backend.reconcileRootScan(
                rootId = root.rootId,
                isWritable = mounted.isWritable,
                scannedAtMs = System.currentTimeMillis(),
                scannedFolders = scannedFolders,
                unchangedCoverUpdates = unchangedCoverUpdates,
                movedItems = movedUpdates,
                upsertedItems = upsertedItems,
                deletedItemIds = deletedIds,
                lyricsUpdates = lyricsUpdates,
                artistSplitter = { artist, albumArtist -> ArtistSplitter.split(artist, albumArtist) },
                liveRootDisplayName = mounted.liveDisplayName,
                hasCompleteFolderScan = true,
            )
            repository.refreshNow()
        } catch (_: VolumeDisconnectedException) {
            // Mid-scan OTG/SD disconnect guard: mark OFFLINE_UNMOUNTED and DO NOT prune any records!
            repository.backend.updateSafRootStatus(root.rootId, MountState.OFFLINE_UNMOUNTED)
            repository.refreshNow()
        } finally {
            _scanProgress.value = _scanProgress.value.copy(isScanning = false)
        }
    }
}
