package com.lunaexplorer.app.ui

import android.graphics.Typeface
import android.text.Annotation
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SsaStyledParser(
    private val delegate: SubtitleParser,
    initialization: List<ByteArray>,
) : SubtitleParser {
    private val styles = linkedMapOf<String, AssStyle>()
    private var eventFormat: List<String> = emptyList()
    private var baseline = AssStyle()

    init {
        initialization.getOrNull(1)?.let { readHeader(String(it, Charsets.UTF_8)) }
        initialization.firstOrNull()?.let { eventFormat = fields(String(it, Charsets.UTF_8)) }
    }

    override fun getCueReplacementBehavior(): Int = delegate.cueReplacementBehavior
    override fun reset() = delegate.reset()

    override fun parse(data: ByteArray, offset: Int, length: Int, outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>) {
        val source = String(normalizeSsaSections(data.copyOfRange(offset, offset + length)), Charsets.UTF_8)
        if (source.contains("[Script Info]", ignoreCase = true)) readHeader(source)
        val styled = mutableMapOf<String, StyledEvent>()
        val rewritten = source.lineSequence().joinToString("\n") { line ->
            when {
                line.startsWith("Format:") && isEventFormat(fields(line)) -> {
                    eventFormat = fields(line)
                    line
                }
                line.startsWith("Dialogue:") && eventFormat.isNotEmpty() -> {
                    val values = line.substringAfter(':').split(',', limit = eventFormat.size).toMutableList()
                    val textIndex = eventFormat.indexOf("text")
                    if (values.size != eventFormat.size || textIndex < 0) return@joinToString line
                    val styleIndex = eventFormat.indexOf("style")
                    val style = values.getOrNull(styleIndex)?.trim()?.let(styles::get) ?: baseline
                    val raw = values[textIndex]
                    val marker = "luna-ass-${styled.size}"
                    styled[marker] = styledEvent(raw, style)
                    // Media3 supplies timing and placement; markers keep identical dialogue lines distinct.
                    values[textIndex] = overrides.findAll(raw).joinToString("") { it.value } + marker
                    "Dialogue:" + values.joinToString(",")
                }
                else -> line
            }
        }.toByteArray()
        delegate.parse(rewritten, 0, rewritten.size, outputOptions) { timed ->
            val cues = timed.cues.map { cue ->
                val event = styled[cue.text?.toString()] ?: return@map cue
                val text = SpannableStringBuilder(event.text)
                val original = cue.text as? Spanned
                if (text.isNotEmpty() && original != null) {
                    original.getSpans(0, original.length, BackgroundColorSpan::class.java).forEach { span ->
                        text.setSpan(BackgroundColorSpan(span.backgroundColor), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                val builder = cue.buildUpon().setText(text)
                if (event.vertical) {
                    builder.setVerticalType(Cue.VERTICAL_TYPE_RL)
                    if (cue.line != Cue.DIMEN_UNSET && cue.position != Cue.DIMEN_UNSET) {
                        builder.setPosition(cue.line).setLine(1f - cue.position, Cue.LINE_TYPE_FRACTION)
                            .setPositionAnchor(cue.lineAnchor).setLineAnchor(cue.positionAnchor)
                    }
                }
                builder.build()
            }
            output.accept(CuesWithTiming(cues, timed.startTimeUs, timed.durationUs))
        }
    }

    private fun readHeader(source: String) {
        styles.clear()
        var section = ""
        var styleFormat = emptyList<String>()
        source.lineSequence().forEach { raw ->
            val line = raw.removePrefix("\uFEFF")
            when {
                line.startsWith('[') -> section = line.trim().lowercase()
                section == "[v4+ styles]" && line.startsWith("Format:") -> styleFormat = fields(line)
                section == "[v4+ styles]" && line.startsWith("Style:") -> {
                    val values = line.substringAfter(':').split(',').map(String::trim)
                    if (values.size != styleFormat.size) return@forEach
                    fun value(name: String) = values.getOrNull(styleFormat.indexOf(name))
                    val name = value("name") ?: return@forEach
                    styles[name] = AssStyle(
                        font = value("fontname").orEmpty(),
                        size = value("fontsize")?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: 0f,
                        color = value("primarycolour")?.let(::assColor),
                        bold = value("bold")?.toIntOrNull()?.let { it != 0 } ?: false,
                        italic = value("italic")?.toIntOrNull()?.let { it != 0 } ?: false,
                        underline = value("underline")?.toIntOrNull()?.let { it != 0 } ?: false,
                        strike = value("strikeout")?.toIntOrNull()?.let { it != 0 } ?: false,
                        alignment = value("alignment")?.toIntOrNull() ?: 2,
                    )
                }
                section == "[events]" && line.startsWith("Format:") -> eventFormat = fields(line)
            }
        }
        val dialogue = styles.values.filter { it.alignment == 2 && !it.bold && !it.italic && it.size > 0f }
        val sizes = dialogue.groupingBy { it.size }.eachCount()
        val commonSize = sizes.maxByOrNull { it.value }?.key
        baseline = dialogue.firstOrNull { it.size == commonSize }
            ?: styles["Default"] ?: styles.values.firstOrNull() ?: AssStyle()
    }

    private fun styledEvent(raw: String, original: AssStyle): StyledEvent {
        val text = SpannableStringBuilder()
        var current = original
        var explicitColor = false
        var drawing = false
        var vertical: Boolean? = null
        fun append(value: String) {
            if (drawing) return
            val start = text.length
            text.append(value.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", "\u00A0"))
            if (start == text.length) return
            if (vertical == null) vertical = current.font.startsWith('@')
            fun span(value: Any) = text.setSpan(value, start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val style = (if (current.bold) Typeface.BOLD else 0) or (if (current.italic) Typeface.ITALIC else 0)
            if (style != 0) span(StyleSpan(style))
            if (current.underline) span(UnderlineSpan())
            if (current.strike) span(StrikethroughSpan())
            if (current.font.isNotEmpty()) span(TypefaceSpan(current.font.removePrefix("@")))
            if (current.size > 0f && original.size > 0f && current.size != original.size) {
                span(RelativeSizeSpan((current.size / original.size).coerceIn(.01f, 100f)))
            }
            current.color?.let { color ->
                span(ForegroundColorSpan(color))
                span(Annotation("luna.subtitle.colorOverride", (explicitColor || color != baseline.color).toString()))
            }
        }
        var cursor = 0
        overrides.findAll(raw).forEach { block ->
            append(raw.substring(cursor, block.range.first))
            overrideCommands(block.value).forEach { command ->
                val tag = command.takeWhile { !it.isDigit() && it != '-' && it != '+' && it != '&' }.trim()
                val number = command.drop(tag.length).trim()
                when {
                    command.startsWith("fn") -> {
                        current = current.copy(font = command.drop(2).ifEmpty { original.font })
                    }
                    command.startsWith("r") -> {
                        current = styles[command.drop(1)] ?: original
                        explicitColor = false
                    }
                    tag == "b" -> if (number.isEmpty()) current = current.copy(bold = original.bold)
                        else number.toIntOrNull()?.let { current = current.copy(bold = it == 1 || it == -1 || it >= 600) }
                    tag == "i" -> if (number.isEmpty()) current = current.copy(italic = original.italic)
                        else number.toIntOrNull()?.let { current = current.copy(italic = it != 0) }
                    tag == "u" -> if (number.isEmpty()) current = current.copy(underline = original.underline)
                        else number.toIntOrNull()?.let { current = current.copy(underline = it != 0) }
                    tag == "s" -> if (number.isEmpty()) current = current.copy(strike = original.strike)
                        else number.toIntOrNull()?.let { current = current.copy(strike = it != 0) }
                    tag == "fs" -> if (number.isEmpty()) current = current.copy(size = original.size)
                        else number.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }?.let { current = current.copy(size = it) }
                    tag == "c" || command.startsWith("1c") -> {
                        val value = command.drop(if (command.startsWith("1c")) 2 else 1)
                        val color = if (value.isEmpty()) original.color else assColor(value)
                        if (color != null) {
                            current = current.copy(color = (color and 0x00ffffff) or ((current.color ?: color) and -0x1000000))
                            explicitColor = value.isNotEmpty()
                        }
                    }
                    command.startsWith("alpha") || command.startsWith("1a") -> {
                        val value = command.drop(if (command.startsWith("alpha")) 5 else 2)
                        val alpha = if (value.isEmpty()) 255 - ((original.color ?: -1) ushr 24)
                            else value.removePrefix("&H").removeSuffix("&").toIntOrNull(16)
                        if (alpha != null && alpha in 0..255) {
                            current = current.copy(color = ((current.color ?: -1) and 0x00ffffff) or ((255 - alpha) shl 24))
                            explicitColor = explicitColor || value.isNotEmpty()
                        }
                    }
                    tag == "p" -> number.toIntOrNull()?.let { drawing = it != 0 }
                }
            }
            cursor = block.range.last + 1
        }
        append(raw.substring(cursor))
        if (text.isNotEmpty() && original.size > 0f && baseline.size > 0f) {
            text.setSpan(Annotation("luna.subtitle.relativeSize", (original.size / baseline.size).toString()),
                0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return StyledEvent(text, vertical == true)
    }

    private data class StyledEvent(val text: CharSequence, val vertical: Boolean)
    private data class AssStyle(
        val font: String = "", val size: Float = 0f, val color: Int? = null,
        val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false,
        val strike: Boolean = false, val alignment: Int = 2,
    )
}

private fun fields(line: String): List<String> = line.substringAfter(':').split(',').map { it.trim().lowercase() }
private fun isEventFormat(fields: List<String>): Boolean = fields.containsAll(listOf("start", "end", "text"))

private fun assColor(value: String): Int? {
    val hex = value.trim().removeSuffix("&")
    val color = if (hex.startsWith("&H", ignoreCase = true)) hex.drop(2).toLongOrNull(16) else hex.toLongOrNull()
    if (color == null || color !in 0..0xffffffffL) return null
    return ((255 - ((color shr 24) and 255)).toInt() shl 24) or
        ((color and 255).toInt() shl 16) or (color.toInt() and 0x00ff00) or ((color shr 16).toInt() and 255)
}

private fun overrideCommands(block: String): List<String> {
    val commands = mutableListOf<String>()
    var start = -1
    var depth = 0
    block.forEachIndexed { index, character ->
        when {
            character == '(' -> depth++
            character == ')' -> depth = (depth - 1).coerceAtLeast(0)
            character == '\\' && depth == 0 -> {
                if (start >= 0) commands.add(block.substring(start, index))
                start = index + 1
            }
        }
    }
    if (start >= 0) commands.add(block.substring(start, block.length - 1))
    return commands
}

private val overrides = Regex("\\{[^}]*\\}")
