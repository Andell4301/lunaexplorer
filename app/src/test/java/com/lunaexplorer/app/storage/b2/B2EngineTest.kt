package com.lunaexplorer.app.storage.b2

import com.lunaexplorer.core.ItemStatus
import com.lunaexplorer.core.MemoryStorageProvider
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.OperationEngine
import com.lunaexplorer.core.OperationRequest
import com.lunaexplorer.core.OperationType
import com.lunaexplorer.core.ProviderRegistry
import com.lunaexplorer.core.StorageProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class B2EngineTest {
    @get:Rule val temporary = TemporaryFolder()

    private val backend = FakeB2Backend("media")
    private val account = B2Account(id = "cloud", name = "Cloud", keyId = "key-id", bucket = "media")
    private val b2 by lazy {
        B2StorageProvider({ listOf(account) }, { it.copy(applicationKey = "s") }, FakeB2Connector(backend),
            B2ListingCache(temporary.newFolder("listings")), temporary.newFolder("uploads"))
    }
    private val device = MemoryStorageProvider("device")
    private val bucket = NodeRef("b2", "cloud:media:")

    private fun engine(source: StorageProvider = device) = OperationEngine(ProviderRegistry(listOf(source, b2)))

    private fun stored(): Map<String, Int> = backend.buckets.getValue("media").mapValues { it.value.size }.filterValues { it > 0 }

    @Test fun `a folder copied in arrives whole, under its own name and nothing else`() = runBlocking {
        val album = device.folder(device.root, "album")
        device.file(album, "a.jpg", "a")
        device.file(device.folder(album, "day2"), "b.jpg", "b")

        val result = engine().run(OperationRequest(type = OperationType.COPY, sources = listOf(album), destination = bucket))

        assertTrue(result.outcomes.joinToString { it.message.orEmpty() }, result.successful)
        assertEquals("a", backend.text("media", "album/a.jpg"))
        assertEquals("b", backend.text("media", "album/day2/b.jpg"))
        assertEquals("One version of each, and no staging name left", setOf("album/.bzEmpty", "album/a.jpg", "album/day2/.bzEmpty", "album/day2/b.jpg"),
            stored().keys)
        assertTrue(stored().values.all { it == 1 })
    }

    @Test fun `a folder with no marker moves out, and its last file takes it along`() = runBlocking {
        backend.put("media", "trip/day1/a.jpg", "a")
        backend.put("media", "trip/b.jpg", "b")

        val result = engine().run(OperationRequest(type = OperationType.MOVE,
            sources = listOf(NodeRef("b2", "cloud:media:trip")), destination = device.root))

        assertTrue(result.outcomes.joinToString { it.message.orEmpty() }, result.successful)
        val moved = device.childRef(device.root, "trip")!!
        assertEquals("b", device.text(device.childRef(moved, "b.jpg")!!))
        assertTrue("The source is removed outright", stored().isEmpty())
    }

    @Test fun `the same move keeping versions hides the source instead`() = runBlocking {
        backend.put("media", "trip/b.jpg", "b")

        val result = engine().run(OperationRequest(type = OperationType.MOVE,
            sources = listOf(NodeRef("b2", "cloud:media:trip")), destination = device.root, keepVersions = true))

        assertTrue(result.successful)
        assertEquals(listOf(true, false), backend.versionsOf("media", "trip/b.jpg").map { it.hidden })
    }

    @Test fun `a copy that fails leaves no staging data, hidden or otherwise`() = runBlocking {
        val file = device.file(device.root, "film.mkv", "0123456789")
        val failing = object : StorageProvider by device {
            override suspend fun openRead(ref: NodeRef): InputStream = object : InputStream() {
                private var sent = 0
                override fun read(): Int = if (sent++ < 4) 'x'.code else throw IOException("The card was pulled")
            }
        }

        val result = engine(failing).run(OperationRequest(type = OperationType.COPY, sources = listOf(file),
            destination = bucket, keepVersions = true))

        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertFalse("Hidden staging data would be stored, billed and never listed: ${stored()}", stored().keys.any { ".luna-" in it })
    }
}
