package com.lunaexplorer.app.storage

import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.MemoryStorageProvider
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.StorageProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ViewerFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val cache get() = temporary.root
    private val entry = Entry(NodeRef("remote", "opaque-document"), "Résumé\u200b.docx", directory = false)
    private val memory = MemoryStorageProvider("remote")

    private fun provider(open: () -> InputStream): StorageProvider = object : StorageProvider by memory {
        override suspend fun openRead(ref: NodeRef): InputStream = open()
    }

    private fun stagedFiles(): List<File> = File(cache, "viewers").listFiles().orEmpty().toList()

    @Test fun `staging copies provider bytes to distinct seekable files and closes each source`() = runBlocking {
        val data = "A remote document with Unicode 日本語".toByteArray()
        var closed = 0
        val provider = provider {
            object : ByteArrayInputStream(data) {
                override fun close() { closed++; super.close() }
            }
        }
        val first = ViewerFiles.stage(cache, provider, entry, 1024).getOrThrow()
        val second = ViewerFiles.stage(cache, provider, entry, 1024).getOrThrow()
        assertArrayEquals(data, first.readBytes())
        assertArrayEquals(data, second.readBytes())
        assertNotEquals(first.absolutePath, second.absolutePath)
        assertEquals(2, closed)
        assertEquals(2, stagedFiles().size)
        first.delete()
        second.delete()
        Unit
    }

    @Test fun `known oversize is rejected without opening the provider`() = runBlocking {
        var opened = false
        val result = ViewerFiles.stage(cache, provider { opened = true; byteArrayOf(1).inputStream() },
            entry.copy(size = 1025), 1024)
        assertTrue(result.isFailure)
        assertFalse(opened)
        assertTrue(stagedFiles().isEmpty())
    }

    @Test fun `a viewer can refuse an oversize file in its own words`() = runBlocking {
        val result = ViewerFiles.stage(cache, provider { ByteArrayInputStream(ByteArray(32)) }, entry, 16, "Too large for this viewer")
        assertEquals("Too large for this viewer", result.exceptionOrNull()?.message)
    }

    @Test fun `a copy left behind for a day goes when the next one is staged`() = runBlocking {
        val directory = File(cache, "viewers").apply { mkdirs() }
        val abandoned = File(directory, "document-old.bin").apply { writeText("old") }
        val inUse = File(directory, "document-new.bin").apply { writeText("new") }
        abandoned.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
        val staged = ViewerFiles.stage(cache, provider { ByteArrayInputStream(ByteArray(8)) }, entry, 1024).getOrThrow()
        assertFalse(abandoned.exists())
        assertTrue(inUse.exists())
        assertTrue(staged.exists())
    }

    @Test fun `unknown or dishonest size cannot exceed the streaming bound`() = runBlocking {
        var closed = 0
        val provider = provider {
            object : ByteArrayInputStream(ByteArray(32)) {
                override fun close() { closed++; super.close() }
            }
        }
        for (reported in listOf(null, 2L)) {
            val result = ViewerFiles.stage(cache, provider, entry.copy(size = reported), 16)
            assertTrue(result.isFailure)
            assertTrue(stagedFiles().isEmpty())
        }
        assertEquals(2, closed)
    }

    @Test fun `read failure closes the stream and removes partial data`() = runBlocking {
        var closed = false
        var reads = 0
        val provider = provider {
            object : InputStream() {
                override fun read(): Int = error("Bulk read expected")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (reads++ > 0) throw IOException("Network disconnected")
                    buffer[offset] = 1
                    return 1
                }
                override fun close() { closed = true }
            }
        }
        assertTrue(ViewerFiles.stage(cache, provider, entry, 1024).isFailure)
        assertTrue(closed)
        assertTrue(stagedFiles().isEmpty())
    }

    @Test fun `cancellation during a copy is rethrown after closing and deleting the stage`() = runBlocking {
        val owner = Job()
        var closed = false
        val provider = provider {
            object : ByteArrayInputStream(ByteArray(32)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val count = super.read(buffer, offset, length)
                    owner.cancel()
                    return count
                }
                override fun close() { closed = true; super.close() }
            }
        }
        val error = runCatching { withContext(owner) { ViewerFiles.stage(cache, provider, entry, 1024) } }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(closed)
        assertTrue(stagedFiles().isEmpty())
    }

    @Test fun `failure to open a provider and zero progress both clean up their stage`() = runBlocking {
        assertTrue(ViewerFiles.stage(cache, provider { throw IOException("Missing document") }, entry, 1024).isFailure)
        assertTrue(stagedFiles().isEmpty())
        val stalled = provider { object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        } }
        assertTrue(ViewerFiles.stage(cache, stalled, entry, 1024).isFailure)
        assertTrue(stagedFiles().isEmpty())
    }
}
