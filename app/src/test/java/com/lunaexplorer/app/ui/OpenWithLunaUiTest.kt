package com.lunaexplorer.app.ui

import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.storage.ViewerAdvertising
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class OpenWithLunaUiTest : RobolectricBrowserUiTest() {
    @Test
    fun aSwitchStopsAdvertisingItsKindAndTheIndexCountsWhatIsLeft() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        val application = compose.activity.application
        val images = ViewerAdvertising.componentFor(application, ViewerAdvertising.kinds.first { it.key == "images" })

        openSettingsPage("Open with Luna")
        compose.onNodeWithContentDescription("Images").assertIsOn()
        compose.onNodeWithContentDescription("Audio and video").assertIsOn()

        compose.onNodeWithContentDescription("Images").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); "images" !in viewModel.state.value.preferences.openWithLuna }
        compose.onNodeWithContentDescription("Images").assertIsOff()
        compose.waitUntil(10_000) {
            application.packageManager.getComponentEnabledSetting(images) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        assertEquals("Only the kind switched off stops being advertised",
            ViewerAdvertising.allKeys - "images", viewModel.state.value.preferences.openWithLuna)

        // The browser behind the dialog has its own Back button.
        compose.onNode(hasContentDescription("Back") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("5 kinds").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
