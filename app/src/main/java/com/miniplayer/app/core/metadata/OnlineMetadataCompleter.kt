package com.miniplayer.app.core.metadata

import com.miniplayer.app.core.model.BatchMatchItem
import com.miniplayer.app.core.model.MediaItem
import com.miniplayer.app.core.model.MetadataCandidate
import com.miniplayer.app.core.model.OnlineSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class RawHttpResponse(
    val statusCode: Int,
    val finalUrl: String,
    val contentType: String? = null,
    val body: ByteArray = ByteArray(0),
) {
    val bodyUtf8: String get() = String(body, Charsets.UTF_8)
}

interface OnlineHttpClient {
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 8_000,
        maxBytes: Int = 2 * 1024 * 1024,
        headOnly: Boolean = false,
    ): RawHttpResponse
}

class UrlConnectionOnlineHttpClient(
    private val allowInsecureLoopback: Boolean = false,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : OnlineHttpClient {

    companion object {
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        maxBytes: Int,
        headOnly: Boolean,
    ): RawHttpResponse = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        var current = URL(url)
        validateScheme(current)

        var hops = 0
        while (true) {
            val conn = openConnection(current)
            try {
                conn.requestMethod = if (headOnly) "HEAD" else "GET"
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.connectTimeout = connectTimeoutMs.coerceAtLeast(250)
                conn.readTimeout = readTimeoutMs.coerceAtLeast(250)
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

                val code = conn.responseCode
                if (code in REDIRECT_CODES) {
                    val loc = conn.getHeaderField("Location")?.trim()
                        ?: throw IOException("Redirect HTTP $code missing Location")
                    if (++hops > MAX_REDIRECTS) {
                        throw IOException("Exceeded max redirects ($MAX_REDIRECTS)")
                    }
                    @Suppress("DEPRECATION")
                    val next = URL(current, loc)
                    if (current.protocol.equals("https", ignoreCase = true) &&
                        next.protocol.equals("http", ignoreCase = true)
                    ) {
                        throw IOException("Refusing HTTPS -> HTTP downgrade redirect")
                    }
                    validateScheme(next)
                    current = next
                    continue
                }

                val contentType = conn.contentType
                val body = if (headOnly || code !in 200..299) {
                    ByteArray(0)
                } else {
                    conn.inputStream.use { readCapped(it, maxBytes) }
                }
                return@withContext RawHttpResponse(
                    statusCode = code,
                    finalUrl = current.toString(),
                    contentType = contentType,
                    body = body,
                )
            } finally {
                conn.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IOException("Unreachable")
    }

    private fun validateScheme(url: URL) {
        val proto = url.protocol?.lowercase(Locale.ROOT).orEmpty()
        val host = url.host?.lowercase(Locale.ROOT).orEmpty()
        if (proto == "https" && host.isNotBlank()) return
        if (allowInsecureLoopback && proto == "http" && (host == "127.0.0.1" || host == "localhost")) return
        throw IOException("Refusing insecure non-HTTPS URL: $url")
    }

    private fun readCapped(input: InputStream, maxBytes: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            if (baos.size() + n > maxBytes) {
                throw IOException("Online metadata payload exceeded $maxBytes bytes")
            }
            baos.write(buf, 0, n)
        }
        return baos.toByteArray()
    }
}

data class MbRecordingHit(
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val year: Int? = null,
    val durationMs: Long? = null,
    val releaseMbid: String? = null,
)

class MusicBrainzClient(
    private val http: OnlineHttpClient,
    private val baseUrl: String = "https://musicbrainz.org/ws/2",
    private val minIntervalMs: Long = 1_000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val USER_AGENT = "MiniPlayer/1.0.0 (https://github.com/codescv/miniplayer)"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    private val rateLimitMutex = Mutex()
    private var lastRequestAtMs: Long = 0L

    suspend fun searchRecordings(
        title: String,
        artist: String?,
        limit: Int = 5,
    ): List<MbRecordingHit> {
        val cleanTitle = escapeLucene(title.trim())
        if (cleanTitle.isEmpty()) return emptyList()
        val lucene = buildString {
            append("recording:\"").append(cleanTitle).append('"')
            val cleanArtist = artist?.trim()?.takeIf {
                it.isNotEmpty() && !it.equals(ArtistSplitter.UNKNOWN_ARTIST_DISPLAY, ignoreCase = true)
            }
            if (cleanArtist != null) {
                append(" AND artist:\"").append(escapeLucene(cleanArtist)).append('"')
            }
        }
        val encoded = URLEncoder.encode(lucene, Charsets.UTF_8.name())
        val url = "${baseUrl.trimEnd('/')}/recording/?query=$encoded&fmt=json&limit=$limit"

        val resp = rateLimitMutex.withLock {
            val now = clockMs()
            val elapsed = now - lastRequestAtMs
            if (lastRequestAtMs > 0L && elapsed < minIntervalMs) {
                delay(minIntervalMs - elapsed)
            }
            lastRequestAtMs = clockMs()
            runCatching {
                http.get(
                    url = url,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json",
                    ),
                    connectTimeoutMs = 6_000,
                    readTimeoutMs = 6_000,
                )
            }.getOrNull()
        } ?: return emptyList()

        if (resp.statusCode !in 200..299) return emptyList()
        return parseRecordingJson(resp.bodyUtf8)
    }

    internal fun parseRecordingJson(rawJson: String): List<MbRecordingHit> {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return emptyList()
        val recordings = root["recordings"] as? JsonArray ?: return emptyList()
        val out = ArrayList<MbRecordingHit>(recordings.size)

        for (elem in recordings) {
            val obj = elem as? JsonObject ?: continue
            val recTitle = obj.str("title") ?: continue
            val durationMs = obj["length"]?.jsonPrimitive?.longOrNull
            val artistCredit = joinArtistCredits(obj["artist-credit"] as? JsonArray) ?: continue

            val releases = (obj["releases"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            // Prefer Official Album release with a date
            val chosenRelease = releases.sortedWith(
                compareByDescending<JsonObject> { it.str("status").equals("Official", ignoreCase = true) }
                    .thenByDescending { !it.str("date").isNullOrBlank() },
            ).firstOrNull()

            val releaseMbid = chosenRelease?.str("id")
            val albumTitle = chosenRelease?.str("title")
            val year = chosenRelease?.str("date")?.let { extractYear(it) }
            val releaseAlbumArtist = chosenRelease?.let { joinArtistCredits(it["artist-credit"] as? JsonArray) }
            val trackNum = chosenRelease?.let { rel ->
                val mediaArr = rel["media"] as? JsonArray
                val firstMedium = mediaArr?.firstOrNull() as? JsonObject
                val trackArr = firstMedium?.get("track") as? JsonArray
                val firstTrack = trackArr?.firstOrNull() as? JsonObject
                firstTrack?.str("number")?.substringBefore('/')?.filter { it.isDigit() }?.toIntOrNull()
            }

            out.add(
                MbRecordingHit(
                    title = recTitle,
                    artist = artistCredit,
                    album = albumTitle,
                    albumArtist = releaseAlbumArtist ?: artistCredit,
                    trackNumber = trackNum,
                    year = year,
                    durationMs = durationMs,
                    releaseMbid = releaseMbid,
                ),
            )
        }
        return out
    }

    private fun joinArtistCredits(arr: JsonArray?): String? {
        if (arr == null || arr.isEmpty()) return null
        val sb = StringBuilder()
        for (item in arr) {
            val obj = item as? JsonObject ?: continue
            val name = obj.str("name") ?: (obj["artist"] as? JsonObject)?.str("name") ?: continue
            sb.append(name)
            val joinPhrase = obj.str("joinphrase")
            if (joinPhrase != null) sb.append(joinPhrase)
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun escapeLucene(s: String): String =
        s.replace(Regex("""[+\-&|!(){}\[\]^"~*?:\\/]"""), " ").replace(Regex("""\s+"""), " ").trim()
}

class CoverArtArchiveClient(
    private val http: OnlineHttpClient,
    private val baseUrl: String = "https://coverartarchive.org",
    val timeoutMs: Long = STRICT_CAA_TIMEOUT_MS,
) {
    companion object {
        const val STRICT_CAA_TIMEOUT_MS = 3_000L
    }

    /**
     * Resolves Cover Art Archive `front-500` URL under a **strict 3.0s timeout** (`DESIGN.md` §6.4 #2).
     * Returns `null` immediately on timeout, network failure, `404`, or `5xx` so the caller seamlessly
     * falls back to the iTunes `1000x1000bb.jpg` global CDN URL.
     */
    suspend fun resolveFrontCoverUrl(releaseMbid: String?): String? {
        val mbid = releaseMbid?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val url = "${baseUrl.trimEnd('/')}/release/$mbid/front-500"
        val sockTimeout = timeoutMs.coerceIn(100L, 3_000L).toInt()

        return withTimeoutOrNull(timeoutMs) {
            runCatching {
                val resp = http.get(
                    url = url,
                    headers = mapOf("User-Agent" to MusicBrainzClient.USER_AGENT),
                    connectTimeoutMs = sockTimeout,
                    readTimeoutMs = sockTimeout,
                    maxBytes = 64,
                    headOnly = true,
                )
                if (resp.statusCode in 200..299) {
                    resp.finalUrl.ifBlank { url }
                } else if (resp.statusCode == 405) {
                    // Fallback in case endpoint dislikes HEAD
                    val getResp = http.get(
                        url = url,
                        headers = mapOf("User-Agent" to MusicBrainzClient.USER_AGENT, "Range" to "bytes=0-15"),
                        connectTimeoutMs = sockTimeout,
                        readTimeoutMs = sockTimeout,
                        maxBytes = 1024,
                        headOnly = false,
                    )
                    if (getResp.statusCode in 200..299) getResp.finalUrl.ifBlank { url } else null
                } else {
                    null
                }
            }.getOrNull()
        }
    }
}

class ItunesSearchClient(
    private val http: OnlineHttpClient,
    private val baseUrl: String = "https://itunes.apple.com",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val KANA_REGEX = Regex("""[\u3040-\u30FF]""")
        private val CJK_REGEX = Regex("""[\u4E00-\u9FFF]""")

        fun upgradeArtworkTo1000x1000(artworkUrl: String?): String? {
            val clean = artworkUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return clean.replace(Regex("""\d+x\d+bb"""), "1000x1000bb")
        }
    }

    suspend fun searchSongs(
        title: String,
        artist: String?,
        limit: Int = 5,
    ): List<MetadataCandidate> {
        val term = listOfNotNull(
            artist?.trim()?.takeIf { it.isNotEmpty() && !it.equals(ArtistSplitter.UNKNOWN_ARTIST_DISPLAY, ignoreCase = true) },
            title.trim().takeIf { it.isNotEmpty() },
        ).joinToString(" ")
        if (term.isEmpty()) return emptyList()

        val countries = selectRegions(term)
        for (country in countries) {
            val encoded = URLEncoder.encode(term, Charsets.UTF_8.name())
            val url = "${baseUrl.trimEnd('/')}/search?media=music&entity=song&limit=$limit&country=$country&term=$encoded"
            val resp = runCatching {
                http.get(
                    url = url,
                    headers = mapOf(
                        "User-Agent" to MusicBrainzClient.USER_AGENT,
                        "Accept" to "application/json",
                    ),
                    connectTimeoutMs = 5_000,
                    readTimeoutMs = 5_000,
                )
            }.getOrNull() ?: continue

            if (resp.statusCode in 200..299) {
                val parsed = parseItunesJson(resp.bodyUtf8)
                if (parsed.isNotEmpty()) return parsed
            }
        }
        return emptyList()
    }

    internal fun parseItunesJson(rawJson: String): List<MetadataCandidate> {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return emptyList()
        val results = root["results"] as? JsonArray ?: return emptyList()
        val out = ArrayList<MetadataCandidate>(results.size)

        for (elem in results) {
            val obj = elem as? JsonObject ?: continue
            val trackName = obj.str("trackName") ?: continue
            val artistName = obj.str("artistName") ?: continue
            val albumName = obj.str("collectionName")
            val albumArtist = obj.str("collectionArtistName") ?: artistName
            val trackNum = obj["trackNumber"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
            val year = obj.str("releaseDate")?.let { extractYear(it) }
            val durationMs = obj["trackTimeMillis"]?.jsonPrimitive?.longOrNull
            val coverUrl = upgradeArtworkTo1000x1000(obj.str("artworkUrl100") ?: obj.str("artworkUrl60"))

            out.add(
                MetadataCandidate(
                    source = OnlineSource.ITUNES,
                    title = trackName,
                    artist = artistName,
                    album = albumName,
                    albumArtist = albumArtist,
                    trackNumber = trackNum,
                    year = year,
                    durationMs = durationMs,
                    coverUrl = coverUrl,
                ),
            )
        }
        return out
    }

    private fun selectRegions(term: String): List<String> = when {
        KANA_REGEX.containsMatchIn(term) -> listOf("JP", "US")
        CJK_REGEX.containsMatchIn(term) -> listOf("JP", "CN", "US")
        else -> listOf("US", "JP")
    }
}

data class LrclibResult(
    val syncedLrc: String?,
    val plainLyrics: String?,
)

class LrclibClient(
    private val http: OnlineHttpClient,
    private val baseUrl: String = "https://lrclib.net",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    suspend fun fetchLyrics(
        title: String,
        artist: String?,
        album: String? = null,
        durationMs: Long? = null,
        isBackingTrack: Boolean = false,
    ): LrclibResult? {
        val cleanTitle = title.trim().takeIf { it.isNotEmpty() } ?: return null
        val cleanArtist = artist?.trim()?.takeIf {
            it.isNotEmpty() && !it.equals(ArtistSplitter.UNKNOWN_ARTIST_DISPLAY, ignoreCase = true)
        }
        val durationSec = durationMs?.takeIf { it > 0L }?.let { (it / 1000.0).roundToInt() }

        // 1. Try exact `/api/get` if artist + duration are available
        if (cleanArtist != null && durationSec != null) {
            val q = buildString {
                append("track_name=").append(URLEncoder.encode(cleanTitle, "UTF-8"))
                append("&artist_name=").append(URLEncoder.encode(cleanArtist, "UTF-8"))
                if (!album.isNullOrBlank()) {
                    append("&album_name=").append(URLEncoder.encode(album.trim(), "UTF-8"))
                }
                append("&duration=").append(durationSec)
            }
            val getResp = runCatching {
                http.get(
                    url = "${baseUrl.trimEnd('/')}/api/get?$q",
                    headers = mapOf("User-Agent" to MusicBrainzClient.USER_AGENT, "Accept" to "application/json"),
                    connectTimeoutMs = 5_000,
                    readTimeoutMs = 5_000,
                )
            }.getOrNull()

            if (getResp != null && getResp.statusCode in 200..299) {
                val obj = runCatching { json.parseToJsonElement(getResp.bodyUtf8).jsonObject }.getOrNull()
                val parsed = obj?.let { extractLyricsFromObj(it) }
                if (parsed != null) return parsed
            }
        }

        // 2. Fallback to `/api/search`
        val searchQuery = listOfNotNull(cleanArtist, cleanTitle).joinToString(" ")
        val searchUrl = "${baseUrl.trimEnd('/')}/api/search?q=${URLEncoder.encode(searchQuery, "UTF-8")}"
        val searchResp = runCatching {
            http.get(
                url = searchUrl,
                headers = mapOf("User-Agent" to MusicBrainzClient.USER_AGENT, "Accept" to "application/json"),
                connectTimeoutMs = 5_000,
                readTimeoutMs = 5_000,
            )
        }.getOrNull() ?: return null

        if (searchResp.statusCode !in 200..299) return null
        val arr = runCatching { json.parseToJsonElement(searchResp.bodyUtf8).jsonArray }.getOrNull() ?: return null

        val validHits = arr.mapNotNull { it as? JsonObject }
            .filter { it["instrumental"]?.jsonPrimitive?.booleanOrNull != true }

        val bestObj = if (durationMs != null && durationMs > 0L) {
            val toleranceMs = if (isBackingTrack) 25_000L else 2_500L
            validHits
                .map { obj ->
                    val hitDurMs = ((obj["duration"]?.jsonPrimitive?.doubleOrNull ?: -100.0) * 1000.0).toLong()
                    obj to abs(hitDurMs - durationMs)
                }
                .filter { (_, diff) -> diff <= toleranceMs }
                .minByOrNull { (_, diff) -> diff }
                ?.first
        } else {
            validHits.firstOrNull()
        }

        return bestObj?.let { extractLyricsFromObj(it) }
    }

    private fun extractLyricsFromObj(obj: JsonObject): LrclibResult? {
        val synced = obj.str("syncedLyrics")?.takeIf { it.isNotBlank() }
        val plain = obj.str("plainLyrics")?.takeIf { it.isNotBlank() }
        if (synced == null && plain == null) return null
        return LrclibResult(syncedLrc = synced, plainLyrics = plain)
    }
}

/**
 * 3-Stage Zero-Key Online Metadata Completer (`DESIGN.md` §6.4, `ARCHITECTURE.md` §4.5).
 */
class OnlineMetadataCompleter(
    private val http: OnlineHttpClient = UrlConnectionOnlineHttpClient(),
    private val musicBrainz: MusicBrainzClient = MusicBrainzClient(http),
    private val coverArtArchive: CoverArtArchiveClient = CoverArtArchiveClient(http),
    private val itunes: ItunesSearchClient = ItunesSearchClient(http),
    private val lrclib: LrclibClient = LrclibClient(http),
) {
    companion object {
        const val AUTO_CHECK_CONFIDENCE_THRESHOLD = 85
        private const val MAX_CANDIDATES = 5
        private const val BATCH_CONCURRENCY = 3
    }

    private val batchSemaphore = Semaphore(BATCH_CONCURRENCY)

    suspend fun searchCandidates(
        item: MediaItem,
        queryTitleOverride: String? = null,
        queryArtistOverride: String? = null,
    ): List<MetadataCandidate> = coroutineScope {
        val parsedFile = SmartFilenameParser.parse(item.parentRelPath, item.fileName)
        val rawSearchTitle = queryTitleOverride?.takeIf { it.isNotBlank() } ?: item.title.ifBlank { parsedFile.queryTitle }
        val (cleanQueryTitle, strippedSuffix) = SmartFilenameParser.stripNoiseAndBackingForSearch(rawSearchTitle)
        val effectiveQueryTitle = cleanQueryTitle.ifBlank { parsedFile.queryTitle }

        val effectiveQueryArtist = (queryArtistOverride?.takeIf { it.isNotBlank() }
            ?: item.artist.takeIf { it.isNotBlank() && !it.equals(ArtistSplitter.UNKNOWN_ARTIST_DISPLAY, ignoreCase = true) }
            ?: parsedFile.artist)

        val effectiveQueryAlbum = item.album?.takeIf { it.isNotBlank() } ?: parsedFile.album
        val effectiveQueryTrack = item.trackNumber ?: parsedFile.trackNumber
        val isBacking = item.isBackingTrack || parsedFile.isBackingTrack || strippedSuffix != null

        // Stage 2: Concurrent MusicBrainz + iTunes lookup
        val mbDeferred = async { musicBrainz.searchRecordings(effectiveQueryTitle, effectiveQueryArtist, limit = 5) }
        val itunesDeferred = async { itunes.searchSongs(effectiveQueryTitle, effectiveQueryArtist, limit = 5) }

        val mbHits = mbDeferred.await()
        val itunesHits = itunesDeferred.await()

        // Resolve cover art for top MusicBrainz hits with strict 3.0s CAA timeout -> iTunes 1000x1000 CDN fallback
        val mbCandidates = mbHits.take(4).map { hit ->
            async {
                val caaCoverUrl = coverArtArchive.resolveFrontCoverUrl(hit.releaseMbid)
                val finalCoverUrl = caaCoverUrl ?: findMatchingItunesCover(hit, itunesHits)
                val baseCandidate = MetadataCandidate(
                    source = OnlineSource.MUSICBRAINZ,
                    title = hit.title,
                    artist = hit.artist,
                    album = hit.album,
                    albumArtist = hit.albumArtist,
                    trackNumber = hit.trackNumber,
                    year = hit.year,
                    durationMs = hit.durationMs,
                    coverUrl = finalCoverUrl,
                )
                val score = scoreCandidate(
                    candidate = baseCandidate,
                    queryTitle = effectiveQueryTitle,
                    queryArtist = effectiveQueryArtist,
                    queryAlbum = effectiveQueryAlbum,
                    queryTrackNum = effectiveQueryTrack,
                    targetDurationMs = item.durationMs,
                    isBackingTrack = isBacking,
                )
                baseCandidate.copy(confidenceScore = score)
            }
        }.map { it.await() }

        val scoredItunes = itunesHits.map { cand ->
            cand.copy(
                confidenceScore = scoreCandidate(
                    candidate = cand,
                    queryTitle = effectiveQueryTitle,
                    queryArtist = effectiveQueryArtist,
                    queryAlbum = effectiveQueryAlbum,
                    queryTrackNum = effectiveQueryTrack,
                    targetDurationMs = item.durationMs,
                    isBackingTrack = isBacking,
                ),
            )
        }

        val combined = ArrayList<MetadataCandidate>(mbCandidates.size + scoredItunes.size)
        combined.addAll(mbCandidates)
        combined.addAll(scoredItunes)
        combined.sortWith(
            compareByDescending<MetadataCandidate> { it.confidenceScore }
                .thenByDescending { !it.coverUrl.isNullOrBlank() }
                .thenBy { it.source.ordinal },
        )

        val deduped = ArrayList<MetadataCandidate>()
        val seenKeys = HashSet<String>()
        for (cand in combined) {
            val dedupKey = "${cand.source}|${normalizeForCompare(cand.title)}|${normalizeForCompare(cand.artist)}|${normalizeForCompare(cand.album.orEmpty())}"
            if (seenKeys.add(dedupKey)) {
                deduped.add(cand)
                if (deduped.size >= MAX_CANDIDATES) break
            }
        }

        // Enrich top candidate(s) with LRCLIB time-synced lyrics
        if (deduped.isNotEmpty()) {
            val top = deduped[0]
            val lrc = lrclib.fetchLyrics(
                title = top.title,
                artist = top.artist,
                album = top.album,
                durationMs = item.durationMs.takeIf { it > 0L } ?: top.durationMs,
                isBackingTrack = isBacking,
            )
            if (lrc != null) {
                deduped[0] = top.copy(
                    syncedLrc = lrc.syncedLrc,
                    plainLyrics = lrc.plainLyrics,
                )
            }
        }

        deduped
    }

    suspend fun previewBatch(
        items: List<MediaItem>,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): List<BatchMatchItem> = coroutineScope {
        if (items.isEmpty()) return@coroutineScope emptyList()
        var completed = 0
        val progressMutex = Mutex()
        val deferreds = items.map { item ->
            async {
                batchSemaphore.withPermit {
                    val parsed = SmartFilenameParser.parse(item.parentRelPath, item.fileName)
                    val candidates = searchCandidates(item)
                    val topScore = candidates.firstOrNull()?.confidenceScore ?: 0
                    val selectedIdx = if (topScore >= AUTO_CHECK_CONFIDENCE_THRESHOLD) 0 else -1
                    val batchItem = BatchMatchItem(
                        mediaItem = item,
                        cleanedTitle = parsed.cleanedTitle,
                        candidates = candidates,
                        selectedCandidateIndex = selectedIdx,
                        preserveBackingSuffix = item.isBackingTrack || parsed.isBackingTrack,
                    )
                    progressMutex.withLock {
                        completed += 1
                        onProgress(completed, items.size)
                    }
                    batchItem
                }
            }
        }
        deferreds.map { it.await() }
    }

    suspend fun downloadCoverBytes(coverUrl: String?): Pair<ByteArray, String>? {
        val url = coverUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val resp = runCatching {
            http.get(
                url = url,
                headers = mapOf("User-Agent" to MusicBrainzClient.USER_AGENT),
                connectTimeoutMs = 6_000,
                readTimeoutMs = 10_000,
                maxBytes = 8 * 1024 * 1024,
            )
        }.getOrNull() ?: return null
        if (resp.statusCode !in 200..299 || resp.body.isEmpty()) return null
        val mime = resp.contentType?.substringBefore(';')?.trim()?.takeIf { it.startsWith("image/") }
            ?: CoverImage.detectImageMimeType(resp.body)
        return resp.body to mime
    }

    /**
     * Stage 3 Confidence Scorer (`0–100%` per `DESIGN.md` §6.4 #3):
     * - Title normalized edit-distance similarity: `40%` (0..40)
     * - Artist similarity: `25%` (0..25)
     * - Duration proximity (`|Δt| <= 3s` full 25 pts; relaxed decay for backing tracks): `25%` (0..25)
     * - Album / Track# alignment: `10%` (0..10)
     */
    fun scoreCandidate(
        candidate: MetadataCandidate,
        queryTitle: String,
        queryArtist: String?,
        queryAlbum: String? = null,
        queryTrackNum: Int? = null,
        targetDurationMs: Long = 0L,
        isBackingTrack: Boolean = false,
    ): Int {
        // 1. Title score (0..40)
        val (strippedCandTitle, _) = SmartFilenameParser.stripNoiseAndBackingForSearch(candidate.title)
        val titleSim = maxOf(
            stringSimilarity(queryTitle, candidate.title),
            stringSimilarity(queryTitle, strippedCandTitle),
        )
        val titlePoints = titleSim * 40.0

        // 2. Artist score (0..25)
        val cleanArtist = queryArtist?.trim()?.takeIf {
            it.isNotEmpty() && !it.equals(ArtistSplitter.UNKNOWN_ARTIST_DISPLAY, ignoreCase = true)
        }
        val artistPoints = if (cleanArtist != null) {
            val directSim = stringSimilarity(cleanArtist, candidate.artist)
            val splitQuery = ArtistSplitter.split(cleanArtist).map { it.display }
            val splitCand = ArtistSplitter.split(candidate.artist, candidate.albumArtist).map { it.display }
            val bestTokenSim = splitQuery.maxOfOrNull { q ->
                splitCand.maxOfOrNull { c -> stringSimilarity(q, c) } ?: 0.0
            } ?: 0.0
            maxOf(directSim, bestTokenSim) * 25.0
        } else {
            // Neutral baseline when file has no artist tag/filename hint yet
            16.0 * titleSim
        }

        // 3. Duration proximity (0..25)
        val durationPoints = if (targetDurationMs > 0L && candidate.durationMs != null && candidate.durationMs > 0L) {
            val diffMs = abs(targetDurationMs - candidate.durationMs)
            when {
                diffMs <= 3_000L -> 25.0
                isBackingTrack -> when {
                    diffMs <= 12_000L -> 22.0
                    diffMs <= 30_000L -> 18.0
                    diffMs <= 60_000L -> 12.0
                    else -> 6.0
                }
                diffMs >= 30_000L -> 0.0
                else -> 25.0 * (1.0 - (diffMs - 3_000L).toDouble() / 27_000.0)
            }
        } else {
            15.0
        }

        // 4. Album / Track# alignment (0..10)
        val hasAlbumHint = !queryAlbum.isNullOrBlank()
        val hasTrackHint = queryTrackNum != null && queryTrackNum > 0
        val albumTrackPoints = when {
            hasAlbumHint && hasTrackHint -> {
                val albPts = if (!candidate.album.isNullOrBlank()) stringSimilarity(queryAlbum!!, candidate.album) * 6.0 else 0.0
                val trkPts = if (candidate.trackNumber == queryTrackNum) 4.0 else 0.0
                albPts + trkPts
            }
            hasAlbumHint -> {
                if (!candidate.album.isNullOrBlank()) stringSimilarity(queryAlbum!!, candidate.album) * 10.0 else 2.0
            }
            hasTrackHint -> {
                val trkPts = if (candidate.trackNumber == queryTrackNum) 7.0 else 2.0
                val completeness = if (!candidate.album.isNullOrBlank()) 3.0 else 0.0
                trkPts + completeness
            }
            else -> {
                var completeness = 0.0
                if (!candidate.album.isNullOrBlank()) completeness += 5.0
                if (candidate.trackNumber != null && candidate.trackNumber > 0) completeness += 2.5
                if (!candidate.coverUrl.isNullOrBlank()) completeness += 2.5
                completeness
            }
        }

        return (titlePoints + artistPoints + durationPoints + albumTrackPoints)
            .roundToInt()
            .coerceIn(0, 100)
    }

    private fun findMatchingItunesCover(hit: MbRecordingHit, itunesHits: List<MetadataCandidate>): String? {
        if (itunesHits.isEmpty()) return null
        val best = itunesHits
            .filter { !it.coverUrl.isNullOrBlank() }
            .maxByOrNull { cand ->
                val tSim = stringSimilarity(hit.title, cand.title)
                val aSim = stringSimilarity(hit.artist, cand.artist)
                tSim * 0.65 + aSim * 0.35
            } ?: return null
        val sim = stringSimilarity(hit.title, best.title) * 0.65 + stringSimilarity(hit.artist, best.artist) * 0.35
        return if (sim >= 0.45) best.coverUrl else null
    }

    internal fun stringSimilarity(a: String, b: String): Double {
        val na = normalizeForCompare(a)
        val nb = normalizeForCompare(b)
        if (na.isEmpty() && nb.isEmpty()) return 1.0
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        val dist = levenshtein(na, nb)
        val maxLen = max(na.length, nb.length)
        return (1.0 - dist.toDouble() / maxLen.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun normalizeForCompare(s: String): String {
        return s.lowercase(Locale.ROOT)
            .replace(Regex("""[\p{Punct}\s　・·—–\-_()\[\]（）【】「」『』]"""), "")
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            val ca = a[i]
            for (j in b.indices) {
                val cost = if (ca == b[j]) 0 else 1
                curr[j + 1] = minOf(
                    curr[j] + 1,
                    prev[j + 1] + 1,
                    prev[j] + cost,
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonElement)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        ?.takeIf { it != "null" && it.isNotBlank() }

private fun extractYear(rawDate: String): Int? =
    Regex("""\b(19\d{2}|20\d{2})\b""").find(rawDate)?.groupValues?.get(1)?.toIntOrNull()
