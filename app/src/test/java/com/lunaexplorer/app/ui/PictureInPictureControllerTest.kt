package com.lunaexplorer.app.ui

import android.app.PictureInPictureParams
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

class PipTestActivity : ComponentActivity()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PictureInPictureControllerTest {

    /** Robolectric leaves setPictureInPictureParams unshadowed, so both calls are recorded here instead. */
    private class Recording(activity: ComponentActivity) : ActivityPictureInPicture(activity) {
        var written: PictureInPictureParams? = null
        var entered = 0
        var moveResult = true
        var moveFailure: RuntimeException? = null
        var duringMove: () -> Unit = {}
        override fun moveTaskToBack(): Boolean {
            duringMove()
            moveFailure?.let { throw it }
            return moveResult
        }
        override fun setParams(params: PictureInPictureParams) { written = params }
        override fun enterMode(params: PictureInPictureParams): Boolean { entered++; return true }
    }

    private var controller: ActivityController<PipTestActivity>? = null

    private fun started(): Pair<PipTestActivity, Recording> {
        // A signature permission the app declares for itself; Robolectric grants nothing by default.
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions("${application.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val built = Robolectric.buildActivity(PipTestActivity::class.java).setup()
        controller = built
        val activity = built.get()
        return activity to Recording(activity)
    }

    private fun video(playing: Boolean = true, seekable: Boolean = true, autoEnter: Boolean = true) =
        PipRequest(aspect = 16 to 9, sourceRect = null, playing = playing, seekable = seekable, autoEnter = autoEnter)

    @After fun teardown() {
        controller?.destroy()
        controller = null
    }

    @Test fun `leaving the browser does not enter picture in picture`() {
        val (_, pip) = started()
        controller!!.userLeaving()
        assertEquals(0, pip.entered)
        assertNull("Nothing armed, so no params are written at all", pip.written)
    }

    @Test fun `leaving a playing video enters it`() {
        val (_, pip) = started()
        pip.arm(video())
        controller!!.userLeaving()
        assertEquals(1, pip.entered)
    }

    @Test fun `leaving a paused video does not enter it`() {
        val (_, pip) = started()
        pip.arm(video(playing = false))
        controller!!.userLeaving()
        assertEquals(0, pip.entered)
    }

    @Test fun `a launch made by Luna does not enter it`() {
        val (_, pip) = started()
        pip.arm(video())
        pip.launching(true)
        controller!!.userLeaving()
        assertEquals(0, pip.entered)
    }

    @Test fun `disarming stops it`() {
        val (_, pip) = started()
        pip.arm(video())
        pip.arm(null)
        controller!!.userLeaving()
        assertEquals(0, pip.entered)
        assertFalse(pip.enter())
    }

    @Test
    @Config(sdk = [30, 35])
    fun `manual entry stays available when automatic entry is disabled`() {
        val (_, pip) = started()
        pip.arm(video(autoEnter = false))

        controller!!.userLeaving()
        assertEquals(0, pip.entered)
        if (Build.VERSION.SDK_INT >= 31) assertFalse(requireNotNull(pip.written).isAutoEnterEnabled)
        assertTrue(pip.enter())
        assertEquals(1, pip.entered)
    }

    @Test
    @Config(sdk = [30, 35])
    fun `explicit background playback suppresses PiP before moving and restores it on return`() {
        val (_, pip) = started()
        pip.arm(video())
        pip.duringMove = {
            if (Build.VERSION.SDK_INT >= 31) assertFalse(requireNotNull(pip.written).isAutoEnterEnabled)
            controller!!.userLeaving()
            assertEquals(0, pip.entered)
        }

        assertTrue(pip.moveToBackground())
        pip.arm(video())
        controller!!.userLeaving()
        assertEquals(0, pip.entered)
        if (Build.VERSION.SDK_INT >= 31) assertFalse(requireNotNull(pip.written).isAutoEnterEnabled)

        controller!!.pause().resume()
        if (Build.VERSION.SDK_INT >= 31) {
            assertTrue(requireNotNull(pip.written).isAutoEnterEnabled)
        } else {
            controller!!.userLeaving()
            assertEquals(1, pip.entered)
        }
    }

    @Test
    @Config(sdk = [30, 35])
    fun `a refused background request restores automatic entry`() {
        val (_, pip) = started()
        pip.arm(video())
        pip.moveResult = false

        assertFalse(pip.moveToBackground())
        if (Build.VERSION.SDK_INT >= 31) {
            assertTrue(requireNotNull(pip.written).isAutoEnterEnabled)
        } else {
            controller!!.userLeaving()
            assertEquals(1, pip.entered)
        }
    }

    @Test
    @Config(sdk = [30, 35])
    fun `a failed background transition does not clear app launch suppression`() {
        val (_, pip) = started()
        pip.arm(video())
        pip.launching(true)
        pip.moveFailure = IllegalStateException("Task unavailable")

        assertFalse(pip.moveToBackground())
        controller!!.userLeaving()
        assertEquals(0, pip.entered)
        if (Build.VERSION.SDK_INT >= 31) assertFalse(requireNotNull(pip.written).isAutoEnterEnabled)

        pip.launching(false)
        if (Build.VERSION.SDK_INT >= 31) {
            assertTrue(requireNotNull(pip.written).isAutoEnterEnabled)
        } else {
            controller!!.userLeaving()
            assertEquals(1, pip.entered)
        }
    }

    @Test fun `closing the window pauses when the stop arrives first`() {
        val (activity, pip) = started()
        val paused = watchForPause(activity, pip)
        pinned(activity)

        controller!!.stop()
        modeChanged(activity, false)

        assertTrue("A closed window pauses even with a session published", paused())
    }

    @Test fun `closing the window pauses when the mode change arrives first`() {
        val (activity, pip) = started()
        val paused = watchForPause(activity, pip)
        pinned(activity)

        modeChanged(activity, false)
        controller!!.stop()

        assertTrue("A closed window pauses even with a session published", paused())
    }

    @Test fun `a controller built while the window is pinned still closes it`() {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions("${application.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val built = Robolectric.buildActivity(PipTestActivity::class.java).setup()
        controller = built
        val activity = built.get()
        // A recreation the pinned window survives: the rebuilt controller only has the activity to go by.
        activity.enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        val pip = Recording(activity)
        val paused = watchForPause(activity, pip)

        built.pause()
        built.stop()
        modeChanged(activity, false)

        assertTrue("A window closed after a recreation pauses too", paused())
    }

    @Test fun `expanding the window keeps playing`() {
        val (activity, pip) = started()
        val paused = watchForPause(activity, pip)
        pinned(activity)

        modeChanged(activity, false)
        controller!!.resume()
        controller!!.pause().stop()

        assertFalse("An expanded window is back on screen; a later stop is an ordinary one", paused())
    }

    /** A pinned activity is started but not resumed, which is what the phase is read against. */
    private fun pinned(activity: PipTestActivity) {
        modeChanged(activity, true)
        controller!!.pause()
    }

    private fun modeChanged(activity: PipTestActivity, inPip: Boolean) =
        activity.onPictureInPictureModeChanged(inPip, activity.resources.configuration)

    /** Stands in for the viewer: it pauses on the closed callback, or on a stop the policy calls for. */
    private fun watchForPause(activity: PipTestActivity, pip: ActivityPictureInPicture): () -> Boolean {
        var paused = false
        pip.arm(video(), onClosed = { paused = true })
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP &&
                pauseOnStop(published = true, pip = pip.phase, interactive = true, closing = false)) paused = true
        })
        return { paused }
    }

    @Test
    @Config(sdk = [35])
    fun `auto enter follows playback and is off while Luna launches something`() {
        val (_, pip) = started()
        pip.arm(video())
        assertTrue(requireNotNull(pip.written).isAutoEnterEnabled)

        pip.launching(true)
        assertFalse(requireNotNull(pip.written).isAutoEnterEnabled)

        pip.launching(false)
        pip.arm(video(playing = false))
        assertFalse(requireNotNull(pip.written).isAutoEnterEnabled)

        pip.arm(null)
        assertFalse(requireNotNull(pip.written).isAutoEnterEnabled)
    }

    @Test
    @Config(sdk = [35])
    fun `a user leave hint does not enter on its own where Android auto enters`() {
        val (_, pip) = started()
        pip.arm(video())
        controller!!.userLeaving()
        assertEquals(0, pip.entered)
    }
}
