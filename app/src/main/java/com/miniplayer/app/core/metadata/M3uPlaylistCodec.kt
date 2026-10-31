package com.miniplayer.app.core.metadata

import com.miniplayer.app.core.model.MediaItem
import com.miniplayer.app.core.model.SafRoot
import java.net.URLDecoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

data class ParsedM3uEntry(
    val rawPath: String,
    val extInfDurationSec: Long? = null,
    val extInfTitle: String? = null,
)

data class ParsedM3uPlaylist(
    val playlistName: String?,
    val isPracticePlaylist: Boolean,
    val entries: List<ParsedM3uEntry>,
)

data class M3uResolutionSummary(
    val playlistName: String,
    val isPracticePlaylist: Boolean,
    val matchedItems: List<MediaItem>,
    val unmatchedCount: Int,
)

/**
 * UTF-8 `.m3u / .m3u8` importer & exporter (`DESIGN.md` §4.2).
 *
 * - Writes `#EXT-X-MINIPLAYER-PRACTICE:1` header when `isPracticePlaylist == true`.
 * - Resolves entries across all SAF roots via a deterministic 3-tier fallback:
 *   1. Relative path suffix match (`<RootName>/<parent_rel_path>/<file_name>` or `<parent_rel_path>/<file_name>`)
 *   2. `file_name + #EXTINF duration (±1s)` match
 *   3. Unique same-name `file_name` match
 */
object M3uPlaylistCodec {

    const val PRACTICE_HEADER = "#EXT-X-MINIPLAYER-PRACTICE:1"
    private val EXTINF_REGEX = Regex("""^#EXTINF:\s*(-?\d+(?:\.\d+)?)\s*(?:,(.*))?$""", RegexOption.IGNORE_CASE)
    private val PLAYLIST_HEADER_REGEX = Regex("""^#PLAYLIST:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val PRACTICE_HEADER_REGEX = Regex("""^#EXT-X-MINIPLAYER-PRACTICE:\s*(1|true|yes)\s*$""", RegexOption.IGNORE_CASE)

    fun exportToM3u8(
        playlistName: String,
        isPracticePlaylist: Boolean,
        items: List<MediaItem>,
        rootsById: Map<Long, SafRoot>,
    ): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        if (isPracticePlaylist) {
            sb.append(PRACTICE_HEADER).append('\n')
        }
        if (playlistName.isNotBlank()) {
            sb.append("#PLAYLIST:").append(playlistName.trim()).append('\n')
        }
        for (item in items) {
            val durSec = if (item.durationMs > 0L) (item.durationMs / 1000.0).roundToLong() else -1L
            val label = if (item.artist.isNotBlank() && item.artist != ArtistSplitter.UNKNOWN_ARTIST_DISPLAY) {
                "${item.artist} - ${item.title}"
            } else {
                item.title
            }
            sb.append("#EXTINF:").append(durSec).append(',').append(label.replace('\n', ' ')).append('\n')

            val rootName = rootsById[item.rootId]?.displayName?.trim('/')?.takeIf { it.isNotEmpty() }
            val relParent = item.parentRelPath.replace('\\', '/').trim('/')
            val pathLine = buildString {
                if (rootName != null) {
                    append(rootName).append('/')
                }
                if (relParent.isNotEmpty()) {
                    append(relParent).append('/')
                }
                append(item.fileName)
            }
            sb.append(pathLine).append('\n')
        }
        return sb.toString()
    }

    fun parseM3u8(rawContent: String): ParsedM3uPlaylist {
        val cleaned = rawContent
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        var playlistName: String? = null
        var isPractice = false
        var pendingDurSec: Long? = null
        var pendingTitle: String? = null
        val entries = ArrayList<ParsedM3uEntry>()

        for (rawLine in cleaned.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#")) {
                if (PRACTICE_HEADER_REGEX.matches(line)) {
                    isPractice = true
                    continue
                }
                val plMatch = PLAYLIST_HEADER_REGEX.matchEntire(line)
                if (plMatch != null) {
                    playlistName = plMatch.groupValues[1].trim().takeIf { it.isNotEmpty() }
                    continue
                }
                val infMatch = EXTINF_REGEX.matchEntire(line)
                if (infMatch != null) {
                    val secDouble = infMatch.groupValues[1].toDoubleOrNull()
                    pendingDurSec = secDouble?.roundToLong()?.takeIf { it >= 0L }
                    pendingTitle = infMatch.groupValues[2].trim().takeIf { it.isNotEmpty() }
                }
                continue
            }

            entries.add(
                ParsedM3uEntry(
                    rawPath = line,
                    extInfDurationSec = pendingDurSec,
                    extInfTitle = pendingTitle,
                ),
            )
            pendingDurSec = null
            pendingTitle = null
        }

