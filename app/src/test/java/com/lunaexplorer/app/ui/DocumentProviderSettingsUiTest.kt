package com.lunaexplorer.app.ui

import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.ServedFolder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class DocumentProviderSettingsUiTest : RobolectricBrowserUiTest() {

    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun theServedListTakesTypedAndBrowsedFoldersAndGivesOneBack() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        val here = requireNotNull(viewModel.state.value.directoryPath)
        openSettingsPage("Document provider")
        val field = hasContentDescription("Served folder") and hasSetTextAction()
        // The browser behind the settings dialog has its own "Add".
        val add = hasText("Add") and hasAnyAncestor(isDialog())
        fun openAdd() {
            compose.onNodeWithText("Add a folder").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.waitForIdle(); compose.onAllNodes(field).fetchSemanticsNodes().isNotEmpty() }
        }

        compose.onAllNodes(field).assertCountEquals(0)
        openAdd()
        compose.onNode(add).assertIsNotEnabled()
        compose.onNode(field).performScrollTo().performTextReplacement("/typed/path ")
        compose.onNode(add).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.preferences.servedFolders == listOf(ServedFolder("/typed/path "))
        }
        compose.onAllNodes(field).assertCountEquals(0)

        openAdd()
        compose.onNode(field).performScrollTo().performTextReplacement("/typed/path ")
        compose.onNode(add).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(ServedFolder("/typed/path ")), viewModel.state.value.preferences.servedFolders)

        // Browsed to: down from the storage to the folder on screen, which is then the one chosen.
        openAdd()
        compose.onNodeWithText("Browse").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Test fixture").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Test fixture").onLast().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(fixture.token).fetchSemanticsNodes().size > 1 }
        compose.onAllNodesWithText(fixture.token).onLast().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("alpha").fetchSemanticsNodes().size > 1 }
        compose.onNodeWithText("Choose this folder").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Choose this folder").fetchSemanticsNodes().isEmpty() }
        compose.onNode(add).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.preferences.servedFolders == listOf(ServedFolder("/typed/path "), ServedFolder(here))
        }

        compose.onNodeWithText("Serve folders to other apps").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.preferences.documentsProvider }

        compose.onNodeWithContentDescription("Remove /typed/path ").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.preferences.servedFolders == listOf(ServedFolder(here))
        }
        assertEquals(listOf(ServedFolder(here)), viewModel.state.value.preferences.servedFolders)
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun aStorageIsOfferedByNameAndFillsInItsPath() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        val storage = requireNotNull(fixture.directory.parentFile).absolutePath
        openSettingsPage("Document provider")
        compose.onNodeWithText("Add a folder").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Test fixture") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(hasText("Test fixture") and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.onNode(hasText("Add") and hasAnyAncestor(isDialog())).performScrollTo().performClick()

        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.preferences.servedFolders == listOf(ServedFolder(storage))
        }
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun renamingAServedFolderKeepsThePathItIsServedUnder() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        compose.runOnUiThread {
            viewModel.setPreferences(viewModel.state.value.preferences
                .copy(servedFolders = listOf(ServedFolder("/served/pictures "))))
        }
        openSettingsPage("Document provider")
        val row = hasText("/served/pictures ") and hasAnyAncestor(isDialog())
        compose.waitUntil(10_000) { compose.waitForIdle(); compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("pictures ") and hasAnyAncestor(isDialog())).assertExists()

        val field = hasContentDescription("Name for /served/pictures ") and hasSetTextAction()
        compose.onAllNodes(field).assertCountEquals(0)
        compose.onNode(row).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); compose.onAllNodes(field).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(field).performScrollTo().performTextReplacement("Camera")
        compose.onNode(hasText("Save") and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.preferences.servedFolders == listOf(ServedFolder("/served/pictures ", "Camera"))
        }
        assertEquals("Renaming must not move the path other apps hold grants against",
            listOf(ServedFolder("/served/pictures ", "Camera")), viewModel.state.value.preferences.servedFolders)
    }
}
