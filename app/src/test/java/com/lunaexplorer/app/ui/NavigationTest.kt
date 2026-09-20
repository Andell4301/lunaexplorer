package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.storage.MediaCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class NavigationTest {
    @get:Rule val harness = BrowserViewModelHarness().startingWith { dir -> File(dir, "inner").mkdirs() }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    private fun ready() {
        assertTrue(harness.awaitUntil { state.ready })
    }

    private fun goTo(file: File) {
        viewModel.goTo(file.absolutePath)
        assertTrue("Never arrived at ${file.absolutePath}",
            harness.awaitUntil { state.screen == Screen.BROWSER && state.directoryPath == file.absolutePath })
    }

    @Test fun `back retraces home, then the tool, then leaves`() {
        ready()
        assertEquals(Screen.HOME, state.screen)
        assertFalse("Home is where the app opens, so there is nothing behind it", viewModel.canGoBack())

        viewModel.showScreen(Screen.STORAGE)
        assertEquals(listOf(Screen.HOME), state.backStack)

        goTo(harness.directory)
        assertEquals(listOf(Screen.HOME, Screen.STORAGE), state.backStack)

        viewModel.back()
        assertEquals("Back out of the browser returns to the tool it was opened from",
            Screen.STORAGE, state.screen)
        viewModel.back()
        assertEquals("And back out of the tool returns home", Screen.HOME, state.screen)
        assertFalse("Which is where it stops, rather than bouncing back into the browser",
            viewModel.canGoBack())
    }

    @Test fun `back on the home screen leaves the app rather than walking the tab's history`() {
        ready()
        goTo(harness.directory)
        goTo(File(harness.directory, "inner"))
        val deep = requireNotNull(state.tab).index
        assertTrue("The tab needs history behind it for this to mean anything", deep > 0)

        viewModel.showScreen(Screen.HOME)
        assertFalse("Nothing on screen would change, so the button must not offer it",
            viewModel.canGoBack())
        viewModel.back()
        harness.idle()
        assertEquals(Screen.HOME, state.screen)
        assertEquals("Back must not walk a history the home screen is not showing",
            deep, requireNotNull(state.tab).index)
    }

    @Test fun `a folder opened from home goes back to home, not to what the tab was showing`() {
        ready()
        goTo(harness.directory)
        goTo(File(harness.directory, "inner"))
        viewModel.showScreen(Screen.HOME)

        goTo(harness.directory)
        viewModel.back()
        harness.idle()
        assertEquals("Back from a folder opened at home returns home", Screen.HOME, state.screen)
    }

    @Test fun `a tool opened twice does not stack up behind itself`() {
        ready()
        viewModel.showScreen(Screen.STORAGE)
        viewModel.showScreen(Screen.APPS)
        viewModel.showScreen(Screen.STORAGE)
        assertEquals("Going back to something already behind you is a return, not another step",
            listOf(Screen.HOME), state.backStack)
        viewModel.back()
        assertEquals(Screen.HOME, state.screen)
        assertFalse(viewModel.canGoBack())
    }

    @Test fun `back clears every selected storage card before leaving the tool`() {
        ready()
        viewModel.showScreen(Screen.STORAGE)
        var oldestSelected = true
        var largestSelected = true
        viewModel.registerToolSelection(Any()) { oldestSelected = false }
        viewModel.registerToolSelection(Any()) { largestSelected = false }
        assertTrue(state.toolSelectionActive)

        viewModel.back()
        assertFalse(oldestSelected)
        assertFalse(largestSelected)
        assertFalse(state.toolSelectionActive)
        assertEquals(Screen.STORAGE, state.screen)
        assertEquals(listOf(Screen.HOME), state.backStack)

        viewModel.back()
        assertEquals(Screen.HOME, state.screen)
    }

    @Test fun `disposing a tool card removes its selection from back handling`() {
        ready()
        viewModel.showScreen(Screen.STORAGE)
        val owner = Any()
        var cleared = false
        viewModel.registerToolSelection(owner) { cleared = true }
        viewModel.registerToolSelection(owner, null)
        assertFalse(state.toolSelectionActive)
        viewModel.back()
        assertFalse(cleared)
        assertEquals(Screen.HOME, state.screen)
    }

    @Test
    fun choosingSomewhereToGoLeavesWhicheverToolScreenWasCoveringTheBrowser() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        harness.viewModel.showScreen(Screen.BROWSER)
        val home = requireNotNull(viewModel.state.value.location)

        for (screen in listOf(Screen.APPS, Screen.STORAGE, Screen.RECYCLE_BIN)) {
            viewModel.showScreen(screen)
            assertTrue(harness.awaitUntil { viewModel.state.value.screen == screen })
            viewModel.navigate(home)
            assertTrue(harness.awaitUntil { viewModel.state.value.screen == Screen.BROWSER })
            assertEquals("Navigating must leave $screen", Screen.BROWSER, viewModel.state.value.screen)
        }

        viewModel.showScreen(Screen.APPS)
        assertTrue(harness.awaitUntil { viewModel.state.value.screen == Screen.APPS })
        viewModel.showCategory(MediaCategory.IMAGES)
        assertTrue(harness.awaitUntil { viewModel.state.value.screen == Screen.BROWSER })
        assertEquals("A device-wide view must leave it too", Screen.BROWSER, viewModel.state.value.screen)
    }
}
