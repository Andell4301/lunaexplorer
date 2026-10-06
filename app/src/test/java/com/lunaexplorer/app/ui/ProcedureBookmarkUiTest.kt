package com.lunaexplorer.app.ui

import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.Destination
import com.lunaexplorer.app.model.Screen
import com.lunaexplorer.app.model.StoredProcedure
import com.lunaexplorer.core.OperationType
import com.lunaexplorer.core.ProcedureLocation
import com.lunaexplorer.core.ProcedureStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class, qualifiers = "w393dp-h852dp-xhdpi")
class ProcedureBookmarkUiTest : RobolectricBrowserUiTest() {
    @Test
    fun settingsBookmarksAProcedureInBothListsAndHomeRunsIt() {
        awaitListing()
        val root = requireNotNull(fixture.graph.local.refFor(fixture.directory.absolutePath))
        val procedure = StoredProcedure(name = "Make output", steps = listOf(
            ProcedureStep(OperationType.CREATE_FOLDER, destination = ProcedureLocation(root), name = "output"),
        ))
        runBlocking { fixture.graph.procedures.save(procedure) }
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        openSettingsPage("Stored procedures")
        bookmark("Home")
        bookmark("Sidebar")
        assertEquals(Destination.Procedure(procedure.id), viewModel.state.value.homeBookmarks.single().destination)
        assertEquals(Destination.Procedure(procedure.id), viewModel.state.value.bookmarks.single().destination)
        bookmark("Sidebar")
        assertTrue(viewModel.state.value.bookmarks.isEmpty())

        compose.runOnUiThread { viewModel.dismissOverlay(); viewModel.showScreen(Screen.HOME) }
        compose.onNodeWithTag("home-screen").performScrollToNode(hasText(procedure.name))
        compose.onNode(hasText(procedure.name) and hasAnyAncestor(hasTestTag("home-screen"))).performClick()
        awaitCondition("Bookmarked procedure completed", 15_000) {
            fixture.graph.database.procedureRuns.value.any { it.procedureId == procedure.id && it.status == "SUCCEEDED" }
        }
        assertTrue(File(fixture.directory, "output").isDirectory)
        assertEquals(Screen.HOME, viewModel.state.value.screen)
    }

    private fun bookmark(list: String) {
        compose.onNodeWithContentDescription("Bookmark Make output").performScrollTo().performClick()
        compose.onNode(hasText(list) and hasAnyAncestor(isPopup())).performClick()
    }
}
