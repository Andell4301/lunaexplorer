package com.lunaexplorer.app.storage

import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.Feature
import com.lunaexplorer.core.MemoryStorageProvider
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.PathAddressable
import com.lunaexplorer.core.ProviderRegistry
import com.lunaexplorer.core.StorageProvider
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ThumbnailStagingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val ref = NodeRef("assisted", "opaque-document")
    private val entry = Entry(ref, "document.pdf", directory = false,
        mimeType = "application/pdf", capabilities = setOf(Capability.READ))

    private fun provider(open: () -> InputStream): StorageProvider = object :
        StorageProvider by MemoryStorageProvider("assisted"), PathAddressable {
        override val features = setOf(Feature.RANGE_READ)
        override fun pathOf(ref: NodeRef): String? = null
        override fun shownPathOf(ref: NodeRef): String = "/storage/Android/data/document.pdf"
        override fun refFor(path: String): NodeRef = ref
        override suspend fun openRead(ref: NodeRef): InputStream = open()
    }

    @Test fun `unknown or stale sizes cannot let a local PDF exceed the staging cap`() = runBlocking {
        for (size in listOf(null, 2L)) {
            var read = 0L
            var closed = false
            val provider = provider {
                object : InputStream() {
                    override fun read(): Int = error("Bulk read expected")
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        read += length
                        check(read <= 65L * 1024 * 1024)
                        return length
                    }
                    override fun close() { closed = true }
                }
            }
            val loader = ThumbnailLoader(context, ProviderRegistry(listOf(provider)))
            assertNull(loader.load(entry.copy(size = size), 32))
            assertTrue(closed)
            assertTrue(read in 1..(64L * 1024 * 1024 + 1))
            assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("thumbnail-stage-") })
        }
    }

    @Test fun `cancellation while opening a stage is rethrown and not cached as failure`() {
        var opened = 0
        val provider = provider { opened++; throw CancellationException("Cancelled by storage") }
        val loader = ThumbnailLoader(context, ProviderRegistry(listOf(provider)))
        repeat(2) {
            assertThrows(CancellationException::class.java) { runBlocking { loader.load(entry, 32) } }
        }
        assertEquals(2, opened)
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("thumbnail-stage-") })
    }
}
