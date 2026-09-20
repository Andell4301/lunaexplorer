package com.lunaexplorer.app.storage.smb

import com.lunaexplorer.core.StorageError
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FakeSmbShare : SmbShare {
    private class Node(val directory: Boolean, var bytes: ByteArray = ByteArray(0), var modified: Long = 1L)
    private val nodes = HashMap<String, Node>().apply { put("", Node(directory = true)) }
    var connected = true
    /** Thrown by the next request; the tree still reports connected. */
    var failNext: SmbFailure? = null
    var failNextRead: SmbFailure? = null
    var free: Long? = 1L shl 40
    var readersOpened = 0
    var readersOpen = 0
    var reads = 0
    var bytesServed = 0L
    /** A read (or an open) counts down its `Entered` latch, then waits on its gate. */
    @Volatile var readGate: CountDownLatch? = null
    @Volatile var readEntered: CountDownLatch? = null
    @Volatile var openGate: CountDownLatch? = null
    @Volatile var openEntered: CountDownLatch? = null

    private fun node(path: String) = nodes[path.trim('/')]
    private fun parentOf(path: String) = path.trim('/').substringBeforeLast('/', "")
    private fun nameOf(path: String) = path.trim('/').substringAfterLast('/')
    private fun require(path: String): Node = node(path) ?: throw SmbFailure(StorageError.NOT_FOUND, "Not found on the server")
    private fun requireUp() {
        if (!connected) throw SmbFailure(StorageError.DISCONNECTED, "The connection to the server was lost")
        failNext?.let { failNext = null; throw it }
    }
    private fun item(path: String, node: Node) = SmbItem(nameOf(path), node.directory, node.bytes.size.toLong(),
        node.modified, readOnly = false, hidden = false)

    override fun list(path: String): List<SmbItem> {
        requireUp()
        val folder = require(path)
        if (!folder.directory) throw SmbFailure(StorageError.UNSUPPORTED, "Not a folder")
        val prefix = path.trim('/')
        return nodes.filterKeys { it.isNotEmpty() && parentOf(it) == prefix }.map { (p, n) -> item(p, n) }
    }

    override fun stat(path: String): SmbItem? { requireUp(); return node(path)?.let { item(path, it) } }

    override fun mkdir(path: String) {
        requireUp()
        if (node(path) != null) throw SmbFailure(StorageError.CONFLICT, "Something of that name is already there")
        require(parentOf(path))
        nodes[path.trim('/')] = Node(directory = true)
    }

    override fun createFile(path: String) {
        requireUp()
        if (node(path) != null) throw SmbFailure(StorageError.CONFLICT, "Something of that name is already there")
        require(parentOf(path))
        nodes[path.trim('/')] = Node(directory = false)
    }

    override fun rename(path: String, to: String, replace: Boolean) {
        requireUp()
        val moving = require(path)
        val from = path.trim('/'); val target = to.trim('/')
        if (node(to) != null) {
            if (!replace) throw SmbFailure(StorageError.CONFLICT, "Something of that name is already there")
            nodes.remove(target)
        }
        nodes.remove(from)
        nodes[target] = moving
        nodes.keys.filter { it.startsWith("$from/") }.forEach { key ->
            nodes[target + key.removePrefix(from)] = nodes.remove(key)!!
        }
    }

    override fun deleteFile(path: String) { requireUp(); require(path); nodes.remove(path.trim('/')) }

    override fun deleteFolder(path: String) {
        requireUp()
        require(path)
        if (list(path).isNotEmpty()) throw SmbFailure(StorageError.CONFLICT, "The folder is not empty")
        nodes.remove(path.trim('/'))
    }

    override fun openReader(path: String): SmbReader {
        requireUp()
        val target = require(path)
        if (target.directory) throw SmbFailure(StorageError.UNSUPPORTED, "Not a file")
        openEntered?.countDown()
        openGate?.await(10, TimeUnit.SECONDS)
        readersOpened++; readersOpen++
        return object : SmbReader {
            private var closed = false
            override fun read(offset: Long, into: ByteArray, at: Int, length: Int): Int {
                readEntered?.countDown()
                readGate?.await(10, TimeUnit.SECONDS)
                requireUp()
                failNextRead?.let { failNextRead = null; throw it }
                reads++
                val bytes = target.bytes
                if (offset >= bytes.size) return -1
                val n = minOf(length, (bytes.size - offset).toInt())
                System.arraycopy(bytes, offset.toInt(), into, at, n)
                bytesServed += n
                return n
            }
            override fun close() { if (!closed) { closed = true; readersOpen-- } }
        }
    }

    fun put(path: String, bytes: ByteArray) {
        if (node(path) == null) createFile(path)
        write(path).use { it.write(bytes) }
    }

    override fun write(path: String): OutputStream {
        requireUp()
        val target = require(path)
        return object : ByteArrayOutputStream() {
            override fun close() { target.bytes = toByteArray(); target.modified++ }
        }
    }

    override fun setModified(path: String, epochMillis: Long) { requireUp(); require(path).modified = epochMillis }
    override fun freeBytes(): Long? = free
    override fun alive(): Boolean = connected
    override fun close() { connected = false }
}

