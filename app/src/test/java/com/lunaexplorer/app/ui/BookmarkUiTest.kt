package com.lunaexplorer.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class BookmarkUiTest : RobolectricBrowserUiTest() {

    @Test
    fun lunasOwnDataNeverAppearsAmongTheStorageLocations() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }

        compose.runOnUiThread { viewModel.setAppDataBookmark(true) }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.bookmarks.appDataOn(viewModel.state.value) && viewModel.state.value.roots.any { it.hidden }
        }

        compose.runOnUiThread { viewModel.showScreen(Screen.HOME) }
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.screen == Screen.HOME }
        compose.onAllNodesWithText("Luna app data").assertCountEquals(0)
    }

    @Test
    fun addingABookmarkFromHomePicksAFolderAndKeepsItsName() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        compose.runOnUiThread { viewModel.showScreen(Screen.HOME) }
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.screen == Screen.HOME }

        compose.onAllNodesWithText("Add").onFirst().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.overlay is Overlay.AddBookmark }

        val overlay = viewModel.state.value.overlay as Overlay.AddBookmark
        assertEquals(BookmarkList.HOME, overlay.list)

        compose.onNode(hasText("Name") and hasAnyAncestor(isDialog())).performTextReplacement("Alpha on home")
        chooseAlphaFolder()
        compose.onNode(hasText("Name") and hasAnyAncestor(isDialog())).assertTextContains("Alpha on home")
        compose.onNode(hasText("Add") and hasAnyAncestor(isDialog())).performClick()

        awaitCondition("Home bookmark saved", 10_000) {
            viewModel.state.value.homeBookmarks.any { it.title == "Alpha on home" }
        }
        val bookmark = viewModel.state.value.homeBookmarks.single { it.title == "Alpha on home" }
        assertEquals(File(fixture.directory, "alpha").absolutePath, (bookmark.destination as Destination.Place).path)
        assertEquals(0, viewModel.state.value.bookmarks.count { it.title == "Alpha on home" })
    }

    @Test
    fun sidebarPickerKeepsTheNameOnCancelAndReplacesAToolDestination() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        compose.runOnUiThread { viewModel.showScreen(Screen.RECYCLE_BIN) }
        awaitCondition("Recycle bin opened", 10_000) { viewModel.state.value.screen == Screen.RECYCLE_BIN }

        compose.onNodeWithContentDescription("Locations").performClick()
        val openDrawer = SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)
        compose.onNode(hasScrollAction() and hasAnyAncestor(openDrawer)).performScrollToNode(hasText("Add"))
        compose.onNode(hasText("Add") and hasAnyAncestor(openDrawer)).performClick()
        awaitCondition("Sidebar bookmark editor opened", 10_000) { viewModel.state.value.overlay is Overlay.AddBookmark }
        val overlay = viewModel.state.value.overlay as Overlay.AddBookmark
        assertEquals(Destination.Tool(Screen.RECYCLE_BIN), overlay.proposal.destination)

        compose.onNode(hasText("Name") and hasAnyAncestor(isDialog())).performTextReplacement("Alpha in sidebar")
        compose.onNode(hasText("Browse") and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(hasContentDescription("Back") and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(hasText("Name") and hasAnyAncestor(isDialog())).assertTextContains("Alpha in sidebar")
        assertEquals(overlay, viewModel.state.value.overlay)

        chooseAlphaFolder()
        compose.onNode(hasText("Add") and hasAnyAncestor(isDialog())).performClick()
        awaitCondition("Sidebar bookmark saved", 10_000) {
            viewModel.state.value.bookmarks.any { it.title == "Alpha in sidebar" }
        }
        val bookmark = viewModel.state.value.bookmarks.single { it.title == "Alpha in sidebar" }
        assertEquals(File(fixture.directory, "alpha").absolutePath, (bookmark.destination as Destination.Place).path)
        assertEquals(0, viewModel.state.value.homeBookmarks.count { it.title == "Alpha in sidebar" })
    }

    private fun chooseAlphaFolder() {
        compose.onNode(hasText("Browse") and hasAnyAncestor(isDialog())).performClick()
        val inPicker = hasAnyAncestor(isDialog())
        for (name in listOf("Test fixture", fixture.token, "alpha")) {
            awaitCondition("Picker lists $name", 10_000) {
                compose.onAllNodes(hasText(name) and inPicker).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasScrollAction() and inPicker).performScrollToNode(hasText(name))
            compose.onNode(hasText(name) and inPicker).performClick()
        }
        awaitCondition("Picker selects alpha", 10_000) {
            compose.onAllNodes(hasText(File(fixture.directory, "alpha").absolutePath) and inPicker)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Choose this folder") and inPicker).performClick()
    }
}
