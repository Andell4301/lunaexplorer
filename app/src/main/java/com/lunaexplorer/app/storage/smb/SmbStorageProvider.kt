package com.lunaexplorer.app.storage.smb

import com.lunaexplorer.app.debug.DebugLog
import com.lunaexplorer.app.storage.RemoteChannel
import com.lunaexplorer.app.storage.RemoteInputStream
import com.lunaexplorer.app.storage.RemoteReading
import com.lunaexplorer.core.BudgetedReads
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.Feature
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.ReadBudget
import com.lunaexplorer.core.RetainedObjects
import com.lunaexplorer.core.RootKind
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import com.lunaexplorer.core.StorageProvider
import com.lunaexplorer.core.StorageRoot
import com.lunaexplorer.core.validateName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection
import java.nio.channels.SeekableByteChannel

// Root metadata comes from settings; credentials are resolved only when connecting.
class SmbStorageProvider(
    private val accounts: () -> List<SmbAccount>,
    /** Null while the credential vault is locked. */
    private val withPassword: (SmbAccount) -> SmbAccount?,
    private val connector: SmbConnector = SmbjConnector(),
) : StorageProvider, BudgetedReads {
    override val id = "smb"
    override val features = setOf(Feature.STABLE_KEYS, Feature.SET_TIMES, Feature.RANGE_READ, Feature.NETWORK)

    private val sessions = SmbSessions(connector)

    fun disconnect(accountId: String) = sessions.drop(accountId)

    suspend fun probe(account: SmbAccount): SmbProbe = withContext(Dispatchers.IO) {
        mapped {
            connector.connect(account).use { server ->
                if (account.wholeServer) {
                    val shares = try { server.shares() } catch (refused: SmbFailure) {
                        throw SmbFailure(refused.reason, "The server would not list its shares " +
                            "(${refused.message}). The sign-in itself worked; name a share instead.", refused)
                    }
                    SmbProbe(server.summary, shares.count { it.disk })
                } else {
                    server.open(account.share).close()
                    SmbProbe(server.summary, shares = null)
                }
            }
        }
    }

    override suspend fun roots(): List<StorageRoot> = accounts().map { account ->
        StorageRoot(rootOf(account), account.name, description = account.address, kind = RootKind.NETWORK)
    }

    override suspend fun forgetRoot(root: StorageRoot) {
        fail(StorageError.UNSUPPORTED, "Remove the server from Settings, under Network")
    }

    override suspend fun stat(ref: NodeRef): Entry {
        val place = place(ref)
        if (place.atServer) return serverEntry(place.account)
        return io(place, "stat") { share ->
            val item = share.stat(place.path) ?: fail(StorageError.NOT_FOUND, "Not found on ${place.account.name}")
            entry(place, item)
        }
    }

    override fun list(parent: NodeRef, complete: Boolean): Flow<List<Entry>> = flow {
        val place = place(parent)
        val listed = if (place.atServer) server(place, "list shares") { server ->
            server.shares().filter { it.disk }.map { shareEntry(place.account, it) }
        } else io(place, "list") { share ->
            val folder = share.stat(place.path) ?: fail(StorageError.NOT_FOUND, "Not found on ${place.account.name}")
            if (!folder.directory) fail(StorageError.UNSUPPORTED, "${folder.name} is not a folder")
            share.list(place.path).map { entry(place.child(it.name), it) }
        }
        listed.chunked(256).forEach { emit(it) }
    }

    override suspend fun child(parent: NodeRef, name: String): Entry? {
        val place = place(parent)
        if (place.atServer) return server(place, "look up share") { server ->
            server.shares().firstOrNull { it.disk && it.name.equals(name, ignoreCase = true) }
                ?.let { shareEntry(place.account, it) }
        }
        return io(place, "look up") { share ->
            val at = place.child(name)
            share.stat(at.path)?.let { entry(at, it) }
        }
    }

    override suspend fun parentOf(ref: NodeRef): NodeRef? {
        val place = place(ref)
        if (place.atServer) return null
        if (place.path.isEmpty()) return if (place.account.wholeServer) rootOf(place.account) else null
        return refOf(place.account.id, place.share, place.parentPath)
    }

    override suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String): Entry {
        validateName(name)
        val place = place(parent)
        if (place.atServer) fail(StorageError.UNSUPPORTED, "Shares are made on the server, not from here")
        return io(place, "create") { share ->
            val at = place.child(name)
            if (share.stat(at.path) != null) fail(StorageError.CONFLICT, "$name already exists")
            if (directory) share.mkdir(at.path) else share.createFile(at.path)
            entry(at, share.stat(at.path) ?: fail(StorageError.IO, "$name was not created"))
        }
    }

    override suspend fun rename(ref: NodeRef, name: String): Entry {
        validateName(name)
        val place = place(ref)
        if (place.path.isEmpty()) fail(StorageError.UNSUPPORTED, if (place.atServer) "A server cannot be renamed" else "A share cannot be renamed")
        return io(place, "rename") { share ->
            val to = place.sibling(name)
            if (share.stat(to.path) != null) fail(StorageError.CONFLICT, "$name already exists")
            share.rename(place.path, to.path, replace = false)
            entry(to, share.stat(to.path) ?: fail(StorageError.IO, "$name was not renamed"))
        }
    }

    override suspend fun delete(ref: NodeRef) {
        val place = place(ref)
        if (place.path.isEmpty()) fail(StorageError.UNSUPPORTED, if (place.atServer) "A server cannot be deleted" else "A share cannot be deleted")
        io(place, "delete") { share ->
            val item = share.stat(place.path) ?: fail(StorageError.NOT_FOUND, "Already gone")
            if (item.directory) {
                if (share.list(place.path).isNotEmpty()) fail(StorageError.CONFLICT, "The folder is not empty")
                share.deleteFolder(place.path)
            } else share.deleteFile(place.path)
        }
    }

    override suspend fun openRead(ref: NodeRef): InputStream = openReading(ref, null, ::RemoteInputStream)

    override suspend fun openRead(ref: NodeRef, budget: ReadBudget): InputStream =
        openReading(ref, budget, ::RemoteInputStream)

    override suspend fun openChannel(ref: NodeRef): SeekableByteChannel = openReading(ref, null) { reading, size -> RemoteChannel(reading, size) }

    override suspend fun openChannel(ref: NodeRef, budget: ReadBudget): SeekableByteChannel =
        openReading(ref, budget) { reading, size -> RemoteChannel(reading, size) }

    private suspend fun <T : Closeable> openReading(ref: NodeRef, budget: ReadBudget?, wrap: (RemoteReading, Long) -> T): T = holding {
        budget?.checkCancelled()
        io(ref, "open") { place, share ->
            val item = share.stat(place.path) ?: fail(StorageError.NOT_FOUND, "Not found on ${place.account.name}")
            if (item.directory) fail(StorageError.UNSUPPORTED, "${item.name} is a folder")
            wrap(reading(place, share, budget), item.size)
        }
    }

    override suspend fun openWrite(ref: NodeRef): OutputStream = holding { io(ref, "open for writing") { place, share -> share.write(place.path) } }

    override suspend fun relocate(ref: NodeRef, parent: NodeRef, name: String): Entry? {
        val from = place(ref)
        val into = place(parent)
        // A server-side rename works only within one share; null makes the caller copy instead.
        if (from.account.id != into.account.id || from.share != into.share || from.path.isEmpty()) return null
        return io(parent, "move") { _, share ->
            val to = into.child(name)
            if (share.stat(to.path) != null) fail(StorageError.CONFLICT, "$name already exists")
            share.rename(from.path, to.path, replace = false)
            entry(to, share.stat(to.path) ?: fail(StorageError.IO, "$name was not moved"))
        }
    }

    override suspend fun availableBytes(parent: NodeRef): Long? {
        val place = place(parent)
        return if (place.atServer) null else io(place, "free space") { it.freeBytes() }
    }

    override suspend fun isDescendant(candidate: NodeRef, ancestor: NodeRef): Boolean {
        val below = runCatching { place(candidate) }.getOrNull() ?: return false
        val above = runCatching { place(ancestor) }.getOrNull() ?: return false
        if (below.account.id != above.account.id) return false
        if (above.atServer) return true
        if (below.share != above.share) return false
        return above.path.isEmpty() || below.path == above.path || below.path.startsWith(above.path + "/")
    }

    override suspend fun commit(staged: NodeRef, parent: NodeRef, name: String, replace: Entry?, onRetained: RetainedObjects?): Entry {
        val from = place(staged)
        val into = place(parent)
        if (from.account.id != into.account.id || from.share != into.share || from.parentPath != into.path) {
            fail(StorageError.UNSUPPORTED, "Staged files must belong to the destination folder")
        }
        return io(parent, "publish") { _, share ->
            val to = into.child(name)
            val current = share.stat(to.path)
            if (replace == null) {
                if (current != null) fail(StorageError.CONFLICT, "$name already exists")
                share.rename(from.path, to.path, replace = false)
            } else {
                // The target may have changed during the copy; recheck it before overwriting.
                if (current == null) fail(StorageError.CONFLICT, "$name is no longer there; refresh and try again")
                if (current.directory) fail(StorageError.UNSUPPORTED, "Folders are merged rather than replaced")
                if (versionOf(current) != replace.version) {
                    fail(StorageError.CONFLICT, "$name changed since it was seen; refresh and try again")
                }
                share.rename(from.path, to.path, replace = true)
            }
            entry(to, share.stat(to.path) ?: fail(StorageError.IO, "$name was not published"))
        }
    }

    override suspend fun setModified(ref: NodeRef, epochMillis: Long): Long = io(ref, "set time") { place, share ->
        share.setModified(place.path, epochMillis)
        share.stat(place.path)?.modified ?: epochMillis
    }

    private class Place(val account: SmbAccount, val share: String, val path: String) {
        val atServer: Boolean get() = share.isEmpty()
        val parentPath: String get() = path.substringBeforeLast('/', "")
        fun child(name: String) = if (atServer) Place(account, name, "") else Place(account, share, if (path.isEmpty()) name else "$path/$name")
        fun sibling(name: String) = Place(account, share, if (parentPath.isEmpty()) name else "$parentPath/$name")
    }

    private fun refOf(accountId: String, share: String, path: String) = NodeRef(id, "$accountId:$share:$path")

    private fun rootOf(account: SmbAccount) = refOf(account.id, account.share, "")

    // Keys use account:share:path; only the path can contain a colon.
    private fun place(ref: NodeRef): Place {
        if (ref.provider != id) fail(StorageError.NOT_FOUND, "Not an SMB location")
        val accountId = ref.key.substringBefore(':')
        val rest = ref.key.substringAfter(':', "")
        if (':' !in rest) fail(StorageError.NOT_FOUND, "Not an SMB location")
        val share = rest.substringBefore(':')
        val path = rest.substringAfter(':').trim('/')
        if (share.isEmpty() && path.isNotEmpty()) fail(StorageError.NOT_FOUND, "Not an SMB location")
        val account = accounts().firstOrNull { it.id == accountId }
            ?: fail(StorageError.NOT_FOUND, "That server is no longer set up")
        if (!account.wholeServer && share != account.share) {
            fail(StorageError.NOT_FOUND, "That is not on ${account.name}")
        }
        return Place(account, share, path)
    }

    private fun serverEntry(account: SmbAccount) = Entry(
        ref = rootOf(account), name = account.name, directory = true, capabilities = setOf(Capability.LIST),
    )

    private fun shareEntry(account: SmbAccount, share: SmbShareInfo): Entry {
        val hidden = share.special || share.name.endsWith('$')
        return Entry(
            ref = refOf(account.id, share.name, ""), name = share.name, directory = true,
            capabilities = if (hidden) setOf(Capability.LIST, Capability.HIDDEN) else setOf(Capability.LIST),
            hidden = hidden,
        )
    }

    private fun entry(place: Place, item: SmbItem): Entry {
        val root = place.path.isEmpty()
        val capabilities = buildSet {
            if (item.directory) { add(Capability.LIST); add(Capability.CREATE); add(Capability.ATOMIC_REPLACE) }
            else { add(Capability.READ); if (!item.readOnly) add(Capability.WRITE) }
            if (!root && !item.readOnly) { add(Capability.RENAME); add(Capability.DELETE) }
            if (item.hidden) add(Capability.HIDDEN)
        }
        return Entry(
            ref = refOf(place.account.id, place.share, place.path),
            // A share root has no name of its own: use the share name, or the account label when
            // the account is scoped to that share.
            name = if (!root) item.name else if (place.account.wholeServer) place.share else place.account.name,
            directory = item.directory,
            size = if (item.directory) null else item.size,
            modified = item.modified,
            mimeType = if (item.directory) "application/octet-stream" else mimeFor(item.name),
            capabilities = capabilities,
            hidden = item.hidden || item.name.startsWith("."),
            version = versionOf(item),
        )
    }

    private fun versionOf(item: SmbItem) = "${item.size}:${item.modified}"

    private fun mimeFor(name: String): String =
        URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"

    private suspend fun <T> io(ref: NodeRef, what: String, block: (Place, SmbShare) -> T): T {
        val place = place(ref)
        return io(place, what) { block(place, it) }
    }

    private suspend fun <T> io(place: Place, what: String, block: (SmbShare) -> T): T = withContext(Dispatchers.IO) {
        if (place.atServer) fail(StorageError.UNSUPPORTED, "The server itself holds only shares; open one")
        timed(what, where(place)) {
            mapped {
                val tree = sessions.share(place.account, place.share, withPassword)
                try {
                    block(tree)
                } catch (dropped: SmbFailure) {
                    if (dropped.reason == StorageError.DISCONNECTED) sessions.forget(place.account, place.share, tree)
                    throw dropped
                }
            }
        }
    }

    private inline fun <T> timed(what: String, where: String, block: () -> T): T {
        val started = System.nanoTime()
        // Logged before the request so a stalled one is visible.
        DebugLog.d(TAG) { "$what $where…" }
        return try {
            block().also { DebugLog.d(TAG) { "$what $where · ${DebugLog.millisSince(started)} ms" } }
        } catch (failure: StorageException) {
            val serious = failure.reason in SERIOUS
            DebugLog.emit(if (serious) DebugLog.Level.WARN else DebugLog.Level.DEBUG, TAG, if (serious) failure.cause else null) {
                "$what $where failed after ${DebugLog.millisSince(started)} ms: ${failure.reason} · ${failure.message}"
            }
            throw failure
        }
    }

    private fun where(place: Place): String =
        listOf(place.account.name, place.share, place.path).filter { it.isNotEmpty() }.joinToString("/")

    /** The reader reopens itself once on a new tree after a disconnect; reads are safe to retry. */
    private fun reading(place: Place, tree: SmbShare, budget: ReadBudget?): RemoteReading {
        // Forget only the tree this reader was opened on; dropping a newer one would break
        // concurrent operations.
        var current = tree
        val openedAs = place.account.copy(password = "")
        return RemoteReading(tree.openReader(place.path), where(place), budget) {
            sessions.forget(place.account, place.share, current)
            // Reconnecting with stale settings would replace the edited account's shared session.
            val account = accounts().firstOrNull { it.id == place.account.id }
            if (account == null || account.copy(password = "") != openedAs) {
                throw SmbFailure(StorageError.DISCONNECTED, "${place.account.name} was changed or removed since the file was opened")
            }
            val next = sessions.share(account, place.share, withPassword)
            val reader = try {
                next.openReader(place.path)
            } catch (failure: SmbFailure) {
                if (failure.reason == StorageError.DISCONNECTED) sessions.forget(account, place.share, next)
                throw failure
            }
            current = next
            reader
        }
    }

    // Cancellation during open must not discard the only reference to the remote handle.
    private suspend fun <T : Closeable> holding(open: suspend () -> T): T {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val opened = withContext(NonCancellable) { open() }
        if (!caller.isActive) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { opened.close() } }
            caller.ensureActive()
        }
        return opened
    }

    private suspend fun <T> server(place: Place, what: String, block: (SmbServer) -> T): T = withContext(Dispatchers.IO) {
        timed(what, where(place)) { mapped { block(sessions.server(place.account, withPassword)) } }
    }

    private inline fun <T> mapped(block: () -> T): T = try {
        block()
    } catch (error: SmbFailure) {
        throw StorageException(error.reason, error.message ?: "The share reported an error", error)
    }

    private fun fail(reason: StorageError, message: String): Nothing = throw StorageException(reason, message)
}

private const val TAG = "Smb"

private val SERIOUS = setOf(StorageError.DISCONNECTED, StorageError.TIMEOUT, StorageError.OFFLINE, StorageError.IO, StorageError.AUTH)

/** [shares] is the disk share count, or null for an account scoped to one share. */
data class SmbProbe(val summary: SmbSessionSummary, val shares: Int?)
