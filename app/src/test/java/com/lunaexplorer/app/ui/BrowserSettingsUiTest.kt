package com.lunaexplorer.app.ui

import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.*
import com.lunaexplorer.app.storage.MediaCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class BrowserSettingsUiTest : RobolectricBrowserUiTest() {

    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun settingsListsTheFoldersThatKeepAViewOfTheirOwnAndCanForgetOne() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.folderKey != null }
        val name = requireNotNull(viewModel.state.value.directoryPath).substringAfterLast('/')
        compose.runOnUiThread {
            viewModel.setViewOptions(
                viewModel.state.value.preferences.copy(view = ViewMode.GRID_LARGE), thisFolderOnly = true)
        }
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.folderViews.isNotEmpty() }

        openSettingsPage("Files and folders")
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("Per-folder display settings").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("One folder keeps a layout or ordering of its own").assertExists()
        compose.onNodeWithText("Per-folder display settings").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Large grid · Natural").assertExists()

        compose.onNodeWithContentDescription("Forget $name").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.folderViews.isEmpty() }
        assertTrue("The folder goes back to the layout everything else has",
            viewModel.state.value.folderViews.isEmpty())
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun settingsCarriesTheDefaultLayoutAndOrdering() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        openSettingsPage("Files and folders")

        compose.onNodeWithText("Compact").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.preferences.view == ViewMode.COMPACT
        }
        compose.onNodeWithText("Ascending").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.preferences.descending }
        compose.onNodeWithText("Descending").assertExists()

        compose.onNodeWithText("Sections").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.preferences.sections }

        assertEquals(ViewMode.COMPACT, viewModel.state.value.preferences.view)
        assertTrue("And it is the default, not this folder's own",
            viewModel.state.value.folderViews.isEmpty())
    }

    @Test
    fun networkThumbnailBudgetAcceptsCustomAmountsAndRejectsInvalidInput() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        openSettingsPage("Network")
        compose.onNodeWithText("SMB").performClick()
        compose.onNodeWithText("Custom").performScrollTo().performClick()
        compose.onNodeWithText("MB per thumbnail").performTextReplacement("0")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        assertEquals(NetworkThumbnails.MB_25, viewModel.state.value.preferences.thumbnailsOn("smb"))

        compose.onNodeWithText("MB per thumbnail").performTextReplacement("999999999999999999999")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("MB per thumbnail").performTextReplacement("75")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.preferences.thumbnailsOn("smb").bytes == (75L shl 20)
        }
        compose.onNodeWithText("Custom: 75 MB").assertIsSelected()
        assertEquals("Each provider keeps its own", NetworkThumbnails.MB_25, viewModel.state.value.preferences.thumbnailsOn("b2"))
    }

    @Test
    fun eachNetworkProviderHasAPageOfItsOwnAndBackLeavesThroughTheNetworkPage() {
        awaitListing()
        openSettingsPage("Network")
        compose.onNodeWithText("Credential vault", ignoreCase = true).assertExists()

        compose.onNodeWithText("Backblaze B2").performClick()
        compose.onNodeWithText("Add a B2 account").assertExists()
        compose.onNodeWithText("Add an SMB server").assertDoesNotExist()
        compose.onNodeWithText("Network thumbnails").performScrollTo().assertExists()

        // The browser behind the settings window has a Back of its own.
        val back = hasContentDescription("Back") and hasAnyAncestor(isDialog())
        compose.onNode(back).performClick()
        compose.onNodeWithText("SMB").performClick()
        compose.onNodeWithText("Add an SMB server").assertExists()

        compose.onNode(back).performClick()
        compose.onNode(back).performClick()
        compose.onNodeWithText("Storage access").assertExists()
    }

    @Test
    fun aMessageRaisedWhileSettingsIsOpenIsShownOverSettings() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        openSettingsPage("Network")

        compose.runOnUiThread { viewModel.showMessage("Set a screen lock on this device first") }

        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodes(hasText("Set a screen lock on this device first") and hasAnyAncestor(isDialog()))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun subtitleAppearanceCanBeChangedFromTheVideoPlayerPage() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        fun appearance() = viewModel.state.value.preferences.subtitleAppearance
        openSettingsPage("Video player")

        compose.onNodeWithText("Custom appearance").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); appearance().enabled }
        compose.onNodeWithText("Bold text").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); appearance().bold }

        compose.onNodeWithText("Reset appearance").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); appearance() == SubtitleAppearance() }
    }

    @Test
    fun mediaCategoriesIsAPageWhereAnExtensionCanBeAdded() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        openSettingsPage("Files and folders")
        compose.onNodeWithText("Media categories").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Extension").performScrollTo().performTextReplacement(".M4B")
        compose.onNodeWithText("Add").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.preferences.categoryExtras["AUDIO"] == setOf("m4b")
        }

        // The browser behind the settings dialog has a Back of its own; the dialog's is the last.
        compose.onAllNodesWithContentDescription("Back").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Files and folders").assertExists()
    }

    @Test
    fun anAddedExtensionCanBeTakenBackAndABuiltInOneCannot() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        compose.runOnUiThread {
            viewModel.setPreferences(viewModel.state.value.preferences.copy(
                categoryExtras = mapOf("PACKAGES" to setOf("obb"))))
            viewModel.showCategory(MediaCategory.PACKAGES)
        }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("OBB").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("APK").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
        val added = compose.onNodeWithText("OBB")
        added.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        added.performTouchInput { longClick() }

        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.preferences.categoryExtras["PACKAGES"].orEmpty().isEmpty()
        }
        assertTrue("The chip goes with what it stood for",
            compose.onAllNodesWithText("OBB").fetchSemanticsNodes().isEmpty())
    }

    @Test
    @Config(qualifiers = "w393dp-h640dp-xhdpi")
    fun theCustomGridTakesASizeWhicheverScopeTheSheetIsIn() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        compose.runOnUiThread { viewModel.showOverlay(Overlay.ViewOptions) }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("Custom grid").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Custom grid").performScrollTo().performClick()

        val field = hasContentDescription("Tile size") and hasSetTextAction()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodes(field).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(field).onFirst().performScrollTo().performTextReplacement("151")
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.preferences.gridCell == 151 }

        compose.onNodeWithText("This folder").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.folderHasOwnView() }
        compose.onAllNodes(field).onFirst().performScrollTo().performTextReplacement("197")
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.preferences.gridCell == 197 }

        assertEquals("A size set with the folder's own layout showing must still be kept",
            197, viewModel.state.value.preferences.gridCell)
        assertEquals("And the layout stays the folder's", ViewMode.GRID_CUSTOM,
            viewModel.state.value.folderViews[viewModel.state.value.folderKey]?.view)

        // The sheet is taller than this short screen; its last setting must still be reachable.
        compose.onNodeWithText("Hidden files").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.preferences.showHidden }

        dragTheSheetUpAndPressBack()
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.overlay == null }
    }

    /**
     * Expands the sheet, then presses Back through the sheet's own dialog dispatcher. Waiting for
     * the Collapse action proves a partial-height stop exists, so a Back that only collapses fails.
     */
    private fun dragTheSheetUpAndPressBack() {
        val canExpand = SemanticsMatcher.keyIsDefined(SemanticsActions.Expand)
        val canCollapse = SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse)
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodes(canExpand).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(canCollapse).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodes(canExpand).fetchSemanticsNodes().isNotEmpty()) {
            compose.onAllNodes(canExpand).onFirst().performSemanticsAction(SemanticsActions.Expand)
        }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodes(canCollapse).fetchSemanticsNodes().isNotEmpty()
        }
        val dialog = ShadowDialog.getLatestDialog() as OnBackPressedDispatcherOwner
        compose.runOnUiThread { dialog.onBackPressedDispatcher.onBackPressed() }
    }
}
