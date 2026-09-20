package com.lunaexplorer.app.storage.shizuku

import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.lunaexplorer.app.debug.DebugLog
import com.lunaexplorer.app.storage.LocalRoot
import com.lunaexplorer.app.storage.RemoteChannel
import com.lunaexplorer.app.storage.RemoteFailure
import com.lunaexplorer.app.storage.RemoteInputStream
import com.lunaexplorer.app.storage.RemoteReader
import com.lunaexplorer.app.storage.RemoteReading
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.RetainedObjects
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel

// Both processes use the same local roots so references keep their meaning.
internal class HelperFiles(private val id: String, private val helper: IFileHelper) {
    @Volatile private var descriptors = true

    suspend fun uid(): Int = asking { helper.uid() }

    suspend fun protocol(): Int = asking { helper.protocol() }

    suspend fun setRoots(roots: List<LocalRoot>) = asking {
        helper.setRoots(Wire.json.encodeToString(ListSerializer(Wire.Root.serializer()), roots.map(Wire::root)))
    }

    suspend fun stat(ref: NodeRef): Entry = asking { decoded(helper.stat(ref.key)) }

    fun list(parent: NodeRef, complete: Boolean): Flow<List<Entry>> = flow {
        val listing = holding({ helper.list(parent.key, complete) }) { helper.done(it) }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val batch = asking { helper.more(listing) } ?: break
                emit(Wire.json.decodeFromString(ListSerializer(Wire.Item.serializer()), batch).map { Wire.entry(id, it) })
            }
        } finally {
            // Also after the last batch, which the helper has closed by then; this is for a collector that left early.
            runCatching { helper.done(listing) }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String): Entry =
        asking { decoded(helper.create(parent.key, name, directory, mimeType)) }

    suspend fun rename(ref: NodeRef, name: String): Entry = asking { decoded(helper.rename(ref.key, name)) }

    suspend fun delete(ref: NodeRef) = asking { helper.delete(ref.key) }

    suspend fun relocate(ref: NodeRef, parent: NodeRef, name: String): Entry? =
        asking { helper.relocate(ref.key, parent.key, name)?.let(::decoded) }

    suspend fun commit(staged: NodeRef, parent: NodeRef, name: String, replace: Entry?, onRetained: RetainedObjects?): Entry {
        val committed = asking {
            val target = replace?.let { Wire.json.encodeToString(Wire.Item.serializer(), Wire.item(it)) }
            Wire.json.decodeFromString(Wire.Committed.serializer(), helper.commit(staged.key, parent.key, name, target))
        }
        committed.retained.forEach { (key, message) -> onRetained?.invoke(NodeRef(id, key), message) }
        return Wire.entry(id, committed.item)
    }

    suspend fun setModified(ref: NodeRef, epochMillis: Long): Long = asking { helper.setModified(ref.key, epochMillis) }

    suspend fun availableBytes(parent: NodeRef): Long? = asking { helper.availableBytes(parent.key).takeIf { it >= 0 } }

    suspend fun describe(): String = asking { helper.describe() }

    suspend fun openRead(ref: NodeRef): InputStream {
        descriptor(ref, writing = false)?.let { return ParcelFileDescriptor.AutoCloseInputStream(it) }
        val size = stat(ref).size ?: 0L
        return RemoteInputStream(blocks(ref), size)
    }

    // A real descriptor on the file itself, so it seeks; closing the channel closes the stream and the descriptor with it.
    suspend fun openChannel(ref: NodeRef): SeekableByteChannel {
        descriptor(ref, writing = false)?.let { return ParcelFileDescriptor.AutoCloseInputStream(it).channel }
        val size = stat(ref).size ?: 0L
        return RemoteChannel(blocks(ref), size, Wire.BLOCK)
    }

    suspend fun openWrite(ref: NodeRef): OutputStream {
        val descriptor = descriptor(ref, writing = true)
        if (descriptor != null) return object : FileOutputStream(descriptor.fileDescriptor) {
            private var closed = false
            // A sync that fails must fail the close: the engine rereads from the page cache, which would still look whole.
            override fun close() {
                if (closed) return
                closed = true
                try { flush(); fd.sync() } finally { try { super.close() } finally { descriptor.close() } }
            }
        }
        val handle = holding({ helper.open(ref.key, true) }) { helper.close(it, false) }
        return BufferedOutputStream(object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                var from = off
                while (from < off + len) {
                    val n = minOf(Wire.BLOCK, off + len - from)
                    told { helper.append(handle, b.copyOfRange(from, from + n)) }
                    from += n
                }
            }
            override fun close() = told { helper.close(handle, true) }
        }, Wire.BLOCK)
    }

    // Some devices refuse descriptor transfer despite a live binder; use bounded byte transactions after that refusal.
    private suspend fun descriptor(ref: NodeRef, writing: Boolean): ParcelFileDescriptor? {
        if (!descriptors) return null
        return holding<ParcelFileDescriptor?>({ crossing(ref, writing) }) { it?.close() }
    }

    private fun crossing(ref: NodeRef, writing: Boolean): ParcelFileDescriptor? = try {
        if (writing) helper.openWrite(ref.key) else helper.openRead(ref.key)
    } catch (failed: IllegalStateException) {
        throw Wire.failure(failed) ?: StorageException(StorageError.IO, failed.message ?: "The helper failed", failed)
    } catch (failed: RemoteException) {
        if (!helper.asBinder().pingBinder()) throw StorageException(StorageError.DISCONNECTED, "Shizuku stopped", failed)
        DebugLog.w(TAG, failed) { "A descriptor did not cross from the helper; reading and writing in blocks from now on" }
        descriptors = false
        null
    }

    private suspend fun blocks(ref: NodeRef): RemoteReading {
        val first = holding({ Blocks(helper.open(ref.key, false)) }) { it.close() }
        return RemoteReading(first, ref.key, null, TAG, StorageError.DISCONNECTED) { told { Blocks(helper.open(ref.key, false)) } }
    }

    private inner class Blocks(private val handle: Long) : RemoteReader {
        override fun read(offset: Long, into: ByteArray, at: Int, length: Int): Int = told {
            val bytes = helper.readAt(handle, offset, minOf(length, Wire.BLOCK))
            if (bytes.isEmpty()) -1 else { bytes.copyInto(into, at); bytes.size }
        }
        override fun close() { runCatching { helper.close(handle, false) } }
    }

    /** For the blocking streams: a failure becomes one a stream's reader expects. */
    private fun <T> told(block: () -> T): T = try {
        block()
    } catch (failed: IllegalStateException) {
        val known = Wire.failure(failed)
        throw RemoteFailure(known?.reason ?: StorageError.IO, known?.message ?: failed.message ?: "The helper failed", failed)
    } catch (failed: RemoteException) {
        throw RemoteFailure(StorageError.DISCONNECTED, "Shizuku stopped", failed)
    }

    // Cancellation during open must not discard the only reference to the helper handle.
    private suspend fun <T> holding(open: () -> T, release: (T) -> Unit): T {
        val held = withContext(NonCancellable) { asking(open) }
        if (!currentCoroutineContext().isActive) {
            withContext(NonCancellable + Dispatchers.IO) { try { release(held) } catch (_: Exception) { } }
            currentCoroutineContext().ensureActive()
        }
        return held
    }

    private suspend fun <T> asking(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (failed: IllegalStateException) {
            throw Wire.failure(failed) ?: StorageException(StorageError.IO, failed.message ?: "The helper failed", failed)
        } catch (gone: DeadObjectException) {
            throw StorageException(StorageError.DISCONNECTED, "Shizuku stopped", gone)
        } catch (failed: RemoteException) {
            throw StorageException(StorageError.DISCONNECTED, "Shizuku did not answer: ${failed.message ?: failed.javaClass.simpleName}", failed)
        }
    }

    private fun decoded(item: String): Entry = Wire.entry(id, Wire.json.decodeFromString(Wire.Item.serializer(), item))

    private companion object {
        const val TAG = "Shizuku"
    }
}
