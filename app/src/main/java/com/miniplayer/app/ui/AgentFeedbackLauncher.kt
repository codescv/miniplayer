package com.miniplayer.app.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.miniplayer.app.core.feedback.AgentFeedbackPayload
import com.miniplayer.app.core.feedback.FeedbackEmailTargetResolver
import com.miniplayer.app.core.feedback.FeedbackSendIntentSpec
import com.miniplayer.app.core.feedback.FeedbackPreferenceStore
import com.miniplayer.app.core.feedback.ResolvedEmailActivity
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
        val pm = context.packageManager
        val mailtoProbes = listOf(
            Intent(Intent.ACTION_SENDTO, Uri.parse(payload.mailtoUri)),
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")),
        )
        val defaultMailto = mailtoProbes.firstNotNullOfOrNull { probe ->
            runCatching { pm.resolveDefaultActivity(probe) }.getOrNull()
        }
        val mailtoActivities = mailtoProbes
            .flatMap { probe ->
                pm.queryActivitiesSafe(probe, PackageManager.MATCH_DEFAULT_ONLY) +
                    pm.queryActivitiesSafe(probe, 0)
            }
            .distinct()

        if (attachment == null) {
            fun buildSendTo(pkg: String?): Intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse(payload.mailtoUri)
                if (pkg != null) setPackage(pkg)
                putExtra(Intent.EXTRA_EMAIL, arrayOf(payload.recipientEmail))
                putExtra(Intent.EXTRA_SUBJECT, payload.subject)
                putExtra(Intent.EXTRA_TEXT, payload.body)
                if (needNewTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.tryStart(buildSendTo(null))) return true
            val emailPackages = FeedbackEmailTargetResolver.orderEmailPackages(
                defaultMailto = defaultMailto,
                mailtoActivities = mailtoActivities,
                includeGmailIfInstalled = false,
            )
            for (pkg in emailPackages) {
                if (context.tryStart(buildSendTo(pkg))) return true
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
            return context.tryStart(chooser)
        }

        // v1.0.15: launch the email client's ACTION_SEND compose activity explicitly (no selector) so
        // Android 15–17 intent-filter enforcement never rejects it and ColorOS/OxygenOS never turns it
        // into the generic "分享1张图片" share sheet.
        val targets = FeedbackEmailTargetResolver.resolveComposeTargets(
            defaultMailto = defaultMailto,
            mailtoActivities = mailtoActivities,
            attachmentMime = attachment.mimeType,
        ) { pkg, mime ->
            val probe = Intent(Intent.ACTION_SEND).apply {
                type = mime
                setPackage(pkg)
            }
            pm.queryActivitiesSafe(probe, PackageManager.MATCH_DEFAULT_ONLY)
                .ifEmpty { pm.queryActivitiesSafe(probe, 0) }
        }
        for (target in targets) {
            val spec = FeedbackEmailTargetResolver.buildAttachmentSendSpec(
                payload = payload,
                target = target,
                attachmentUri = attachment.contentUri.toString(),
            )
            runCatching {
                context.grantUriPermission(
                    target.packageName,
                    attachment.contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val intent = spec.toIntent(attachment.contentUri).apply {
                if (needNewTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.tryStart(intent)) return true
        }

        // Last resort only when no email client exposes a visible ACTION_SEND compose activity.
        val fallbackSend = Intent(Intent.ACTION_SEND).apply {
            type = attachment.mimeType.ifBlank { "image/*" }
            putExtra(Intent.EXTRA_EMAIL, arrayOf(payload.recipientEmail))
            putExtra(Intent.EXTRA_SUBJECT, payload.subject)
            putExtra(Intent.EXTRA_TEXT, payload.body)
            putExtra(Intent.EXTRA_STREAM, attachment.contentUri)
            clipData = ClipData.newRawUri(FeedbackEmailTargetResolver.CLIP_LABEL, attachment.contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(fallbackSend, "发送反馈给 Agent").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (needNewTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return context.tryStart(chooser)
    }

    private fun FeedbackSendIntentSpec.toIntent(streamUri: Uri): Intent {
        val spec = this
        val intent = Intent(spec.action)
        val explicitClass = spec.className
        if (explicitClass != null) {
            intent.component = ComponentName(spec.packageName, explicitClass)
        } else {
            intent.setPackage(spec.packageName)
        }
        intent.type = spec.mimeType
        intent.putExtra(Intent.EXTRA_EMAIL, spec.recipients.toTypedArray())
        intent.putExtra(Intent.EXTRA_SUBJECT, spec.subject)
        intent.putExtra(Intent.EXTRA_TEXT, spec.text)
        intent.putExtra(Intent.EXTRA_STREAM, streamUri)
        intent.clipData = ClipData.newRawUri(spec.clipLabel, streamUri)
        if (spec.grantReadUriPermission) intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // spec.hasSelector is always false by contract: never set `selector` on an explicit ACTION_SEND.
        return intent
    }

    private fun Context.tryStart(intent: Intent): Boolean =
        try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private fun PackageManager.resolveDefaultActivity(intent: Intent): ResolvedEmailActivity? {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return info?.toResolvedEmailActivity()
    }

    private fun PackageManager.queryActivitiesSafe(intent: Intent, flags: Int): List<ResolvedEmailActivity> =
        runCatching {
            val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                queryIntentActivities(intent, flags)
            }
            infos.mapNotNull { it.toResolvedEmailActivity() }
        }.getOrDefault(emptyList())

    private fun ResolveInfo.toResolvedEmailActivity(): ResolvedEmailActivity? {
        val ai = activityInfo ?: return null
        val pkg = ai.packageName ?: return null
        val name = ai.name ?: return null
        return ResolvedEmailActivity(packageName = pkg, activityName = name)
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
