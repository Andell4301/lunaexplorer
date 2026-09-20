package com.lunaexplorer.app.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import com.lunaexplorer.app.MainActivity

// The viewer owns the player; this releases only the session, whose ID must be unique while live.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackSession(
    private val context: Context,
    private val player: Player,
    private val id: String,
    private val onRefused: () -> Unit,
) {
    var session: MediaSession? = null
        private set
    private var started = false

    val published: Boolean get() = session != null

    fun open() {
        if (session != null) return
        val built = MediaSession.Builder(context, player)
            .setId(id)
            .setSessionActivity(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setMediaButtonPreferences(listOf(
                seekButton(CommandButton.ICON_SKIP_BACK_10, Player.COMMAND_SEEK_BACK,
                    "Back 10 seconds", CommandButton.SLOT_BACK),
                seekButton(CommandButton.ICON_SKIP_FORWARD_10, Player.COMMAND_SEEK_FORWARD,
                    "Forward 10 seconds", CommandButton.SLOT_FORWARD)))
            .setCallback(LunaSessionCallback)
            .build()
        session = built
        BackgroundPlayback.publish(built, onRefused)
    }

    fun close() {
        val open = session ?: return
        session = null
        started = false
        BackgroundPlayback.withdraw(context, open)
        open.release()
    }

    // Ask on every start because Android may have stopped the service.
    fun start(): Boolean {
        if (session == null) return true
        started = BackgroundPlayback.start(context)
        return started
    }

    fun stop() {
        if (!started) return
        started = false
        BackgroundPlayback.stop(context)
    }

    private fun seekButton(icon: Int, command: Int, label: String, slot: Int): CommandButton =
        CommandButton.Builder(icon).setPlayerCommand(command).setDisplayName(label).setSlots(slot).build()
}

// Remote controllers may seek and pause; file choice, speed and repeat belong to the viewer.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object LunaSessionCallback : MediaSession.Callback {
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
        val commands = builder.build().availablePlayerCommands.buildUpon()
            .removeAll(Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .build()
        return builder.setAvailablePlayerCommands(commands).build()
    }
}
