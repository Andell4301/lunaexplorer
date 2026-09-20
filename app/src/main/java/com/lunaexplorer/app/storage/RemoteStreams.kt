package com.lunaexplorer.app.storage

import com.lunaexplorer.app.debug.DebugLog
import com.lunaexplorer.core.ReadBudget
import com.lunaexplorer.core.ReadBudgetExceeded
import com.lunaexplorer.core.StorageError
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

private const val SLOW_READ_MS = 1_000L

interface RemoteReader : Closeable {
    /** Returns -1 at EOF. */
    fun read(offset: Long, into: ByteArray, at: Int, length: Int): Int
}

open class RemoteFailure(val reason: StorageError, message: String, cause: Throwable? = null) : Exception(message, cause)

// Retry matching failures once on a reopened file; expose remote failures as IOExceptions.
internal class RemoteReading(
    private var reader: RemoteReader,
    private val label: String,
    val budget: ReadBudget? = null,
    private val tag: String = "Smb",
    private val retryOn: StorageError = StorageError.DISCONNECTED,
    private val reopen: () -> RemoteReader,
) : Closeable {
    @Volatile private var closed = false

    fun checkCancelled() = budget?.checkCancelled()

    private fun readRaw(offset: Long, into: ByteArray, at: Int, length: Int): Int =
        budget?.read(length) { allowed -> reader.read(offset, into, at, allowed) }
            ?: reader.read(offset, into, at, length)

    fun read(offset: Long, into: ByteArray, at: Int, length: Int): Int {
        if (closed) throw ClosedChannelException()
        val started = System.nanoTime()
        return try {
            readRaw(offset, into, at, length).also {
                val took = DebugLog.millisSince(started)
                if (took >= SLOW_READ_MS) DebugLog.i(tag) { "Read of $length bytes of $label at $offset took $took ms" }
            }
        } catch (dropped: RemoteFailure) {
            val took = DebugLog.millisSince(started)
            if (dropped.reason != retryOn) {
                DebugLog.w(tag, dropped) { "Read of $label at $offset failed after $took ms: ${dropped.reason}" }
                throw IOException(dropped.message, dropped)
            }
            DebugLog.w(tag, dropped) { "Read of $label at $offset found its connection gone after $took ms; opening the file again" }
            budget?.let {
                it.checkCancelled()
                if (it.remaining == 0L) throw ReadBudgetExceeded(it.max)
            }
            runCatching { reader.close() }
            reader = try { reopen() } catch (again: Exception) {
                DebugLog.w(tag, again) { "Could not open $label again" }
                throw IOException(again.message ?: dropped.message, again)
            }
            try { readRaw(offset, into, at, length) } catch (failed: RemoteFailure) {
                DebugLog.w(tag, failed) { "Read of $label at $offset failed again: ${failed.reason}" }
                throw IOException(failed.message, failed)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { reader.close() }
    }
}

internal class RemoteInputStream(private val reading: RemoteReading, private val size: Long) : InputStream() {
    private var position = 0L
    private val buffer = ByteArray(256 * 1024)
    private var buffered = 0
    private var offset = 0

    private fun fill(): Boolean {
        if (position >= size) return false
        val got = reading.read(position, buffer, 0, buffer.size)
        if (got <= 0) return false
        position += got; buffered = got; offset = 0
        return true
    }

    override fun read(): Int {
        reading.checkCancelled()
        if (offset >= buffered && !fill()) return -1
        return buffer[offset++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        reading.checkCancelled()
        if (len == 0) return 0
        if (offset >= buffered && !fill()) return -1
        val n = minOf(len, buffered - offset)
        System.arraycopy(buffer, offset, b, off, n)
        offset += n
        return n
    }

    override fun close() = reading.close()
}

// Cache small reads so archive headers do not cost a network request per field.
internal class RemoteChannel(
    private val reading: RemoteReading,
    private val size: Long,
    private val block: Int = 64 * 1024,
) : SeekableByteChannel {
    private class Block(val start: Long, val bytes: ByteArray, val length: Int)
    private val blocks = ArrayDeque<Block>()
    private var position = 0L
    private var open = true

    override fun read(dst: ByteBuffer): Int {
        if (!open) throw ClosedChannelException()
        reading.checkCancelled()
        if (position >= size) return -1
        val wanted = minOf(dst.remaining().toLong(), size - position).toInt()
        if (wanted == 0) return 0
        val alreadyFetched = reading.budget != null && blocks.any {
            position >= it.start && position - it.start < it.length
        }
        val got = if (wanted >= block && !alreadyFetched) direct(dst, wanted) else cached(dst, wanted)
        if (got <= 0) return -1
        position += got
        return got
    }

    private fun direct(dst: ByteBuffer, wanted: Int): Int {
        if (dst.hasArray()) {
            val got = reading.read(position, dst.array(), dst.arrayOffset() + dst.position(), wanted)
            if (got > 0) dst.position(dst.position() + got)
            return got
        }
        val chunk = ByteArray(wanted)
        val got = reading.read(position, chunk, 0, wanted)
        if (got > 0) dst.put(chunk, 0, got)
        return got
    }

    private fun cached(dst: ByteBuffer, wanted: Int): Int {
        val budgeted = reading.budget != null
        // With a budget, blocks start at the requested offset so none of the allowance goes on
        // alignment. Without one, blocks are aligned to the block size.
        val start = if (budgeted) position else position - position % block
        val block = blocks.firstOrNull {
            if (budgeted) position >= it.start && position - it.start < it.length else it.start == start
        }?.also { blocks.remove(it); blocks.addLast(it) } ?: fetch(start)
        val from = (position - block.start).toInt()
        if (from >= block.length) return -1
        val n = minOf(wanted, block.length - from)
        dst.put(block.bytes, from, n)
        return n
    }

    private fun fetch(start: Long): Block {
        val budget = reading.budget
        budget?.checkCancelled()
        val remaining = budget?.remaining ?: Long.MAX_VALUE
        if (remaining == 0L) throw ReadBudgetExceeded(requireNotNull(budget).max)
        val bytes = ByteArray(minOf(block.toLong(), size - start, remaining).toInt())
        var filled = 0
        while (filled < bytes.size) {
            // The budget is shared with other handles and can run out mid-block; keep what was
            // already fetched.
            if (filled > 0 && budget?.remaining == 0L) break
            val got = try {
                reading.read(start + filled, bytes, filled, bytes.size - filled)
            } catch (exhausted: ReadBudgetExceeded) {
                if (filled == 0) throw exhausted
                break
            }
            if (got <= 0) break
            filled += got
        }
        return Block(start, bytes, filled).also {
            blocks.addLast(it)
            while (blocks.size > BLOCKS_KEPT) blocks.removeAt(0)
        }
    }

    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
    override fun position(): Long = position
    override fun position(newPosition: Long): SeekableByteChannel = apply { position = newPosition.coerceAtLeast(0) }
    override fun size(): Long = size
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    override fun isOpen(): Boolean = open

    override fun close() {
        if (!open) return
        open = false
        blocks.clear()
        reading.close()
    }

    private companion object {
        const val BLOCKS_KEPT = 8
    }
}
