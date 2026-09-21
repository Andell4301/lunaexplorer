package com.lunaexplorer.app.ui

import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.Overlay
import com.lunaexplorer.app.model.ViewerKind
import com.lunaexplorer.app.ui.GifFramesFixture.frames
import com.lunaexplorer.app.ui.GifFramesFixture.twoFrames
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GifFramesUiTest : RobolectricBrowserUiTest() {
    private val viewModel get() = compose.runOnUiThread {
        ViewModelProvider(compose.activity)[BrowserViewModel::class.java]
    }

    private fun open(name: String, bytes: ByteArray) {
        File(fixture.directory, name).writeBytes(bytes)
        awaitListing()
        val model = viewModel
        compose.runOnUiThread { model.refresh() }
        awaitText(name)
        val entry = model.state.value.entries.first { it.name == name }
        compose.runOnUiThread { model.showOverlay(Overlay.Viewer(entry, ViewerKind.IMAGE)) }
    }

    private fun awaitNode(description: String, matcher: SemanticsMatcher) =
        awaitCondition(description, 15_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    @Test fun aGifListsItsFramesAndStepsThroughThem() {
        open("moving.gif", twoFrames)
        awaitNode("Frames button", hasContentDescription("Frames"))
        val viewer = ShadowDialog.getLatestDialog() as OnBackPressedDispatcherOwner
        compose.onNodeWithContentDescription("Frames").performClick()

        awaitNode("Both frames listed", hasText("2  ·  500 ms"))
        compose.onNodeWithText("2 frames").assertIsDisplayed()
        compose.onNodeWithText("1  ·  500 ms").assertIsDisplayed()

        compose.onNodeWithText("2  ·  500 ms").performClick()
        awaitNode("Second frame at full size", hasText("Frame 2 of 2"))
        awaitNode("Its picture", hasTestTag("gifFrame"))
        compose.onNodeWithContentDescription("Next frame").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Previous frame").performClick()
        awaitNode("First frame", hasText("Frame 1 of 2"))

        compose.runOnUiThread { viewer.onBackPressedDispatcher.onBackPressed() }
        awaitNode("Back to the frames", hasTestTag("gifFrames"))
        compose.runOnUiThread { viewer.onBackPressedDispatcher.onBackPressed() }
        awaitNode("Back to the picture", hasContentDescription("Frames"))
        assertNotNull("Back from the frames must not close the viewer", viewModel.state.value.overlay)
    }

    @Test fun closingAFrameLeavesTheGridWhereItWas() {
        open("long.gif", frames(60))
        awaitNode("Frames button", hasContentDescription("Frames"))
        val viewer = ShadowDialog.getLatestDialog() as OnBackPressedDispatcherOwner
        compose.onNodeWithContentDescription("Frames").performClick()
        awaitNode("Frames listed", hasTestTag("gifFrames"))
        awaitNode("All of them counted", hasText("60 frames"))
        awaitCondition("Every frame decoded", 15_000) {
            compose.onNodeWithTag("gifFrames").fetchSemanticsNode().config[SemanticsProperties.IndexForKey](59) >= 0
        }
        compose.onNodeWithTag("gifFrames").performScrollToIndex(59)
        awaitNode("Last frame listed", hasText("60  ·  500 ms"))

        compose.onNodeWithText("60  ·  500 ms").performClick()
        awaitNode("Last frame at full size", hasText("Frame 60 of 60"))
        compose.runOnUiThread { viewer.onBackPressedDispatcher.onBackPressed() }

        awaitNode("Back to the frames", hasTestTag("gifFrames"))
        compose.onNodeWithText("60  ·  500 ms").assertIsDisplayed()
    }

    @Test fun theGalleryStillPagesAfterFramesWereOpenedOnAZoomedPicture() {
        File(fixture.directory, "next.gif").writeBytes(twoFrames)
        open("moving.gif", twoFrames)
        awaitNode("Frames button", hasContentDescription("Frames"))
        val viewer = ShadowDialog.getLatestDialog() as OnBackPressedDispatcherOwner
        awaitNode("Picture shown", hasContentDescription("moving.gif"))
        compose.onNodeWithText("1 of 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("moving.gif").performTouchInput { doubleClick() }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Frames").performClick()
        awaitNode("Frames listed", hasTestTag("gifFrames"))
        compose.runOnUiThread { viewer.onBackPressedDispatcher.onBackPressed() }
        awaitNode("Back to the picture", hasContentDescription("Frames"))

        compose.onNodeWithContentDescription("moving.gif").performTouchInput { swipeLeft() }
        // The file list behind the viewer names both pictures; only the viewer counts them.
        awaitNode("The next picture", hasText("2 of 2"))
    }

    @Test fun aStillPictureOffersNoFrames() {
        val still = twoFrames.copyOfRange(0, 62) + byteArrayOf(59)
        open("still.gif", still)
        awaitNode("Picture shown or refused", hasContentDescription("still.gif") or hasText("This image could not be decoded"))
        compose.onAllNodesWithContentDescription("Frames").assertCountEquals(0)
    }
}
