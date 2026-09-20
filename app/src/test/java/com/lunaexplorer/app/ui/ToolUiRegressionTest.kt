package com.lunaexplorer.app.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.Overlay
import com.lunaexplorer.app.model.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class ToolUiRegressionTest {
    private val harness = BrowserViewModelHarness().startingWith {
        File(it, "alpha.txt").writeText("a")
        File(it, "beta.txt").writeText("b")
    }.withSession { it.copy(preferences = it.preferences.copy(thumbnails = false)) }
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(harness).around(compose)

    private fun listed() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        harness.viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { harness.state.entries.size == 2 })
    }

    @Composable
    private fun Header(state: BrowserState, onBookmark: () -> Unit = {}) {
        PathBar(state, harness.viewModel, wide = false, onMenu = {}, onSearch = {},
            onView = {}, onFilter = {}, onPinPath = {}, onAddBookmarkPath = onBookmark,
            onCreate = {}, onNewFile = {}, onProperties = {}, onGoToPath = {}, onSystemBrowser = {})
    }

    @Test fun `tool page menus retain bookmarks and tabs without hidden-folder actions`() {
        listed()
        var bookmarks = 0
        compose.setContent {
            MaterialTheme {
                val state by harness.viewModel.state.collectAsState()
                Header(state) { bookmarks++ }
            }
        }
        val irrelevant = listOf("New folder", "New file", "Filter this folder", "Filter files", "View and sort",
            "Select all", "Show hidden files", "Hide hidden files", "Forward", "Refresh", "Properties", "Open in system files app")
        for (screen in listOf(Screen.STORAGE, Screen.APPS, Screen.RECYCLE_BIN, Screen.HOME)) {
            compose.runOnIdle { harness.viewModel.showScreen(screen) }
            compose.onNodeWithContentDescription("Page actions").performClick()
            compose.onNodeWithText("Bookmark").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("New tab").assertIsDisplayed().assertIsEnabled()
            irrelevant.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
            compose.onNodeWithText("Bookmark").performClick()
        }
        assertEquals("The retained bookmark action must still invoke its callback", 4, bookmarks)

        compose.runOnIdle { harness.viewModel.showScreen(Screen.BROWSER) }
        compose.onNodeWithContentDescription("Folder actions").performClick()
        compose.onNodeWithText("New file").assertExists()
        compose.onNodeWithText("New folder").assertExists()
        compose.onNodeWithText("Properties").assertExists()
    }

    private lateinit var backDispatcher: OnBackPressedDispatcher

    private fun storageSelections() {
        listed()
        harness.viewModel.showScreen(Screen.STORAGE)
        val rows = harness.state.entries.map { entry ->
            AnalysisRow(entry.ref.key, entry.name, entry.mimeType, entry.size, entry = entry)
        }
        compose.setContent {
            MaterialTheme {
                val state by harness.viewModel.state.collectAsState()
                backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                BackHandler(enabled = harness.viewModel.canGoBack()) { harness.viewModel.back() }
                Column {
                    Header(state)
                    AnalysisFiles(rows.take(1), viewModel = harness.viewModel, onReveal = {}, onDelete = {})
                    AnalysisFiles(rows.drop(1), viewModel = harness.viewModel, onReveal = {}, onDelete = {})
                }
            }
        }
        compose.onNodeWithText("alpha.txt").performTouchInput { longClick() }
        compose.onNodeWithText("beta.txt").performTouchInput { longClick() }
        compose.onAllNodesWithText("Remove 1").assertCountEquals(2)
        compose.runOnIdle { assertTrue(harness.state.toolSelectionActive) }
    }

    private fun assertStorageSelectionsCleared() {
        compose.onNodeWithText("Remove 1").assertDoesNotExist()
        compose.onNodeWithText("Tick none").assertDoesNotExist()
        compose.onNodeWithText("alpha.txt").assertIsDisplayed()
        compose.onNodeWithText("beta.txt").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(harness.state.toolSelectionActive)
            assertEquals("Back clears all cards before navigating away", Screen.STORAGE, harness.state.screen)
        }
    }

    @Test fun `storage toolbar back clears ticked files across cards before leaving`() {
        storageSelections()
        compose.onNodeWithContentDescription("Back").performClick()
        assertStorageSelectionsCleared()
    }

    @Test fun `storage system back clears ticked files across cards before leaving`() {
        storageSelections()
        compose.runOnIdle { backDispatcher.onBackPressed() }
        assertStorageSelectionsCleared()
    }

    @Test fun `adding a sidebar bookmark leaves the drawer open after cancelling its dialog`() {
        listed()
        val actions = LunaActions(grantFolder = {}, open = { _, _ -> }, openWith = { _, _, _ -> },
            shareReport = {}, requestFullAccess = {}, openSystemBrowser = {})
        compose.setContent { LunaApp(harness.viewModel, actions) }
        compose.onNodeWithContentDescription("Locations").performClick()
        val openDrawer = SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)
        val add = hasText("Add") and hasAnyAncestor(openDrawer)
        compose.onNode(hasScrollAction() and hasAnyAncestor(openDrawer)).performScrollToNode(hasText("Add"))
        compose.onNode(add).performClick()
        compose.onNodeWithText("Add bookmark").assertIsDisplayed()
        compose.runOnIdle { assertTrue(harness.state.overlay is Overlay.AddBookmark) }
        compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        compose.onNode(hasText("BOOKMARKS") and hasAnyAncestor(openDrawer)).assertIsDisplayed()
        compose.onNode(add).assertIsDisplayed()
    }
}
