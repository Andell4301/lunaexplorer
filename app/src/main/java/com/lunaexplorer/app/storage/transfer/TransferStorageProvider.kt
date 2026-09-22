package com.lunaexplorer.app.storage.transfer

import com.lunaexplorer.app.storage.RemoteFailure
import com.lunaexplorer.app.storage.RemoteReader
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.util.UUID
import kotlin.coroutines.CoroutineContext

class TransferStorageProvider(
    override val id: String,
    private val accounts: () -> List<TransferAccount>,
    private val credentials: (TransferAccount) -> TransferCredentials,
    private val connector: TransferConnector,
) : StorageProvider, BudgetedReads {
    private val seekable = id == TransferProtocol.SFTP.providerId
    override val features = buildSet {
        add(Feature.STABLE_KEYS)
        add(Feature.NETWORK)
        if (seekable) { add(Feature.RANGE_READ); add(Feature.SET_TIMES) }
        else add(Feature.UNGUARDED_REPLACE)
    }
    private val connections = TransferConnections(connector, credentials, seekable)

    init { require(TransferProtocol.entries.any { it.providerId == id }) }

    fun disconnect(accountId: String) = connections.disconnect(accountId)

    suspend fun probe(account: TransferAccount, credentials: TransferCredentials): Unit = withContext(Dispatchers.IO) {
        account.validationError()?.let { fail(StorageError.INVALID_NAME, it) }
        mapped {
            connector.connect(account, credentials).use { client ->
                requireFolder(client.stat("") ?: fail(StorageError.NOT_FOUND, "Folder not found"))
            }
        }
    }

    override suspend fun roots(): List<StorageRoot> = accounts().filter { it.protocol.providerId == id }.map {
        StorageRoot(refOf(it.id, ""), it.name.ifEmpty { it.host }, description = "${it.host}:${it.port}${it.rootPath}",
            kind = RootKind.NETWORK)
    }

    override suspend fun forgetRoot(root: StorageRoot) {
        fail(StorageError.UNSUPPORTED, "Remove the server from Settings, under Network")
    }

    override suspend fun stat(ref: NodeRef): Entry {
        val place = place(ref)
        return io(place) { client -> entry(place, found(client, place.path)) }
    }

    override fun list(parent: NodeRef, complete: Boolean): Flow<List<Entry>> = flow {
        val place = place(parent)
        val listed = io(place) { client ->
            client.list(place.path).map { item -> entry(place.child(item.name), item) }
        }
        listed.chunked(256).forEach { emit(it) }
    }

    override suspend fun child(parent: NodeRef, name: String): Entry? {
        val place = place(parent)
        val at = place.child(name)
        return io(place) { client ->
            requireFolder(found(client, place.path))
            client.stat(at.path)?.let { entry(at, it) }
        }
    }

    override suspend fun parentOf(ref: NodeRef): NodeRef? {
        val place = place(ref)
        return if (place.path.isEmpty()) null else refOf(place.account.id, place.parentPath)
    }

    override suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String): Entry {
        validateName(name)
        val place = place(parent)
        val at = place.child(name)
        return io(place) { client ->
            requireFolder(found(client, place.path))
            if (client.stat(at.path) != null) fail(StorageError.CONFLICT, "$name already exists")
            if (directory) client.mkdir(at.path) else client.createFile(at.path)
            entry(at, found(client, at.path)).let {
                if (directory) it else it.copy(capabilities = it.capabilities + Capability.WRITE)
            }
        }
    }

    override suspend fun rename(ref: NodeRef, name: String): Entry {
        validateName(name)
        val from = place(ref)
        requireNonRoot(from)
        val to = Place(from.account, from.parentPath).child(name)
        return io(from) { client -> move(client, from, to) }
    }

    override suspend fun relocate(ref: NodeRef, parent: NodeRef, name: String): Entry? {
        validateName(name)
        val from = place(ref)
        val into = place(parent)
        requireNonRoot(from)
        if (from.account.id != into.account.id) return null
        val to = into.child(name)
        return io(from) { client ->
            requireFolder(found(client, into.path))
            move(client, from, to)
        }
    }

    override suspend fun delete(ref: NodeRef) {
        val place = place(ref)
        requireNonRoot(place)
        io(place) { client ->
            val item = found(client, place.path)
            if (item.directory && !item.link) {
                if (client.list(place.path).isNotEmpty()) fail(StorageError.CONFLICT, "The folder is not empty")
                client.deleteFolder(place.path)
            } else client.deleteFile(place.path)
        }
    }

    override suspend fun commit(staged: NodeRef, parent: NodeRef, name: String, replace: Entry?, onRetained: RetainedObjects?): Entry {
        validateName(name)
        val from = place(staged)
        val into = place(parent)
        if (from.path.isEmpty() || from.account.id != into.account.id || from.parentPath != into.path) {
            fail(StorageError.UNSUPPORTED, "Staged files must belong to the destination folder")
        }
        val to = into.child(name)
        return io(from) { client ->
            if (found(client, from.path).link) fail(StorageError.UNSUPPORTED, "A link cannot be published")
            if (replace != null) {
                replacing(client, from, into, to, replace, onRetained)
            } else move(client, from, to)
        }
    }

    private suspend fun replacing(
        client: TransferClient, from: Place, parent: Place, to: Place, replace: Entry, onRetained: RetainedObjects?,
    ): Entry {
        if (replace.ref != refOf(to.account.id, to.path) || from.path == to.path) {
            fail(StorageError.CONFLICT, "The item being replaced changed; refresh and try again")
        }
        if (replace.directory || replace.link) fail(StorageError.UNSUPPORTED, "Only regular files can be replaced")
        requireFile(found(client, from.path))
        requireFolder(found(client, parent.path))
        checkReplacement(client, to, replace)
        val backup = parent.child(".luna-replaced-${UUID.randomUUID()}.backup")
        val backupRef = refOf(backup.account.id, backup.path)
        val name = replace.name
        val backupName = backup.path.substringAfterLast('/')
        if (client.stat(backup.path) != null) fail(StorageError.CONFLICT, "The backup name already exists")
        onRetained?.invoke(backupRef, "Reserved backup for $name: $backupName")
        currentCoroutineContext().ensureActive()
        checkReplacement(client, to, replace)
        try {
            client.rename(to.path, backup.path)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (error is CancellationException) throw error
            throw replacementFailure(error, "$name could not be moved aside; inspect $backupName")
        }
        var attempted = false
        try {
            checkReplacement(client, backup, replace)
            currentCoroutineContext().ensureActive()
            if (client.stat(to.path) != null) fail(StorageError.CONFLICT, "$name already exists")
            attempted = true
            client.rename(from.path, to.path)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (error is CancellationException) throw error
            // A lost reply can mean publication succeeded; never restore over an uncertain result.
            val rejected = !attempted || (error as? StorageException)?.reason in setOf(
                StorageError.CONFLICT, StorageError.PERMISSION, StorageError.UNSUPPORTED,
                StorageError.NOT_FOUND, StorageError.INVALID_NAME, StorageError.NO_SPACE,
            )
            val restored = if (rejected) restore(client, backup, to) else false
            throw replacementFailure(error, "Overwriting $name failed; " +
                if (restored) "previous file restored" else "inspect $name and $backupName")
        }
        val published = try {
            entry(to, found(client, to.path))
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (error is CancellationException) throw error
            throw replacementFailure(error, "$name was published; inspect $backupName")
        }
        currentCoroutineContext().ensureActive()
        try {
            checkReplacement(client, backup, replace)
            client.deleteFile(backup.path)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (error is CancellationException) throw error
            val message = "$name was published; backup cleanup failed: $backupName"
            if (onRetained == null) throw replacementFailure(error, message)
            onRetained(backupRef, message)
            return published
        }
        onRetained?.invoke(backupRef, "Previous $name backup removed: $backupName")
        return published
    }

    private fun checkReplacement(client: TransferClient, place: Place, expected: Entry) {
        val current = client.stat(place.path)
            ?: fail(StorageError.CONFLICT, "${expected.name} changed since it was seen; refresh and try again")
        requireFile(current)
        if (versionOf(current) != expected.version || current.size != expected.size || current.modified != expected.modified) {
            fail(StorageError.CONFLICT, "${expected.name} changed since it was seen; refresh and try again")
        }
    }

    private suspend fun restore(client: TransferClient, backup: Place, target: Place): Boolean = try {
        currentCoroutineContext().ensureActive()
        if (client.stat(target.path) != null) false else {
            client.rename(backup.path, target.path)
            true
        }
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        if (error is CancellationException) throw error
        false
    }

    private fun replacementFailure(error: Exception, message: String) =
        StorageException((error as? StorageException)?.reason ?: StorageError.IO, message, error)

    override suspend fun setModified(ref: NodeRef, epochMillis: Long): Long {
        if (!seekable) fail(StorageError.UNSUPPORTED, "Changing times is not supported")
        val place = place(ref)
        return io(place) { client ->
            if (found(client, place.path).link) fail(StorageError.UNSUPPORTED, "A link's time cannot be changed")
            if (!client.setModified(place.path, epochMillis)) fail(StorageError.UNSUPPORTED, "Changing times is not supported")
            found(client, place.path).modified ?: epochMillis
        }
    }

    override suspend fun openRead(ref: NodeRef): InputStream {
        val place = place(ref)
        return holding { caller ->
            val client = connect(place.account, caller)
            try {
                requireFile(found(client, place.path))
                val stream = client.openStream(place.path)
                object : InputStream() {
                    private var closed = false
                    private fun <T> reading(action: () -> T): T = try {
                        if (closed) throw ClosedChannelException()
                        action()
                    } catch (error: Throwable) {
                        client.discard()
                        if (error is RemoteFailure) throw IOException(error.message, error)
                        throw error
                    }
                    override fun read() = reading { stream.read() }
                    override fun read(bytes: ByteArray, offset: Int, length: Int) = reading { stream.read(bytes, offset, length) }
                    override fun close() {
                        if (closed) return
                        closed = true
                        client.closeAfter(stream)
                    }
                }
            } catch (error: Throwable) {
                client.discard()
                throw error
            }
        }
    }
    override suspend fun openRead(ref: NodeRef, budget: ReadBudget): InputStream = openReading(ref, budget, ::TransferInputStream)
    override suspend fun openChannel(ref: NodeRef): SeekableByteChannel? = channel(ref, null)
    override suspend fun openChannel(ref: NodeRef, budget: ReadBudget): SeekableByteChannel? = channel(ref, budget)

    private suspend fun channel(ref: NodeRef, budget: ReadBudget?): SeekableByteChannel? {
        if (!seekable) return null
        return openReading(ref, budget) { reading, size ->
            TransferChannel(reading, size ?: fail(StorageError.UNSUPPORTED, "File size is unavailable"))
        }
    }

    private suspend fun <T : Closeable> openReading(ref: NodeRef, budget: ReadBudget?, wrap: (TransferReading, Long?) -> T): T {
        val place = place(ref)
        return holding { caller ->
            budget?.checkCancelled()
            val client = connect(place.account, caller)
            try {
                val item = found(client, place.path)
                requireFile(item)
                val reading = TransferReading(client.openReader(place.path), client, budget)
                try { wrap(reading, if (seekable) item.size else null) } catch (error: Throwable) {
                    client.discard()
                    closeQuietly(reading)
                    throw error
                }
            } catch (error: Throwable) {
                client.discard()
                throw error
            }
        }
    }

    override suspend fun openWrite(ref: NodeRef): OutputStream {
        val place = place(ref)
        return holding { caller ->
            val client = connect(place.account, caller)
            try {
                val item = found(client, place.path)
                requireFile(item)
                if (item.size != 0L) fail(StorageError.CONFLICT, "Refusing to truncate a nonempty file")
                val stream = client.write(place.path)
                object : OutputStream() {
                    private var closed = false
                    private fun writing(action: () -> Unit) = try {
                        if (closed) throw ClosedChannelException()
                        action()
                    } catch (error: Throwable) {
                        client.discard()
                        throw error
                    }
                    override fun write(value: Int) = writing { stream.write(value) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) = writing { stream.write(bytes, offset, length) }
                    override fun flush() = writing { stream.flush() }
                    override fun close() {
                        if (closed) return
                        closed = true
                        client.closeAfter(stream)
                    }
                }
            } catch (error: Throwable) {
                client.discard()
                throw error
            }
        }
    }

    override suspend fun isDescendant(candidate: NodeRef, ancestor: NodeRef): Boolean {
        val below = try { place(candidate) } catch (_: StorageException) { return false }
        val above = try { place(ancestor) } catch (_: StorageException) { return false }
        return below.account.id == above.account.id &&
            (above.path.isEmpty() || below.path == above.path || below.path.startsWith(above.path + "/"))
    }

    private data class Place(val account: TransferAccount, val path: String) {
        val parentPath: String get() = path.substringBeforeLast('/', "")
        fun child(name: String): Place {
            if (!validName(name)) fail(StorageError.INVALID_NAME, "Invalid remote name")
            return Place(account, if (path.isEmpty()) name else "$path/$name")
        }
    }

    private fun refOf(accountId: String, path: String) = NodeRef(id, "$accountId:$path")

    private fun place(ref: NodeRef): Place {
        if (ref.provider != id || ':' !in ref.key) fail(StorageError.NOT_FOUND, "Not a server location")
        val accountId = ref.key.substringBefore(':')
        val path = ref.key.substringAfter(':')
        if (path.isNotEmpty() && path.split('/').any { !validName(it) }) {
            fail(StorageError.NOT_FOUND, "Not a server location")
        }
        val account = accounts().firstOrNull { it.id == accountId && it.protocol.providerId == id }
            ?: fail(StorageError.NOT_FOUND, "That server is no longer set up")
        return Place(account, path)
    }

    private fun entry(place: Place, item: TransferItem): Entry {
        val root = place.path.isEmpty()
        return Entry(
            ref = refOf(place.account.id, place.path),
            name = if (root) place.account.name.ifEmpty { place.account.host } else place.path.substringAfterLast('/'),
            directory = item.directory && !item.link,
            size = if (item.directory || item.link) null else item.size,
            modified = item.modified,
            mimeType = if (item.directory || item.link) "application/octet-stream"
                else URLConnection.guessContentTypeFromName(item.name) ?: "application/octet-stream",
            capabilities = buildSet {
                if (!item.link) {
                    if (item.directory) { add(Capability.LIST); add(Capability.CREATE); add(Capability.REPLACE) }
                    else add(Capability.READ)
                }
                if (!root) { add(Capability.RENAME); add(Capability.DELETE) }
            },
            link = item.link,
            version = versionOf(item),
        )
    }

    private fun move(client: TransferClient, from: Place, to: Place): Entry {
        if (client.stat(to.path) != null) fail(StorageError.CONFLICT, "${to.path.substringAfterLast('/')} already exists")
        client.rename(from.path, to.path)
        return entry(to, found(client, to.path))
    }

    private fun found(client: TransferClient, path: String): TransferItem =
        client.stat(path) ?: fail(StorageError.NOT_FOUND, "Not found on the server")

    private fun versionOf(item: TransferItem): String? = item.version ?: item.modified?.let { "${item.size}:$it" }
    private fun requireNonRoot(place: Place) { if (place.path.isEmpty()) fail(StorageError.UNSUPPORTED, "A storage root cannot be changed") }

    private fun connect(account: TransferAccount, caller: CoroutineContext): TransferConnections.Lease {
        account.validationError()?.let { fail(StorageError.INVALID_NAME, it) }
        return connections.acquire(account, caller)
    }

    private suspend fun <T> io(place: Place, action: suspend (TransferClient) -> T): T = withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext()
        mapped {
            val client = connect(place.account, caller)
            try {
                action(client).also { caller.ensureActive() }
            } catch (error: Throwable) {
                client.discard()
                throw error
            } finally { client.close() }
        }
    }

    private suspend fun <T : Closeable> holding(open: (CoroutineContext) -> T): T {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val opened = try {
            withContext(NonCancellable) { withContext(Dispatchers.IO) { mapped { open(caller) } } }
        } catch (error: Exception) {
            caller.ensureActive()
            throw error
        }
        if (!caller.isActive) {
            withContext(NonCancellable + Dispatchers.IO) { closeQuietly(opened) }
            caller.ensureActive()
        }
        return opened
    }

    private suspend fun <T> mapped(action: suspend () -> T): T = try {
        action()
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        if (error is RemoteFailure) throw StorageException(error.reason, error.message ?: "Server error", error)
        throw error
    }

    private companion object {
        fun validName(name: String) = name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name
        fun fail(reason: StorageError, message: String): Nothing = throw StorageException(reason, message)
        fun requireFolder(item: TransferItem) {
            if (!item.directory || item.link) fail(StorageError.UNSUPPORTED, "Not a folder")
        }
        fun requireFile(item: TransferItem) {
            if (item.directory || item.link) fail(StorageError.UNSUPPORTED, "Not a regular file")
        }
    }
}

