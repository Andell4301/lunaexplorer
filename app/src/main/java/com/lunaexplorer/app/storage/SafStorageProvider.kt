package com.lunaexplorer.app.storage

import android.annotation.SuppressLint
import android.app.AuthenticationRequiredException
import android.content.Context
import android.content.UriPermission
import android.database.ContentObserver
import android.database.Cursor
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Bundle
import android.os.DeadObjectException
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.lunaexplorer.core.*
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resumeWithException

class SafStorageProvider(context: Context) : StorageProvider, ContentAddressable {
    override val id = "saf"
    override val features = setOf(Feature.STABLE_KEYS, Feature.RANGE_READ)
    // Removable media and remote document providers may be case-insensitive.
    override fun namesEqual(first: String, second: String): Boolean = first.equals(second, ignoreCase = true)
    private val resolver = context.applicationContext.contentResolver
    private val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val notices: SharedFlow<String> = _notices

    fun rootForTree(uri: Uri): StorageRoot {
        if (!DocumentsContract.isTreeUri(uri)) fail(StorageError.PERMISSION, "Choose a folder using the system folder picker")
        val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        return StorageRoot(NodeRef(id, document.toString()), nameFromDocumentId(uri), removable = true)
    }

    override suspend fun roots(): List<StorageRoot> = withContext(Dispatchers.IO) {
        val grants = grants()
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
            .map { rootForTree(it.uri) }
        if (grants.isEmpty()) return@withContext emptyList()
        coroutineScope {
            grants.map { root ->
                async {
                    withTimeoutOrNull(ROOT_NAME_BUDGET_MILLIS) {
                        runCatching { root.copy(title = stat(root.ref).name) }.getOrNull()
                    } ?: root.copy(description = "Unavailable")
                }
            }.awaitAll()
        }
    }

    private fun nameFromDocumentId(uri: Uri): String = runCatching {
        DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').trimEnd('/')
            .substringAfterLast('/').ifBlank { uri.authority.orEmpty() }
    }.getOrDefault("").ifBlank { "Granted folder" }

    override fun contentUri(ref: NodeRef): Uri = uri(ref)

    suspend fun takeGrant(uri: Uri, flags: Int) = io {
        val granted = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (granted and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) fail(StorageError.PERMISSION, "The provider did not grant read access")
        resolver.takePersistableUriPermission(uri, granted)
        forgetGrants()
    }

    override suspend fun forgetRoot(root: StorageRoot) = io {
        val document = uri(root.ref)
        val tree = DocumentsContract.buildTreeDocumentUri(document.authority, DocumentsContract.getTreeDocumentId(document))
        resolver.persistedUriPermissions.find { it.uri == tree }?.let {
            resolver.releasePersistableUriPermission(it.uri,
                (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))
            forgetGrants()
        }
        Unit
    }

    override suspend fun stat(ref: NodeRef): Entry = io {
        val document = uri(ref)
        val permission = requireGrant(document, write = false)
        query(document).use { cursor ->
            if (!cursor.moveToFirst()) fail(StorageError.NOT_FOUND, "This item no longer exists")
            entry(cursor, document, parentUri(ref), permission)
        }
    }

    override fun list(parent: NodeRef, complete: Boolean): Flow<List<Entry>> = listing(parent, requireComplete = complete)

    /** Known only for refs seen in a listing, create or move; a restored ref returns null. */
    override suspend fun parentOf(ref: NodeRef): NodeRef? = parentUri(ref)?.let { reference(it) }

