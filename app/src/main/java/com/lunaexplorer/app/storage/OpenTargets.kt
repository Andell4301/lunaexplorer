package com.lunaexplorer.app.storage

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class OpenCandidate(
    val packageName: String,
    val activityName: String,
    val label: String,
    val specific: Boolean,
) {
    val component: String get() = "$packageName/$activityName"
}

class OpenTargets(context: Context) {
    private val context = context.applicationContext
    private val packages = this.context.packageManager

    suspend fun candidates(uri: Uri, mimeType: String): List<OpenCandidate> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val flags = PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER
        @Suppress("DEPRECATION")
        val resolved: List<ResolveInfo> = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                packages.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
            } else {
                packages.queryIntentActivities(intent, flags)
            }
        }.getOrDefault(emptyList())

        resolved.asSequence()
            .filter { it.activityInfo != null && it.activityInfo.exported }
            .filter { it.activityInfo.packageName != context.packageName }
            .map { info ->
                OpenCandidate(
                    packageName = info.activityInfo.packageName,
                    activityName = info.activityInfo.name,
                    label = runCatching { info.loadLabel(packages).toString() }
                        .getOrNull()?.ifBlank { null } ?: info.activityInfo.packageName,
                    specific = info.filter?.let { filter ->
                        (0 until filter.countDataTypes()).any { filter.getDataType(it) != "*/*" }
                    } ?: false,
                )
            }
            .distinctBy { it.component }
            .sortedWith(compareByDescending<OpenCandidate> { it.specific }.thenBy { it.label.lowercase() })
            .toList()
    }

    suspend fun icons(candidates: List<OpenCandidate>): Map<String, Bitmap> = withContext(Dispatchers.IO) {
        candidates.mapNotNull { candidate ->
            runCatching {
                val drawable = packages.getActivityIcon(
                    ComponentName(candidate.packageName, candidate.activityName),
                )
                val pixels = 128
                val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, pixels, pixels)
                drawable.draw(Canvas(bitmap))
                candidate.component to bitmap
            }.getOrNull()
        }.toMap()
    }
}
