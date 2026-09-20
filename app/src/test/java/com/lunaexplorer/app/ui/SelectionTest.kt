package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
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
class SelectionTest {
    @get:Rule val harness = BrowserViewModelHarness().startingWith { dir ->
        File(dir, "one.txt").writeText("1")
        File(dir, "two.txt").writeText("2")
        File(dir, "inner").mkdirs()
        File(dir, "other").mkdirs()
    }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    private fun listed() {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { state.entries.size == 4 })
    }

    private fun names() = state.selectedEntries.map { it.name }.sorted()

    @Test fun `a selection can be widened to everything, or to one kind of thing`() {
        listed()

        viewModel.selectAllFiles()
        assertEquals(listOf("one.txt", "two.txt"), names())

        viewModel.selectAllFolders()
        assertEquals("One kind replaces the other rather than adding to it",
            listOf("inner", "other"), names())

        viewModel.selectAll()
        assertEquals(listOf("inner", "one.txt", "other", "two.txt"), names())
    }

    @Test fun `a file revealed from a tool screen goes back to that screen`() {
        listed()
        val file = state.entries.first { !it.directory }
        viewModel.goTo(File(harness.directory, "inner").absolutePath)
        assertTrue(harness.awaitUntil {
            state.directoryPath?.endsWith("inner") == true && !state.loading && state.entries.isEmpty()
        })
        viewModel.showScreen(Screen.STORAGE)
        assertEquals(Screen.STORAGE, state.screen)

        viewModel.revealEntry(file)
        assertTrue("The file must be shown where it lives",
            harness.awaitUntil { state.screen == Screen.BROWSER && state.selected.contains(file.ref) })

        viewModel.back()
        assertTrue("The first press lets go of the file", state.selected.isEmpty())
        assertEquals("And stays where it is", Screen.BROWSER, state.screen)

        viewModel.back()
        assertEquals("Back belongs to the screen the file was found on", Screen.STORAGE, state.screen)
    }
}
