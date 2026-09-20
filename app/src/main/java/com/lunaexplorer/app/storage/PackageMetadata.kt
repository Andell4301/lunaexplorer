package com.lunaexplorer.app.storage

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

internal object PackageMetadata {
    @Suppress("DEPRECATION")
    val signingFlags: Int
        get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    fun signatures(info: PackageInfo): List<Signature> =
        if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            }.orEmpty().toList()
        } else {
            info.signatures.orEmpty().toList()
        }

    fun fingerprint(bytes: ByteArray, algorithm: String = "SHA-256"): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString(":") { "%02X".format(it) }

    fun installedVersion(packages: PackageManager, packageName: String): Pair<String?, Long>? = runCatching {
        val info = packages.getPackageInfo(packageName, 0)
        info.versionName to versionCode(info)
    }.getOrNull()
}
