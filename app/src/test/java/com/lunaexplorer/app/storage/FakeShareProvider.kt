package com.lunaexplorer.app.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Without [file], openFile throws, like a sender that only streams. */
class FakeShareProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "other.app.share"
        var name: String? = null
        var size: Long? = null
        var type: String? = null
        var file: File? = null
        fun reset() { name = null; size = null; type = null; file = null }
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> name
                    OpenableColumns.SIZE -> size
                    else -> null
                }
            })
        }
    }

    override fun getType(uri: Uri): String? = type

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val backing = file ?: throw FileNotFoundException("This sender only streams")
        return ParcelFileDescriptor.open(backing, ParcelFileDescriptor.parseMode(mode))
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
