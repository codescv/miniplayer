package com.miniplayer.app.core.feedback

import com.miniplayer.app.core.model.PracticeMath
import com.miniplayer.app.core.model.PracticeState
import com.miniplayer.app.core.update.SemanticVersion
import java.util.Locale

interface FeedbackPreferenceStore {
    fun getString(key: String, defaultValue: String): String
    fun putString(key: String, value: String)
}

class InMemoryFeedbackPreferenceStore(
    initial: Map<String, String> = emptyMap(),
) : FeedbackPreferenceStore {
    private val entries = LinkedHashMap<String, String>(initial)

    override fun getString(key: String, defaultValue: String): String = entries[key] ?: defaultValue

    override fun putString(key: String, value: String) {
        entries[key] = value
    }
}

data class FeedbackDiagnostics(
    val versionName: String,
    val versionCode: Int? = null,
    val androidVersion: String? = null,
    val deviceModel: String? = null,
    val currentTrackTitle: String? = null,
    val isVideo: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPracticeMode: Boolean = false,
    val practiceState: PracticeState? = null,
    val attachmentFileName: String? = null,
)

data class AgentFeedbackRequest(
    val recipientEmail: String,
    val summary: String,
    val details: String = "",
    val versionName: String,
    val includeDiagnostics: Boolean = true,
    val diagnostics: FeedbackDiagnostics? = null,
)

data class AgentFeedbackPayload(
    val recipientEmail: String,
    val subject: String,
    val body: String,
    val mailtoUri: String,
    val hasAttachment: Boolean,
)

/**
 * Pure-JVM helper for validating locally configured Agent Feedback email addresses and formatting
 * Vibe Coding `play: [vX.Y.Z] <summary>` email subjects, diagnostic bodies, and RFC 6068 `mailto:` URIs.
 *
 * Privacy invariant: zero hardcoded personal email addresses; default stored address is strictly `""`.
 */
object AgentFeedbackHelper {
    const val PREFS_NAME = "miniplayer_agent_feedback"
    const val KEY_AGENT_EMAIL = "agent_feedback_email"
    const val DEFAULT_EMAIL = ""
    const val EMAIL_PLACEHOLDER = "agent@example.com"
    const val PROJECT_ROUTING_PREFIX = "play"
    const val DEFAULT_SUMMARY = "使用反馈"
    const val MAX_SUMMARY_LENGTH = 120
    private const val FALLBACK_SUMMARY_LENGTH = 60

    fun normalizeEmail(raw: String?): String = raw?.trim().orEmpty()

