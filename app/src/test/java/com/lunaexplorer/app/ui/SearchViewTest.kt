package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class SearchViewTest {
    @get:Rule val harness = BrowserViewModelHarness().startingWith { dir ->
        File(dir, "quarry").mkdirs()
        File(dir, "quarry/deep.txt").writeText("x")
    }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    private fun browsing() {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { state.view is View.Folder && state.entries.isNotEmpty() })
    }

    @Test fun `a search looks through the folder in view, and through storage when there is none`() {
        browsing()
        val inner = File(harness.directory, "quarry").absolutePath
        viewModel.goTo(inner)
        assertTrue(harness.awaitUntil { state.directoryPath == inner })
        val innerRef = requireNotNull(state.location).ref

        viewModel.search("deep", "ALL", null, null, null)
        assertTrue(harness.awaitUntil { state.view is View.Search })
        assertEquals("From a folder, that folder", innerRef, (state.view as View.Search).root)

        viewModel.closeSearch()
        viewModel.showScreen(Screen.HOME)
        viewModel.search("deep", "ALL", null, null, null)
        assertTrue(harness.awaitUntil { state.view is View.Search })
        val fromHome = (state.view as View.Search).root

        assertNotEquals("Not the folder a tab happened to be left on", innerRef, fromHome)
        assertTrue("A root, which is what searching the device means",
            state.roots.any { it.ref == fromHome })
        assertEquals("And the results are shown where results are shown", Screen.BROWSER, state.screen)
    }

    @Test fun `stepping into a result and back shows the same results, without searching again`() {
        browsing()
        viewModel.search("quarry", "ALL", null, null, null)
        assertTrue(harness.awaitUntil { state.view is View.Search && state.entries.isNotEmpty() && !state.searching })
        val found = state.entries.map { it.ref }
        val folder = state.entries.first { it.directory }

        viewModel.openFolder(folder)
        assertTrue(harness.awaitUntil { state.view is View.Folder && state.directoryPath?.endsWith("quarry") == true })

        viewModel.back()
        assertTrue("Back returns to the results", harness.awaitUntil { state.view is View.Search })
        assertEquals("And they are the same results", found, state.entries.map { it.ref })
        assertFalse("Put back rather than run again", state.searching)
    }
}
