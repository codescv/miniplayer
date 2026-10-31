package com.miniplayer.app.core.metadata

import java.util.Locale

data class ParsedFilename(
    val cleanedTitle: String,
    val queryTitle: String,
    val backingSuffix: String? = null,
    val isBackingTrack: Boolean = false,
    val artist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
)

/**
 * Stage 1 Smart Filename & Path Parser (`DESIGN.md` §6.4 #1).
 *
 * - Strips quality/codec/watermark noise (`[320k]`, `[FLAC]`, `(24bit-96kHz)`, `[Official Video]`, etc.)
 *   and leading track numbers (`01 - `, `04. `, `Track 02 - `).
 * - Detects backing-track markers (`(Backing Track)`, `[Guitar Backing]`, `(伴奏)`, `(无吉他伴奏)`, etc.)
 *   so online search queries (`queryTitle`) strip the suffix for accurate album/cover hits while
 *   preserving or re-attaching the suffix when filling titles (`preserveBackingSuffix = true`).
 * - Infers `<Artist>/<Album>` from non-generic parent folder hierarchies when filename lacks artist/album.
 */
object SmartFilenameParser {

    private val QUALITY_BRACKET_NOISE = Regex(
        """[\[\(]\s*(?:""" +
            """\d{2,4}\s*k(?:bps)?|""" +
            """flac|wav|mp3|aac|m4a|ape|alac|ogg|opus|hi-?res|lossless|无损|高音质|""" +
            """\d{2}\s*bit(?:\s*[-/·]\s*\d+(?:\.\d+)?\s*k(?:hz)?)?|""" +
            """\d+(?:\.\d+)?\s*khz|""" +
            """official\s*(?:music\s*)?(?:video|audio|mv|lyric\s*video)|""" +
            """mv|hd|uhd|fhd|4k|2k|1080p|720p|480p|60fps|""" +
            """web-?dl|webrip|bdrip|cd\s*rip|remaster(?:ed)?(?:\s*\d{4})?|""" +
            """(?:www\.|[a-z0-9-]+\.(?:com|net|org|cn|cc|vip))[^\])\n]*""" +
            """)\s*[\]\)]""",
        RegexOption.IGNORE_CASE,
    )

    private val BACKING_BRACKET_PATTERN = Regex(
        """[\[\(（【]\s*(""" +
            """(?:[a-z\s/-]*?\b(?:backing\s*track|jam\s*track|guitar\s*backing|minus\s*(?:guitar|vocals?|drums?|bass)|no\s*(?:guitar|vocals?)|karaoke|bt)\b[a-z0-9\s#♭♯/-]*)|""" +
            """(?:[^\]\)）】]*?(?:无吉他伴奏|吉他伴奏|贝斯伴奏|鼓伴奏|乐队伴奏|原版伴奏|纯伴奏|消音伴奏|伴奏版|伴奏))""" +
            """)\s*[\]\)）】]""",
        RegexOption.IGNORE_CASE,
    )

    private val BACKING_TRAILING_PATTERN = Regex(
        """(?:\s*[-_–—]\s*|\s+)(backing\s*track|guitar\s*backing(?:\s*track)?|jam\s*track|minus\s*guitar|无吉他伴奏|吉他伴奏|原版伴奏|伴奏)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private val LEADING_TRACK_PREFIX = Regex(
        """^\s*(?:(?:cd|disc|disk)\s*\d+\s*[-_.]\s*)?(?:track\s*)?#?(\d{1,3})\s*(?:[-_.–—)\]]\s+|\s+-\s+)""",
        RegexOption.IGNORE_CASE,
    )

    private val STANDALONE_BLIND_TRACK = Regex(
        """^(?:track|audio|曲目|音轨)\s*[-_ ]?\s*(\d{1,3})$""",
        RegexOption.IGNORE_CASE,
    )

    private val GENERIC_FOLDER_NAMES = setOf(
        "music", "audio", "songs", "tracks", "lossless", "flac", "mp3", "wav",
        "video", "videos", "movies", "mv", "clips",
        "backing", "backing tracks", "backing_tracks", "guitar", "guitar backing", "practice", "tabs", "lessons",
        "download", "downloads", "media", "unsorted", "misc", "unknown",
        "音乐", "音频", "歌曲", "无损", "无损音乐", "视频", "教学", "教学视频", "伴奏", "吉他伴奏", "练习", "扒谱", "下载",
    )

    private val MULTI_SPACE = Regex("""\s{2,}""")