    fun isValidEmail(raw: String?): Boolean {
        val email = normalizeEmail(raw)
        if (email.isEmpty() || email.length > 254) return false
        if (email.any { it <= ' ' || it.code == 0x7F }) return false

        val atIndex = email.indexOf('@')
        if (atIndex <= 0 || email.lastIndexOf('@') != atIndex) return false

        val local = email.substring(0, atIndex)
        val domain = email.substring(atIndex + 1)
        if (local.isEmpty() || local.length > 64 || domain.isEmpty() || domain.length > 253) return false
        if (local.startsWith('.') || local.endsWith('.') || local.contains("..")) return false
        if (!local.all { it.isAsciiAlphanumeric() || it in "._%+-" }) return false

        if (domain.startsWith('.') || domain.endsWith('.') || !domain.contains('.')) return false
        val labels = domain.split('.')
        if (labels.size < 2) return false
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return false
            if (label.startsWith('-') || label.endsWith('-')) return false
            if (!label.all { it.isAsciiAlphanumeric() || it == '-' }) return false
        }
        val tld = labels.last()
        return tld.length >= 2 && tld.all { it.isAsciiLetter() }
    }

    fun loadAgentEmail(store: FeedbackPreferenceStore): String =
        normalizeEmail(store.getString(KEY_AGENT_EMAIL, DEFAULT_EMAIL))

    fun saveAgentEmail(store: FeedbackPreferenceStore, rawEmail: String): String {
        val normalized = normalizeEmail(rawEmail)
        require(normalized.isEmpty() || isValidEmail(normalized)) {
            "Invalid Agent feedback email address"
        }
        store.putString(KEY_AGENT_EMAIL, normalized)
        return normalized
    }

    fun normalizeVersionTag(versionName: String?): String {
        val stripped = SemanticVersion.stripDebugSuffix(versionName)
            ?: versionName?.trim().orEmpty()
        val bare = stripped.trim().removePrefix("v").removePrefix("V").trim()
        return if (bare.isEmpty()) "v1.0.12" else "v$bare"
    }

    fun sanitizeSummary(summary: String?, detailsFallback: String? = null): String {
        val collapsed = collapseSingleLine(summary)
        if (collapsed.isNotEmpty()) {
            return truncateWithEllipsis(collapsed, MAX_SUMMARY_LENGTH)
        }
        val firstDetailLine = detailsFallback
            ?.lineSequence()
            ?.map { collapseSingleLine(it) }
            ?.firstOrNull { it.isNotEmpty() }
            .orEmpty()
        if (firstDetailLine.isNotEmpty()) {
            return truncateWithEllipsis(firstDetailLine, FALLBACK_SUMMARY_LENGTH)
        }
        return DEFAULT_SUMMARY
    }

    fun formatSubject(
        summary: String?,
        versionName: String?,
        detailsFallback: String? = null,
    ): String {
        val versionTag = normalizeVersionTag(versionName)
        val cleanSummary = sanitizeSummary(summary, detailsFallback)
        return "$PROJECT_ROUTING_PREFIX: [$versionTag] $cleanSummary"
    }

    fun formatDiagnosticsBlock(diagnostics: FeedbackDiagnostics): String {
        return buildString {
            appendLine("---")
            appendLine("诊断信息 (Play)")
            val versionTag = normalizeVersionTag(diagnostics.versionName)
            val codeSuffix = diagnostics.versionCode?.let { " ($it)" }.orEmpty()
            appendLine("应用版本: $versionTag$codeSuffix")

            val sysParts = listOfNotNull(
                diagnostics.androidVersion?.trim()?.takeIf { it.isNotEmpty() },
                diagnostics.deviceModel?.trim()?.takeIf { it.isNotEmpty() },
            )
            if (sysParts.isNotEmpty()) {
                appendLine("设备环境: ${sysParts.joinToString(" · ")}")
            }

            val track = diagnostics.currentTrackTitle?.trim()?.takeIf { it.isNotEmpty() }
            if (track != null) {
                val kind = if (diagnostics.isVideo) "视频" else "音频"
                val stateLabel = if (diagnostics.isPlaying) "播放中" else "已暂停"
                val pos = PracticeMath.formatTimeMs(diagnostics.positionMs, includeTenths = true)
                val dur = PracticeMath.formatTimeMs(diagnostics.durationMs, includeTenths = true)
                appendLine("当前媒体: $track ($kind · $stateLabel · $pos / $dur)")
            }

            val ps = diagnostics.practiceState
            if (diagnostics.isPracticeMode && ps != null) {
                val speedStr = String.format(Locale.US, "%.2fx", ps.practiceSpeed)
                val pitchStr = buildString {
                    append(if (ps.pitchSemitones >= 0) "+${ps.pitchSemitones}" else "${ps.pitchSemitones}")
                    append("st")
                    if (ps.pitchCents != 0) {
                        append(if (ps.pitchCents > 0) "+${ps.pitchCents}c" else "${ps.pitchCents}c")
                    }
                }
                val loopStr = if (ps.activeAMs != null && ps.activeBMs != null) {
                    val a = PracticeMath.formatTimeMs(ps.activeAMs, includeTenths = true)
                    val b = PracticeMath.formatTimeMs(ps.activeBMs, includeTenths = true)
                    val activeFlag = if (ps.loopEnabled) "激活" else "挂起"
                    "A $a - B $b ($activeFlag)"
                } else {
                    "未设循环"
                }
                appendLine("练习状态: 速度 $speedStr · 变调 $pitchStr · BPM ${ps.bpm} · $loopStr")
            } else if (track != null) {
                appendLine("播放模式: 听歌模式")
            }

            diagnostics.attachmentFileName?.trim()?.takeIf { it.isNotEmpty() }?.let { fileName ->
                appendLine("附加截图: $fileName")
            }
        }.trimEnd()
    }

    fun formatBody(
        summary: String?,
        details: String?,
        includeDiagnostics: Boolean = true,
        diagnostics: FeedbackDiagnostics? = null,
    ): String {
        val normalizedDetails = details
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.trim()
            .orEmpty()
        val mainContent = normalizedDetails.ifEmpty { sanitizeSummary(summary, null) }
        return if (includeDiagnostics && diagnostics != null) {
            "$mainContent\n\n${formatDiagnosticsBlock(diagnostics)}"
        } else {
            mainContent
        }
    }

    /**
     * RFC 3986 / RFC 6068 percent-encoding (`%20` for spaces, `%2B` for `+`) so Android
     * `MailTo.parse()` / `Uri.decode()` never leaves literal `+` signs in email subjects or bodies.
     */
    fun percentEncodeUriComponent(value: String): String {
        if (value.isEmpty()) return ""
        val bytes = value.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size + 16)
        for (b in bytes) {
            val code = b.toInt() and 0xFF
            val ch = code.toChar()
            if (isUnreservedUriChar(ch)) {
                sb.append(ch)
            } else {
                sb.append('%')
                sb.append(HEX_DIGITS[code ushr 4])
                sb.append(HEX_DIGITS[code and 0x0F])
            }
        }
        return sb.toString()
    }

    fun buildMailtoUri(
        recipientEmail: String,
        subject: String,
        body: String,
    ): String {
        val normalizedEmail = normalizeEmail(recipientEmail)
        require(isValidEmail(normalizedEmail)) { "Invalid recipient email address" }

        val atIndex = normalizedEmail.indexOf('@')
        val localPart = normalizedEmail.substring(0, atIndex)
        val domainPart = normalizedEmail.substring(atIndex + 1)
        val encodedAddr = buildString {
            for (ch in localPart) {
                if (isUnreservedUriChar(ch) || ch == '+') {
                    append(ch)
                } else {
                    append(percentEncodeUriComponent(ch.toString()))
                }
            }
            append('@')
            append(domainPart)
        }
        val encodedSubject = percentEncodeUriComponent(subject)
        val encodedBody = percentEncodeUriComponent(body)
        return "mailto:$encodedAddr?subject=$encodedSubject&body=$encodedBody"
    }

    fun buildPayload(request: AgentFeedbackRequest): AgentFeedbackPayload {
        val normalizedEmail = normalizeEmail(request.recipientEmail)
        require(isValidEmail(normalizedEmail)) { "Invalid recipient email address" }

        val subject = formatSubject(
            summary = request.summary,
            versionName = request.versionName,
            detailsFallback = request.details,
        )
        val body = formatBody(
            summary = request.summary,
            details = request.details,
            includeDiagnostics = request.includeDiagnostics,
            diagnostics = request.diagnostics,
        )
        val mailtoUri = buildMailtoUri(
            recipientEmail = normalizedEmail,
            subject = subject,
            body = body,
        )
        return AgentFeedbackPayload(
            recipientEmail = normalizedEmail,
            subject = subject,
            body = body,
            mailtoUri = mailtoUri,
            hasAttachment = !request.diagnostics?.attachmentFileName.isNullOrBlank(),
        )
    }

    private fun collapseSingleLine(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.replace(WHITESPACE_REGEX, " ").trim()
    }

    private fun truncateWithEllipsis(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take((maxChars - 1).coerceAtLeast(1)).trimEnd() + "…"
    }

    private fun Char.isAsciiLetter(): Boolean =
        (this in 'a'..'z') || (this in 'A'..'Z')

    private fun Char.isAsciiAlphanumeric(): Boolean =
        isAsciiLetter() || (this in '0'..'9')

    private fun isUnreservedUriChar(ch: Char): Boolean =
        ch.isAsciiAlphanumeric() || ch == '-' || ch == '_' || ch == '.' || ch == '~'

    private val WHITESPACE_REGEX = Regex("\\s+")
    private const val HEX_DIGITS = "0123456789ABCDEF"
}
