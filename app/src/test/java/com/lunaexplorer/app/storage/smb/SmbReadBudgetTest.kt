package com.lunaexplorer.app.storage.smb

import com.lunaexplorer.app.storage.RemoteChannel
import com.lunaexplorer.app.storage.RemoteReading
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.ReadBudget
import com.lunaexplorer.core.ReadBudgetExceeded
import com.lunaexplorer.core.StorageError
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmbReadBudgetTest {
    private val account = SmbAccount(id = "nas", name = "NAS", host = "nas.local", guest = true)
    private val connector = FakeSmbConnector()
    private val provider = SmbStorageProvider({ listOf(account) }, { it }, connector)
    private val file = NodeRef("smb", "nas:media:file.bin")
    private val share get() = connector.share

    @After fun disconnect() { provider.disconnect(account.id) }

    @Test fun `tiny reads can seek through a huge file and cached payload remains usable after exhaustion`() {
        val source = HugeReader(8L shl 30)
        val budget = ReadBudget(BLOCK + 48L)
        val start = (5L shl 30) + 123
        RemoteChannel(RemoteReading(source, "huge.bin", budget) { error("No reconnect expected") }, source.size).use { channel ->
            channel.position(start)
            val first = ByteBuffer.allocate(8)
            assertEquals(8, channel.read(first))
            assertEquals(HugeReader.byteAt(start), first.array()[0])
            assertEquals(BLOCK.toLong(), source.served)

            channel.position(source.size - 7)
            assertEquals(7, channel.read(ByteBuffer.allocate(7)))
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)))

            val finalStart = (3L shl 30) + 19
            channel.position(finalStart)
            val last = ByteBuffer.allocate(64)
            assertEquals(41, channel.read(last))
            assertEquals(HugeReader.byteAt(finalStart), last.array()[0])
            assertEquals(0L, budget.remaining)

            // Blocks already paid for stay readable after the budget is spent.
            channel.position(start)
            assertEquals(BLOCK, channel.read(ByteBuffer.allocate(BLOCK * 2)))
            channel.position(finalStart)
            assertEquals(41, channel.read(ByteBuffer.allocate(64)))
            channel.position(0)
            assertThrows(ReadBudgetExceeded::class.java) { channel.read(ByteBuffer.allocate(1)) }
            assertEquals(budget.max, source.served)
            assertEquals(3, source.requests)
        }
        assertTrue(source.closed)
    }

    @Test fun `large direct buffers also clamp the raw request`() {
        val source = HugeReader(8L shl 30)
        val budget = ReadBudget(23)
        RemoteChannel(RemoteReading(source, "huge.bin", budget) { error("No reconnect expected") }, source.size).use { channel ->
            channel.position(7L shl 30)
            assertEquals(23, channel.read(ByteBuffer.allocateDirect(BLOCK * 2)))
            assertEquals(23L, source.served)
            assertThrows(ReadBudgetExceeded::class.java) { channel.read(ByteBuffer.allocateDirect(BLOCK * 2)) }
        }
        assertTrue(source.closed)
    }

    @Test fun `stream read ahead is charged once and exact EOF stays EOF`() = runBlocking {
        val bytes = ByteArray(31) { it.toByte() }
        share.put("file.bin", bytes)
        val budget = ReadBudget(bytes.size.toLong())
        provider.openRead(file, budget).use { input ->
            assertEquals(0, input.read())
            assertEquals(31L, share.bytesServed)
            assertEquals(0L, budget.remaining)
            val rest = ByteArray(30)
            assertEquals(30, input.read(rest))
            assertArrayEquals(bytes.copyOfRange(1, 31), rest)
            assertEquals(-1, input.read())
            assertEquals(-1, input.read(ByteArray(8)))
        }
        assertEquals(0, share.readersOpen)
    }

    @Test fun `streams and channels opened again spend the same allowance`() = runBlocking {
        share.put("file.bin", byteArrayOf(1, 2, 3))
        val budget = ReadBudget(5)
        provider.openRead(file, budget).use { input -> assertArrayEquals(byteArrayOf(1, 2, 3), input.readBytes()) }
        provider.openChannel(file, budget).use { channel ->
            val data = ByteBuffer.allocate(3)
            assertEquals(2, channel.read(data))
            assertArrayEquals(byteArrayOf(1, 2, 0), data.array())
            assertThrows(ReadBudgetExceeded::class.java) { channel.read(ByteBuffer.allocate(1)) }
        }
        assertEquals(5L, share.bytesServed)
        assertEquals(5L, budget.bytesRead)
        assertEquals(0, share.readersOpen)
    }

    @Test fun `a reconnect cannot replenish the budget or discard its final partial cache block`() = runBlocking {
        share.put("file.bin", ByteArray(BLOCK * 4) { (it % 251).toByte() })
        val budget = ReadBudget(BLOCK * 2 + 13L)
        provider.openChannel(file, budget).use { channel ->
            assertEquals(1, channel.read(ByteBuffer.allocate(1)))
            share.failNextRead = SmbFailure(StorageError.DISCONNECTED, "Tree dropped")
            val start = BLOCK * 2L + 10
            channel.position(start)
            val first = ByteBuffer.allocate(1)
            assertEquals(1, channel.read(first))
            assertEquals(HugeReader.byteAt(start), first.array()[0])
            assertEquals(0L, budget.remaining)
            assertEquals(12, channel.read(ByteBuffer.allocate(20)))
            assertThrows(ReadBudgetExceeded::class.java) { channel.read(ByteBuffer.allocate(1)) }
            assertEquals(2, share.readersOpened)
            assertEquals(BLOCK + 13L, share.bytesServed)
            assertEquals(BLOCK * 2 + 13L, budget.bytesRead)
        }
        assertEquals(0, share.readersOpen)
    }

    @Test fun `a failed request that spends the allowance does not open a replacement reader`() = runBlocking {
        share.put("file.bin", ByteArray(BLOCK * 2))
        val budget = ReadBudget(BLOCK.toLong())
        provider.openChannel(file, budget).use { channel ->
            share.failNextRead = SmbFailure(StorageError.DISCONNECTED, "Response lost")
            assertThrows(ReadBudgetExceeded::class.java) { channel.read(ByteBuffer.allocate(1)) }
            assertEquals(1, share.readersOpened)
            assertEquals(0L, share.bytesServed)
            assertEquals(BLOCK.toLong(), budget.bytesRead)
        }
        assertEquals(0, share.readersOpen)
    }

    @Test fun `cancelling blocks cached channel and stream reads without more network traffic`() = runBlocking {
        share.put("file.bin", ByteArray(BLOCK * 2))
        for (channelRead in listOf(false, true)) {
            val cancelled = AtomicBoolean()
            val budget = ReadBudget(128) { if (cancelled.get()) throw CancellationException() }
            if (channelRead) {
                provider.openChannel(file, budget).use { channel ->
                    assertEquals(1, channel.read(ByteBuffer.allocate(1)))
                    cancelled.set(true)
                    assertThrows(CancellationException::class.java) { channel.read(ByteBuffer.allocate(1)) }
                }
            } else {
                provider.openRead(file, budget).use { input ->
                    assertEquals(0, input.read())
                    cancelled.set(true)
                    assertThrows(CancellationException::class.java) { input.read() }
                    assertThrows(CancellationException::class.java) { input.read(ByteArray(1)) }
                }
            }
            assertEquals(128L, budget.bytesRead)
        }
        assertEquals(256L, share.bytesServed)
        assertEquals(0, share.readersOpen)
    }

    @Test fun `cancellation after a failed request stops before reconnecting`() {
        val cancelled = AtomicBoolean()
        var closed = false
        var reopened = false
        val source = object : SmbReader {
            override fun read(offset: Long, into: ByteArray, at: Int, length: Int): Int {
                cancelled.set(true)
                throw SmbFailure(StorageError.DISCONNECTED, "Cancelled during the request")
            }
            override fun close() { closed = true }
        }
        val budget = ReadBudget(BLOCK * 2L) { if (cancelled.get()) throw CancellationException() }
        RemoteChannel(RemoteReading(source, "cancelled.bin", budget) { reopened = true; source }, BLOCK * 3L).use { channel ->
            assertThrows(CancellationException::class.java) { channel.read(ByteBuffer.allocate(1)) }
        }
        assertTrue(closed)
        assertEquals(false, reopened)
    }

    private class HugeReader(val size: Long) : SmbReader {
        var served = 0L
        var requests = 0
        var closed = false
        override fun read(offset: Long, into: ByteArray, at: Int, length: Int): Int {
            check(!closed)
            requests++
            if (offset >= size) return -1
            val count = minOf(length.toLong(), size - offset).toInt()
            repeat(count) { into[at + it] = byteAt(offset + it) }
            served += count
            return count
        }
        override fun close() { closed = true }
        companion object { fun byteAt(position: Long): Byte = (position % 251).toByte() }
    }

    private companion object { const val BLOCK = 64 * 1024 }
}
