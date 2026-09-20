package com.lunaexplorer.app.storage

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** [complete] is false when only the APK size could be measured. */
data class AppSize(
    val packageName: String,
    val label: String,
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
    val system: Boolean,
    val complete: Boolean,
) {
    val totalBytes: Long get() = appBytes + dataBytes + cacheBytes
}

data class DuplicateGroup(val bytesEach: Long, val paths: List<String>) {
    val reclaimable: Long get() = bytesEach * (paths.size - 1).coerceAtLeast(0)
}

data class DuplicateReport(val groups: List<DuplicateGroup>, val examined: Int, val complete: Boolean) {
    val fileCount: Int get() = groups.sumOf { it.paths.size }
    val reclaimable: Long get() = groups.sumOf { it.reclaimable }
}

class StorageAnalysis(context: Context) {
    private val context = context.applicationContext
    private val packages = this.context.packageManager

    /** Data and cache sizes need usage access; without it only APK sizes can be read. */
    fun canMeasureApps(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Not built on [AppInventory.list], which also hashes signing certificates. */
    suspend fun appSizes(): List<AppSize> = withContext(Dispatchers.IO) {
        val stats = if (canMeasureApps()) {
            context.getSystemService(StorageStatsManager::class.java)
        } else null
        val uuid = runCatching { StorageManager.UUID_DEFAULT }.getOrNull()

        installedApplications().mapNotNull { info ->
            currentCoroutineContext().ensureActive()
            val measured = if (stats != null && uuid != null) {
                runCatching { stats.queryStatsForUid(uuid, info.uid) }.getOrNull()
            } else null
            val apk = apkBytes(info)
            AppSize(
                packageName = info.packageName,
                label = runCatching { packages.getApplicationLabel(info).toString() }.getOrDefault(info.packageName),
                appBytes = measured?.appBytes ?: apk,
                dataBytes = measured?.dataBytes ?: 0L,
                cacheBytes = measured?.cacheBytes ?: 0L,
                system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                complete = measured != null,
            ).takeIf { it.totalBytes > 0 }
        }.sortedByDescending { it.totalBytes }
    }

    @Suppress("DEPRECATION")
    private fun installedApplications(): List<ApplicationInfo> = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            packages.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else packages.getInstalledApplications(0)
    }.getOrDefault(emptyList())

    private fun apkBytes(info: ApplicationInfo): Long = runCatching {
        val base = File(info.sourceDir).length()
        val splits = info.splitSourceDirs?.sumOf { File(it).length() } ?: 0L
        base + splits
    }.getOrDefault(0L)

    suspend fun duplicates(
        candidates: List<Pair<String, Long>>,
        budgetBytes: Long = 3L * 1024 * 1024 * 1024,
    ): DuplicateReport = withContext(Dispatchers.IO) {
        val bySize = candidates.groupBy { it.second }.filterValues { it.size > 1 }
        var spent = 0L
        var examined = 0
        var complete = true
        val groups = ArrayList<DuplicateGroup>()

        suspend fun matching(paths: List<String>, limit: Long): List<List<String>> {
            val hashed = paths.groupBy { digest(it, limit) }
            if (null in hashed) complete = false
            return hashed.filterKeys { it != null }.values.filter { it.size > 1 }
        }

        for ((size, sharing) in bySize.entries.sortedByDescending { it.key }) {
            currentCoroutineContext().ensureActive()
            if (spent >= budgetBytes) { complete = false; break }
            val paths = sharing.map { it.first }.filter { runCatching { File(it).isFile }.getOrDefault(false) }
            if (paths.size < 2) continue

            val byHead = matching(paths, HEAD_BYTES)
            spent += paths.size * minOf(size, HEAD_BYTES)
            for (sharingHead in byHead) {
                currentCoroutineContext().ensureActive()
                if (spent >= budgetBytes) { complete = false; break }
                // A matching prefix is not enough: files of one container format share headers.
                val byWhole = matching(sharingHead, Long.MAX_VALUE)
                spent += sharingHead.size * size
                examined += sharingHead.size
                byWhole.forEach { same -> groups += DuplicateGroup(size, same) }
            }
        }
        DuplicateReport(groups.sortedByDescending { it.reclaimable }, examined, complete)
    }

    private suspend fun digest(path: String, limit: Long): String? = try {
        val sha = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { stream ->
            val buffer = ByteArray(128 * 1024)
            var read = 0L
            while (read < limit) {
                currentCoroutineContext().ensureActive()
                val wanted = minOf(buffer.size.toLong(), limit - read).toInt()
                val got = stream.read(buffer, 0, wanted)
                if (got <= 0) break
                sha.update(buffer, 0, got)
                read += got
            }
        }
        sha.digest().joinToString("") { "%02x".format(it) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        null
    }

    private companion object {
        const val HEAD_BYTES = 128L * 1024
    }
}
