package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class BrowserFilesClipTest {
    @get:Rule val harness = BrowserViewModelHarness()

    @Before fun awaitBrowser() {
        assertTrue(harness.awaitUntil { harness.state.ready && harness.state.location != null })
    }

    @Test fun `saving queues the durable clip with the exact requested name`() = runBlocking {
        val file = harness.viewModel.files.createClipFile().apply { writeText("clip bytes") }
        val destination = harness.graph.local.root("harness")
        val id = harness.viewModel.files.saveClip(file, destination, " clip .mp4 ")
        val request = requireNotNull(harness.graph.database.request(id))

        assertEquals(OperationType.MOVE, request.type)
        assertEquals(destination, request.destination)
        assertEquals(" clip .mp4 ", request.name)
        assertEquals(ConflictPolicy.ASK, request.conflictPolicy)
        assertFalse(file.exists())
        val source = request.sources.single()
        assertNotEquals(destination.provider, source.provider)
        assertEquals("clip bytes", harness.graph.providers.provider(source).openRead(source).use { it.readBytes().decodeToString() })
    }

    @Test fun `the source parent is preferred over the folder on screen`() = runBlocking {
        val storage = MemoryStorageProvider("videos")
        val ref = storage.file(storage.root, "source.mp4", "video")
        harness.graph.providers.register(storage)

        assertEquals(storage.root, harness.viewModel.files.clipDestination(storage.stat(ref))?.ref)
    }

    @Test fun `a read-only source falls back to the browser folder`() = runBlocking {
        val storage = MemoryStorageProvider("videos")
        val ref = storage.file(storage.root, "source.mp4", "video")
        harness.graph.providers.register(object : StorageProvider by storage {
            override suspend fun stat(ref: NodeRef): Entry = storage.stat(ref).let {
                it.copy(capabilities = it.capabilities - Capability.CREATE)
            }
        })

        assertEquals(harness.graph.local.root("harness"), harness.viewModel.files.clipDestination(storage.stat(ref))?.ref)
    }

    @Test fun `an invalid filename leaves the render owned by its caller`() = runBlocking {
        val file = harness.viewModel.files.createClipFile().apply { writeText("clip bytes") }
        try {
            assertThrows(StorageException::class.java) {
                runBlocking { harness.viewModel.files.saveClip(file, harness.graph.local.root("harness"), "../clip.mp4") }
            }
            assertEquals("clip bytes", file.readText())
        } finally {
            file.delete()
        }
    }
}
