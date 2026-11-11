package com.lunaexplorer.app.ui

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ChunkIndexProvider
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.TrackAwareSeekMap
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import com.lunaexplorer.app.playback.PlaybackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import java.io.EOFException
import java.nio.channels.SeekableByteChannel
import kotlin.coroutines.ContinuationInterceptor

internal class VideoFrameIndex(
    timestampsUs: LongArray,
    private val fromUs: Long = 0,
    private val untilUs: Long = Long.MAX_VALUE,
) {
    private val times = timestampsUs
    private val count: Int

    init {
        times.sort()
        var distinct = 0
        for (time in times) if (time >= 0 && (distinct == 0 || time != times[distinct - 1])) times[distinct++] = time
        count = distinct
    }

    fun covers(positionUs: Long, forward: Boolean): Boolean {
        if (positionUs < fromUs || positionUs >= untilUs) return false
        val next = adjacent(positionUs, forward)
        return if (next != null) next in fromUs until untilUs
        else if (forward) untilUs == Long.MAX_VALUE else fromUs == 0L
    }

    fun adjacent(positionUs: Long, forward: Boolean): Long? {
        val found = times.binarySearch(positionUs, toIndex = count)
        val index = if (forward) {
            if (found >= 0) found + 1 else -found - 1
        } else {
            if (found >= 0) found - 1 else -found - 2
        }
        return if (index in 0 until count) times[index] else null
    }
}

