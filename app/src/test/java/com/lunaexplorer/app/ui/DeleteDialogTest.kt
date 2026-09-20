package com.lunaexplorer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.lunaexplorer.app.LunaApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import com.lunaexplorer.app.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class DeleteDialogTest {
    private val harness = BrowserViewModelHarness().startingWith { dir ->
        File(dir, "big").mkdirs()
        RandomAccessFile(File(dir, "big/blob.bin"), "rw").use { it.setLength(1_200L * 1024 * 1024) }
        File(dir, "small").mkdirs()
        File(dir, "small/note.txt").writeText("tiny")
    }
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(harness).around(compose)

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    private fun show() {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { state.entries.size == 2 })
        compose.setContent {
            MaterialTheme {
                val current by viewModel.state.collectAsState()
                if (current.overlay == Overlay.Delete) DeleteDialog(current, viewModel, viewModel::dismissOverlay)
            }
        }
    }

    private fun requestDeleting(name: String) {
        viewModel.toggleSelection(state.entries.first { it.name == name }.ref)
        viewModel.requestDelete()
        compose.waitUntil(10_000) { compose.waitForIdle(); state.overlay == Overlay.Delete }
    }

    @Test fun `Cancel forgets the check and keeps the folder`() {
        show()
        requestDeleting("small")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); state.overlay == null }

        assertNull(state.deleteCheck)
        harness.idle()
        assertTrue("Nothing may be queued", state.operations.isEmpty())
        assertTrue(File(harness.directory, "small/note.txt").exists())
    }

    @Test fun `a folder above the warning asks a second time before deleting`() {
        show()
        viewModel.setPreferences(state.preferences.copy(confirmDelete = false, recycleBin = false,
            warnLargeDelete = true, largeDeleteGb = 1))
        requestDeleting("big")
        compose.waitUntil(10_000) { compose.waitForIdle(); state.deleteCheck?.measuring == false }

        compose.onNodeWithText("above the 1 GB warning you set.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Don't ask again this session").assertDoesNotExist()
        compose.onNodeWithText("Skip check").assertDoesNotExist()
        compose.onNodeWithText("Delete").assertIsEnabled().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Are you sure?").assertExists()
        compose.onNodeWithText("above the 1 GB warning you set.", substring = true).assertExists()
        harness.idle()
        assertTrue("The first confirm queues nothing", state.operations.isEmpty())
        assertTrue("And the check survives for the second", state.deleteCheck != null)

        compose.onNodeWithText("Delete").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); state.overlay == null }
        assertNull(state.deleteCheck)
        assertTrue(harness.awaitUntil { state.operations.any { it.title.startsWith("Delete") } })
    }

    @Test fun `cancelling the second step deletes nothing and forgets the check`() {
        show()
        viewModel.setPreferences(state.preferences.copy(confirmDelete = false, recycleBin = false,
            warnLargeDelete = true, largeDeleteGb = 1))
        requestDeleting("big")
        compose.waitUntil(10_000) { compose.waitForIdle(); state.deleteCheck?.measuring == false }
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Are you sure?").assertExists()

        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); state.overlay == null }
        assertNull(state.deleteCheck)
        harness.idle()
        assertTrue("Nothing may be queued", state.operations.isEmpty())
        assertTrue(File(harness.directory, "big/blob.bin").exists())
    }

    @Test fun `the permanent button carries its own label into the second step`() {
        show()
        viewModel.setPreferences(state.preferences.copy(recycleBin = true, warnLargeDelete = true, largeDeleteGb = 1))
        requestDeleting("big")
        compose.waitUntil(10_000) { compose.waitForIdle(); state.deleteCheck?.measuring == false }

        compose.onNodeWithText("Delete permanently").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Are you sure?").assertExists()
        compose.onNodeWithText("Move to bin").assertDoesNotExist()
        compose.onNodeWithText("Delete permanently").assertIsEnabled().performClick()

        compose.waitUntil(10_000) { compose.waitForIdle(); state.overlay == null }
        assertTrue(harness.awaitUntil { state.operations.any { it.title == "Delete 1 item(s)" } })
    }

    @Test fun `moving a large folder to the bin asks a second time too`() {
        show()
        viewModel.setPreferences(state.preferences.copy(recycleBin = true, warnLargeDelete = true, largeDeleteGb = 1))
        requestDeleting("big")
        compose.waitUntil(10_000) { compose.waitForIdle(); state.deleteCheck?.measuring == false }

        compose.onNodeWithText("Move to bin").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Are you sure?").assertExists()
        compose.onNodeWithText("Delete permanently").assertDoesNotExist()
        harness.idle()
        assertTrue("Still nothing queued", state.operations.isEmpty())

        compose.onNodeWithText("Move to bin").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); state.overlay == null }
        assertNull(state.deleteCheck)
    }

    @Test fun `the dialog counts and deletes what was checked, not what is selected now`() {
        show()
        viewModel.setPreferences(state.preferences.copy(recycleBin = false))
        requestDeleting("small")
        compose.waitUntil(10_000) { compose.waitForIdle(); state.deleteCheck?.measuring == false }
        assertEquals(listOf("small"), requireNotNull(state.deleteCheck).entries.map { it.name })

        // The selection bar stays live behind the dialog, so the selection can be emptied under it.
        viewModel.clearSelection()
        compose.waitForIdle()
        assertTrue(state.selected.isEmpty())

        compose.onNodeWithText("Delete 1 item?").assertExists()
        compose.onNodeWithText("Delete").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); state.overlay == null }

        assertNull(state.deleteCheck)
        assertTrue("The checked folder is the one deleted",
            harness.awaitUntil { state.operations.any { it.title == "Delete 1 item(s)" } })
    }

    @Test fun `a plain deletion still offers the session default`() {
        show()
        requestDeleting("small")
        compose.waitUntil(10_000) { compose.waitForIdle(); state.deleteCheck?.measuring == false }

        compose.onNodeWithText("Don't ask again this session").performClick()
        compose.onNodeWithText("Move to bin").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); state.overlay == null }

        assertEquals("The answer is kept for the session", false, state.session.confirmDelete)
        assertNull(state.deleteCheck)
    }
}
