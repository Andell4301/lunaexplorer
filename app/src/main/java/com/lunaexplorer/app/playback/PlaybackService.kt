package com.lunaexplorer.app.playback

import android.app.NotificationManager
import android.content.Intent
import androidx.annotation.RequiresApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lunaexplorer.app.R

// The viewer owns the session; this service only exposes it while Luna is off screen.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(PLAYBACK_CHANNEL)
            .setChannelName(R.string.playback_channel)
            .setNotificationId(PLAYBACK_NOTIFICATION_ID)
            .build().apply { setSmallIcon(R.drawable.ic_operation) })
        setListener(object : Listener {
            @RequiresApi(31)
            override fun onForegroundServiceStartNotAllowedException() {
                // Left started it would retry startForeground on every later player event and throw.
                stopSelf()
                BackgroundPlayback.startRefused()
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        BackgroundPlayback.session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val session = BackgroundPlayback.session
        if (session == null) stopSelf() else if (!isSessionAdded(session)) addSession(session)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) = pauseAllPlayersAndStopSelf()

    override fun onDestroy() {
        // The session is the viewer's and outlives this service; media3 drops its notification
        // controller only once the session is removed, and a left-behind one keeps restarting us.
        sessions.forEach { removeSession(it) }
        getSystemService(NotificationManager::class.java).cancel(PLAYBACK_NOTIFICATION_ID)
        clearListener()
        super.onDestroy()
    }
}
