package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.core.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class DropTest {
    @get:Rule val harness = BrowserViewModelHarness().startingWith { dir ->
        File(dir, "target").mkdirs()
        File(dir, "dragged.txt").writeText("payload")
    }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    /** Returns (folder, file). */
    private fun listed(): Pair<Entry, Entry> {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.showScreen(Screen.BROWSER)
        assertTrue("The harness folder never listed", harness.awaitUntil { state.entries.size == 2 })
        return state.entries.first { it.directory } to state.entries.first { !it.directory }
    }

    @Test fun `an answer kept for the session stops the question`() {
        val (folder, file) = listed()
        viewModel.rememberForSession(dropAction = DropAction.MOVE)

        viewModel.dropOnto(listOf(file), folder.ref, folder.name)
        assertNull("Answered already, so nothing to ask", state.overlay)
        assertTrue("The move must reach the ordinary queue",
            harness.awaitUntil { state.operations.any { it.title.startsWith("Move") } })
    }

    @Test fun `a standing preference answers it too`() {
        val (folder, file) = listed()
        viewModel.setPreferences(state.preferences.copy(dropAction = DropAction.COPY))

        viewModel.dropOnto(listOf(file), folder.ref, folder.name)
        assertNull(state.overlay)
        assertTrue(harness.awaitUntil { state.operations.any { it.title.startsWith("Copy") } })
    }

    @Test fun `picking something up selects it and carries everything selected`() {
        val (folder, file) = listed()

        assertEquals("With nothing selected, the press picks up what it is on",
            listOf(file.ref), viewModel.beginDrag(file).map { it.ref })
        assertEquals("And selects it, so what travels is what is shown as picked",
            setOf(file.ref), state.selected)

        assertEquals("Picking up a second thing carries both",
            setOf(file.ref, folder.ref), viewModel.beginDrag(folder).map { it.ref }.toSet())
        assertEquals(setOf(file.ref, folder.ref), state.selected)

        assertEquals("And picking up something already selected carries the selection unchanged",
            setOf(file.ref, folder.ref), viewModel.beginDrag(file).map { it.ref }.toSet())
        assertEquals(setOf(file.ref, folder.ref), state.selected)
    }

    @Test fun `a folder dropped onto itself is refused rather than queued`() {
        val (folder, _) = listed()
        viewModel.dropOnto(listOf(folder), folder.ref, folder.name)

        assertNull("Nothing to ask about", state.overlay)
        assertNotNull("And it says why", state.message)
        assertTrue("Nothing may be queued", state.operations.isEmpty())
    }
}