    fun parse(parentRelPath: String, fileName: String): ParsedFilename {
        val stem = stripExtension(fileName).trim()
        if (stem.isEmpty()) {
            return ParsedFilename(cleanedTitle = fileName, queryTitle = fileName)
        }

        // Check if blind track filename like "Track_01" or "Track 04" before stripping everything
        val blindMatch = STANDALONE_BLIND_TRACK.matchEntire(stem)
        if (blindMatch != null) {
            val trackNum = blindMatch.groupValues[1].toIntOrNull()
            val (folderArtist, folderAlbum) = inferFromParentPath(parentRelPath)
            return ParsedFilename(
                cleanedTitle = stem.replace('_', ' ').trim(),
                queryTitle = stem.replace('_', ' ').trim(),
                backingSuffix = null,
                isBackingTrack = isBackingPath(parentRelPath),
                artist = folderArtist,
                album = folderAlbum,
                trackNumber = trackNum,
            )
        }

        var working = stem

        // 1. Strip quality / bitrate / codec / resolution noise brackets
        working = QUALITY_BRACKET_NOISE.replace(working, " ").trim()

        // 2. Detect & extract backing-track marker before Artist-Title split
        var detectedBackingSuffix: String? = null
        val bracketBacking = BACKING_BRACKET_PATTERN.find(working)
        if (bracketBacking != null) {
            val innerText = bracketBacking.groupValues[1].trim()
            detectedBackingSuffix = "($innerText)"
            working = working.removeRange(bracketBacking.range).trim()
        } else {
            val trailingBacking = BACKING_TRAILING_PATTERN.find(working)
            if (trailingBacking != null) {
                val innerText = trailingBacking.groupValues[1].trim()
                detectedBackingSuffix = "($innerText)"
                working = working.removeRange(trailingBacking.range).trim()
            }
        }

        // Strip any remaining quality brackets exposed after removing backing tag
        working = QUALITY_BRACKET_NOISE.replace(working, " ").trim()

        // 3. Extract leading track number ("04. ", "01 - ", "1-02 ", etc.)
        var trackNumber: Int? = null
        val trackMatch = LEADING_TRACK_PREFIX.find(working)
        if (trackMatch != null) {
            val candidateNum = trackMatch.groupValues[1].toIntOrNull()
            val remainder = working.substring(trackMatch.range.last + 1).trim()
            if (remainder.isNotEmpty() && candidateNum != null && candidateNum in 1..999) {
                trackNumber = candidateNum
                working = remainder
            }
        }

        // Normalize underscores if there are no spaces at all
        if (' ' !in working && '_' in working) {
            working = working.replace('_', ' ')
        }
        working = MULTI_SPACE.replace(working, " ").trim()

        // 4. Split "Artist - Title" if separated by spaced hyphen (" - " or " – " or " — ")
        var parsedArtist: String? = null
        var baseTitle = working
        val separatorMatch = Regex("""\s+[-–—]\s+""").find(working)
        if (separatorMatch != null) {
            val left = working.substring(0, separatorMatch.range.first).trim()
            val right = working.substring(separatorMatch.range.last + 1).trim()
            if (left.isNotEmpty() && right.isNotEmpty() && !left.all { it.isDigit() }) {
                parsedArtist = left
                // Check if right starts with another track number (e.g. "Artist - 04. Title")
                val rightTrackMatch = LEADING_TRACK_PREFIX.find(right)
                if (trackNumber == null && rightTrackMatch != null) {
                    val rightRemainder = right.substring(rightTrackMatch.range.last + 1).trim()
                    val num = rightTrackMatch.groupValues[1].toIntOrNull()
                    if (rightRemainder.isNotEmpty() && num != null) {
                        trackNumber = num
                        baseTitle = rightRemainder
                    } else {
                        baseTitle = right
                    }
                } else {
                    baseTitle = right
                }
            }
        }

        baseTitle = MULTI_SPACE.replace(baseTitle, " ").trim().ifEmpty { stem }

        // 5. Infer missing artist / album from parent folder hierarchy
        val (folderArtist, folderAlbum) = inferFromParentPath(parentRelPath)
        val finalArtist = parsedArtist ?: folderArtist
        val isBacking = detectedBackingSuffix != null || isBackingPath(parentRelPath)

        val cleanedDisplayTitle = if (detectedBackingSuffix != null) {
            "$baseTitle $detectedBackingSuffix"
        } else {
            baseTitle
        }

        return ParsedFilename(
            cleanedTitle = cleanedDisplayTitle,
            queryTitle = baseTitle,
            backingSuffix = detectedBackingSuffix,
            isBackingTrack = isBacking,
            artist = finalArtist,
            album = folderAlbum,
            trackNumber = trackNumber,
        )
    }

