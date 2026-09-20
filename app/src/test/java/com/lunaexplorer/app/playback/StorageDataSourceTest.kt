package com.lunaexplorer.app.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.lunaexplorer.core.Feature
import com.lunaexplorer.core.MemoryStorageProvider
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.StorageProvider
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StorageDataSourceTest {
    private val ref = NodeRef("remote", "opaque-document")
    private val uri = Uri.parse("luna-media:current")
    private val bytes = "0123456789".toByteArray()

    private fun spec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build()

    private class Channel(private val bytes: ByteArray) : SeekableByteChannel {
        var offset = 0L
        var closes = 0
        var reads = 0
        var failure: Exception? = null
        var sizeFailure: Exception? = null
        var seekFailure: Exception? = null
        var stalled = false
        var truncated = false
        override fun read(dst: ByteBuffer): Int {
            reads++
            failure?.let { throw it }
            if (stalled) return 0
            if (truncated) return -1
            if (offset == bytes.size.toLong()) return -1
            val count = minOf(dst.remaining(), bytes.size - offset.toInt())
            dst.put(bytes, offset.toInt(), count)
            offset += count
            return count
        }
        override fun position() = offset
        override fun position(newPosition: Long): SeekableByteChannel {
            seekFailure?.let { throw it }
            offset = newPosition
            return this
        }
        override fun size(): Long {
            sizeFailure?.let { throw it }
            return bytes.size.toLong()
        }
        override fun isOpen() = closes == 0
        override fun close() { closes++ }
        override fun write(src: ByteBuffer): Int = error("Read only")
        override fun truncate(size: Long): SeekableByteChannel = error("Read only")
    }

    private class Stream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closes = 0
        override fun skip(count: Long) = 0L
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, minOf(length, 2))
        override fun close() { closes++ }
    }

    private fun provider(channel: suspend () -> SeekableByteChannel? = { null },
        stream: suspend () -> InputStream = { error("A seekable source must not open a stream") },
    ): StorageProvider = object : StorageProvider by MemoryStorageProvider(ref.provider) {
        override val features = setOf(Feature.NETWORK)
        override suspend fun openChannel(ref: NodeRef): SeekableByteChannel? {
            assertEquals(this@StorageDataSourceTest.ref, ref)
            return channel()
        }
        override suspend fun openRead(ref: NodeRef): InputStream {
            assertEquals(this@StorageDataSourceTest.ref, ref)
            return stream()
        }
    }

    @Test fun `channel reads seek directly and stop at the requested length`() {
        val channel = Channel(bytes)
        val source = StorageDataSource(provider(channel = { channel }), ref)
        val transfers = mutableListOf<String>()
        source.addTransferListener(object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                assertTrue(isNetwork)
                transfers += "initializing"
            }
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { transfers += "start" }
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                transfers += "bytes:$bytesTransferred"
            }
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { transfers += "end" }
        })
        assertEquals(3L, source.open(spec(4, 3)))
        assertEquals(uri, source.uri)
        assertEquals(4L, channel.offset)
        val buffer = ByteArray(9)
        assertEquals(3, source.read(buffer, 2, 5))
        assertArrayEquals("456".toByteArray(), buffer.copyOfRange(2, 5))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, buffer.size))
        assertEquals(0, source.read(buffer, 0, 0))
        assertEquals(1, channel.closes)
        source.close()
        source.close()
        assertEquals(1, channel.closes)
        assertNull(source.uri)
        assertEquals(listOf("initializing", "start", "bytes:3", "end"), transfers)
    }

    @Test fun `bounded requests extending past channel EOF return the requested length and available bytes`() {
        val channel = Channel(bytes)
        val source = StorageDataSource(provider(channel = { channel }), ref)
        assertEquals(20L, source.open(spec(8, 20)))
        val buffer = ByteArray(20)
        assertEquals(2, source.read(buffer, 0, buffer.size))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, buffer.size))
        assertEquals(1, channel.closes)
        source.close()
    }

    @Test fun `unbounded channel requests resolve length and distinguish exact EOF from past EOF`() {
        val channels = mutableListOf<Channel>()
        val source = StorageDataSource(provider(channel = { Channel(bytes).also(channels::add) }), ref)
        assertEquals(4L, source.open(spec(6)))
        source.close()
        assertEquals(0L, source.open(spec(10)))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(1), 0, 1))
        source.close()
        val failure = assertThrows(IOException::class.java) { source.open(spec(11)) }
        assertTrue(DataSourceException.isCausedByPositionOutOfRange(failure))
        assertNull(source.uri)
        source.close()
        assertTrue(channels.all { it.closes == 1 })
    }

    @Test fun `stream fallback handles zero skip progress and short reads`() {
        val stream = Stream(bytes)
        val source = StorageDataSource(provider(stream = { stream }), ref)
        assertEquals(3L, source.open(spec(5, 3)))
        val buffer = ByteArray(4)
        assertEquals(2, source.read(buffer, 0, 4))
        assertEquals(1, source.read(buffer, 2, 2))
        assertArrayEquals("567".toByteArray(), buffer.copyOf(3))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, 4))
        assertEquals(1, stream.closes)
        source.close()
        assertEquals(1, stream.closes)
    }

    @Test fun `unknown stream size permits exact EOF but rejects a seek past EOF`() {
        val streams = mutableListOf<Stream>()
        val source = StorageDataSource(provider(stream = { Stream(bytes).also(streams::add) }), ref)
        assertEquals(C.LENGTH_UNSET.toLong(), source.open(spec(10)))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(1), 0, 1))
        assertEquals(1, streams.single().closes)
        source.close()
        val failure = assertThrows(IOException::class.java) { source.open(spec(11)) }
        assertTrue(DataSourceException.isCausedByPositionOutOfRange(failure))
        source.close()
        assertTrue(streams.all { it.closes == 1 })
    }

    @Test fun `failed size and cancelled seek close the acquired channel without opening a stream`() {
        val failure = IOException("Disconnected")
        val cancelled = CancellationException("Cancelled")
        for (channel in listOf(Channel(bytes).apply { sizeFailure = failure }, Channel(bytes).apply { seekFailure = cancelled })) {
            val source = StorageDataSource(provider(channel = { channel }), ref)
            val actual = assertThrows(Exception::class.java) { source.open(spec(2)) }
            assertSame(channel.sizeFailure ?: channel.seekFailure, actual)
            assertEquals(1, channel.closes)
            assertNull(source.uri)
            source.close()
            assertEquals(1, channel.closes)
        }
    }

    @Test fun `read failure closes the channel and never reopens it`() {
        val failure = IOException("Disconnected")
        val channel = Channel(bytes).apply { this.failure = failure }
        var opens = 0
        val source = StorageDataSource(provider(channel = { opens++; channel }), ref)
        source.open(spec())
        assertSame(failure, assertThrows(IOException::class.java) { source.read(ByteArray(3), 0, 3) })
        assertEquals(1, channel.closes)
        assertEquals(1, opens)
        source.close()
        assertEquals(1, channel.closes)
    }

    @Test fun `a channel returning no bytes fails without spinning`() {
        val channel = Channel(bytes).apply { stalled = true }
        val source = StorageDataSource(provider(channel = { channel }), ref)
        source.open(spec())
        assertThrows(IOException::class.java) { source.read(ByteArray(3), 0, 3) }
        assertEquals(1, channel.reads)
        assertEquals(1, channel.closes)
        source.close()
    }

    @Test fun `a channel ending before its reported size fails and closes`() {
        val channel = Channel(bytes)
        val source = StorageDataSource(provider(channel = { channel }), ref)
        source.open(spec())
        channel.truncated = true
        assertThrows(EOFException::class.java) { source.read(ByteArray(3), 0, 3) }
        assertEquals(1, channel.reads)
        assertEquals(1, channel.closes)
        source.close()
        assertEquals(1, channel.closes)
    }

    @Test fun `a stream failing while seeking closes before open returns`() {
        val failure = IOException("Disconnected")
        var closed = 0
        val stream = object : InputStream() {
            override fun read(): Int = throw failure
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw failure
            override fun close() { closed++ }
        }
        val source = StorageDataSource(provider(stream = { stream }), ref)
        assertSame(failure, assertThrows(IOException::class.java) { source.open(spec(1)) })
        assertEquals(1, closed)
        source.close()
        assertEquals(1, closed)
    }

    @Test fun `an interrupted loader closes its channel before reading`() {
        val channel = Channel(bytes)
        val source = StorageDataSource(provider(channel = { channel }), ref)
        source.open(spec())
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) { source.read(ByteArray(3), 0, 3) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(0, channel.reads)
            assertEquals(1, channel.closes)
        } finally {
            Thread.interrupted()
            source.close()
        }
    }

    @Test fun `an interrupted loader never starts another provider open`() {
        var opens = 0
        val source = StorageDataSource(provider(channel = { opens++; Channel(bytes) }), ref)
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) { source.open(spec()) }
            assertEquals(0, opens)
        } finally {
            Thread.interrupted()
            source.close()
        }
    }
}
