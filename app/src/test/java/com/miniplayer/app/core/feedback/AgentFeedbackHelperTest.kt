package com.miniplayer.app.core.feedback

import com.miniplayer.app.core.model.PracticeState
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentFeedbackHelperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `emailValidation accepts valid addresses and rejects blank malformed or newline header injection`() {
        assertTrue(AgentFeedbackHelper.isValidEmail("agent@example.com"))
        assertTrue(AgentFeedbackHelper.isValidEmail("  vibe.coder+play_01@sub-mail.example.org  "))
        assertTrue(AgentFeedbackHelper.isValidEmail("feedback123@dev.example.net"))

        assertFalse(AgentFeedbackHelper.isValidEmail(null))
        assertFalse(AgentFeedbackHelper.isValidEmail(""))
        assertFalse(AgentFeedbackHelper.isValidEmail("   "))
        assertFalse(AgentFeedbackHelper.isValidEmail("plain-string"))
        assertFalse(AgentFeedbackHelper.isValidEmail("@example.com"))
        assertFalse(AgentFeedbackHelper.isValidEmail("agent@"))
        assertFalse(AgentFeedbackHelper.isValidEmail("agent@localhost"))
        assertFalse(AgentFeedbackHelper.isValidEmail("agent@.example.com"))
        assertFalse(AgentFeedbackHelper.isValidEmail("agent@example..com"))
        assertFalse(AgentFeedbackHelper.isValidEmail(".agent@example.com"))
        assertFalse(AgentFeedbackHelper.isValidEmail("agent..two@example.com"))
        assertFalse(AgentFeedbackHelper.isValidEmail("agent@-bad.example.com"))
        assertFalse(AgentFeedbackHelper.isValidEmail("agent@example.c"))
        assertFalse(AgentFeedbackHelper.isValidEmail("agent@example.12"))
        assertFalse(AgentFeedbackHelper.isValidEmail("agent name@example.com"))
        assertFalse(AgentFeedbackHelper.isValidEmail("agent@example.com\nBcc:other@example.org"))
        assertFalse(AgentFeedbackHelper.isValidEmail("agent@example.com\r\nSubject:hijack"))
    }

    @Test
    fun `preferenceStore defaults to empty string saves normalized email clears cleanly and rejects invalid address`() {
        val store = InMemoryFeedbackPreferenceStore()
        assertEquals("", AgentFeedbackHelper.loadAgentEmail(store))

        val saved = AgentFeedbackHelper.saveAgentEmail(store, "  agent+play@example.com  ")
        assertEquals("agent+play@example.com", saved)
        assertEquals("agent+play@example.com", AgentFeedbackHelper.loadAgentEmail(store))
        assertEquals("agent+play@example.com", store.getString(AgentFeedbackHelper.KEY_AGENT_EMAIL, ""))

        // Clearing back to empty string is permitted so user can remove local config
        val cleared = AgentFeedbackHelper.saveAgentEmail(store, "   ")
        assertEquals("", cleared)
        assertEquals("", AgentFeedbackHelper.loadAgentEmail(store))

        // Invalid non-empty email must fail closed without overwriting existing valid value
        AgentFeedbackHelper.saveAgentEmail(store, "valid@example.com")
        val error = runCatching {
            AgentFeedbackHelper.saveAgentEmail(store, "not-an-email")
        }.exceptionOrNull()
        assertNotNull(error)
        assertEquals("valid@example.com", AgentFeedbackHelper.loadAgentEmail(store))
    }

    @Test
    fun `formatSubject enforces play routing prefix v1_0_15 tag strips debug suffix and sanitizes summary`() {
        assertEquals(
            "play: [v1.0.15] 练习模式希望支持导出循环片段",
            AgentFeedbackHelper.formatSubject(
                summary = "练习模式希望支持导出循环片段",
                versionName = "1.0.15",
            ),
        )
        // Debug build suffix (-DEBUG) must normalize cleanly to [v1.0.15] and collapse newlines to single space
        assertEquals(
            "play: [v1.0.15] 五轨混音 音量记忆优化",
            AgentFeedbackHelper.formatSubject(
                summary = "  五轨混音\n\t音量记忆优化  ",
                versionName = "1.0.15-DEBUG",
            ),
        )
        // Leading 'v' is not doubled and blank versionName falls back to v1.0.15
        assertEquals(
            "play: [v1.0.15] 歌词字体大小调节",
            AgentFeedbackHelper.formatSubject(
                summary = "歌词字体大小调节",
                versionName = "v1.0.15",
            ),
        )
        assertEquals(
            "play: [v1.0.15] 默认版本兜底",
            AgentFeedbackHelper.formatSubject(
                summary = "默认版本兜底",
                versionName = "",
            ),
        )
        // Blank summary falls back to first non-empty line of details
        assertEquals(
            "play: [v1.0.15] 第一行详细描述自动提炼为主题",
            AgentFeedbackHelper.formatSubject(
                summary = "   ",
                versionName = "1.0.15",
                detailsFallback = "\n  第一行详细描述自动提炼为主题 \n第二行补充细节",
            ),
        )
        // Both blank falls back to DEFAULT_SUMMARY
        assertEquals(
            "play: [v1.0.15] 使用反馈",
            AgentFeedbackHelper.formatSubject(
                summary = "",
                versionName = "1.0.15",
                detailsFallback = "   ",
            ),
        )
        // Overly long summary is truncated safely
        val longSummary = "A".repeat(200)
        val truncatedSubject = AgentFeedbackHelper.formatSubject(longSummary, "1.0.15")
        assertTrue(truncatedSubject.startsWith("play: [v1.0.15] "))
        assertTrue(truncatedSubject.endsWith("…"))
        assertTrue(truncatedSubject.length <= "play: [v1.0.15] ".length + AgentFeedbackHelper.MAX_SUMMARY_LENGTH)
    }

    @Test
    fun `formatBody formats practice and casual diagnostics cleanly and honors includeDiagnostics toggle`() {
        val practiceDiagnostics = FeedbackDiagnostics(
            versionName = "1.0.15-DEBUG",
            versionCode = 10015,
            androidVersion = "Android 15 (SDK 35)",
            deviceModel = "OnePlus 13T",
            currentTrackTitle = "Constellation Backing",
            isVideo = false,
            isPlaying = true,
            positionMs = 72_400L,
            durationMs = 240_000L,
            isPracticeMode = true,
            practiceState = PracticeState(
                itemId = 42L,
                practiceSpeed = 0.85f,
                pitchSemitones = -1,
                pitchCents = -15,
                bpm = 128,
                loopEnabled = true,
                activeAMs = 8_000L,
                activeBMs = 16_000L,
            ),
            attachmentFileName = "loop_ui.png",
        )

        val bodyWithDiag = AgentFeedbackHelper.formatBody(
            summary = "小节吸附微调",
            details = "在 128 BPM 下希望支持半小节切换。",
            includeDiagnostics = true,
            diagnostics = practiceDiagnostics,
        )
        assertTrue(bodyWithDiag.startsWith("在 128 BPM 下希望支持半小节切换。"))
        assertTrue(bodyWithDiag.contains("应用版本: v1.0.15 (10015)"))
        assertTrue(bodyWithDiag.contains("设备环境: Android 15 (SDK 35) · OnePlus 13T"))
        assertTrue(bodyWithDiag.contains("当前媒体: Constellation Backing (音频 · 播放中 · 01:12.4 / 04:00.0)"))
        assertTrue(bodyWithDiag.contains("练习状态: 速度 0.85x · 变调 -1st-15c · BPM 128 · A 00:08.0 - B 00:16.0 (激活)"))
        assertFalse(bodyWithDiag.contains("共振峰"))
        assertTrue(bodyWithDiag.contains("附加截图: loop_ui.png"))

        val bodyWithoutDiag = AgentFeedbackHelper.formatBody(
            summary = "小节吸附微调",
            details = "在 128 BPM 下希望支持半小节切换。",
            includeDiagnostics = false,
            diagnostics = practiceDiagnostics,
        )
        assertEquals("在 128 BPM 下希望支持半小节切换。", bodyWithoutDiag)
    }

    @Test
    fun `buildPayload percentEncodes spaces as pct20 pluses ampersands and CJK UTF8 for Android MailTo compatibility`() {
        val payload = AgentFeedbackHelper.buildPayload(
            AgentFeedbackRequest(
                recipientEmail = "agent+triage@example.com",
                summary = "A+B & C=1 混音反馈",
                details = "第一行 A+B\n第二行 100% 正常",
                versionName = "1.0.15",
                includeDiagnostics = false,
            ),
        )

        assertEquals("agent+triage@example.com", payload.recipientEmail)
        assertEquals("play: [v1.0.15] A+B & C=1 混音反馈", payload.subject)
        assertFalse(payload.hasAttachment)
        assertTrue(payload.mailtoUri.startsWith("mailto:agent+triage@example.com?subject="))

        // Spaces MUST be encoded as %20 (never '+') so Android Uri.decode / MailTo.parse preserves spaces
        val queryPart = payload.mailtoUri.substringAfter('?')
        assertFalse("Query must not contain raw spaces", queryPart.contains(' '))
        assertFalse("Query must encode '+' as %2B rather than raw '+'", queryPart.contains('+'))

        val params = queryPart.split('&').associate { pair ->
            val idx = pair.indexOf('=')
            pair.substring(0, idx) to percentDecodeUtf8(pair.substring(idx + 1))
        }
        assertEquals("play: [v1.0.15] A+B & C=1 混音反馈", params["subject"])
        assertEquals("第一行 A+B\n第二行 100% 正常", params["body"])
    }

    @Test
    fun `unified feedback sheet send gate requires valid email and at least one of title description or image`() {
        val email = "agent@example.com"
        // No content in any of the 3 core fields (标题 / 问题描述 / 增加图片) -> blocked
        assertFalse(AgentFeedbackHelper.hasFeedbackContent("", "", hasAttachment = false))
        assertFalse(AgentFeedbackHelper.hasFeedbackContent("   ", "\n\t ", hasAttachment = false))
        assertFalse(AgentFeedbackHelper.hasFeedbackContent(null, null, hasAttachment = false))
        assertFalse(AgentFeedbackHelper.canSendFeedback(email, "  ", "", hasAttachment = false))

        // Any single field is sufficient
        assertTrue(AgentFeedbackHelper.canSendFeedback(email, "播放卡顿", "", hasAttachment = false))
        assertTrue(AgentFeedbackHelper.canSendFeedback(email, "", "切歌后进度条不刷新", hasAttachment = false))
        assertTrue(AgentFeedbackHelper.canSendFeedback(email, "", "", hasAttachment = true))
        assertTrue(AgentFeedbackHelper.canSendFeedback("  $email  ", "标题", "描述", hasAttachment = true))

        // Invalid / blank email always blocks, even with full content
        assertFalse(AgentFeedbackHelper.canSendFeedback("", "标题", "描述", hasAttachment = true))
        assertFalse(AgentFeedbackHelper.canSendFeedback(null, "标题", "描述", hasAttachment = true))
        assertFalse(AgentFeedbackHelper.canSendFeedback("not-an-email", "标题", "描述", hasAttachment = true))
        assertFalse(AgentFeedbackHelper.canSendFeedback("agent@example.com\nBcc:x@example.org", "标题", "", false))
    }

    @Test
    fun `buildPayload uses title for v1_0_15 subject description for body and supports image only feedback`() {
        // 标题 -> subject, 问题描述 -> body
        val full = AgentFeedbackHelper.buildPayload(
            AgentFeedbackRequest(
                recipientEmail = "agent@example.com",
                summary = "歌词页闪退",
                details = "打开歌词页后立即闪退\n复现率 100%",
                versionName = "1.0.15-DEBUG",
                includeDiagnostics = false,
            ),
        )
        assertEquals("play: [v1.0.15] 歌词页闪退", full.subject)
        assertEquals("打开歌词页后立即闪退\n复现率 100%", full.body)
        assertFalse(full.hasAttachment)

        // Blank versionName falls back to the current release tag
        assertEquals("v1.0.15", AgentFeedbackHelper.normalizeVersionTag(""))
        assertEquals("v1.0.15", AgentFeedbackHelper.normalizeVersionTag(null))

        // 问题描述 only -> first line becomes subject
        val detailsOnly = AgentFeedbackHelper.buildPayload(
            AgentFeedbackRequest(
                recipientEmail = "agent@example.com",
                summary = "",
                details = "\n  循环 A-B 点无法拖动 \n第二行",
                versionName = "1.0.15",
                includeDiagnostics = false,
            ),
        )
        assertEquals("play: [v1.0.15] 循环 A-B 点无法拖动", detailsOnly.subject)

        // 增加图片 only (no 标题 / 问题描述) -> image-only fallback subject/body, flagged as attachment
        val imageOnly = AgentFeedbackHelper.buildPayload(
            AgentFeedbackRequest(
                recipientEmail = "agent@example.com",
                summary = "  ",
                details = "",
                versionName = "1.0.15",
                includeDiagnostics = false,
                hasAttachment = true,
            ),
        )
        assertEquals("play: [v1.0.15] ${AgentFeedbackHelper.ATTACHMENT_ONLY_SUMMARY}", imageOnly.subject)
        assertEquals(AgentFeedbackHelper.ATTACHMENT_ONLY_SUMMARY, imageOnly.body)
        assertTrue(imageOnly.hasAttachment)

        // Attachment inferred from diagnostics file name stays backward compatible
        val viaDiagnostics = AgentFeedbackHelper.buildPayload(
            AgentFeedbackRequest(
                recipientEmail = "agent@example.com",
                summary = "",
                versionName = "1.0.15",
                includeDiagnostics = true,
                diagnostics = FeedbackDiagnostics(versionName = "1.0.15", versionCode = 10015, attachmentFileName = "shot.png"),
            ),
        )
        assertTrue(viaDiagnostics.hasAttachment)
        assertTrue(viaDiagnostics.subject.endsWith(AgentFeedbackHelper.ATTACHMENT_ONLY_SUMMARY))
        assertTrue(viaDiagnostics.body.contains("附加截图: shot.png"))
    }

    @Test
    fun `settings screen exposes a single feedback entry and sheet labels title description and add image`() {
        val repoRoot = locateRepoRoot()
        val settings = File(repoRoot, "app/src/main/java/com/miniplayer/app/ui/SettingsScreen.kt").readText()
        // Single entry: one 发送反馈 button, no separate email config dialog / button
        assertFalse(settings.contains("AgentEmailConfigDialog"))
        assertFalse(settings.contains("showEmailConfigDialog"))
        assertFalse(settings.contains("Text(\"Agent 反馈邮箱\")"))
        assertEquals(1, Regex("""Text\("发送反馈"\)""").findAll(settings).count())
        // Sheet keeps inline email + 保存 and the 3 labeled core fields
        assertTrue(settings.contains("Agent 反馈邮箱（仅保存在本机）"))
        assertTrue(settings.contains("label = { Text(\"标题\") }"))
        assertTrue(settings.contains("label = { Text(\"问题描述\") }"))
        assertTrue(settings.contains("else -> \"增加图片\""))
        assertTrue(settings.contains("AgentFeedbackHelper.canSendFeedback("))
    }

    @Test
    fun `repository contains zero personal emails or secret tokens and feedback helper is pure JVM`() {
        val repoRoot = locateRepoRoot()
        val violations = scanTreeForLeaks(repoRoot)
        assertTrue(
            "Found forbidden email/secret patterns in repository:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )

        // Synthetic leak detector verification
        val syntheticDir = tempFolder.newFolder("synthetic_leak_check")
        val corpDomain = "@" + "goo" + "gle" + ".com"
        File(syntheticDir, "Sample.kt").writeText("val leaked = \"user$corpDomain\"\n")
        val syntheticHits = scanTreeForLeaks(syntheticDir)
        assertEquals(1, syntheticHits.size)

        // Pure-JVM invariant on com.miniplayer.app.core.feedback
        val feedbackCoreDir = File(repoRoot, "app/src/main/java/com/miniplayer/app/core/feedback")
        assertTrue(feedbackCoreDir.isDirectory)
        val androidImports = ArrayList<String>()
        feedbackCoreDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { ktFile ->
                ktFile.useLines { lines ->
                    lines.forEachIndexed { idx, line ->
                        if (FORBIDDEN_ANDROID_IMPORT.containsMatchIn(line)) {
                            androidImports.add("${ktFile.name}:${idx + 1}: $line")
                        }
                    }
                }
            }
        assertTrue(
            "Expected zero Android imports in core/feedback:\n${androidImports.joinToString("\n")}",
            androidImports.isEmpty(),
        )
    }

    companion object {
        private val SKIPPED_DIR_NAMES = setOf(
            ".git",
            "build",
            ".gradle",
            ".kotlin",
            ".idea",
        )

        private val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "webp", "gif", "ico",
            "jar", "apk", "aab", "dex", "class", "so", "bin", "stm", "stems", "onnx", "dat",
            "keystore", "jks", "p12", "zip", "gz", "pdf", "ttf", "otf", "woff", "woff2",
        )

        // Constructed dynamically so this test file contains zero literal forbidden strings.
        private val FORBIDDEN_PATTERNS: List<Pair<String, Regex>> = listOf(
            "Corporate email domain" to Regex("@" + "goo" + "gle" + "\\.com\\b", RegexOption.IGNORE_CASE),
            "OAuth client secret" to Regex("GOC" + "SPX-" + "[A-Za-z0-9_-]{20,}"),
            "OAuth access token" to Regex("ya" + "29\\." + "[A-Za-z0-9_-]{20,}"),
            "Cloud API key" to Regex("AI" + "za" + "[0-9A-Za-z_-]{35}"),
        )

        private val FORBIDDEN_ANDROID_IMPORT = Regex("""^\s*import\s+(android|androidx|dalvik)\.""")

        private fun locateRepoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").isFile && File(dir, "app").isDirectory) {
                    return dir
                }
                dir = dir.parentFile
            }
            error("Could not locate repository root containing settings.gradle.kts")
        }

        private fun scanTreeForLeaks(root: File): List<String> {
            val hits = ArrayList<String>()
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)

            root.walkTopDown()
                .onEnter { dir -> dir == root || dir.name !in SKIPPED_DIR_NAMES }
                .filter { it.isFile }
                .forEach { file ->
                    if (file.extension.lowercase() in BINARY_EXTENSIONS) return@forEach
                    if (file.length() > 4L * 1024L * 1024L) return@forEach
                    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
                    val probeLen = minOf(bytes.size, 4096)
                    for (i in 0 until probeLen) {
                        if (bytes[i] == 0.toByte()) return@forEach
                    }
                    val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
                    val relPath = file.relativeTo(root).path
                    text.lineSequence().forEachIndexed { idx, line ->
                        for ((label, regex) in FORBIDDEN_PATTERNS) {
                            if (regex.containsMatchIn(line)) {
                                hits.add("$relPath:${idx + 1}: matched $label")
                            }
                        }
                    }
                }
            return hits
        }

        private fun percentDecodeUtf8(encoded: String): String {
            val out = ByteArrayOutputStream(encoded.length)
            var i = 0
            while (i < encoded.length) {
                val ch = encoded[i]
                if (ch == '%' && i + 2 < encoded.length) {
                    val hi = Character.digit(encoded[i + 1], 16)
                    val lo = Character.digit(encoded[i + 2], 16)
                    if (hi >= 0 && lo >= 0) {
                        out.write((hi shl 4) or lo)
                        i += 3
                        continue
                    }
                }
                out.write(ch.code)
                i++
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