        return ParsedM3uPlaylist(
            playlistName = playlistName,
            isPracticePlaylist = isPractice,
            entries = entries,
        )
    }

    private data class IndexedCandidate(
        val item: MediaItem,
        val fullRootRelLower: String,
        val parentRelLower: String,
        val fileNameLower: String,
    )

    fun resolvePlaylist(
        rawContent: String,
        fallbackName: String,
        allItems: List<MediaItem>,
        rootsById: Map<Long, SafRoot>,
    ): M3uResolutionSummary {
        val parsed = parseM3u8(rawContent)
        val effectiveName = parsed.playlistName?.takeIf { it.isNotBlank() } ?: fallbackName

        val indexed = allItems.map { item ->
            val rootName = rootsById[item.rootId]?.displayName?.replace('\\', '/')?.trim('/').orEmpty()
            val relParent = item.parentRelPath.replace('\\', '/').trim('/')
            val parentAndFile = if (relParent.isNotEmpty()) "$relParent/${item.fileName}" else item.fileName
            val rootAndFile = if (rootName.isNotEmpty()) "$rootName/$parentAndFile" else parentAndFile
            IndexedCandidate(
                item = item,
                fullRootRelLower = rootAndFile.lowercase(Locale.ROOT),
                parentRelLower = parentAndFile.lowercase(Locale.ROOT),
                fileNameLower = item.fileName.lowercase(Locale.ROOT),
            )
        }

        val byFileName = indexed.groupBy { it.fileNameLower }
        val matched = ArrayList<MediaItem>()
        val addedItemIds = HashSet<Long>()
        var unmatched = 0

        for (entry in parsed.entries) {
            val resolved = resolveSingleEntry(entry, indexed, byFileName)
            if (resolved != null) {
                if (addedItemIds.add(resolved.itemId)) {
                    matched.add(resolved)
                }
            } else {
                unmatched++
            }
        }

        return M3uResolutionSummary(
            playlistName = effectiveName,
            isPracticePlaylist = parsed.isPracticePlaylist,
            matchedItems = matched,
            unmatchedCount = unmatched,
        )
    }

    private fun resolveSingleEntry(
        entry: ParsedM3uEntry,
        allIndexed: List<IndexedCandidate>,
        byFileName: Map<String, List<IndexedCandidate>>,
    ): MediaItem? {
        val rawTrimmed = entry.rawPath.trim()
        if (rawTrimmed.startsWith("content://", ignoreCase = true)) {
            allIndexed.firstOrNull { it.item.documentUri == rawTrimmed }?.let { return it.item }
        }

        val normalizedPath = normalizeM3uPath(rawTrimmed)
        val normalizedLower = normalizedPath.lowercase(Locale.ROOT)
        val entryFileNameLower = normalizedLower.substringAfterLast('/')
        val hasDir = '/' in normalizedLower

        val candidatesSameFile = byFileName[entryFileNameLower].orEmpty()
        if (candidatesSameFile.isEmpty()) return null

        // Tier 1: Relative path suffix match (`<RootName>/<parent_rel_path>/<file_name>` or `<parent_rel_path>/<file_name>`)
        if (hasDir) {
            val fullRootMatches = candidatesSameFile
                .filter { pathMatchesSuffix(normalizedLower, it.fullRootRelLower) }
                .map { it.item }
            if (fullRootMatches.isNotEmpty()) {
                return pickClosestDurationOrFirst(fullRootMatches, entry.extInfDurationSec)
            }

            val parentRelMatches = candidatesSameFile
                .filter { it.parentRelLower.contains('/') && pathMatchesSuffix(normalizedLower, it.parentRelLower) }
                .map { it.item }
            if (parentRelMatches.isNotEmpty()) {
                return pickClosestDurationOrFirst(parentRelMatches, entry.extInfDurationSec)
            }
        }

        // Tier 2: `file_name + #EXTINF duration (±1s)` match
        val extSec = entry.extInfDurationSec
        if (extSec != null && extSec >= 0L) {
            val targetMs = extSec * 1_000L
            val withinTolerance = candidatesSameFile
                .map { it.item }
                .filter { it.durationMs > 0L && abs(it.durationMs - targetMs) <= 1_500L }
                .minByOrNull { abs(it.durationMs - targetMs) }
            if (withinTolerance != null) {
                return withinTolerance
            }
        }

        // Tier 3: Unique same-name `file_name` match
        if (candidatesSameFile.size == 1) {
            return candidatesSameFile[0].item
        }

        return null
    }

    private fun normalizeM3uPath(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("file://", ignoreCase = true)) {
            s = s.substring(7)
            s = runCatching { URLDecoder.decode(s, Charsets.UTF_8.name()) }.getOrDefault(s)
        } else if ('%' in s && !s.startsWith("content://", ignoreCase = true)) {
            s = runCatching { URLDecoder.decode(s, Charsets.UTF_8.name()) }.getOrDefault(s)
        }
        s = s.replace('\\', '/')
        while (s.startsWith("./")) s = s.removePrefix("./")
        s = s.trim('/')
        return s
    }

    private fun pathMatchesSuffix(m3uNormalizedLower: String, indexedRelLower: String): Boolean {
        if (m3uNormalizedLower == indexedRelLower) return true
        if (m3uNormalizedLower.endsWith("/$indexedRelLower")) return true
        if (indexedRelLower.endsWith("/$m3uNormalizedLower")) return true
        return false
    }

    private fun pickClosestDurationOrFirst(items: List<MediaItem>, extInfDurationSec: Long?): MediaItem {
        if (items.size == 1 || extInfDurationSec == null || extInfDurationSec < 0L) return items.first()
        val targetMs = extInfDurationSec * 1_000L
        return items.minByOrNull { abs(it.durationMs - targetMs) } ?: items.first()
    }
}
