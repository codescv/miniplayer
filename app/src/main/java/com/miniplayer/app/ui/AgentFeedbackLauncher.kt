package com.miniplayer.app.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.miniplayer.app.core.feedback.AgentFeedbackPayload
import com.miniplayer.app.core.feedback.FeedbackPreferenceStore
import java.io.File
import java.io.IOException

class SharedPreferencesFeedbackStore(
    private val prefs: SharedPreferences,
) : FeedbackPreferenceStore {
    override fun getString(key: String, defaultValue: String): String =
        prefs.getString(key, defaultValue) ?: defaultValue

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

data class StagedFeedbackAttachment(
    val file: File,
    val contentUri: Uri,
    val displayName: String,
    val mimeType: String,
)

object AgentFeedbackLauncher {
    private const val MAX_ATTACHMENT_BYTES = 20L * 1024L * 1024L
    private const val STALE_ATTACHMENT_AGE_MS = 24L * 60L * 60L * 1000L

    fun stageAttachmentFromUri(
        context: Context,
        sourceUri: Uri,
    ): StagedFeedbackAttachment {
        val appContext = context.applicationContext ?: context
        val cr = appContext.contentResolver
        val rawMime = cr.getType(sourceUri)?.trim()?.takeIf { it.startsWith("image/", ignoreCase = true) }
            ?: "image/jpeg"
        val queriedName = runCatching {
            cr.query(sourceUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        val fallbackExt = when {
            rawMime.contains("png", ignoreCase = true) -> ".png"
            rawMime.contains("webp", ignoreCase = true) -> ".webp"
            rawMime.contains("gif", ignoreCase = true) -> ".gif"
            else -> ".jpg"
        }
        val baseDisplayName = queriedName ?: "screenshot$fallbackExt"
        val safeName = baseDisplayName
            .replace(Regex("[^0-9A-Za-z._-]"), "_")
            .trim('_')
            .ifEmpty { "screenshot$fallbackExt" }

        val feedbackDir = File(appContext.cacheDir, "feedback").apply { mkdirs() }
        purgeStaleAttachments(feedbackDir)

        val destFile = File(feedbackDir, "fb_${System.currentTimeMillis()}_$safeName")
        val inputStream = cr.openInputStream(sourceUri)
            ?: throw IOException("无法读取所选图片")

        var totalBytes = 0L
        var copiedOk = false
        try {
            inputStream.use { ins ->
                destFile.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        totalBytes += n
                        if (totalBytes > MAX_ATTACHMENT_BYTES) {
                            throw IOException("截图文件超过 20 MB 上限")
                        }
                        out.write(buf, 0, n)
                    }
                    out.flush()
                }
            }
            if (totalBytes <= 0L) {
                throw IOException("所选图片内容为空")
            }
            copiedOk = true
        } finally {
            if (!copiedOk) {
                runCatching { destFile.delete() }
            }
        }

        val authority = "${appContext.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(appContext, authority, destFile)
        return StagedFeedbackAttachment(
            file = destFile,
            contentUri = contentUri,
            displayName = baseDisplayName,
            mimeType = rawMime,
        )
    }

    fun launchFeedbackEmail(
        context: Context,
        payload: AgentFeedbackPayload,
        attachment: StagedFeedbackAttachment? = null,
    ): Boolean {
        val needNewTask = context !is Activity
        if (attachment == null) {
            val sendToIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse(payload.mailtoUri)
                putExtra(Intent.EXTRA_EMAIL, arrayOf(payload.recipientEmail))
                putExtra(Intent.EXTRA_SUBJECT, payload.subject)
                putExtra(Intent.EXTRA_TEXT, payload.body)
                if (needNewTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(sendToIntent)
                return true
            } catch (_: ActivityNotFoundException) {
            }

            val fallbackSend = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(payload.recipientEmail))
                putExtra(Intent.EXTRA_SUBJECT, payload.subject)
                putExtra(Intent.EXTRA_TEXT, payload.body)
            }
            val chooser = Intent.createChooser(fallbackSend, "发送反馈给 Agent").apply {
                if (needNewTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return runCatching {
                context.startActivity(chooser)
                true
            }.getOrDefault(false)
        }

        fun buildAttachmentSendIntent(withMailtoSelector: Boolean): Intent =
            Intent(Intent.ACTION_SEND).apply {
                type = attachment.mimeType.ifBlank { "image/*" }
                putExtra(Intent.EXTRA_EMAIL, arrayOf(payload.recipientEmail))
                putExtra(Intent.EXTRA_SUBJECT, payload.subject)
                putExtra(Intent.EXTRA_TEXT, payload.body)
                putExtra(Intent.EXTRA_STREAM, attachment.contentUri)
                clipData = ClipData.newRawUri("feedback_attachment", attachment.contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (withMailtoSelector) {
                    selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                }
                if (needNewTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        try {
            context.startActivity(buildAttachmentSendIntent(withMailtoSelector = true))
            return true
        } catch (_: ActivityNotFoundException) {
        }

        val chooser = Intent.createChooser(
            buildAttachmentSendIntent(withMailtoSelector = false),
            "发送反馈给 Agent",
        ).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (needNewTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }

    private fun purgeStaleAttachments(feedbackDir: File) {
        val now = System.currentTimeMillis()
        feedbackDir.listFiles()?.forEach { f ->
            if (f.isFile && now - f.lastModified() > STALE_ATTACHMENT_AGE_MS) {
                runCatching { f.delete() }
            }
        }
    }
}
