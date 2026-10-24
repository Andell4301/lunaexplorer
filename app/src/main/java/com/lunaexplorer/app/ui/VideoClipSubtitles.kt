package com.lunaexplorer.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import com.lunaexplorer.app.model.SubtitleAppearance
import com.lunaexplorer.app.playback.PlaybackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TreeMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

internal data class VideoClipSubtitle(val identity: MediaTrackIdentity, val externalFile: File? = null)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun readVideoClipSubtitles(
    source: PlaybackSource,
    subtitle: VideoClipSubtitle,
    startMs: Long,
    endMs: Long,
    delayMs: Long,
    onProgress: (Float?) -> Unit = {},
): VideoClipSubtitles {
    val context = currentCoroutineContext()
    val reportProgress = { progress: Float? -> context.ensureActive(); onProgress(progress) }
    val builder = VideoClipSubtitleBuilder(startMs, endMs, delayMs)
    val external = subtitle.externalFile
    val allowEmpty = if (external == null) {
        val endTimeUs = try {
            Math.multiplyExact(Math.subtractExact(endMs, delayMs), 1000L)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        readEmbeddedSubtitleCues(source, subtitle.identity, onProgress = reportProgress, endTimeUs = endTimeUs) { cues, behavior ->
            builder.add(cues, behavior)
        }
    } else {
        runInterruptible(Dispatchers.IO) {
            reportProgress(0f)
            require(external.length() <= MAX_SUBTITLE_BYTES) { "This subtitle file is too large to open here" }
            val bytes = external.inputStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    context.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(read <= MAX_SUBTITLE_BYTES - output.size()) { "This subtitle file is too large to open here" }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            context.ensureActive()
            require(bytes.size <= MAX_SUBTITLE_BYTES) { "This subtitle file is too large to open here" }
            val format = Format.Builder().setSampleMimeType(subtitleMimeType(external.name)).build()
            val factory = OffsetSubtitleParserFactory(SubtitleOffset())
            require(factory.supportsFormat(format)) { "These subtitles cannot be burned in" }
            val parser = factory.create(format)
            parser.parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues()) { cues ->
                context.ensureActive()
                builder.add(cues, parser.cueReplacementBehavior)
            }
            reportProgress(1f)
        }
        false
    }
    return runInterruptible(Dispatchers.IO) { builder.build(allowEmpty) { context.ensureActive() } }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VideoClipSubtitles(private val changes: List<Pair<Long, List<Cue>>>) {
    fun cuesAt(presentationTimeUs: Long): List<Cue> {
        val index = changes.binarySearch { it.first.compareTo(presentationTimeUs) }
        val before = if (index >= 0) index else -index - 2
        return if (before < 0) emptyList() else changes[before].second
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VideoClipSubtitleBuilder(startMs: Long, endMs: Long, delayMs: Long) {
    private val startUs = Math.multiplyExact(startMs, 1000L)
    private val endUs = Math.multiplyExact(endMs, 1000L)
    private val delayUs = Math.multiplyExact(delayMs, 1000L)
    private val samples = mutableListOf<Pair<CuesWithTiming, Int>>()
    private var characters = 0L
    private var bitmapBytes = 0L

    init {
        require(startMs >= 0L && endMs > startMs) { "Choose a valid clip range" }
    }

    fun add(cues: CuesWithTiming, replacementBehavior: Int) {
        val start = startOf(cues)
        val previous = samples.lastOrNull()
        if (previous != null && previous.first.durationUs == C.TIME_UNSET &&
            startOf(previous.first) <= start && start <= startUs) {
            account(previous.first, -1)
            samples[samples.lastIndex] = withoutCues(previous.first) to previous.second
        }
        val kept = if (start >= endUs ||
            (cues.durationUs != C.TIME_UNSET && Math.addExact(start, cues.durationUs) <= startUs)) withoutCues(cues) else cues
        account(kept, 1)
        require(samples.size < 100_000 && characters <= MAX_SUBTITLE_TEXT_CHARS && bitmapBytes <= 64L * 1024 * 1024) {
            "This subtitle track is too large to burn in"
        }
        samples += kept to replacementBehavior
    }

    private fun startOf(cues: CuesWithTiming) =
        Math.addExact(if (cues.startTimeUs == C.TIME_UNSET) 0L else cues.startTimeUs, delayUs)

    private fun withoutCues(cues: CuesWithTiming) = CuesWithTiming(emptyList(), cues.startTimeUs, cues.durationUs)

    private fun account(cues: CuesWithTiming, direction: Int) {
        characters += direction * cues.cues.sumOf { it.text?.length?.toLong() ?: 0L }
        bitmapBytes += direction * cues.cues.sumOf { it.bitmap?.allocationByteCount?.toLong() ?: 0L }
    }

    fun build(allowEmpty: Boolean = false, active: () -> Unit = {}): VideoClipSubtitles {
        check(allowEmpty || samples.isNotEmpty()) { "These subtitles could not be read" }
        val sorted = samples.sortedBy { it.first.startTimeUs }
        val events = TreeMap<Long, MutableList<Pair<Int, List<Cue>?>>>()
        sorted.forEachIndexed { index, (timed, behavior) ->
            active()
            val start = startOf(timed)
            val next = sorted.getOrNull(index + 1)?.first?.let(::startOf) ?: endUs
            val timedEnd = if (timed.durationUs == C.TIME_UNSET) next else Math.addExact(start, timed.durationUs)
            val end = if (behavior == Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE) minOf(timedEnd, next) else timedEnd
            val clippedStart = maxOf(startUs, start) - startUs
            val clippedEnd = minOf(endUs, end) - startUs
            if (clippedStart >= clippedEnd || timed.cues.isEmpty()) return@forEachIndexed
            require(timed.cues.none { it.bitmap == null && it.verticalType != Cue.TYPE_UNSET }) {
                "Vertical subtitles cannot be burned in"
            }
            events.getOrPut(clippedStart) { mutableListOf() } += index to timed.cues
            events.getOrPut(clippedEnd) { mutableListOf() } += index to null
        }
        val visible = sortedMapOf<Int, List<Cue>>()
        var references = 0L
        val changes = events.map { (time, updates) ->
            active()
            updates.forEach { (id, cues) -> if (cues == null) visible.remove(id) else visible[id] = cues }
            val count = visible.values.sumOf { it.size.toLong() }
            references += count
            require(references <= 500_000L) { "This subtitle track is too large to burn in" }
            time to visible.values.flatten()
        }
        return VideoClipSubtitles(changes)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun createVideoClipSubtitleOverlay(
    context: Context,
    subtitles: VideoClipSubtitles,
    appearance: SubtitleAppearance,
): BitmapOverlay = object : BitmapOverlay() {
    private var renderer: VideoClipSubtitleRenderer? = null
    private var size: Size? = null
    private var previousCues: List<Cue>? = null
    private var bitmap: Bitmap? = null
    private var released = false

    @Synchronized override fun configure(videoSize: Size) {
        check(!released)
        if (size != videoSize) previousCues = null
        size = videoSize
    }

    @Synchronized override fun getBitmap(presentationTimeUs: Long): Bitmap {
        check(!released)
        val cues = subtitles.cuesAt(presentationTimeUs)
        if (cues !== previousCues || bitmap == null) {
            val targetSize = checkNotNull(size)
            val drawing = renderer ?: VideoClipSubtitleRenderer(context.applicationContext, appearance).also { renderer = it }
            bitmap = drawing.draw(targetSize, cues)
            previousCues = cues
        }
        return checkNotNull(bitmap)
    }

    @Synchronized override fun release() {
        if (released) return
        released = true
        try {
            super.release()
        } finally {
            renderer?.close()
            renderer = null
            bitmap = null
            previousCues = null
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class VideoClipSubtitleRenderer(
    private val context: Context,
    private val appearance: SubtitleAppearance,
) : AutoCloseable {
    // Transformer can wait for GL on the main thread, so the overlay view needs its own looper.
    private val thread = HandlerThread("VideoClipSubtitles").apply { start() }
    private val handler = Handler(thread.looper)
    private var view: LunaSubtitleView? = null
    private var bitmap: Bitmap? = null

    fun draw(size: Size, cues: List<Cue>): Bitmap = onThread {
        val drawing = view ?: LunaSubtitleView(context).also { it.appearance = appearance; view = it }
        val previous = bitmap
        val image = if (previous?.width == size.width && previous.height == size.height) previous else {
            previous?.recycle()
            Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).also { bitmap = it }
        }
        image.eraseColor(Color.TRANSPARENT)
        drawing.setCues(cues)
        drawing.measure(View.MeasureSpec.makeMeasureSpec(size.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size.height, View.MeasureSpec.EXACTLY))
        drawing.layout(0, 0, size.width, size.height)
        drawing.draw(Canvas(image))
        image
    }

    override fun close() {
        try {
            onThread {
                view?.setCues(null)
                view = null
                bitmap?.recycle()
                bitmap = null
            }
        } finally {
            thread.quitSafely()
        }
    }

    private fun <T> onThread(block: () -> T): T {
        val task = FutureTask(block)
        check(handler.post(task)) { "Subtitle renderer is closed" }
        return try {
            task.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw VideoFrameProcessingException(error)
        } catch (error: ExecutionException) {
            throw VideoFrameProcessingException(error.cause ?: error)
        }
    }
}
