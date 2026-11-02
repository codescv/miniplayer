package com.miniplayer.app.core.update

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** `v1.0.16`: tapping `下载并安装更新` installs directly after SHA-256 + signature verification. */
class DirectInstallCoordinatorTest {

    private lateinit var dir: File
    private lateinit var apk: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("direct-install").toFile()
        apk = File(dir, "MiniPlayer-1.0.16.apk").apply { writeBytes(byteArrayOf(0x50, 0x4b, 3, 4)) }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun verifiedApk_withInstallPermission_launchesInstallerImmediately() {
        val c = DirectInstallCoordinator()
        assertEquals(DirectInstallAction.LaunchInstaller(apk), c.onApkVerified(apk, canRequestPackageInstalls = true))
        assertFalse(c.pendingInstallAfterSettings)
        assertNull(c.pendingInstallApkFile)
        // Returning to foreground afterwards must not relaunch the installer.
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = true))
    }

    @Test
    fun verifiedApk_withoutPermission_requestsPermissionAndInstallsAfterGrant() {
        val c = DirectInstallCoordinator()
        assertEquals(
            DirectInstallAction.RequestInstallPermission(apk),
            c.onApkVerified(apk, canRequestPackageInstalls = false),
        )
        assertTrue(c.pendingInstallAfterSettings)
        assertEquals(apk, c.pendingInstallApkFile)

        // User returns without granting: stays pending, nothing launched.
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = false))
        assertTrue(c.pendingInstallAfterSettings)

        // User grants and returns: installer auto-launches exactly once.
        assertEquals(DirectInstallAction.LaunchInstaller(apk), c.onAppForegrounded(canRequestPackageInstalls = true))
        assertFalse(c.pendingInstallAfterSettings)
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = true))
    }

    @Test
    fun foregroundWithoutPendingApk_doesNothing() {
        val c = DirectInstallCoordinator()
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = true))
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = false))
    }

    @Test
    fun pendingApkDeletedWhileInSettings_isClearedWithoutLaunching() {
        val c = DirectInstallCoordinator()
        c.onApkVerified(apk, canRequestPackageInstalls = false)
        apk.delete()
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = true))
        assertFalse(c.pendingInstallAfterSettings)
    }

    @Test
    fun manualRetap_afterPermissionGranted_clearsPendingAndLaunches() {
        val c = DirectInstallCoordinator()
        c.onApkVerified(apk, canRequestPackageInstalls = false)
        // User re-taps `立即安装更新` after granting permission elsewhere.
        assertEquals(DirectInstallAction.LaunchInstaller(apk), c.onApkVerified(apk, canRequestPackageInstalls = true))
        assertFalse(c.pendingInstallAfterSettings)
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = true))
    }

    @Test
    fun clearPending_dropsPendingInstall() {
        val c = DirectInstallCoordinator()
        c.onApkVerified(apk, canRequestPackageInstalls = false)
        c.clearPending()
        assertFalse(c.pendingInstallAfterSettings)
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = true))
    }

    @Test
    fun settingsAndLifecycleWireDirectInstallAndRecreatedActivityResume() {
        var root: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (root != null && !(File(root, "settings.gradle.kts").isFile && File(root, "app").isDirectory)) {
            root = root.parentFile
        }
        val repoRoot = checkNotNull(root) { "Could not locate repository root" }
        val settings = File(repoRoot, "app/src/main/java/com/miniplayer/app/ui/SettingsScreen.kt").readText()
        val mainActivity = File(repoRoot, "app/src/main/java/com/miniplayer/app/MainActivity.kt").readText()
        val viewModel = File(repoRoot, "app/src/main/java/com/miniplayer/app/ui/MiniPlayerViewModel.kt").readText()

        // Download button immediately triggers requestInstallApk without requiring a second tap
        assertTrue(settings.contains("Text(\"下载并安装更新 (SHA-256 + 签名校验)\")"))
        assertFalse(settings.contains("Text(\"下载并校验更新 (SHA-256 + 签名校验)\")"))
        assertTrue(settings.contains("viewModel.downloadAndVerifyUpdate(info)"))
        assertTrue(settings.contains("viewModel.requestInstallApk(apkFile, context)"))
        // Fallback button stays in ReadyToInstall if user dismissed the system installer dialog
        assertTrue(settings.contains("Text(\"立即安装更新\")"))
        assertTrue(settings.contains("viewModel.requestInstallApk(state.apkFile, context)"))
        // Both normal foreground return and recreated-Activity ON_START resume pending install
        assertTrue(mainActivity.contains("viewModel.onAppForegrounded()"))
        assertTrue(mainActivity.contains("viewModel.resumePendingInstallIfPermitted()"))
        assertTrue(viewModel.contains("resumePendingInstallIfPermitted()"))
    }
}
