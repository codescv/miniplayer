package com.miniplayer.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.miniplayer.app.player.CountInMetronome
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Memory + disk-cached artwork & video keyframe loader for local SAF files, sidecar covers,
 * cached override covers, and online candidate preview URLs.
 */
object ArtworkLoader {
    private val memoryCache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val decodeSemaphore = Semaphore(permits = 3)

    fun peekCached(key: String): Bitmap? = memoryCache.get(key)

    suspend fun loadBitmap(
        context: Context,
        coverPathOrUri: String?,
        mediaDocumentUri: String? = null,
        isVideo: Boolean = false,
        maxDimensionPx: Int = 512,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val rawKey = buildString {
            append(coverPathOrUri.orEmpty())
            append('|')
            append(mediaDocumentUri.orEmpty())
            append('|')
            append(isVideo)
            append('|')
            append(maxDimensionPx)
        }
        if (rawKey.startsWith("||")) return@withContext null

        memoryCache.get(rawKey)?.takeIf { !it.isRecycled }?.let { return@withContext it }

        decodeSemaphore.withPermit {
            memoryCache.get(rawKey)?.takeIf { !it.isRecycled }?.let { return@withPermit it }

            val thumbCacheDir = File(context.cacheDir, "thumbs").apply { mkdirs() }
            val diskKeyFile = File(thumbCacheDir, "thumb_${rawKey.hashCode().toUInt().toString(16)}.jpg")
            if (diskKeyFile.isFile && diskKeyFile.length() > 64L) {
                decodeSampledFile(diskKeyFile, maxDimensionPx)?.let { cachedBmp ->
                    memoryCache.put(rawKey, cachedBmp)
                    return@withPermit cachedBmp
                }
            }

            val loaded = loadSourceBitmap(
                context = context,
                coverPathOrUri = coverPathOrUri,
                mediaDocumentUri = mediaDocumentUri,
                isVideo = isVideo,
                maxDimensionPx = maxDimensionPx,
            )
            if (loaded != null) {
                memoryCache.put(rawKey, loaded)
                try {
                    FileOutputStream(diskKeyFile).use { out ->
                        loaded.compress(Bitmap.CompressFormat.JPEG, 86, out)
                    }
                } catch (_: Throwable) {
                }
            }
            loaded
        }
    }

    private suspend fun loadSourceBitmap(
        context: Context,
        coverPathOrUri: String?,
        mediaDocumentUri: String?,
        isVideo: Boolean,
        maxDimensionPx: Int,
    ): Bitmap? {
        if (!coverPathOrUri.isNullOrBlank()) {
            when {
                coverPathOrUri.startsWith("https://", ignoreCase = true) ||
                    coverPathOrUri.startsWith("http://", ignoreCase = true) -> {
                    fetchHttpsBitmap(coverPathOrUri, maxDimensionPx)?.let { return it }
                }
                coverPathOrUri.startsWith("content://") || coverPathOrUri.startsWith("file://") -> {
                    decodeSampledUri(context, Uri.parse(coverPathOrUri), maxDimensionPx)?.let { return it }
                }
                else -> {
                    val file = File(coverPathOrUri)
                    if (file.isFile) {
                        decodeSampledFile(file, maxDimensionPx)?.let { return it }
                    }
                }
            }
        }

        if (!mediaDocumentUri.isNullOrBlank()) {
            return extractFromMediaRetriever(
                context = context,
                uri = Uri.parse(mediaDocumentUri),
                isVideo = isVideo,
                maxDimensionPx = maxDimensionPx,
            )
        }
        return null
    }

    private fun extractFromMediaRetriever(
        context: Context,
        uri: Uri,
        isVideo: Boolean,
        maxDimensionPx: Int,
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
            } ?: retriever.setDataSource(context, uri)

            retriever.embeddedPicture?.let { bytes ->
                decodeSampledBytes(bytes, maxDimensionPx)?.let { return it }
            }

