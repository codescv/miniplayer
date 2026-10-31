package com.miniplayer.app.core.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * APK package, downgrade, and signing certificate validator (`ARCHITECTURE.md` §6.3).
 */
object ApkValidator {

    const val EXPECTED_APPLICATION_ID = "com.miniplayer.app"

    /**
     * Strictly validates a downloaded APK file before atomic rename:
     * 1. `getPackageArchiveInfo` succeeds
     * 2. `packageName == context.packageName` (`com.miniplayer.app`)
     * 3. `candidateVersionCode > installedVersionCode` (prevents downgrade attacks)
     * 4. Signing certificate SHA-256 digest set matches installed app (fails closed if empty or mismatched)
     */
    fun validate(context: Context, apkFile: File) {
        val pm = context.packageManager
        val ownPkg = context.packageName
        val installedInfo = getInstalledInfo(pm, ownPkg)
        val installedVersionCode = versionCodeOf(installedInfo)

        val archiveInfo = getArchiveInfo(pm, apkFile.absolutePath)
            ?: throw UpdateException(
                UpdateErrorCode.VERIFY_FAILED,
                "Not a valid Android APK package.",
            )

        val archiveCertDigests = extractArchiveCertDigests(pm, apkFile.absolutePath, archiveInfo)
        val installedCertDigests = extractInstalledCertDigests(pm, ownPkg, installedInfo)

        validateParsedMetadata(
            expectedPackageName = ownPkg,
            installedVersionCode = installedVersionCode,
            installedCertDigests = installedCertDigests,
            candidatePackageName = archiveInfo.packageName,
            candidateVersionCode = versionCodeOf(archiveInfo),
            candidateCertDigests = archiveCertDigests,
        )
    }

    /**
     * Pure-JVM verification logic for package name, anti-downgrade versionCode, and signing certificate set.
     */
    fun validateParsedMetadata(
        expectedPackageName: String,
        installedVersionCode: Long,
        installedCertDigests: Set<String>,
        candidatePackageName: String?,
        candidateVersionCode: Long,
        candidateCertDigests: Set<String>,
    ) {
        if (candidatePackageName.isNullOrBlank() || candidatePackageName != expectedPackageName) {
            throw UpdateException(
                UpdateErrorCode.VERIFY_FAILED,
                "Package mismatch: expected $expectedPackageName, got $candidatePackageName.",
            )
        }
        if (candidateVersionCode <= installedVersionCode) {
            throw UpdateException(
                UpdateErrorCode.VERIFY_FAILED,
                "Downloaded APK versionCode ($candidateVersionCode) is not newer than installed ($installedVersionCode).",
            )
        }
        if (candidateCertDigests.isEmpty() || installedCertDigests.isEmpty()) {
            throw UpdateException(
                UpdateErrorCode.VERIFY_FAILED,
                "Could not verify APK signing certificates.",
            )
        }
        if (candidateCertDigests != installedCertDigests) {
            throw UpdateException(
                UpdateErrorCode.VERIFY_FAILED,
                "The downloaded update is not signed by the same key as the installed app.",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun getInstalledInfo(pm: PackageManager, pkg: String): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
        }
    }

    @Suppress("DEPRECATION")
    private fun getArchiveInfo(pm: PackageManager, path: String): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }
    }

    @Suppress("DEPRECATION")
    private fun extractArchiveCertDigests(
        pm: PackageManager,
        path: String,
        primaryInfo: PackageInfo,
    ): Set<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = primaryInfo.signingInfo
            val signers: Array<Signature>? = signing?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            }
            if (!signers.isNullOrEmpty()) {
                return digestSignatures(signers)
            }
            val legacy = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
            val legacySigs = legacy?.signatures
            if (!legacySigs.isNullOrEmpty()) {
                return digestSignatures(legacySigs)
            }
            return emptySet()
        } else {
            val sigs = primaryInfo.signatures
            return if (sigs.isNullOrEmpty()) emptySet() else digestSignatures(sigs)
        }
    }

    @Suppress("DEPRECATION")
    private fun extractInstalledCertDigests(
        pm: PackageManager,
        pkg: String,
        primaryInfo: PackageInfo,
    ): Set<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = primaryInfo.signingInfo
            val signers: Array<Signature>? = signing?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            }
            if (!signers.isNullOrEmpty()) {
                return digestSignatures(signers)
            }
            val legacy = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            val legacySigs = legacy.signatures
            if (!legacySigs.isNullOrEmpty()) {
                return digestSignatures(legacySigs)
            }
            return emptySet()
        } else {
            val sigs = primaryInfo.signatures
            return if (sigs.isNullOrEmpty()) emptySet() else digestSignatures(sigs)
        }
    }

    internal fun digestSignatureBytes(rawCerts: List<ByteArray>): Set<String> {
        val out = HashSet<String>(rawCerts.size)
        for (raw in rawCerts) {
            val sha = MessageDigest.getInstance("SHA-256").digest(raw)
            out.add(sha.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
        }
        return out
    }

    private fun digestSignatures(signatures: Array<Signature>): Set<String> =
        digestSignatureBytes(signatures.map { it.toByteArray() })

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
}
