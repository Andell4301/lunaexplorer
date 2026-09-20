package com.lunaexplorer.app.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

class FakeDocumentsProvider : DocumentsProvider() {
    companion object {
        const val AUTHORITY = "com.lunaexplorer.test.documents"
        const val ROOT = "root"
        lateinit var base: File
        /** Folder IDs that return partial results on their first query. */
        val stillLoading = HashSet<String>()
        /** Folder IDs that always return partial results and notify again. */
        val neverSettles = HashSet<String>()
        var appendTextExtension = false
        private val served = HashSet<String>()
        fun reset() { stillLoading.clear(); neverSettles.clear(); served.clear(); appendTextExtension = false }
    }

    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

    override fun onCreate(): Boolean = true

    // Robolectric calls the legacy query overload; DocumentsProvider only routes the Bundle one.
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?, signal: CancellationSignal?): Cursor? =
        query(uri, projection, null as Bundle?, signal)

    private fun fileOf(id: String): File = if (id == ROOT) base else File(base, id.removePrefix("$ROOT/"))
    private fun idOf(file: File): String = if (file == base) ROOT else "$ROOT/" + file.relativeTo(base).path.replace(File.separatorChar, '/')

    private fun row(cursor: MatrixCursor, file: File) {
        val directory = file.isDirectory
        val flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_MOVE or
            (if (directory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE)
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, idOf(file))
            .add(Document.COLUMN_DISPLAY_NAME, if (file == base) "Granted" else file.name)
            .add(Document.COLUMN_MIME_TYPE, if (directory) Document.MIME_TYPE_DIR else "application/octet-stream")
            .add(Document.COLUMN_SIZE, if (directory) null else file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
    }

    override fun queryRoots(projection: Array<String>?): Cursor = MatrixCursor(projection ?: columns)

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val file = fileOf(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        return MatrixCursor(projection ?: columns).also { row(it, file) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor {
        val folder = fileOf(parentDocumentId)
        if (!folder.isDirectory) throw FileNotFoundException(parentDocumentId)
        val children = folder.listFiles().orEmpty().sortedBy { it.name }
        val cursor = MatrixCursor(projection ?: columns)
        val partial = parentDocumentId in neverSettles || (parentDocumentId in stillLoading && served.add(parentDocumentId))
        (if (partial) children.take(1) else children).forEach { row(cursor, it) }
        if (partial) {
            cursor.extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
            val resolver = context!!.contentResolver
            val childrenUri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId)
            cursor.setNotificationUri(resolver, childrenUri)
            // Notify late enough for the caller to have registered its cursor observer.
            Thread { Thread.sleep(50); resolver.notifyChange(childrenUri, null) }.start()
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val name = if (appendTextExtension && mimeType == "text/plain" && !displayName.endsWith(".txt"))
            "$displayName.txt" else displayName
        val file = File(fileOf(parentDocumentId), name)
        if (file.exists()) throw IllegalStateException("exists")
        if (mimeType == Document.MIME_TYPE_DIR) file.mkdirs() else file.createNewFile()
        return idOf(file)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileOf(documentId)
        val target = File(file.parentFile, displayName)
        if (!file.renameTo(target)) throw IllegalStateException("rename failed")
        return idOf(target)
    }

    override fun deleteDocument(documentId: String) {
        if (!fileOf(documentId).deleteRecursively()) throw FileNotFoundException(documentId)
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        val file = fileOf(sourceDocumentId)
        val target = File(fileOf(targetParentDocumentId), file.name)
        if (!file.renameTo(target)) throw IllegalStateException("move failed")
        return idOf(target)
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(documentId), ParcelFileDescriptor.parseMode(mode))
}
