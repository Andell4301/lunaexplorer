package com.lunaexplorer.app.storage

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.RecycleBin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.Locale

enum class MediaCategory(
    val label: String,
    val mediaType: Int? = null,
    /** MIME type or SQL LIKE pattern to display name; aliases share a name. */
    val mimeFormats: Map<String, String> = emptyMap(),
    val extensions: List<String> = emptyList(),
) {
    IMAGES("Photos", mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
    VIDEO("Video", mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO),
    AUDIO("Audio", mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO),
    DOCUMENTS("Documents", mimeFormats = mapOf(
        "application/pdf" to "PDF", "application/msword" to "Word", "application/vnd.ms-excel" to "Excel",
        "application/vnd.ms-powerpoint" to "PowerPoint", "application/rtf" to "RTF",
        "application/epub+zip" to "EPUB", "text/%" to "All text formats",
        "application/vnd.openxmlformats-officedocument.%" to "Office Open XML",
        "application/vnd.oasis.opendocument.%" to "OpenDocument",
    )),
    ARCHIVES("Archives", mimeFormats = mapOf(
        "application/zip" to "ZIP", "application/x-tar" to "TAR", "application/gzip" to "GZIP",
        "application/x-gzip" to "GZIP", "application/x-7z-compressed" to "7Z",
        "application/vnd.rar" to "RAR", "application/x-rar-compressed" to "RAR",
        "application/java-archive" to "JAR", "application/x-bzip2" to "BZIP2", "application/x-xz" to "XZ",
    )),
    // XAPK and APKS bundles are plain ZIPs by MIME, so they are matched by name too.
    PACKAGES("App packages", mimeFormats = mapOf("application/vnd.android.package-archive" to "APK"),
        extensions = listOf("apk", "xapk", "apks"));

    val commonExtensions: List<String>
        get() = when (this) {
            IMAGES -> listOf("jpg", "png", "gif", "webp", "heic", "dng")
            VIDEO -> listOf("mp4", "mkv", "webm", "mov", "avi", "3gp")
            AUDIO -> listOf("mp3", "m4a", "flac", "ogg", "opus", "wav")
            DOCUMENTS -> listOf("pdf", "txt", "doc", "docx", "epub", "md")
            ARCHIVES -> listOf("zip", "rar", "7z", "tar", "gz", "xz")
            PACKAGES -> extensions
        }

    val defaultsDescription: String
        get() = if (mediaType != null) "All files Android classifies as ${label.lowercase(Locale.ROOT)}."
        else mimeFormats.values.distinct().joinToString(", ")
}

data class CategoryTotal(val count: Int, val bytes: Long)

data class MediaBatch(val entries: List<Entry>, val complete: Boolean, val skipped: Int)

// MediaStore covers scanned, accessible volumes and can miss recent or private files.
class MediaIndex(context: Context, private val local: LocalStorageProvider) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    // The "external" literal: MediaStore.VOLUME_EXTERNAL was only added in API 29.
    private val externalFiles = MediaStore.Files.getContentUri("external")

    var ignoreDotFiles: Boolean = false

    /** Also list media that a .nomedia file or a dot folder keeps out of Android's media collections. */
    var includeNomedia: Boolean = false

    /** Extra extensions per category, keyed by [MediaCategory.name]. */
    var categoryExtras: Map<String, Set<String>> = emptyMap()

    private companion object {
        /** Assumed instead of probed per row; operations re-stat their targets before mutating. */
        val MEDIA_CAPABILITIES = setOf(
            Capability.READ, Capability.RENAME, Capability.DELETE, Capability.WRITE, Capability.HIDDEN,
        )
        val ENTRY_PROJECTION = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
        )
    }

    /** Each batch holds only the rows read since the previous one. */
    fun query(category: MediaCategory, batchSize: Int = 512): Flow<MediaBatch> = flow {
        val (base, baseArguments) = selectionFor(category)
        val extras = categoryExtras[category.name].orEmpty().filter { it.isNotBlank() }
        val selection = if (extras.isEmpty()) base else {
            val name = MediaStore.MediaColumns.DISPLAY_NAME
            "($base) OR " + extras.joinToString(" OR ") { "$name LIKE ?" }
        }
        val arguments = if (extras.isEmpty()) baseArguments else {
            baseArguments + extras.map { "%.${it.trimStart('.').lowercase()}" }
        }
        val order = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        var skipped = 0
        var batch = ArrayList<Entry>(batchSize)
        val (guarded, guardedArguments) = outsideBin(selection, arguments)
        val cursor = resolver.query(externalFiles, ENTRY_PROJECTION, guarded, guardedArguments, order)
        if (cursor == null) {
            emit(MediaBatch(emptyList(), true, 0))
            return@flow
        }
        cursor.use {
            val columns = EntryColumns(it)
            while (it.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val path = it.stringAt(columns.data)
                val ref = path?.let { found -> local.referenceTo(found) }
                if (path == null || ref == null) {
                    skipped++
                } else if (!ignoreDotFiles || !path.substringAfterLast('/').startsWith('.')) {
                    batch.add(entryOf(it, columns, path, ref))
                }
                if (batch.size >= batchSize) {
                    emit(MediaBatch(batch, false, skipped))
                    batch = ArrayList(batchSize)
                }
            }
        }
        emit(MediaBatch(batch, true, skipped))
    }.flowOn(Dispatchers.IO)

    suspend fun largestFiles(limit: Int = 100): List<Entry> = filesWhere(
        selection = "${MediaStore.MediaColumns.SIZE} > 0",
        sortColumn = MediaStore.MediaColumns.SIZE,
        descending = true,
        limit = limit,
    )

    suspend fun categoryTotals(): Map<MediaCategory, CategoryTotal> = withContext(Dispatchers.IO) {
        MediaCategory.entries.associateWith { category ->
            val (selection, args) = selectionFor(category).let { (clause, arguments) -> outsideBin(clause, arguments) }
            var count = 0
            var bytes = 0L
            runCatching {
                resolver.query(externalFiles, arrayOf(MediaStore.MediaColumns.SIZE), selection, args, null)?.use { cursor ->
                    val sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        count++
                        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) bytes += cursor.getLong(sizeColumn)
                    }
                }
            }
            CategoryTotal(count, bytes)
        }
    }

    private suspend fun filesWhere(
        selection: String,
        sortColumn: String,
        descending: Boolean,
        limit: Int,
    ): List<Entry> = withContext(Dispatchers.IO) {
        // Android 11+ MediaProvider rejects LIMIT in the sort string, so pass it as a query argument.
        val cursor = if (Build.VERSION.SDK_INT >= 30) {
            resolver.query(externalFiles, ENTRY_PROJECTION, Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(sortColumn))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    if (descending) ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                    else ContentResolver.QUERY_SORT_DIRECTION_ASCENDING)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }, null)
        } else {
            resolver.query(externalFiles, ENTRY_PROJECTION, selection, null,
                "$sortColumn ${if (descending) "DESC" else "ASC"} LIMIT $limit")
        }
        val found = ArrayList<Entry>(limit)
        cursor?.use {
            val columns = EntryColumns(it)
            while (it.moveToNext() && found.size < limit) {
                val path = it.stringAt(columns.data) ?: continue
                val ref = local.referenceTo(path) ?: continue
                found += entryOf(it, columns, path, ref)
            }
        }
        found
    }

    suspend fun oldestFiles(limit: Int = 100): List<Entry> = filesWhere(
        selection = "${MediaStore.MediaColumns.SIZE} > 0 AND ${MediaStore.MediaColumns.DATE_MODIFIED} > 0",
        sortColumn = MediaStore.MediaColumns.DATE_MODIFIED,
        descending = false,
        limit = limit,
    )

    suspend fun pathsAndSizes(): List<Pair<String, Long>> = withContext(Dispatchers.IO) {
        val found = ArrayList<Pair<String, Long>>(4096)
        runCatching {
            resolver.query(externalFiles,
                arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE),
                "${MediaStore.MediaColumns.SIZE} > 0", null, null)?.use { cursor ->
                val dataColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    if (dataColumn < 0 || cursor.isNull(dataColumn)) continue
                    val size = if (sizeColumn >= 0) cursor.getLong(sizeColumn) else 0L
                    if (size > 0) found += cursor.getString(dataColumn) to size
                }
            }
        }
        found
    }

    private class EntryColumns(cursor: Cursor) {
        val data = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
        val name = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val size = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
        val modified = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
        val type = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
    }

    private fun Cursor.stringAt(column: Int): String? = if (column >= 0 && !isNull(column)) getString(column) else null
    private fun Cursor.longAt(column: Int): Long? = if (column >= 0 && !isNull(column)) getLong(column) else null

    private fun entryOf(cursor: Cursor, columns: EntryColumns, path: String, ref: NodeRef) = Entry(
        ref = ref,
        name = cursor.stringAt(columns.name) ?: path.substringAfterLast('/'),
        directory = false,
        size = cursor.longAt(columns.size),
        // DATE_MODIFIED is in seconds.
        modified = cursor.longAt(columns.modified)?.let { it * 1000 },
        mimeType = cursor.stringAt(columns.type) ?: "application/octet-stream",
        capabilities = MEDIA_CAPABILITIES,
    )

    private fun selectionFor(category: MediaCategory): Pair<String, Array<String>> {
        category.mediaType?.let { type ->
            val mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE
            if (!includeNomedia) return "$mediaType = ?" to arrayOf(type.toString())
            // MediaStore files everything in a hidden folder as MEDIA_TYPE_NONE but still records its MIME
            // type. Only those rows are added: playlists, say, have an audio MIME type and a type of their own.
            val prefix = when (category) {
                MediaCategory.IMAGES -> "image/%"
                MediaCategory.VIDEO -> "video/%"
                else -> "audio/%"
            }
            val unclassified = MediaStore.Files.FileColumns.MEDIA_TYPE_NONE
            return "$mediaType = ? OR ($mediaType = $unclassified AND ${MediaStore.MediaColumns.MIME_TYPE} LIKE ?)" to
                arrayOf(type.toString(), prefix)
        }
        val (clause, arguments) = mimeSelection(category.mimeFormats.keys)
        if (category.extensions.isEmpty()) return clause to arguments
        val name = MediaStore.MediaColumns.DISPLAY_NAME
        return "($clause) OR " + category.extensions.joinToString(" OR ") { "$name LIKE ?" } to
            (arguments + category.extensions.map { "%.$it" })
    }

    /** A recycled file keeps its name and type, and MediaStore still lists it from inside the bin. */
    private fun outsideBin(selection: String, arguments: Array<String>): Pair<String, Array<String>> =
        "($selection) AND ${MediaStore.MediaColumns.DATA} NOT LIKE ?" to arguments + "%/${RecycleBin.FOLDER}/%"

    private fun mimeSelection(patterns: Collection<String>): Pair<String, Array<String>> {
        val column = MediaStore.MediaColumns.MIME_TYPE
        val clause = patterns.joinToString(" OR ") { if (it.contains('%')) "$column LIKE ?" else "$column = ?" }
        return clause to patterns.toTypedArray()
    }

    fun coverageNote(): String = when {
        partialMediaAccess() -> "Selected media only. Grant full access to see everything."
        !DeviceStorage.hasFullAccess(context) && Build.VERSION.SDK_INT >= 30 ->
            "Media only without all-files access."
        else -> ""
    }

    /** API 34+ "selected photos" access, under which MediaStore returns only the chosen images. */
    private fun partialMediaAccess(): Boolean {
        if (Build.VERSION.SDK_INT < 34 || DeviceStorage.hasFullAccess(context)) return false
        fun granted(name: String) = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
        return granted("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") &&
            !granted(Manifest.permission.READ_MEDIA_IMAGES)
    }
}
