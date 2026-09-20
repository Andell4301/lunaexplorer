package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class BrowserFilesReadTest {
    @get:Rule val harness = BrowserViewModelHarness()
    private val memory = MemoryStorageProvider("reader")
    private val ref = memory.file(memory.root, "notes.txt", "notes")
    private val entry = Entry(ref, "notes.txt", directory = false)

    @Test fun `a cancelled provider read escapes instead of becoming a failed result`() {
        val cancelled = CancellationException("Read cancelled")
        harness.graph.providers.register(object : StorageProvider by memory {
            override suspend fun openRead(ref: NodeRef): InputStream = throw cancelled
        })
        assertThrows(CancellationException::class.java) {
            runBlocking { harness.viewModel.files.readBytes(entry, 100) }
        }
    }

    @Test fun `cancellation between reads closes the stream without reading the rest`() {
        var reads = 0
        var closed = false
        harness.graph.providers.register(object : StorageProvider by memory {
            override suspend fun openRead(ref: NodeRef): InputStream {
                val job = currentCoroutineContext()[Job]!!
                return object : InputStream() {
                    override fun read(): Int = error("Use bulk reads")
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        reads++
                        if (reads > 1) return -1
                        bytes[offset] = 1
                        job.cancel()
                        return 1
                    }
                    override fun close() { closed = true }
                }
            }
        })

        assertThrows(CancellationException::class.java) {
            runBlocking { harness.viewModel.files.readBytes(entry, 100) }
        }
        assertTrue(closed)
        assertEquals(1, reads)
    }

    @Test fun `the byte limit is enforced even when listing metadata is absent or stale`() {
        for (size in listOf(null, 1L)) {
            var closed = false
            harness.graph.providers.register(object : StorageProvider by memory {
                override suspend fun openRead(ref: NodeRef): InputStream =
                    object : ByteArrayInputStream(ByteArray(5)) {
                        override fun close() { closed = true; super.close() }
                    }
            })
            val result = runBlocking { harness.viewModel.files.readBytes(entry.copy(size = size), 4) }
            assertEquals(StorageError.UNSUPPORTED, (result.exceptionOrNull() as StorageException).reason)
            assertTrue(closed)
        }
    }
}
