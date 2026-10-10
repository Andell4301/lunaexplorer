package com.lunaexplorer.app.ui

import com.lunaexplorer.app.terminal.TerminalConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TerminalSessionTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `connection waits for the measured terminal size`() = runTest {
        val parent = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val terminal = FakeConnection()
        val opened = CompletableDeferred<Pair<Int, Int>>()
        try {
            val session = TerminalSession(parent, "test", null, {}) { columns, rows ->
                opened.complete(columns to rows)
                terminal
            }
            runCurrent()
            assertFalse(opened.isCompleted)
            session.resize(47, 30)
            runCurrent()
            assertTrue(opened.isCompleted)
            assertEquals(47 to 30, opened.await())
            session.close()
        } finally { parent.cancel(); terminal.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a missing viewport fails startup without opening a connection`() = runTest {
        val parent = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val terminal = FakeConnection()
        val opened = CompletableDeferred<Unit>()
        try {
            val session = TerminalSession(parent, "test", null, {}) { _, _ ->
                opened.complete(Unit)
                terminal
            }
            val connecting = session.status.value
            runCurrent()
            advanceTimeBy(60_000)
            session.running.first { !it }
            assertFalse(opened.isCompleted)
            assertTrue(session.status.value != connecting)
        } finally { parent.cancel(); terminal.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `closing before a viewport arrives prevents a later connection`() = runTest {
        val parent = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val terminal = FakeConnection()
        val opened = CompletableDeferred<Unit>()
        try {
            val session = TerminalSession(parent, "test", null, {}) { _, _ ->
                opened.complete(Unit)
                terminal
            }
            runCurrent()
            session.close()
            session.resize(47, 30)
            runCurrent()
            assertFalse(opened.isCompleted)
        } finally { parent.cancel(); terminal.close() }
    }

    @Test fun `output and pending stderr are rendered before an exited connection is released`() = runBlocking {
        val terminal = FakeConnection(withError = true)
        terminal.input.emit("output".toByteArray())
        terminal.error!!.emit("error".toByteArray())
        terminal.input.end()
        val rendered = ByteArrayOutputStream()
        val parent = parent()
        try {
            val session = start(parent, { synchronized(rendered) { rendered.write(it) } }) { terminal }
            finished(session)
            val text = rendered.toString("UTF-8")
            assertTrue(text.contains("output"))
            assertTrue(text.contains("error"))
            assertEquals(1, terminal.closes.get())
            assertFalse(session.running.value)
        } finally { parent.cancel(); terminal.close() }
    }

    @Test fun `writer failure closes the transport and unblocks both readers`() = runBlocking {
        val terminal = FakeConnection(withError = true, rejectWrites = true)
        val parent = parent()
        try {
            val session = start(parent) { terminal }
            assertTrue(terminal.input.reading.await(5, TimeUnit.SECONDS))
            assertTrue(terminal.error!!.reading.await(5, TimeUnit.SECONDS))
            session.input(byteArrayOf(3))
            finished(session)
            assertEquals(1, terminal.closes.get())
        } finally { parent.cancel(); terminal.close() }
    }

    @Test fun `a resize failure closes a blocked reader`() = runBlocking {
        val terminal = FakeConnection(rejectResize = true)
        val parent = parent()
        try {
            val session = start(parent) { terminal }
            finished(session)
            assertEquals(1, terminal.closes.get())
        } finally { parent.cancel(); terminal.close() }
    }

    @Test fun `canceling the owner closes a transport blocked in input`() = runBlocking {
        val terminal = FakeConnection(withError = true)
        val parent = parent()
        try {
            val session = start(parent) { terminal }
            assertTrue(terminal.input.reading.await(5, TimeUnit.SECONDS))
            parent.cancel()
            finished(session)
            assertEquals(1, terminal.closes.get())
        } finally { parent.cancel(); terminal.close() }
    }

    @Test fun `closing while connecting cancels the opener`() = runBlocking {
        val parent = parent()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        try {
            val session = start(parent) {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            withTimeout(5_000) { started.await() }
            session.close()
            withTimeout(5_000) { cancelled.await() }
            finished(session)
        } finally { parent.cancel() }
    }

    @Test fun `closing while a connection returns still releases it once`() = runBlocking {
        val terminal = FakeConnection()
        val parent = parent()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val session = start(parent) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                terminal
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            session.close()
            release.countDown()
            assertTrue(terminal.closed.await(5, TimeUnit.SECONDS))
            assertEquals(1, terminal.closes.get())
            assertEquals(1L, terminal.input.reading.count)
        } finally { release.countDown(); parent.cancel(); terminal.close() }
    }

    @Test fun `input is copied before the caller can reuse its byte array`() = runBlocking {
        val terminal = FakeConnection()
        val parent = parent()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val session = start(parent) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                terminal
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val bytes = byteArrayOf(27, 91, 65)
            session.input(bytes)
            bytes.fill(0)
            release.countDown()
            assertTrue(terminal.wrote.await(5, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(27, 91, 65), terminal.written.toByteArray())
            session.close()
        } finally { release.countDown(); parent.cancel(); terminal.close() }
    }

    @Test fun `a render failure releases the reader and transport`() = runBlocking {
        val terminal = FakeConnection()
        terminal.input.emit(byteArrayOf(65))
        val parent = parent()
        try {
            val session = start(parent, { throw IOException("render failed") }) { terminal }
            finished(session)
            assertEquals(1, terminal.closes.get())
        } finally { parent.cancel(); terminal.close() }
    }

    @Test fun `an external renderer failure closes an idle connection immediately`() = runBlocking {
        val terminal = FakeConnection()
        val parent = parent()
        try {
            val session = start(parent) { terminal }
            assertTrue(terminal.input.reading.await(5, TimeUnit.SECONDS))
            session.fail("renderer failed")
            assertTrue(terminal.closed.await(5, TimeUnit.SECONDS))
            finished(session)
            assertEquals("renderer failed", session.status.value)
            assertEquals(1, terminal.closes.get())
        } finally { parent.cancel(); terminal.close() }
    }

    @Test fun `an already canceled owner never starts a connection`() = runBlocking {
        val parent = parent().also { it.cancel() }
        val opened = AtomicInteger()
        val session = start(parent) {
            opened.incrementAndGet()
            FakeConnection()
        }
        finished(session)
        assertEquals(0, opened.get())
    }

    private fun parent() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun start(
        parent: CoroutineScope,
        render: suspend (ByteArray) -> Unit = {},
        connect: suspend () -> TerminalConnection,
    ) = TerminalSession(parent, "test", null, render) { _, _ -> connect() }.apply { resize(80, 24) }

    private suspend fun finished(session: TerminalSession) {
        withTimeout(5_000) { session.running.first { !it } }
    }

    private class FakeConnection(
        withError: Boolean = false,
        private val rejectWrites: Boolean = false,
        private val rejectResize: Boolean = false,
    ) : TerminalConnection {
        override val input = BlockingInput()
        override val error = if (withError) BlockingInput() else null
        val closes = AtomicInteger()
        val closed = CountDownLatch(1)
        val wrote = CountDownLatch(1)
        val written = ByteArrayOutputStream()
        override val output = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (rejectWrites) throw IOException("write failed")
                written.write(bytes, offset, length)
                wrote.countDown()
            }
        }
        override val exitStatus: Int get() = 0
        override fun resize(columns: Int, rows: Int) {
            if (rejectResize) throw IOException("resize failed")
        }
        override fun close() {
            closes.incrementAndGet()
            input.end()
            error?.end()
            closed.countDown()
        }
    }

    private class BlockingInput : InputStream() {
        val reading = CountDownLatch(1)
        private val chunks = LinkedBlockingQueue<ByteArray>()
        private var current = byteArrayOf()
        private var offset = 0
        private var ended = false
        fun emit(bytes: ByteArray) { chunks.put(bytes) }
        fun end() { chunks.put(byteArrayOf()) }
        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
        }
        override fun read(bytes: ByteArray, start: Int, length: Int): Int {
            if (length == 0) return 0
            reading.countDown()
            if (ended) return -1
            if (offset == current.size) {
                current = chunks.take()
                offset = 0
                if (current.isEmpty()) { ended = true; return -1 }
            }
            val count = minOf(length, current.size - offset)
            current.copyInto(bytes, start, offset, offset + count)
            offset += count
            return count
        }
    }
}