    // EXTRA_LOADING cursors are incomplete; requery on notifications and emit only unseen rows.
    private fun listing(parent: NodeRef, requireComplete: Boolean): Flow<List<Entry>> = flow {
        mapped {
            val document = uri(parent)
            val permission = requireGrant(document, write = false)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(document, DocumentsContract.getDocumentId(document))
            val seen = HashSet<String>()
            val changed = Channel<Unit>(Channel.CONFLATED)
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) { changed.trySend(Unit) }
            }
            // Register both: the cursor's observer works even when its notification URI is outside
            // the tree grant; the resolver observer is the fallback.
            runCatching { resolver.registerContentObserver(children, true, observer) }
            var cursor: Cursor? = null
            try {
                val deadline = System.nanoTime() + LOADING_BUDGET_NANOS
                var attempts = 0
                while (true) {
                    val next = query(children)
                    cursor?.close()
                    cursor = next
                    runCatching { next.registerContentObserver(observer) }
                    attempts++
                    val before = seen.size
                    var batch = ArrayList<Entry>(128)
                    while (next.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val item = entry(next, document, document, permission)
                        if (!seen.add(item.ref.key)) continue
                        batch.add(item)
                        if (batch.size == 128) { emit(batch); batch = ArrayList(128) }
                    }
                    if (batch.isNotEmpty()) emit(batch)
                    val extras = next.extras ?: Bundle.EMPTY
                    extras.getString(DocumentsContract.EXTRA_ERROR)?.let { message ->
                        if (seen.isEmpty() || requireComplete) fail(StorageError.IO, message)
                        _notices.tryEmit(message)
                    }
                    extras.getString(DocumentsContract.EXTRA_INFO)?.let { _notices.tryEmit(it) }
                    if (!extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) return@mapped
                    // Stop when a query adds no rows: a provider that always sets EXTRA_LOADING and
                    // notifies immediately would otherwise loop.
                    val gainedRows = seen.size > before
                    val remaining = (deadline - System.nanoTime()) / 1_000_000
                    if (!gainedRows || attempts >= MAX_LOADING_ATTEMPTS || remaining <= 0 ||
                        withTimeoutOrNull(remaining) { changed.receive() } == null) {
                        if (requireComplete) {
                            fail(StorageError.IO, "The storage provider has not finished listing this folder yet")
                        }
                        if (seen.isEmpty()) {
                            _notices.tryEmit("The storage provider is still preparing this folder. Pull down to refresh.")
                        }
                        return@mapped
                    }
                }
            } finally {
                runCatching { resolver.unregisterContentObserver(observer) }
                cursor?.close()
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String): Entry = io {
        validateName(name)
        val document = uri(parent)
        requireCapability(parent, Capability.CREATE)
        val internalStage = name.matches(STAGE_NAME)
        // Generated staging names skip the sibling scan. For user-chosen names the scan tells a
        // provider-renamed result apart from an existing sibling.
        val siblings = if (internalStage) null else siblings(parent)
        if (siblings?.any { namesEqual(it.name, name) } == true) fail(StorageError.CONFLICT, "An item named '$name' already exists")
        val created = DocumentsContract.createDocument(resolver, document,
            if (directory) Document.MIME_TYPE_DIR else mimeType, name)
            ?: fail(StorageError.IO, "The storage provider did not create the item")
        val result = stat(reference(created, document))
        if (siblings?.any { sameDocument(uri(it.ref), created) } == true) {
            fail(StorageError.CONFLICT, "The storage provider returned an existing item; writing was refused")
        }
        if (result.name != name && !internalStage) {
            // Only delete the newly returned identity, never a pre-existing sibling.
            try { delete(result.ref) } catch (error: CancellationException) { throw error } catch (_: Exception) { }
            fail(StorageError.CONFLICT, "The provider changed '$name' to '${result.name}'. Choose a different name and refresh the folder")
        }
        result
    }

    override suspend fun rename(ref: NodeRef, name: String): Entry = io {
        validateName(name)
        val previous = stat(ref)
        if (previous.name == name) return@io previous
        requireCapability(ref, Capability.RENAME)
        val parent = parentUri(ref) ?: fail(StorageError.UNSUPPORTED, "Open the containing folder before renaming this item")
        val parentRef = reference(parent)
        val clash = child(parentRef, name)
        if (clash != null && !sameDocument(uri(clash.ref), uri(ref))) {
            fail(StorageError.CONFLICT, "An item named '$name' already exists")
        }
        val renamed = DocumentsContract.renameDocument(resolver, uri(ref), name)
            ?: fail(StorageError.IO, "The storage provider did not rename the item")
        val result = stat(reference(renamed, parent))
        if (result.name != name) {
            // Restore the original name if possible. Never delete after a failed rename.
            val restored = try {
                val holder = child(parentRef, previous.name)
                if (holder == null || sameDocument(uri(holder.ref), renamed)) {
                    DocumentsContract.renameDocument(resolver, renamed, previous.name) != null
                } else false
            } catch (error: CancellationException) { throw error } catch (_: Exception) { false }
            fail(StorageError.CONFLICT, "The provider changed the requested name to '${result.name}'. " +
                if (restored) "The original name was restored; refresh the folder" else "The item remains under its new name; refresh the folder")
        }
        result
    }

