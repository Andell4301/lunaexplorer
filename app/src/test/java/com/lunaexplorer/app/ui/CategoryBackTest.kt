package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.storage.MediaCategory
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
class CategoryBackTest {
    @get:Rule val harness = BrowserViewModelHarness().startingWith { dir ->
        File(dir, "note.txt").writeText("x")
    }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    private fun atHome() {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { state.view is View.Folder && state.entries.isNotEmpty() })
        viewModel.showScreen(Screen.HOME)
        assertEquals(Screen.HOME, state.screen)
    }

    @Test fun `back from a category opened on Home returns to Home`() {
        atHome()
        viewModel.showCategory(MediaCategory.IMAGES)
        assertTrue("The results take over the browser",
            harness.awaitUntil { state.screen == Screen.BROWSER && state.view is View.Category })

        viewModel.back()
        harness.idle()
        assertEquals("Back belongs to the screen the results were opened from",
            Screen.HOME, state.screen)
    }

    @Test fun `closing a category from its own control also returns to Home`() {
        atHome()
        viewModel.showCategory(MediaCategory.AUDIO)
        assertTrue(harness.awaitUntil { state.view is View.Category })

        viewModel.closeSearch()
        harness.idle()
        assertEquals(Screen.HOME, state.screen)
    }

    @Test fun `a category opened on Home is still there after another tab, and still goes back to Home`() {
        atHome()
        val first = state.activeTabId
        viewModel.showCategory(MediaCategory.IMAGES)
        assertTrue(harness.awaitUntil { state.screen == Screen.BROWSER && state.view is View.Category })

        viewModel.newTab()
        assertTrue(harness.awaitUntil { state.activeTabId != first && state.view is View.Folder })
        viewModel.selectTab(first)
        assertTrue("The category comes back with its tab",
            harness.awaitUntil { state.screen == Screen.BROWSER && state.view is View.Category })

        viewModel.back()
        harness.idle()
        assertEquals("Back still belongs to the screen the results were opened from",
            Screen.HOME, state.screen)
    }

    @Test fun `a category opened while browsing still closes to the folder`() {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { state.view is View.Folder && state.entries.isNotEmpty() })

        viewModel.showCategory(MediaCategory.IMAGES)
        assertTrue(harness.awaitUntil { state.view is View.Category })

        viewModel.back()
        harness.idle()
        assertEquals("It was opened from the browser, so it closes back into it",
            Screen.BROWSER, state.screen)
        assertTrue("And the folder comes back", harness.awaitUntil { state.view is View.Folder })
    }
}
