package com.miniplayer.app.core.metadata

import java.util.Locale

data class SplitArtist(
    val norm: String,
    val display: String,
)

/**
 * Multi-artist splitter (`DESIGN.md` §6.2 & `ARCHITECTURE.md` §4.1).
 *
 * Splits `/`, `;`, `；`, `、`, `feat.`, `ft.`, `featuring`, `cv.`, `cv:` while protecting
 * band names in [BAND_ALLOWLIST] (`AC/DC`, `Simon & Garfunkel`, `MYTH & ROID`, etc.)
 * as well as matching [albumArtist] band names.
 */
object ArtistSplitter {

    const val UNKNOWN_ARTIST_DISPLAY = "未知歌手"
    const val UNKNOWN_ARTIST_NORM = "未知歌手"

    private val VARIOUS_ARTISTS_NORMS = setOf(
        "various artists",
        "various",
        "va",
        "v.a.",
        "群星",
        "合辑",
    )

    val BAND_ALLOWLIST: List<String> = listOf(
        "AC/DC",
        "Simon & Garfunkel",
        "Guns N' Roses",
        "Earth, Wind & Fire",
        "Crosby, Stills, Nash & Young",
        "Crosby, Stills & Nash",
        "Emerson, Lake & Palmer",
        "Hall & Oates",
        "Daryl Hall & John Oates",
        "Kool & The Gang",
        "Blood, Sweat & Tears",
        "Echo & the Bunnymen",
        "Mumford & Sons",
        "Of Mice & Men",
        "Angels & Airwaves",
        "Coheed and Cambria",
        "Tegan and Sara",
        "Belle and Sebastian",
        "Florence + The Machine",
        "She & Him",
        "Brooks & Dunn",
        "Aly & AJ",
        "MYTH & ROID",
        "King & Prince",
        "Chage & Aska",
        "CHAGE and ASKA",
        "Tackey & Tsubasa",
        "B'z",
        "BL/ACK",
        "+/-",
    )

    // Matches parenthesized/bracketed credits like "(feat. Guest)", "[ft. A / B]", "(CV. 青山吉能)", "(CV: 喜多郁代)"
    private val BRACKETED_CREDIT_REGEX = Regex(
        """[\(\[（【]\s*(?:featuring|feat\.?|ft\.?|cv\s*[.:：])\s*([^\)\]）】]+)[\)\]）】]""",
        RegexOption.IGNORE_CASE,
    )

    // Matches inline credits like "Artist A feat. Artist B", "Character cv. VoiceActor"
    private val INLINE_CREDIT_REGEX = Regex(
        """(?:\s+|\b)(?:featuring|feat\.|ft\.|cv\s*[.:：])\s*|\s+(?:feat|ft)\s+""",
        RegexOption.IGNORE_CASE,
    )

    // Standard delimiters: `/`, `;`, `；`, `、`, plus spaced J-Pop/CJK collaboration ` × `
    private val DELIMITER_REGEX = Regex("""\s*(?:/|;|；|、)\s*|\s+×\s+""")

    private val WHITESPACE_REGEX = Regex("""\s+""")

    fun normalizeKey(artist: String): String =
        artist.trim().lowercase(Locale.ROOT).replace(WHITESPACE_REGEX, " ")

    fun split(artist: String?, albumArtist: String? = null): List<SplitArtist> {
        val raw = artist?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals(UNKNOWN_ARTIST_DISPLAY, ignoreCase = true)) {
            val fallbackAlbumArtist = albumArtist?.trim()?.takeIf {
                it.isNotEmpty() && normalizeKey(it) !in VARIOUS_ARTISTS_NORMS
            }
            if (fallbackAlbumArtist == null) {
                return listOf(SplitArtist(UNKNOWN_ARTIST_NORM, UNKNOWN_ARTIST_DISPLAY))
            }
            return split(fallbackAlbumArtist, null)
        }

        // Build list of protected tokens (longest first to avoid partial shadowing)
        val protectedBands = ArrayList<String>(BAND_ALLOWLIST.size + 1)
        protectedBands.addAll(BAND_ALLOWLIST)
        val cleanAlbumArtist = albumArtist?.trim()?.takeIf {
            it.isNotEmpty() &&
                normalizeKey(it) !in VARIOUS_ARTISTS_NORMS &&
                !INLINE_CREDIT_REGEX.containsMatchIn(it) &&
                ';' !in it && '；' !in it && '、' !in it &&
                " / " !in it && " × " !in it
        }
        if (cleanAlbumArtist != null && protectedBands.none { it.equals(cleanAlbumArtist, ignoreCase = true) }) {
            protectedBands.add(cleanAlbumArtist)
        }
        protectedBands.sortByDescending { it.length }

        var working = raw
        val tokenMap = LinkedHashMap<String, String>()
        protectedBands.forEachIndexed { idx, band ->
            val pattern = Regex(Regex.escape(band), RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(working)) {
                val placeholder = "\u0001BAND_${idx}_\u0002"
                // Preserve canonical allowlist casing or matched albumArtist casing
                tokenMap[placeholder] = band
                working = pattern.replace(working, placeholder)
            }
        }

        // Unwrap bracketed feat./ft./cv. into standard `;` delimiter so outer parens don't stick to names
        working = BRACKETED_CREDIT_REGEX.replace(working) { match ->
            " ; ${match.groupValues[1].trim()} "
        }

        // Replace inline feat./ft./featuring/cv. with `;`
        working = INLINE_CREDIT_REGEX.replace(working, " ; ")

        // Split on `/`, `;`, `；`, `、`
        val segments = working.split(DELIMITER_REGEX)

        val result = ArrayList<SplitArtist>(segments.size)
        val seenNorms = HashSet<String>()

        for (seg in segments) {
            var restored = seg
            for ((placeholder, originalBand) in tokenMap) {
                if (placeholder in restored) {
                    restored = restored.replace(placeholder, originalBand)
                }
            }
            val cleaned = restored
                .trim()
                .trim('(', ')', '[', ']', '（', '）')
                .replace(WHITESPACE_REGEX, " ")
                .trim()

            if (cleaned.isEmpty()) continue
            val norm = normalizeKey(cleaned)
            if (norm.isNotEmpty() && seenNorms.add(norm)) {
                result.add(SplitArtist(norm = norm, display = cleaned))
            }
        }

        if (result.isEmpty()) {
            return listOf(SplitArtist(UNKNOWN_ARTIST_NORM, UNKNOWN_ARTIST_DISPLAY))
        }
        return result
    }
}
