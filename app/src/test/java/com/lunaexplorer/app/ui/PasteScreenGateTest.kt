package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import com.lunaexplorer.app.model.*

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class PasteScreenGateTest {
    @get:Rule val harness = BrowserViewModelHarness().startingWith { dir ->
        File(dir, "target").mkdirs()
        File(dir, "copied.txt").writeText("payload")
    }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    private fun copied() {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.showScreen(Screen.BROWSER)
        assertTrue("The harness folder never listed", harness.awaitUntil { state.entries.size == 2 })
        viewModel.toggleSelection(state.entries.first { !it.directory }.ref)
        viewModel.operations.copySelection(move = false)
        assertNotNull("The copy has to reach the clipboard", state.clipboard)
    }

    @Test fun `pasting is refused from every screen that is not the browser`() {
        copied()
        assertTrue("A loaded, writable folder takes a paste while it is on screen", state.canPasteHere)

        for (screen in listOf(Screen.HOME, Screen.RECYCLE_BIN, Screen.APPS, Screen.STORAGE)) {
            viewModel.showScreen(screen)
            assertFalse("$screen must not offer the folder behind it", state.canPasteHere)

            viewModel.operations.paste(keep = true)
            harness.idle()
            assertTrue("And asking anyway queues nothing from $screen", state.operations.isEmpty())
            assertNotNull("The clipboard survives the detour", state.clipboard)
        }
    }

    @Test fun `returning to the browser makes the same clipboard pastable again`() {
        copied()
        viewModel.showScreen(Screen.HOME)
        assertFalse(state.canPasteHere)

        viewModel.showScreen(Screen.BROWSER)
        assertTrue("Coming back restores the destination", state.canPasteHere)
        viewModel.operations.paste()
        assertTrue("And the paste now reaches the queue",
            harness.awaitUntil { state.operations.isNotEmpty() })
    }
}
