package com.lunaexplorer.app.storage

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

// Unlistable directories need candidate names; confirm each with PathProbe before showing it.
class SystemNames(context: Context) {
    private val packages = context.applicationContext.packageManager

    private val installPaths: List<String> by lazy { collectInstallPaths() }

    fun namesUnder(directory: String): List<String> {
        val prefix = if (directory == "/") "/" else "$directory/"
        return installPaths.asSequence()
            .filter { it.startsWith(prefix) }
            .mapNotNull { path ->
                val remainder = path.removePrefix(prefix)
                if (remainder.isEmpty()) null else remainder.substringBefore('/')
            }
            .distinct()
            .toList()
    }

    private fun collectInstallPaths(): List<String> = runCatching {
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        @Suppress("DEPRECATION")
        val installed = if (Build.VERSION.SDK_INT >= 33) {
            packages.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        } else {
            packages.getInstalledApplications(flags)
        }
        buildSet {
            installed.forEach { app ->
                app.sourceDir?.let { addAncestry(it) }
                app.publicSourceDir?.let { addAncestry(it) }
                app.splitSourceDirs?.forEach { addAncestry(it) }
                app.nativeLibraryDir?.let { addAncestry(it) }
                // The mount namespace hides other apps' data directories, but their parents are
                // still candidates.
                app.dataDir?.let { addAncestry(it) }
            }
        }.toList()
    }.getOrDefault(emptyList())

    private fun MutableSet<String>.addAncestry(path: String) {
        var current: File? = File(path)
        while (current != null && current.path.length > 1) {
            add(current.path)
            current = current.parentFile
        }
    }

    companion object {
        /** From AOSP init.rc and file_contexts. */
        val FIXED_CANDIDATES: Map<String, List<String>> = mapOf(
            "/" to listOf(
                "acct", "apex", "bin", "bugreports", "cache", "config", "d", "data", "data_mirror",
                "debug_ramdisk", "dev", "etc", "init", "linkerconfig", "lost+found", "metadata",
                "mnt", "odm", "odm_dlkm", "oem", "postinstall", "proc", "product", "sbin", "sdcard",
                "second_stage_resources", "storage", "sys", "system", "system_dlkm", "system_ext",
                "vendor", "vendor_dlkm",
            ),
            "/data" to listOf(
                "adb", "anr", "apex", "app", "app-asec", "app-ephemeral", "app-lib", "app-private",
                "app-staging", "backup", "bootchart", "cache", "dalvik-cache", "data", "drm",
                "font", "gsi", "gsi_persistent_data", "incremental", "local", "lost+found", "media",
                "mediadrm", "misc", "misc_ce", "misc_de", "nfc", "ota", "ota_package",
                "pkg_staging", "preloads", "property", "resource-cache", "rollback", "sdcard",
                "security", "server_configurable_flags", "ss", "system", "system_ce", "system_de",
                "time", "tombstones", "user", "user_de", "vendor", "vendor_ce", "vendor_de",
            ),
            "/data/misc" to listOf(
                "adb", "apexdata", "apns", "audio", "audioserver", "bluetooth", "bootstat",
                "camera", "carrierid", "dhcp", "gatekeeper", "installd", "keychain", "keystore",
                "location", "logd", "media", "net", "network_watchlist", "nfc", "odsign",
                "perfetto-traces", "profcollectd", "radio", "recovery", "shared_relro", "snapshotctl_log",
                "stats-data", "systemkeys", "textclassifier", "trace", "user", "vold", "wifi",
            ),
            "/data/local" to listOf("tmp", "traces"),
            "/mnt" to listOf(
                "androidwritable", "appfuse", "expand", "installer", "media_rw", "pass_through",
                "product", "runtime", "sdcard", "secure", "system", "user", "vendor",
            ),
            "/storage" to listOf("emulated", "self", "sdcard0"),
            "/proc" to listOf(
                "self", "cmdline", "cpuinfo", "meminfo", "mounts", "stat", "uptime", "version",
                "net", "sys", "filesystems", "partitions", "vmstat", "loadavg", "misc", "modules",
            ),
            "/sys" to listOf(
                "block", "bus", "class", "dev", "devices", "firmware", "fs", "kernel", "module", "power",
            ),
        )

        val CLOSED_PATHS: Map<String, String> = mapOf(
            "/data/media" to "Owned by media_rw and closed to apps. The same content is at /storage/emulated.",
            "/data/data" to "Replaced by an empty private mount for every app targeting Android 10 or later.",
            "/data/user" to "Replaced by an empty private mount for every app targeting Android 10 or later.",
            "/data/user_de" to "Replaced by an empty private mount for every app targeting Android 10 or later.",
            "/cache" to "Closed to apps.",
            "/metadata" to "Closed to apps.",
            "/data_mirror" to "Closed to apps.",
            "/mnt/media_rw" to "Owned by media_rw and closed to apps.",
            "/mnt/pass_through" to "Closed to apps.",
            "/data/local/tmp" to "Reachable over ADB, not from an app.",
        )
    }
}
