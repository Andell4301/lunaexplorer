package com.lunaexplorer.app.ui

import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.Overlay
import com.lunaexplorer.app.model.ViewerKind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageBarUiTest : RobolectricBrowserUiTest() {
    /** Two frames, red then blue: a still decode is not implemented on the JVM, an animated one is. */
    private val picture = byteArrayOf(
        71, 73, 70, 56, 57, 97, 2, 0, 2, 0, -16, 0, 0, -1, 0, 0, 0, 0, -1, 33, -1, 11, 78, 69, 84,
        83, 67, 65, 80, 69, 50, 46, 48, 3, 1, 0, 0, 0, 33, -7, 4, 0, 50, 0, 0, 0, 44, 0, 0, 0, 0, 2,
        0, 2, 0, 0, 2, 3, 4, -128, 2, 0, 33, -7, 4, 0, 50, 0, 0, 0, 44, 0, 0, 0, 0, 2, 0, 2, 0, 0,
        2, 3, 76, -110, 2, 0, 59,
    )

    private fun counted(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test fun aTapHidesTheBarAndAnotherBringsItBack() {
        File(fixture.directory, "first.gif").writeBytes(picture)
        File(fixture.directory, "second.gif").writeBytes(picture)
        awaitListing()
        val model = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        compose.runOnUiThread { model.refresh() }
        awaitText("first.gif")
        val entry = model.state.value.entries.first { it.name == "first.gif" }
        compose.runOnUiThread { model.showOverlay(Overlay.Viewer(entry, ViewerKind.IMAGE)) }
        awaitCondition("Picture shown", 15_000) {
            compose.onAllNodesWithContentDescription("first.gif").fetchSemanticsNodes().isNotEmpty()
        }
        // The file list behind the viewer names the picture too; only the viewer's bar counts them.
        compose.onNodeWithText("1 of 2").assertIsDisplayed()

        compose.onNodeWithContentDescription("first.gif").performClick()
        awaitCondition("The bar left the picture", 5_000) { !counted("1 of 2") }

        compose.onNodeWithContentDescription("first.gif").performClick()
        awaitCondition("The bar is back", 5_000) { counted("1 of 2") }
    }
}
