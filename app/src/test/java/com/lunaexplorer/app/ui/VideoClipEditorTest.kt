package com.lunaexplorer.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoClipEditorTest {
    @get:Rule val compose = createComposeRule()
    private var saved: List<Any>? = null
    private var previewed: Pair<Long, Long>? = null
    private var position by mutableStateOf(23_456L)
    private var playing by mutableStateOf(false)
    private var stepping by mutableStateOf(false)
    private var buffering by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var exportProgress by mutableStateOf<VideoClipProgress?>(null)
    private var playCount = 0
    private var pauseCount = 0
    private var cancelCount = 0
    private var folderChoices = 0
    private var destination by mutableStateOf("Movies")
    private val frameSteps = mutableListOf<Boolean>()

    private fun show(subtitles: Boolean = true, preview: Boolean = false, scale: Float = 1f,
                     readPosition: () -> Long = { position }) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
                MaterialTheme {
                    Column(Modifier.fillMaxSize()) {
                        if (preview) ViewerBar("Cut video", {})
                        VideoClipEditor("movie-clip.mp4", 120_000, 5_000, position, subtitles,
                            destination, busy, exportProgress?.percent, playing, null, { folderChoices++; destination = "Clips" },
                            { start, end -> previewed = start to end },
                            { position = it; playing = false }, { pauseCount++; playing = false },
                            { name, start, end, burn -> saved = listOf(name, start, end, burn) },
                            onPlay = { playCount++; playing = true },
                            onStepFrame = { forward ->
                                frameSteps += forward
                                position += if (forward) 40L else -40L
                                playing = false
                            },
                            stepping = stepping,
                            buffering = buffering,
                            readPosition = readPosition,
                            exportProgress = exportProgress,
                            onCancel = { cancelCount++; busy = false; exportProgress = null },
                            preview = { Box(Modifier.fillMaxSize().testTag("testClipPreview")) })
                    }
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-xhdpi")
    fun `landscape exposes playback cut marks and export settings without scrolling`() {
        show(preview = true)
        compose.onNodeWithText("Cut").assertIsDisplayed()
        compose.onNodeWithText("Export").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous frame").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next frame").assertIsDisplayed()
        compose.onNodeWithText("Set start").assertIsDisplayed()
        compose.onNodeWithText("Set end").assertIsDisplayed()
        compose.onNodeWithText("Save clip").assertIsDisplayed()
        val picture = compose.onNodeWithTag("testClipPreview").fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNodeWithText("Cut").fetchSemanticsNode().boundsInRoot
        assertTrue("The landscape preview stays beside the options", picture.right <= tabs.left)
        assertTrue("The preview has a visible area", picture.height > 0f && picture.width > 0f)
        compose.onNodeWithText("Export").performClick()
        compose.onNodeWithText("Burn in subtitles").assertIsDisplayed()
        compose.onNodeWithText("Save clip").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-xhdpi")
    fun `zoomed landscape keeps playback marks and export reachable`() {
        show(preview = true, scale = 1.35f)
        compose.onNodeWithText("Cut").assertIsDisplayed()
        compose.onNodeWithText("Export").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous frame").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next frame").assertIsDisplayed()
        compose.onNodeWithText("Set start").assertIsDisplayed().performClick()
        compose.onNodeWithText("Set end").assertIsDisplayed()
        compose.onNodeWithText("Save to…").assertIsDisplayed()
        compose.onNodeWithText("Save clip").assertIsDisplayed().performClick()
        assertEquals(23_456L, saved?.get(1))
        compose.onNodeWithText("Export").performClick()
        compose.onNodeWithText("Name").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save to…").assertIsDisplayed()
        compose.onNodeWithText("Save clip").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-xhdpi")
    fun `save destination is visible and changeable from either panel in landscape`() {
        show(preview = true)
        compose.onNodeWithText("Movies").assertIsDisplayed()
        compose.onNodeWithText("Save to…").assertIsDisplayed().performClick()
        assertEquals(1, folderChoices)
        compose.onNodeWithText("Clips").assertIsDisplayed()
        compose.onNodeWithText("Export").performClick()
        compose.onNodeWithText("Clips").assertIsDisplayed()
        compose.onNodeWithText("Save to…").assertIsDisplayed().performClick()
        assertEquals(2, folderChoices)
        compose.onNodeWithText("Save clip").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-xhdpi")
    fun `export phases progress and cancel stay visible in zoomed landscape`() {
        show(preview = true, scale = 1.35f)
        compose.runOnIdle {
            busy = true
            exportProgress = VideoClipProgress(VideoClipPhase.READING_SUBTITLES)
        }
        compose.onNodeWithText("Reading subtitles…").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Save clip").assertIsNotEnabled()
        compose.runOnIdle { exportProgress = VideoClipProgress(VideoClipPhase.READING_SUBTITLES, 37) }
        compose.onNodeWithText("37%").assertIsDisplayed()
        compose.runOnIdle { exportProgress = VideoClipProgress(VideoClipPhase.RENDERING, 64) }
        compose.onNodeWithText("Rendering clip…").assertIsDisplayed()
        compose.onNodeWithText("64%").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        assertEquals(1, cancelCount)
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        compose.onNodeWithText("Save clip").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun `cancelling subtitle preparation preserves cut points name subtitles and destination`() {
        show()
        compose.onNodeWithText("Start").performScrollTo().performTextReplacement("00:00:12.345")
        compose.onNodeWithText("End").performScrollTo().performTextReplacement("00:01:02.009")
        compose.onNodeWithText("Export").performClick()
        compose.onNodeWithText("Burn in subtitles").performScrollTo().performClick()
        compose.onNodeWithText("Name").performScrollTo().performTextReplacement(" selected clip .mp4")
        compose.onNodeWithText("Save to…").performClick()
        compose.runOnIdle {
            busy = true
            exportProgress = VideoClipProgress(VideoClipPhase.READING_SUBTITLES, 28)
        }
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        assertEquals(1, cancelCount)
        compose.onNodeWithText("Clips").assertIsDisplayed()
        compose.onNodeWithText("Name").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Save clip").assertIsEnabled().performClick()
        assertEquals(listOf(" selected clip .mp4", 12_345L, 62_009L, true), saved)
    }

    @Test fun `editing cut points preserves exact times and file name for export`() {
        show()
        compose.onNodeWithText("Start").performScrollTo().performTextReplacement("00:00:12.345")
        compose.onNodeWithText("End").performScrollTo().performTextReplacement("00:01:02.009")
        compose.onNodeWithText("Preview").performScrollTo().performClick()
        assertEquals(12_345L to 62_009L, previewed)
        compose.onNodeWithText("Export").performClick()
        compose.onNodeWithText("Name").performScrollTo().performTextReplacement(" clip  .mp4")
        compose.onNodeWithText("Save clip").performClick()
        assertEquals(listOf(" clip  .mp4", 12_345L, 62_009L, false), saved)
    }

    @Test fun `burn in is an immediate export choice with no acknowledgement`() {
        show()
        compose.onNodeWithText("Export").performClick()
        compose.onNodeWithText("Burn in subtitles").performScrollTo().performClick()
        compose.onNodeWithText("May reduce quality.").assertExists()
        compose.onNodeWithText("Save clip").performClick()
        assertEquals(true, saved?.last())
        compose.onNodeWithText("No subtitles").performScrollTo().performClick()
        compose.onNodeWithText("Save clip").performClick()
        assertEquals(false, saved?.last())
    }

    @Test fun `invalid or reversed ranges cannot be exported`() {
        show()
        for (value in listOf("nonsense", "00:03:00", "00:00:01", "-00:00:03", "00:00:60")) {
            compose.onNodeWithText("End").performScrollTo().performTextReplacement(value)
            compose.onNodeWithText("Save clip").assertIsNotEnabled()
        }
        assertNull(saved)
        compose.onNodeWithText("End").performScrollTo().performTextReplacement("00:00:06.001")
        compose.onNodeWithText("Save clip").assertIsEnabled()
    }

    @Test fun `mark buttons use the current preview position`() {
        show()
        compose.onNodeWithText("Set start").performClick()
        compose.onNodeWithText("Save clip").performClick()
        assertEquals(23_456L, saved?.get(1))
        compose.onNodeWithText("Set end").performClick()
        compose.onNodeWithText("Save clip").assertIsNotEnabled()
    }

    @Test fun `mark buttons read the playback position between display updates`() {
        var current = 64_321L
        show(readPosition = { current })
        compose.onNodeWithText("Set start").performClick()
        current = 70_003L
        compose.onNodeWithText("Set end").performClick()
        compose.onNodeWithText("Save clip").performClick()
        assertEquals(listOf("movie-clip.mp4", 64_321L, 70_003L, false), saved)
    }

    @Test fun `playback resumes and frame buttons provide updated cut points`() {
        show()
        compose.onNodeWithContentDescription("Play").performClick()
        assertEquals(1, playCount)
        assertNull(previewed)
        assertEquals(23_456L, position)
        compose.onNodeWithContentDescription("Pause").performClick()
        assertEquals(1, pauseCount)
        compose.onNodeWithContentDescription("Next frame").performClick()
        compose.onNodeWithContentDescription("Next frame").performClick()
        compose.onNodeWithContentDescription("Previous frame").performClick()
        assertEquals(listOf(true, true, false), frameSteps)
        compose.onNodeWithText("Set start").performClick()
        compose.onNodeWithText("Save clip").performClick()
        assertEquals(23_496L, saved?.get(1))
    }

    @Test fun `reading frames blocks duplicate steps marking and export until ready`() {
        show()
        compose.runOnIdle { stepping = true }
        compose.onNodeWithContentDescription("Previous frame").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Next frame").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Play").assertIsNotEnabled()
        compose.onNodeWithText("Set start").assertIsNotEnabled()
        compose.onNodeWithText("Set end").assertIsNotEnabled()
        compose.onNodeWithText("Start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Save clip").assertIsNotEnabled()
        compose.runOnIdle { stepping = false }
        compose.onNodeWithContentDescription("Next frame").assertIsEnabled().performClick()
        assertEquals(listOf(true), frameSteps)
        compose.onNodeWithText("Set start").assertIsEnabled().performClick()
        compose.onNodeWithText("Save clip").assertIsEnabled().performClick()
        assertEquals(23_496L, saved?.get(1))
    }

    @Test fun `decoding a frame blocks marks while allowing further navigation`() {
        show()
        compose.runOnIdle { buffering = true }
        compose.onNodeWithText("Set start").assertIsNotEnabled()
        compose.onNodeWithText("Set end").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Previous frame").assertIsEnabled()
        compose.onNodeWithContentDescription("Play").assertIsEnabled()
        compose.onNodeWithText("Start").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Save clip").assertIsEnabled()
        compose.onNodeWithContentDescription("Next frame").assertIsEnabled().performClick()
        assertEquals(listOf(true), frameSteps)
        compose.onNodeWithText("Set start").assertIsNotEnabled()
        compose.runOnIdle { buffering = false }
        compose.onNodeWithText("Set start").assertIsEnabled().performClick()
        compose.onNodeWithText("Save clip").performClick()
        assertEquals(23_496L, saved?.get(1))
    }

    @Test fun `changing panels preserves manual cut points and export choices`() {
        show()
        compose.onNodeWithText("Start").performScrollTo().performTextReplacement("00:00:12.345")
        compose.onNodeWithText("End").performScrollTo().performTextReplacement("00:01:02.009")
        compose.onNodeWithText("Export").performClick()
        compose.onNodeWithText("Burn in subtitles").performScrollTo().performClick()
        compose.onNodeWithText("Name").performScrollTo().performTextReplacement(" selected clip .mp4")
        compose.onNodeWithText("Cut").performClick()
        compose.onNodeWithText("Preview").performScrollTo().performClick()
        assertEquals(12_345L to 62_009L, previewed)
        compose.onNodeWithText("Export").performClick()
        compose.onNodeWithText("May reduce quality.").assertExists()
        compose.onNodeWithText("Save clip").performClick()
        assertEquals(listOf(" selected clip .mp4", 12_345L, 62_009L, true), saved)
    }

    @Test fun `burn in needs a selected subtitle track`() {
        show(subtitles = false)
        compose.onNodeWithText("Export").performClick()
        compose.onNodeWithText("Burn in subtitles").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Save clip").assertIsEnabled()
    }

    @Test fun `an invalid filename cannot start rendering`() {
        show()
        compose.onNodeWithText("Export").performClick()
        compose.onNodeWithText("Name").performScrollTo().performTextReplacement("../clip.mp4")
        compose.onNodeWithText("Save clip").assertIsNotEnabled()
        assertNull(saved)
    }

    @Test fun `time parsing rejects overflow and preserves fractional milliseconds`() {
        assertEquals(3_723_100L, parseClipTime("01:02:03.1"))
        assertEquals(3_723_012L, parseClipTime("01:02:03.012"))
        assertNull(parseClipTime("999999999999999999999:00:00"))
        assertNull(parseClipTime("2562047788015:00:00"))
        assertNull(parseClipTime(" 00:00:01"))
        assertNull(parseClipTime("00:00:01.0001"))
    }
}
