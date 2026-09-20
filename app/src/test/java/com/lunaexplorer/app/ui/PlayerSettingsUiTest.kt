package com.lunaexplorer.app.ui

import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.VideoExitBehavior
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class PlayerSettingsUiTest : RobolectricBrowserUiTest() {

    private fun pictureInPicture(available: Boolean) {
        shadowOf(RuntimeEnvironment.getApplication().packageManager)
            .setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, available)
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun videoExitBehaviorCanBeChangedWithoutEnablingPlaybackFeatures() {
        pictureInPicture(true)
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        openSettingsPage("Video player")

        for (behavior in listOf(VideoExitBehavior.BACKGROUND, VideoExitBehavior.OFF, VideoExitBehavior.PICTURE_IN_PICTURE)) {
            compose.onNodeWithText(behavior.label).performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                viewModel.state.value.preferences.videoExitBehavior == behavior
            }
        }
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun exitPreferenceCanBeChosenBeforePictureInPictureIsAvailable() {
        pictureInPicture(false)
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        openSettingsPage("Video player")

        compose.onNodeWithText("Off").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.preferences.videoExitBehavior == VideoExitBehavior.OFF
        }
        compose.onNodeWithText("Picture in picture").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.preferences.videoExitBehavior == VideoExitBehavior.PICTURE_IN_PICTURE
        }
    }

    @Test
    fun audioBackgroundPlayCanBeDisabledAndEnabledIndependently() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        val videoExit = viewModel.state.value.preferences.videoExitBehavior
        openSettingsPage("Audio player")

        compose.onNodeWithText("Background play").performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            !viewModel.state.value.preferences.audioBackground
        }
        compose.onNodeWithText("Background play").performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.preferences.audioBackground
        }
        assertEquals(videoExit, viewModel.state.value.preferences.videoExitBehavior)
    }
}
