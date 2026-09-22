package com.lunaexplorer.app.storage.transfer

import com.lunaexplorer.app.storage.RemoteReader
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class FakeTransferConnector : TransferConnector {
    private data class Node(
        val directory: Boolean,
        var bytes: ByteArray = byteArrayOf(),
        var modified: Long = 1,
        val link: Boolean = false,
    )
    private val nodes = linkedMapOf("" to Node(directory = true))
    val sessionsOpened = AtomicInteger()
    val sessionsClosed = AtomicInteger()
    val handlesOpened = AtomicInteger()
    val handlesClosed = AtomicInteger()
    var bytesRead = 0L
    var renameCalls = 0
    var creates = 0
    var failRead = false
    var createRacer = false
    var renameRacer = false
    var renameFailsAfter = false
    var renameFailsAfterCall: Int? = null
    var beforeRename: ((String, String) -> Unit)? = null
    var beforeStat: ((String) -> Unit)? = null
    var beforeDelete: ((String) -> Unit)? = null
    val deletedPaths = mutableListOf<String>()
    var extraListed: TransferItem? = null
    var openGate: CountDownLatch? = null
    var openEntered: CountDownLatch? = null
    var connectGate: CountDownLatch? = null
    var connectEntered: CountDownLatch? = null
    var sessionClosed: CountDownLatch? = null
    var lastAccount: TransferAccount? = null
    var writeOpens = 0
    var corruptWrites = false
    var failWrite = false
    var failWriteClose = false
    private val serverEpoch = AtomicInteger()
    val readPaths = mutableListOf<String>()

    fun put(path: String, text: String) { nodes[path] = Node(directory = false, bytes = text.toByteArray()) }
    fun folder(path: String) { nodes[path] = Node(directory = true) }
    fun link(path: String) { nodes[path] = Node(directory = true, link = true) }
    fun text(path: String): String? = nodes[path]?.bytes?.decodeToString()
    fun dropSessions() { serverEpoch.incrementAndGet() }
    private fun missing(): Nothing = throw StorageException(StorageError.NOT_FOUND, "Missing")
    private fun conflict(): Nothing = throw StorageException(StorageError.CONFLICT, "Exists")
    private fun node(path: String): Node = nodes[path] ?: missing()
    private fun parent(path: String): String = path.substringBeforeLast('/', "")
    private fun item(path: String, node: Node) = TransferItem(
        path.substringAfterLast('/'), node.directory, node.bytes.size.toLong(), node.modified, node.link,
        "${node.bytes.size}:${node.modified}",
    )

    override fun connect(account: TransferAccount, credentials: TransferCredentials): TransferClient {
        sessionsOpened.incrementAndGet()
        lastAccount = account
        connectEntered?.countDown()
        check(connectGate?.await(10, TimeUnit.SECONDS) != false)
        val epoch = serverEpoch.get()
        return object : TransferClient {
            @Volatile private var closed = false
            override fun alive() = !closed && epoch == serverEpoch.get() && account.protocol == TransferProtocol.SFTP
            private fun up() { if (closed || epoch != serverEpoch.get()) throw StorageException(StorageError.DISCONNECTED, "Closed") }
            private fun checked(path: String) {
                up()
                val parts = path.split('/')
                for (count in 1 until parts.size) {
                    if (nodes[parts.take(count).joinToString("/")]?.link == true) {
                        throw StorageException(StorageError.UNSUPPORTED, "Link ancestor")
                    }
                }
            }
            override fun stat(path: String): TransferItem? {
                checked(path)
                beforeStat?.invoke(path)
                return nodes[path]?.let { item(path, it) }
            }
            override fun list(path: String): List<TransferItem> {
                checked(path)
                val folder = node(path)
                if (!folder.directory || folder.link) throw StorageException(StorageError.UNSUPPORTED, "Not a folder")
                return nodes.filterKeys { it.isNotEmpty() && parent(it) == path }.map { (name, entry) -> item(name, entry) } + listOfNotNull(extraListed)
            }
            override fun mkdir(path: String) {
                checked(path)
                if (path in nodes) conflict()
                node(parent(path))
                folder(path)
            }
            override fun createFile(path: String) {
                checked(path)
                creates++
                if (createRacer) { createRacer = false; put(path, "racer") }
                if (path in nodes) conflict()
                node(parent(path))
                put(path, "")
            }
            override fun rename(from: String, to: String) {
                checked(from); checked(to)
                renameCalls++
                beforeRename?.invoke(from, to)
                if (renameRacer) { renameRacer = false; put(to, "racer") }
                if (to in nodes) conflict()
                val moving = node(from)
                nodes.remove(from)
                nodes[to] = moving
                nodes.keys.filter { it.startsWith("$from/") }.forEach { child ->
                    nodes[to + child.removePrefix(from)] = nodes.remove(child)!!
                }
                if (renameFailsAfter || renameCalls == renameFailsAfterCall) throw StorageException(StorageError.DISCONNECTED, "Reply lost")
            }
            override fun deleteFile(path: String) {
                checked(path)
                beforeDelete?.invoke(path)
                node(path)
                nodes.remove(path)
                deletedPaths += path
            }
            override fun deleteFolder(path: String) {
                if (list(path).isNotEmpty()) conflict()
                nodes.remove(path)
            }
            override fun openReader(path: String): RemoteReader {
                checked(path)
                readPaths += path
                val bytes = node(path).bytes
                openEntered?.countDown()
                check(openGate?.await(10, TimeUnit.SECONDS) != false)
                handlesOpened.incrementAndGet()
                return object : RemoteReader {
                    private var handleClosed = false
                    private var position = 0L
                    override fun read(offset: Long, into: ByteArray, at: Int, length: Int): Int {
                        up()
                        check(!handleClosed)
                        if (failRead) { failRead = false; throw IOException("Read lost") }
                        if (account.protocol == TransferProtocol.FTP) check(offset == position)
                        if (offset >= bytes.size) return -1
                        val count = minOf(length, bytes.size - offset.toInt())
                        bytes.copyInto(into, at, offset.toInt(), offset.toInt() + count)
                        bytesRead += count
                        position += count
                        return count
                    }
                    override fun close() {
                        if (!handleClosed) { handleClosed = true; handlesClosed.incrementAndGet() }
                    }
                }
            }
            override fun write(path: String): OutputStream {
                checked(path)
                val target = node(path)
                if (target.bytes.isNotEmpty()) conflict()
                writeOpens++
                openEntered?.countDown()
                check(openGate?.await(10, TimeUnit.SECONDS) != false)
                handlesOpened.incrementAndGet()
                return object : ByteArrayOutputStream() {
                    private var handleClosed = false
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        up()
                        if (failWrite) throw IOException("Write lost")
                        super.write(bytes, offset, length)
                    }
                    override fun close() {
                        if (handleClosed) return
                        handleClosed = true
                        target.bytes = toByteArray().also { if (corruptWrites && it.isNotEmpty()) it[0] = (it[0].toInt() xor 1).toByte() }
                        target.modified++
                        handlesClosed.incrementAndGet()
                        if (failWriteClose) throw IOException("Write reply lost")
                    }
                }
            }
            override fun setModified(path: String, millis: Long): Boolean {
                checked(path)
                node(path).modified = millis
                return true
            }
            override fun close() {
                if (!closed) { closed = true; sessionsClosed.incrementAndGet(); sessionClosed?.countDown() }
            }
        }
    }
}