            if (isVideo) {
                val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val sampleTimeUs = if (durMs > 10_000L) (durMs * 100L) else 1_000_000L
                val rawFrame = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        sampleTimeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        maxDimensionPx,
                        maxDimensionPx,
                    )
                } else {
                    retriever.getFrameAtTime(sampleTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                rawFrame?.let { downscaleIfNeeded(it, maxDimensionPx) }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    private const val TOTAL_HTTPS_TIMEOUT_MS = 3_000L
    private const val MAX_ARTWORK_DOWNLOAD_BYTES = 4 * 1024 * 1024

    /**
     * Enforces HTTPS for remote cover art while upgrading trusted Cover Art Archive / Internet
     * Archive `http://` redirects (`archive.org`, `*.archive.org`, `coverartarchive.org`,
     * `*.coverartarchive.org`) to `https://` (`usesCleartextTraffic="false"`).
     */
    internal fun normalizeTrustedCoverUrl(rawUrl: String): String? {
        val parsed = try {
            URL(rawUrl.trim())
        } catch (_: Throwable) {
            return null
        }
        return when (parsed.protocol.lowercase()) {
            "https" -> parsed.toString()
            "http" -> {
                val host = parsed.host.orEmpty().lowercase()
                val isTrustedArchiveHost = host == "archive.org" ||
                    host.endsWith(".archive.org") ||
                    host == "coverartarchive.org" ||
                    host.endsWith(".coverartarchive.org")
                if (!isTrustedArchiveHost) {
                    null
                } else {
                    val port = if (parsed.port > 0 && parsed.port != 80) parsed.port else -1
                    URL("https", parsed.host, port, parsed.file).toString()
                }
            }
            else -> null
        }
    }

    private suspend fun fetchHttpsBitmap(urlStr: String, maxDimensionPx: Int): Bitmap? {
        val activeConn = AtomicReference<HttpURLConnection?>(null)
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                runCatching { activeConn.getAndSet(null)?.disconnect() }
            }
        }
        val deadlineMs = CountInMetronome.monotonicNowMs() + TOTAL_HTTPS_TIMEOUT_MS
        return try {
            var currentUrl = normalizeTrustedCoverUrl(urlStr) ?: return null
            var hops = 0
            while (hops <= 4) {
                coroutineContext.ensureActive()
                val remainingMs = (deadlineMs - CountInMetronome.monotonicNowMs()).toInt()
                if (remainingMs <= 50) return null
                val url = URL(currentUrl)
                if (!url.protocol.equals("https", ignoreCase = true)) return null
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = remainingMs
                    readTimeout = remainingMs
                    setRequestProperty("User-Agent", "MiniPlayer/1.0.0 (Android)")
                }
                activeConn.set(conn)
                val code = conn.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    val resolved = URL(url, loc).toString()
                    activeConn.set(null)
                    conn.disconnect()
                    currentUrl = normalizeTrustedCoverUrl(resolved) ?: return null
                    hops++
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) return null
                val bytes = conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream(32 * 1024)
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        if (CountInMetronome.monotonicNowMs() > deadlineMs) return null
                        val n = input.read(buf)
                        if (n < 0) break
                        if (out.size() + n > MAX_ARTWORK_DOWNLOAD_BYTES) return null
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
                return decodeSampledBytes(bytes, maxDimensionPx)
            }
            null
        } catch (_: Throwable) {
            null
        } finally {
            cancelHandle?.dispose()
            runCatching { activeConn.getAndSet(null)?.disconnect() }
        }
    }

    private fun decodeSampledFile(file: File, maxDimensionPx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDimensionPx)
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    } catch (_: Throwable) {
        null
    }

    private fun decodeSampledUri(context: Context, uri: Uri, maxDimensionPx: Int): Bitmap? = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        decodeSampledBytes(bytes, maxDimensionPx)
    } catch (_: Throwable) {
        null
    }

    private fun decodeSampledBytes(bytes: ByteArray, maxDimensionPx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDimensionPx)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (_: Throwable) {
        null
    }

    private fun downscaleIfNeeded(bmp: Bitmap, maxDimensionPx: Int): Bitmap {
        val maxSide = maxOf(bmp.width, bmp.height)
        if (maxSide <= maxDimensionPx || maxSide <= 0) return bmp
        val ratio = maxDimensionPx.toFloat() / maxSide.toFloat()
        val w = (bmp.width * ratio).toInt().coerceAtLeast(1)
        val h = (bmp.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    private fun computeInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        val largest = maxOf(width, height)
        if (largest > maxDim && maxDim > 0) {
            val half = largest / 2
            while ((half / sample) >= maxDim) {
                sample *= 2
            }
        }
        return sample
    }
}

@Composable
fun MediaArtwork(
    coverPathOrUri: String?,
    mediaDocumentUri: String? = null,
    isVideo: Boolean = false,
    shape: Shape,
    modifier: Modifier = Modifier,
    maxDimensionPx: Int = 512,
    fallbackIcon: ImageVector = when {
        isVideo -> Icons.Outlined.OndemandVideo
        mediaDocumentUri == null -> Icons.Outlined.Album
        else -> Icons.Outlined.MusicNote
    },
    iconSize: Dp = 28.dp,
    contentDescription: String? = null,
    onBitmapLoaded: ((Bitmap?) -> Unit)? = null,
) {
    val context = LocalContext.current
    var bitmap by remember(coverPathOrUri, mediaDocumentUri, isVideo, maxDimensionPx) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(coverPathOrUri, mediaDocumentUri, isVideo, maxDimensionPx) {
        val loaded = ArtworkLoader.loadBitmap(
            context = context,
            coverPathOrUri = coverPathOrUri,
            mediaDocumentUri = mediaDocumentUri,
            isVideo = isVideo,
            maxDimensionPx = maxDimensionPx,
        )
        bitmap = loaded
        onBitmapLoaded?.invoke(loaded)
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
