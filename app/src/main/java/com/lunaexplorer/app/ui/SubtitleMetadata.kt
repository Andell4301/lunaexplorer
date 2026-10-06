package com.lunaexplorer.app.ui

import android.text.Spanned
import android.text.Annotation
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import java.util.Locale

internal data class EmbeddedSubtitleDocument(val text: String, val metadata: String)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SubtitleMetadata {
    private val source = StringBuilder()
    private val decoded = StringBuilder()
    private var samples = 0
    private var cues = 0
    private var truncated = false

    fun format(format: Format) {
        if (truncated) return
        append(source, buildString {
            appendLine("Track: ${format.id.orEmpty()}")
            appendLine("Label: ${format.label.orEmpty()}")
            appendLine("Language: ${format.language.orEmpty()}")
            appendLine("MIME: ${format.sampleMimeType.orEmpty()}")
            appendLine("Codecs: ${format.codecs.orEmpty()}")
            appendLine("Subsample offset: ${format.subsampleOffsetUs} µs")
            appendLine("Selection flags: ${format.selectionFlags}; role flags: ${format.roleFlags}")
            format.initializationData.forEachIndexed { index, bytes ->
                require(bytes.size <= MAX_SUBTITLE_SAMPLE_BYTES) { "This subtitle track is too large to open here" }
                appendLine("Initialization ${index + 1}: ${bytes.size} bytes")
                if (textual(format)) appendLine(bytes.toString(Charsets.UTF_8))
            }
            appendLine()
        })
    }

    fun duration(microseconds: Long) {
        if (microseconds != C.TIME_UNSET) append(source, "Duration: $microseconds µs\n\n")
    }

    fun sample(format: Format, bytes: ByteArray, offset: Int, length: Int) {
        if (truncated || !textual(format)) return
        require(length <= MAX_SUBTITLE_SAMPLE_BYTES) { "This subtitle track is too large to open here" }
        append(source, "Sample ${++samples}\n${String(bytes, offset, length, Charsets.UTF_8)}\n\n")
    }

    fun cues(timed: CuesWithTiming) {
        timed.cues.forEach { cue ->
            if (truncated) return
            append(decoded, buildString {
                appendLine("Cue ${++cues}")
                appendLine("Start: ${timed.startTimeUs} µs; duration: ${timed.durationUs} µs")
                appendLine("Alignment: ${cue.textAlignment}; rows: ${cue.multiRowAlignment}")
                appendLine("Line: ${dimension(cue.line)}; type: ${when (cue.lineType) {
                    Cue.LINE_TYPE_FRACTION -> "fraction"
                    Cue.LINE_TYPE_NUMBER -> "number"
                    else -> "unset"
                }}; anchor: ${anchor(cue.lineAnchor)}")
                appendLine("Position: ${dimension(cue.position)}; anchor: ${anchor(cue.positionAnchor)}; size: ${dimension(cue.size)}")
                appendLine("Text size: ${dimension(cue.textSize)}; type: ${when (cue.textSizeType) {
                    Cue.TEXT_SIZE_TYPE_FRACTIONAL -> "fraction"
                    Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING -> "fraction including padding"
                    Cue.TEXT_SIZE_TYPE_ABSOLUTE -> "absolute"
                    else -> "unset"
                }}")
                appendLine("Direction: ${when (cue.verticalType) {
                    Cue.VERTICAL_TYPE_RL -> "vertical, right to left"
                    Cue.VERTICAL_TYPE_LR -> "vertical, left to right"
                    else -> "horizontal"
                }}; shear: ${cue.shearDegrees}°")
                appendLine("Layer: ${cue.zIndex}")
                if (cue.windowColorSet) appendLine("Window color: ${color(cue.windowColor)}")
                val text = cue.text
                if (text is Spanned) text.getSpans(0, text.length, Any::class.java).forEach { span ->
                    val value = when (span) {
                        is Annotation -> "${span.key}=${span.value}"
                        is StyleSpan -> "style=${span.style}"
                        is ForegroundColorSpan -> "color=${color(span.foregroundColor)}"
                        is BackgroundColorSpan -> "color=${color(span.backgroundColor)}"
                        is RelativeSizeSpan -> "scale=${span.sizeChange}"
                        is AbsoluteSizeSpan -> "size=${span.size}; dip=${span.dip}"
                        is TypefaceSpan -> "family=${span.family}"
                        else -> ""
                    }
                    appendLine("[${text.getSpanStart(span)}, ${text.getSpanEnd(span)}) ${span.javaClass.simpleName} $value")
                }
                appendLine(text ?: "")
                appendLine()
            })
        }
    }

    fun text() = "$source$decoded${if (truncated) TRUNCATED else ""}"

    private fun append(target: StringBuilder, value: String) {
        if (truncated) return
        val available = MAX_SUBTITLE_TEXT_CHARS - source.length - decoded.length - TRUNCATED.length
        val count = minOf(value.length, available)
        target.append(value, 0, count)
        truncated = count < value.length
    }

    private fun textual(format: Format) = format.sampleMimeType in setOf(
        MimeTypes.TEXT_SSA, MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_TTML,
    )
    private fun color(value: Int) = String.format(Locale.ROOT, "#%08X", value)
    private fun dimension(value: Float) = if (value == Cue.DIMEN_UNSET) "unset" else value.toString()
    private fun anchor(value: Int) = when (value) {
        Cue.ANCHOR_TYPE_START -> "start"
        Cue.ANCHOR_TYPE_MIDDLE -> "middle"
        Cue.ANCHOR_TYPE_END -> "end"
        else -> "unset"
    }

    private companion object {
        const val TRUNCATED = "\nMetadata truncated\n"
    }
}
