package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.BookmarkList
import com.lunaexplorer.app.model.Destination
import com.lunaexplorer.app.model.StoredProcedure
import com.lunaexplorer.core.OperationType
import com.lunaexplorer.core.ProcedureLocation
import com.lunaexplorer.core.ProcedureStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class ProcedureBookmarkTest {
    @get:Rule val harness = BrowserViewModelHarness()

    private fun procedure(): StoredProcedure {
        assertTrue(harness.awaitUntil { harness.state.ready })
        val root = requireNotNull(harness.graph.local.referenceTo(harness.directory.path))
        return StoredProcedure(name = "  Make folder  ", steps = listOf(
            ProcedureStep(OperationType.CREATE_FOLDER, destination = ProcedureLocation(root), name = "old"),
        )).also { runBlocking { harness.graph.procedures.save(it) } }
    }

    @Test fun `procedure bookmarks queue the current definition without changing screens`() {
        val saved = procedure()
        val viewModel = harness.viewModel
        viewModel.bookmarks.addProcedure(saved.id, saved.name, BookmarkList.HOME)
        val bookmark = harness.state.homeBookmarks.single()
        val updated = saved.copy(name = "Updated", steps = listOf(saved.steps.single().copy(name = "new")))
        runBlocking { harness.graph.procedures.save(updated) }
        val screen = harness.state.screen

        viewModel.openBookmark(bookmark)
        assertTrue(harness.awaitUntil { harness.graph.database.procedureRuns.value.isNotEmpty() })
        val run = harness.graph.database.procedureRuns.value.single()
        val request = runBlocking { requireNotNull(harness.graph.database.request(run.id)) }
        assertEquals(saved.id, request.procedureId)
        assertEquals(updated.steps, request.steps)
        assertEquals(updated.name, run.title)
        assertEquals(screen, harness.state.screen)
    }

    @Test fun `a bookmark cannot queue a removed procedure`() {
        val saved = procedure()
        val viewModel = harness.viewModel
        viewModel.bookmarks.addProcedure(saved.id, saved.name)
        val bookmark = harness.state.bookmarks.single()
        runBlocking { harness.graph.procedures.remove(saved.id) }

        viewModel.openBookmark(bookmark)
        assertTrue(harness.awaitUntil { harness.state.message != null })
        assertTrue(harness.graph.database.procedureRuns.value.isEmpty())
    }

    @Test fun `procedure bookmarks retain names and persist independently in both lists`() {
        val saved = procedure()
        val bookmarks = harness.viewModel.bookmarks
        bookmarks.addProcedure(saved.id, saved.name)
        val original = harness.state.bookmarks.single()
        bookmarks.addProcedure(saved.id, saved.name)
        assertEquals(listOf(original), harness.state.bookmarks)
        bookmarks.copyTo(original, BookmarkList.HOME)
        val home = harness.state.homeBookmarks.single()
        assertNotEquals(original.id, home.id)
        bookmarks.edit(home, "  Quick run  ", "", BookmarkList.HOME)
        bookmarks.remove(original)

        assertTrue(harness.awaitUntil {
            val restored = runBlocking { harness.graph.database.loadSession() }
            restored?.bookmarks?.isEmpty() == true && restored.homeBookmarks.singleOrNull()?.title == "  Quick run  "
        })
        val restored = runBlocking { requireNotNull(harness.graph.database.loadSession()) }
        assertEquals(Destination.Procedure(saved.id), restored.homeBookmarks.single().destination)
        assertEquals(saved.name, original.title)
    }
}
