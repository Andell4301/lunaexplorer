package com.lunaexplorer.app.playback

import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession

internal const val PLAYBACK_CHANNEL = "playback"

// Ids 101 to 103 belong to file operations and external streams.
internal const val PLAYBACK_NOTIFICATION_ID = 104

// Main-thread singleton shared with the Android-created PlaybackService.
internal object BackgroundPlayback {
    var session: MediaSession? = null
        private set
    private var onRefused: (() -> Unit)? = null

    fun publish(session: MediaSession, onRefused: () -> Unit) {
        this.session?.takeIf { it !== session }?.release()
        this.session = session
        this.onRefused = onRefused
    }

    // A refused background start makes the caller pause.
    fun start(context: Context): Boolean = try {
        context.startService(Intent(context, PlaybackService::class.java))
        true
    } catch (_: IllegalStateException) {
        false
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, PlaybackService::class.java))
    }

    fun withdraw(context: Context, session: MediaSession) {
        // A late close from an earlier viewer must not stop the service the current one needs.
        if (this.session !== session) return
        this.session = null
        this.onRefused = null
        stop(context)
    }

    fun startRefused() {
        onRefused?.invoke()
    }
}
