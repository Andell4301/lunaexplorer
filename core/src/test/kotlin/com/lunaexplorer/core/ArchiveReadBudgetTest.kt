package com.lunaexplorer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveReadBudgetTest {
    @Test fun `budgeted reads bypass the unlimited cached archive and close their own handle on exhaustion`() = runBlocking {
        val contents = ByteArray(100_000) { (it % 251).toByte() }
        val remote = RemoteArchive(zip("picture.bin", contents, stored = true))
        val archive = archiveProvider(remote)
        val root = archive.open(remote.ref, "pictures.zip")
        val member = memberRef(root, "picture.bin")
        val before = remote.unlimitedBytes
        val budget = ReadBudget(64)

        val failure = runCatching { archive.openRead(member, budget).use { it.readBytes() } }.exceptionOrNull()
        assertTrue("Expected a read limit, got $failure", failure is InterruptedIOException)
        assertTrue(budget.bytesRead in 1..budget.max)
        assertEquals(budget.bytesRead, remote.budgetedBytes)
        assertEquals("The shared unlimited channel must not pay for thumbnail reads", before, remote.unlimitedBytes)
        assertEquals(1, remote.budgetedChannelOpens)
        assertEquals("Exhaustion must not restart a full archive stream", 0, remote.budgetedStreamOpens)
        assertEquals(0, remote.activeBudgetedHandles)

        // An exhausted budgeted read must not evict the cached unbudgeted archive.
        archive.openRead(member).use { assertArrayEquals(contents, it.readBytes()) }
        assertEquals(1, remote.unlimitedChannelOpens)
        assertEquals(0, remote.unlimitedStreamOpens)
    }

    @Test fun `compressed and nested members charge source bytes rather than inflated member size`() = runBlocking {
        val contents = ByteArray(2 * 1024 * 1024) { 'a'.code.toByte() }
        val inner = zip("picture.bin", contents)
        for (nested in listOf(false, true)) {
            val remote = RemoteArchive(if (nested) zip("inner.zip", inner) else inner)
            val archive = archiveProvider(remote)
            val outer = archive.open(remote.ref, "pictures.zip")
            val root = if (nested) archive.open(memberRef(outer, "inner.zip"), "inner.zip") else outer
            val member = memberRef(root, "picture.bin")
            val before = remote.unlimitedBytes
            val budget = ReadBudget(16 * 1024)

            archive.openRead(member, budget).use { assertArrayEquals(contents, it.readBytes()) }
            assertTrue("The compressed network payload must be charged", budget.bytesRead > 0)
            assertTrue("Inflated bytes must not consume the network allowance", contents.size > budget.max)
            assertTrue(budget.bytesRead <= budget.max)
            assertEquals(budget.bytesRead, remote.budgetedBytes)
            assertEquals(before, remote.unlimitedBytes)
            assertTrue(remote.budgetedChannelOpens > 0)
            assertEquals(0, remote.activeBudgetedHandles)
        }
    }

    @Test fun `cancelling an opened member stops network reads and closes only its own handle`() = runBlocking {
        val remote = RemoteArchive(zip("picture.bin", ByteArray(100_000), stored = true))
        val archive = archiveProvider(remote)
        val root = archive.open(remote.ref, "pictures.zip")
        val member = memberRef(root, "picture.bin")
        var cancelled = false
        val budget = ReadBudget(200_000) {
            if (cancelled) throw CancellationException("Thumbnail scrolled away")
        }
        val stream = archive.openRead(member, budget)
        val before = remote.budgetedBytes
        val unmeteredBefore = remote.unlimitedBytes
        cancelled = true

        val failure = runCatching { stream.use { it.readBytes() } }.exceptionOrNull()
        assertTrue("Cancellation must reach the caller, got $failure", failure is CancellationException)
        assertEquals(before, remote.budgetedBytes)
        assertEquals(unmeteredBefore, remote.unlimitedBytes)
        assertEquals(0, remote.budgetedStreamOpens)
        assertEquals(0, remote.activeBudgetedHandles)
        assertEquals(1, remote.unlimitedChannelOpens)
    }

    @Test fun `stream-only archives share the same allowance while walking preceding entries`() = runBlocking {
        val bytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                storedEntry(zip, "before.bin", ByteArray(32_000))
                storedEntry(zip, "picture.bin", byteArrayOf(1, 2, 3))
            }
        }.toByteArray()
        val remote = RemoteArchive(bytes, seekable = false)
        val archive = archiveProvider(remote)
        val root = archive.open(remote.ref, "pictures.zip")
        val member = memberRef(root, "picture.bin")
        val before = remote.unlimitedBytes
        val budget = ReadBudget(1024)

        val failure = runCatching { archive.openRead(member, budget).use { it.readBytes() } }.exceptionOrNull()
        assertTrue("Walking earlier members must stop at the limit, got $failure", failure is InterruptedIOException)
        assertEquals(budget.max, budget.bytesRead)
        assertEquals(budget.bytesRead, remote.budgetedBytes)
        assertEquals(before, remote.unlimitedBytes)
        assertEquals(1, remote.budgetedStreamOpens)
        assertEquals(1, remote.unlimitedStreamOpens)
        assertEquals(0, remote.activeBudgetedHandles)
    }

    private fun zip(name: String, data: ByteArray, stored: Boolean = false): ByteArray =
        ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                if (stored) storedEntry(zip, name, data) else {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun storedEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = size
            crc = CRC32().apply { update(data) }.value
        })
        zip.write(data)
        zip.closeEntry()
    }

    /** Counts actual source reads, including archive headers, independently of ReadBudget. */
    private class RemoteArchive(private val bytes: ByteArray, private val seekable: Boolean = true) :
        StorageProvider by MemoryStorageProvider("remote"), BudgetedReads {
        val ref = NodeRef("remote", "pictures.zip")
        override val features = if (seekable) setOf(Feature.NETWORK, Feature.RANGE_READ) else setOf(Feature.NETWORK)
        var unlimitedBytes = 0L
        var budgetedBytes = 0L
        var unlimitedChannelOpens = 0
        var unlimitedStreamOpens = 0
        var budgetedChannelOpens = 0
        var budgetedStreamOpens = 0
        var activeBudgetedHandles = 0

        override suspend fun stat(ref: NodeRef) = Entry(ref, "pictures.zip", false,
            size = bytes.size.toLong(), capabilities = setOf(Capability.READ))

        override suspend fun openChannel(ref: NodeRef): SeekableByteChannel? {
            if (!seekable) return null
            unlimitedChannelOpens++
            return channel(null)
        }

        override suspend fun openChannel(ref: NodeRef, budget: ReadBudget): SeekableByteChannel? {
            if (!seekable) return null
            budgetedChannelOpens++
            return channel(budget)
        }

        override suspend fun openRead(ref: NodeRef): InputStream {
            unlimitedStreamOpens++
            return stream(null)
        }

        override suspend fun openRead(ref: NodeRef, budget: ReadBudget): InputStream {
            budgetedStreamOpens++
            return stream(budget)
        }

        private fun stream(budget: ReadBudget?): InputStream {
            val channel = channel(budget)
            return object : InputStream() {
                override fun read(): Int {
                    val single = ByteArray(1)
                    return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
                }
                override fun read(b: ByteArray, off: Int, len: Int) = channel.read(ByteBuffer.wrap(b, off, len))
                override fun close() = channel.close()
            }
        }

        private fun channel(budget: ReadBudget?): SeekableByteChannel {
            if (budget != null) activeBudgetedHandles++
            return object : SeekableByteChannel {
                private var offset = 0
                private var open = true
                override fun read(dst: ByteBuffer): Int {
                    if (!dst.hasRemaining()) return 0
                    if (offset >= bytes.size) return -1
                    fun fetch(requested: Int): Int {
                        val count = minOf(requested, bytes.size - offset)
                        dst.put(bytes, offset, count)
                        offset += count
                        if (budget == null) unlimitedBytes += count else budgetedBytes += count
                        return count
                    }
                    return budget?.read(dst.remaining(), ::fetch) ?: fetch(dst.remaining())
                }
                override fun position() = offset.toLong()
                override fun position(newPosition: Long): SeekableByteChannel = apply { offset = newPosition.toInt() }
                override fun size() = bytes.size.toLong()
                override fun isOpen() = open
                override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException()
                override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException()
                override fun close() {
                    if (open && budget != null) activeBudgetedHandles--
                    open = false
                }
            }
        }
    }
}
