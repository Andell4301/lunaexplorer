package com.lunaexplorer.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class BrowserSelectionUiTest : RobolectricBrowserUiTest() {

    @Test
    fun aLongPressThatStartsNoDragDoesNotAlsoClickTheRow() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }

        compose.onNodeWithText("beta.txt").performTouchInput { longClick() }
        compose.waitForIdle()

        assertEquals("A long press selects exactly what it picked up", 1, viewModel.state.value.selected.size)
        assertEquals("And must not open it as well", null, viewModel.state.value.overlay)
    }

    // Dragging owns the pointer long-press, so selection must stay reachable as a semantics action.
    @Test
    fun selectingStaysAnActionAScreenReaderCanPerform() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }

        val row = compose.onNodeWithText("beta.txt")
        row.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        row.performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()

        assertEquals("Performing it must select the row", 1, viewModel.state.value.selected.size)
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun theSelectionBarNamesWhatItDoesAndKeepsTheRestBehindMore() {
        awaitListing()
        compose.onNodeWithContentDescription("Select beta.txt").performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("More").fetchSemanticsNodes().isNotEmpty()
        }

        listOf("Copy", "Move", "Share", "Rename", "Delete", "More").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        listOf("Select all", "Select all files", "Select all folders", "Invert selection").forEach {
            compose.onNodeWithContentDescription(it).assertIsDisplayed()
        }
        assertTrue("What is rare is behind More rather than on the bar",
            compose.onAllNodesWithText("Add to archive").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText("More").performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("Properties").fetchSemanticsNodes().isNotEmpty()
        }
        listOf("Go to location", "Add to archive", "Look inside", "Properties").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("Look inside").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Go to location").performScrollTo().assertIsNotEnabled()
    }
}
