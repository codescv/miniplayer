package com.miniplayer.app.core.metadata

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.io.PushbackInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Base64
import java.util.Locale

enum class AudioContainerFormat {
    MP3,
    FLAC,
    OGG,
    MP4,
    UNSUPPORTED,
}

data class CoverImage(
    val bytes: ByteArray,
    val mimeType: String = detectImageMimeType(bytes),
) {
    companion object {
        fun detectImageMimeType(bytes: ByteArray): String {
            if (bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte()
            ) {
                return "image/png"
            }
            if (bytes.size >= 12 &&
                bytes[0] == 'R'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() &&
                bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() &&
                bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() &&
                bytes[11] == 'P'.code.toByte()
            ) {
                return "image/webp"
            }
            return "image/jpeg"
        }
    }
}

sealed class OptionalField<out T> {
    data object Unchanged : OptionalField<Nothing>()
    data class Set<T>(val value: T) : OptionalField<T>()
}

data class TagWriteSpec(
    val title: OptionalField<String?> = OptionalField.Unchanged,
    val artist: OptionalField<String?> = OptionalField.Unchanged,
    val album: OptionalField<String?> = OptionalField.Unchanged,
    val albumArtist: OptionalField<String?> = OptionalField.Unchanged,
    val trackNumber: OptionalField<Int?> = OptionalField.Unchanged,
    val year: OptionalField<Int?> = OptionalField.Unchanged,
    val cover: OptionalField<CoverImage?> = OptionalField.Unchanged,
)

data class EmbeddedAudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val year: Int? = null,
    val lyrics: String? = null,
    val coverBytes: ByteArray? = null,
    val coverMimeType: String? = null,
    val durationMs: Long? = null,
)

/**
 * Pure-JVM binary audio tag reader & crash-safe temp-staged SAF writer (`DESIGN.md` §6.3, `ARCHITECTURE.md` §4.4).
 *
 * Supports:
 * - `.mp3`: ID3v2.2 / v2.3 / v2.4 + ID3v1 fallback reading; ID3v2.4 (`TIT2/TPE1/TALB/TPE2/TRCK/TDRC/APIC`) writing.
 * - `.flac`: FLAC `STREAMINFO` + `VORBIS_COMMENT` (type 4) + `PICTURE` (type 6) reading & writing.
 * - `.ogg / .opus`: Ogg Vorbis (`\x03vorbis`) & Opus (`OpusTags`) + `METADATA_BLOCK_PICTURE` reading & Ogg CRC-32 writing.
 * - `.m4a / .mp4`: MP4 `moov.udta.meta.ilst` (`©nam/©ART/©alb/aART/trkn/©day/©lyr/covr`) reading & writing,
 *   including exact `stco` (with 64-bit `co64` overflow upgrade) and `co64` chunk offset recalculation when `moov` precedes `mdat`.
 */
object AudioTagCodec {

    private const val STREAM_BUF_SIZE = 64 * 1024
    private const val MAX_FLAC_BLOCK_SIZE = 16 * 1024 * 1024 - 1
    private const val MP4_LATIN1 = "ISO-8859-1"
    private const val MP4_ATOM_COPY_SYM = "\u00A9"

    private val OGG_CRC_TABLE: IntArray = IntArray(256).also { table ->
        for (i in 0 until 256) {
            var r = i shl 24
            for (bit in 0 until 8) {
                r = if ((r and -0x80000000) != 0) {
                    (r shl 1) xor 0x04C11DB7
                } else {
                    r shl 1
                }
            }
            table[i] = r
        }
    }

    fun supportsWrite(fileName: String?, mimeType: String? = null): Boolean {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        if (ext in setOf("mp3", "flac", "ogg", "oga", "opus", "m4a", "mp4")) return true
        val mime = mimeType?.lowercase(Locale.ROOT).orEmpty()
        return mime in setOf(
            "audio/mpeg", "audio/mp3", "audio/flac", "audio/x-flac",
            "audio/ogg", "application/ogg", "audio/opus",
            "audio/mp4", "audio/x-m4a", "video/mp4",
        )
    }

    fun detectFormat(
        header: ByteArray,
        headerLen: Int = header.size,
        fileNameHint: String? = null,
        mimeTypeHint: String? = null,
    ): AudioContainerFormat {
        if (headerLen >= 4 &&
            header[0] == 'f'.code.toByte() &&
            header[1] == 'L'.code.toByte() &&
            header[2] == 'a'.code.toByte() &&
            header[3] == 'C'.code.toByte()
        ) {
            return AudioContainerFormat.FLAC
        }
        if (headerLen >= 4 &&
            header[0] == 'O'.code.toByte() &&
            header[1] == 'g'.code.toByte() &&
            header[2] == 'g'.code.toByte() &&
            header[3] == 'S'.code.toByte()
        ) {
            return AudioContainerFormat.OGG
        }
        if (headerLen >= 8) {
            val boxType = String(header, 4, 4, Charsets.ISO_8859_1)
            if (boxType in setOf("ftyp", "moov", "mdat", "free", "skip", "wide")) {
                return AudioContainerFormat.MP4
            }
        }
        if (headerLen >= 3 &&
            header[0] == 'I'.code.toByte() &&
            header[1] == 'D'.code.toByte() &&
            header[2] == '3'.code.toByte()
        ) {
            return AudioContainerFormat.MP3
        }
        if (headerLen >= 2 &&
            (header[0].toInt() and 0xFF) == 0xFF &&
            (header[1].toInt() and 0xE0) == 0xE0
        ) {
            return AudioContainerFormat.MP3
        }

        val ext = fileNameHint?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        return when (ext) {
            "mp3" -> AudioContainerFormat.MP3
            "flac" -> AudioContainerFormat.FLAC
            "ogg", "oga", "opus" -> AudioContainerFormat.OGG
            "m4a", "mp4", "m4v", "mov" -> AudioContainerFormat.MP4
            else -> {
                val mime = mimeTypeHint?.lowercase(Locale.ROOT).orEmpty()
                when {
                    mime == "audio/mpeg" || mime == "audio/mp3" -> AudioContainerFormat.MP3
                    mime.contains("flac") -> AudioContainerFormat.FLAC
                    mime.contains("ogg") || mime.contains("opus") -> AudioContainerFormat.OGG
                    mime == "audio/mp4" || mime == "audio/x-m4a" || mime == "video/mp4" -> AudioContainerFormat.MP4
                    else -> AudioContainerFormat.UNSUPPORTED
                }
            }
        }
    }

    internal interface SeekableByteSource {
        val size: Long
        fun readAt(position: Long, dest: ByteArray, offset: Int = 0, length: Int = dest.size - offset): Int
    }

    internal class FileChannelByteSource(
        private val channel: FileChannel,
        override val size: Long = channel.size(),
    ) : SeekableByteSource {
        override fun readAt(position: Long, dest: ByteArray, offset: Int, length: Int): Int {
            if (length <= 0 || position < 0L || position >= size) return -1
            val capped = minOf(length.toLong(), size - position).toInt()
            var total = 0
            while (total < capped) {
                val bb = ByteBuffer.wrap(dest, offset + total, capped - total)
                val n = channel.read(bb, position + total)
                if (n <= 0) break
                total += n
            }
            return if (total == 0) -1 else total
        }
    }

    /**
     * Positional POSIX `Os.pread` source over a `ParcelFileDescriptor`'s raw `FileDescriptor`.
     * Never wraps in `FileInputStream` / `FileChannel`, preventing Android 10+ `fdsan` (`SIGABRT`)
     * premature FD closure before `ParcelFileDescriptor.close()`.
     */
    internal class PosixFdByteSource(
        private val fd: FileDescriptor,
        override val size: Long,
        private val preadFn: (FileDescriptor, ByteArray, Int, Int, Long) -> Int = { d, b, off, len, pos ->
            Os.pread(d, b, off, len, pos)
        },
    ) : SeekableByteSource {
        override fun readAt(position: Long, dest: ByteArray, offset: Int, length: Int): Int {
            if (length <= 0 || position < 0L || position >= size) return -1
            val capped = minOf(length.toLong(), size - position).toInt()
            var total = 0
            while (total < capped) {
                val n = try {
                    preadFn(fd, dest, offset + total, capped - total, position + total)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    throw IOException("POSIX pread failed at offset ${position + total}", e)
                } catch (e: InterruptedIOException) {
                    val transferred = e.bytesTransferred.coerceAtLeast(0)
                    total += transferred
                    continue
                }
                if (n <= 0) break
                total += n
            }
            return if (total == 0) -1 else total
        }
    }

    internal class ByteArrayByteSource(
        private val data: ByteArray,
    ) : SeekableByteSource {
        override val size: Long get() = data.size.toLong()
        override fun readAt(position: Long, dest: ByteArray, offset: Int, length: Int): Int {
            if (length <= 0 || position < 0L || position >= data.size.toLong()) return -1
            val start = position.toInt()
            val n = minOf(length, data.size - start)
            System.arraycopy(data, start, dest, offset, n)
            return n
        }
    }

    private class SeekableSourceInputStream(
        private val src: SeekableByteSource,
        startPosition: Long = 0L,
    ) : InputStream() {
        var position: Long = startPosition
            private set

        override fun read(): Int {
            val one = ByteArray(1)
            val n = src.readAt(position, one, 0, 1)
            if (n <= 0) return -1
            position += 1L
            return one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = src.readAt(position, b, off, len)
            if (n <= 0) return -1
            position += n.toLong()
            return n
        }

        override fun skip(n: Long): Long {
            if (n <= 0L) return 0L
            val avail = (src.size - position).coerceAtLeast(0L)
            val step = minOf(n, avail)
            position += step
            return step
        }
    }

    private fun SeekableByteSource.readExactAt(position: Long, dest: ByteArray): Boolean {
        if (dest.isEmpty()) return true
        val n = readAt(position, dest, 0, dest.size)
        return n == dest.size
    }

    internal fun readTagsFromSource(
        source: SeekableByteSource,
        fileNameHint: String? = null,
        mimeTypeHint: String? = null,
    ): EmbeddedAudioTags {
        if (source.size <= 0L) return EmbeddedAudioTags()
        val head = ByteArray(16)
        val n = source.readAt(0L, head, 0, head.size).coerceAtLeast(0)
        return when (detectFormat(head, n, fileNameHint, mimeTypeHint)) {
            AudioContainerFormat.MP3 -> readMp3Tags(source)
            AudioContainerFormat.FLAC -> readFlacTags(SeekableSourceInputStream(source, 0L))
            AudioContainerFormat.OGG -> readOggTags(source)
            AudioContainerFormat.MP4 -> readMp4Tags(source)
            AudioContainerFormat.UNSUPPORTED -> EmbeddedAudioTags()
        }
    }

    fun readTags(
        channel: FileChannel,
        fileNameHint: String? = null,
        mimeTypeHint: String? = null,
    ): EmbeddedAudioTags {
        val sz = channel.size()
        if (sz <= 0L) return EmbeddedAudioTags()
        return readTagsFromSource(FileChannelByteSource(channel, sz), fileNameHint, mimeTypeHint)
    }

    fun readTags(
        file: File,
        fileNameHint: String? = file.name,
        mimeTypeHint: String? = null,
    ): EmbeddedAudioTags {
        if (!file.isFile || file.length() <= 0L) return EmbeddedAudioTags()
        return FileInputStream(file).use { fis ->
            readTags(fis.channel, fileNameHint, mimeTypeHint)
        }
    }

