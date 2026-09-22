package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.Overlay
import com.lunaexplorer.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class OverwriteConfirmationTest {
    @get:Rule val harness = BrowserViewModelHarness()
    private val memory = MemoryStorageProvider("unguarded")
    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    private fun conflict(type: OperationType = OperationType.COPY, localDestination: Boolean = false): OperationRequest {
        assertTrue(harness.awaitUntil { state.ready && !state.loading && state.listingRef != null })
        harness.graph.providers.register(object : StorageProvider by memory {
            override val features = memory.features + Feature.UNGUARDED_REPLACE
        })
        val source = memory.file(memory.root, "source.txt", "new")
        val destination = if (localDestination) state.location!!.ref else memory.root
        val request = OperationRequest(type = type, sources = listOf(source),
            destination = destination.takeUnless { type == OperationType.RENAME }, name = "existing.txt")
        runBlocking {
            harness.graph.database.enqueue(request, "Collision")
            harness.graph.database.saveConflicts(request.id, listOf(ItemOutcome(source,
                status = ItemStatus.CONFLICT, message = "Exists", conflictName = "existing.txt")))
            harness.graph.database.finish(request.id, "CONFLICT", "Exists")
        }
        assertTrue(harness.awaitUntil { state.operations.any { it.id == request.id && it.status == "CONFLICT" } })
        viewModel.showOverlay(Overlay.Queue)
        return request
    }

    @Test fun `unguarded replacement waits for confirmation without resolving the conflict`() {
        val request = conflict()

        viewModel.operations.resolveConflict(request.id, ConflictPolicy.REPLACE)

        assertTrue(harness.awaitUntil { state.overlay is Overlay.Overwrite })
        assertEquals("CONFLICT", state.operations.single { it.id == request.id }.status)
        assertEquals(1, state.operations.size)
    }

    @Test fun `merging into unguarded storage also requires confirmation`() {
        val request = conflict()

        viewModel.operations.resolveConflict(request.id, ConflictPolicy.MERGE)

        assertTrue(harness.awaitUntil { state.overlay is Overlay.Overwrite })
        assertEquals(ConflictPolicy.MERGE, (state.overlay as Overlay.Overwrite).policy)
        assertEquals("CONFLICT", state.operations.single { it.id == request.id }.status)
    }

    @Test fun `renaming over a file uses the source storage for its warning`() {
        val request = conflict(type = OperationType.RENAME)

        viewModel.operations.resolveConflict(request.id, ConflictPolicy.REPLACE)

        assertTrue(harness.awaitUntil { state.overlay is Overlay.Overwrite })
        assertEquals("CONFLICT", state.operations.single { it.id == request.id }.status)
    }

    @Test fun `an unguarded source does not warn when replacing on local storage`() {
        val request = conflict(localDestination = true)

        viewModel.operations.resolveConflict(request.id, ConflictPolicy.REPLACE)

        assertTrue(harness.awaitUntil { state.operations.any { it.id == request.id && it.status == "RESOLVED" } })
        assertEquals(Overlay.Queue, state.overlay)
    }

    @Test fun `keeping both files needs no overwrite confirmation`() {
        val request = conflict()

        viewModel.operations.resolveConflict(request.id, ConflictPolicy.KEEP_BOTH)

        assertTrue(harness.awaitUntil { state.operations.any { it.id == request.id && it.status == "RESOLVED" } })
        assertEquals(Overlay.Queue, state.overlay)
    }

    @Test fun `confirmation queues the chosen replacement policy`() {
        val request = conflict()
        viewModel.operations.resolveConflict(request.id, ConflictPolicy.MERGE)
        assertTrue(harness.awaitUntil { state.overlay is Overlay.Overwrite })

        viewModel.operations.confirmOverwrite(state.overlay as Overlay.Overwrite)

        assertTrue(harness.awaitUntil { state.operations.any { it.id == request.id && it.status == "RESOLVED" } })
        val retried = state.operations.single { it.id != request.id }
        assertEquals(ConflictPolicy.MERGE, runBlocking { harness.graph.database.request(retried.id) }!!.conflictPolicy)
        assertEquals(Overlay.Queue, state.overlay)
    }

    @Test fun `a dismissed confirmation cannot later queue replacement`() {
        val request = conflict()
        viewModel.operations.resolveConflict(request.id, ConflictPolicy.REPLACE)
        assertTrue(harness.awaitUntil { state.overlay is Overlay.Overwrite })
        val confirmation = state.overlay as Overlay.Overwrite
        viewModel.showOverlay(Overlay.Queue)

        viewModel.operations.confirmOverwrite(confirmation)
        harness.idle()

        assertEquals("CONFLICT", state.operations.single { it.id == request.id }.status)
        assertEquals(1, state.operations.size)
    }
}
