package com.lunaexplorer.app.ui

import androidx.compose.ui.graphics.Color
import com.lunaexplorer.core.HexBlocks
import com.lunaexplorer.core.HexLayout
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Content is a function of position. With [gated], each read waits for a permit from [gate]. */
internal class FakeHexChannel(private val length: Long, private val byteAt: (Long) -> Byte) : SeekableByteChannel {
    constructor(bytes: ByteArray) : this(bytes.size.toLong(), { bytes[it.toInt()] })

    val gate = Semaphore(0)
    @Volatile var gated = false
    @Volatile var failing = false
    val entered = AtomicInteger()
    private var position = 0L
    @Volatile private var open = true

    override fun read(dst: ByteBuffer): Int {
        if (!open) throw ClosedChannelException()
        entered.incrementAndGet()
        if (gated) gate.acquire()
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
    override fun size(): Long = length
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    override fun isOpen(): Boolean = open
    override fun close() { open = false }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HexSessionTest {
    // The session is confined to one thread, as it is to the main thread in the viewer.
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(Job() + main)
    private val layout = HexLayout(4, 16)
    private val colors = HexColors(Color.Gray, Color.LightGray, Color.Yellow, Color.Black)

    @After fun stop() {
        scope.cancel()
        main.close()
    }

    private fun data(vararg needles: Pair<Int, String>) = ByteArray(1024) { '.'.code.toByte() }.also { bytes ->
        needles.forEach { (at, needle) -> needle.toByteArray().copyInto(bytes, at) }
    }

    private fun session(channel: FakeHexChannel) = on { HexSession(HexBlocks(channel, blockSize = 64), scope, settleMillis = 0) }

    private fun <T> on(block: () -> T): T = runBlocking(main) { block() }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!on(condition)) {
            assertTrue("Timed out: $what", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }

    private fun settle() {
        Thread.sleep(150)
        on { }
    }

    // Called inside await, which is already on the session's thread.
    private fun HexSession.row(offset: Long) = rowText(offset, layout, colors).text

    private fun HexSession.found(query: String, forward: Boolean, hex: Boolean = false): HexMark? {
        on { find(query, hex, forward) }
        await("Search for $query finished") { finding == null }
        return on { mark }
    }

    @Test fun `rows fill in once their block is read`() {
        val session = session(FakeHexChannel(data(0 to "LUNA")))
        assertEquals("0000", on { session.row(0) })
        on { session.show(0, 64) }
        await("First rows read") { session.row(0).contains("4C 55 4E 41") }
        assertTrue(on { session.row(0) }.endsWith("LUNA............"))
    }

    @Test fun `a read failure shows and retry loads the block`() {
        val channel = FakeHexChannel(data(0 to "LUNA")).apply { failing = true }
        val session = session(channel)
        on { session.show(0, 64) }
        await("Failure shown") { session.readFailure != null }
        assertTrue(on { session.row(0) }.contains("??"))
        channel.failing = false
        on { session.retry() }
        await("Block read") { session.row(0).contains("4C 55 4E 41") }
        assertNull(on { session.readFailure })
    }

    @Test fun `a load overtaken by a newer range is not recorded as a failure`() {
        val channel = FakeHexChannel(data(0 to "LUNA", 512 to "FAR!")).apply { gated = true }
        val session = session(channel)
        on { session.show(0, 64) }
        await("First read in flight") { channel.entered.get() == 1 }
        on { session.show(512, 576) }
        channel.gated = false
        channel.gate.release(64)
        await("Newer range read") { session.row(512).contains("FAR!") }
        settle()
        assertNull(on { session.readFailure })
        assertTrue(on { session.row(0) }.contains("LUNA"))
    }

    @Test fun `a read that fails while it is overtaken is not recorded`() {
        val channel = FakeHexChannel(data(0 to "LUNA", 512 to "FAR!")).apply { gated = true; failing = true }
        val session = session(channel)
        on { session.show(0, 64) }
        await("First read in flight") { channel.entered.get() == 1 }
        on { session.show(512, 576) }
        channel.gate.release()
        await("Newer range read in flight") { channel.entered.get() == 2 }
        channel.failing = false
        channel.gated = false
        channel.gate.release(64)
        await("Newer range read") { session.row(512).contains("FAR!") }
        settle()
        assertNull(on { session.readFailure })
    }

    @Test fun `a stopped search writes no result and clears its progress`() {
        val channel = FakeHexChannel(data(700 to "needle")).apply { gated = true }
        val session = session(channel)
        on { session.find("needle", hex = false, forward = true) }
        await("Search read in flight") { channel.entered.get() == 1 }
        assertNotNull(on { session.finding })
        on { session.stopFinding() }
        assertNull(on { session.finding })
        channel.gate.release()
        settle()
        assertNull(on { session.mark })
        assertNull(on { session.findStatus })
        assertNull(on { session.finding })
    }

    @Test fun `a new search is not cleared by the one it replaced`() {
        val channel = FakeHexChannel(data(700 to "needle")).apply { gated = true }
        val session = session(channel)
        on { session.find("absent", hex = false, forward = true) }
        await("First search read in flight") { channel.entered.get() == 1 }
        on { session.find("needle", hex = false, forward = true) }
        channel.gate.release()
        await("Second search read in flight") { channel.entered.get() == 2 }
        settle()
        assertNotNull("The replaced search must leave its successor's progress alone", on { session.finding })
        channel.gate.release()
        await("Found") { session.mark == HexMark(700, 6) }
        await("Progress cleared") { session.finding == null }
    }

    @Test fun `previous from a match finds the one before it, not the same one`() {
        val session = session(FakeHexChannel(data(100 to "ab", 200 to "ab", 300 to "ab")))
        assertEquals(HexMark(100, 2), session.found("ab", forward = true))
        assertEquals(HexMark(200, 2), session.found("ab", forward = true))
        assertEquals(HexMark(100, 2), session.found("ab", forward = false))
        assertNull(on { session.findStatus })
        assertEquals(HexMark(300, 2), session.found("ab", forward = false))
        assertNotNull("Going round the start is said", on { session.findStatus })
    }

    @Test fun `a search starts from the top of the view once the match has been scrolled away`() {
        val session = session(FakeHexChannel(data(100 to "ab", 200 to "ab", 300 to "ab")))
        assertEquals(HexMark(100, 2), session.found("ab", forward = true))
        on { session.show(256, 320) }
        assertEquals(HexMark(300, 2), session.found("ab", forward = true))
        on { session.show(208, 272) }
        assertEquals(HexMark(200, 2), session.found("ab", forward = false))
    }

    @Test fun `a match scrolled away and back is still where the next search carries on from`() {
        val session = session(FakeHexChannel(data(100 to "ab", 200 to "ab", 300 to "ab")))
        on { session.show(64, 128) }
        assertEquals(HexMark(100, 2), session.found("ab", forward = true))
        on { session.show(256, 320) }
        on { session.show(64, 128) }
        assertEquals(HexMark(200, 2), session.found("ab", forward = true))
    }

    @Test fun `find right after go to matches at the target offset`() {
        val session = session(FakeHexChannel(data(100 to "ab", 300 to "ab")))
        assertNull(on { session.goTo("64", hex = true) })
        assertEquals(HexMark(100, 1), on { session.mark })
        assertEquals(HexMark(100, 2), session.found("ab", forward = true))
        assertNull(on { session.findStatus })

        assertNull(on { session.goTo("300", hex = false) })
        assertEquals(HexMark(100, 2), session.found("ab", forward = false))
    }

    @Test fun `go to while finding leaves the view at the offset`() {
        val channel = FakeHexChannel(data(700 to "needle")).apply { gated = true }
        val session = session(channel)
        val reveals = ArrayList<Long>()
        val collector = scope.launch { session.reveals.collect { reveals += it } }
        on { session.find("needle", hex = false, forward = true) }
        await("Search read in flight") { channel.entered.get() == 1 }
        assertNull(on { session.goTo("10", hex = true) })
        assertNull(on { session.finding })
        channel.gate.release()
        settle()
        assertEquals(HexMark(0x10, 1), on { session.mark })
        assertEquals(listOf(0x10L), on { reveals.toList() })
        collector.cancel()
    }

    @Test fun `an offset that is no number or lies past the end is refused and nothing moves`() {
        val session = session(FakeHexChannel(data()))
        assertNotNull(on { session.goTo("12g", hex = true) })
        assertNotNull(on { session.goTo("400", hex = true) })
        assertNull(on { session.mark })
        assertNull(on { session.goTo("3FF", hex = true) })
        assertEquals(HexMark(0x3FF, 1), on { session.mark })
    }

    @Test fun `a pattern that is not whole bytes is refused without reading`() {
        val channel = FakeHexChannel(data(100 to "ab"))
        val session = session(channel)
        for (query in listOf("6", "6g", "61 6")) {
            on { session.find(query, hex = true, forward = true) }
            assertNotNull(query, on { session.findError })
            assertNull(on { session.finding })
        }
        on { session.find("ab".repeat(40_000), hex = false, forward = true) }
        assertNotNull(on { session.findError })
        assertEquals(0, channel.entered.get())
        assertEquals(HexMark(100, 2), session.found("61 62", forward = true, hex = true))
        assertNull(on { session.findError })
    }

    @Test fun `a mark is painted across the pairs and the characters of every row it covers`() {
        val session = session(FakeHexChannel(data(14 to "abcd")))
        on { session.show(0, 64) }
        await("Rows read") { session.row(0).contains("61 62") }
        assertEquals(HexMark(14, 4), session.found("abcd", forward = true))
        fun marked(offset: Long) = on {
            session.rowText(offset, layout, colors).let { row ->
                row.spanStyles.filter { it.item.background == colors.markBackground }.map { row.text.substring(it.start, it.end) }
            }
        }
        assertEquals(listOf("61 62", "ab"), marked(0))
        assertEquals(listOf("63 64", "cd"), marked(16))
        assertTrue(marked(32).isEmpty())
    }
}
