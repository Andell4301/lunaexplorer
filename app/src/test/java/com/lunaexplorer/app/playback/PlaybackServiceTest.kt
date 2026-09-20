package com.lunaexplorer.app.playback

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.media3.session.MediaSession
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.R
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class, sdk = [35])
class PlaybackServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val players = mutableListOf<StubPlayer>()
    private val sessions = mutableListOf<MediaSession>()
    private var controller: ServiceController<PlaybackService>? = null

    private fun service(): PlaybackService =
        Robolectric.buildService(PlaybackService::class.java).create().also { controller = it }.get()

    private fun publish(id: String): MediaSession {
        val player = StubPlayer().also { players.add(it) }
        val session = MediaSession.Builder(context, player).setId(id).build().also { sessions.add(it) }
        BackgroundPlayback.publish(session) {}
        return session
    }

    private fun start(service: PlaybackService) =
        service.onStartCommand(Intent(context, PlaybackService::class.java), 0, 1)

    @After fun cleanup() {
        controller?.destroy()
        BackgroundPlayback.session?.let { BackgroundPlayback.withdraw(context, it) }
        sessions.forEach { runCatching { it.release() } }
        players.forEach { it.release() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `a start with no session stops the service`() {
        val service = service()
        start(service)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `a published session is taken up on start`() {
        val session = publish("taken")
        val service = service()
        start(service)
        assertTrue(service.isSessionAdded(session))
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `removing the task pauses and stops`() {
        val session = publish("removed")
        val service = service()
        start(service)
        session.player.play()
        shadowOf(Looper.getMainLooper()).idle()

        service.onTaskRemoved(null)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(session.player.playWhenReady)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `a destroyed service leaves no notification and lets the session go`() {
        val session = publish("destroyed")
        val service = service()
        start(service)
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.notify(PLAYBACK_NOTIFICATION_ID, Notification.Builder(context, PLAYBACK_CHANNEL)
            .setSmallIcon(R.drawable.ic_operation).build())

        controller!!.destroy()
        controller = null
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("A stale playback notification would outlive the service",
            shadowOf(notifications).allNotifications.isEmpty())
        assertFalse("The session belongs to the viewer and outlives the service",
            service.isSessionAdded(session))
    }
}
