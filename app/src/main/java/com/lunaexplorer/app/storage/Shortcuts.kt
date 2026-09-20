package com.lunaexplorer.app.storage

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

class Shortcuts(context: Context) {
    private val context = context.applicationContext

    fun canPin(): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** True means the launcher accepted the request, not that the shortcut was created. */
    fun pinFolder(path: String, label: String): Boolean = runCatching {
        ShortcutManagerCompat.requestPinShortcut(context, shortcutFor(path, label), null)
    }.getOrDefault(false)

    fun publishRecent(folders: List<Pair<String, String>>) {
        runCatching {
            val limit = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(1)
            val shortcuts = folders.take(limit).map { (path, label) -> shortcutFor(path, label) }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }
    }

    // Encode the path so URI delimiters and whitespace survive shortcut persistence.
    fun openFolderIntent(path: String): Intent = Intent(ACTION_OPEN_FOLDER)
        .setPackage(context.packageName)
        .setData(Uri.fromParts("luna", path, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun shortcutFor(path: String, label: String): ShortcutInfoCompat {
        val intent = openFolderIntent(path)
        return ShortcutInfoCompat.Builder(context, "folder:$path")
            // isBlank() misses invisible format characters such as direction marks.
            .setShortLabel(label.takeIf { it.any(Char::isLetterOrDigit) }
                ?: path.trimEnd('/').substringAfterLast('/').takeIf { it.any(Char::isLetterOrDigit) }
                ?: path.ifBlank { "/" })
            .setLongLabel(path)
            .setIcon(icon)
            .setIntent(intent)
            .build()
    }

    private val icon: IconCompat by lazy { runCatching {
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        val pixels = 192
        val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, pixels, pixels)
        drawable.draw(Canvas(bitmap))
        IconCompat.createWithAdaptiveBitmap(bitmap)
    }.getOrElse { IconCompat.createWithResource(context, android.R.drawable.ic_menu_more) } }

    companion object {
        const val ACTION_OPEN_FOLDER = "com.lunaexplorer.app.OPEN_FOLDER"

        fun folderFrom(intent: Intent?): String? {
            if (intent?.action != ACTION_OPEN_FOLDER) return null
            return intent.data?.schemeSpecificPart?.takeIf { it.isNotEmpty() }
        }
    }
}
