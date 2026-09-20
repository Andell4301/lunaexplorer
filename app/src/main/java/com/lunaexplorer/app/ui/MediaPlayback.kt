package com.lunaexplorer.app.ui

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.PlayerView
import com.lunaexplorer.app.model.SubtitleAppearance
import com.lunaexplorer.app.playback.PlaybackSession
import com.lunaexplorer.app.playback.PlaybackSource
import com.lunaexplorer.core.NodeRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

internal data class MediaTrack(val id: Int, val label: String, val supported: Boolean = true)

/** Where a viewer torn down by an Activity recreation left off, for the one that replaces it. */
internal data class PlaybackResume(val ref: NodeRef, val position: Long, val playing: Boolean)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MediaPlayback(
    context: Context,
    source: PlaybackSource,
    val subtitleDirectory: File,
    private val title: String = "",
) {
    private val uri = source.uri
    private val appContext = context.applicationContext
    private val subtitleOffset = SubtitleOffset()
    private val handler = Handler(Looper.getMainLooper())
    private val player = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(source.dataSources)
            .setSubtitleParserFactory(OffsetSubtitleParserFactory(subtitleOffset)))
        .setAudioAttributes(AudioAttributes.DEFAULT, true)
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
        .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
        // Bound release time so a blocked network read cannot cause an ANR on dismissal.
        .setReleaseTimeoutMs(500)
        .setDetachSurfaceTimeoutMs(500)
        .build()
    private val trackNames = DefaultTrackNameProvider(context.resources)
    private val trackOverrides = mutableMapOf<Int, TrackSelectionOverride>()
    private val trackIdentities = mutableMapOf<Int, MediaTrackIdentity>()
    private val externalSubtitles = mutableListOf<MediaItem.SubtitleConfiguration>()
    private var pendingExternalId: String? = null
    private var preferredAudio: MediaTrackIdentity? = null
    private var preferredSubtitle: MediaTrackIdentity? = null
    private var restoreAudio = false
    private var restoreSubtitle = false
    private var closed = false
    private var layout: PlayerView? = null
    private var subtitleRenderer: LunaSubtitleView? = null
    private var subtitleAppearance = SubtitleAppearance()
    private var repeatState by mutableStateOf(false)
    var repeat: Boolean
        get() = repeatState
        set(value) {
            if (!closed) {
                repeatState = value
                player.repeatMode = if (value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            }
        }
    var started by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var seekable by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(true)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var audioTracks by mutableStateOf(emptyList<MediaTrack>())
        private set
    var subtitleTracks by mutableStateOf(emptyList<MediaTrack>())
        private set
    var audioTrack by mutableIntStateOf(-1)
        private set

    /** Separate from [audioTrack] == -1, which also means the tracks have not been read yet. */
    var audioOff by mutableStateOf(false)
        private set
    var subtitleTrack by mutableIntStateOf(-1)
        private set
    var subtitleDelayMs by mutableLongStateOf(0L)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var videoWidth by mutableIntStateOf(0)
        private set
    var videoHeight by mutableIntStateOf(0)
        private set

    /** The picture's place in the window, for the shrink into a picture-in-picture window. */
    var pictureBounds by mutableStateOf<Rect?>(null)
        private set

    private var session: PlaybackSession? = null
    private var onScreen = true

    /** A media session is published, which is what decides whether a stop may carry on playing. */
    val background: Boolean get() = session?.published == true

    private val listener = object : Player.Listener {
        override fun onCues(cueGroup: CueGroup) {
            if (!closed) subtitleRenderer?.setCues(cueGroup.cues)
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            if (closed) return
            val width = (size.width * size.pixelWidthHeightRatio).roundToInt()
            // Keep the last real size: a momentary zero would reset the window's shape.
            if (width > 0 && size.height > 0) { videoWidth = width; videoHeight = size.height }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (closed) return
            playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED &&
                player.playbackState != Player.STATE_IDLE && player.playerError == null
            buffering = player.playbackState == Player.STATE_BUFFERING
            // A remote play re-prepares an errored player, so the viewer must stop showing the error.
            if (player.playerError == null && player.playbackState == Player.STATE_READY) failure = null
            refreshPosition()
            if (events.contains(Player.EVENT_TRACKS_CHANGED) || events.contains(Player.EVENT_TRACK_SELECTION_PARAMETERS_CHANGED)) {
                refreshTracks()
            }
            if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) { applyPicture(); applyService() }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (closed) return
            failure = when (error.errorCode) {
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "This device cannot decode a track in this file. Try opening it in another app."
                else -> "This file could not be played: ${error.errorCodeName}"
            }
            playing = false
            buffering = false
        }
    }

    init {
        player.addListener(listener)
        player.setMediaItem(mediaItem())
        player.prepare()
    }

    fun attach(view: PlayerView) {
        if (closed || layout === view) return
        removeSubtitleRenderer()
        layout?.player = null
        layout = view
        view.useController = false
        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        view.setKeepContentOnPlayerReset(true)
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        val builtInSubtitles = view.subtitleView
        val subtitleParent = builtInSubtitles?.parent as? ViewGroup
        if (builtInSubtitles != null && subtitleParent != null) {
            builtInSubtitles.visibility = View.INVISIBLE
            subtitleRenderer = LunaSubtitleView(view.context).also { renderer ->
                renderer.appearance = subtitleAppearance
                // Added to the built-in SubtitleView's video-sized parent; an outer overlay would
                // change cue coordinates and text size on letterboxed video.
                subtitleParent.addView(renderer, subtitleParent.indexOfChild(builtInSubtitles) + 1,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
        view.player = player
        // On the PlayerView, not the surface: the surface's own parent resizes it without laying it out.
        view.addOnLayoutChangeListener(pictureLayout)
        readPictureBounds(view)
        subtitleRenderer?.setCues(player.currentCues.cues)
    }

    fun detach(view: PlayerView) {
        if (!closed && layout === view) {
            removeSubtitleRenderer()
            view.removeOnLayoutChangeListener(pictureLayout)
            view.player = null
            layout = null
        }
    }

    private val pictureLayout = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        (view as? PlayerView)?.let { readPictureBounds(it) }
    }

    private fun readPictureBounds(view: PlayerView) {
        val surface = view.videoSurfaceView ?: view
        val rect = Rect()
        pictureBounds = if (surface.getGlobalVisibleRect(rect)) rect else null
    }

    fun setSubtitleAppearance(value: SubtitleAppearance) {
        if (closed || subtitleAppearance == value) return
        subtitleAppearance = value
        subtitleRenderer?.appearance = value
    }

    private fun removeSubtitleRenderer() {
        subtitleRenderer?.let { renderer ->
            renderer.setCues(null)
            (renderer.parent as? ViewGroup)?.removeView(renderer)
        }
        subtitleRenderer = null
        layout?.subtitleView?.visibility = View.VISIBLE
    }

    fun play() {
        if (closed || failure != null) return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
        // A dismissed notification or a remote STOP leaves the player idle; play must prepare it again.
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        started = true
        player.play()
    }

    fun pause() {
        if (!closed) { player.pause(); playing = false }
    }

    fun startAt(resume: PlaybackResume) {
        if (closed) return
        player.seekTo(resume.position.coerceAtLeast(0L))
        refreshPosition()
        // Set even when paused, so the view does not start it from the top instead.
        started = true
        if (resume.playing) play()
    }

    fun setBackground(on: Boolean) {
        if (closed || on == background) return
        if (on) {
            session = PlaybackSession(appContext, player, subtitleDirectory.name) { pause() }.also { it.open() }
            player.setWakeMode(C.WAKE_MODE_NETWORK)
        } else {
            session?.close()
            session = null
            player.setWakeMode(C.WAKE_MODE_NONE)
        }
        applyPicture()
        applyService()
    }

    fun onScreen(visible: Boolean) {
        if (closed || onScreen == visible) return
        onScreen = visible
        applyPicture()
        applyService()
    }

    private fun applyPicture() {
        if (closed) return
        val decoding = C.TRACK_TYPE_VIDEO !in player.trackSelectionParameters.disabledTrackTypes
        val decode = decodePicture(onScreen, background && player.playWhenReady, decoding)
        if (decode == decoding) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !decode).build()
    }

    private fun applyService() {
        val open = session ?: return
        when (serviceAction(open.published, onScreen, player.playWhenReady, failure != null)) {
            ServiceAction.START -> if (!open.start()) pause()
            ServiceAction.STOP -> open.stop()
            ServiceAction.LEAVE -> Unit
        }
    }

    fun refreshPosition() {
        if (closed) return
        position = player.currentPosition.coerceAtLeast(0L)
        if (player.duration != C.TIME_UNSET) duration = player.duration.coerceAtLeast(0L)
        seekable = player.isCurrentMediaItemSeekable
    }

    fun seek(target: Long) {
        if (!closed && seekable) {
            player.seekTo(target.coerceIn(0L, duration))
            refreshPosition()
        }
    }

    fun changeSpeed(value: Float) {
        if (!closed) { speed = value; player.setPlaybackSpeed(value) }
    }

    fun selectAudio(id: Int): Boolean {
        if (closed) return false
        if (id == -1) {
            preferredAudio = null
            restoreAudio = false
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            audioTrack = -1
            audioOff = true
            return true
        }
        audioOff = false
        return selectTrack(id, C.TRACK_TYPE_AUDIO)
    }

    fun selectSubtitle(id: Int): Boolean {
        if (closed) return false
        pendingExternalId = null
        if (id == -1) {
            preferredSubtitle = null
            restoreSubtitle = false
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            subtitleTrack = -1
            return true
        }
        return selectTrack(id, C.TRACK_TYPE_TEXT)
    }

    private fun selectTrack(id: Int, type: Int): Boolean {
        if (closed) return false
        val choices = if (type == C.TRACK_TYPE_AUDIO) audioTracks else subtitleTracks
        if (choices.none { it.id == id && it.supported }) return false
        val override = trackOverrides[id] ?: return false
        if (type == C.TRACK_TYPE_AUDIO) {
            preferredAudio = trackIdentities[id]
            restoreAudio = false
        } else {
            preferredSubtitle = trackIdentities[id]
            restoreSubtitle = false
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false).setOverrideForType(override).build()
        return true
    }

    fun addSubtitle(file: File, label: String): Boolean {
        if (closed) return false
        val mimeType = subtitleMimeType(file.name) ?: return false
        val id = file.name
        externalSubtitles.add(MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setMimeType(mimeType).setId(id).setLabel(label).build())
        pendingExternalId = id
        preferredSubtitle = null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
        reloadAtCurrentPosition()
        return true
    }

    private val reloadSubtitles = Runnable { if (!closed) reloadAtCurrentPosition() }

    fun setSubtitleDelay(value: Long) {
        if (closed) return
        subtitleDelayMs = value.coerceIn(-600_000L, 600_000L)
        subtitleOffset.microseconds = subtitleDelayMs * 1000L
        // Buffered cues keep the old offset, so re-prepare; debounced so repeated changes cause one reload.
        handler.removeCallbacks(reloadSubtitles)
        handler.postDelayed(reloadSubtitles, 250)
    }

    private fun mediaItem(): MediaItem = MediaItem.Builder().setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .setSubtitleConfigurations(externalSubtitles).build()

    private fun reloadAtCurrentPosition() {
        handler.removeCallbacks(reloadSubtitles)
        val at = player.currentPosition.coerceAtLeast(0L)
        preferredAudio = preferredAudio ?: trackIdentities[audioTrack]
        if (pendingExternalId == null) preferredSubtitle = preferredSubtitle ?: trackIdentities[subtitleTrack]
        restoreAudio = preferredAudio != null && !audioOff
        restoreSubtitle = pendingExternalId == null && preferredSubtitle != null &&
            C.TRACK_TYPE_TEXT !in player.trackSelectionParameters.disabledTrackTypes
        player.setMediaItem(mediaItem(), at)
        player.prepare()
    }

    private fun refreshTracks() {
        val audio = mutableListOf<MediaTrack>()
        val subtitles = mutableListOf<MediaTrack>()
        trackOverrides.clear()
        trackIdentities.clear()
        audioTrack = -1
        subtitleTrack = -1
        var nextId = 0
        var externalChoice: Int? = null
        player.currentTracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO && group.type != C.TRACK_TYPE_TEXT) return@forEach
            kotlin.repeat(group.length) { index ->
                val id = nextId++
                val format = group.getTrackFormat(index)
                val supported = group.isTrackSupported(index)
                val label = trackNames.getTrackName(format).ifBlank { "Track ${index + 1}" }
                val track = MediaTrack(id, if (supported) label else "$label · Unsupported on this device", supported)
                trackOverrides[id] = TrackSelectionOverride(group.mediaTrackGroup, index)
                trackIdentities[id] = mediaTrackIdentity(format)
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    audio.add(track)
                    if (group.isTrackSelected(index)) audioTrack = id
                } else {
                    subtitles.add(track)
                    if (group.isTrackSelected(index)) subtitleTrack = id
                    if (pendingExternalId != null && unmergedMediaTrackId(format.id) == pendingExternalId && supported) externalChoice = id
                }
            }
        }
        audioTracks = audio
        subtitleTracks = subtitles
        if (restoreAudio) audio.firstOrNull { it.supported && trackIdentities[it.id] == preferredAudio }
            ?.let { selectTrack(it.id, C.TRACK_TYPE_AUDIO) }
        if (restoreSubtitle) subtitles.firstOrNull { it.supported && trackIdentities[it.id] == preferredSubtitle }
            ?.let { selectTrack(it.id, C.TRACK_TYPE_TEXT) }
        externalChoice?.let { id ->
            pendingExternalId = null
            selectTrack(id, C.TRACK_TYPE_TEXT)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(reloadSubtitles)
        player.removeListener(listener)
        removeSubtitleRenderer()
        layout?.removeOnLayoutChangeListener(pictureLayout)
        layout?.player = null
        layout = null
        // Stops the service and releases the session before the player it is showing.
        session?.close()
        session = null
        player.release()
        cleanupScope.launch { subtitleDirectory.deleteRecursively() }
    }

    private companion object {
        const val SEEK_INCREMENT_MS = 10_000L
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
