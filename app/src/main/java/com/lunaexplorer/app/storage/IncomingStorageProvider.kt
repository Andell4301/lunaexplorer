package com.lunaexplorer.app.storage

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.Feature
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.RetainedObjects
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import com.lunaexplorer.core.StorageProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.ConcurrentHashMap

// Incoming URI grants last only as long as the task, so their references must not be persisted.
class IncomingStorageProvider(context: Context) : StorageProvider, ContentAddressable {
    override val id = "incoming"
    override val features = setOf(Feature.RANGE_READ)
    private val resolver = context.applicationContext.contentResolver

    /** MIME type declared by the delivering intent, by URI, for senders whose provider reports none. */
    private val declaredTypes = ConcurrentHashMap<String, String>()

    fun referenceTo(uri: Uri, declaredType: String? = null): NodeRef {
        val key = uri.toString()
        val declared = declaredType?.takeIf { specific(it) }
        if (declared == null) declaredTypes.remove(key) else declaredTypes[key] = declared
        return NodeRef(id, key)
    }

    private fun uriOf(ref: NodeRef): Uri {
        if (ref.provider != id) fail(StorageError.NOT_FOUND, "Not a file handed over by another app")
        return Uri.parse(ref.key)
    }

    /** Null for file: URIs; those are shared through Luna's stream provider instead. */
    override fun contentUri(ref: NodeRef): Uri? = uriOf(ref).takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }

    override suspend fun stat(ref: NodeRef): Entry = withContext(Dispatchers.IO) {
        val uri = uriOf(ref)
        if (uri.scheme == ContentResolver.SCHEME_FILE) fileEntry(ref, uri) else contentEntry(ref, uri)
    }

    private fun fileEntry(ref: NodeRef, uri: Uri): Entry {
        val file = File(uri.path ?: fail(StorageError.NOT_FOUND, "This file has no path"))
        if (!file.isFile) fail(StorageError.NOT_FOUND, "This file is no longer there")
        if (!file.canRead()) fail(StorageError.PERMISSION, "Android does not let Luna read this file")
        return entry(ref, file.name, file.length(), file.lastModified(), mimeFor(file.name, null))
    }

    private fun contentEntry(ref: NodeRef, uri: Uri): Entry {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                }
            }
        }
        // Open once now so a refused grant fails here, before a viewer is chosen.
        access { resolver.openInputStream(uri)?.close() ?: fail(StorageError.DISCONNECTED, NOT_OPENED) }
        val displayName = name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Shared file"
        val reported = runCatching { resolver.getType(uri) }.getOrNull()?.takeIf { specific(it) }
        return entry(ref, displayName, size, null, reported ?: mimeFor(displayName, declaredTypes[ref.key]))
    }

    private fun entry(ref: NodeRef, name: String, size: Long?, modified: Long?, mimeType: String) = Entry(
        ref, name, directory = false, size = size, modified = modified, mimeType = mimeType,
        capabilities = setOf(Capability.READ), hidden = false,
    )

    private fun mimeFor(name: String, declared: String?): String {
        if (declared != null) return declared
        val extension = name.substringAfterLast('.', "").lowercase()
        return URLConnection.guessContentTypeFromName(name)
            ?: extension.takeIf { it.isNotEmpty() }?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
    }

    private fun specific(type: String): Boolean =
        type.contains('/') && !type.endsWith("/*") && type != "application/octet-stream"

    override fun list(parent: NodeRef, complete: Boolean): Flow<List<Entry>> = flow {
        fail(StorageError.UNSUPPORTED, "A file handed over by another app has nothing to list")
    }

    @SuppressLint("Recycle") // The caller closes the stream.
    override suspend fun openRead(ref: NodeRef): InputStream = withContext(Dispatchers.IO) {
        val uri = uriOf(ref)
        access { resolver.openInputStream(uri) } ?: fail(StorageError.DISCONNECTED, NOT_OPENED)
    }

    /** Null when the descriptor is not seekable, e.g. a sender that streams through a pipe. */
    override suspend fun openChannel(ref: NodeRef): SeekableByteChannel? = withContext(Dispatchers.IO) {
        val uri = uriOf(ref)
        val descriptor = try {
            resolver.openFileDescriptor(uri, "r")
        } catch (_: FileNotFoundException) {
            null
        } catch (denied: SecurityException) {
            fail(StorageError.PERMISSION, NO_GRANT, denied)
        } ?: return@withContext null
        val channel = ParcelFileDescriptor.AutoCloseInputStream(descriptor).channel
        if (runCatching { channel.position() }.isSuccess) channel else { runCatching { channel.close() }; null }
    }

    override suspend fun isDescendant(candidate: NodeRef, ancestor: NodeRef): Boolean = false

    override suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String): Entry = readOnly()
    override suspend fun rename(ref: NodeRef, name: String): Entry = readOnly()
    override suspend fun delete(ref: NodeRef) { readOnly() }
    override suspend fun openWrite(ref: NodeRef): OutputStream = readOnly()
    override suspend fun commit(staged: NodeRef, parent: NodeRef, name: String, replace: Entry?, onRetained: RetainedObjects?): Entry =
        readOnly()

    private fun readOnly(): Nothing = fail(StorageError.UNSUPPORTED, "Files handed over by other apps are read-only")

    private inline fun <T> access(block: () -> T): T = try {
        block()
    } catch (denied: SecurityException) {
        fail(StorageError.PERMISSION, NO_GRANT, denied)
    } catch (missing: FileNotFoundException) {
        fail(StorageError.NOT_FOUND, "This file is no longer there", missing)
    }

    private fun fail(reason: StorageError, message: String, cause: Throwable? = null): Nothing =
        throw StorageException(reason, message, cause)

    private companion object {
        const val NO_GRANT = "Luna was not given access to this file"
        const val NOT_OPENED = "The app that shared this file could not open it"
    }
}
