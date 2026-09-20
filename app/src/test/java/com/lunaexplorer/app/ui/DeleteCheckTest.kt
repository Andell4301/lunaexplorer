package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.PathAddressable
import com.lunaexplorer.core.StorageProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import com.lunaexplorer.app.model.*

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class DeleteCheckTest {
    @get:Rule val harness = BrowserViewModelHarness().startingWith { dir ->
        File(dir, "big").mkdirs()
        // Sparse: uses no disk, but the listing reports the full length.
        RandomAccessFile(File(dir, "big/blob.bin"), "rw").use { it.setLength(1_200L * 1024 * 1024) }
        File(dir, "small").mkdirs()
        File(dir, "small/note.txt").writeText("tiny")
        File(dir, "loose.txt").writeText("loose")
    }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    private fun listed() {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.showScreen(Screen.BROWSER)
        assertTrue("The harness folder never listed", harness.awaitUntil { state.entries.size == 3 })
    }

    private fun select(name: String) = viewModel.toggleSelection(state.entries.first { it.name == name }.ref)

    @Test fun `a folder above the warning asks even when asking is switched off`() {
        listed()
        viewModel.setPreferences(state.preferences.copy(confirmDelete = false, recycleBin = false,
            warnLargeDelete = true, largeDeleteGb = 1))
        select("big")

        viewModel.requestDelete()
        assertTrue("The measured total must open the dialog",
            harness.awaitUntil { state.overlay == Overlay.Delete && state.deleteCheck?.large == true })
        assertTrue("And the measurement runs to its end", harness.awaitUntil { state.deleteCheck?.measuring == false })
        val check = requireNotNull(state.deleteCheck)
        assertEquals(1L shl 30, check.threshold ?: 0L)
        assertEquals(1_200L * 1024 * 1024, check.bytes)
        assertEquals(1L, check.files)
        assertTrue("Nothing is deleted until the dialog is answered", File(harness.directory, "big/blob.bin").exists())
        assertTrue(state.operations.isEmpty())

        viewModel.deleteChecked(toBin = false)
        assertNull("Answering forgets the check", state.deleteCheck)
        assertTrue(harness.awaitUntil { state.operations.any { it.title.startsWith("Delete") } })
    }

    @Test fun `a disabled size warning allows a large deletion without a prompt`() {
        listed()
        viewModel.setPreferences(state.preferences.copy(confirmDelete = false, recycleBin = false, warnLargeDelete = false))
        select("big")

        viewModel.requestDelete()
        assertTrue("The deletion reaches the queue on its own",
            harness.awaitUntil { state.operations.any { it.title.startsWith("Delete") } })
        assertNull("However big it is, no dialog opens while the warning is off", state.overlay)
    }

    @Test fun `a small folder goes without any dialog when asking is off`() {
        listed()
        viewModel.setPreferences(state.preferences.copy(confirmDelete = false, recycleBin = false,
            warnLargeDelete = true, largeDeleteGb = 1))
        select("small")
        select("loose.txt")

        viewModel.requestDelete()
        assertTrue("The deletion reaches the queue on its own",
            harness.awaitUntil { state.operations.any { it.title.startsWith("Delete 2") } })
        assertNull("Without a dialog ever opening", state.overlay)
        assertNull("And with the check cleared behind it", state.deleteCheck)
        assertTrue(state.selected.isEmpty())
    }

    @Test fun `leaving for another screen abandons a pending check`() {
        listed()
        // 1.2 GB is under the 10 GB warning, so only the measurement is pending.
        viewModel.setPreferences(state.preferences.copy(confirmDelete = false, recycleBin = false,
            warnLargeDelete = true, largeDeleteGb = 10))
        select("big")
        val folder = state.selectedEntries.single()
        val provider = harness.graph.providers.provider(folder.ref)
        val measuring = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        harness.graph.providers.register(object : StorageProvider by provider, PathAddressable by (provider as PathAddressable) {
            override fun list(parent: NodeRef, complete: Boolean): Flow<List<Entry>> = flow {
                if (parent == folder.ref) {
                    measuring.complete(Unit)
                    try { awaitCancellation() }
                    finally { cancelled.complete(Unit) }
                } else emitAll(provider.list(parent, complete))
            }
        })

        try {
            viewModel.requestDelete()
            assertTrue("The measurement must reach the held listing", harness.awaitUntil(rounds = 2_000) { measuring.isCompleted })
            assertTrue("The check stays pending while the listing is held", state.deleteCheck?.measuring == true)
            viewModel.showScreen(Screen.RECYCLE_BIN)

            assertNull("Changing screens forgets the check", state.deleteCheck)
            assertTrue("Changing screens cancels the held listing", harness.awaitUntil(rounds = 2_000) { cancelled.isCompleted })
            harness.idle()
            assertTrue("Nothing is queued for deletion", harness.graph.database.queue.value.isEmpty())
            assertNull("No dialog opens behind the new screen", state.overlay)
        } finally {
            viewModel.cancelDeleteCheck()
            harness.graph.providers.register(provider)
        }
    }

    @Test fun `cancelling forgets the check and deletes nothing`() {
        listed()
        select("big")
        viewModel.requestDelete()
        assertEquals("Asking is on, so the dialog opens at once", Overlay.Delete, state.overlay)
        assertTrue("With a check to show in it", state.deleteCheck != null)

        viewModel.cancelDeleteCheck()
        viewModel.dismissOverlay()
        assertNull(state.deleteCheck)
        assertNull(state.overlay)
        harness.idle()
        assertFalse("A measurement finishing late must not delete anything",
            harness.awaitUntil(rounds = 20) { state.operations.isNotEmpty() })
        assertTrue(File(harness.directory, "big/blob.bin").exists())
    }
}
