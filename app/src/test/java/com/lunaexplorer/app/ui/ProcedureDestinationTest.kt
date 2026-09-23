package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class ProcedureDestinationTest {
    @get:Rule val harness = BrowserViewModelHarness()

    @Test fun `an existing typed destination remains creatable after its hierarchy is removed`() = runBlocking {
        assertTrue(harness.awaitUntil { harness.state.ready })
        val folder = File(harness.directory, " archive / invoices ").apply { mkdirs() }
        val location = harness.viewModel.procedures.resolve(folder.path, allowMissing = true)

        assertEquals(harness.graph.local.root("harness"), location.ref)
        assertEquals(listOf(" archive ", " invoices "), location.children)
        assertTrue(File(harness.directory, " archive ").deleteRecursively())
        assertTrue(harness.graph.providers.provider(location.ref).stat(location.ref).directory)
    }

    @Test fun `missing saved paths retain every literal component under their provider root`() = runBlocking {
        assertTrue(harness.awaitUntil { harness.state.ready })
        val path = File(harness.directory, " output / new ").path
        val missing = requireNotNull(harness.graph.local.refFor(path))

        val location = harness.viewModel.procedures.resolve(
            ProcedureLocation(missing, listOf(" later ")), allowMissing = true)

        assertEquals(harness.graph.local.root("harness"), location.ref)
        assertEquals(listOf(" output ", " new ", " later "), location.children)
        assertFalse(File(path).exists())
    }

    @Test fun `opaque picked destinations anchor through provider parents without a local path`() = runBlocking {
        assertTrue(harness.awaitUntil { harness.state.ready })
        val storage = MemoryStorageProvider("picked")
        val parent = storage.folder(storage.root, "parent")
        val child = storage.folder(parent, "child")
        harness.graph.providers.register(storage)

        val location = harness.viewModel.procedures.resolve(
            ProcedureLocation(child, listOf("future")), allowMissing = true)

        assertEquals(ProcedureLocation(storage.root, listOf("parent", "child", "future")), location)
        storage.delete(child)
        storage.delete(parent)
        assertTrue(storage.stat(location.ref).directory)
    }

    @Test fun `a missing provider root is not recreated through another local root`() = runBlocking {
        assertTrue(harness.awaitUntil { harness.state.ready })
        val root = harness.graph.local.root("harness")
        assertTrue(harness.directory.deleteRecursively())

        val error = assertThrows(StorageException::class.java) {
            runBlocking { harness.viewModel.procedures.resolve(ProcedureLocation(root), allowMissing = true) }
        }

        assertEquals(StorageError.NOT_FOUND, error.reason)
        assertFalse(harness.directory.exists())
    }

    @Test fun `ancestor permission failures do not become a different destination`() = runBlocking {
        assertTrue(harness.awaitUntil { harness.state.ready })
        val storage = MemoryStorageProvider("denied")
        val child = storage.folder(storage.root, "child")
        harness.graph.providers.register(object : StorageProvider by storage {
            override suspend fun stat(ref: NodeRef): Entry {
                if (ref == storage.root) throw StorageException(StorageError.PERMISSION, "Denied")
                return storage.stat(ref)
            }
        })

        val error = assertThrows(StorageException::class.java) {
            runBlocking { harness.viewModel.procedures.resolve(ProcedureLocation(child), allowMissing = true) }
        }

        assertEquals(StorageError.PERMISSION, error.reason)
        assertTrue(storage.exists(child))
    }

    @Test fun `ancestor traversal rejects cycles and preserves cancellation`(): Unit = runBlocking {
        assertTrue(harness.awaitUntil { harness.state.ready })
        val storage = MemoryStorageProvider("cycle")
        harness.graph.providers.register(object : StorageProvider by storage {
            override suspend fun parentOf(ref: NodeRef) = ref
        })
        assertThrows(IllegalStateException::class.java) {
            runBlocking { harness.viewModel.procedures.resolve(ProcedureLocation(storage.root), allowMissing = true) }
        }

        harness.graph.providers.register(object : StorageProvider by storage {
            override suspend fun parentOf(ref: NodeRef): NodeRef? = throw CancellationException("Cancelled")
        })
        assertThrows(CancellationException::class.java) {
            runBlocking { harness.viewModel.procedures.resolve(ProcedureLocation(storage.root), allowMissing = true) }
        }
    }
}
