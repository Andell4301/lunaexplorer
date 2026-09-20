package com.lunaexplorer.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.LunaApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class PlayerGesturesTest {
    @get:Rule val compose = createComposeRule()

    private class Recorder {
        var lockedTouches = 0
        var taps = 0
        val zones = mutableListOf<TapZone>()
        var seekStarts = 0
        var seekEnds = 0
        val seeks = mutableListOf<Float>()
        val volumes = mutableListOf<Float>()
        val brightnesses = mutableListOf<Float>()
        var volumeStarts = 0
        var brightnessStarts = 0
        var adjustEnds = 0
    }

    private fun show(
        recorder: Recorder,
        locked: Boolean = false,
        doubleTap: Boolean = true,
        seek: Boolean = true,
        volume: Boolean = true,
        brightness: Boolean = true,
    ) {
        compose.setContent {
            Box(
                Modifier.size(400.dp).testTag("picture").playerGestures(
                    key = Unit,
                    locked = locked,
                    doubleTap = doubleTap,
                    seek = seek,
                    volume = volume,
                    brightness = brightness,
                    onLockedTouch = { recorder.lockedTouches++ },
                    onTap = { recorder.taps++ },
                    onDoubleTap = { recorder.zones += it },
                    onSeekStart = { recorder.seekStarts++ },
                    onSeek = { recorder.seeks += it },
                    onSeekEnd = { recorder.seekEnds++ },
                    onVolumeStart = { recorder.volumeStarts++ },
                    onVolume = { recorder.volumes += it },
                    onBrightnessStart = { recorder.brightnessStarts++ },
                    onBrightness = { recorder.brightnesses += it },
                    onAdjustEnd = { recorder.adjustEnds++ },
                ),
            ) { Box(Modifier.fillMaxSize()) }
        }
    }

    @Test fun `dragging sideways reports how far it actually moved`() {
        val recorder = Recorder()
        show(recorder)

        compose.onNodeWithTag("picture").performTouchInput {
            down(centerLeft + Offset(10f, 0f))
            moveBy(Offset(width * .4f, 0f))
            moveBy(Offset(width * .2f, 0f))
            up()
        }

        assertEquals(1, recorder.seekStarts)
        assertEquals(1, recorder.seekEnds)
        assertTrue("Something must have been reported", recorder.seeks.isNotEmpty())
        assertTrue("And it must not all be zero: ${recorder.seeks}",
            recorder.seeks.any { it > .01f })
        assertTrue("Rightwards is forwards", recorder.seeks.sum() > 0f)
    }

    @Test fun `dragging up the right side turns the volume up`() {
        val recorder = Recorder()
        show(recorder)

        compose.onNodeWithTag("picture").performTouchInput {
            down(centerRight - Offset(10f, 0f))
            moveBy(Offset(0f, -height * .3f))
            moveBy(Offset(0f, -height * .2f))
            up()
        }

        assertEquals(1, recorder.volumeStarts)
        assertTrue("Upwards is louder: ${recorder.volumes}", recorder.volumes.sum() > .1f)
        assertTrue("And brightness was left alone", recorder.brightnesses.isEmpty())
    }

    @Test fun `dragging up the left side turns the brightness up`() {
        val recorder = Recorder()
        show(recorder)

        compose.onNodeWithTag("picture").performTouchInput {
            down(centerLeft + Offset(10f, 0f))
            moveBy(Offset(0f, -height * .3f))
            moveBy(Offset(0f, -height * .2f))
            up()
        }

        assertEquals(1, recorder.brightnessStarts)
        assertTrue("Upwards is brighter: ${recorder.brightnesses}", recorder.brightnesses.sum() > .1f)
        assertTrue("And volume was left alone", recorder.volumes.isEmpty())
    }

    @Test fun `a volume drag is told when it is over`() {
        val recorder = Recorder()
        show(recorder)

        compose.onNodeWithTag("picture").performTouchInput {
            down(centerRight - Offset(10f, 0f))
            moveBy(Offset(0f, -height * .3f))
            up()
        }

        assertEquals(1, recorder.adjustEnds)
    }

    @Test fun `a seek always ends, even when the recogniser is restarted mid-drag`() {
        // Changing the pointerInput key cancels the recogniser while the finger is still down.
        val recorder = Recorder()
        var key by mutableStateOf(0)
        compose.setContent {
            Box(
                Modifier.size(400.dp).testTag("picture").playerGestures(
                    key = key,
                    locked = false, doubleTap = true, seek = true, volume = true, brightness = true,
                    onLockedTouch = {}, onTap = {}, onDoubleTap = {},
                    onSeekStart = { recorder.seekStarts++ },
                    onSeek = { recorder.seeks += it },
                    onSeekEnd = { recorder.seekEnds++ },
                    onVolumeStart = {}, onVolume = {},
                    onBrightnessStart = {}, onBrightness = {},
                    onAdjustEnd = { recorder.adjustEnds++ },
                ),
            ) { Box(Modifier.fillMaxSize()) }
        }

        compose.onNodeWithTag("picture").performTouchInput {
            down(centerLeft + Offset(10f, 0f))
            moveBy(Offset(width * .4f, 0f))
        }
        assertEquals("The drag is under way", 1, recorder.seekStarts)
        assertEquals(0, recorder.seekEnds)

        key = 1
        compose.waitForIdle()

        assertEquals("It must still be told the scrub is over", 1, recorder.seekEnds)
    }

    @Test fun `a gesture switched off is not recognised at all`() {
        val recorder = Recorder()
        show(recorder, seek = false, volume = false, brightness = false)

        compose.onNodeWithTag("picture").performTouchInput {
            down(center)
            moveBy(Offset(width * .4f, 0f))
            up()
        }

        assertTrue(recorder.seeks.isEmpty() && recorder.volumes.isEmpty() && recorder.brightnesses.isEmpty())
        assertEquals("A drag is still not a tap", 0, recorder.taps)
    }

    @Test fun `a double tap is placed by where the second tap landed`() {
        val recorder = Recorder()
        show(recorder)

        compose.onNodeWithTag("picture").performTouchInput { doubleClick(centerLeft + Offset(10f, 0f)) }
        compose.waitForIdle()

        assertEquals(listOf(TapZone.BACK), recorder.zones)
    }

    @Test fun `each tap after a double tap skips again`() {
        val recorder = Recorder()
        show(recorder)

        compose.onNodeWithTag("picture").performTouchInput {
            val at = centerRight - Offset(10f, 0f)
            doubleClick(at)
            advanceEventTime(100)
            click(at)
            advanceEventTime(100)
            click(at)
        }
        compose.waitForIdle()

        assertEquals("Four taps are three skips, not two", List(3) { TapZone.FORWARD }, recorder.zones)
        assertEquals(0, recorder.taps)
    }

    @Test fun `a tap in the middle ends the skipping and is a tap`() {
        val recorder = Recorder()
        show(recorder)

        compose.onNodeWithTag("picture").performTouchInput {
            doubleClick(centerLeft + Offset(10f, 0f))
            advanceEventTime(100)
            click(center)
        }
        compose.waitForIdle()

        assertEquals(listOf(TapZone.BACK), recorder.zones)
        assertEquals(1, recorder.taps)
    }

    @Test fun `skips in a row add up, and turning round starts again`() {
        var total = 0L
        val shown = listOf(10_000L, 10_000L, 10_000L, -10_000L, -10_000L).map { step -> skipTotal(total, step).also { total = it } }

        assertEquals(listOf(10_000L, 20_000L, 30_000L, -10_000L, -20_000L), shown)
        assertEquals("A run that has left the screen is over", 10_000L, skipTotal(0L, 10_000L))
    }

    @Test fun `nothing but the way out answers while it is locked`() {
        val recorder = Recorder()
        show(recorder, locked = true)

        compose.onNodeWithTag("picture").performTouchInput {
            down(center)
            moveBy(Offset(width * .4f, 0f))
            up()
        }

        assertEquals(1, recorder.lockedTouches)
        assertEquals(0, recorder.taps)
        assertTrue(recorder.seeks.isEmpty() && recorder.volumes.isEmpty())
    }
}