    override suspend fun delete(ref: NodeRef) = io {
        val info = requireCapability(ref, Capability.DELETE)
        if (info.directory) {
            // deleteDocument may delete recursively, so refuse nonempty folders.
            var hasChildren = false
            listing(ref, requireComplete = true).collect { if (it.isNotEmpty()) hasChildren = true }
            if (hasChildren) fail(StorageError.CONFLICT, "The folder is not empty; its remaining contents were preserved")
        }
        if (!DocumentsContract.deleteDocument(resolver, uri(ref))) fail(StorageError.IO, "The storage provider refused to delete the item")
    }

    @SuppressLint("Recycle") // The caller closes the stream.
    override suspend fun openRead(ref: NodeRef): InputStream = io {
        requireCapability(ref, Capability.READ)
        resolver.openInputStream(uri(ref)) ?: fail(StorageError.DISCONNECTED, "The storage provider could not open this file")
    }

    override suspend fun openChannel(ref: NodeRef): SeekableByteChannel? = io {
        requireCapability(ref, Capability.READ)
        val descriptor = resolver.openFileDescriptor(uri(ref), "r") ?: return@io null
        val channel = ParcelFileDescriptor.AutoCloseInputStream(descriptor).channel
        // position() fails on pipe-backed documents, which cannot seek.
        if (runCatching { channel.position() }.isSuccess) channel else { runCatching { channel.close() }; null }
    }

    @SuppressLint("Recycle") // The caller closes the stream.
    override suspend fun openWrite(ref: NodeRef): OutputStream = io {
        val info = requireCapability(ref, Capability.WRITE)
        if (info.size != null && info.size != 0L) fail(StorageError.CONFLICT, "Refusing to truncate a nonempty file")
        resolver.openOutputStream(uri(ref), "wt") ?: fail(StorageError.DISCONNECTED, "The storage provider could not open this file for writing")
    }

    override suspend fun commit(staged: NodeRef, parent: NodeRef, name: String, replace: Entry?, onRetained: RetainedObjects?): Entry = io {
        val actualParent = parentUri(staged)
        if (actualParent == null || !sameDocument(actualParent, uri(parent))) fail(StorageError.UNSUPPORTED, "Staged files must belong to the destination folder")
        if (replace == null) return@io rename(staged, name)
        replacing(staged, parent, name, replace.ref, onRetained)
    }

    // Keep the old document until the replacement is published; restore it on failure and report undeletable backups.
    private suspend fun replacing(staged: NodeRef, parent: NodeRef, name: String, replace: NodeRef, onRetained: RetainedObjects?): Entry {
        val existing = stat(replace)
        if (existing.directory) fail(StorageError.UNSUPPORTED, "Folders are merged rather than replaced")
        if (Capability.RENAME !in existing.capabilities || Capability.DELETE !in existing.capabilities) {
            fail(StorageError.UNSUPPORTED, "This storage provider cannot overwrite '${existing.name}'; choose Skip or Keep both")
        }
        if (!namesEqual(existing.name, name)) {
            fail(StorageError.CONFLICT, "The item being replaced is now named '${existing.name}'; refresh and try again")
        }
        val occupant = child(parent, name)
        if (occupant == null || !sameDocument(uri(occupant.ref), uri(replace))) {
            fail(StorageError.CONFLICT, "A different item now occupies '$name'; refresh and try again")
        }

        val retained = overwriting(existing.name) { rename(replace, backupName(name)) }
        val published = try {
            overwriting(existing.name) { rename(staged, name) }
        } catch (error: Throwable) {
            // NonCancellable: the restore must run even when cancellation caused the failure.
            val restored = withContext(NonCancellable) { runCatching { rename(retained.ref, existing.name) }.isSuccess }
            if (error is CancellationException) throw error
            throw StorageException(StorageError.IO, "Overwriting '${existing.name}' failed and the previous file was " +
                if (restored) "restored" else "retained as ${retained.name}", error)
        }
        withContext(NonCancellable) {
            if (runCatching { delete(retained.ref) }.isFailure) {
                onRetained?.invoke(retained.ref, "The previous '${existing.name}' could not be removed after " +
                    "overwriting it; a full copy remains as ${retained.name}")
            }
        }
        return published
    }

