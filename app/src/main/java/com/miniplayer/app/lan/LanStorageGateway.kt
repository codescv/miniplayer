package com.miniplayer.app.lan

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import com.miniplayer.app.core.MetadataEngineContract
import com.miniplayer.app.core.model.M3uImportResult
import com.miniplayer.app.core.model.MediaItem
import com.miniplayer.app.core.model.MountState
import com.miniplayer.app.core.model.SafRoot
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

internal class LanHttpException(
    val status: Int,
    val reason: String,
    override val message: String,
) : IOException(message)

data class StreamableResource(
    val sizeBytes: Long,
    val mimeType: String,
    val fileName: String,
    val openAtOffset: (offset: Long) -> InputStream,
)

data class CreatedFolderInfo(
    val folderUri: String,
    val relPath: String,
    val name: String,
)

data class UploadedFileInfo(
    val documentUri: String,
    val relPath: String,
    val fileName: String,
    val bytesWritten: Long,
)

/**
 * Storage abstraction bridging [LanStudioServer] with Android SAF (`content://`) trees
 * in production and local filesystem directories in pure-JVM tests.
 */
interface LanStorageGateway {
    fun openMediaStream(item: MediaItem): StreamableResource?
    fun openCoverStream(item: MediaItem): StreamableResource?

    fun createSubfolder(
        root: SafRoot,
        parentRelPath: String,
        folderName: String,
    ): CreatedFolderInfo

    fun writeUploadedFile(
        root: SafRoot,
        parentRelPath: String,
        fileName: String,
        mimeType: String,
        source: InputStream,
        maxBytes: Long,
    ): UploadedFileInfo

    suspend fun exportM3u8ViaCoreOrNull(
        metadata: MetadataEngineContract,
        playlistId: Long,
    ): String? = null

    suspend fun importM3u8ViaCoreOrNull(
        metadata: MetadataEngineContract,
        m3uContent: String,
        fallbackName: String,
    ): M3uImportResult? = null
}

internal object LanPathUtils {
    val DEFAULT_ALLOWED_UPLOAD_EXTENSIONS: Set<String> = setOf(
        // Audio
        "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "alac", "aiff", "ape",
        // Video
        "mp4", "mkv", "webm", "mov", "m4v", "avi", "3gp", "ts",
        // Lyrics / Subtitles / Sidecar covers / Playlists
        "lrc", "srt", "vtt", "jpg", "jpeg", "png", "webp", "m3u", "m3u8",
    )

    private val FAT_UNSAFE_CHARS = setOf(':', '*', '?', '"', '<', '>', '|')

    fun normalizeRelPath(rawRelPath: String): String {
        if (rawRelPath.contains('\u0000') || rawRelPath.contains('\\')) {
            throw LanHttpException(403, "Forbidden", "Illegal characters in relative path")
        }
        val segments = ArrayList<String>()
        for (rawSeg in rawRelPath.split('/')) {
            if (rawSeg.isEmpty()) continue
            if (rawSeg == "." || rawSeg == "..") {
                throw LanHttpException(403, "Forbidden", "Path traversal is not permitted")
            }
            segments.add(validateLeafName(rawSeg))
        }
        return segments.joinToString("/")
    }

    fun validateLeafName(rawName: String): String {
        if (rawName.contains('\u0000') || rawName.contains('/') || rawName.contains('\\')) {
            throw LanHttpException(403, "Forbidden", "Illegal path separator in name")
        }
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) {
            throw LanHttpException(400, "Bad Request", "Name must not be empty")
        }
        if (trimmed == "." || trimmed == "..") {
            throw LanHttpException(403, "Forbidden", "Reserved directory traversal name")
        }
        if (trimmed.toByteArray(Charsets.UTF_8).size > 255) {
            throw LanHttpException(400, "Bad Request", "File name exceeds 255 UTF-8 bytes")
        }
        for (ch in trimmed) {
            if (ch.code < 0x20 || ch in FAT_UNSAFE_CHARS) {
                throw LanHttpException(400, "Bad Request", "Name contains unsupported character '$ch'")
            }
        }
        return trimmed
    }

    fun extensionOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.lastIndex) return ""
        return fileName.substring(dot + 1).lowercase(Locale.ROOT)
    }

    fun guessMimeType(fileName: String): String = when (extensionOf(fileName)) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "m4a", "alac" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wma" -> "audio/x-ms-wma"
        "aiff" -> "audio/aiff"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "3gp" -> "video/3gpp"
        "ts" -> "video/mp2t"
        "lrc", "srt", "vtt" -> "text/plain; charset=utf-8"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "m3u", "m3u8" -> "audio/x-mpegurl; charset=utf-8"
        else -> "application/octet-stream"
    }

    fun skipFully(input: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val scratch = ByteArray(16 * 1024)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }
            val toRead = minOf(remaining, scratch.size.toLong()).toInt()
            val read = input.read(scratch, 0, toRead)
            if (read < 0) break
            remaining -= read
        }
    }
}