// Seeking past a fractional millisecond would skip the selected frame as decode-only.
internal fun videoFramePositionMs(timeUs: Long): Long = timeUs / 1000

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun readVideoFrameIndex(
    source: PlaybackSource,
    selected: MediaTrackIdentity?,
    positionUs: Long = 0,
): VideoFrameIndex {
    val context = currentCoroutineContext()
    return runInterruptible(Dispatchers.IO) {
        val active = { context.ensureActive() }
        var channel: SeekableByteChannel? = null
        var data: DataSource? = null
        val tracks = mutableMapOf<Int, FrameIndexTrackOutput>()
        var extractors = emptyArray<Extractor>()
        var tracksReady = false
        var seekMap: SeekMap? = null
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) {
                FrameIndexTrackOutput(type == C.TRACK_TYPE_VIDEO, selected, active)
            }
            override fun endTracks() { tracksReady = true }
            override fun seekMap(map: SeekMap) { seekMap = map }
        }
        try {
            runBlocking(context.minusKey(ContinuationInterceptor)) { source.openChannel?.invoke()?.also { channel = it } }
            extractors = lunaExtractorsFactory(SubtitleParser.Factory.UNSUPPORTED,
                if (channel == null) MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES else 0)
                .createExtractors(source.uri, emptyMap())
            data = if (channel == null) source.dataSources.createDataSource() else null
            val channelInput = channel?.let { SubtitleExtractorInput(it, active) }
            val reader = DataReader { bytes, offset, length -> active(); checkNotNull(data).read(bytes, offset, length) }
            fun open(position: Long): ExtractorInput {
                active()
                if (channelInput != null) return channelInput.apply { seek(position) }
                val length = checkNotNull(data).open(DataSpec.Builder().setUri(source.uri).setPosition(position).build())
                return DefaultExtractorInput(reader, position,
                    if (length == C.LENGTH_UNSET.toLong()) length else position + length)
            }
            var input = open(0)
            val extractor = extractors.firstOrNull { candidate ->
                active()
                try { candidate.sniff(input) }
                catch (_: EOFException) { false }
                finally { input.resetPeekPosition() }
            } ?: error("This video's frames could not be read")
            extractor.init(output)
            val seek = PositionHolder()
            var table: LongArray? = null
            var window: FrameIndexWindow? = null
            var choseWindow = false
            while (true) {
                active()
                if (window?.let { input.position >= it.untilPosition } == true) break
                val result = extractor.read(input, seek)
                // MP4 exposes the full sample table before its video packets are read.
                if (tracksReady && extractor is Mp4Extractor) {
                    val track = tracks.entries.singleOrNull { it.value.selected }
                        ?: error("The selected video track could not be read")
                    table = extractor.getSampleTimestampsUs(track.key)
                    break
                }
                val map = seekMap
                if (!choseWindow && tracksReady && map != null && extractor is MatroskaExtractor && channel != null) {
                    choseWindow = true
                    val track = tracks.entries.singleOrNull { it.value.selected }
                        ?: error("The selected video track could not be read")
                    window = frameIndexWindow(map, track.key, positionUs, input.length)
                    window?.let { range ->
                        tracks.values.forEach { it.clear() }
                        extractor.seek(range.fromPosition, range.fromUs)
                        input = open(range.fromPosition)
                    }
                    if (window != null) continue
                }
                if (result == Extractor.RESULT_END_OF_INPUT) break
                if (result == Extractor.RESULT_SEEK) { data?.close(); input = open(seek.position) }
            }
            val times = table ?: tracks.values.singleOrNull { it.selected }?.timestamps()
                ?: error("The selected video track could not be read")
            check(times.isNotEmpty()) { "No video frames were found" }
            check(times.size <= MAX_INDEXED_VIDEO_FRAMES) { "This video has too many frames to step through" }
            active()
            VideoFrameIndex(times, window?.fromUs ?: 0, window?.untilUs ?: Long.MAX_VALUE).also { active() }
        } finally {
            try {
                channel?.close()
                data?.close()
            } finally {
                extractors.forEach { it.release() }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class FrameIndexTrackOutput(
    private val video: Boolean,
    private val identity: MediaTrackIdentity?,
    private val active: () -> Unit,
) : TrackOutput {
    var selected = false
        private set
    private var times = LongArray(0)
    private var count = 0
    private val fallback = DiscardingTrackOutput()

    override fun format(format: Format) {
        selected = video && (identity == null || mediaTrackIdentity(format) == identity)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        active()
        if (!selected || timeUs < 0) return
        check(count < MAX_INDEXED_VIDEO_FRAMES) { "This video has too many frames to step through" }
        if (count == times.size) times = times.copyOf(maxOf(1024, times.size * 2).coerceAtMost(MAX_INDEXED_VIDEO_FRAMES))
        times[count++] = timeUs
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        active()
        if (input !is ExtractorInput) return fallback.sampleData(input, length, allowEndOfInput, sampleDataPart)
        val count = input.skip(length)
        if (count == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw EOFException()
        return count
    }

    fun clear() { count = 0 }
    fun timestamps() = times.copyOf(count)
}

private data class FrameIndexWindow(val fromPosition: Long, val fromUs: Long, val untilPosition: Long, val untilUs: Long)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun frameIndexWindow(map: SeekMap, track: Int, positionUs: Long, length: Long): FrameIndexWindow? {
    val perTrack = map as? TrackAwareSeekMap
    if (!(perTrack?.isSeekable(track) ?: map.isSeekable)) return null
    val chunks = (map as? ChunkIndexProvider)?.chunkIndex
    // Time-sorted cues may point to physically reordered clusters, which need a full scan.
    if (chunks != null && (1 until chunks.length).any {
        chunks.offsets[it] < chunks.offsets[it - 1] || chunks.timesUs[it] < chunks.timesUs[it - 1]
    }) return null
    fun points(timeUs: Long) = perTrack?.getSeekPoints(timeUs, track) ?: map.getSeekPoints(timeUs)
    val before = (positionUs - FRAME_INDEX_WINDOW_US).coerceAtLeast(0)
    val first = points(before).first
    val after = positionUs.coerceAtMost(Long.MAX_VALUE - FRAME_INDEX_WINDOW_US) + FRAME_INDEX_WINDOW_US
    val last = points(after).second
    val current = points(positionUs).first
    if (first.position > current.position || current.position > last.position) return null
    // Include the following cue interval so the last nearby frame still has a next frame.
    val stop = points(last.timeUs.coerceAtMost(Long.MAX_VALUE - 1) + 1).second
    if (stop.position < last.position || stop.timeUs < last.timeUs) return null
    val fromPosition = if (first.timeUs > before) 0 else first.position
    val fromUs = if (fromPosition == 0L) 0 else first.timeUs
    if (fromPosition < 0 || fromPosition >= length || fromUs < 0) return null
    return if (stop.position > last.position && stop.timeUs > last.timeUs && stop.position <= length) {
        FrameIndexWindow(fromPosition, fromUs, stop.position, stop.timeUs)
    } else FrameIndexWindow(fromPosition, fromUs, Long.MAX_VALUE, Long.MAX_VALUE)
}

private const val FRAME_INDEX_WINDOW_US = 5_000_000L
private const val MAX_INDEXED_VIDEO_FRAMES = 4_000_000
