package com.lunaexplorer.app.playback

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.lunaexplorer.app.LunaApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class, sdk = [35])
class PlaybackSessionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val players = mutableListOf<StubPlayer>()
    private val sessions = mutableListOf<PlaybackSession>()

    private fun playbackSession(id: String, onRefused: () -> Unit = {}): PlaybackSession {
        val player = StubPlayer().also { players.add(it) }
        return PlaybackSession(context, player, id, onRefused).also { sessions.add(it) }
    }

    @After fun cleanup() {
        sessions.forEach { it.close() }
        BackgroundPlayback.session?.let { BackgroundPlayback.withdraw(context, it) }
        players.forEach { it.release() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `nothing is published while background play is off`() {
        val playback = playbackSession("off")
        assertFalse(playback.published)
        assertNull(BackgroundPlayback.session)
        assertTrue("Nothing to carry, so nothing to refuse", playback.start())
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun `the service is asked for again once it has gone`() {
        val playback = playbackSession("again")
        playback.open()
        playback.start()
        assertEquals(PlaybackService::class.java.name,
            shadowOf(context).nextStartedService.component!!.className)

        // As after Android stopped the idle service: asking once is not knowing it still runs.
        assertTrue(playback.start())
        assertEquals(PlaybackService::class.java.name,
            shadowOf(context).nextStartedService.component!!.className)
    }

    @Test fun `opening publishes, withdrawing stops the service and releases the session`() {
        val playback = playbackSession("open")
        playback.open()
        assertTrue(playback.published)
        assertEquals(playback.session, BackgroundPlayback.session)
        playback.start()
        assertEquals(PlaybackService::class.java.name,
            shadowOf(context).nextStartedService.component!!.className)

        playback.close()
        assertFalse(playback.published)
        assertNull(BackgroundPlayback.session)
        assertEquals(PlaybackService::class.java.name,
            shadowOf(context).nextStoppedService.component!!.className)
        // A released session gives its id back; an unreleased one makes this throw.
        MediaSession.Builder(context, StubPlayer().also { players.add(it) }).setId("open").build().release()
    }

    @Test fun `closing leaves the player to its owner`() {
        val playback = playbackSession("owner")
        playback.open()
        playback.close()
        val player = players.first()
        player.play()
        assertTrue("The viewer still owns the player after its session goes", player.playWhenReady)
    }

    @Test fun `a refused start pauses`() {
        var paused = false
        val playback = playbackSession("refused") { paused = true }
        playback.open()
        playback.session?.player?.play()
        BackgroundPlayback.startRefused()
        assertTrue(paused)
    }

    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun `a remote controller may not change what Luna's own controls own`() {
        val playback = playbackSession("commands")
        playback.open()
        val session = requireNotNull(playback.session)
        val controller = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            context.packageName, 0, 0, 1, 1, true, Bundle.EMPTY, true)
        val allowed = LunaSessionCallback.onConnect(session, controller).availablePlayerCommands

        assertFalse(allowed.contains(Player.COMMAND_SET_SPEED_AND_PITCH))
        assertFalse(allowed.contains(Player.COMMAND_SET_REPEAT_MODE))
        assertFalse(allowed.contains(Player.COMMAND_SET_MEDIA_ITEM))
        assertFalse(allowed.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertTrue(allowed.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(allowed.contains(Player.COMMAND_SEEK_BACK))
        assertTrue(allowed.contains(Player.COMMAND_SEEK_FORWARD))
    }
}
