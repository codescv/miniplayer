package com.miniplayer.app.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.miniplayer.app.BuildConfig
import com.miniplayer.app.core.AppUpdateContract
import com.miniplayer.app.core.model.UpdateInfo
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android service adapter implementing [AppUpdateContract] (`ARCHITECTURE.md` §6).
 */
class AndroidAppUpdateService(
    context: Context,
    apiBaseOverride: String? = null,
    allowInsecureLoopbackOverride: Boolean? = null,
    private val apkValidator: (File) -> Unit = { file -> ApkValidator.validate(context.applicationContext, file) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AppUpdateContract {

    companion object {
        const val STALE_APK_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    }

    private val appContext = context.applicationContext
    private val apiBase = (apiBaseOverride ?: BuildConfig.UPDATE_API_BASE).trimEnd('/')
    private val allowLoopback = allowInsecureLoopbackOverride ?: (
        BuildConfig.DEBUG && (apiBase.startsWith("http://127.0.0.1") || apiBase.startsWith("http://localhost"))
        )

    private val updater = AppUpdater(
        latestUrl = "$apiBase/releases/latest",
        listUrl = "$apiBase/releases?per_page=5",
        allowInsecureLoopback = allowLoopback,
    )

    val updatesDir: File
        get() = File(appContext.cacheDir, "updates").apply { mkdirs() }

    override suspend fun checkForUpdates(includePreRelease: Boolean): UpdateInfo = withContext(ioDispatcher) {
        purgeStaleCachedApks()
        val result = updater.checkLatestRelease(
            currentVersion = BuildConfig.VERSION_NAME,
            includePre = includePreRelease,
        )
        val apk = result.apk
        if (result.updateAvailable && apk == null) {
            throw UpdateException(
                UpdateErrorCode.NO_ASSET,
                "Release ${result.latest.tagName} has no downloadable APK asset.",
            )
        }
        UpdateInfo(
            currentVersion = result.currentVersion,
            latestTag = result.latest.tagName,
            releaseNotes = result.latest.body.orEmpty(),
            publishedAt = result.latest.publishedAt.orEmpty(),
            apkName = apk?.name.orEmpty(),
            apkDownloadUrl = apk?.url.orEmpty(),
            apkSizeBytes = apk?.size ?: 0L,
            expectedSha256 = result.expectedSha256,
            updateAvailable = result.updateAvailable && apk != null,
        )
    }

    override suspend fun downloadAndVerifyUpdate(
        updateInfo: UpdateInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): File = withContext(ioDispatcher) {
        if (updateInfo.apkDownloadUrl.isBlank()) {
            throw UpdateException(UpdateErrorCode.NO_ASSET, "No APK download URL in UpdateInfo")
        }
        val dest = cachedApkFileForTag(updateInfo.latestTag)
        // Reuse already-verified cached APK if its SHA-256 and package signature match
        if (dest.isFile &&
            (updateInfo.expectedSha256 == null || AppUpdater.sha256HexOfFile(dest).equals(updateInfo.expectedSha256, ignoreCase = true)) &&
            runCatching { apkValidator(dest) }.isSuccess
        ) {
            onProgress(dest.length(), dest.length())
            return@withContext dest
        }

        updater.downloadApk(
            url = updateInfo.apkDownloadUrl,
            dest = dest,
            expectedSize = updateInfo.apkSizeBytes,
            expectedSha256 = updateInfo.expectedSha256,
            validator = apkValidator,
            onProgress = onProgress,
        )
    }

    override fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    override fun buildInstallIntent(apkFile: File): Intent {
        if (!canRequestPackageInstalls()) {
            return Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val authority = "${appContext.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(appContext, authority, apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun cachedApkFileForTag(tagName: String): File {
        val clean = tagName.trim().removePrefix("v").removePrefix("V")
            .replace(Regex("[^0-9A-Za-z._-]"), "_")
            .ifEmpty { "latest" }
        return File(updatesDir, "MiniPlayer-$clean.apk")
    }

    private fun purgeStaleCachedApks() {
        val dir = updatesDir
        if (!dir.isDirectory) return
        val now = System.currentTimeMillis()
        val baseInstalled = SemanticVersion.stripDebugSuffix(BuildConfig.VERSION_NAME) ?: BuildConfig.VERSION_NAME
        dir.listFiles()?.forEach { f ->
            val tooOld = now - f.lastModified() > STALE_APK_MAX_AGE_MS
            val isTmp = f.name.endsWith(".tmp")
            val versionFromName = f.name.removePrefix("MiniPlayer-").removeSuffix(".apk")
            val notNewer = f.name.endsWith(".apk") && !SemanticVersion.isNewerVersion(baseInstalled, versionFromName)
            if (isTmp || tooOld || notNewer) {
                runCatching { f.delete() }
            }
        }
    }
}
