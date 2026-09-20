package com.lunaexplorer.app.storage.shizuku

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep
import com.lunaexplorer.app.storage.LocalStorageProvider
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

// Shizuku constructs this service by reflection in a separate shell process; R8 must keep its name and constructors.
@Keep
class FileHelperService() : IFileHelper.Stub() {
    /** Shizuku 13 offers a Context; nothing here needs one. */
    @Keep constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    private val files = LocalStorageProvider()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listings = ConcurrentHashMap<Long, Listing>()
    private val nextListing = AtomicLong()
    private val handles = ConcurrentHashMap<Long, FileChannel>()
    private val nextHandle = AtomicLong()

    private class Listing(val batches: Channel<List<Entry>>, val job: Job)

    override fun destroy() {
        scope.cancel()
        exitProcess(0)
    }

    override fun uid(): Int = Process.myUid()

    override fun protocol(): Int = Wire.PROTOCOL

    // The groups are what let the shell user into Android/data (ext_data_rw) and Android/obb (ext_obb_rw).
    override fun describe(): String = buildString {
        append("uid ").append(Process.myUid())
        runCatching { File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith("Groups:") } } }
            .getOrNull()?.let { append(", groups ").append(it.removePrefix("Groups:").trim()) }
        // The kernel ends the context with a NUL.
        runCatching { File("/proc/self/attr/current").readText().trim('\u0000', '\n', ' ') }.getOrNull()
            ?.takeIf { it.isNotEmpty() }?.let { append(", ").append(it) }
    }

    override fun setRoots(roots: String) = answering {
        files.updateRoots(Wire.json.decodeFromString(ListSerializer(Wire.Root.serializer()), roots).map(Wire::root))
    }

    override fun stat(key: String): String = answering { encoded(files.stat(ref(key))) }

    override fun list(key: String, complete: Boolean): Long = answering {
        val batches = Channel<List<Entry>>(1)
        val job = scope.launch {
            try {
                files.list(ref(key), complete).collect { batches.send(it) }
                batches.close()
            } catch (error: Exception) {
                batches.close(error)
            }
        }
        nextListing.incrementAndGet().also { listings[it] = Listing(batches, job) }
    }

    override fun more(listing: Long): String? = answering {
        val open = listings[listing] ?: throw StorageException(StorageError.IO, "That listing is no longer open")
        val batch = open.batches.receiveCatching()
        batch.exceptionOrNull()?.let { listings.remove(listing); throw it }
        batch.getOrNull()?.let { Wire.json.encodeToString(ListSerializer(Wire.Item.serializer()), it.map(Wire::item)) }
            ?: run { listings.remove(listing); null }
    }

    override fun done(listing: Long) {
        listings.remove(listing)?.job?.cancel()
    }

    override fun create(parent: String, name: String, directory: Boolean, mimeType: String): String =
        answering { encoded(files.create(ref(parent), name, directory, mimeType)) }

    override fun rename(key: String, name: String): String = answering { encoded(files.rename(ref(key), name)) }

    override fun delete(key: String) = answering { files.delete(ref(key)) }

    override fun relocate(key: String, parent: String, name: String): String? =
        answering { files.relocate(ref(key), ref(parent), name)?.let(::encoded) }

    override fun commit(staged: String, parent: String, name: String, replace: String?): String = answering {
        val retained = ArrayList<Pair<String, String>>()
        val target = replace?.let { Wire.entry(files.id, Wire.json.decodeFromString(Wire.Item.serializer(), it)) }
        val entry = files.commit(ref(staged), ref(parent), name, target) { left, message -> retained += left.key to message }
        Wire.json.encodeToString(Wire.Committed.serializer(), Wire.Committed(Wire.item(entry), retained))
    }

    override fun setModified(key: String, epochMillis: Long): Long = answering { files.setModified(ref(key), epochMillis) }

    override fun availableBytes(key: String): Long = answering { files.availableBytes(ref(key)) ?: -1L }

    override fun openRead(key: String): ParcelFileDescriptor = answering {
        val entry = files.stat(ref(key))
        if (entry.directory) throw StorageException(StorageError.UNSUPPORTED, "${entry.name} is a folder")
        ParcelFileDescriptor.open(existing(key), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openWrite(key: String): ParcelFileDescriptor = answering {
        files.stat(ref(key))
        val file = existing(key)
        // Engine contract: only a newly created staging object is passed here.
        if (file.length() != 0L) throw StorageException(StorageError.CONFLICT, "Refusing to truncate a nonempty file")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY)
    }

    override fun open(key: String, writing: Boolean): Long = answering {
        val entry = files.stat(ref(key))
        if (entry.directory) throw StorageException(StorageError.UNSUPPORTED, "${entry.name} is a folder")
        val file = existing(key)
        if (writing && file.length() != 0L) throw StorageException(StorageError.CONFLICT, "Refusing to truncate a nonempty file")
        val channel = FileChannel.open(file.toPath(), if (writing) StandardOpenOption.WRITE else StandardOpenOption.READ)
        nextHandle.incrementAndGet().also { handles[it] = channel }
    }

    override fun readAt(handle: Long, offset: Long, length: Int): ByteArray = answering {
        val buffer = ByteBuffer.allocate(length.coerceIn(0, Wire.BLOCK))
        val channel = opened(handle)
        while (buffer.hasRemaining()) { if (channel.read(buffer, offset + buffer.position()) < 0) break }
        buffer.array().copyOf(buffer.position())
    }

    override fun append(handle: Long, bytes: ByteArray) = answering {
        val buffer = ByteBuffer.wrap(bytes)
        val channel = opened(handle)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    override fun close(handle: Long, sync: Boolean) = answering {
        val channel = handles.remove(handle) ?: return@answering
        channel.use { if (sync) it.force(true) }
    }

    private fun opened(handle: Long): FileChannel =
        handles[handle] ?: throw StorageException(StorageError.IO, "That file is no longer open")

    /** The checked path: inside its root, and no link where the root does not follow them. */
    private fun existing(key: String): File =
        File(files.absolutePath(ref(key)) ?: throw StorageException(StorageError.NOT_FOUND, "Not found"))

    private fun ref(key: String) = NodeRef(files.id, key)

    private fun encoded(entry: Entry): String = Wire.json.encodeToString(Wire.Item.serializer(), Wire.item(entry))

    private fun <T> answering(block: suspend () -> T): T = try {
        runBlocking { block() }
    } catch (error: StorageException) {
        throw Wire.failure(error)
    } catch (error: Exception) {
        throw Wire.failure(StorageException(StorageError.IO, error.message ?: error.javaClass.simpleName, error))
    }
}
