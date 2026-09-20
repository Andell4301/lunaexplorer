package com.lunaexplorer.core

import java.io.InterruptedIOException
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ArchiveCancellationTest {
    @Test fun `cancelled size discovery never starts a fallback archive read`() = runBlocking {
        val members = linkedMapOf("note.txt" to bytesOf("body"))
        val sources = listOf(
            ByteSource("bundle.zip", jdkZipBytes(members), seekable = false),
            ByteSource("bundle.tar.gz", tarBytes(ArchiveFormat.TAR_GZ, members), seekable = false),
            ByteSource("bundle.7z", sevenZBytes(members), seekable = false),
        )
        for (source in sources) {
            val cancelled = CancellationException(source.name)
            val provider = object : StorageProvider by source {
                override suspend fun stat(ref: NodeRef): Entry = throw cancelled
            }
            try {
                archiveProvider(provider).open(source.ref, source.name)
                fail("Cancellation was swallowed for ${source.name}")
            } catch (failure: CancellationException) {
                assertEquals(cancelled.message, failure.message)
            }
            assertEquals(0, source.streamOpens)
            assertEquals(0, source.openHandles)
        }
    }

    @Test fun `cancelled capability discovery closes the index without caching a read-only archive`() = runBlocking {
        val source = ByteSource("bundle.zip", jdkZipBytes(linkedMapOf("note.txt" to bytesOf("body"))))
        val folder = NodeRef(source.id, "parent")
        val cancelled = CancellationException("Stopped")
        var interrupted = true
        val provider = object : StorageProvider by source {
            override suspend fun parentOf(ref: NodeRef): NodeRef {
                if (interrupted) throw cancelled
                return folder
            }
            override suspend fun stat(ref: NodeRef): Entry = if (ref == folder) {
                Entry(folder, "parent", directory = true, capabilities = setOf(Capability.CREATE, Capability.ATOMIC_REPLACE))
            } else source.stat(ref)
        }
        val archive = archiveProvider(provider)

        try {
            archive.open(source.ref, source.name)
            fail("Cancellation was swallowed")
        } catch (failure: CancellationException) {
            assertEquals(cancelled.message, failure.message)
        }
        assertEquals(0, source.openHandles)

        interrupted = false
        val root = archive.open(source.ref, source.name)
        assertTrue(Capability.CREATE in archive.stat(root).capabilities)
        assertEquals(2, source.channelOpens)
    }

    private fun channelOf(owner: Job? = null) =
        JobChannel(SeekableInMemoryByteChannel(ByteArray(4096)), owner)

    private fun read(channel: JobChannel) = channel.read(ByteBuffer.allocate(16))

    @Test fun `reads stop once the job that opened the handle is cancelled`() {
        val owner = Job()
        val channel = channelOf(owner)

        assertEquals(16, read(channel))
        owner.cancel()

        val failure = runCatching { read(channel) }.exceptionOrNull()
        assertTrue("Expected the read to be interrupted, got $failure", failure is InterruptedIOException)
    }

    @Test fun `a member open is bound to its own caller and released afterwards`() {
        val channel = channelOf()
        val caller = Job()

        channel.readFor(caller)
        assertEquals(16, read(channel))
        caller.cancel()

        val failure = runCatching { read(channel) }.exceptionOrNull()
        assertTrue("A cancelled caller stops its own reads, got $failure", failure is InterruptedIOException)

        // The reader serves one member at a time; the next open replaces the binding.
        channel.readFor(null)
        assertEquals("A later member read is unaffected", 16, read(channel))
    }
}
