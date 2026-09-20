package com.lunaexplorer.app.storage

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

enum class AppFilter(val label: String) { USER("Installed"), SYSTEM("System"), ALL("All") }

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val system: Boolean,
    val enabled: Boolean,
    val debuggable: Boolean,
    val minSdk: Int,
    val targetSdk: Int,
    val installed: Long,
    val updated: Long,
    val installer: String?,
    val uid: Int,
    val sharedUserId: String?,
    val dataDir: String?,
    val sourceDir: String?,
    val splitDirs: List<String>,
    val apkBytes: Long,
    val permissions: List<String>,
    val grantedPermissions: Set<String>,
    val certificates: List<String>,
) {
    val hasSplits: Boolean get() = splitDirs.isNotEmpty()
}

/** Listing every package on API 30+ depends on the QUERY_ALL_PACKAGES permission. */
class AppInventory(context: Context) {
    private val packages = context.applicationContext.packageManager
    private val infoFlags = PackageManager.GET_PERMISSIONS or PackageMetadata.signingFlags

    fun list(filter: AppFilter): Flow<List<InstalledApp>> = flow {
        @Suppress("DEPRECATION")
        val all = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                packages.getInstalledPackages(PackageManager.PackageInfoFlags.of(infoFlags.toLong()))
            } else {
                packages.getInstalledPackages(infoFlags)
            }
        }.getOrDefault(emptyList())

        val collected = ArrayList<InstalledApp>(all.size)
        for (info in all) {
            currentCoroutineContext().ensureActive()
            val application = info.applicationInfo ?: continue
            val system = application.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val keep = when (filter) {
                AppFilter.USER -> !system
                AppFilter.SYSTEM -> system
                AppFilter.ALL -> true
            }
            if (!keep) continue
            collected += describe(info, application, system)
            if (collected.size % 40 == 0) emit(collected.sortedBy { it.label.lowercase() })
        }
        emit(collected.sortedBy { it.label.lowercase() })
    }.flowOn(Dispatchers.IO)

    suspend fun detail(packageName: String): InstalledApp? = withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION")
            val info = if (Build.VERSION.SDK_INT >= 33) {
                packages.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(infoFlags.toLong()))
            } else {
                packages.getPackageInfo(packageName, infoFlags)
            }
            val application = info.applicationInfo ?: return@runCatching null
            describe(info, application, application.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        }.getOrNull()
    }

    private fun describe(info: PackageInfo, application: ApplicationInfo, system: Boolean): InstalledApp {
        val splits = (application.splitPublicSourceDirs ?: application.splitSourceDirs)?.toList().orEmpty()
        val requested = info.requestedPermissions?.toList().orEmpty()
        val granted = buildSet {
            val flagsArray = info.requestedPermissionsFlags
            requested.forEachIndexed { index, permission ->
                val flag = flagsArray?.getOrNull(index) ?: 0
                if (flag and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0) add(permission)
            }
        }
        return InstalledApp(
            packageName = info.packageName,
            label = runCatching { packages.getApplicationLabel(application).toString() }.getOrDefault(info.packageName),
            versionName = info.versionName,
            versionCode = PackageMetadata.versionCode(info),
            system = system,
            enabled = application.enabled,
            debuggable = application.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            minSdk = application.minSdkVersion,
            targetSdk = application.targetSdkVersion,
            installed = info.firstInstallTime,
            updated = info.lastUpdateTime,
            installer = installerOf(info.packageName),
            uid = application.uid,
            sharedUserId = info.sharedUserId,
            dataDir = application.dataDir,
            sourceDir = application.publicSourceDir ?: application.sourceDir,
            splitDirs = splits,
            apkBytes = (listOfNotNull(application.sourceDir) + splits)
                .sumOf { runCatching { File(it).length() }.getOrDefault(0L) },
            permissions = requested,
            grantedPermissions = granted,
            certificates = PackageMetadata.signatures(info).map { PackageMetadata.fingerprint(it.toByteArray()) },
        )
    }

    @Suppress("DEPRECATION")
    private fun installerOf(packageName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) packages.getInstallSourceInfo(packageName).installingPackageName
        else packages.getInstallerPackageName(packageName)
    }.getOrNull()

    fun launchIntent(packageName: String): Intent? = packages.getLaunchIntentForPackage(packageName)

    fun settingsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

    fun storeIntent(packageName: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))

    fun uninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))

    /** (bundle entry name, file path) pairs. Names are made unique because a ZIP rejects duplicates. */
    fun componentsOf(app: InstalledApp): List<Pair<String, String>> = buildList {
        val used = mutableSetOf<String>()
        fun put(preferred: String, path: String) {
            var name = preferred
            var index = 1
            while (!used.add(name)) {
                name = preferred.substringBeforeLast(".apk") + "-${index++}.apk"
            }
            add(name to path)
        }
        app.sourceDir?.let { put("base.apk", it) }
        app.splitDirs.forEach { path ->
            val file = File(path).name
            put(if (file.endsWith(".apk")) file else "$file.apk", path)
        }
    }
}
