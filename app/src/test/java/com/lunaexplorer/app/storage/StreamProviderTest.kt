package com.lunaexplorer.app.storage

import android.app.Application
import android.content.pm.ProviderInfo
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.MemoryStorageProvider
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.StorageProvider
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowStorageManager
import java.io.File
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StreamProviderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = ByteArray(12) { it.toByte() }

    @Test
    @Config(application = LunaApplication::class, shadows = [CapturedStreamProxy::class])
    fun `an in-process stream reader receives bytes without a proxy descriptor`() {
        withStreamReader(external = false) { provider, uri, storage ->
            provider.openFile(uri, "r").use { descriptor ->
                assertNull("A process must not serve its own proxy descriptor", storage.callback)
                awaitPipeBytes(descriptor, bytes.size)
                assertArrayEquals(bytes, ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes())
            }
        }
    }

    @Test
    @Config(application = LunaApplication::class, shadows = [CapturedStreamProxy::class])
    fun `an external stream reader retains offset reads through a proxy descriptor`() {
        withStreamReader(external = true) { provider, uri, storage ->
            provider.openFile(uri, "r").use {
                val callback = requireNotNull(storage.callback)
                try {
                    val range = ByteArray(4)
                    assertEquals(4, callback.onRead(5, range.size, range))
                    assertArrayEquals(bytes.copyOfRange(5, 9), range)
                    assertEquals(bytes.size.toLong(), callback.onGetSize())
                } finally { callback.onRelease() }
            }
        }
    }

    @Test fun `a cancelled pipe read closes its stream and releases its lease once`() {
        val context = RuntimeEnvironment.getApplication()
        val ref = NodeRef("pipe", "cancelled")
        val closed = AtomicInteger()
        val releases = AtomicInteger()
        val finished = CountDownLatch(1)
        val provider = object : StorageProvider by MemoryStorageProvider(ref.provider) {
            override suspend fun stat(ref: NodeRef) = Entry(ref, "clip.mp4", directory = false, size = bytes.size.toLong())
            override suspend fun openRead(ref: NodeRef) = object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw CancellationException("Cancelled")
                override fun close() { closed.incrementAndGet() }
            }
        }
        StreamProvider.openForReading(context, provider, ref, Handler(Looper.getMainLooper()),
            context.packageName, allowProxy = false) { releases.incrementAndGet(); finished.countDown() }.use {
            assertTrue("Cancellation must finish the writer", finished.await(5, TimeUnit.SECONDS))
            assertEquals(1, closed.get())
            assertEquals(1, releases.get())
        }
    }

    private fun withStreamReader(
        external: Boolean,
        read: (StreamProvider, android.net.Uri, CapturedStreamProxy) -> Unit,
    ) {
        val context = RuntimeEnvironment.getApplication() as LunaApplication
        val graph = context.graph
        val root = temporary.newFolder()
        val file = File(root, "clip.mp4").apply { writeBytes(bytes) }
        graph.additionalRoots = listOf(LocalRoot("stream-test", "Stream", root))
        val ref = requireNotNull(graph.local.referenceTo(file.absolutePath))
        val authority = "${context.packageName}.stream"
        val controller = Robolectric.buildContentProvider(StreamProvider::class.java)
            .create(ProviderInfo().apply { this.authority = authority })
        val storage = Shadow.extract<CapturedStreamProxy>(context.getSystemService(StorageManager::class.java))
        ShadowBinder.setCallingPid(Process.myPid() + if (external) 1 else 0)
        try { read(controller.get(), StreamProvider.uriFor(context, ref), storage) }
        finally { storage.callback?.onRelease(); ShadowBinder.reset(); controller.shutdown() }
    }

    @Test fun `short channel reads fill the requested range and final reads stop at the known end`() {
        val channel = Reader(bytes, maxRead = 2)
        val proxy = StreamProvider.Proxy(channel, bytes.size.toLong()) { error("No reconnect expected") }
        val data = ByteArray(8) { 99 }

        assertEquals(8, proxy.onRead(3, data.size, data))
        assertArrayEquals(bytes.copyOfRange(3, 11), data)

        data.fill(99)
        assertEquals(2, proxy.onRead(10, data.size, data))
        assertArrayEquals(byteArrayOf(10, 11, 99, 99, 99, 99, 99, 99), data)
        assertEquals(0, proxy.onRead(12, data.size, data))
        proxy.onRelease()
    }

    @Test fun `a failed partial read closes the old channel and retries the whole range at the requested offset`() {
        val failed = Reader(ByteArray(bytes.size) { 99 }, maxRead = 2, failOnRead = 2)
        val replacement = Reader(bytes)
        var reopened = 0
        val proxy = StreamProvider.Proxy(failed, bytes.size.toLong()) {
            assertFalse("The failed handle must be closed before reconnecting", failed.isOpen())
            reopened++
            replacement
        }
        val data = ByteArray(6)

        assertEquals(6, proxy.onRead(3, data.size, data))
        assertArrayEquals(bytes.copyOfRange(3, 9), data)
        assertEquals(listOf(3L), replacement.seekOffsets)
        assertEquals(1, reopened)
        proxy.onRelease()
        assertEquals(1, replacement.closes)
    }

    @Test fun `a reconnect that cannot open the file reports EIO with its cause`() {
        val failed = Reader(bytes, failOnRead = 1)
        val reconnectFailure = IOException("The server is unavailable")
        val proxy = StreamProvider.Proxy(failed, bytes.size.toLong()) { throw reconnectFailure }

        val error = assertThrows(ErrnoException::class.java) { proxy.onRead(4, 3, ByteArray(3)) }

        assertEquals(OsConstants.EIO, error.errno)
        assertSame(reconnectFailure, error.cause)
        assertFalse(failed.isOpen())
        proxy.onRelease()
    }

    @Test fun `a second read failure reports EIO without an unbounded reconnect loop`() {
        val first = Reader(bytes, failOnRead = 1)
        val second = Reader(bytes, failOnRead = 1)
        var reopened = 0
        val proxy = StreamProvider.Proxy(first, bytes.size.toLong()) { reopened++; second }

        val error = assertThrows(ErrnoException::class.java) { proxy.onRead(4, 3, ByteArray(3)) }

        assertEquals(OsConstants.EIO, error.errno)
        assertEquals(1, reopened)
        proxy.onRelease()
        assertEquals(1, second.closes)
    }

    @Test fun `a read that makes no progress reconnects instead of telling the player the movie ended`() {
        val stalled = Reader(bytes, zeroOnRead = 1)
        val replacement = Reader(bytes)
        val proxy = StreamProvider.Proxy(stalled, bytes.size.toLong()) { replacement }
        val data = ByteArray(5)

        assertEquals(5, proxy.onRead(2, data.size, data))
        assertArrayEquals(bytes.copyOfRange(2, 7), data)
        assertFalse(stalled.isOpen())
        proxy.onRelease()
    }

    @Test fun `a premature end is an error when neither channel can supply the known file range`() {
        val first = Reader(bytes.copyOf(3))
        val second = Reader(bytes.copyOf(3))
        val proxy = StreamProvider.Proxy(first, bytes.size.toLong()) { second }

        val error = assertThrows(ErrnoException::class.java) { proxy.onRead(0, 6, ByteArray(6)) }

        assertEquals(OsConstants.EIO, error.errno)
        proxy.onRelease()
    }

    @Test fun `release closes the handle and foreground lease once even when handle cleanup fails`() {
        val channel = Reader(bytes, failOnClose = true)
        var releases = 0
        val proxy = StreamProvider.Proxy(channel, bytes.size.toLong(), release = { releases++ }) {
            error("A released descriptor must not reopen")
        }

        proxy.onRelease()
        proxy.onRelease()

        assertEquals(1, channel.closes)
        assertEquals(1, releases)
        val error = assertThrows(ErrnoException::class.java) { proxy.onRead(0, 2, ByteArray(2)) }
        assertEquals(OsConstants.EBADF, error.errno)
    }

    private class Reader(
        private val bytes: ByteArray,
        private val maxRead: Int = Int.MAX_VALUE,
        private val failOnRead: Int? = null,
        private val zeroOnRead: Int? = null,
        private val failOnClose: Boolean = false,
    ) : SeekableByteChannel {
        private var offset = 0L
        private var reads = 0
        private var open = true
        var closes = 0
            private set
        val seekOffsets = mutableListOf<Long>()

        override fun read(dst: ByteBuffer): Int {
            reads++
            if (reads == failOnRead) throw IOException("Connection dropped")
            if (reads == zeroOnRead) return 0
            if (offset >= bytes.size) return -1
            val count = minOf(dst.remaining(), maxRead, bytes.size - offset.toInt())
            dst.put(bytes, offset.toInt(), count)
            offset += count
            return count
        }

        override fun position(): Long = offset
        override fun position(newPosition: Long): SeekableByteChannel = apply {
            seekOffsets += newPosition
            offset = newPosition
        }
        override fun size(): Long = bytes.size.toLong()
        override fun isOpen(): Boolean = open
        override fun close() {
            closes++
            open = false
            if (failOnClose) throw IOException("Close failed")
        }
        override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
        override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    }
}

@Implements(StorageManager::class)
class CapturedStreamProxy : ShadowStorageManager() {
    var callback: ProxyFileDescriptorCallback? = null
        private set

    @Implementation
    fun openProxyFileDescriptor(mode: Int, callback: ProxyFileDescriptorCallback, handler: Handler): ParcelFileDescriptor {
        this.callback = callback
        val file = File.createTempFile("stream-proxy-", ".tmp", RuntimeEnvironment.getApplication().cacheDir)
        return try { ParcelFileDescriptor.open(file, mode) } finally { file.delete() }
    }
}

internal fun awaitPipeBytes(descriptor: ParcelFileDescriptor, count: Int) {
    // Robolectric models a pipe as a file, so its reader cannot wait for the writer.
    val deadline = System.nanoTime() + 5_000_000_000L
    while (descriptor.statSize < count && System.nanoTime() < deadline) Thread.sleep(10)
    assertTrue("The stream writer must supply the file", descriptor.statSize >= count)
}
