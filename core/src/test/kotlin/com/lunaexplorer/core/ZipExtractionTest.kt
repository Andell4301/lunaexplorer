package com.lunaexplorer.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ZipExtractionTest {
    @Test fun `zip extraction preserves unflagged CP437 names`() =
        extractNamed("café.jpg", Charset.forName("CP437"))

    @Test fun `zip extraction preserves UTF-8 names when the flag is set`() =
        extractNamed("写真.jpg", Charsets.UTF_8)

    @Test fun `a storage failure while indexing a zip keeps its reason and identity`() {
        val source = ByteSource("photos.zip", jdkZipBytes(mapOf("first.jpg" to bytesOf("image"))))
        val refused = StorageException(StorageError.AUTH, "The session expired")
        val provider = object : StorageProvider by source {
            override suspend fun openChannel(ref: NodeRef): SeekableByteChannel {
                val channel = requireNotNull(source.openChannel(ref))
                return object : SeekableByteChannel by channel {
                    override fun read(dst: ByteBuffer): Int = throw refused
                }
            }
        }
        val destination = MemoryStorageProvider("destination")
        val engine = ArchiveEngine(ProviderRegistry(listOf(provider, destination)))

        val failure = assertThrows(StorageException::class.java) {
            runBlocking { engine.extract(source.ref, destination.root, null).last() }
        }

        assertSame(refused, failure)
        assertEquals(StorageError.AUTH, failure.reason)
        assertEquals(0, source.openHandles)
    }

    private fun extractNamed(name: String, charset: Charset) = runBlocking {
        val raw = ByteArrayOutputStream()
        ZipOutputStream(raw, charset).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytesOf("image"))
            zip.closeEntry()
        }
        val source = ByteSource("photos.zip", raw.toByteArray())
        val destination = MemoryStorageProvider("destination")
        val engine = ArchiveEngine(ProviderRegistry(listOf(source, destination)))

        engine.extract(source.ref, destination.root, null).last()

        val extracted = requireNotNull(destination.childRef(destination.root, name)) { "Missing $name" }
        assertEquals("image", destination.text(extracted))
        assertEquals(0, source.openHandles)
    }
}
