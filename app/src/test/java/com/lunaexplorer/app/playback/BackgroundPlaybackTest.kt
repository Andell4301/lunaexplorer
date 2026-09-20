package com.lunaexplorer.app.playback

import android.os.Looper
import androidx.media3.session.MediaSession
import com.lunaexplorer.app.LunaApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class, sdk = [35])
class BackgroundPlaybackTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val built = mutableListOf<MediaSession>()

    private fun session(id: String): MediaSession =
        MediaSession.Builder(context, StubPlayer()).setId(id).build().also { built.add(it) }

    /** The holder is a process singleton; a Robolectric sandbox is shared by every test in it. */
    @After fun cleanup() {
        BackgroundPlayback.session?.let { BackgroundPlayback.withdraw(context, it) }
        built.forEach { runCatching { it.release() } }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `start asks for the service`() {
        BackgroundPlayback.publish(session("start")) {}
        assertEquals(true, BackgroundPlayback.start(context))
        assertEquals(PlaybackService::class.java.name,
            shadowOf(context).nextStartedService.component!!.className)
    }

    @Test fun `withdrawing stops the service and lets go of the session`() {
        val published = session("withdraw")
        BackgroundPlayback.publish(published) {}
        assertSame(published, BackgroundPlayback.session)

        BackgroundPlayback.withdraw(context, published)
        assertNull(BackgroundPlayback.session)
        assertEquals(PlaybackService::class.java.name,
            shadowOf(context).nextStoppedService.component!!.className)
    }

    @Test fun `a late withdrawal from an earlier viewer leaves the current one alone`() {
        val old = session("old")
        BackgroundPlayback.publish(old) {}
        val current = session("current")
        BackgroundPlayback.publish(current) {}
        shadowOf(context).clearStartedServices()

        BackgroundPlayback.withdraw(context, old)
        assertSame(current, BackgroundPlayback.session)
        assertNull("Stopping here would kill the service the new viewer needs",
            shadowOf(context).nextStoppedService)
    }

    @Test fun `publishing again releases the session before it`() {
        BackgroundPlayback.publish(session("reused"), {})
        BackgroundPlayback.publish(session("second"), {})
        // A released session gives its id back; an unreleased one makes this throw.
        session("reused").release()
    }

    @Test fun `a refused start reaches the player that asked`() {
        var refused = 0
        BackgroundPlayback.publish(session("refused")) { refused++ }
        BackgroundPlayback.startRefused()
        assertEquals(1, refused)
    }
}
