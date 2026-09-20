package com.lunaexplorer.app.storage

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.lunaexplorer.core.RootKind
import java.io.File

data class LocalRoot(
    val id: String,
    val title: String,
    val path: File,
    val kind: RootKind = RootKind.APP,
    // Device roots must follow platform symlinks such as /sdcard and /etc.
    val followLinks: Boolean = false,
    /** Paths under the root resolve, but it is not listed in navigation. */
    val hidden: Boolean = false,
)

object DeviceStorage {
    private const val DEVICE_ROOT_ID = "device"
    private const val APP_DATA_ID = "appdata"
    private const val EXTERNAL_AUTHORITY = "com.android.externalstorage.documents"

    fun hasFullAccess(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Android 11+ routes all-files access through Settings rather than a runtime permission dialog. */
    fun fullAccessSettingsIntents(context: Context): List<Intent> = if (Build.VERSION.SDK_INT < 30) emptyList() else listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null)),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    )

    /** READ_EXTERNAL_STORAGE is still a fallback on API 30-32; API 33+ has media-specific permissions instead. */
    fun legacyPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> emptyArray()
        Build.VERSION.SDK_INT >= 30 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    fun appData(context: Context): LocalRoot? = runCatching {
        LocalRoot(APP_DATA_ID, "Luna app data", context.dataDir, RootKind.APP, followLinks = true, hidden = true)
    }.getOrNull()

    fun volumes(context: Context, includeDeviceRoot: Boolean): List<LocalRoot> {
        val byId = LinkedHashMap<String, LocalRoot>()
        val seenPaths = HashSet<String>()
        // Root ids are stored in saved references, so they must be unique.
        fun offer(volume: LocalRoot) {
            if (!seenPaths.add(volume.path.absolutePath)) return
            if (byId.containsKey(volume.id)) return
            byId[volume.id] = volume
        }
        runCatching { platformVolumes(context) }.getOrDefault(emptyList()).forEach(::offer)
        runCatching { legacyVolumes(context) }.getOrDefault(emptyList()).forEach(::offer)
        val ordered = byId.values.sortedBy { if (it.kind == RootKind.INTERNAL) 0 else 1 }.toMutableList()
        if (includeDeviceRoot) {
            ordered += LocalRoot(DEVICE_ROOT_ID, "Root", File("/"), RootKind.SYSTEM, followLinks = true)
        }
        return ordered
    }

    private fun platformVolumes(context: Context): List<LocalRoot> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        val primaryPath = runCatching { Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull()
        return manager.storageVolumes.mapNotNull { volume ->
            val directory = runCatching { volume.directory }.getOrNull() ?: return@mapNotNull null
            val state = runCatching { volume.state }.getOrNull()
            if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return@mapNotNull null
            // Identify the primary volume by directory first: removable volumes can have a null UUID too.
            val primary = directory.absolutePath == primaryPath ||
                (primaryPath == null && runCatching { volume.uuid }.getOrNull() == null)
            val description = runCatching { volume.getDescription(context) }.getOrNull()
            LocalRoot(
                id = if (primary) "primary" else "volume-${volume.uuid ?: directory.name}",
                title = when {
                    primary -> "Internal storage"
                    !description.isNullOrBlank() -> description
                    else -> "Removable storage"
                },
                path = directory,
                kind = if (primary) RootKind.INTERNAL else removableKind(directory, description),
                followLinks = true,
            )
        }
    }

    /** API 26-29 has no public volume-path accessor, so derive the paths from the app-files directories. */
    private fun legacyVolumes(context: Context): List<LocalRoot> = buildList {
        if (Build.VERSION.SDK_INT >= 30) return@buildList
        runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let {
            add(LocalRoot("primary", "Internal storage", it, RootKind.INTERNAL, followLinks = true))
        }
        context.getExternalFilesDirs(null).orEmpty().filterNotNull().forEach { appDir ->
            val marker = "${File.separator}Android${File.separator}data${File.separator}"
            val index = appDir.absolutePath.indexOf(marker)
            if (index <= 0) return@forEach
            val volume = File(appDir.absolutePath.substring(0, index))
            if (!volume.isDirectory || volume.absolutePath == Environment.getExternalStorageDirectory()?.absolutePath) return@forEach
            add(LocalRoot("volume-${volume.name}", volume.name, volume, removableKind(volume, null), followLinks = true))
        }
    }

    private fun removableKind(directory: File, description: String?): RootKind {
        val text = "${description.orEmpty()} ${directory.absolutePath}".lowercase()
        return if (text.contains("usb") || text.contains("otg")) RootKind.USB else RootKind.SD_CARD
    }

    fun systemBrowseIntents(context: Context, absolutePath: String?): List<Intent> =
        browseIntents(absolutePath).flatMap { aimedAtSystemBrowser(context, it) }

    private fun browseIntents(absolutePath: String?): List<Intent> = buildList {
        externalDocumentId(absolutePath)?.let {
            add(Intent(Intent.ACTION_VIEW)
                .setDataAndType(DocumentsContract.buildDocumentUri(EXTERNAL_AUTHORITY, it), DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        add(Intent(Intent.ACTION_VIEW).setType(DocumentsContract.Document.MIME_TYPE_DIR))
        // ACTION_OPEN_DOCUMENT_TREE is left to the caller: its result handler has to persist the grant.
    }

    /** Sets an explicit system component to avoid a chooser; without one the intent stays implicit. */
    private fun aimedAtSystemBrowser(context: Context, intent: Intent): List<Intent> {
        val matches = runCatching {
            @Suppress("DEPRECATION") context.packageManager.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList())
        val system = matches.filter { match ->
            val info = match.activityInfo?.applicationInfo ?: return@filter false
            info.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                info.packageName != context.packageName
        }
        val best = system.firstOrNull { it.activityInfo.packageName.contains("documentsui", ignoreCase = true) }
            ?: system.firstOrNull()
            ?: return listOf(intent)
        return listOf(Intent(intent).setComponent(ComponentName(best.activityInfo.packageName, best.activityInfo.name)))
    }

    fun pickerIntent(absolutePath: String?): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        externalDocumentId(absolutePath)?.let {
            // EXTRA_INITIAL_URI requires a document URI; a tree-only URI is ignored by the picker.
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(EXTERNAL_AUTHORITY, it))
        }
        return intent
    }

    private fun externalDocumentId(absolutePath: String?): String? {
        val primary = runCatching { Environment.getExternalStorageDirectory()?.canonicalPath }.getOrNull() ?: return null
        val raw = absolutePath ?: return "primary:"
        val path = runCatching { File(raw).canonicalPath }.getOrDefault(raw)
        if (path == primary) return "primary:"
        if (!path.startsWith("$primary/")) return null
        return "primary:${path.removePrefix("$primary/")}"
    }
}
