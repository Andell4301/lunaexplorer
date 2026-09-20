package com.lunaexplorer.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HexTest {
    /** Content is a function of position, so a file far past Int costs no memory. */
    private class FakeChannel(var length: Long, private val byteAt: (Long) -> Byte) : SeekableByteChannel {
        constructor(bytes: ByteArray) : this(bytes.size.toLong(), { bytes[it.toInt()] })

        val reads = ArrayList<Pair<Long, Int>>()
        var failing = false
        /** What size() answers, where that is not the length of the data. */
        var reported: Long? = null
        private var position = 0L
        private var open = true

        override fun read(dst: ByteBuffer): Int {
            if (!open) throw ClosedChannelException()
            reads += position to dst.remaining()
            if (failing) throw IOException("Connection lost")
            if (position >= length) return -1
            val count = minOf(dst.remaining().toLong(), length - position).toInt()
            for (index in 0 until count) dst.put(byteAt(position + index))
            position += count
            return count
        }

        override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
        override fun position(): Long = position
        override fun position(newPosition: Long): SeekableByteChannel = apply { position = newPosition }
        override fun size(): Long = reported ?: length
        override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
        override fun isOpen(): Boolean = open
        override fun close() { open = false }
    }

    private fun counting(size: Int) = FakeChannel(ByteArray(size) { it.toByte() })

    private fun find(blocks: HexBlocks, pattern: String, from: Long, forward: Boolean, chunk: Int = 16): HexStep {
        val search = HexSearch(blocks, pattern.toByteArray(), from, forward, chunk)
        while (true) search.step().let { if (it !is HexStep.More) return it }
    }

    private fun text(size: Int, vararg needles: Pair<Int, String>): ByteArray = ByteArray(size) { '.'.code.toByte() }.also { bytes ->
        needles.forEach { (at, needle) -> needle.toByteArray().copyInto(bytes, at) }
    }

    @Test fun `a row shows its offset, hex pairs and printable ASCII, padded when short`() {
        val narrow = HexLayout(4, 8)
        val bytes = byteArrayOf(0x4C, 0x55, 0x4E, 0x41, 0x00, 0xFF.toByte(), 0x20, 0x7E)
        assertEquals("0010  4C 55 4E 41 00 FF 20 7E  LUNA.. ~", narrow.row(0x10, bytes, 8))
        assertEquals("0018  4C 55 4E" + " ".repeat(15) + "  LUN", narrow.row(0x18, bytes, 3))
        assertEquals(39, narrow.columns)
        assertEquals(6..7, narrow.hexRange(0))
        assertEquals(27..28, narrow.hexRange(7))
        assertEquals(31, narrow.asciiAt(0))

        val wide = HexLayout(8, 16)
        val aligned = wide.row(0xFFFF_FF00, ByteArray(16) { (0x41 + it).toByte() }, 16)
        assertEquals("FFFFFF00  41 42 43 44 45 46 47 48  49 4A 4B 4C 4D 4E 4F 50  ABCDEFGHIJKLMNOP", aligned)
        assertEquals("49", aligned.substring(wide.hexRange(8).first, wide.hexRange(8).last + 1))
        assertEquals('I', aligned[wide.asciiAt(8)])
        assertEquals(aligned.length, wide.columns)

        assertEquals("0020", narrow.unread(0x20, 8, failed = false))
        assertEquals("0020  ?? ?? ??", narrow.unread(0x20, 3, failed = true))
        assertEquals("0004  41 42" + " ".repeat(8) + "AB", HexLayout(4, 4).row(4, byteArrayOf(0x41, 0x42), 2))
    }

    @Test fun `the offset column widens with the file`() {
        assertEquals(4, HexLayout.offsetDigits(0))
        assertEquals(4, HexLayout.offsetDigits(0x1_0000))
        assertEquals(6, HexLayout.offsetDigits(0x1_0001))
        assertEquals(8, HexLayout.offsetDigits(0x1_0000_0000))
        assertEquals(10, HexLayout.offsetDigits(0x1_0000_0001))
        assertEquals(16, HexLayout.offsetDigits(Long.MAX_VALUE))
    }

    @Test fun `bytes per row is the widest power of two that fits, never under four`() {
        assertEquals(64, HexLayout.fit(1_000, 8))
        assertEquals(16, HexLayout.fit(HexLayout(8, 16).columns, 8))
        assertEquals(8, HexLayout.fit(HexLayout(8, 16).columns - 1, 8))
        assertEquals(8, HexLayout.fit(43, 8))
        assertEquals(4, HexLayout.fit(42, 8))
        assertEquals(4, HexLayout.fit(27, 8))
        assertEquals(4, HexLayout.fit(10, 8))
    }

    @Test fun `an offset reads as hex or decimal, and 0x forces hex`() {
        assertEquals(0x100L, parseOffset("100", hex = true))
        assertEquals(100L, parseOffset("100", hex = false))
        assertEquals(0x100L, parseOffset("0x100", hex = false))
        assertEquals(0xFFL, parseOffset(" 0Xff ", hex = true))
        assertEquals(Long.MAX_VALUE, parseOffset("7FFFFFFFFFFFFFFF", hex = true))
        for (text in listOf("", " ", "-1", "+1", "0x", "12g", "1 2", "1_000", "FFFFFFFFFFFFFFFF", "٣")) {
            assertNull(text, parseOffset(text, hex = true))
        }
        assertNull(parseOffset("ff", hex = false))
        assertNull(parseOffset("99999999999999999999", hex = false))
    }

    @Test fun `a hex pattern ignores spaces and rejects odd digits and other characters`() {
        assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
            (parseHexPattern(" DE ad\tBEef\n") as HexPattern.Bytes).bytes)
        assertArrayEquals(byteArrayOf(0x00), (parseHexPattern("00") as HexPattern.Bytes).bytes)
        assertEquals(0, (parseHexPattern("  ") as HexPattern.Bytes).bytes.size)
        assertEquals(HexPattern.Invalid.ODD, parseHexPattern("DEA"))
        assertEquals(HexPattern.Invalid.NOT_HEX, parseHexPattern("0xDE"))
        assertEquals(HexPattern.Invalid.NOT_HEX, parseHexPattern("DG"))
    }

    @Test fun `a block is read once, and the least recently used goes first`() {
        val channel = counting(64)
        val blocks = HexBlocks(channel, blockSize = 16, kept = 2)
        blocks.load(0)
        blocks.load(0)
        assertEquals(listOf(0L to 16), channel.reads)
        blocks.load(1)
        assertEquals(2, blocks.peek(0, ByteArray(2), 2))
        blocks.load(2)
        assertTrue(blocks.has(0))
        assertFalse(blocks.has(1))
        assertTrue(blocks.has(2))
        assertEquals(listOf(0L to 16, 16L to 16, 32L to 16), channel.reads)
    }

    @Test fun `peek answers only from loaded blocks and stops at the end of the file`() {
        val blocks = HexBlocks(counting(40), blockSize = 16)
        val into = ByteArray(8)
        assertEquals(-1, blocks.peek(16, into, 8))
        blocks.load(1)
        assertEquals(8, blocks.peek(24, into, 8))
        assertArrayEquals(ByteArray(8) { (24 + it).toByte() }, into)
        assertEquals(-1, blocks.peek(28, into, 8))
        blocks.load(2)
        assertEquals(8, blocks.peek(28, into, 8))
        assertEquals(28, into[0].toInt())
        assertEquals(35, into[7].toInt())
        assertEquals(4, blocks.peek(36, into, 8))
        assertEquals(0, blocks.peek(40, into, 8))
    }

    @Test fun `a file that ends early shrinks instead of failing`() {
        val channel = counting(40)
        val blocks = HexBlocks(channel, blockSize = 16)
        channel.length = 20
        blocks.load(1)
        assertEquals(20, blocks.size)
        val into = ByteArray(8)
        assertEquals(4, blocks.peek(16, into, 8))
        assertEquals(16, into[0].toInt())
        blocks.load(2)
        assertEquals(0, blocks.peek(32, into, 8))
    }

    @Test fun `a read that stops early while the file is still as long is an error, not a shorter file`() {
        val channel = counting(40)
        val blocks = HexBlocks(channel, blockSize = 16)
        channel.length = 20
        channel.reported = 40
        assertThrows(IOException::class.java) { blocks.load(1) }
        assertEquals(40, blocks.size)
        assertFalse(blocks.has(1))
        assertThrows(IOException::class.java) { blocks.read(0, ByteArray(40), 40) }
    }

    @Test fun `a failed read leaves the cache usable and the block loadable later`() {
        val channel = counting(64)
        val blocks = HexBlocks(channel, blockSize = 16)
        blocks.load(0)
        channel.failing = true
        assertThrows(IOException::class.java) { blocks.load(1) }
        assertFalse(blocks.has(1))
        assertEquals(4, blocks.peek(4, ByteArray(4), 4))
        channel.failing = false
        blocks.load(1)
        val into = ByteArray(4)
        assertEquals(4, blocks.peek(16, into, 4))
        assertEquals(16, into[0].toInt())
    }

    @Test fun `a match straddling two chunks is found`() {
        val blocks = HexBlocks(FakeChannel(text(64, 14 to "match")))
        assertEquals(HexStep.Found(14, wrapped = false), find(blocks, "match", 0, forward = true))
        assertEquals(HexStep.Found(14, wrapped = false), find(blocks, "match", 59, forward = false))
        assertEquals(HexStep.Found(14, wrapped = false), find(blocks, "match", 30, forward = false))
    }

    @Test fun `search wraps in both directions and says so`() {
        val blocks = HexBlocks(FakeChannel(text(64, 5 to "one", 40 to "one")))
        assertEquals(HexStep.Found(40, wrapped = false), find(blocks, "one", 6, forward = true))
        assertEquals(HexStep.Found(5, wrapped = true), find(blocks, "one", 41, forward = true))
        assertEquals(HexStep.Found(5, wrapped = false), find(blocks, "one", 39, forward = false))
        assertEquals(HexStep.Found(40, wrapped = true), find(blocks, "one", 4, forward = false))
        assertEquals(HexStep.None, find(blocks, "two", 20, forward = true))
        assertEquals(HexStep.None, find(blocks, "two", 20, forward = false))
    }

    @Test fun `a search checks where it starts first, in either direction`() {
        val blocks = HexBlocks(FakeChannel(text(64, 20 to "abab")))
        assertEquals(HexStep.Found(20, wrapped = false), find(blocks, "ab", 20, forward = true))
        assertEquals(HexStep.Found(22, wrapped = false), find(blocks, "ab", 21, forward = true))
        assertEquals(HexStep.Found(22, wrapped = false), find(blocks, "ab", 22, forward = false))
        assertEquals(HexStep.Found(20, wrapped = false), find(blocks, "ab", 21, forward = false))
    }

    @Test fun `a match at the first and at the last byte is found`() {
        val blocks = HexBlocks(FakeChannel(text(64, 0 to "edge", 60 to "edge")))
        assertEquals(HexStep.Found(60, wrapped = false), find(blocks, "edge", 1, forward = true))
        assertEquals(HexStep.Found(0, wrapped = true), find(blocks, "edge", 61, forward = true))
        assertEquals(HexStep.Found(0, wrapped = false), find(blocks, "edge", 59, forward = false))
        assertEquals(HexStep.Found(60, wrapped = true), find(blocks, "edge", -1, forward = false))
    }

    @Test fun `a start past the last place a match could begin goes straight to the wrapped part`() {
        val blocks = HexBlocks(FakeChannel(text(64, 10 to "tail")))
        assertEquals(HexStep.Found(10, wrapped = true), find(blocks, "tail", 62, forward = true))
        assertEquals(HexStep.Found(10, wrapped = true), find(blocks, "tail", 64, forward = true))
        assertEquals(HexStep.Found(10, wrapped = false), find(blocks, "tail", 63, forward = false))
    }

    @Test fun `a pattern longer than the file, or an empty file, finds nothing without reading`() {
        val short = FakeChannel(text(4))
        assertEquals(HexStep.None, find(HexBlocks(short), "longer", 0, forward = true))
        assertEquals(HexStep.None, find(HexBlocks(short), "", 0, forward = true))
        val empty = FakeChannel(ByteArray(0))
        assertEquals(HexStep.None, find(HexBlocks(empty), "a", 0, forward = false))
        assertTrue(short.reads.isEmpty())
        assertTrue(empty.reads.isEmpty())
    }

    @Test fun `the only match is found again after a full wrap`() {
        val blocks = HexBlocks(FakeChannel(text(64, 30 to "sole")))
        assertEquals(HexStep.Found(30, wrapped = true), find(blocks, "sole", 31, forward = true))
        assertEquals(HexStep.Found(30, wrapped = true), find(blocks, "sole", 29, forward = false))
    }

    @Test fun `no read is larger than one chunk, past four gigabytes`() {
        val marker = "MARK".toByteArray()
        val at = 5L shl 30
        val channel = FakeChannel(6L shl 30) { position ->
            if (position >= at && position < at + marker.size) marker[(position - at).toInt()] else 0
        }
        val blocks = HexBlocks(channel)
        val search = HexSearch(blocks, marker, at - HEX_SEARCH_CHUNK, forward = true)
        var step = search.step()
        while (step is HexStep.More) step = search.step()
        assertEquals(HexStep.Found(at, wrapped = false), step)
        assertTrue(channel.reads.all { it.second <= HEX_SEARCH_CHUNK })
        assertTrue(channel.reads.size <= 3)

        val block = at / HEX_BLOCK
        blocks.load(block)
        val into = ByteArray(4)
        assertEquals(4, blocks.peek(at, into, 4))
        assertArrayEquals(marker, into)
        assertEquals(-1, blocks.peek(at - 1, into, 4))
    }

    @Test fun `reads after close fail as closed`() {
        val blocks = HexBlocks(counting(64), blockSize = 16)
        blocks.load(0)
        blocks.close()
        assertThrows(ClosedChannelException::class.java) { blocks.load(1) }
        assertThrows(ClosedChannelException::class.java) { blocks.read(0, ByteArray(4), 4) }
        assertThrows(ClosedChannelException::class.java) { HexSearch(blocks, byteArrayOf(1), 0, forward = true).step() }
    }

    @Test fun `a file past the row limit is shown one window at a time`() {
        assertEquals(HexWindow(0, 0), HexWindow.around(0, 0, 16))
        assertEquals(HexWindow(0, 7), HexWindow.around(50, 100, 16))
        val limit = HEX_MAX_ROWS.toLong() * 16
        assertEquals(HexWindow(0, HEX_MAX_ROWS), HexWindow.around(limit - 1, limit, 16))

        val size = limit * 3 + 5
        val first = HexWindow.around(0, size, 16)
        assertEquals(HexWindow(0, HEX_MAX_ROWS), first)
        assertNull(first.earlier(size, 16))

        val target = limit + limit / 2
        val middle = HexWindow.around(target, size, 16)
        assertEquals(0, middle.base % 16)
        assertTrue(middle.base + limit / 8 <= target && target + limit / 8 < middle.base + middle.rows.toLong() * 16)
        assertEquals(middle, HexWindow.around(target + 16, size, 16))
        assertEquals(middle, HexWindow.at(middle.base, size, 16))

        fun HexWindow.end() = base + rows.toLong() * 16
        val last = HexWindow.around(size - 1, size, 16)
        assertTrue(last.rows > HEX_MAX_ROWS / 2)
        assertTrue(last.end() >= size)
        assertNull(last.later(size, 16))

        var window = first
        var steps = 0
        while (true) {
            val next = window.later(size, 16) ?: break
            assertTrue(next.base > window.base && next.base < window.end())
            val back = next.earlier(size, 16)!!
            assertTrue(back.base < next.base && next.base < back.end())
            window = next
            steps++
        }
        assertEquals(last, window)
        assertTrue(steps in 1..16)
    }
}
