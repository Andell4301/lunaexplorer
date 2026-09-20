package com.lunaexplorer.app.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** The least a MediaSession accepts: one item that can be played and paused. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class StubPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var wanted = false

    override fun getState(): State = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .setPlaylist(listOf(MediaItemData.Builder("stub").setMediaItem(MediaItem.EMPTY).build()))
        .setPlayWhenReady(wanted, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .build()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wanted = playWhenReady
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}