    /**
     * Reads embedded tags from a SAF [uri] via positional `Os.pread` over `ParcelFileDescriptor`
     * without copying audio payloads to `cacheDir` or violating Android 10+ `fdsan` FD ownership;
     * falls back to non-staging header stream reading if the provider returns a pipe/non-regular FD.
     */
    fun readTagsFromSafUri(
        context: Context,
        uri: Uri,
        fileNameHint: String? = null,
        mimeTypeHint: String? = null,
    ): EmbeddedAudioTags {
        val resolver = context.contentResolver
        val seekableTags = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val fd = pfd.fileDescriptor
                val st = Os.fstat(fd)
                if (OsConstants.S_ISREG(st.st_mode) && st.st_size > 0L) {
                    readTagsFromSource(PosixFdByteSource(fd, st.st_size), fileNameHint, mimeTypeHint)
                } else {
                    null
                }
            }
        }.getOrNull()
        if (seekableTags != null) return seekableTags

        return runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                readTags(stream, fileNameHint, mimeTypeHint)
            }
        }.getOrNull() ?: EmbeddedAudioTags()
    }

    fun readTags(
        inputStream: InputStream,
        fileNameHint: String? = null,
        mimeTypeHint: String? = null,
        @Suppress("UNUSED_PARAMETER") tempDir: File? = null,
    ): EmbeddedAudioTags {
        // Parse directly from leading header packets/atoms without staging full audio files to disk!
        val pb = PushbackInputStream(inputStream, 16)
        val head = ByteArray(16)
        val n = readUpTo(pb, head)
        if (n <= 0) return EmbeddedAudioTags()
        pb.unread(head, 0, n)
        return when (detectFormat(head, n, fileNameHint, mimeTypeHint)) {
            AudioContainerFormat.MP3 -> readMp3TagsFromStream(pb)
            AudioContainerFormat.FLAC -> readFlacTags(pb)
            AudioContainerFormat.OGG -> readOggTags(pb)
            AudioContainerFormat.MP4 -> readMp4TagsFromStream(pb)
            AudioContainerFormat.UNSUPPORTED -> EmbeddedAudioTags()
        }
    }

    fun readTags(
        bytes: ByteArray,
        fileNameHint: String? = null,
        mimeTypeHint: String? = null,
    ): EmbeddedAudioTags {
        if (bytes.isEmpty()) return EmbeddedAudioTags()
        return readTagsFromSource(ByteArrayByteSource(bytes), fileNameHint, mimeTypeHint)
    }

    /**
     * Writes updated tags from [sourceFile] into [tempOutputFile] and verifies output integrity
     * before returning (`DESIGN.md` §6.3 Three-Step Crash-Safe Protocol Steps 1 & 2).
     */
    fun writeTagsToTempFile(
        sourceFile: File,
        tempOutputFile: File,
        spec: TagWriteSpec,
        fileNameHint: String? = sourceFile.name,
        mimeTypeHint: String? = null,
    ) {
        if (!sourceFile.isFile || sourceFile.length() <= 0L) {
            throw IOException("Source audio file does not exist or is empty: ${sourceFile.path}")
        }
        tempOutputFile.parentFile?.mkdirs()
        val head = ByteArray(16)
        val n = FileInputStream(sourceFile).use { readUpTo(it, head) }
        val format = detectFormat(head, n, fileNameHint, mimeTypeHint)

        var succeeded = false
        try {
            when (format) {
                AudioContainerFormat.MP3 -> writeMp3File(sourceFile, tempOutputFile, spec)
                AudioContainerFormat.FLAC -> writeFlacFile(sourceFile, tempOutputFile, spec)
                AudioContainerFormat.OGG -> writeOggFile(sourceFile, tempOutputFile, spec)
                AudioContainerFormat.MP4 -> writeMp4File(sourceFile, tempOutputFile, spec)
                AudioContainerFormat.UNSUPPORTED ->
                    throw IOException("Unsupported audio container for tag write-back: ${fileNameHint ?: sourceFile.name}")
            }
            verifyAudioFileIntegrity(sourceFile, tempOutputFile, format, fileNameHint, mimeTypeHint)
            succeeded = true
        } finally {
            if (!succeeded) {
                tempOutputFile.delete()
            }
        }
    }

    /**
     * Crash-Safe Temp-Staged SAF Write-Back (`DESIGN.md` §6.3, `ARCHITECTURE.md` §4.4):
     * 1. Streams source SAF URI to staging area and builds rewritten `.tmp` file in `cacheDir/tag_stage/`.
     * 2. Verifies `.tmp` file container header, frame/atom payload integrity, and parseability.
     * 3. Opens SAF `ParcelFileDescriptor` in `"rw"` mode, seeks to 0, streams `.tmp`, calls
     *    `Os.ftruncate(fd, newLength)` + `Os.fsync(fd)`.
     *
     * @return Pair of `(newSizeBytes, updatedTimestampMs)`.
     */
    fun writeTagsToSafUri(
        context: Context,
        docUri: Uri,
        itemId: Long,
        fileName: String,
        mimeType: String,
        spec: TagWriteSpec,
    ): Pair<Long, Long> {
        val stageDir = File(context.cacheDir, "tag_stage").apply { mkdirs() }
        val srcCopy = File(stageDir, "src_${itemId}_${System.nanoTime()}.tmp")
        val stagedOut = File(stageDir, "${itemId}_${System.nanoTime()}.tmp")
        try {
            val resolver = context.contentResolver
            resolver.openInputStream(docUri)?.use { input ->
                FileOutputStream(srcCopy).use { output ->
                    input.copyTo(output, STREAM_BUF_SIZE)
                    output.flush()
                }
            } ?: throw IOException("Unable to open SAF InputStream for $docUri")

            writeTagsToTempFile(
                sourceFile = srcCopy,
                tempOutputFile = stagedOut,
                spec = spec,
                fileNameHint = fileName,
                mimeTypeHint = mimeType,
            )

            val newSize = stagedOut.length()
            val pfd = resolver.openFileDescriptor(docUri, "rw")
                ?: throw IOException("Unable to open writable SAF ParcelFileDescriptor for $docUri")

            pfd.use { descriptor ->
                val fd = descriptor.fileDescriptor
                Os.lseek(fd, 0L, OsConstants.SEEK_SET)
                FileInputStream(stagedOut).use { stagedIn ->
                    val buf = ByteArray(STREAM_BUF_SIZE)
                    while (true) {
                        val read = stagedIn.read(buf)
                        if (read < 0) break
                        if (read > 0) writeFullyToFd(fd, buf, 0, read)
                    }
                }
                Os.ftruncate(fd, newSize)
                Os.fsync(fd)
            }
            val actualLastModified = querySafLastModified(resolver, docUri)
                ?: System.currentTimeMillis()
            return newSize to actualLastModified
        } finally {
            srcCopy.delete()
            stagedOut.delete()
        }
    }

    private fun writeFullyToFd(fd: FileDescriptor, buf: ByteArray, offset: Int, length: Int) {
        var written = 0
        while (written < length) {
            val n = try {
                Os.write(fd, buf, offset + written, length - written)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                throw IOException("POSIX write failed after $written bytes", e)
            } catch (e: InterruptedIOException) {
                written += e.bytesTransferred.coerceAtLeast(0)
                continue
            }
            if (n <= 0) throw IOException("POSIX write returned $n with ${length - written} bytes remaining")
            written += n
        }
    }

    private fun querySafLastModified(
        resolver: android.content.ContentResolver,
        docUri: Uri,
    ): Long? = runCatching {
        resolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                cursor.getLong(0).takeIf { it > 0L }
            } else {
                null
            }
        }
    }.getOrNull()

    fun verifyAudioFileIntegrity(
        originalFile: File,
        stagedFile: File,
        format: AudioContainerFormat,
        fileNameHint: String? = null,
        mimeTypeHint: String? = null,
    ) {
        if (!stagedFile.isFile || stagedFile.length() <= 0L) {
            throw IOException("Staged audio file is empty")
        }
        val head = ByteArray(16)
        val n = FileInputStream(stagedFile).use { readUpTo(it, head) }
        val detected = detectFormat(head, n, fileNameHint, mimeTypeHint)
        if (detected != format) {
            throw IOException("Staged audio container mismatch: expected $format, got $detected")
        }

        when (format) {
            AudioContainerFormat.MP3 -> {
                val origBounds = locateMp3AudioBounds(originalFile)
                val newBounds = locateMp3AudioBounds(stagedFile)
                val origAudioLen = origBounds.second - origBounds.first
                val newAudioLen = newBounds.second - newBounds.first
                if (origAudioLen != newAudioLen || newAudioLen < 0L) {
                    throw IOException("MP3 audio payload length altered ($origAudioLen != $newAudioLen)")
                }
            }
            AudioContainerFormat.FLAC -> {
                val origAudioLen = locateFlacAudioLength(originalFile)
                val newAudioLen = locateFlacAudioLength(stagedFile)
                if (origAudioLen != newAudioLen || newAudioLen < 0L) {
                    throw IOException("FLAC audio frames length altered ($origAudioLen != $newAudioLen)")
                }
            }
            AudioContainerFormat.MP4 -> {
                val origBoxes = RandomAccessFile(originalFile, "r").use { parseTopLevelMp4Boxes(it) }
                val newBoxes = RandomAccessFile(stagedFile, "r").use { parseTopLevelMp4Boxes(it) }
                val sumSizes = newBoxes.sumOf { it.size }
                if (sumSizes != stagedFile.length()) {
                    throw IOException("MP4 top-level box sizes ($sumSizes) != file length (${stagedFile.length()})")
                }
                val origMdatTotal = origBoxes.filter { it.type == "mdat" }.sumOf { it.size - it.headerSize }
                val newMdatTotal = newBoxes.filter { it.type == "mdat" }.sumOf { it.size - it.headerSize }
                if (origMdatTotal != newMdatTotal) {
                    throw IOException("MP4 mdat payload length altered ($origMdatTotal != $newMdatTotal)")
                }
                if (newBoxes.none { it.type == "moov" }) {
                    throw IOException("Staged MP4 missing moov atom")
                }
            }
            AudioContainerFormat.OGG -> {
                if (n < 4 || String(head, 0, 4, Charsets.ISO_8859_1) != "OggS") {
                    throw IOException("Staged Ogg missing OggS capture pattern")
                }
            }
            AudioContainerFormat.UNSUPPORTED -> throw IOException("Unsupported format")
        }

        // Round-trip parse verification
        readTags(stagedFile, fileNameHint, mimeTypeHint)
    }

    // =========================================================================
    // 1. MP3 (ID3v2.2 / ID3v2.3 / ID3v2.4 + ID3v1 + MPEG duration)
    // =========================================================================

    internal data class RawId3Frame(
        val id: String,
        val payload: ByteArray,
    )

    private fun locateMp3AudioBounds(source: SeekableByteSource): Pair<Long, Long> {
        val len = source.size
        var audioStart = 0L
        if (len >= 10L) {
            val hdr = ByteArray(10)
            if (source.readExactAt(0L, hdr) &&
                hdr[0] == 'I'.code.toByte() &&
                hdr[1] == 'D'.code.toByte() &&
                hdr[2] == '3'.code.toByte()
            ) {
                val flags = hdr[5].toInt() and 0xFF
                val tagSize = decodeSynchsafeInt(hdr, 6).toLong()
                val footerSize = if ((flags and 0x10) != 0) 10L else 0L
                audioStart = (10L + tagSize + footerSize).coerceAtMost(len)
            }
        }
        var audioEnd = len
        if (len - audioStart >= 128L) {
            val v1 = ByteArray(3)
            if (source.readExactAt(len - 128L, v1) &&
                v1[0] == 'T'.code.toByte() &&
                v1[1] == 'A'.code.toByte() &&
                v1[2] == 'G'.code.toByte()
            ) {
                audioEnd = len - 128L
            }
        }
        return audioStart to audioEnd
    }

    private fun locateMp3AudioBounds(file: File): Pair<Long, Long> =
        FileInputStream(file).use { locateMp3AudioBounds(FileChannelByteSource(it.channel)) }

    private fun readMp3Tags(file: File): EmbeddedAudioTags =
        FileInputStream(file).use { readMp3Tags(FileChannelByteSource(it.channel)) }

    private fun readMp3Tags(source: SeekableByteSource): EmbeddedAudioTags {
        val frames = parseExistingId3v2Frames(source)
        val parsed = extractId3FieldsFromFrames(frames)
        var title = parsed.title
        var artist = parsed.artist
        var album = parsed.album
        var trackNum = parsed.trackNumber
        var year = parsed.year
        var durationMs = parsed.durationMs

        // ID3v1 fallback if primary fields missing (reads only trailing 128 bytes)
        if (title == null && artist == null && album == null && source.size >= 128L) {
            val v1 = ByteArray(128)
            if (source.readExactAt(source.size - 128L, v1) &&
                v1[0] == 'T'.code.toByte() &&
                v1[1] == 'A'.code.toByte() &&
                v1[2] == 'G'.code.toByte()
            ) {
                title = decodeFixedId3v1String(v1, 3, 30)
                artist = decodeFixedId3v1String(v1, 33, 30)
                album = decodeFixedId3v1String(v1, 63, 30)
                year = parseYear(decodeFixedId3v1String(v1, 93, 4))
                if (v1[125].toInt() == 0 && v1[126].toInt() != 0) {
                    trackNum = v1[126].toInt() and 0xFF
                }
            }
        }

        if (durationMs == null || durationMs <= 0L) {
            durationMs = estimateMp3DurationMs(source)
        }

        return parsed.copy(
            title = title,
            artist = artist,
            album = album,
            trackNumber = trackNum,
            year = year,
            durationMs = durationMs,
        )
    }

    private fun readMp3TagsFromStream(input: InputStream): EmbeddedAudioTags {
        val head = ByteArray(10)
        val n = readFully(input, head)
        if (n < 10) return EmbeddedAudioTags()

        var frames: List<RawId3Frame> = emptyList()
        val probe: ByteArray
        if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            val flags = head[5].toInt() and 0xFF
            val tagSize = decodeSynchsafeInt(head, 6)
            if (tagSize in 1..(32 * 1024 * 1024)) {
                val tagBody = ByteArray(tagSize)
                if (readFully(input, tagBody) == tagSize) {
                    frames = parseId3v2HeaderAndBody(head, tagBody)
                }
            } else if (tagSize > 0) {
                skipFully(input, tagSize.toLong())
            }
            if ((flags and 0x10) != 0) skipFully(input, 10L)
            val p = ByteArray(8192)
            val pn = readFully(input, p)
            probe = if (pn == p.size) p else p.copyOf(pn.coerceAtLeast(0))
        } else {
            val rest = ByteArray(8182)
            val rn = readFully(input, rest)
            probe = ByteArray(n + rn.coerceAtLeast(0))
            System.arraycopy(head, 0, probe, 0, n)
            if (rn > 0) System.arraycopy(rest, 0, probe, n, rn)
        }

        val parsed = extractId3FieldsFromFrames(frames)
        val durationMs = parsed.durationMs?.takeIf { it > 0L } ?: estimateMp3DurationFromProbe(probe, audioBytes = -1L)
        return parsed.copy(durationMs = durationMs)
    }

    private fun extractId3FieldsFromFrames(frames: List<RawId3Frame>): EmbeddedAudioTags {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var trackNum: Int? = null
        var year: Int? = null
        var lyrics: String? = null
        var coverBytes: ByteArray? = null
        var coverMime: String? = null
        var durationMs: Long? = null

        for (frame in frames) {
            when (frame.id) {
                "TIT2", "TT2" -> if (title == null) title = decodeId3TextPayload(frame.payload)
                "TPE1", "TP1" -> if (artist == null) artist = decodeId3TextPayload(frame.payload)
                "TALB", "TAL" -> if (album == null) album = decodeId3TextPayload(frame.payload)
                "TPE2", "TP2" -> if (albumArtist == null) albumArtist = decodeId3TextPayload(frame.payload)
                "TRCK", "TRK" -> if (trackNum == null) trackNum = parseTrackNumber(decodeId3TextPayload(frame.payload))
                "TDRC", "TYER", "TYE" -> if (year == null) year = parseYear(decodeId3TextPayload(frame.payload))
                "TLEN", "TLE" -> if (durationMs == null) durationMs = decodeId3TextPayload(frame.payload)?.trim()?.toLongOrNull()
                "USLT", "ULT" -> if (lyrics == null) lyrics = decodeId3UsltPayload(frame.payload)
                "APIC" -> {
                    val parsedApic = decodeId3ApicPayload(frame.payload, isV22 = false)
                    if (parsedApic != null && (coverBytes == null || parsedApic.first == 3)) {
                        coverBytes = parsedApic.third
                        coverMime = parsedApic.second
                    }
                }
                "PIC" -> {
                    val parsedPic = decodeId3ApicPayload(frame.payload, isV22 = true)
                    if (parsedPic != null && (coverBytes == null || parsedPic.first == 3)) {
                        coverBytes = parsedPic.third
                        coverMime = parsedPic.second
                    }
                }
            }
        }
        return EmbeddedAudioTags(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            trackNumber = trackNum,
            year = year,
            lyrics = lyrics,
            coverBytes = coverBytes,
            coverMimeType = coverMime,
            durationMs = durationMs,
        )
    }

    private fun parseExistingId3v2Frames(file: File): List<RawId3Frame> =
        FileInputStream(file).use { parseExistingId3v2Frames(FileChannelByteSource(it.channel)) }

    private fun parseExistingId3v2Frames(source: SeekableByteSource): List<RawId3Frame> {
        if (source.size < 10L) return emptyList()
        val hdr = ByteArray(10)
        if (!source.readExactAt(0L, hdr)) return emptyList()
        if (hdr[0] != 'I'.code.toByte() || hdr[1] != 'D'.code.toByte() || hdr[2] != '3'.code.toByte()) {
            return emptyList()
        }
        val tagSize = decodeSynchsafeInt(hdr, 6)
        if (tagSize <= 0 || tagSize > 32 * 1024 * 1024 || 10L + tagSize.toLong() > source.size) return emptyList()
        val rawBody = ByteArray(tagSize)
        if (!source.readExactAt(10L, rawBody)) return emptyList()
        return parseId3v2HeaderAndBody(hdr, rawBody)
    }

    internal fun parseId3v2HeaderAndBody(hdr: ByteArray, rawTagBody: ByteArray): List<RawId3Frame> {
        val version = hdr[3].toInt() and 0xFF
        if (version !in 2..4) return emptyList()
        val flags = hdr[5].toInt() and 0xFF

        val tagUnsync = (flags and 0x80) != 0
        val tagBody = if (tagUnsync && version <= 3) {
            removeUnsynchronisation(rawTagBody)
        } else {
            rawTagBody
        }

        var pos = 0
        if ((flags and 0x40) != 0) {
            if (pos + 4 > tagBody.size) return emptyList()
            val extSkipLong = if (version == 4) {
                decodeSynchsafeInt(tagBody, pos).toLong().coerceAtLeast(4L)
            } else {
                4L + readUInt32BE(tagBody, pos)
            }
            if (extSkipLong < 4L || extSkipLong > (tagBody.size - pos).toLong()) return emptyList()
            pos += extSkipLong.toInt()
        }

        val out = ArrayList<RawId3Frame>()
        while (pos < tagBody.size) {
            if (tagBody[pos].toInt() == 0) break // Padding reached
            if (version == 2) {
                if (pos + 6 > tagBody.size) break
                val id = String(tagBody, pos, 3, Charsets.ISO_8859_1)
                if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
                val fSize = ((tagBody[pos + 3].toInt() and 0xFF) shl 16) or
                    ((tagBody[pos + 4].toInt() and 0xFF) shl 8) or
                    (tagBody[pos + 5].toInt() and 0xFF)
                pos += 6
                if (fSize <= 0 || fSize > tagBody.size - pos) break
                val payload = tagBody.copyOfRange(pos, pos + fSize)
                pos += fSize
                out.add(RawId3Frame(id, payload))
            } else {
                if (pos + 10 > tagBody.size) break
                val id = String(tagBody, pos, 4, Charsets.ISO_8859_1)
                if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
                val fSizeLong = if (version == 4) {
                    decodeSynchsafeInt(tagBody, pos + 4).toLong()
                } else {
                    readUInt32BE(tagBody, pos + 4)
                }
                val flag2 = tagBody[pos + 9].toInt() and 0xFF
                pos += 10
                if (fSizeLong < 0L || fSizeLong > (tagBody.size - pos).toLong()) break
                val fSize = fSizeLong.toInt()
                if (fSize == 0) continue

                var payload = tagBody.copyOfRange(pos, pos + fSize)
                pos += fSize

                if (version == 3) {
                    // ID3v2.3: 0x80 = compression, 0x40 = encryption, 0x20 = grouping identity
                    if ((flag2 and 0xC0) != 0) continue
                    if ((flag2 and 0x20) != 0) {
                        if (payload.isEmpty()) continue
                        payload = payload.copyOfRange(1, payload.size)
                    }
                } else if (version == 4) {
                    // ID3v2.4: 0x08 = compression, 0x04 = encryption, 0x40 = grouping, 0x02 = unsync, 0x01 = data len
                    if ((flag2 and 0x0C) != 0) continue
                    if ((flag2 and 0x02) != 0 || tagUnsync) {
                        payload = removeUnsynchronisation(payload)
                    }
                    var subOffset = 0
                    if ((flag2 and 0x40) != 0) {
                        subOffset += 1 // Grouping identity byte
                    }
                    if ((flag2 and 0x01) != 0) {
                        subOffset += 4 // Data length indicator
                    }
                    if (subOffset > payload.size) continue
                    if (subOffset > 0) {
                        payload = payload.copyOfRange(subOffset, payload.size)
                    }
                }
                out.add(RawId3Frame(id, payload))
            }
        }
        return out
    }

    private fun writeMp3File(sourceFile: File, destFile: File, spec: TagWriteSpec) {
        val existingFrames = parseExistingId3v2Frames(sourceFile)
        val currentTags = readMp3Tags(sourceFile)

        val newTitle = resolveField(spec.title, currentTags.title)
        val newArtist = resolveField(spec.artist, currentTags.artist)
        val newAlbum = resolveField(spec.album, currentTags.album)
        val newAlbumArtist = resolveField(spec.albumArtist, currentTags.albumArtist)
        val newTrack = resolveField(spec.trackNumber, currentTags.trackNumber)
        val newYear = resolveField(spec.year, currentTags.year)
        val existingCover = if (currentTags.coverBytes != null) {
            CoverImage(currentTags.coverBytes, currentTags.coverMimeType ?: CoverImage.detectImageMimeType(currentTags.coverBytes))
        } else {
            null
        }
        val newCover = resolveField(spec.cover, existingCover)

        val managedIds = setOf(
            "TIT2", "TT2",
            "TPE1", "TP1",
            "TALB", "TAL",
            "TPE2", "TP2",
            "TRCK", "TRK",
            "TDRC", "TYER", "TYE",
            "APIC", "PIC",
        )

        val preservedFrames = existingFrames.filter { frame ->
            frame.id.length == 4 && frame.id !in managedIds
        }

        val outputFrames = ArrayList<RawId3Frame>()
        newTitle?.takeIf { it.isNotBlank() }?.let { outputFrames.add(buildId3Utf8TextFrame("TIT2", it)) }
        newArtist?.takeIf { it.isNotBlank() }?.let { outputFrames.add(buildId3Utf8TextFrame("TPE1", it)) }
        newAlbum?.takeIf { it.isNotBlank() }?.let { outputFrames.add(buildId3Utf8TextFrame("TALB", it)) }
        newAlbumArtist?.takeIf { it.isNotBlank() }?.let { outputFrames.add(buildId3Utf8TextFrame("TPE2", it)) }
        newTrack?.takeIf { it > 0 }?.let { outputFrames.add(buildId3Utf8TextFrame("TRCK", it.toString())) }
        newYear?.takeIf { it > 0 }?.let { outputFrames.add(buildId3Utf8TextFrame("TDRC", it.toString())) }
        outputFrames.addAll(preservedFrames)
        if (newCover != null && newCover.bytes.isNotEmpty()) {
            outputFrames.add(buildId3ApicFrame(newCover))
        }

        val framesBaos = ByteArrayOutputStream()
        for (frame in outputFrames) {
            val idBytes = frame.id.toByteArray(Charsets.ISO_8859_1)
            if (idBytes.size != 4) continue
            framesBaos.write(idBytes)
            framesBaos.write(encodeSynchsafeInt(frame.payload.size))
            framesBaos.write(0) // status flags
            framesBaos.write(0) // format flags
            framesBaos.write(frame.payload)
        }
        // Add small 128-byte zero padding for future edits
        framesBaos.write(ByteArray(128))
        val tagPayload = framesBaos.toByteArray()

        val (audioStart, audioEnd) = locateMp3AudioBounds(sourceFile)

        FileOutputStream(destFile).use { out ->
            // ID3v2.4 header
            out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0))
            out.write(encodeSynchsafeInt(tagPayload.size))
            out.write(tagPayload)

            RandomAccessFile(sourceFile, "r").use { raf ->
                streamRange(raf, audioStart, audioEnd - audioStart, out)
            }
            out.flush()
            out.fd.sync()
        }
    }

    private fun buildId3Utf8TextFrame(id: String, value: String): RawId3Frame {
        val textBytes = value.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + textBytes.size)
        payload[0] = 3 // UTF-8 encoding in ID3v2.4
        System.arraycopy(textBytes, 0, payload, 1, textBytes.size)
        return RawId3Frame(id, payload)
    }

    private fun buildId3ApicFrame(cover: CoverImage): RawId3Frame {
        val mimeBytes = cover.mimeType.ifBlank { "image/jpeg" }.toByteArray(Charsets.ISO_8859_1)
        val baos = ByteArrayOutputStream(4 + mimeBytes.size + cover.bytes.size)
        baos.write(3) // UTF-8 description encoding
        baos.write(mimeBytes)
        baos.write(0) // null terminator for MIME
        baos.write(3) // Picture type 3 = Cover (front)
        baos.write(0) // Empty null-terminated description
        baos.write(cover.bytes)
        return RawId3Frame("APIC", baos.toByteArray())
    }

    private fun decodeId3TextPayload(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val encoding = payload[0].toInt() and 0xFF
        val body = payload.copyOfRange(1, payload.size)
        val raw = decodeId3EncodedString(body, encoding)
        // ID3v2.4 supports multiple values separated by \u0000
        val parts = raw.split('\u0000').map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.isEmpty()) null else parts.joinToString(" / ")
    }

    private fun decodeId3UsltPayload(payload: ByteArray): String? {
        if (payload.size < 5) return null
        val encoding = payload[0].toInt() and 0xFF
        // Bytes 1..3 are 3-char ISO-639-2 language
        val descEnd = findNullTerminatorEnd(payload, 4, encoding)
        if (descEnd >= payload.size) return null
        val lyricsBytes = payload.copyOfRange(descEnd, payload.size)
        return decodeId3EncodedString(lyricsBytes, encoding).trim('\u0000', ' ', '\r', '\n').takeIf { it.isNotEmpty() }
    }

    private fun decodeId3ApicPayload(payload: ByteArray, isV22: Boolean): Triple<Int, String, ByteArray>? {
        if (payload.size < 6) return null
        val encoding = payload[0].toInt() and 0xFF
        var cursor: Int
        val mime: String
        if (isV22) {
            if (payload.size < 6) return null
            val fmt = String(payload, 1, 3, Charsets.ISO_8859_1).lowercase(Locale.ROOT)
            mime = if (fmt == "png") "image/png" else "image/jpeg"
            cursor = 4
        } else {
            var nul = 1
            while (nul < payload.size && payload[nul].toInt() != 0) nul++
            mime = String(payload, 1, nul - 1, Charsets.ISO_8859_1).trim().ifEmpty { "image/jpeg" }
            cursor = (nul + 1).coerceAtMost(payload.size)
        }
        if (cursor >= payload.size) return null
        val picType = payload[cursor].toInt() and 0xFF
        cursor += 1
        val imgStart = findNullTerminatorEnd(payload, cursor, encoding)
        if (imgStart >= payload.size) return null
        val imgBytes = payload.copyOfRange(imgStart, payload.size)
        if (imgBytes.isEmpty()) return null
        return Triple(picType, mime, imgBytes)
    }

    private fun findNullTerminatorEnd(buf: ByteArray, start: Int, encoding: Int): Int {
        if (encoding == 1 || encoding == 2) {
            var i = start
            while (i + 1 < buf.size) {
                if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0) return i + 2
                i += 2
            }
            return buf.size
        } else {
            var i = start
            while (i < buf.size) {
                if (buf[i].toInt() == 0) return i + 1
                i++
            }
            return buf.size
        }
    }

    private fun decodeId3EncodedString(bytes: ByteArray, encoding: Int): String {
        if (bytes.isEmpty()) return ""
        return when (encoding) {
            0 -> String(bytes, Charsets.ISO_8859_1)
            1 -> {
                if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                    String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                } else if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                    String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                } else {
                    String(bytes, Charsets.UTF_16LE)
                }
            }
            2 -> String(bytes, Charsets.UTF_16BE)
            else -> String(bytes, Charsets.UTF_8)
        }
    }

    private fun decodeFixedId3v1String(buf: ByteArray, offset: Int, len: Int): String? {
        var end = offset
        val limit = offset + len
        while (end < limit && buf[end].toInt() != 0) end++
        if (end <= offset) return null
        return String(buf, offset, end - offset, Charsets.ISO_8859_1).trim().takeIf { it.isNotEmpty() }
    }

    private fun removeUnsynchronisation(src: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(src.size)
        var i = 0
        while (i < src.size) {
            val b = src[i].toInt() and 0xFF
            out.write(b)
            if (b == 0xFF && i + 1 < src.size && src[i + 1].toInt() == 0x00) {
                i += 2
            } else {
                i += 1
            }
        }
        return out.toByteArray()
    }

    private fun estimateMp3DurationMs(source: SeekableByteSource): Long? {
        val (audioStart, audioEnd) = locateMp3AudioBounds(source)
        val audioBytes = audioEnd - audioStart
        if (audioBytes < 4L) return null
        val probeLen = minOf(audioBytes, 8192L).toInt()
        val probe = ByteArray(probeLen)
        val n = source.readAt(audioStart, probe, 0, probeLen)
        if (n < 4) return null
        val slice = if (n == probe.size) probe else probe.copyOf(n)
        return estimateMp3DurationFromProbe(slice, audioBytes)
    }

    private fun estimateMp3DurationFromProbe(probe: ByteArray, audioBytes: Long): Long? {
        if (probe.size < 4) return null
        for (i in 0..probe.size - 4) {
            val b0 = probe[i].toInt() and 0xFF
            val b1 = probe[i + 1].toInt() and 0xFF
            if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) continue
            val versionBits = (b1 ushr 3) and 0x03
            val layerBits = (b1 ushr 1) and 0x03
            if (versionBits == 1 || layerBits != 1) continue // MPEG Layer III
            val b2 = probe[i + 2].toInt() and 0xFF
            val bitrateIdx = (b2 ushr 4) and 0x0F
            val srIdx = (b2 ushr 2) and 0x03
            if (bitrateIdx == 0 || bitrateIdx == 15 || srIdx == 3) continue

            val isMpeg1 = versionBits == 3
            val sampleRate = when (srIdx) {
                0 -> if (isMpeg1) 44100 else if (versionBits == 2) 22050 else 11025
                1 -> if (isMpeg1) 48000 else if (versionBits == 2) 24000 else 12000
                else -> if (isMpeg1) 32000 else if (versionBits == 2) 16000 else 8000
            }
            val bitrateKbps = if (isMpeg1) {
                intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)[bitrateIdx]
            } else {
                intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)[bitrateIdx]
            }
            val samplesPerFrame = if (isMpeg1) 1152L else 576L

            // Check for Xing / Info VBR frame count header inside first frame
            val channelMode = (probe[i + 3].toInt() ushr 6) and 0x03
            val xingOffset = i + 4 + if (isMpeg1) {
                if (channelMode == 3) 17 else 32
            } else {
                if (channelMode == 3) 9 else 17
            }
            if (xingOffset + 12 <= probe.size) {
                val tag = String(probe, xingOffset, 4, Charsets.ISO_8859_1)
                if (tag == "Xing" || tag == "Info") {
                    val xingFlags = readInt32BE(probe, xingOffset + 4)
                    if ((xingFlags and 0x01) != 0) {
                        val totalFrames = readUInt32BE(probe, xingOffset + 8)
                        if (totalFrames > 0L) {
                            return (totalFrames * samplesPerFrame * 1000L) / sampleRate
                        }
                    }
                }
            }
            if (bitrateKbps > 0 && audioBytes > 0L) {
                return (audioBytes * 8L) / bitrateKbps
            }
        }
        return null
    }

    // =========================================================================
    // 2. FLAC (STREAMINFO + VORBIS_COMMENT + METADATA_BLOCK_PICTURE)
    // =========================================================================

    private data class FlacBlock(
        val type: Int,
        val data: ByteArray,
    )

    private data class VorbisCommentData(
        val vendor: String,
        val entries: List<Pair<String, String>>,
    )

    private fun locateFlacAudioLength(file: File): Long {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 8L) return -1L
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, Charsets.ISO_8859_1) != "fLaC") return -1L
            val hdr = ByteArray(4)
            while (true) {
                if (raf.filePointer + 4L > raf.length()) return -1L
                raf.readFully(hdr)
                val isLast = (hdr[0].toInt() and 0x80) != 0
                val len = ((hdr[1].toInt() and 0xFF) shl 16) or
                    ((hdr[2].toInt() and 0xFF) shl 8) or
                    (hdr[3].toInt() and 0xFF)
                if (raf.filePointer + len > raf.length()) return -1L
                raf.seek(raf.filePointer + len)
                if (isLast) {
                    return raf.length() - raf.filePointer
                }
            }
        }
    }

    private fun readFlacTags(input: InputStream): EmbeddedAudioTags {
        val magic = ByteArray(4)
        if (readFully(input, magic) < 4 || String(magic, Charsets.ISO_8859_1) != "fLaC") {
            return EmbeddedAudioTags()
        }
        var durationMs: Long? = null
        var vc: VorbisCommentData? = null
        var bestCover: Pair<String, ByteArray>? = null
        var foundFrontCover = false

        val hdr = ByteArray(4)
        while (true) {
            if (readFully(input, hdr) < 4) break
            val isLast = (hdr[0].toInt() and 0x80) != 0
            val blockType = hdr[0].toInt() and 0x7F
            val len = ((hdr[1].toInt() and 0xFF) shl 16) or
                ((hdr[2].toInt() and 0xFF) shl 8) or
                (hdr[3].toInt() and 0xFF)

            if (blockType in setOf(0, 4, 6) && len in 0..MAX_FLAC_BLOCK_SIZE) {
                val body = ByteArray(len)
                if (readFully(input, body) < len) break
                when (blockType) {
                    0 -> if (body.size >= 34) {
                        val sampleRate = ((body[10].toInt() and 0xFF) shl 12) or
                            ((body[11].toInt() and 0xFF) shl 4) or
                            ((body[12].toInt() and 0xF0) ushr 4)
                        val totalSamples = ((body[13].toLong() and 0x0FL) shl 32) or readUInt32BE(body, 14)
                        if (sampleRate > 0 && totalSamples > 0L) {
                            durationMs = (totalSamples * 1000L) / sampleRate
                        }
                    }
                    4 -> vc = parseVorbisCommentBlock(body)
                    6 -> {
                        val pic = parseFlacPictureBlock(body)
                        if (pic != null && (!foundFrontCover || pic.first == 3)) {
                            bestCover = pic.second to pic.third
                            if (pic.first == 3) foundFrontCover = true
                        }
                    }
                }
            } else {
                skipFully(input, len.toLong())
            }
            if (isLast) break
        }

        return buildTagsFromVorbis(vc, bestCover, durationMs)
    }

    private fun writeFlacFile(sourceFile: File, destFile: File, spec: TagWriteSpec) {
        RandomAccessFile(sourceFile, "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, Charsets.ISO_8859_1) != "fLaC") {
                throw IOException("Invalid FLAC header")
            }

            val blocks = ArrayList<FlacBlock>()
            val hdr = ByteArray(4)
            while (true) {
                raf.readFully(hdr)
                val isLast = (hdr[0].toInt() and 0x80) != 0
                val blockType = hdr[0].toInt() and 0x7F
                val len = ((hdr[1].toInt() and 0xFF) shl 16) or
                    ((hdr[2].toInt() and 0xFF) shl 8) or
                    (hdr[3].toInt() and 0xFF)
                val data = ByteArray(len)
                raf.readFully(data)
                if (blockType != 1) { // Drop old PADDING blocks; we'll manage clean blocks
                    blocks.add(FlacBlock(blockType, data))
                }
                if (isLast) break
            }
            val audioStart = raf.filePointer
            val audioLen = raf.length() - audioStart

            if (blocks.isEmpty() || blocks.first().type != 0) {
                throw IOException("FLAC file missing leading STREAMINFO block")
            }

            val existingVc = blocks.firstOrNull { it.type == 4 }?.let { parseVorbisCommentBlock(it.data) }
                ?: VorbisCommentData("MiniPlayer 1.0.0", emptyList())

            val existingPic = blocks.filter { it.type == 6 }
                .mapNotNull { parseFlacPictureBlock(it.data) }
                .firstOrNull { it.first == 3 }
                ?: blocks.filter { it.type == 6 }.firstNotNullOfOrNull { parseFlacPictureBlock(it.data) }

            val updatedVcBytes = encodeUpdatedVorbisCommentBlock(existingVc, spec, includePictureComment = false, existingCover = null)

            val outputBlocks = ArrayList<FlacBlock>()
            outputBlocks.add(blocks.first()) // STREAMINFO MUST be first
            for (b in blocks.drop(1)) {
                if (b.type == 4) continue
                if (b.type == 6 && spec.cover is OptionalField.Set) continue
                outputBlocks.add(b)
            }
            outputBlocks.add(FlacBlock(4, updatedVcBytes))

            when (val cov = spec.cover) {
                is OptionalField.Unchanged -> {
                    // Existing type-6 blocks were already kept above
                }
                is OptionalField.Set -> {
                    val img = cov.value
                    if (img != null && img.bytes.isNotEmpty()) {
                        val picBytes = encodeFlacPictureBlock(img)
                        if (picBytes.size > MAX_FLAC_BLOCK_SIZE) {
                            throw IOException("Cover image exceeds 16 MiB FLAC block limit")
                        }
                        outputBlocks.add(FlacBlock(6, picBytes))
                    }
                }
            }

            FileOutputStream(destFile).use { out ->
                out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
                for ((idx, blk) in outputBlocks.withIndex()) {
                    val isLast = idx == outputBlocks.lastIndex
                    val firstByte = (if (isLast) 0x80 else 0x00) or (blk.type and 0x7F)
                    val len = blk.data.size
                    out.write(firstByte)
                    out.write((len ushr 16) and 0xFF)
                    out.write((len ushr 8) and 0xFF)
                    out.write(len and 0xFF)
                    out.write(blk.data)
                }
                streamRange(raf, audioStart, audioLen, out)
                out.flush()
                out.fd.sync()
            }
        }
    }

    private fun parseVorbisCommentBlock(bytes: ByteArray): VorbisCommentData {
        if (bytes.size < 8) return VorbisCommentData("MiniPlayer", emptyList())
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val vendorLen = bb.int
        if (vendorLen < 0 || vendorLen > bb.remaining()) return VorbisCommentData("MiniPlayer", emptyList())
        val vendorBytes = ByteArray(vendorLen)
        bb.get(vendorBytes)
        val vendor = String(vendorBytes, Charsets.UTF_8)

        if (bb.remaining() < 4) return VorbisCommentData(vendor, emptyList())
        val count = bb.int.coerceAtLeast(0)
        val entries = ArrayList<Pair<String, String>>(minOf(count, 256))
        for (i in 0 until count) {
            if (bb.remaining() < 4) break
            val cLen = bb.int
            if (cLen < 0 || cLen > bb.remaining()) break
            val cBytes = ByteArray(cLen)
            bb.get(cBytes)
            val str = String(cBytes, Charsets.UTF_8)
            val eq = str.indexOf('=')
            if (eq > 0) {
                val key = str.substring(0, eq).trim().uppercase(Locale.ROOT)
                val value = str.substring(eq + 1)
                entries.add(key to value)
            }
        }
        return VorbisCommentData(vendor, entries)
    }

    private fun encodeUpdatedVorbisCommentBlock(
        existing: VorbisCommentData,
        spec: TagWriteSpec,
        includePictureComment: Boolean,
        existingCover: CoverImage?,
    ): ByteArray {
        val managedKeys = setOf(
            "TITLE",
            "ARTIST",
            "ALBUM",
            "ALBUMARTIST",
            "ALBUM ARTIST",
            "TRACKNUMBER",
            "DATE",
            "YEAR",
        )
        val currentMap = LinkedHashMap<String, MutableList<String>>()
        for ((k, v) in existing.entries) {
            currentMap.getOrPut(k) { mutableListOf() }.add(v)
        }

        val curTitle = currentMap["TITLE"]?.firstOrNull()
        val curArtist = currentMap["ARTIST"]?.takeIf { it.isNotEmpty() }?.joinToString(" / ")
        val curAlbum = currentMap["ALBUM"]?.firstOrNull()
        val curAlbumArtist = currentMap["ALBUMARTIST"]?.firstOrNull() ?: currentMap["ALBUM ARTIST"]?.firstOrNull()
        val curTrack = parseTrackNumber(currentMap["TRACKNUMBER"]?.firstOrNull())
        val curYear = parseYear(currentMap["DATE"]?.firstOrNull() ?: currentMap["YEAR"]?.firstOrNull())

        val newTitle = resolveField(spec.title, curTitle)
        val newArtist = resolveField(spec.artist, curArtist)
        val newAlbum = resolveField(spec.album, curAlbum)
        val newAlbumArtist = resolveField(spec.albumArtist, curAlbumArtist)
        val newTrack = resolveField(spec.trackNumber, curTrack)
        val newYear = resolveField(spec.year, curYear)

        val outEntries = ArrayList<Pair<String, String>>()
        newTitle?.takeIf { it.isNotBlank() }?.let { outEntries.add("TITLE" to it) }
        newArtist?.takeIf { it.isNotBlank() }?.let { outEntries.add("ARTIST" to it) }
        newAlbum?.takeIf { it.isNotBlank() }?.let { outEntries.add("ALBUM" to it) }
        newAlbumArtist?.takeIf { it.isNotBlank() }?.let { outEntries.add("ALBUMARTIST" to it) }
        newTrack?.takeIf { it > 0 }?.let { outEntries.add("TRACKNUMBER" to it.toString()) }
        newYear?.takeIf { it > 0 }?.let { outEntries.add("DATE" to it.toString()) }

        for ((k, v) in existing.entries) {
            if (k in managedKeys) continue
            if (includePictureComment && k == "METADATA_BLOCK_PICTURE") continue
            outEntries.add(k to v)
        }

        if (includePictureComment) {
            val resolvedCover = resolveField(spec.cover, existingCover)
            if (resolvedCover != null && resolvedCover.bytes.isNotEmpty()) {
                val picBlock = encodeFlacPictureBlock(resolvedCover)
                val b64 = Base64.getEncoder().encodeToString(picBlock)
                outEntries.add("METADATA_BLOCK_PICTURE" to b64)
            }
        }

        val vendorBytes = existing.vendor.ifBlank { "MiniPlayer 1.0.0" }.toByteArray(Charsets.UTF_8)
        val encodedComments = outEntries.map { (k, v) -> "$k=$v".toByteArray(Charsets.UTF_8) }
        val totalLen = 4 + vendorBytes.size + 4 + encodedComments.sumOf { 4 + it.size }
        val bb = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(vendorBytes.size)
        bb.put(vendorBytes)
        bb.putInt(encodedComments.size)
        for (c in encodedComments) {
            bb.putInt(c.size)
            bb.put(c)
        }
        return bb.array()
    }

    private fun parseFlacPictureBlock(bytes: ByteArray): Triple<Int, String, ByteArray>? {
        if (bytes.size < 32) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val picType = bb.int
        val mimeLen = bb.int
        if (mimeLen < 0 || mimeLen > bb.remaining()) return null
        val mimeBytes = ByteArray(mimeLen)
        bb.get(mimeBytes)
        val mime = String(mimeBytes, Charsets.ISO_8859_1).trim().ifEmpty { "image/jpeg" }

        if (bb.remaining() < 4) return null
        val descLen = bb.int
        if (descLen < 0 || descLen > bb.remaining()) return null
        bb.position(bb.position() + descLen)

        if (bb.remaining() < 20) return null
        bb.position(bb.position() + 16) // width, height, depth, colors
        val dataLen = bb.int
        if (dataLen <= 0 || dataLen > bb.remaining()) return null
        val imgBytes = ByteArray(dataLen)
        bb.get(imgBytes)
        return Triple(picType, mime, imgBytes)
    }

    private fun encodeFlacPictureBlock(cover: CoverImage): ByteArray {
        val mimeBytes = cover.mimeType.ifBlank { "image/jpeg" }.toByteArray(Charsets.ISO_8859_1)
        val total = 4 + 4 + mimeBytes.size + 4 + 16 + 4 + cover.bytes.size
        val bb = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(3) // Front Cover
        bb.putInt(mimeBytes.size)
        bb.put(mimeBytes)
        bb.putInt(0) // Empty description
        bb.putInt(0) // width
        bb.putInt(0) // height
        bb.putInt(0) // color depth
        bb.putInt(0) // indexed colors
        bb.putInt(cover.bytes.size)
        bb.put(cover.bytes)
        return bb.array()
    }

    private fun buildTagsFromVorbis(
        vc: VorbisCommentData?,
        externalCover: Pair<String, ByteArray>?,
        durationMs: Long?,
    ): EmbeddedAudioTags {
        if (vc == null && externalCover == null && durationMs == null) return EmbeddedAudioTags()
        val entries = vc?.entries.orEmpty()
        fun firstVal(vararg keys: String): String? =
            entries.firstOrNull { it.first in keys && it.second.isNotBlank() }?.second?.trim()

        val artists = entries.filter { it.first == "ARTIST" && it.second.isNotBlank() }.map { it.second.trim() }
        val artistJoined = artists.takeIf { it.isNotEmpty() }?.joinToString(" / ")

        var coverMime = externalCover?.first
        var coverBytes = externalCover?.second
        if (coverBytes == null) {
            val b64Pic = firstVal("METADATA_BLOCK_PICTURE")
            if (b64Pic != null) {
                val decoded = runCatching { Base64.getDecoder().decode(b64Pic.trim()) }.getOrNull()
                val parsed = decoded?.let { parseFlacPictureBlock(it) }
                if (parsed != null) {
                    coverMime = parsed.second
                    coverBytes = parsed.third
                }
            }
        }

        return EmbeddedAudioTags(
            title = firstVal("TITLE"),
            artist = artistJoined,
            album = firstVal("ALBUM"),
            albumArtist = firstVal("ALBUMARTIST", "ALBUM ARTIST"),
            trackNumber = parseTrackNumber(firstVal("TRACKNUMBER", "TRACK")),
            year = parseYear(firstVal("DATE", "YEAR")),
            lyrics = firstVal("LYRICS", "UNSYNCEDLYRICS"),
            coverBytes = coverBytes,
            coverMimeType = coverMime,
            durationMs = durationMs,
        )
    }

    // =========================================================================
    // 3. Ogg Vorbis / Opus (OggS pages + VorbisComment / OpusTags + Ogg CRC-32)
    // =========================================================================

    private data class OggPage(
        val version: Int,
        val headerType: Int,
        val granulePosition: Long,
        val serialNumber: Int,
        val pageSequence: Int,
        val segments: List<ByteArray>,
    )

    private fun readOggTags(source: SeekableByteSource): EmbeddedAudioTags {
        val headerIn = SeekableSourceInputStream(source, 0L)
        val packetBaos = ByteArrayOutputStream()
        var packetIndex = 0
        var vc: VorbisCommentData? = null
        var sampleRate = 48_000
        var isOpus = false
        var preSkip = 0
        var maxGranule = -1L
        var streamSerial: Int? = null

        while (packetIndex < 2) {
            val page = readNextOggPage(headerIn) ?: break
            if (streamSerial == null) streamSerial = page.serialNumber
            if (page.granulePosition > maxGranule) {
                maxGranule = page.granulePosition
            }
            for (seg in page.segments) {
                if (packetIndex < 2) {
                    packetBaos.write(seg)
                }
                if (seg.size < 255) {
                    if (packetIndex < 2) {
                        val pkt = packetBaos.toByteArray()
                        packetBaos.reset()
                        if (packetIndex == 0) {
                            if (pkt.size >= 19 && String(pkt, 0, 8, Charsets.ISO_8859_1) == "OpusHead") {
                                isOpus = true
                                sampleRate = 48_000
                                preSkip = ((pkt[11].toInt() and 0xFF) shl 8) or (pkt[10].toInt() and 0xFF)
                            } else if (pkt.size >= 16 && pkt[0].toInt() == 1 && String(pkt, 1, 6, Charsets.ISO_8859_1) == "vorbis") {
                                sampleRate = ByteBuffer.wrap(pkt, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
                            }
                        } else if (packetIndex == 1 && vc == null) {
                            vc = parseOggCommentPacket(pkt)?.second
                        }
                    }
                    packetIndex++
                }
            }
        }

        // Jump straight to tail 64 KiB to read the final OggS page granulePosition without scanning middle audio pages!
        val tailGranule = extractTailOggMaxGranule(source, headerIn.position, streamSerial)
        if (tailGranule > maxGranule) {
            maxGranule = tailGranule
        }

        val durationMs = if (maxGranule > 0L && sampleRate > 0) {
            val effectiveSamples = if (isOpus) (maxGranule - preSkip).coerceAtLeast(0L) else maxGranule
            (effectiveSamples * 1000L) / sampleRate
        } else {
            null
        }
        return buildTagsFromVorbis(vc, externalCover = null, durationMs = durationMs)
    }

    private fun extractTailOggMaxGranule(
        source: SeekableByteSource,
        minStartOffset: Long,
        expectedSerial: Int?,
    ): Long {
        if (source.size <= minStartOffset) return -1L
        val tailWindow = 65_536L
        val readStart = maxOf(minStartOffset, source.size - tailWindow)
        val len = (source.size - readStart).toInt()
        if (len < 27) return -1L
        val buf = ByteArray(len)
        val n = source.readAt(readStart, buf, 0, len)
        if (n < 27) return -1L

        var bestGranule = -1L
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i <= n - 27) {
            if (buf[i] == 'O'.code.toByte() &&
                buf[i + 1] == 'g'.code.toByte() &&
                buf[i + 2] == 'g'.code.toByte() &&
                buf[i + 3] == 'S'.code.toByte() &&
                buf[i + 4].toInt() == 0
            ) {
                val headerType = buf[i + 5].toInt() and 0xFF
                val segCount = buf[i + 26].toInt() and 0xFF
                if ((headerType and 0xF8) == 0 && i + 27 + segCount <= n) {
                    var bodySize = 0
                    for (s in 0 until segCount) {
                        bodySize += buf[i + 27 + s].toInt() and 0xFF
                    }
                    val totalPageBytes = 27 + segCount + bodySize
                    val granule = bb.getLong(i + 6)
                    val serial = bb.getInt(i + 14)
                    val expectedCrc = bb.getInt(i + 22)

                    val serialMatches = expectedSerial == null || serial == expectedSerial
                    val crcMatches = if (i + totalPageBytes <= n) {
                        val copy = buf.copyOfRange(i, i + totalPageBytes)
                        copy[22] = 0
                        copy[23] = 0
                        copy[24] = 0
                        copy[25] = 0
                        computeOggCrc32(copy) == expectedCrc
                    } else {
                        false
                    }
                    if (serialMatches && crcMatches) {
                        if (granule > bestGranule) bestGranule = granule
                        i += totalPageBytes
                        continue
                    }
                }
            }
            i++
        }
        return bestGranule
    }

    private fun readOggTags(input: InputStream): EmbeddedAudioTags {
        val packetBaos = ByteArrayOutputStream()
        var packetIndex = 0
        var vc: VorbisCommentData? = null
        var sampleRate = 48_000
        var isOpus = false
        var preSkip = 0
        var maxGranule = -1L

        while (true) {
            val page = readNextOggPage(input) ?: break
            if (page.granulePosition > maxGranule) {
                maxGranule = page.granulePosition
            }
            if (packetIndex >= 2) {
                // Header & comment packets already decoded; never copy audio payload segments into packetBaos!
                continue
            }
            for (seg in page.segments) {
                if (packetIndex < 2) {
                    packetBaos.write(seg)
                }
                if (seg.size < 255) {
                    if (packetIndex < 2) {
                        val pkt = packetBaos.toByteArray()
                        packetBaos.reset()
                        if (packetIndex == 0) {
                            if (pkt.size >= 19 && String(pkt, 0, 8, Charsets.ISO_8859_1) == "OpusHead") {
                                isOpus = true
                                sampleRate = 48_000
                                preSkip = ((pkt[11].toInt() and 0xFF) shl 8) or (pkt[10].toInt() and 0xFF)
                            } else if (pkt.size >= 16 && pkt[0].toInt() == 1 && String(pkt, 1, 6, Charsets.ISO_8859_1) == "vorbis") {
                                sampleRate = ByteBuffer.wrap(pkt, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
                            }
                        } else if (packetIndex == 1 && vc == null) {
                            vc = parseOggCommentPacket(pkt)?.second
                        }
                    }
                    packetIndex++
                }
            }
        }

        val durationMs = if (maxGranule > 0L && sampleRate > 0) {
            val effectiveSamples = if (isOpus) (maxGranule - preSkip).coerceAtLeast(0L) else maxGranule
            (effectiveSamples * 1000L) / sampleRate
        } else {
            null
        }
        return buildTagsFromVorbis(vc, externalCover = null, durationMs = durationMs)
    }

    private fun parseOggCommentPacket(pkt: ByteArray): Pair<String, VorbisCommentData>? {
        if (pkt.size >= 8 && String(pkt, 0, 8, Charsets.ISO_8859_1) == "OpusTags") {
            return "OpusTags" to parseVorbisCommentBlock(pkt.copyOfRange(8, pkt.size))
        }
        if (pkt.size >= 7 && pkt[0].toInt() == 3 && String(pkt, 1, 6, Charsets.ISO_8859_1) == "vorbis") {
            return "\u0003vorbis" to parseVorbisCommentBlock(pkt.copyOfRange(7, pkt.size))
        }
        return null
    }

    private fun writeOggFile(sourceFile: File, destFile: File, spec: TagWriteSpec) {
        val pages = ArrayList<OggPage>()
        FileInputStream(sourceFile).use { input ->
            while (true) {
                val p = readNextOggPage(input) ?: break
                pages.add(p)
            }
        }
        if (pages.size < 2) throw IOException("Invalid Ogg stream: fewer than 2 pages")

        val serial = pages.first().serialNumber
        // Page 0 holds BOS identification packet; Pages 1..k hold comment packet (+ possible setup packet in Vorbis)
        // Reassemble packets starting at page 1 until all header packets in that page group end cleanly
        var commentEndPageIdx = -1
        val packetsInGroup = ArrayList<ByteArray>()
        val acc = ByteArrayOutputStream()
        for (idx in 1 until pages.size) {
            val page = pages[idx]
            for (seg in page.segments) {
                acc.write(seg)
                if (seg.size < 255) {
                    packetsInGroup.add(acc.toByteArray())
                    acc.reset()
                }
            }
            if (packetsInGroup.isNotEmpty() && acc.size() == 0) {
                commentEndPageIdx = idx
                break
            }
        }
        if (commentEndPageIdx < 1 || packetsInGroup.isEmpty()) {
            throw IOException("Could not locate complete Ogg comment packet")
        }

        val parsedComment = parseOggCommentPacket(packetsInGroup[0])
            ?: throw IOException("Unsupported Ogg comment header")
        val prefixTag = parsedComment.first
        val existingVc = parsedComment.second
        val currentTags = buildTagsFromVorbis(existingVc, null, null)
        val existingCover = currentTags.coverBytes?.let {
            CoverImage(it, currentTags.coverMimeType ?: CoverImage.detectImageMimeType(it))
        }

        val updatedVcBytes = encodeUpdatedVorbisCommentBlock(
            existing = existingVc,
            spec = spec,
            includePictureComment = true,
            existingCover = existingCover,
        )

        val newCommentPkt = ByteArrayOutputStream()
        newCommentPkt.write(prefixTag.toByteArray(Charsets.ISO_8859_1))
        newCommentPkt.write(updatedVcBytes)
        if (prefixTag == "\u0003vorbis") {
            newCommentPkt.write(1) // Vorbis framing bit = 1
        }
        packetsInGroup[0] = newCommentPkt.toByteArray()

        val rebuiltGroupPages = packetizeOggGroup(
            packets = packetsInGroup,
            serialNumber = serial,
            startSeq = 1,
            endGranule = pages[commentEndPageIdx].granulePosition,
        )

        FileOutputStream(destFile).use { out ->
            writeOggPage(out, pages[0])
            var nextSeq = 1
            for (rp in rebuiltGroupPages) {
                writeOggPage(out, rp.copy(pageSequence = nextSeq++))
            }
            for (idx in (commentEndPageIdx + 1) until pages.size) {
                val orig = pages[idx]
                val adjusted = if (orig.serialNumber == serial) orig.copy(pageSequence = nextSeq++) else orig
                writeOggPage(out, adjusted)
            }
            out.flush()
            out.fd.sync()
        }
    }

    private fun packetizeOggGroup(
        packets: List<ByteArray>,
        serialNumber: Int,
        startSeq: Int,
        endGranule: Long,
    ): List<OggPage> {
        val allSegments = ArrayList<Pair<ByteArray, Boolean>>() // (segmentBytes, isContinuationOfPrevPage)
        for (pkt in packets) {
            var offset = 0
            while (offset < pkt.size) {
                val chunk = minOf(255, pkt.size - offset)
                allSegments.add(pkt.copyOfRange(offset, offset + chunk) to (offset > 0))
                offset += chunk
            }
            if (pkt.isEmpty() || pkt.size % 255 == 0) {
                allSegments.add(ByteArray(0) to (pkt.isNotEmpty()))
            }
        }

        val outPages = ArrayList<OggPage>()
        var idx = 0
        var seq = startSeq
        while (idx < allSegments.size) {
            val end = minOf(idx + 255, allSegments.size)
            val isCont = allSegments[idx].second
            val isLastGroupPage = end == allSegments.size
            val segSlice = allSegments.subList(idx, end).map { it.first }
            val anyPacketFinished = segSlice.any { it.size < 255 }
            val pageGranule = when {
                isLastGroupPage -> if (anyPacketFinished && endGranule < 0L) 0L else endGranule
                anyPacketFinished -> 0L // RFC 3533 §6: header packet completed on this page -> granule 0 (not -1)
                else -> -1L
            }
            outPages.add(
                OggPage(
                    version = 0,
                    headerType = if (isCont) 0x01 else 0x00,
                    granulePosition = pageGranule,
                    serialNumber = serialNumber,
                    pageSequence = seq++,
                    segments = segSlice,
                ),
            )
            idx = end
        }
        return outPages
    }

    private fun readNextOggPage(input: InputStream): OggPage? {
        val hdr = ByteArray(27)
        val n = readFully(input, hdr)
        if (n == 0) return null
        if (n < 27 || String(hdr, 0, 4, Charsets.ISO_8859_1) != "OggS") {
            throw IOException("Invalid OggS page header")
        }
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(4)
        val version = bb.get().toInt() and 0xFF
        val headerType = bb.get().toInt() and 0xFF
        val granule = bb.long
        val serial = bb.int
        val seq = bb.int
        bb.int // checksum
        val pageSegments = bb.get().toInt() and 0xFF

        val segTable = ByteArray(pageSegments)
        if (readFully(input, segTable) < pageSegments) throw EOFException("Truncated Ogg segment table")

        val segments = ArrayList<ByteArray>(pageSegments)
        for (i in 0 until pageSegments) {
            val len = segTable[i].toInt() and 0xFF
            val seg = ByteArray(len)
            if (len > 0 && readFully(input, seg) < len) throw EOFException("Truncated Ogg segment data")
            segments.add(seg)
        }
        return OggPage(version, headerType, granule, serial, seq, segments)
    }

    private fun writeOggPage(out: OutputStream, page: OggPage) {
        val segCount = page.segments.size
        val bodySize = page.segments.sumOf { it.size }
        val rawPage = ByteArray(27 + segCount + bodySize)
        val bb = ByteBuffer.wrap(rawPage).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("OggS".toByteArray(Charsets.ISO_8859_1))
        bb.put(page.version.toByte())
        bb.put(page.headerType.toByte())
        bb.putLong(page.granulePosition)
        bb.putInt(page.serialNumber)
        bb.putInt(page.pageSequence)
        bb.putInt(0) // zeroed for CRC computation
        bb.put(segCount.toByte())
        for (s in page.segments) {
            bb.put(s.size.toByte())
        }
        for (s in page.segments) {
            bb.put(s)
        }
        val crc = computeOggCrc32(rawPage)
        bb.putInt(22, crc)
        out.write(rawPage)
    }

    internal fun computeOggCrc32(bytes: ByteArray): Int {
        var crc = 0
        for (b in bytes) {
            val idx = ((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF
            crc = (crc shl 8) xor OGG_CRC_TABLE[idx]
        }
        return crc
    }

    // =========================================================================
    // 4. MP4 / M4A (`moov.udta.meta.ilst` + `stco` / `co64` offset recalculation)
    // =========================================================================

    private data class Mp4BoxRef(
        val type: String,
        val offset: Long,
        val size: Long,
        val headerSize: Int,
    )

    internal data class MemBox(
        val type: String,
        val payload: ByteArray,
    ) {
        fun encode(): ByteArray {
            val total = 8 + payload.size
            val out = ByteArray(total)
            writeInt32BE(out, 0, total)
            val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
            System.arraycopy(typeBytes, 0, out, 4, 4)
            System.arraycopy(payload, 0, out, 8, payload.size)
            return out
        }
    }

    private fun parseTopLevelMp4Boxes(raf: RandomAccessFile): List<Mp4BoxRef> =
        parseTopLevelMp4Boxes(FileChannelByteSource(raf.channel, raf.length()))

    private fun parseTopLevelMp4Boxes(source: SeekableByteSource): List<Mp4BoxRef> {
        val len = source.size
        var pos = 0L
        val boxes = ArrayList<Mp4BoxRef>()
        val hdr8 = ByteArray(8)
        val hdr8Ext = ByteArray(8)
        while (pos + 8L <= len) {
            if (!source.readExactAt(pos, hdr8)) break
            val size32 = readUInt32BE(hdr8, 0)
            val type = String(hdr8, 4, 4, Charsets.ISO_8859_1)
            val headerSize: Int
            val boxSize: Long
            when (size32) {
                0L -> {
                    headerSize = 8
                    boxSize = len - pos
                }
                1L -> {
                    if (pos + 16L > len) throw IOException("Truncated 64-bit MP4 box header at $pos")
                    if (!source.readExactAt(pos + 8L, hdr8Ext)) {
                        throw IOException("Truncated 64-bit MP4 largesize at $pos")
                    }
                    headerSize = 16
                    boxSize = ByteBuffer.wrap(hdr8Ext).order(ByteOrder.BIG_ENDIAN).long
                }
                else -> {
                    headerSize = 8
                    boxSize = size32
                }
            }
            if (boxSize < headerSize.toLong() || boxSize > len - pos) {
                throw IOException("Invalid MP4 box '$type' size=$boxSize at offset=$pos (fileLen=$len)")
            }
            boxes.add(Mp4BoxRef(type, pos, boxSize, headerSize))
            pos += boxSize
        }
        return boxes
    }

    internal fun parseMemBoxes(buf: ByteArray, start: Int = 0, end: Int = buf.size): List<MemBox> {
        val out = ArrayList<MemBox>()
        var pos = start
        while (pos >= 0 && pos <= end - 8) {
            val size32 = readUInt32BE(buf, pos)
            val type = String(buf, pos + 4, 4, Charsets.ISO_8859_1)
            val headerSize: Int
            val boxSizeLong: Long
            when (size32) {
                0L -> {
                    headerSize = 8
                    boxSizeLong = (end - pos).toLong()
                }
                1L -> {
                    if (pos > end - 16) break
                    headerSize = 16
                    boxSizeLong = ByteBuffer.wrap(buf, pos + 8, 8).order(ByteOrder.BIG_ENDIAN).long
                }
                else -> {
                    headerSize = 8
                    boxSizeLong = size32
                }
            }
            if (boxSizeLong < headerSize.toLong() || boxSizeLong > (end - pos).toLong()) break
            val boxSize = boxSizeLong.toInt()
            val payload = buf.copyOfRange(pos + headerSize, pos + boxSize)
            out.add(MemBox(type, payload))
            pos += boxSize
        }
        return out
    }

    private fun detectMetaPrefixBytes(metaPayload: ByteArray): Int {
        if (metaPayload.size >= 8) {
            val possibleType = String(metaPayload, 4, 4, Charsets.ISO_8859_1)
            val firstSize = readUInt32BE(metaPayload, 0)
            // If bytes 4..7 look like a child box header directly at offset 0 ('hdlr', 'ilst', 'keys', 'free')
            if (firstSize in 8L..metaPayload.size.toLong() && possibleType in setOf("hdlr", "ilst", "keys", "free")) {
                return 0
            }
        }
        return minOf(4, metaPayload.size) // Standard ISO FullBox 4-byte version+flags
    }

    private fun readMp4Tags(file: File): EmbeddedAudioTags =
        FileInputStream(file).use { readMp4Tags(FileChannelByteSource(it.channel)) }

    private fun readMp4Tags(source: SeekableByteSource): EmbeddedAudioTags {
        val topBoxes = runCatching { parseTopLevelMp4Boxes(source) }.getOrDefault(emptyList())
        val moovRef = topBoxes.firstOrNull { it.type == "moov" } ?: return EmbeddedAudioTags()
        val moovPayloadSizeLong = moovRef.size - moovRef.headerSize.toLong()
        if (moovPayloadSizeLong <= 0L || moovPayloadSizeLong > 64L * 1024L * 1024L) return EmbeddedAudioTags()

        val moovPayload = ByteArray(moovPayloadSizeLong.toInt())
        if (!source.readExactAt(moovRef.offset + moovRef.headerSize, moovPayload)) return EmbeddedAudioTags()
        return parseMp4MoovPayload(moovPayload)
    }

    private fun readMp4TagsFromStream(input: InputStream): EmbeddedAudioTags {
        val hdr8 = ByteArray(8)
        val hdr8Ext = ByteArray(8)
        while (true) {
            if (readFully(input, hdr8) < 8) return EmbeddedAudioTags()
            val size32 = readUInt32BE(hdr8, 0)
            val type = String(hdr8, 4, 4, Charsets.ISO_8859_1)
            val headerSize: Int
            val boxSize: Long
            when (size32) {
                0L -> return EmbeddedAudioTags()
                1L -> {
                    if (readFully(input, hdr8Ext) < 8) return EmbeddedAudioTags()
                    headerSize = 16
                    boxSize = ByteBuffer.wrap(hdr8Ext).order(ByteOrder.BIG_ENDIAN).long
                }
                else -> {
                    headerSize = 8
                    boxSize = size32
                }
            }
            val payloadSize = boxSize - headerSize.toLong()
            if (payloadSize < 0L) return EmbeddedAudioTags()
            if (type == "moov") {
                if (payloadSize == 0L || payloadSize > 64L * 1024L * 1024L) return EmbeddedAudioTags()
                val moovPayload = ByteArray(payloadSize.toInt())
                if (readFully(input, moovPayload) < moovPayload.size) return EmbeddedAudioTags()
                return parseMp4MoovPayload(moovPayload)
            } else {
                skipFully(input, payloadSize)
            }
        }
    }

    private fun parseMp4MoovPayload(moovPayload: ByteArray): EmbeddedAudioTags {
        val moovChildren = parseMemBoxes(moovPayload)
        val durationMs = moovChildren.firstOrNull { it.type == "mvhd" }?.let { parseMvhdDurationMs(it.payload) }

        val udta = moovChildren.firstOrNull { it.type == "udta" }
        val meta = udta?.let { parseMemBoxes(it.payload).firstOrNull { c -> c.type == "meta" } }
        val ilst = meta?.let { m ->
            val prefix = detectMetaPrefixBytes(m.payload)
            parseMemBoxes(m.payload, prefix, m.payload.size).firstOrNull { c -> c.type == "ilst" }
        }

        if (ilst == null) return EmbeddedAudioTags(durationMs = durationMs)

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var trackNum: Int? = null
        var year: Int? = null
        var lyrics: String? = null
        var coverBytes: ByteArray? = null
        var coverMime: String? = null

        for (item in parseMemBoxes(ilst.payload)) {
            val dataBox = parseMemBoxes(item.payload).firstOrNull { it.type == "data" } ?: continue
            if (dataBox.payload.size < 8) continue
            val typeIndicator = readInt32BE(dataBox.payload, 0) and 0x00FFFFFF
            val valBytes = dataBox.payload.copyOfRange(8, dataBox.payload.size)

            when (item.type) {
                "${MP4_ATOM_COPY_SYM}nam" -> title = String(valBytes, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                "${MP4_ATOM_COPY_SYM}ART" -> artist = String(valBytes, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                "${MP4_ATOM_COPY_SYM}alb" -> album = String(valBytes, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                "aART" -> albumArtist = String(valBytes, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                "${MP4_ATOM_COPY_SYM}day" -> year = parseYear(String(valBytes, Charsets.UTF_8))
                "${MP4_ATOM_COPY_SYM}lyr" -> lyrics = String(valBytes, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                "trkn" -> {
                    if (valBytes.size >= 4) {
                        val num = ((valBytes[2].toInt() and 0xFF) shl 8) or (valBytes[3].toInt() and 0xFF)
                        if (num > 0) trackNum = num
                    }
                }
                "covr" -> {
                    if (valBytes.isNotEmpty() && coverBytes == null) {
                        coverBytes = valBytes
                        coverMime = when (typeIndicator) {
                            14 -> "image/png"
                            13 -> "image/jpeg"
                            else -> CoverImage.detectImageMimeType(valBytes)
                        }
                    }
                }
            }
        }

        return EmbeddedAudioTags(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            trackNumber = trackNum,
            year = year,
            lyrics = lyrics,
            coverBytes = coverBytes,
            coverMimeType = coverMime,
            durationMs = durationMs,
        )
    }

    private fun parseMvhdDurationMs(payload: ByteArray): Long? {
        if (payload.size < 20) return null
        val version = payload[0].toInt() and 0xFF
        return if (version == 1 && payload.size >= 32) {
            val timescale = readUInt32BE(payload, 20)
            val duration = ByteBuffer.wrap(payload, 24, 8).order(ByteOrder.BIG_ENDIAN).long
            if (timescale > 0L && duration > 0L) (duration * 1000L) / timescale else null
        } else {
            val timescale = readUInt32BE(payload, 12)
            val duration = readUInt32BE(payload, 16)
            if (timescale > 0L && duration > 0L) (duration * 1000L) / timescale else null
        }
    }

    private fun writeMp4File(sourceFile: File, destFile: File, spec: TagWriteSpec) {
        val currentTags = readMp4Tags(sourceFile)
        val newTitle = resolveField(spec.title, currentTags.title)
        val newArtist = resolveField(spec.artist, currentTags.artist)
        val newAlbum = resolveField(spec.album, currentTags.album)
        val newAlbumArtist = resolveField(spec.albumArtist, currentTags.albumArtist)
        val newTrack = resolveField(spec.trackNumber, currentTags.trackNumber)
        val newYear = resolveField(spec.year, currentTags.year)
        val existingCover = currentTags.coverBytes?.let {
            CoverImage(it, currentTags.coverMimeType ?: CoverImage.detectImageMimeType(it))
        }
        val newCover = resolveField(spec.cover, existingCover)

        RandomAccessFile(sourceFile, "r").use { raf ->
            val topBoxes = parseTopLevelMp4Boxes(raf)
            val moovRef = topBoxes.firstOrNull { it.type == "moov" }
                ?: throw IOException("MP4 file has no moov atom")
            val firstMdatRef = topBoxes.firstOrNull { it.type == "mdat" }

            val origMoovPayloadSizeLong = moovRef.size - moovRef.headerSize.toLong()
            if (origMoovPayloadSizeLong <= 0L || origMoovPayloadSizeLong > 64L * 1024L * 1024L) {
                throw IOException("MP4 moov atom size out of bounds: $origMoovPayloadSizeLong")
            }
            val origMoovPayload = ByteArray(origMoovPayloadSizeLong.toInt())
            raf.seek(moovRef.offset + moovRef.headerSize)
            raf.readFully(origMoovPayload)

            val moovWithUpdatedIlstPayload = rebuildMoovPayloadWithIlst(
                origMoovPayload = origMoovPayload,
                title = newTitle,
                artist = newArtist,
                album = newAlbum,
                albumArtist = newAlbumArtist,
                trackNumber = newTrack,
                year = newYear,
                cover = newCover,
            )

            // If `moov` precedes `mdat`, recalculate `stco` / `co64` chunk offsets!
            val moovBeforeMdat = firstMdatRef != null && moovRef.offset < firstMdatRef.offset
            val finalMoovBoxBytes = if (moovBeforeMdat) {
                buildOffsetAdjustedMoovBox(moovWithUpdatedIlstPayload, origMoovTotalSize = moovRef.size)
            } else {
                MemBox("moov", moovWithUpdatedIlstPayload).encode()
            }

            FileOutputStream(destFile).use { out ->
                for (box in topBoxes) {
                    if (box.offset == moovRef.offset && box.type == "moov") {
                        out.write(finalMoovBoxBytes)
                    } else {
                        streamRange(raf, box.offset, box.size, out)
                    }
                }
                out.flush()
                out.fd.sync()
            }
        }
    }

    private fun rebuildMoovPayloadWithIlst(
        origMoovPayload: ByteArray,
        title: String?,
        artist: String?,
        album: String?,
        albumArtist: String?,
        trackNumber: Int?,
        year: Int?,
        cover: CoverImage?,
    ): ByteArray {
        val moovChildren = parseMemBoxes(origMoovPayload)
        val existingUdta = moovChildren.firstOrNull { it.type == "udta" }
        val udtaChildren = existingUdta?.let { parseMemBoxes(it.payload) }.orEmpty()
        val existingMeta = udtaChildren.firstOrNull { it.type == "meta" }

        val metaPrefixLen = existingMeta?.let { detectMetaPrefixBytes(it.payload) } ?: 4
        val metaPrefix = existingMeta?.payload?.copyOfRange(0, metaPrefixLen) ?: ByteArray(4)
        val metaChildren = existingMeta?.let { parseMemBoxes(it.payload, metaPrefixLen, it.payload.size) }.orEmpty()
        val existingIlst = metaChildren.firstOrNull { it.type == "ilst" }

        val managedTypes = setOf(
            "${MP4_ATOM_COPY_SYM}nam",
            "${MP4_ATOM_COPY_SYM}ART",
            "${MP4_ATOM_COPY_SYM}alb",
            "aART",
            "trkn",
            "${MP4_ATOM_COPY_SYM}day",
            "covr",
        )
        val preservedIlstItems = existingIlst?.let { parseMemBoxes(it.payload) }.orEmpty()
            .filter { it.type !in managedTypes }

        val newIlstItems = ArrayList<MemBox>()
        title?.takeIf { it.isNotBlank() }?.let { newIlstItems.add(buildIlstTextAtom("${MP4_ATOM_COPY_SYM}nam", it)) }
        artist?.takeIf { it.isNotBlank() }?.let { newIlstItems.add(buildIlstTextAtom("${MP4_ATOM_COPY_SYM}ART", it)) }
        album?.takeIf { it.isNotBlank() }?.let { newIlstItems.add(buildIlstTextAtom("${MP4_ATOM_COPY_SYM}alb", it)) }
        albumArtist?.takeIf { it.isNotBlank() }?.let { newIlstItems.add(buildIlstTextAtom("aART", it)) }
        trackNumber?.takeIf { it > 0 }?.let { newIlstItems.add(buildIlstTrackAtom(it)) }
        year?.takeIf { it > 0 }?.let { newIlstItems.add(buildIlstTextAtom("${MP4_ATOM_COPY_SYM}day", it.toString())) }
        newIlstItems.addAll(preservedIlstItems)
        if (cover != null && cover.bytes.isNotEmpty()) {
            newIlstItems.add(buildIlstCoverAtom(cover))
        }

        val ilstBox = MemBox("ilst", concatMemBoxes(newIlstItems))

        val newMetaChildren = ArrayList<MemBox>()
        if (metaChildren.none { it.type == "hdlr" }) {
            newMetaChildren.add(buildMp4MetaHdlrBox())
        }
        var ilstInserted = false
        for (mc in metaChildren) {
            if (mc.type == "free" || mc.type == "skip") continue
            if (mc.type == "ilst") {
                newMetaChildren.add(ilstBox)
                ilstInserted = true
            } else {
                newMetaChildren.add(mc)
            }
        }
        if (!ilstInserted) newMetaChildren.add(ilstBox)

        val metaPayloadBaos = ByteArrayOutputStream()
        metaPayloadBaos.write(metaPrefix)
        metaPayloadBaos.write(concatMemBoxes(newMetaChildren))
        val newMetaBox = MemBox("meta", metaPayloadBaos.toByteArray())

        val newUdtaChildren = ArrayList<MemBox>()
        var metaInserted = false
        for (uc in udtaChildren) {
            if (uc.type == "free" || uc.type == "skip") continue
            if (uc.type == "meta") {
                newUdtaChildren.add(newMetaBox)
                metaInserted = true
            } else {
                newUdtaChildren.add(uc)
            }
        }
        if (!metaInserted) newUdtaChildren.add(newMetaBox)
        val newUdtaBox = MemBox("udta", concatMemBoxes(newUdtaChildren))

        val newMoovChildren = ArrayList<MemBox>()
        var udtaInserted = false
        for (mc in moovChildren) {
            if (mc.type == "free" || mc.type == "skip") continue
            if (mc.type == "udta") {
                newMoovChildren.add(newUdtaBox)
                udtaInserted = true
            } else {
                newMoovChildren.add(mc)
            }
        }
        if (!udtaInserted) newMoovChildren.add(newUdtaBox)

        return concatMemBoxes(newMoovChildren)
    }

    /**
     * Adjusts all `stco` and `co64` chunk offset tables inside `moov` (`trak -> mdia -> minf -> stbl`)
     * when `moov` precedes `mdat`. Uses fixed-point iteration in case a 32-bit `stco` overflows
     * `0xFFFF_FFFF` and upgrades to a 64-bit `co64` box (which grows `moov` by `4 * entryCount` bytes).
     */
    private fun buildOffsetAdjustedMoovBox(
        baseMoovPayload: ByteArray,
        origMoovTotalSize: Long,
    ): ByteArray {
        val baseTotalSize = 8L + baseMoovPayload.size
        var candidateDelta = baseTotalSize - origMoovTotalSize
        if (candidateDelta == 0L) {
            return MemBox("moov", baseMoovPayload).encode()
        }

        // Fixed-point loop (converges in 1 pass normally, or 2 passes if stco -> co64 upgrade occurs)
        repeat(4) {
            val adjustedPayload = adjustBoxesRecursive(
                payload = baseMoovPayload,
                path = "moov",
                delta = candidateDelta,
            )
            val actualNewTotal = 8L + adjustedPayload.size
            val actualDelta = actualNewTotal - origMoovTotalSize
            if (actualDelta == candidateDelta) {
                return MemBox("moov", adjustedPayload).encode()
            }
            candidateDelta = actualDelta
        }

        val finalPayload = adjustBoxesRecursive(baseMoovPayload, "moov", candidateDelta)
        return MemBox("moov", finalPayload).encode()
    }

    private fun adjustBoxesRecursive(payload: ByteArray, path: String, delta: Long): ByteArray {
        val containerChildren = when (path) {
            "moov" -> setOf("trak")
            "moov/trak" -> setOf("mdia")
            "moov/trak/mdia" -> setOf("minf")
            "moov/trak/mdia/minf" -> setOf("stbl")
            else -> emptySet()
        }
        val boxes = parseMemBoxes(payload)
        val updated = boxes.map { box ->
            when {
                path == "moov/trak/mdia/minf/stbl" && box.type == "stco" -> adjustStcoBox(box, delta)
                path == "moov/trak/mdia/minf/stbl" && box.type == "co64" -> adjustCo64Box(box, delta)
                box.type in containerChildren -> {
                    MemBox(box.type, adjustBoxesRecursive(box.payload, "$path/${box.type}", delta))
                }
                else -> box
            }
        }
        return concatMemBoxes(updated)
    }

    private fun adjustStcoBox(stco: MemBox, delta: Long): MemBox {
        if (stco.payload.size < 8) return stco
        val count = readInt32BE(stco.payload, 4)
        if (count <= 0 || 8L + count.toLong() * 4L > stco.payload.size.toLong()) return stco

        val newOffsets = LongArray(count)
        var needs64BitUpgrade = false
        for (i in 0 until count) {
            val oldOff = readUInt32BE(stco.payload, 8 + i * 4)
            val updated = oldOff + delta
            if (updated < 0L) {
                throw IOException("MP4 stco chunk offset underflow ($oldOff + $delta < 0)")
            }
            if (updated > 0xFFFF_FFFFL) {
                needs64BitUpgrade = true
            }
            newOffsets[i] = updated
        }

        return if (!needs64BitUpgrade) {
            val out = stco.payload.copyOf()
            for (i in 0 until count) {
                writeInt32BE(out, 8 + i * 4, newOffsets[i].toInt())
            }
            MemBox("stco", out)
        } else {
            val bb = ByteBuffer.allocate(8 + count * 8).order(ByteOrder.BIG_ENDIAN)
            bb.put(stco.payload, 0, 4) // version + flags
            bb.putInt(count)
            for (off in newOffsets) {
                bb.putLong(off)
            }
            MemBox("co64", bb.array())
        }
    }

    private fun adjustCo64Box(co64: MemBox, delta: Long): MemBox {
        if (co64.payload.size < 8) return co64
        val count = readInt32BE(co64.payload, 4)
        if (count <= 0 || 8L + count.toLong() * 8L > co64.payload.size.toLong()) return co64
        val out = co64.payload.copyOf()
        val bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        for (i in 0 until count) {
            val pos = 8 + i * 8
            val oldOff = bb.getLong(pos)
            val updated = oldOff + delta
            if (updated < 0L) {
                throw IOException("MP4 co64 chunk offset underflow ($oldOff + $delta < 0)")
            }
            bb.putLong(pos, updated)
        }
        return MemBox("co64", out)
    }

    private fun buildMp4MetaHdlrBox(): MemBox {
        // FullBox(0) + pre_defined(0) + handler_type('mdir') + reserved('appl', 0, 0) + empty null name
        val bb = ByteBuffer.allocate(25).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(0) // version + flags
        bb.putInt(0) // pre_defined
        bb.put("mdir".toByteArray(Charsets.ISO_8859_1))
        bb.put("appl".toByteArray(Charsets.ISO_8859_1))
        bb.putInt(0)
        bb.putInt(0)
        bb.put(0)
        return MemBox("hdlr", bb.array())
    }

    private fun buildIlstDataBox(typeIndicator: Int, valueBytes: ByteArray): MemBox {
        val bb = ByteBuffer.allocate(8 + valueBytes.size).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(typeIndicator) // 1 = UTF-8, 0 = binary/int, 13 = JPEG, 14 = PNG
        bb.putInt(0) // locale = 0
        bb.put(valueBytes)
        return MemBox("data", bb.array())
    }

    private fun buildIlstTextAtom(atomType: String, value: String): MemBox {
        val dataBox = buildIlstDataBox(1, value.toByteArray(Charsets.UTF_8))
        return MemBox(atomType, dataBox.encode())
    }

    private fun buildIlstTrackAtom(trackNum: Int): MemBox {
        val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        bb.putShort(0)
        bb.putShort(trackNum.coerceIn(0, 65535).toShort())
        bb.putShort(0)
        bb.putShort(0)
        val dataBox = buildIlstDataBox(0, bb.array())
        return MemBox("trkn", dataBox.encode())
    }

    private fun buildIlstCoverAtom(cover: CoverImage): MemBox {
        val flag = if (cover.mimeType.contains("png", ignoreCase = true)) 14 else 13
        val dataBox = buildIlstDataBox(flag, cover.bytes)
        return MemBox("covr", dataBox.encode())
    }

    private fun concatMemBoxes(boxes: List<MemBox>): ByteArray {
        val baos = ByteArrayOutputStream()
        for (b in boxes) {
            baos.write(b.encode())
        }
        return baos.toByteArray()
    }

    // =========================================================================
    // Low-level Binary & Parsing Utilities
    // =========================================================================

    private fun <T> resolveField(opt: OptionalField<T>, current: T): T = when (opt) {
        is OptionalField.Unchanged -> current
        is OptionalField.Set -> opt.value
    }

    private fun parseTrackNumber(raw: String?): Int? {
        val clean = raw?.trim()?.substringBefore('/')?.trim() ?: return null
        return clean.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun parseYear(raw: String?): Int? {
        val clean = raw?.trim() ?: return null
        val match = Regex("""\b(19\d{2}|20\d{2})\b""").find(clean) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun decodeSynchsafeInt(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0x7F) shl 21) or
            ((buf[offset + 1].toInt() and 0x7F) shl 14) or
            ((buf[offset + 2].toInt() and 0x7F) shl 7) or
            (buf[offset + 3].toInt() and 0x7F)
    }

    private fun encodeSynchsafeInt(value: Int): ByteArray {
        val v = value.coerceAtLeast(0)
        return byteArrayOf(
            ((v ushr 21) and 0x7F).toByte(),
            ((v ushr 14) and 0x7F).toByte(),
            ((v ushr 7) and 0x7F).toByte(),
            (v and 0x7F).toByte(),
        )
    }

    private fun readInt32BE(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)
    }

    private fun readUInt32BE(buf: ByteArray, offset: Int): Long =
        readInt32BE(buf, offset).toLong() and 0xFFFF_FFFFL

    private fun writeInt32BE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    private fun streamRange(raf: RandomAccessFile, offset: Long, length: Long, out: OutputStream) {
        if (length <= 0L) return
        raf.seek(offset)
        val buf = ByteArray(STREAM_BUF_SIZE)
        var remaining = length
        while (remaining > 0L) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val n = raf.read(buf, 0, toRead)
            if (n < 0) throw EOFException("Unexpected EOF while streaming audio payload ($remaining bytes left)")
            if (n > 0) {
                out.write(buf, 0, n)
                remaining -= n
            }
        }
    }

    private fun readUpTo(input: InputStream, buf: ByteArray): Int = readFully(input, buf)

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var rem = bytes
        val scratch = ByteArray(4096)
        while (rem > 0L) {
            val skipped = input.skip(rem)
            if (skipped > 0L) {
                rem -= skipped
            } else {
                val n = input.read(scratch, 0, minOf(scratch.size.toLong(), rem).toInt())
                if (n < 0) break
                rem -= n
            }
        }
    }
}