/**
 * Pure-JVM filesystem implementation of [LanStorageGateway] supporting `file://` URIs and
 * explicit root directory mappings (used directly in JUnit 4 tests and local folder roots).
 */
class LocalLanStorageGateway(
    private val rootDirs: Map<Long, File> = emptyMap(),
) : LanStorageGateway {

    override fun openMediaStream(item: MediaItem): StreamableResource? {
        val file = resolveLocalFileFromUriOrPath(item.documentUri) ?: return null
        if (!file.isFile || !file.canRead()) return null
        // If item belongs to a mapped local root, enforce canonical jail check
        rootDirs[item.rootId]?.let { rootDir -> ensureInsideRoot(rootDir, file) }
        val mime = item.mimeType.ifBlank { LanPathUtils.guessMimeType(file.name) }
        return buildLocalFileResource(file, mime, item.fileName.ifBlank { file.name })
    }

    override fun openCoverStream(item: MediaItem): StreamableResource? {
        val ref = item.coverPathOrUri?.takeIf { it.isNotBlank() } ?: return null
        val file = resolveLocalFileFromUriOrPath(ref) ?: return null
        if (!file.isFile || !file.canRead()) return null
        val mime = LanPathUtils.guessMimeType(file.name).let {
            if (it.startsWith("image/")) it else "image/jpeg"
        }
        return buildLocalFileResource(file, mime, file.name)
    }

    override fun createSubfolder(
        root: SafRoot,
        parentRelPath: String,
        folderName: String,
    ): CreatedFolderInfo {
        ensureRootWritable(root)
        val rootDir = resolveRootDirectory(root)
        val cleanParentRel = LanPathUtils.normalizeRelPath(parentRelPath)
        val cleanName = LanPathUtils.validateLeafName(folderName)

        val parentDir = if (cleanParentRel.isEmpty()) rootDir else File(rootDir, cleanParentRel)
        ensureInsideRoot(rootDir, parentDir)
        if (!parentDir.exists() || !parentDir.isDirectory) {
            throw LanHttpException(404, "Not Found", "Parent folder does not exist: $cleanParentRel")
        }

        val targetDir = File(parentDir, cleanName)
        ensureInsideRoot(rootDir, targetDir)
        if (targetDir.exists() && !targetDir.isDirectory) {
            throw LanHttpException(409, "Conflict", "A file named '$cleanName' already exists")
        }
        if (!targetDir.exists() && !targetDir.mkdir()) {
            throw IOException("Failed to create subfolder '$cleanName'")
        }
        val createdRel = if (cleanParentRel.isEmpty()) cleanName else "$cleanParentRel/$cleanName"
        return CreatedFolderInfo(
            folderUri = targetDir.toURI().toString(),
            relPath = createdRel,
            name = cleanName,
        )
    }

    override fun writeUploadedFile(
        root: SafRoot,
        parentRelPath: String,
        fileName: String,
        mimeType: String,
        source: InputStream,
        maxBytes: Long,
    ): UploadedFileInfo {
        ensureRootWritable(root)
        val rootDir = resolveRootDirectory(root)
        val cleanParentRel = LanPathUtils.normalizeRelPath(parentRelPath)
        val cleanName = LanPathUtils.validateLeafName(fileName)

        val parentDir = if (cleanParentRel.isEmpty()) rootDir else File(rootDir, cleanParentRel)
        ensureInsideRoot(rootDir, parentDir)
        if (!parentDir.exists() || !parentDir.isDirectory) {
            throw LanHttpException(404, "Not Found", "Destination folder does not exist: $cleanParentRel")
        }

        val targetFile = File(parentDir, cleanName)
        ensureInsideRoot(rootDir, targetFile)
        if (targetFile.exists() && targetFile.isDirectory) {
            throw LanHttpException(409, "Conflict", "A folder named '$cleanName' already exists")
        }

        val tempFile = File(parentDir, ".lan_upload_${System.nanoTime()}.tmp")
        ensureInsideRoot(rootDir, tempFile)
        var written = 0L
        try {
            FileOutputStream(tempFile).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        written += n
                        if (written > maxBytes) {
                            throw LanHttpException(413, "Payload Too Large", "Uploaded file exceeds size limit")
                        }
                        out.write(buf, 0, n)
                    }
                }
                out.flush()
                runCatching { out.fd.sync() }
            }
            try {
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Exception) {
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (t: Throwable) {
            runCatching { tempFile.delete() }
            throw t
        }

        val fileRel = if (cleanParentRel.isEmpty()) cleanName else "$cleanParentRel/$cleanName"
        return UploadedFileInfo(
            documentUri = targetFile.toURI().toString(),
            relPath = fileRel,
            fileName = cleanName,
            bytesWritten = written,
        )
    }

    private fun resolveRootDirectory(root: SafRoot): File {
        val mapped = rootDirs[root.rootId]
        val dir = mapped ?: resolveLocalFileFromUriOrPath(root.treeUri)
            ?: throw LanHttpException(400, "Bad Request", "Root '${root.displayName}' is not backed by a local directory")
        if (!dir.exists() || !dir.isDirectory) {
            throw LanHttpException(404, "Not Found", "Root folder is unavailable: ${root.displayName}")
        }
        return dir
    }

    companion object {
        internal fun ensureRootWritable(root: SafRoot) {
            if (root.mountState != MountState.MOUNTED) {
                throw LanHttpException(409, "Conflict", "Folder '${root.displayName}' is currently ${root.mountState}")
            }
            if (!root.isWritable) {
                throw LanHttpException(403, "Forbidden", "Folder '${root.displayName}' is mounted read-only")
            }
        }

        internal fun resolveLocalFileFromUriOrPath(uriOrPath: String): File? {
            if (uriOrPath.startsWith("content://", ignoreCase = true) ||
                uriOrPath.startsWith("http://", ignoreCase = true) ||
                uriOrPath.startsWith("https://", ignoreCase = true)
            ) {
                return null
            }
            if (uriOrPath.startsWith("file:", ignoreCase = true)) {
                return runCatching { File(URI(uriOrPath)) }.getOrNull()
            }
            return File(uriOrPath)
        }

        internal fun ensureInsideRoot(rootDir: File, candidate: File) {
            val rootCanonical = rootDir.canonicalPath
            val candidateCanonical = candidate.canonicalPath
            val inside = candidateCanonical == rootCanonical ||
                candidateCanonical.startsWith(
                    if (rootCanonical.endsWith(File.separator)) rootCanonical else "$rootCanonical${File.separator}",
                )
            if (!inside) {
                throw LanHttpException(403, "Forbidden", "Resolved path escapes root folder boundary")
            }
        }

        internal fun buildLocalFileResource(file: File, mimeType: String, displayName: String): StreamableResource {
            val length = file.length().coerceAtLeast(0L)
            return StreamableResource(
                sizeBytes = length,
                mimeType = mimeType,
                fileName = displayName,
                openAtOffset = { offset ->
                    val raf = RandomAccessFile(file, "r")
                    try {
                        if (offset > 0L) {
                            raf.seek(offset)
                        }
                        RandomAccessFileInputStream(raf)
                    } catch (t: Throwable) {
                        runCatching { raf.close() }
                        throw t
                    }
                },
            )
        }
    }
}

