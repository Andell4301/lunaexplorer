package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.core.RenameStep
import org.junit.Assert.assertEquals
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
class BatchRenameViewModelTest {
    @get:Rule val harness = BrowserViewModelHarness().startingWith { dir ->
        File(dir, "a.txt").writeText("a")
        File(dir, "b.txt").writeText("b")
    }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    private fun listed() {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { state.entries.size == 2 })
    }

    private fun names() = harness.directory.list().orEmpty().sorted()

    @Test fun `with the extension included a replace can change it`() {
        listed()
        viewModel.batchRename(state.entries, listOf(RenameStep.Replace("txt", "md")), includeExtension = true)
        assertTrue("Both files take the new extension", harness.awaitUntil { names() == listOf("a.md", "b.md") })
        assertTrue(harness.awaitUntil { state.message == "Renamed 2 items" })
    }

    @Test fun `with the extension left alone the same replace finds nothing to change`() {
        listed()
        viewModel.batchRename(state.entries, listOf(RenameStep.Replace("txt", "md")), includeExtension = false)
        assertTrue(harness.awaitUntil { state.message == "Nothing to rename" })
        assertEquals(listOf("a.txt", "b.txt"), names())
    }
}
