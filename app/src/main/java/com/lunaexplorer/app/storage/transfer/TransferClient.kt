package com.lunaexplorer.app.storage.transfer

import com.lunaexplorer.app.storage.RemoteReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.ClosedChannelException

fun interface TransferConnector {
    fun connect(account: TransferAccount, credentials: TransferCredentials): TransferClient
}

data class TransferItem(
    val name: String,
    val directory: Boolean,
    val size: Long? = null,
    val modified: Long? = null,
    val link: Boolean = false,
    val version: String? = null,
)

// Paths are relative to the configured root; an empty path names the root itself.
interface TransferClient : Closeable {
    fun alive(): Boolean = false
    fun stat(path: String): TransferItem?
    fun list(path: String): List<TransferItem>
    fun mkdir(path: String)
    fun createFile(path: String)
    fun rename(from: String, to: String)
    fun deleteFile(path: String)
    fun deleteFolder(path: String)
    fun openReader(path: String): RemoteReader
    fun openStream(path: String): InputStream {
        val reader = openReader(path)
        return object : InputStream() {
            private var position = 0L
            private var closed = false
            private val single = ByteArray(1)

            override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (offset < 0 || length < 0 || length > bytes.size - offset) throw IndexOutOfBoundsException()
                if (closed) throw ClosedChannelException()
                if (length == 0) return 0
                return reader.read(position, bytes, offset, length).also {
                    if (it < -1 || it > length || it == 0) throw IOException("Invalid server read length")
                    if (it > 0) position += it
                }
            }

            override fun close() {
                if (closed) return
                closed = true
                reader.close()
            }
        }
    }
    fun write(path: String): OutputStream
    fun setModified(path: String, millis: Long): Boolean
}