    /**
     * Strips noise and backing suffixes from an arbitrary title string (such as an existing raw tag
     * or user-edited title) so online queries hit the canonical song recording.
     */
    fun stripNoiseAndBackingForSearch(rawTitle: String): Pair<String, String?> {
        var working = QUALITY_BRACKET_NOISE.replace(rawTitle.trim(), " ").trim()
        var suffix: String? = null
        val bracketMatch = BACKING_BRACKET_PATTERN.find(working)
        if (bracketMatch != null) {
            suffix = "(${bracketMatch.groupValues[1].trim()})"
            working = working.removeRange(bracketMatch.range).trim()
        } else {
            val trailingMatch = BACKING_TRAILING_PATTERN.find(working)
            if (trailingMatch != null) {
                suffix = "(${trailingMatch.groupValues[1].trim()})"
                working = working.removeRange(trailingMatch.range).trim()
            }
        }
        working = MULTI_SPACE.replace(working, " ").trim().ifEmpty { rawTitle.trim() }
        return working to suffix
    }

    /**
     * Appends the preserved backing suffix (e.g. `(伴奏)` or `(Backing Track)`) to [candidateTitle]
     * if it does not already carry one (`DESIGN.md` §6.4 #1).
     */
    fun applyBackingSuffix(
        candidateTitle: String,
        originalFileName: String,
        fallbackSuffix: String = "(伴奏)",
    ): String {
        val trimmedCandidate = candidateTitle.trim()
        if (BACKING_BRACKET_PATTERN.containsMatchIn(trimmedCandidate) ||
            BACKING_TRAILING_PATTERN.containsMatchIn(trimmedCandidate)
        ) {
            return trimmedCandidate
        }
        val parsed = parse("", originalFileName)
        val suffix = parsed.backingSuffix ?: fallbackSuffix
        return "$trimmedCandidate $suffix"
    }

    fun stripExtension(fileName: String): String {
        val lastDot = fileName.lastIndexOf('.')
        return if (lastDot > 0) fileName.substring(0, lastDot) else fileName
    }

    private fun inferFromParentPath(parentRelPath: String): Pair<String?, String?> {
        val segments = parentRelPath
            .replace('\\', '/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isGenericFolder(it) }

        if (segments.isEmpty()) return null to null

        if (segments.size >= 2) {
            val artistCandidate = segments[segments.size - 2]
            val albumCandidate = cleanAlbumFolderName(segments.last())
            return artistCandidate to albumCandidate
        }

        // Single non-generic folder: check if formatted as "Artist - Album"
        val single = segments.single()
        val dashMatch = Regex("""\s+[-–—]\s+""").find(single)
        if (dashMatch != null) {
            val left = single.substring(0, dashMatch.range.first).trim()
            val right = cleanAlbumFolderName(single.substring(dashMatch.range.last + 1).trim())
            if (left.isNotEmpty() && right.isNotEmpty()) {
                return left to right
            }
        }
        return null to null
    }

    private fun cleanAlbumFolderName(rawFolder: String): String {
        // Strip leading "[2022] " or "(2022) " year prefix/suffix on album folders if present
        return rawFolder
            .replace(Regex("""^(?:\[\d{4}\]|\(\d{4}\))\s*"""), "")
            .replace(QUALITY_BRACKET_NOISE, "")
            .trim()
            .ifEmpty { rawFolder }
    }

    private fun isGenericFolder(segment: String): Boolean =
        segment.trim().lowercase(Locale.ROOT) in GENERIC_FOLDER_NAMES

    private fun isBackingPath(parentRelPath: String): Boolean {
        val lower = parentRelPath.lowercase(Locale.ROOT)
        return lower.contains("backing") ||
            lower.contains("伴奏") ||
            lower.contains("jam track") ||
            lower.contains("minus guitar")
    }

    /**
     * Natural alphanumeric comparator (`Track 2 < Track 10`, case-insensitive with deterministic tie-break)
     * per `DESIGN.md` §4.1.
     */
    val NaturalComparator: Comparator<String> = Comparator { a, b -> naturalCompare(a, b) }

    fun naturalCompare(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        val na = a.length
        val nb = b.length
        while (ia < na && ib < nb) {
            val ca = a[ia]
            val cb = b[ib]
            if (ca.isDigit() && cb.isDigit()) {
                var za = ia
                while (za < na && a[za] == '0') za++
                var zb = ib
                while (zb < nb && b[zb] == '0') zb++

                var ea = za
                while (ea < na && a[ea].isDigit()) ea++
                var eb = zb
                while (eb < nb && b[eb].isDigit()) eb++

                val lenA = ea - za
                val lenB = eb - zb
                if (lenA != lenB) return lenA.compareTo(lenB)
                for (k in 0 until lenA) {
                    val diff = a[za + k].compareTo(b[zb + k])
                    if (diff != 0) return diff
                }
                val rawLenDiff = (ea - ia).compareTo(eb - ib)
                if (rawLenDiff != 0) return rawLenDiff
                ia = ea
                ib = eb
            } else {
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) return la.compareTo(lb)
                ia++
                ib++
            }
        }
        if (ia < na) return 1
        if (ib < nb) return -1
        return a.compareTo(b)
    }
}
