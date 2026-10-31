package com.miniplayer.app.core.metadata

import com.miniplayer.app.core.model.LrcLine
import com.miniplayer.app.core.model.LyricsData
import com.miniplayer.app.core.model.LyricsSource
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Parser for `.lrc` time-synced and plain lyrics (`DESIGN.md` §6.4 #2).
 *
 * Supports:
 * - Standard `[mm:ss.xx]`, `[mm:ss.xxx]`, `[mm:ss:xx]`, and `[mm:ss]` timestamps
 * - Multi-timestamp compressed chorus lines (`[00:18.20][01:42.50]Lyric text`)
 * - Global `[offset:+/-ms]` calibration header
 * - UTF-8 BOM, UTF-16 LE/BE BOM, strict UTF-8, and GB18030 fallback decoding for local `.lrc` sidecars
 */
object LrcParser {

    private val TIME_TAG_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val OFFSET_TAG_REGEX = Regex("""^\[\s*offset\s*:\s*([+-]?\d+)\s*]$""", RegexOption.IGNORE_CASE)
    private val META_HEADER_REGEX = Regex(
        """^\[\s*(?:ti|ar|al|by|re|ve|length|id|tool|au)\s*:[^\]]*]$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(rawText: String?, source: LyricsSource): LyricsData? {
        val cleaned = rawText
            ?.removePrefix("\uFEFF")
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        var offsetMs = 0L
        val syncedLines = ArrayList<LrcLine>()
        val plainLines = ArrayList<String>()

        for (rawLine in cleaned.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            val offsetMatch = OFFSET_TAG_REGEX.matchEntire(trimmed)
            if (offsetMatch != null) {
                offsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                continue
            }

            if (META_HEADER_REGEX.matches(trimmed)) {
                continue
            }

            val matches = TIME_TAG_REGEX.findAll(trimmed).toList()
            if (matches.isNotEmpty()) {
                val lastTagEnd = matches.last().range.last + 1
                val lyricText = if (lastTagEnd <= trimmed.length) {
                    // Strip inline word-level Karoke <mm:ss.xx> tags if present
                    trimmed.substring(lastTagEnd)
                        .replace(Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>"""), "")
                        .trim()
                } else {
                    ""
                }

                for (match in matches) {
                    val minutes = match.groupValues[1].toLongOrNull() ?: continue
                    val seconds = match.groupValues[2].toLongOrNull() ?: continue
                    val fracStr = match.groupValues[3]
                    val millis = when (fracStr.length) {
                        0 -> 0L
                        1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                        2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                        else -> fracStr.take(3).toLongOrNull() ?: 0L
                    }
                    // Note: in LRC spec, positive [offset:+500] delays/shifts timestamps earlier or later;
                    // standard players apply (timestamp - offset) or clamp >= 0. Let's apply (rawMs - offsetMs).coerceAtLeast(0L).
                    val rawTimeMs = minutes * 60_000L + seconds * 1_000L + millis
                    val adjustedMs = (rawTimeMs - offsetMs).coerceAtLeast(0L)
                    syncedLines.add(LrcLine(timeMs = adjustedMs, text = lyricText))
                }
                if (lyricText.isNotEmpty()) {
                    plainLines.add(lyricText)
                }
            } else {
                plainLines.add(trimmed)
            }
        }

        return if (syncedLines.isNotEmpty()) {
            syncedLines.sortBy { it.timeMs }
            LyricsData(
                lines = syncedLines,
                plainText = plainLines.joinToString("\n").takeIf { it.isNotBlank() },
                source = source,
            )
        } else {
            val plain = plainLines.joinToString("\n").trim()
            if (plain.isEmpty()) null else LyricsData(lines = emptyList(), plainText = plain, source = source)
        }
    }

    /**
     * Binary search for the active lyric line at [positionMs] (`-1` if before the first line).
     */
    fun findActiveLineIndex(lines: List<LrcLine>, positionMs: Long): Int {
        if (lines.isEmpty() || positionMs < lines.first().timeMs) return -1
        var low = 0
        var high = lines.lastIndex
        var ans = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return ans
    }

    /**
     * Decodes raw `.lrc` bytes handling UTF-8 BOM, UTF-16 LE/BE BOM, strict UTF-8, and GB18030 CJK fallback.
     */
    fun decodeBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }

        val strictUtf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (strictUtf8 != null) return strictUtf8

        val gbCharset = runCatching { Charset.forName("GB18030") }.getOrNull() ?: Charsets.UTF_8
        return String(bytes, gbCharset)
    }
}
