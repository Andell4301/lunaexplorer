package com.lunaexplorer.app.ui

import android.content.Context
import android.media.metrics.LogSessionId
import android.os.Looper
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.Clock
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.lunaexplorer.app.debug.DebugLog
import com.lunaexplorer.app.model.SubtitleAppearance
import com.lunaexplorer.app.playback.PlaybackSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal data class VideoClipSelection(
    val audio: MediaTrackIdentity?,
    val audioOff: Boolean,
    val subtitle: VideoClipSubtitle?,
    val subtitleDelayMs: Long,
    val appearance: SubtitleAppearance,
    val video: Format? = null,
    val hasAudio: Boolean = false,
)

internal data class VideoClipRequest(
    val startMs: Long,
    val endMs: Long,
    val burnSubtitles: Boolean,
    val selection: VideoClipSelection,
) {
    init {
        require(startMs >= 0 && endMs > startMs && endMs <= Long.MAX_VALUE / 1000L) { "Invalid clip range" }
        require(!burnSubtitles || selection.subtitle != null) { "Select a subtitle track" }
        require(selection.audioOff || !selection.hasAudio || selection.audio != null) { "Select an audio track or turn audio off" }
    }
}

internal enum class VideoClipPhase { READING_SUBTITLES, RENDERING }

internal data class VideoClipProgress(val phase: VideoClipPhase, val percent: Int? = null)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun exportVideoClip(
    context: Context,
    source: PlaybackSource,
    request: VideoClipRequest,
    output: File,
    onProgress: (VideoClipProgress) -> Unit = {},
) {
    var complete = false
    var overlay: BitmapOverlay? = null
    try {
        if (request.burnSubtitles) {
            DebugLog.i("VideoClip") { "Reading subtitles for ${request.startMs}..${request.endMs} ms" }
            withContext(Dispatchers.Main.immediate) { onProgress(VideoClipProgress(VideoClipPhase.READING_SUBTITLES)) }
            val subtitles = coroutineScope {
                val reports = Channel<Float?>(Channel.CONFLATED)
                val reporting = launch(Dispatchers.Main.immediate) {
                    for (fraction in reports) onProgress(VideoClipProgress(VideoClipPhase.READING_SUBTITLES,
                        fraction?.let { (it * 100).toInt().coerceIn(0, 100) }))
                }
                try {
                    readVideoClipSubtitles(source, requireNotNull(request.selection.subtitle),
                        request.startMs, request.endMs, request.selection.subtitleDelayMs) { reports.trySend(it) }
                } finally {
                    reports.close()
                    reporting.join()
                }
            }
            DebugLog.i("VideoClip") { "Subtitles ready" }
            overlay = createVideoClipSubtitleOverlay(context.applicationContext, subtitles, request.selection.appearance)
        }
        val effects = overlay?.let { listOf(OverlayEffect(listOf(it))) }.orEmpty()
        val item = EditedMediaItem.Builder(MediaItem.Builder().setUri(source.uri)
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(request.startMs).setEndPositionMs(request.endMs).build()).build())
            .setRemoveAudio(request.selection.audioOff || request.selection.audio == null)
            .setEffects(Effects(emptyList(), effects)).build()
        withContext(Dispatchers.Main.immediate) {
            coroutineScope {
                onProgress(VideoClipProgress(VideoClipPhase.RENDERING))
                DebugLog.i("VideoClip") { "Rendering ${request.startMs}..${request.endMs} ms · subtitles ${request.burnSubtitles}" }
                val completion = CompletableDeferred<ExportResult>()
                val transformer = clipTransformer(context.applicationContext, source, request)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            completion.complete(exportResult)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            completion.completeExceptionally(exportException)
                        }
                    }).build()
                try {
                    transformer.start(item, output.absolutePath)
                    val progress = launch {
                        val holder = ProgressHolder()
                        while (isActive) {
                            onProgress(VideoClipProgress(VideoClipPhase.RENDERING,
                                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null))
                            delay(250)
                        }
                    }
                    try {
                        val result = completion.await()
                        check(result.videoFrameCount > 0) { "No video frames in this range" }
                        check(request.burnSubtitles || result.videoEncoderName == null) { "Lossless video export failed" }
                        check(result.audioEncoderName == null) { "Lossless audio export failed" }
                        onProgress(VideoClipProgress(VideoClipPhase.RENDERING, 100))
                        DebugLog.i("VideoClip") { "Rendered ${result.videoFrameCount} frames" }
                    } finally {
                        progress.cancel()
                    }
                } finally {
                    withContext(NonCancellable) { transformer.cancel() }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        complete = true
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        if (error is ExportException) {
            val cause = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<VideoClipFailure>().firstOrNull()
            throw IllegalStateException(cause?.message ?: "Clip export failed: ${error.errorCodeName}", error)
        }
        throw error
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                overlay?.release()
            } finally {
                if (!complete) output.delete()
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class)
private fun clipTransformer(context: Context, source: PlaybackSource, request: VideoClipRequest): Transformer.Builder {
    val parsers = OffsetSubtitleParserFactory(SubtitleOffset())
    val mediaSources = DefaultMediaSourceFactory(source.dataSources, lunaExtractorsFactory(parsers))
        .setEnableClippingInMediaPeriod(true)
    val loaders = ExoPlayerAssetLoader.Factory(context, DefaultDecoderFactory.Builder(context).build(),
        Clock.DEFAULT, mediaSources, TrackSelector.Factory { ClipTrackSelector(it, request.selection) }, null, null)
    val encoder = if (request.burnSubtitles) DefaultEncoderFactory.Builder(context)
        .setEnableFallback(false).setEnableFormatFallback(false).build() else null
    return Transformer.Builder(context).setLooper(Looper.getMainLooper()).setAssetLoaderFactory(loaders)
        .setEncoderFactory(ClipEncoderFactory(encoder, request.selection.video?.averageBitrate ?: Format.NO_VALUE))
        // Edit lists retain compressed preroll from the preceding keyframe without encoding it.
        .experimentalSetMp4EditListTrimEnabled(!request.burnSubtitles)
        .setUsePlatformDiagnostics(false)
        .also { builder ->
            if (request.burnSubtitles) builder.setVideoMimeType(
                if (ColorInfo.isTransferHdr(request.selection.video?.colorInfo)) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
        }
}

internal class VideoClipFailure(message: String) : IllegalStateException(message)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ClipEncoderFactory(
    private val videoEncoder: Codec.EncoderFactory?,
    private val sourceVideoBitrate: Int = Format.NO_VALUE,
) : Codec.EncoderFactory {
    override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec =
        throw ExportException.createForUnexpected(VideoClipFailure("This audio track cannot be saved losslessly in MP4"))

    override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec {
        val encoder = videoEncoder
            ?: throw ExportException.createForUnexpected(VideoClipFailure("This video cannot be saved losslessly in MP4"))
        val frameRate = format.frameRate.takeIf { it > 0 } ?: 30f
        val estimated = (format.width.toDouble() * format.height * frameRate * .14).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
        val bitrate = maxOf(sourceVideoBitrate, format.averageBitrate, estimated)
        return encoder.createForVideoEncoding(format.buildUpon().setAverageBitrate(bitrate).build(), logSessionId)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class ClipTrackSelector(context: Context, private val selection: VideoClipSelection) : DefaultTrackSelector(context) {
    init {
        parameters = buildUponParameters().setForceHighestSupportedBitrate(true)
            .setConstrainAudioChannelCountToDeviceCapabilities(false)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    }

    override fun selectAudioTrack(
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        rendererMixedMimeTypeAdaptationSupports: IntArray,
        params: Parameters,
    ): Pair<ExoTrackSelection.Definition, Int>? {
        if (selection.audioOff || selection.audio == null) return null
        for (renderer in 0 until mappedTrackInfo.rendererCount) {
            if (mappedTrackInfo.getRendererType(renderer) != C.TRACK_TYPE_AUDIO) continue
            val groups = mappedTrackInfo.getTrackGroups(renderer)
            val selected = selectedClipTrack(List(groups.length) { groups[it] }, selection.audio) ?: continue
            return Pair(ExoTrackSelection.Definition(selected.first, selected.second), renderer)
        }
        throw VideoClipFailure("The selected audio track is unavailable")
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun selectedClipTrack(groups: List<TrackGroup>, identity: MediaTrackIdentity): kotlin.Pair<TrackGroup, Int>? {
    val matches = groups.flatMap { group ->
        (0 until group.length).filter { mediaTrackIdentity(group.getFormat(it)) == identity }.map { group to it }
    }
    if (matches.size > 1) throw VideoClipFailure("The selected audio track is ambiguous")
    return matches.singleOrNull()
}
