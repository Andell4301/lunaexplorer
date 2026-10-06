package com.lunaexplorer.app.ui

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
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
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import com.lunaexplorer.app.playback.PlaybackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.Locale
import java.nio.channels.SeekableByteChannel
import kotlin.coroutines.ContinuationInterceptor

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun readableSubtitleFormat(format: Format): Boolean =
    (if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) format.codecs else format.sampleMimeType) in setOf(
        MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_SSA, MimeTypes.TEXT_VTT,
        MimeTypes.APPLICATION_MP4VTT, MimeTypes.APPLICATION_TTML, MimeTypes.APPLICATION_TX3G,
    ) && format.drmInitData == null

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun readEmbeddedSubtitles(source: PlaybackSource, identity: MediaTrackIdentity): String =
    readEmbeddedSubtitleDocument(source, identity).text

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun readEmbeddedSubtitleDocument(
    source: PlaybackSource,
    identity: MediaTrackIdentity,
    onProgress: (Float?) -> Unit = {},
): EmbeddedSubtitleDocument {
    val context = currentCoroutineContext()
    return runInterruptible(Dispatchers.IO) {
        val active = { context.ensureActive() }
        val parser = OffsetSubtitleParserFactory(SubtitleOffset())
        val metadata = SubtitleMetadata()
        fun selected(format: Format): Boolean =
            mediaTrackIdentity(format).copy(mimeType = null) == identity.copy(mimeType = null)
        val parsers = object : SubtitleParser.Factory {
            override fun supportsFormat(format: Format): Boolean =
                selected(format) && readableSubtitleFormat(format) && parser.supportsFormat(format)
            override fun getCueReplacementBehavior(format: Format): Int = parser.getCueReplacementBehavior(format)
            override fun create(format: Format): SubtitleParser {
                metadata.format(format)
                val delegate = parser.create(format)
                return object : SubtitleParser by delegate {
                    override fun parse(data: ByteArray, offset: Int, length: Int,
                        outputOptions: SubtitleParser.OutputOptions, output: Consumer<CuesWithTiming>) {
                        active()
                        metadata.sample(format, data, offset, length)
                        delegate.parse(data, offset, length, outputOptions, output)
                    }
                }
            }
        }
        val transcript = SubtitleTranscript(metadata)
        val tracks = mutableMapOf<Int, TrackOutput>()
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) {
                if (type == C.TRACK_TYPE_TEXT) SubtitleTextOutput(::selected, transcript, active)
                else SkippingTrackOutput()
            }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = metadata.duration(seekMap.durationUs)
        }
        var channel: SeekableByteChannel? = null
        var data: DataSource? = null
        val extractors = lunaExtractorsFactory(parsers, MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
            .createExtractors(source.uri, emptyMap())
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
            var input = open(0L)
            val extractor = extractors.firstOrNull { candidate ->
                active()
                try {
                    candidate.sniff(input)
                } catch (_: EOFException) {
                    false
                } finally {
                    input.resetPeekPosition()
                }
            } ?: error("This video's subtitles could not be read")
            extractor.init(output)
            val seek = PositionHolder()
            var reported = -1f
            onProgress(if (input.length > 0) 0f else null)
            while (true) {
                active()
                when (extractor.read(input, seek)) {
                    Extractor.RESULT_END_OF_INPUT -> break
                    Extractor.RESULT_SEEK -> { data?.close(); input = open(seek.position) }
                }
                if (input.length > 0) {
                    val progress = (input.position.toDouble() / input.length).toFloat().coerceIn(0f, 1f)
                    if (kotlin.math.abs(progress - reported) >= 0.01f) {
                        reported = progress
                        onProgress(progress)
                    }
                }
            }
            onProgress(1f)
            EmbeddedSubtitleDocument(transcript.text(active), metadata.text())
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
private class SubtitleTextOutput(
    private val selected: (Format) -> Boolean,
    private val transcript: SubtitleTranscript,
    private val active: () -> Unit,
) : TrackOutput {
    private val discard = SkippingTrackOutput()
    private val decoder = CueDecoder()
    private val pending = ByteArrayOutputStream()
    private var readable = false

    override fun format(format: Format) {
        readable = selected(format) && format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        active()
        if (!readable) return discard.sampleData(input, length, allowEndOfInput, sampleDataPart)
        val bytes = ByteArray(minOf(length, 8192))
        val read = input.read(bytes, 0, bytes.size)
        if (read == C.RESULT_END_OF_INPUT) {
            if (!allowEndOfInput) throw EOFException()
            return read
        }
        append(bytes, 0, read)
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        active()
        if (readable) append(data.data, data.position, length)
        data.skipBytes(length)
    }

    private fun append(bytes: ByteArray, offset: Int, length: Int) {
        require(length <= MAX_SUBTITLE_SAMPLE_BYTES - pending.size()) { "This subtitle track is too large to open here" }
        pending.write(bytes, offset, length)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        active()
        if (!readable) return
        require(cryptoData == null && flags and C.BUFFER_FLAG_ENCRYPTED == 0) { "Encrypted subtitles cannot be read" }
        val bytes = pending.toByteArray()
        require(offset in 0..bytes.size && size in 0..(bytes.size - offset)) { "This subtitle track could not be read" }
        val start = bytes.size - offset - size
        transcript.add(decoder.decode(timeUs, bytes, start, size))
        pending.reset()
        pending.write(bytes, bytes.size - offset, offset)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class SubtitleTranscript(private val metadata: SubtitleMetadata) {
    private val captions = mutableListOf<Pair<Long, String>>()
    private var characters = 0

    fun add(cues: CuesWithTiming) {
        metadata.cues(cues)
        val text = cues.cues.mapNotNull { it.text?.toString() }.filter { it.isNotEmpty() }.joinToString("\n")
        if (text.isEmpty()) return
        val start = cues.startTimeUs.coerceAtLeast(0L)
        val end = if (cues.durationUs > 0L) start + minOf(cues.durationUs, Long.MAX_VALUE - start) else null
        val caption = "${timestamp(start)}${end?.let { " --> ${timestamp(it)}" }.orEmpty()}\n$text\n\n"
        require(caption.length <= MAX_SUBTITLE_TEXT_CHARS - characters && captions.size < 100_000) {
            "This subtitle track is too large to open here"
        }
        characters += caption.length
        captions.add(start to caption)
    }

    fun text(active: () -> Unit): String {
        check(captions.isNotEmpty()) { "No subtitle text was found" }
        return buildString(characters) {
            captions.sortedBy { it.first }.forEach { (_, text) -> active(); append(text) }
        }
    }

    private fun timestamp(microseconds: Long): String {
        val millis = microseconds / 1000L
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d",
            millis / 3_600_000L, millis / 60_000L % 60, millis / 1000L % 60, millis % 1000)
    }
}

internal const val MAX_SUBTITLE_SAMPLE_BYTES = 2 * 1024 * 1024
internal const val MAX_SUBTITLE_TEXT_CHARS = 8 * 1024 * 1024

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class SkippingTrackOutput : TrackOutput {
    override fun format(format: Format) = Unit
    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) = Unit
    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)
    private val fallback = DiscardingTrackOutput()
    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (input !is ExtractorInput) return fallback.sampleData(input, length, allowEndOfInput, sampleDataPart)
        val count = input.skip(length)
        if (count == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw EOFException()
        return count
    }
}