private class TransferReading(
    private val reader: RemoteReader,
    private val session: TransferConnections.Lease,
    private val budget: ReadBudget?,
) : Closeable {
    private var closed = false

    fun check() {
        try {
            if (closed) throw ClosedChannelException()
            budget?.checkCancelled()
        } catch (error: Throwable) {
            session.discard()
            throw error
        }
    }

    fun read(position: Long, bytes: ByteArray, offset: Int, length: Int): Int {
        check()
        return try {
            val count = budget?.read(length) { reader.read(position, bytes, offset, it) }
                ?: reader.read(position, bytes, offset, length)
            if (count < -1 || count > length || (count == 0 && length > 0)) throw IOException("Invalid server read length")
            count
        } catch (error: Throwable) {
            session.discard()
            if (error is RemoteFailure) throw IOException(error.message, error)
            throw error
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        session.closeAfter(reader)
    }
}

private class TransferInputStream(private val reading: TransferReading, private val size: Long?) : InputStream() {
    private var position = 0L
    private var ended = false
    private val single = ByteArray(1)

    override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || length > bytes.size - offset) throw IndexOutOfBoundsException()
        reading.check()
        if (length == 0) return 0
        if (ended || (size != null && position >= size)) return -1
        val wanted = if (size == null) length else minOf(length.toLong(), size - position).toInt()
        val count = reading.read(position, bytes, offset, wanted)
        if (count > 0) position += count
        if (count < 0) ended = true
        return count
    }

    override fun close() = reading.close()
}

