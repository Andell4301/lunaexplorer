package com.lunaexplorer.app.ui

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

internal class SubtitleOffset {
    @Volatile var microseconds: Long = 0L
}

/** Changes cue times without flattening styled text or modifying audio/video timestamps. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OffsetSubtitleParserFactory(private val offset: SubtitleOffset) : SubtitleParser.Factory {
    private val delegate = DefaultSubtitleParserFactory()
    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)
    override fun getCueReplacementBehavior(format: Format): Int = delegate.getCueReplacementBehavior(format)
    override fun create(format: Format): SubtitleParser {
        if (format.sampleMimeType != MimeTypes.TEXT_SSA) return OffsetSubtitleParser(delegate.create(format), offset.microseconds)
        val initialized = format.buildUpon().setInitializationData(format.initializationData.map(::normalizeSsaSections)).build()
        return OffsetSubtitleParser(SsaSectionParser(delegate.create(initialized)), offset.microseconds)
    }
}

/** Media3 1.11's SSA parser skips the last header/style line before an adjacent section. */
internal fun normalizeSsaSections(bytes: ByteArray): ByteArray {
    val charset = when {
        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }
    val text = String(bytes, charset)
    if (!text.contains("[Script Info]", ignoreCase = true)) return bytes
    // This is only the parser's input; the user's file and the byte-preserving cache stay intact.
    return text.replace(Regex("(?im)^(\\[(?:Script Info|V4\\+? Styles|Events)\\])"), "\n$1").toByteArray(Charsets.UTF_8)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class SsaSectionParser(private val delegate: SubtitleParser) : SubtitleParser {
    override fun getCueReplacementBehavior(): Int = delegate.cueReplacementBehavior
    override fun reset() = delegate.reset()

    override fun parse(data: ByteArray, offset: Int, length: Int, outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>) {
        val adjusted = normalizeSsaSections(data.copyOfRange(offset, offset + length))
        delegate.parse(adjusted, 0, adjusted.size, outputOptions, output)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OffsetSubtitleParser(private val delegate: SubtitleParser, private val offsetUs: Long) : SubtitleParser {
    override fun getCueReplacementBehavior(): Int = delegate.cueReplacementBehavior
    override fun reset() = delegate.reset()

    override fun parse(data: ByteArray, offset: Int, length: Int, outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>) {
        val adjustedOptions = when {
            outputOptions.startTimeUs == C.TIME_UNSET -> SubtitleParser.OutputOptions.allCues()
            outputOptions.outputAllCues -> SubtitleParser.OutputOptions.cuesAfterThenRemainingCuesBefore(outputOptions.startTimeUs - offsetUs)
            else -> SubtitleParser.OutputOptions.onlyCuesAfter(outputOptions.startTimeUs - offsetUs)
        }
        delegate.parse(data, offset, length, adjustedOptions) { cues ->
            // TIME_UNSET means relative to the containing sample; zero is the same origin and
            // permits a real offset without arithmetic on the sentinel value.
            val start = if (cues.startTimeUs == C.TIME_UNSET) 0L else cues.startTimeUs
            output.accept(CuesWithTiming(cues.cues, start + offsetUs, cues.durationUs))
        }
    }
}
