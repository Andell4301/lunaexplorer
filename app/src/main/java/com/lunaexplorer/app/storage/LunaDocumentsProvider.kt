package com.lunaexplorer.app.storage

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import com.lunaexplorer.app.AppGraph
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.R
import com.lunaexplorer.app.debug.DebugLog
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.PathAddressable
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import com.lunaexplorer.core.StorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

// Binder entry points read saved roots without the UI and block on providers off the main thread.
class LunaDocumentsProvider : DocumentsProvider() {
    private val thread by lazy { HandlerThread("luna-documents").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }
    @Volatile private var served: Served? = null

    private class Served(val generation: Long, val loadedAt: Long, val roots: List<ServedRoot>)
    private class ServedRoot(val path: String, val name: String, val provider: StorageProvider, val ref: NodeRef)

    override fun onCreate(): Boolean = true

    private val authority: String get() = authority(requireNotNull(context))
    private fun graph(): AppGraph = (graphOverride ?: applicationGraph)(requireNotNull(context))

    /** Routes the legacy query overload to the Bundle overload the framework uses. */
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?, signal: CancellationSignal?): Cursor? =
        query(uri, projection, null as Bundle?, signal)

    override fun queryRoots(projection: Array<String>?): Cursor = guard {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        for (root in servedRoots()) {
            val entry = runCatching { runBlocking { root.provider.stat(root.ref) } }.getOrNull() ?: continue
            if (!entry.directory) continue
            var flags = Root.FLAG_SUPPORTS_IS_CHILD
            if (Capability.CREATE in entry.capabilities && writesReach(root.provider, root.ref)) flags = flags or Root.FLAG_SUPPORTS_CREATE
            if (root.provider is PathAddressable) flags = flags or Root.FLAG_LOCAL_ONLY
            cursor.newRow()
                .add(Root.COLUMN_ROOT_ID, rootId(root.path))
                .add(Root.COLUMN_FLAGS, flags)
                .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                .add(Root.COLUMN_TITLE, root.name.ifBlank { titleOf(root.path) })
                .add(Root.COLUMN_SUMMARY, root.path)
                .add(Root.COLUMN_DOCUMENT_ID, idOf(root.ref))
                .add(Root.COLUMN_AVAILABLE_BYTES, runCatching { runBlocking { root.provider.availableBytes(root.ref) } }.getOrNull())
        }
        cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor = guard {
        val (provider, ref) = locate(documentId)
        val entry = runBlocking { provider.stat(ref) }
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).also { row(it, entry, provider is PathAddressable, writesReach(provider, ref)) }
    }

    // Writing requires a directly accessible path; offering it for helper-only files could leave empty files behind.
    private fun writesReach(provider: StorageProvider, ref: NodeRef): Boolean =
        provider !is PathAddressable || provider.pathOf(ref) != null

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor = guard {
        val (provider, parent) = locate(parentDocumentId)
        val local = provider is PathAddressable
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        runBlocking { provider.list(parent).collect { batch -> batch.forEach { row(cursor, it, local) } } }
        cursor.setNotificationUri(requireNotNull(context).contentResolver,
            DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId))
        cursor
    }

    /** Fails closed, so a tree grant never reaches outside its tree. */
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = runCatching {
        val parent = refOf(parentDocumentId)
        val child = refOf(documentId)
        parent.provider == child.provider && runBlocking { graph().providers.provider(child).isDescendant(child, parent) }
    }.getOrDefault(false)

    override fun getDocumentType(documentId: String): String = guard {
        val (provider, ref) = locate(documentId)
        mimeOf(runBlocking { provider.stat(ref) })
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor = guard {
        val (provider, ref) = locate(documentId)
        val access = ParcelFileDescriptor.parseMode(mode)
        val writing = access and ParcelFileDescriptor.MODE_WRITE_ONLY != 0
        val entry = blocking(signal) { provider.stat(ref) }
        if (entry.directory) throw FileNotFoundException("${entry.name} is a folder")
        val path = (provider as? PathAddressable)?.pathOf(ref)
        if (path != null) {
            if (writing && Capability.WRITE !in entry.capabilities) throw FileNotFoundException("${entry.name} cannot be written")
            return ParcelFileDescriptor.open(File(path), access)
        }
        if (writing) throw UnsupportedOperationException("Files on ${ref.provider} storage are served for reading only")
        val caller = runCatching { callingPackage }.getOrNull() ?: "Another app"
        signal?.throwIfCanceled()
        val context = requireNotNull(context)
        val external = Binder.getCallingPid() != Process.myPid() && caller != context.packageName
        StreamProvider.openForReading(context, provider, ref, handler, caller, allowProxy = external)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String = guard {
        val (provider, parent) = locate(parentDocumentId)
        val created = runBlocking { provider.create(parent, displayName, directory = mimeType == Document.MIME_TYPE_DIR, mimeType = mimeType) }
        childrenChanged(parentDocumentId)
        idOf(created.ref)
    }

    /** Not recursive: the storage provider refuses a folder that still has contents. */
    override fun deleteDocument(documentId: String) {
        guard {
            val (provider, ref) = locate(documentId)
            val parent = runCatching { runBlocking { provider.parentOf(ref) } }.getOrNull()
            runBlocking { provider.delete(ref) }
            parent?.let { childrenChanged(idOf(it)) }
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String? = guard {
        val (provider, ref) = locate(documentId)
        val renamed = runBlocking { provider.rename(ref, displayName) }
        runCatching { runBlocking { provider.parentOf(renamed.ref) } }.getOrNull()?.let { childrenChanged(idOf(it)) }
        idOf(renamed.ref).takeIf { it != documentId }
    }

    private fun servedRoots(): List<ServedRoot> {
        val now = System.nanoTime()
        val current = generation.get()
        served?.takeIf { it.generation == current && now - it.loadedAt < CACHE_NANOS }?.let { return it.roots }
        val graph = graph()
        val folders = runCatching { runBlocking { graph.database.loadSession() } }
            .onFailure { DebugLog.w(TAG, it) { "The served folders could not be read: ${it.message}" } }
            .getOrNull()?.preferences?.servedFolders.orEmpty()
        val roots = folders.distinctBy { it.path }.mapNotNull { folder ->
            graph.providers.all.firstNotNullOfOrNull { provider ->
                (provider as? PathAddressable)?.refFor(folder.path)
                    ?.let { ServedRoot(folder.path, folder.name, provider, it) }
            }
        }
        served = Served(current, now, roots)
        return roots
    }

    /** A document id can encode any reference, so ids outside the served folders are rejected here. */
    private fun locate(documentId: String): Pair<StorageProvider, NodeRef> {
        val ref = refOf(documentId)
        val provider = runCatching { graph().providers.provider(ref) }.getOrElse { throw FileNotFoundException(it.message) }
        val allowed = servedRoots().any { root ->
            root.ref.provider == ref.provider &&
                (root.ref == ref || runCatching { runBlocking { provider.isDescendant(ref, root.ref) } }.getOrDefault(false))
        }
        if (!allowed) throw FileNotFoundException("This item is not in a folder Luna serves")
        return provider to ref
    }

    private fun row(cursor: MatrixCursor, entry: Entry, local: Boolean, writesReach: Boolean = true) {
        val capabilities = entry.capabilities
        var flags = 0
        if (entry.directory) {
            if (Capability.CREATE in capabilities && writesReach) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (local && writesReach && Capability.WRITE in capabilities) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (Capability.DELETE in capabilities) flags = flags or Document.FLAG_SUPPORTS_DELETE
        if (Capability.RENAME in capabilities) flags = flags or Document.FLAG_SUPPORTS_RENAME
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, idOf(entry.ref))
            .add(Document.COLUMN_DISPLAY_NAME, entry.name)
            .add(Document.COLUMN_MIME_TYPE, mimeOf(entry))
            .add(Document.COLUMN_SIZE, entry.size)
            .add(Document.COLUMN_LAST_MODIFIED, entry.modified)
            .add(Document.COLUMN_FLAGS, flags)
    }

    private fun childrenChanged(parentId: String) {
        runCatching {
            context?.contentResolver?.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, parentId), null)
        }
    }

    private fun <T> blocking(signal: CancellationSignal?, block: suspend () -> T): T = runBlocking {
        val job = coroutineContext.job
        signal?.setOnCancelListener { job.cancel() }
        try {
            signal?.throwIfCanceled()
            block()
        } finally {
            signal?.setOnCancelListener(null)
        }
    }

    /** Maps failures to exceptions the framework can carry across the binder; any other exception ends the process. */
    private inline fun <T> guard(block: () -> T): T = try { block() } catch (error: Exception) {
        throw when (error) {
            is CancellationException -> OperationCanceledException(error.message)
            is FileNotFoundException, is UnsupportedOperationException, is IllegalArgumentException,
            is IllegalStateException, is SecurityException, is OperationCanceledException -> error
            is StorageException ->
                if (error.reason == StorageError.UNSUPPORTED) UnsupportedOperationException(error.message, error)
                else FileNotFoundException(error.message)
            else -> {
                DebugLog.w(TAG, error) { "A document request failed: ${error.message}" }
                FileNotFoundException(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    companion object {
        private const val TAG = "Documents"
        private const val CACHE_NANOS = 2_000_000_000L
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
        private val generation = AtomicLong()
        private val applicationGraph: (Context) -> AppGraph = { (it.applicationContext as LunaApplication).graph }
        /** Test seam: replaces the application's graph. */
        internal var graphOverride: ((Context) -> AppGraph)? = null

        fun authority(context: Context): String = "${context.packageName}.documents"
        fun component(context: Context): ComponentName = ComponentName(context, LunaDocumentsProvider::class.java)

        /** Document id format: `providerId:urlSafeEncodedKey`. */
        internal fun idOf(ref: NodeRef): String = "${ref.provider}:${StreamProvider.encodeKey(ref.key)}"

        private fun refOf(documentId: String): NodeRef {
            val split = documentId.indexOf(':')
            if (split <= 0) throw FileNotFoundException("Not one of Luna's documents")
            val key = StreamProvider.decodeKey(documentId.substring(split + 1))
                ?: throw FileNotFoundException("Not one of Luna's documents")
            return NodeRef(documentId.substring(0, split), key)
        }

        private fun rootId(path: String): String =
            MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

        internal fun titleOf(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

        private fun mimeOf(entry: Entry): String = if (entry.directory) Document.MIME_TYPE_DIR else entry.mimeType

        /** A binder call; run it off the main thread. */
        fun setEnabled(context: Context, enabled: Boolean) {
            context.packageManager.setComponentEnabledSetting(component(context),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
        }

        /** Call after the session with the changed served list is saved: drops cached roots and makes pickers re-query. */
        fun rootsChanged(context: Context) {
            generation.incrementAndGet()
            context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority(context)), null)
        }
    }
}