    /** A rename CONFLICT becomes UNSUPPORTED so internal backup names stay out of the message. */
    private suspend fun overwriting(subject: String, step: suspend () -> Entry): Entry = try {
        step()
    } catch (error: StorageException) {
        if (error.reason == StorageError.CONFLICT) {
            throw StorageException(StorageError.UNSUPPORTED,
                "This storage provider cannot overwrite '$subject'; choose Skip or Keep both", error)
        }
        throw error
    }

    /** Truncated so the whole name fits in 255 UTF-8 bytes. */
    private fun backupName(name: String): String {
        val prefix = ".luna-replaced-${UUID.randomUUID().toString().replace("-", "").take(12)}-"
        return prefix + name.takeBytes(255 - prefix.toByteArray(Charsets.UTF_8).size)
    }

    private fun String.takeBytes(limit: Int): String {
        if (limit <= 0) return ""
        var index = 0
        var used = 0
        while (index < length) {
            val point = codePointAt(index)
            val width = Character.charCount(point)
            val size = String(Character.toChars(point)).toByteArray(Charsets.UTF_8).size
            if (used + size > limit) break
            used += size
            index += width
        }
        return substring(0, index)
    }

    override suspend fun relocate(ref: NodeRef, parent: NodeRef, name: String): Entry? = io {
        validateName(name)
        stat(ref) // Fails if the source is gone or its grant was revoked.
        val sourceParent = parentUri(ref) ?: return@io null
        val target = uri(parent)
        val flags = query(uri(ref)).use { cursor ->
            if (!cursor.moveToFirst()) return@io null
            cursor.long(Document.COLUMN_FLAGS)?.toInt() ?: 0
        }
        if (flags and Document.FLAG_SUPPORTS_MOVE == 0) return@io null
        requireGrant(uri(ref), write = true)
        requireGrant(target, write = true)
        if (child(parent, name) != null) fail(StorageError.CONFLICT, "An item named '$name' already exists")
        val moved = runCatching {
            DocumentsContract.moveDocument(resolver, uri(ref), sourceParent, target)
        }.getOrNull() ?: return@io null
        val landed = stat(reference(moved, target))
        // moveDocument keeps the display name, so renaming is a second step.
        if (!namesEqual(landed.name, name)) rename(landed.ref, name) else landed
    }

