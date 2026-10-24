package com.lunaexplorer.app.ui

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
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

internal class VideoFrameIndex(timestampsUs: LongArray) {
    private val times = timestampsUs
    private val count: Int

    init {
        times.sort()
        var distinct = 0
        for (time in times) if (time >= 0 && (distinct == 0 || time != times[distinct - 1])) times[distinct++] = time
        count = distinct
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
internal suspend fun readVideoFrameIndex(source: PlaybackSource, selected: MediaTrackIdentity?): VideoFrameIndex {
    val context = currentCoroutineContext()
    return runInterruptible(Dispatchers.IO) {
        val active = { context.ensureActive() }
        var channel: SeekableByteChannel? = null
        var data: DataSource? = null
        val tracks = mutableMapOf<Int, FrameIndexTrackOutput>()
        val extractors = lunaExtractorsFactory(SubtitleParser.Factory.UNSUPPORTED, MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
            .createExtractors(source.uri, emptyMap())
        var tracksReady = false
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) {
                FrameIndexTrackOutput(type == C.TRACK_TYPE_VIDEO, selected, active)
            }
            override fun endTracks() { tracksReady = true }
            override fun seekMap(seekMap: SeekMap) = Unit
        }
        try {
            runBlocking(context.minusKey(ContinuationInterceptor)) { source.openChannel?.invoke()?.also { channel = it } }
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
            while (true) {
                active()
                val result = extractor.read(input, seek)
                // MP4 exposes the full sample table before its video packets are read.
                if (tracksReady && extractor is Mp4Extractor) {
                    val track = tracks.entries.singleOrNull { it.value.selected }
                        ?: error("The selected video track could not be read")
                    table = extractor.getSampleTimestampsUs(track.key)
                    break
                }
                if (result == Extractor.RESULT_END_OF_INPUT) break
                if (result == Extractor.RESULT_SEEK) { data?.close(); input = open(seek.position) }
            }
            val times = table ?: tracks.values.singleOrNull { it.selected }?.timestamps()
                ?: error("The selected video track could not be read")
            check(times.isNotEmpty()) { "No video frames were found" }
            check(times.size <= MAX_INDEXED_VIDEO_FRAMES) { "This video has too many frames to step through" }
            active()
            VideoFrameIndex(times).also { active() }
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

    fun timestamps() = times.copyOf(count)
}

private const val MAX_INDEXED_VIDEO_FRAMES = 4_000_000