private class TransferChannel(private val reading: TransferReading, private val length: Long) : SeekableByteChannel {
    private var at = 0L
    private var open = true

    override fun read(dst: ByteBuffer): Int {
        checkOpen()
        reading.check()
        if (!dst.hasRemaining()) return 0
        if (at >= length) return -1
        val wanted = minOf(dst.remaining().toLong(), length - at, 256 * 1024L).toInt()
        val count = if (dst.hasArray()) {
            reading.read(at, dst.array(), dst.arrayOffset() + dst.position(), wanted).also {
                if (it > 0) dst.position(dst.position() + it)
            }
        } else {
            val bytes = ByteArray(wanted)
            reading.read(at, bytes, 0, wanted).also { if (it > 0) dst.put(bytes, 0, it) }
        }
        if (count > 0) at += count
        return count
    }

    private fun checkOpen() { if (!open) throw ClosedChannelException() }
    override fun position(): Long { checkOpen(); return at }
    override fun position(newPosition: Long): SeekableByteChannel = apply {
        checkOpen()
        require(newPosition >= 0)
        at = newPosition
    }
    override fun size(): Long { checkOpen(); return length }
    override fun isOpen(): Boolean = open
    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    override fun close() {
        if (!open) return
        open = false
        reading.close()
    }
}

private fun closeQuietly(resource: Closeable) {
    try { resource.close() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
}