    override suspend fun isDescendant(candidate: NodeRef, ancestor: NodeRef): Boolean = io {
        if (candidate.provider != id || ancestor.provider != id) return@io false
        val child = uri(candidate)
        val parent = uri(ancestor)
        if (child.authority != parent.authority) return@io false
        if (sameDocument(child, parent)) return@io true
        requireGrant(child, write = false)
        requireGrant(parent, write = false)
        if (Build.VERSION.SDK_INT >= 29) {
            try { return@io DocumentsContract.isChildDocument(resolver, parent, child) }
            catch (_: UnsupportedOperationException) { /* Fall back to the traversal below. */ }
        }
        // API 26–28 and providers lacking isChildDocument: do not infer ancestry from opaque IDs.
        val pending = ArrayDeque<NodeRef>()
        val seen = HashSet<String>()
        pending.add(ancestor)
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val next = pending.removeFirst()
            val identity = documentIdentity(uri(next))
            if (!seen.add(identity)) continue
            if (seen.size > 100_000) fail(StorageError.UNSUPPORTED, "Folder ancestry could not be verified safely")
            var found = false
            listing(next, requireComplete = true).collect { batch ->
                batch.forEach { item ->
                    if (sameDocument(uri(item.ref), child)) found = true
                    if (item.directory) pending.add(item.ref)
                }
            }
            if (found) return@io true
        }
        false
    }

    private suspend fun requireCapability(ref: NodeRef, capability: Capability): Entry {
        requireGrant(uri(ref), write = capability != Capability.READ && capability != Capability.LIST)
        val info = stat(ref)
        if (capability !in info.capabilities) fail(StorageError.UNSUPPORTED, "This storage provider does not support ${capability.name.lowercase()} for '${info.name}'")
        return info
    }

    private suspend fun siblings(parent: NodeRef): List<Entry> {
        val result = ArrayList<Entry>()
        listing(parent, requireComplete = true).collect { result.addAll(it) }
        return result
    }

    private fun entry(cursor: Cursor, tree: Uri, parent: Uri?, permission: UriPermission): Entry {
        val documentId = cursor.string(Document.COLUMN_DOCUMENT_ID) ?: fail(StorageError.IO, "The provider returned an item without an identity")
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        val mime = cursor.string(Document.COLUMN_MIME_TYPE) ?: "application/octet-stream"
        val directory = mime == Document.MIME_TYPE_DIR
        val name = cursor.string(Document.COLUMN_DISPLAY_NAME) ?: "Unnamed item"
        val flags = cursor.long(Document.COLUMN_FLAGS)?.toInt() ?: 0
        val caps = mutableSetOf(Capability.HIDDEN)
        if (permission.isReadPermission) {
            if (directory) caps += Capability.LIST
            else if (flags and (Document.FLAG_VIRTUAL_DOCUMENT or Document.FLAG_PARTIAL) == 0) caps += Capability.READ
        }
        if (permission.isWritePermission) {
            if (directory && flags and Document.FLAG_DIR_SUPPORTS_CREATE != 0) {
                caps += Capability.CREATE
                caps += Capability.REPLACE
            }
            if (!directory && flags and Document.FLAG_SUPPORTS_WRITE != 0) caps += Capability.WRITE
            if (parent != null && flags and Document.FLAG_SUPPORTS_RENAME != 0) caps += Capability.RENAME
            if (parent != null && flags and Document.FLAG_SUPPORTS_DELETE != 0) caps += Capability.DELETE
        }
        return Entry(reference(document, parent), name, directory,
            if (directory) null else cursor.long(Document.COLUMN_SIZE)?.takeIf { it >= 0 },
            cursor.long(Document.COLUMN_LAST_MODIFIED)?.takeIf { it > 0 }, mime, caps)
    }

    private suspend fun query(uri: Uri): Cursor = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        try {
            // A null cursor means either a missing document or an unavailable provider.
            val cursor = resolver.query(uri, projection, null, null, null, signal)
                ?: if (providerPresent(uri)) fail(StorageError.NOT_FOUND, "This item no longer exists")
                else fail(StorageError.DISCONNECTED, "The storage provider is disconnected")
            continuation.resume(cursor) { _, value, _ -> value.close() }
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private var grantsAt = 0L
    private var grantsCache: List<UriPermission> = emptyList()

    private fun grants(): List<UriPermission> {
        val now = System.nanoTime()
        if (now - grantsAt > GRANT_CACHE_NANOS || grantsCache.isEmpty()) {
            grantsCache = runCatching { resolver.persistedUriPermissions }.getOrDefault(emptyList())
            grantsAt = now
        }
        return grantsCache
    }

    private fun forgetGrants() { grantsAt = 0L; grantsCache = emptyList() }

    private fun grant(uri: Uri) = grants().firstOrNull {
        DocumentsContract.isTreeUri(it.uri) && it.uri.authority == uri.authority &&
            DocumentsContract.getTreeDocumentId(it.uri) == DocumentsContract.getTreeDocumentId(uri)
    }
    private fun requireGrant(uri: Uri, write: Boolean): UriPermission {
        val grant = grant(uri)
        if (grant == null || !grant.isReadPermission || (write && !grant.isWritePermission)) {
            fail(StorageError.PERMISSION, if (write) "This folder is read-only. Grant write access using Add storage" else "Folder permission was revoked. Grant access again using Add storage")
        }
        return grant
    }
    private fun providerPresent(uri: Uri): Boolean =
        runCatching { resolver.acquireUnstableContentProviderClient(uri.authority.orEmpty())?.use { true } }.getOrNull() == true

    // Cache parents separately from references so renames and overlapping tree grants preserve document identity.
    private val parents = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > PARENT_MEMORY
    }

    private fun reference(uri: Uri, parent: Uri? = null): NodeRef {
        if (parent != null) synchronized(parents) { parents[documentIdentity(uri)] = DocumentsContract.getDocumentId(parent) }
        return NodeRef(id, uri.toString())
    }
    private fun uri(ref: NodeRef): Uri {
        if (ref.provider != id) fail(StorageError.DISCONNECTED, "Wrong storage provider")
        val uri = Uri.parse(ref.key)
        if (uri.scheme != "content" || !DocumentsContract.isTreeUri(uri)) fail(StorageError.PERMISSION, "Invalid granted-folder reference")
        return uri
    }
    private fun parentUri(ref: NodeRef): Uri? {
        val document = uri(ref)
        val parent = synchronized(parents) { parents[documentIdentity(document)] } ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(document, parent)
    }
    private fun sameDocument(a: Uri, b: Uri) = documentIdentity(a) == documentIdentity(b)
    private fun documentIdentity(uri: Uri) = "${uri.authority}:${DocumentsContract.getDocumentId(uri)}"
    private fun Cursor.string(column: String): String? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)
    private fun Cursor.long(column: String): Long? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { mapped(block) }
    private suspend fun <T> mapped(block: suspend () -> T): T = try { block() } catch (error: Exception) {
        if (error is CancellationException || error is StorageException) throw error
        if (error is OperationCanceledException) throw CancellationException("Storage query cancelled", error)
        // A SecurityException subclass, but the tree grant is still valid: AUTH, not PERMISSION.
        if (error is AuthenticationRequiredException) {
            throw StorageException(StorageError.AUTH,
                "This storage provider needs you to sign in again. Open its own app, then refresh.", error)
        }
        val reason = when (error) {
            is SecurityException -> StorageError.PERMISSION
            is FileNotFoundException -> StorageError.NOT_FOUND
            is UnsupportedOperationException -> StorageError.UNSUPPORTED
            is DeadObjectException -> StorageError.DISCONNECTED
            else -> if (error.message?.contains("ENOSPC", true) == true || error.message?.contains("no space", true) == true) StorageError.NO_SPACE else StorageError.IO
        }
        val message = when (reason) {
            StorageError.PERMISSION -> "Folder permission was revoked or access was denied. Grant access again using Add storage"
            StorageError.NOT_FOUND -> "The item no longer exists or its storage is disconnected"
            StorageError.NO_SPACE -> "The destination has insufficient free space"
            StorageError.DISCONNECTED -> "The storage provider disconnected; reconnect and try again"
            else -> error.message ?: "The storage provider could not complete this operation"
        }
        throw StorageException(reason, message, error)
    }
    private fun fail(reason: StorageError, message: String): Nothing = throw StorageException(reason, message)

    private companion object {
        val STAGE_NAME = Regex("\\.luna-[0-9a-fA-F-]{1,12}-[0-9a-fA-F-]{36}\\.partial(?:\\..*)?")
        const val ROOT_NAME_BUDGET_MILLIS = 1_500L
        const val LOADING_BUDGET_NANOS = 8_000_000_000L
        /** Maximum delay before observing externally revoked grants. */
        const val GRANT_CACHE_NANOS = 2_000_000_000L
        const val PARENT_MEMORY = 100_000
        const val MAX_LOADING_ATTEMPTS = 6
    }
}
