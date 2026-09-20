package com.lunaexplorer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.VideoExitBehavior
import com.lunaexplorer.app.playback.BackgroundPlayback
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class MediaViewerPipTest {
    private val harness = BrowserViewModelHarness().startingWith { folder ->
        File(folder, "clip.mp4").writeBytes(ByteArray(64))
        File(folder, "song.mp3").writeBytes(ByteArray(64))
    }.withSession {
        it.copy(preferences = it.preferences.copy(thumbnails = false, introSeen = true,
            videoExitBehavior = VideoExitBehavior.PICTURE_IN_PICTURE))
    }
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(harness).around(compose)

    @Before fun awaitSession() {
        assertTrue("The saved session is restored", harness.awaitUntil(rounds = 2_000) { harness.state.ready })
    }

    private class FakePip(override val supported: Boolean = true, val acceptsBackground: Boolean = true) : PipHost {
        var backgroundRequests = 0
        var publishedBeforeLeaving = false
        override var active by mutableStateOf(false)
            private set
        var armed: PipRequest? = null
            private set
        override val phase: PipPhase get() = if (active) PipPhase.ACTIVE else PipPhase.NONE
        override fun arm(request: PipRequest?, onAction: (PipAction) -> Unit, onClosed: () -> Unit) {
            armed = request
        }
        override fun enter(): Boolean { active = true; return true }
        override fun moveToBackground(): Boolean {
            backgroundRequests++
            publishedBeforeLeaving = BackgroundPlayback.session != null
            return acceptsBackground
        }
    }

    private fun clip(): Entry = runBlocking {
        val ref: NodeRef = requireNotNull(harness.graph.local.referenceTo(File(harness.directory, "clip.mp4").path))
        harness.graph.local.stat(ref)
    }

    private fun song(): Entry = runBlocking {
        val ref: NodeRef = requireNotNull(harness.graph.local.referenceTo(File(harness.directory, "song.mp3").path))
        harness.graph.local.stat(ref)
    }

    private fun show(pip: PipHost, owner: LifecycleOwner? = null) {
        val entry = clip()
        assertTrue("The viewer only arms picture in picture for a video", entry.mimeType.startsWith("video/"))
        compose.setContent {
            CompositionLocalProvider(LocalPictureInPicture provides pip,
                LocalLifecycleOwner provides (owner ?: LocalLifecycleOwner.current)) {
                MaterialTheme { Surface { RichMediaViewer(entry, harness.viewModel) { } } }
            }
        }
        awaitPlayer(entry)
    }

    private fun awaitPlayer(entry: Entry) {
        val ready = if (entry.mimeType.startsWith("video/")) hasTestTag("playerSurface")
            else hasContentDescription("Audio track") and isEnabled()
        compose.waitUntil(10_000) {
            compose.onAllNodes(ready).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    @Test fun `video offers picture in picture and background actions`() {
        show(FakePip())
        compose.onNodeWithContentDescription("Picture in picture").assertExists()
        compose.onNodeWithContentDescription("Background play").assertExists()
    }

    @Test fun `no button where the device does not support it`() {
        show(FakePip(supported = false))
        compose.onAllNodesWithContentDescription("Picture in picture").assertCountEquals(0)
    }

    @Test fun `manual picture in picture remains available with automatic exit off`() {
        harness.viewModel.setPreferences(harness.state.preferences.copy(videoExitBehavior = VideoExitBehavior.OFF))
        harness.idle()
        val pip = FakePip()
        show(pip)
        assertFalse(requireNotNull(pip.armed).autoEnter)

        compose.onNodeWithContentDescription("Picture in picture").performClick()
        compose.waitForIdle()

        assertTrue(pip.active)
        compose.onNodeWithTag("playerSurface").assertIsDisplayed()
    }

    @Test fun `background action publishes before leaving and keeps the saved exit preference`() {
        harness.viewModel.setPreferences(harness.state.preferences.copy(videoExitBehavior = VideoExitBehavior.OFF))
        harness.idle()
        val pip = FakePip()
        show(pip)

        compose.onNodeWithContentDescription("Background play").performClick()
        compose.waitForIdle()

        assertEquals(1, pip.backgroundRequests)
        assertTrue(pip.publishedBeforeLeaving)
        assertFalse(requireNotNull(pip.armed).autoEnter)
        assertNotNull(BackgroundPlayback.session)
        assertEquals(VideoExitBehavior.OFF, harness.state.preferences.videoExitBehavior)
    }

    @Test fun `returning after an explicit background action restores the saved video policy`() {
        class Owner : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val owner = compose.runOnUiThread {
            Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        }
        val pip = FakePip()
        show(pip, owner)
        compose.onNodeWithContentDescription("Background play").performClick()
        compose.waitForIdle()
        assertNotNull(BackgroundPlayback.session)
        assertFalse(requireNotNull(pip.armed).autoEnter)

        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()

        assertNull(BackgroundPlayback.session)
        assertTrue(requireNotNull(pip.armed).autoEnter)
    }

    @Test fun `background action works when picture in picture is unavailable`() {
        val pip = FakePip(supported = false)
        show(pip)
        compose.onNodeWithContentDescription("Background play").performClick()
        compose.waitForIdle()

        assertEquals(1, pip.backgroundRequests)
        assertTrue(pip.publishedBeforeLeaving)
        assertNotNull(BackgroundPlayback.session)
        assertNull(pip.armed)
    }

    @Test fun `a refused background action restores the automatic exit policy`() {
        val pip = FakePip(acceptsBackground = false)
        show(pip)

        compose.onNodeWithContentDescription("Background play").performClick()
        compose.waitForIdle()

        assertEquals(1, pip.backgroundRequests)
        assertTrue(requireNotNull(pip.armed).autoEnter)
        assertNull(BackgroundPlayback.session)
        compose.onNodeWithText("Unavailable").assertExists()
    }

    @Test fun `in picture in picture only the picture is drawn`() {
        val pip = FakePip()
        show(pip)
        assertNotNull("The viewer arms a video it is showing", pip.armed)

        // Entered without the button, which hides the chrome itself: only the PiP composition may do it here.
        compose.runOnUiThread { pip.enter() }
        compose.waitForIdle()

        listOf("Close", "Picture in picture", "More", "Back 10 seconds", "Forward 10 seconds",
            "Lock the picture", "Turn the picture").forEach { label ->
            compose.onAllNodesWithContentDescription(label).assertCountEquals(0)
        }
        compose.onNodeWithTag("playerSurface").assertIsDisplayed()
    }

    @Test fun `a paused file restored across a recreation stays paused`() {
        val entry = song()
        assertTrue("This one is audio, which has no surface to attach to", entry.mimeType.startsWith("audio/"))
        harness.viewModel.rememberPlayback(PlaybackResume(entry.ref, position = 4_000L, playing = false))

        compose.setContent {
            CompositionLocalProvider(LocalPictureInPicture provides FakePip()) {
                MaterialTheme { Surface { RichMediaViewer(entry, harness.viewModel) { } } }
            }
        }
        awaitPlayer(entry)

        compose.onNodeWithContentDescription("Play").assertExists()
        compose.onAllNodesWithContentDescription("Pause").assertCountEquals(0)
    }

    @Test fun `changing the default exit behavior controls automatic picture in picture`() {
        val pip = FakePip()
        show(pip)
        assertTrue(requireNotNull(pip.armed).autoEnter)

        harness.viewModel.setPreferences(harness.state.preferences.copy(videoExitBehavior = VideoExitBehavior.BACKGROUND))
        harness.idle()
        compose.waitForIdle()
        assertFalse(requireNotNull(pip.armed).autoEnter)
        assertNotNull(BackgroundPlayback.session)

        harness.viewModel.setPreferences(harness.state.preferences.copy(videoExitBehavior = VideoExitBehavior.OFF))
        harness.idle()
        compose.waitForIdle()
        assertFalse(requireNotNull(pip.armed).autoEnter)
        assertNull(BackgroundPlayback.session)
        compose.onNodeWithContentDescription("Picture in picture").assertExists()
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-xhdpi")
    fun `gesture switches in the player update the same saved settings`() {
        show(FakePip())
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Gestures").performClick()

        for ((label, enabled) in listOf<Pair<String, () -> Boolean>>(
            "Double tap controls" to { harness.state.preferences.playerDoubleTap },
            "Swipe to seek" to { harness.state.preferences.playerSwipeSeek },
            "Volume gesture" to { harness.state.preferences.playerVolumeGesture },
            "Brightness gesture" to { harness.state.preferences.playerBrightnessGesture },
        )) {
            compose.onNodeWithText(label).performScrollTo().performClick()
            compose.waitForIdle()
            assertFalse(enabled())
        }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Gestures").performClick()
        compose.onNodeWithText("Swipe to seek").performClick()
        compose.waitForIdle()
        assertTrue(harness.state.preferences.playerSwipeSeek)
    }

    @Test fun `the audio player can switch background play without changing video behavior`() {
        val pip = FakePip()
        val entry = song()
        compose.setContent {
            CompositionLocalProvider(LocalPictureInPicture provides pip) {
                MaterialTheme { Surface { RichMediaViewer(entry, harness.viewModel) { } } }
            }
        }
        awaitPlayer(entry)
        assertNull(pip.armed)
        assertNotNull(BackgroundPlayback.session)

        compose.onNodeWithText("Background play").performClick()
        compose.waitForIdle()
        assertFalse(harness.state.preferences.audioBackground)
        assertNull(BackgroundPlayback.session)

        compose.onNodeWithText("Background play").performClick()
        compose.waitForIdle()
        assertTrue(harness.state.preferences.audioBackground)
        assertNotNull(BackgroundPlayback.session)
        assertEquals(VideoExitBehavior.PICTURE_IN_PICTURE, harness.state.preferences.videoExitBehavior)
    }

    @Test fun `closing the video releases its background session and disarms picture in picture`() {
        harness.viewModel.setPreferences(harness.state.preferences.copy(videoExitBehavior = VideoExitBehavior.BACKGROUND))
        harness.idle()
        val pip = FakePip()
        val entry = clip()
        var open by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalPictureInPicture provides pip) {
                MaterialTheme { Surface {
                    if (open) RichMediaViewer(entry, harness.viewModel) { open = false }
                } }
            }
        }
        awaitPlayer(entry)
        assertNotNull(BackgroundPlayback.session)

        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()

        assertFalse(open)
        assertNull(BackgroundPlayback.session)
        assertNull(pip.armed)
    }
}
