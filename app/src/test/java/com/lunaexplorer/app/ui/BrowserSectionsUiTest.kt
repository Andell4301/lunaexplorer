package com.lunaexplorer.app.ui

import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.SortOrder
import com.lunaexplorer.app.model.ViewMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class BrowserSectionsUiTest : RobolectricBrowserUiTest() {

    private fun viewModel() =
        compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }

    private fun sectionsOn(viewModel: BrowserViewModel, on: Boolean = true) {
        compose.runOnUiThread {
            viewModel.setPreferences(viewModel.state.value.preferences.copy(sections = on))
        }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.sections.isNotEmpty() == on
        }
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun headersAppearOverTheListAndGoAgainWhenTheSettingIsOff() {
        awaitListing()
        val viewModel = viewModel()
        compose.onAllNodesWithText("A").assertCountEquals(0)

        sectionsOn(viewModel)
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("A").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("B").assertExists()
        compose.onNodeWithText("G").assertExists()
        val folderLetter = compose.onNodeWithText("A").fetchSemanticsNode().boundsInRoot.top
        val letterB = compose.onNodeWithText("B").fetchSemanticsNode().boundsInRoot.top
        assertTrue("The folder's section comes before the file sections", folderLetter < letterB)

        sectionsOn(viewModel, on = false)
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("A").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun aHeaderSpansTheWholeLineInTheGrid() {
        awaitListing()
        val viewModel = viewModel()
        compose.runOnUiThread {
            viewModel.setPreferences(viewModel.state.value.preferences
                .copy(sections = true, view = ViewMode.GRID_MEDIUM))
        }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("A").fetchSemanticsNodes().isNotEmpty()
        }
        val header = compose.onNodeWithText("A").fetchSemanticsNode().boundsInRoot
        val tile = compose.onNodeWithText("beta.txt").fetchSemanticsNode().boundsInRoot
        assertTrue("A header takes the whole line, not one tile's width",
            header.width > tile.width * 1.5f)
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun sectionsFollowTheSortOrderInEffect() {
        awaitListing()
        val viewModel = viewModel()
        sectionsOn(viewModel)

        compose.runOnUiThread {
            viewModel.setPreferences(viewModel.state.value.preferences.copy(sort = SortOrder.EXTENSION))
        }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText(".txt").fetchSemanticsNodes().isNotEmpty()
        }

        compose.runOnUiThread {
            viewModel.setPreferences(viewModel.state.value.preferences.copy(sort = SortOrder.SIZE))
        }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("Under 1 KiB").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
