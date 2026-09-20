package com.lunaexplorer.app.ui

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.storage.LocalRoot
import com.lunaexplorer.core.RootKind
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class ShizukuUiTest : RobolectricBrowserUiTest() {
    @get:Rule val volumes = TemporaryFolder()

    private fun viewModel() = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }

    /** A volume of the kind whose Android/data and Android/obb Android closes, with [folder] open on screen. */
    private fun open(folder: String): BrowserViewModel {
        awaitListing()
        val viewModel = viewModel()
        // Outside the fixture, which sits in Luna's own Android/data folder: the one Android does show it.
        val volume = volumes.newFolder("volume")
        val closed = File(volume, folder).apply { mkdirs() }
        compose.runOnUiThread {
            fixture.graph.additionalRoots = fixture.graph.additionalRoots + LocalRoot("volume", "Volume", volume, RootKind.INTERNAL, followLinks = true)
            viewModel.refreshAccess()
        }
        awaitCondition("the volume is a root", 10_000) { viewModel.state.value.roots.any { it.title == "Volume" } }
        compose.runOnUiThread { viewModel.goTo(closed.canonicalPath) }
        awaitCondition("the folder is on screen", 10_000) { viewModel.state.value.directoryPath == closed.canonicalPath && !viewModel.state.value.loading }
        return viewModel
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun switchingShizukuOnSaysWhatIsMissingAndTakesNoOtherWayInAway() {
        val viewModel = open("Android/data")
        compose.onNodeWithText("Try alternate method").assertExists()

        compose.onNodeWithText("Use Shizuku").performClick()

        awaitCondition("the setting is on", 10_000) { viewModel.state.value.preferences.shizuku }
        compose.waitUntil(10_000) { shown("Shizuku is not installed") }
        listOf("Try alternate method", "Add this folder", "Open in system files app").forEach {
            compose.onNodeWithText(it).assertExists("\"$it\" went when Shizuku was switched on, though Shizuku is not there to use")
        }
        compose.onNodeWithText("Use Shizuku").assertDoesNotExist()
    }

    @Test
    fun obbKeepsEveryOtherWayInBesideShizuku() {
        val viewModel = open("Android/obb")
        val ways = listOf("Allow installing apps", "Open in system files app", "Try alternate method", "Add this folder")
        ways.forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Use Shizuku").assertExists()

        compose.onNodeWithText("Use Shizuku").performClick()

        awaitCondition("the setting is on", 10_000) { viewModel.state.value.preferences.shizuku }
        compose.waitUntil(10_000) { shown("Shizuku is not installed") }
        ways.forEach { compose.onNodeWithText(it).assertExists("\"$it\" went when Shizuku was switched on, though Shizuku is not there to use") }
    }

    @Test
    fun theStorageAccessPageSwitchesItAndShowsItsState() {
        awaitListing()
        val viewModel = viewModel()
        openSettingsPage("Storage access")
        compose.onNodeWithText("Use Shizuku for Android/data and Android/obb").performScrollTo().performClick()

        awaitCondition("the setting is on", 10_000) { viewModel.state.value.preferences.shizuku }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Shizuku is not installed") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
