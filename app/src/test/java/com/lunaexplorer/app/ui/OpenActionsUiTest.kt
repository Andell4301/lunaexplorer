package com.lunaexplorer.app.ui

import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class OpenActionsUiTest : RobolectricBrowserUiTest() {
    @Test
    @Config(qualifiers = "w393dp-h640dp-xhdpi")
    fun backClosesTheOpenAsSheetFromWhereverItHasBeenDraggedTo() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        val file = viewModel.state.value.entries.first { it.name == "beta.txt" }
        compose.runOnUiThread { viewModel.showOverlay(Overlay.Opening(file)) }

        val dismissible = SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodes(dismissible).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(dismissible).onFirst().performTouchInput { swipeUp() }
        compose.waitForIdle()
        // The sheet is a dialog with its own back dispatcher; the Activity's does not see its Back events.
        val dialog = ShadowDialog.getLatestDialog() as OnBackPressedDispatcherOwner
        compose.runOnUiThread { dialog.onBackPressedDispatcher.onBackPressed() }

        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.overlay == null }
        assertEquals("Back must close it rather than lower it", null, viewModel.state.value.overlay)
    }

    // Older androidx.fragment versions rejected ActivityResultRegistry request codes above 16 bits.
    @Test
    fun launchingThePickerDoesNotCrash() {
        awaitListing()
        openSettingsPage("Storage access")
        compose.onNodeWithText("Add a location").performScrollTo().performClick()
        compose.waitForIdle()

        val launched = shadowOf(compose.activity).nextStartedActivityForResult
        assertNotNull("The picker must have been asked for", launched)
    }
}