/** Independent connection handle to a [FakeSmbShare]. */
class FakeTree(val store: FakeSmbShare) : SmbShare {
    @Volatile var closed = false
        private set

    private fun <T> up(block: () -> T): T {
        if (closed) throw SmbFailure(StorageError.DISCONNECTED, "The connection to the share was closed")
        return block()
    }

    override fun list(path: String): List<SmbItem> = up { store.list(path) }
    override fun stat(path: String): SmbItem? = up { store.stat(path) }
    override fun mkdir(path: String) = up { store.mkdir(path) }
    override fun createFile(path: String) = up { store.createFile(path) }
    override fun rename(path: String, to: String, replace: Boolean) = up { store.rename(path, to, replace) }
    override fun deleteFile(path: String) = up { store.deleteFile(path) }
    override fun deleteFolder(path: String) = up { store.deleteFolder(path) }
    override fun write(path: String): OutputStream = up { store.write(path) }
    override fun setModified(path: String, epochMillis: Long) = up { store.setModified(path, epochMillis) }
    override fun freeBytes(): Long? = up { store.freeBytes() }

    override fun openReader(path: String): SmbReader = up {
        val inner = store.openReader(path)
        object : SmbReader {
            override fun read(offset: Long, into: ByteArray, at: Int, length: Int): Int = up { inner.read(offset, into, at, length) }
            override fun close() = inner.close()
        }
    }

    override fun alive(): Boolean = !closed && store.connected
    override fun close() { closed = true }
}

class FakeSmbServer : SmbServer {
    private class Held(val info: SmbShareInfo, val share: FakeSmbShare) {
        var openedIn = -1
        var last: FakeTree? = null
    }
    private val held = LinkedHashMap<String, Held>()
    var connected = true
    var refuseListing: SmbFailure? = null
    /** SMBJ keeps returning its cached tree after that tree was closed, until the session is replaced. */
    var keepsClosedTrees = false
    private var session = 0

    /** A new session starts with an empty tree cache. */
    fun signedIn() { session++; connected = true }
    var listings = 0
    var opened = 0
    override val summary = SmbSessionSummary(dialect = "3.1.1", encrypted = true, signed = true)

    fun add(name: String, disk: Boolean = true, special: Boolean = false): FakeSmbShare =
        FakeSmbShare().also { held[name.lowercase()] = Held(SmbShareInfo(name, disk, special), it) }

    fun share(name: String): FakeSmbShare = held.getValue(name.lowercase()).share

    private fun requireUp() { if (!connected) throw SmbFailure(StorageError.DISCONNECTED, "The connection to the server was lost") }

    override fun shares(): List<SmbShareInfo> {
        requireUp(); listings++
        refuseListing?.let { throw it }
        return held.values.map { it.info }
    }

    override fun open(share: String): SmbShare {
        requireUp(); opened++
        val found = held[share.lowercase()] ?: throw SmbFailure(StorageError.NOT_FOUND, "The server has no share by that name")
        if (!found.info.disk) throw SmbFailure(StorageError.UNSUPPORTED, "$share is not a folder share")
        val last = found.last
        if (keepsClosedTrees && last != null && last.closed && found.openedIn == session) {
            throw SmbFailure(StorageError.DISCONNECTED, "The session can no longer open $share")
        }
        found.openedIn = session
        found.share.connected = true
        return FakeTree(found.share).also { found.last = it }
    }

    override fun alive(): Boolean = connected
    override fun close() { connected = false }
}

class FakeSmbConnector(val server: FakeSmbServer = FakeSmbServer().apply { add("media") }) : SmbConnector {
    var connections = 0
    var refuse: SmbFailure? = null
    var lastAccount: SmbAccount? = null
    val share: FakeSmbShare get() = server.share("media")
    override fun connect(account: SmbAccount): SmbServer {
        connections++
        lastAccount = account
        refuse?.let { throw it }
        server.signedIn()
        return server
    }
}
