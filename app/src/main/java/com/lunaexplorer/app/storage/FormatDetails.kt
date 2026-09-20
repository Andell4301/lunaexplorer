package com.lunaexplorer.app.storage

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.lunaexplorer.core.ApkFacts
import com.lunaexplorer.core.ApkReport
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.MetadataReport
import com.lunaexplorer.core.FileMetadata
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class FormatDetails(private val context: Context) {

    fun read(entry: Entry, path: String?, uri: Uri?): List<Pair<String, String>> = runCatching {
        when {
            entry.mimeType.startsWith("image/") -> image(path, uri)
            entry.mimeType.startsWith("audio/") || entry.mimeType.startsWith("video/") -> media(path, uri)
            entry.mimeType == "application/vnd.android.package-archive" -> apk(path)
            isArchive(entry) -> archive(path)
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    fun metadata(entry: Entry, path: String?, uri: Uri?): MetadataReport? {
        if (!worthReading(entry)) return null
        // A path is preferred: parsing from a stream may buffer the whole file in order to seek.
        return if (path != null) {
            FileMetadata.read(File(path))
        } else {
            open(null, uri)?.use { FileMetadata.read(it) }
        }
    }

    private fun worthReading(entry: Entry): Boolean =
        entry.mimeType.startsWith("image/") || entry.mimeType.startsWith("video/") ||
            entry.mimeType.startsWith("audio/") ||
            entry.name.substringAfterLast('.', "").lowercase() in
            setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "tif", "tiff", "dng", "raw",
                "cr2", "nef", "arw", "orf", "rw2", "psd", "ico", "bmp", "avi", "mp4", "mov", "m4v",
                "3gp", "mkv", "webm", "mp3", "m4a", "wav", "aac", "flac", "ogg", "opus", "pcx", "eps")

    fun packageReport(entry: Entry, path: String?): ApkReport? {
        if (path == null) return null
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        if (extension !in setOf("apk", "apks", "xapk", "apkm")) return null
        return ApkFacts.read(File(path))
    }

    private fun isArchive(entry: Entry): Boolean {
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        return entry.mimeType.contains("zip") || extension in setOf("zip", "jar", "apk", "epub")
    }

    private fun open(path: String?, uri: Uri?): InputStream? = runCatching {
        when {
            path != null -> File(path).inputStream()
            uri != null -> context.contentResolver.openInputStream(uri)
            else -> null
        }
    }.getOrNull()

    private fun image(path: String?, uri: Uri?): List<Pair<String, String>> = buildList {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(path, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            add("Dimensions" to "${bounds.outWidth} × ${bounds.outHeight}")
            add("Megapixels" to "%.1f MP".format(bounds.outWidth.toLong() * bounds.outHeight / 1_000_000.0))
            add("Aspect ratio" to aspect(bounds.outWidth, bounds.outHeight))
        }
        bounds.outMimeType?.let { add("Encoded as" to it) }

        val exif = runCatching {
            if (path != null) ExifInterface(path)
            else open(null, uri)?.use { ExifInterface(it) }
        }.getOrNull() ?: return@buildList

        fun tag(label: String, name: String) {
            exif.getAttribute(name)?.takeIf { it.isNotBlank() && it != "0" }?.let { add(label to it) }
        }
        tag("Camera", ExifInterface.TAG_MAKE)
        tag("Model", ExifInterface.TAG_MODEL)
        tag("Taken", ExifInterface.TAG_DATETIME)
        tag("Exposure", ExifInterface.TAG_EXPOSURE_TIME)
        tag("Aperture", ExifInterface.TAG_F_NUMBER)
        tag("ISO", ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
        tag("Focal length", ExifInterface.TAG_FOCAL_LENGTH)
        tag("Flash", ExifInterface.TAG_FLASH)
        tag("White balance", ExifInterface.TAG_WHITE_BALANCE)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
        if (orientation > 1) add("Orientation" to orientationLabel(orientation))
        exif.latLong?.let { coordinates ->
            add("Location" to "%.6f, %.6f".format(coordinates[0], coordinates[1]))
        }
    }

    private fun media(path: String?, uri: Uri?): List<Pair<String, String>> {
        val retriever = MediaMetadataRetriever()
        return try {
            when {
                path != null -> retriever.setDataSource(path)
                uri != null -> retriever.setDataSource(context, uri)
                else -> return emptyList()
            }
            buildList {
                fun key(label: String, code: Int) {
                    retriever.extractMetadata(code)?.takeIf { it.isNotBlank() }?.let { add(label to it) }
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?.let { add("Duration" to formatDuration(it)) }
                key("Title", MediaMetadataRetriever.METADATA_KEY_TITLE)
                key("Artist", MediaMetadataRetriever.METADATA_KEY_ARTIST)
                key("Album", MediaMetadataRetriever.METADATA_KEY_ALBUM)
                key("Album artist", MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                key("Track", MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                key("Disc", MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                key("Year", MediaMetadataRetriever.METADATA_KEY_YEAR)
                key("Genre", MediaMetadataRetriever.METADATA_KEY_GENRE)
                key("Composer", MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                key("Writer", MediaMetadataRetriever.METADATA_KEY_WRITER)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                    ?.let { add("Bitrate" to "${it / 1000} kbps") }
                key("Container", MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                if (width != null && height != null) add("Resolution" to "$width × $height")
                key("Rotation", MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.let { add("Frame rate" to "$it fps") }
                if (Build.VERSION.SDK_INT >= 31) {
                    key("Sample rate", MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                }
                if (retriever.embeddedPicture != null) add("Cover art" to "Embedded")
            }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun apk(path: String?): List<Pair<String, String>> {
        if (path == null) return emptyList()
        val packages = context.packageManager
        val info = packages.getPackageArchiveInfo(path, PackageManager.GET_PERMISSIONS) ?: return emptyList()
        val application = info.applicationInfo?.apply { sourceDir = path; publicSourceDir = path }
        return buildList {
            application?.let { add("Application" to packages.getApplicationLabel(it).toString()) }
            add("Package" to info.packageName)
            add("Version" to "${info.versionName} (${PackageMetadata.versionCode(info)})")
            application?.let {
                add("Minimum Android" to "API ${it.minSdkVersion}")
                add("Targets" to "API ${it.targetSdkVersion}")
            }
            info.requestedPermissions?.size?.let { add("Permissions requested" to it.toString()) }

            val installed = PackageMetadata.installedVersion(packages, info.packageName)
            if (installed == null) {
                add("Installed" to "Not installed")
            } else {
                val (installedName, installedCode) = installed
                add("Installed version" to "${installedName ?: "unknown"} ($installedCode)")
                val mine = PackageMetadata.versionCode(info)
                add("Compared to installed" to when {
                    mine > installedCode -> "Newer — this would be an update"
                    mine < installedCode -> "Older — installing needs the current one removed first"
                    else -> "The same version"
                })
            }
            addAll(ApkInstaller.certificateDetailsOf(context, path))
        }
    }

    private fun archive(path: String?): List<Pair<String, String>> {
        if (path == null) return emptyList()
        return runCatching {
            ZipFile(File(path)).use { zip ->
                var files = 0
                var folders = 0
                var uncompressed = 0L
                var compressed = 0L
                var encrypted = false
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val item = entries.nextElement()
                    if (item.isDirectory) folders++ else files++
                    if (item.size >= 0) uncompressed += item.size
                    if (item.compressedSize >= 0) compressed += item.compressedSize
                    // ZipEntry does not expose the encryption flag (general purpose bit 0), so infer it.
                    if (item.method != ZipEntry.STORED && item.crc == 0L && item.size > 0) encrypted = true
                }
                buildList {
                    add("Entries" to "$files files, $folders folders")
                    add("Uncompressed" to formatByteCount(uncompressed))
                    if (uncompressed > 0 && compressed > 0) {
                        add("Compression" to "%.0f%% of original".format(compressed * 100.0 / uncompressed))
                    }
                    zip.comment?.takeIf { it.isNotBlank() }?.let { add("Comment" to it) }
                    if (encrypted) add("Encrypted" to "Appears to contain encrypted entries")
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun aspect(width: Int, height: Int): String {
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val divisor = gcd(width, height).coerceAtLeast(1)
        val w = width / divisor
        val h = height / divisor
        return if (w <= 40 && h <= 40) "$w:$h" else "%.2f:1".format(width.toDouble() / height)
    }

    private fun orientationLabel(value: Int) = when (value) {
        2 -> "Mirrored"
        3 -> "Rotated 180°"
        4 -> "Mirrored, rotated 180°"
        5 -> "Mirrored, rotated 90°"
        6 -> "Rotated 90°"
        7 -> "Mirrored, rotated 270°"
        8 -> "Rotated 270°"
        else -> "Normal"
    }
}

fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainder = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder) else "%d:%02d".format(minutes, remainder)
}

private fun formatByteCount(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    return String.format(Locale.getDefault(), if (value >= 10) "%.0f %s" else "%.1f %s", value, units[index])
}