private class RandomAccessFileInputStream(
    private val raf: RandomAccessFile,
) : InputStream() {
    override fun read(): Int = raf.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
    override fun close() = raf.close()
}

/**
 * Production Android implementation of [LanStorageGateway] supporting both Android SAF
 * (`content://` tree/document URIs via [DocumentsContract] & [ContentResolver]) and local `file://` files.
 */
class AndroidLanStorageGateway(
    context: Context,
) : LanStorageGateway {
    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver
    private val localDelegate = LocalLanStorageGateway()

    override fun openMediaStream(item: MediaItem): StreamableResource? {
        localDelegate.openMediaStream(item)?.let { return it }
        if (!item.documentUri.startsWith("content://", ignoreCase = true)) return null
        val uri = Uri.parse(item.documentUri)
        val mime = item.mimeType.ifBlank {
            contentResolver.getType(uri) ?: LanPathUtils.guessMimeType(item.fileName)
        }
        val size = queryContentLength(uri, fallback = item.sizeBytes)
        if (size < 0L) return null
        return StreamableResource(
            sizeBytes = size,
            mimeType = mime,
            fileName = item.fileName,
            openAtOffset = { offset -> openSafStreamAtOffset(uri, offset) },
        )
    }

    override fun openCoverStream(item: MediaItem): StreamableResource? {
        localDelegate.openCoverStream(item)?.let { return it }
        val ref = item.coverPathOrUri?.takeIf { it.isNotBlank() } ?: return null
        if (!ref.startsWith("content://", ignoreCase = true)) return null
        val uri = Uri.parse(ref)
        val mime = contentResolver.getType(uri) ?: "image/jpeg"
        val size = queryContentLength(uri, fallback = -1L)
        if (size <= 0L) return null
        return StreamableResource(
            sizeBytes = size,
            mimeType = mime,
            fileName = "cover.jpg",
            openAtOffset = { offset -> openSafStreamAtOffset(uri, offset) },
        )
    }

    override fun createSubfolder(
        root: SafRoot,
        parentRelPath: String,
        folderName: String,
    ): CreatedFolderInfo {
        if (!root.treeUri.startsWith("content://", ignoreCase = true)) {
            return localDelegate.createSubfolder(root, parentRelPath, folderName)
        }
        LocalLanStorageGateway.ensureRootWritable(root)
        val cleanParentRel = LanPathUtils.normalizeRelPath(parentRelPath)
        val cleanName = LanPathUtils.validateLeafName(folderName)
        val treeUri = Uri.parse(root.treeUri)

        val parentDocId = resolveDirectoryDocId(treeUri, root.treeDocId, cleanParentRel)
            ?: throw LanHttpException(404, "Not Found", "Parent folder does not exist: $cleanParentRel")

        val existing = findChildDocument(treeUri, parentDocId, cleanName)
        val folderDocUri: Uri = if (existing != null) {
            if (!existing.isDirectory) {
                throw LanHttpException(409, "Conflict", "A file named '$cleanName' already exists")
            }
            DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.documentId)
        } else {
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
            DocumentsContract.createDocument(
                contentResolver,
                parentDocUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                cleanName,
            ) ?: throw IOException("Failed to create SAF folder '$cleanName'")
        }

        val createdRel = if (cleanParentRel.isEmpty()) cleanName else "$cleanParentRel/$cleanName"
        return CreatedFolderInfo(
            folderUri = folderDocUri.toString(),
            relPath = createdRel,
            name = cleanName,
        )
    }

    override fun writeUploadedFile(
        root: SafRoot,
        parentRelPath: String,
        fileName: String,
        mimeType: String,
        source: InputStream,
        maxBytes: Long,
    ): UploadedFileInfo {
        if (!root.treeUri.startsWith("content://", ignoreCase = true)) {
            return localDelegate.writeUploadedFile(root, parentRelPath, fileName, mimeType, source, maxBytes)
        }
        LocalLanStorageGateway.ensureRootWritable(root)
        val cleanParentRel = LanPathUtils.normalizeRelPath(parentRelPath)
        val cleanName = LanPathUtils.validateLeafName(fileName)
        val treeUri = Uri.parse(root.treeUri)

        val parentDocId = resolveDirectoryDocId(treeUri, root.treeDocId, cleanParentRel)
            ?: throw LanHttpException(404, "Not Found", "Destination folder does not exist: $cleanParentRel")

        val existing = findChildDocument(treeUri, parentDocId, cleanName)
        if (existing?.isDirectory == true) {
            throw LanHttpException(409, "Conflict", "A folder named '$cleanName' already exists")
        }

        val effectiveMime = mimeType.substringBefore(';').trim().takeIf {
            it.isNotEmpty() && !it.equals("application/octet-stream", ignoreCase = true)
        } ?: LanPathUtils.guessMimeType(cleanName).substringBefore(';').trim()

        val targetDocUri: Uri = if (existing != null) {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.documentId)
        } else {
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
            DocumentsContract.createDocument(
                contentResolver,
                parentDocUri,
                effectiveMime,
                cleanName,
            ) ?: throw IOException("Failed to create SAF document '$cleanName'")
        }

        var written = 0L
        var writeSucceeded = false
        try {
            val outStream = runCatching { contentResolver.openOutputStream(targetDocUri, "wt") }.getOrNull()
                ?: contentResolver.openOutputStream(targetDocUri, "w")
                ?: throw IOException("Cannot open SAF output stream for '$cleanName'")
            outStream.use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        written += n
                        if (written > maxBytes) {
                            throw LanHttpException(413, "Payload Too Large", "Uploaded file exceeds size limit")
                        }
                        out.write(buf, 0, n)
                    }
                }
                out.flush()
            }
            writeSucceeded = true
        } finally {
            if (!writeSucceeded && existing == null) {
                runCatching { DocumentsContract.deleteDocument(contentResolver, targetDocUri) }
            }
        }

        val fileRel = if (cleanParentRel.isEmpty()) cleanName else "$cleanParentRel/$cleanName"
        return UploadedFileInfo(
            documentUri = targetDocUri.toString(),
            relPath = fileRel,
            fileName = cleanName,
            bytesWritten = written,
        )
    }

    override suspend fun exportM3u8ViaCoreOrNull(
        metadata: MetadataEngineContract,
        playlistId: Long,
    ): String? {
        val tempDir = File(appContext.cacheDir, "lan_m3u8").apply { mkdirs() }
        val tempFile = File(tempDir, "export_${playlistId}_${System.nanoTime()}.m3u8")
        return try {
            val uri = Uri.fromFile(tempFile) ?: return null
            val ok = metadata.exportPlaylistM3u8(playlistId, uri)
            if (ok && tempFile.isFile && tempFile.length() > 0L) {
                tempFile.readText(Charsets.UTF_8)
            } else {
                null
            }
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    override suspend fun importM3u8ViaCoreOrNull(
        metadata: MetadataEngineContract,
        m3uContent: String,
        fallbackName: String,
    ): M3uImportResult? {
        val tempDir = File(appContext.cacheDir, "lan_m3u8").apply { mkdirs() }
        val tempFile = File(tempDir, "import_${System.nanoTime()}.m3u8")
        return try {
            tempFile.writeText(m3uContent, Charsets.UTF_8)
            val uri = Uri.fromFile(tempFile) ?: return null
            val res = metadata.importPlaylistM3u8(uri, fallbackName)
            if (res.playlistId > 0L) res else null
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    private data class SafChildDoc(
        val documentId: String,
        val displayName: String,
        val isDirectory: Boolean,
    )

    private fun resolveDirectoryDocId(treeUri: Uri, rootTreeDocId: String, cleanRelPath: String): String? {
        var currentDocId = rootTreeDocId.ifBlank { DocumentsContract.getTreeDocumentId(treeUri) }
        if (cleanRelPath.isEmpty()) return currentDocId
        for (segment in cleanRelPath.split('/')) {
            if (segment.isEmpty()) continue
            val child = findChildDocument(treeUri, currentDocId, segment) ?: return null
            if (!child.isDirectory) return null
            currentDocId = child.documentId
        }
        return currentDocId
    }

    private fun findChildDocument(treeUri: Uri, parentDocId: String, targetName: String): SafChildDoc? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) cursor.getString(nameIdx) else continue
                if (name == targetName) {
                    val docId = cursor.getString(idIdx)
                    val mime = if (mimeIdx >= 0 && !cursor.isNull(mimeIdx)) cursor.getString(mimeIdx) else ""
                    return SafChildDoc(
                        documentId = docId,
                        displayName = name,
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    )
                }
            }
        }
        return null
    }

    private fun queryContentLength(uri: Uri, fallback: Long): Long {
        runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length >= 0L) return afd.length
            }
        }
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    val queried = cursor.getLong(0)
                    if (queried >= 0L) return queried
                }
            }
        }
        return fallback
    }

    private fun openSafStreamAtOffset(uri: Uri, offset: Long): InputStream {
        if (offset >= 0L) {
            val pfd = runCatching { contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
            if (pfd != null) {
                try {
                    if (offset > 0L) {
                        Os.lseek(pfd.fileDescriptor, offset, OsConstants.SEEK_SET)
                    }
                    return ParcelFileDescriptor.AutoCloseInputStream(pfd)
                } catch (_: Exception) {
                    runCatching { pfd.close() }
                }
            }
        }
        val stream = contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Cannot open SAF media stream: $uri")
        if (offset > 0L) {
            try {
                LanPathUtils.skipFully(stream, offset)
            } catch (t: Throwable) {
                runCatching { stream.close() }
                throw t
            }
        }
        return stream
    }
}
