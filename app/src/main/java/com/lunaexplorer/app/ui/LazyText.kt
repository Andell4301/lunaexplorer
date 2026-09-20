package com.lunaexplorer.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Read-only text split into rows of at most [ROW_CHARS] so a LazyColumn lays out only the viewport.
 * Rows never split a surrogate pair and are slices of the original [text].
 */
internal class TextDocument private constructor(
    val text: String,
    private val starts: IntArray,
    private val ends: IntArray,
    /** Widest row in estimated monospace cells: wide characters count two, tabs eight. */
    val columns: Int,
) {
    val rowCount: Int get() = starts.size
    fun row(index: Int): String = text.substring(starts[index], ends[index])

    fun row(index: Int, spans: ColorSpans?): AnnotatedString =
        spans?.annotate(text, starts[index], ends[index]) ?: AnnotatedString(row(index))

    /** [matches] get a background on top of [spans]; one that crosses into the next row is clipped to this one. */
    fun row(index: Int, spans: ColorSpans?, matches: TextMatches?, current: Int, match: Color, currentMatch: Color): AnnotatedString {
        val from = starts[index]
        val to = ends[index]
        var found = matches?.firstEndingAfter(from) ?: return row(index, spans)
        if (found >= matches.size || matches.start(found) >= to) return row(index, spans)
        val builder = AnnotatedString.Builder(text.substring(from, to))
        spans?.addTo(builder, from, to)
        while (found < matches.size && matches.start(found) < to) {
            val start = maxOf(matches.start(found), from) - from
            val end = minOf(matches.end(found), to) - from
            if (start < end) builder.addStyle(SpanStyle(background = if (found == current) currentMatch else match), start, end)
            found++
        }
        return builder.toAnnotatedString()
    }

    fun rowStart(index: Int): Int = starts[index]

    /** The row holding [offset]; an offset inside a line break belongs to the row before it. */
    fun rowAt(offset: Int): Int {
        var low = 0
        var high = starts.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (starts[middle] <= offset) low = middle + 1 else high = middle
        }
        return (low - 1).coerceAtLeast(0)
    }

    fun offsetInRow(index: Int, offset: Int): Int = (offset - starts[index]).coerceIn(0, ends[index] - starts[index])

    companion object {
        const val ROW_CHARS = 1024

        fun prepare(text: String, ensureActive: () -> Unit = {}): TextDocument {
            val starts = IntList()
            val ends = IntList()
            var columns = 0
            val length = text.length
            var lineStart = 0
            while (true) {
                var lineEnd = lineStart
                while (lineEnd < length && text[lineEnd] != '\n' && text[lineEnd] != '\r') lineEnd++
                if (lineEnd == lineStart) {
                    starts.add(lineStart)
                    ends.add(lineEnd)
                }
                var start = lineStart
                while (start < lineEnd) {
                    var end = minOf(start + ROW_CHARS, lineEnd)
                    if (end < lineEnd && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                    starts.add(start)
                    ends.add(end)
                    var width = 0
                    for (offset in start until end) {
                        val char = text[offset]
                        width += if (char == '\t') 8 else if (char.code > 127) 2 else 1
                    }
                    if (width > columns) columns = width
                    start = end
                }
                if (lineEnd >= length) break
                lineStart = if (text[lineEnd] == '\r' && lineEnd + 1 < length && text[lineEnd + 1] == '\n') lineEnd + 2 else lineEnd + 1
                ensureActive()
            }
            return TextDocument(text, starts.toArray(), ends.toArray(), columns)
        }
    }
}

/** Sorted, non-overlapping colored ranges over one text, built once and sliced per row. */
internal class ColorSpans private constructor(private val starts: IntArray, private val ends: IntArray, private val colors: IntArray) {
    val size: Int get() = starts.size

    fun colorAt(offset: Int): Int? {
        val index = firstEndingAfter(offset)
        return if (index < size && starts[index] <= offset) colors[index] else null
    }

    /** Colors `text[from, to)`; span offsets in the result are relative to [from]. */
    fun annotate(text: String, from: Int, to: Int): AnnotatedString =
        AnnotatedString.Builder(text.substring(from, to)).also { addTo(it, from, to) }.toAnnotatedString()

    fun addTo(builder: AnnotatedString.Builder, from: Int, to: Int) {
        var index = firstEndingAfter(from)
        while (index < size && starts[index] < to) {
            val start = maxOf(starts[index], from) - from
            val end = minOf(ends[index], to) - from
            if (start < end) builder.addStyle(SpanStyle(color = Color(colors[index])), start, end)
            index++
        }
    }

    private fun firstEndingAfter(offset: Int): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ends[middle] <= offset) low = middle + 1 else high = middle
        }
        return low
    }

    class Builder {
        private val starts = IntList()
        private val ends = IntList()
        private val colors = IntList()

        /** Ranges must arrive in text order. Touching ranges of one color merge into a single span. */
        fun add(start: Int, end: Int, color: Int) {
            if (start >= end) return
            val last = starts.size - 1
            if (last >= 0 && ends[last] == start && colors[last] == color) {
                ends[last] = end
            } else {
                starts.add(start)
                ends.add(end)
                colors.add(color)
            }
        }

        fun build() = ColorSpans(starts.toArray(), ends.toArray(), colors.toArray())
    }
}

internal class IntList {
    private var data = IntArray(256)
    var size = 0
        private set

    operator fun get(index: Int): Int = data[index]
    operator fun set(index: Int, value: Int) { data[index] = value }
    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }
    fun toArray(): IntArray = data.copyOf(size)
}

/** Whole-document tokenizing above this size would delay the first rows for too long. */
internal const val MAX_LAZY_HIGHLIGHT_CHARS = 4 * 1024 * 1024

/** Only languages with Luna's own tokenizer are colored; Highlights offers no per-row slicing. */
@Composable
internal fun rememberLazyTextSpans(document: TextDocument, language: CodeLanguage): ColorSpans? {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val scheme = LocalSyntaxScheme.current
    var spans by remember(document, language, dark, scheme) { mutableStateOf<ColorSpans?>(null) }
    LaunchedEffect(document, language, dark, scheme) {
        val syntax = language.custom ?: return@LaunchedEffect
        if (document.text.length > MAX_LAZY_HIGHLIGHT_CHARS) return@LaunchedEffect
        spans = withContext(Dispatchers.Default) {
            syntax.highlight(document.text, scheme.theme(dark)) { ensureActive() }
        }
    }
    return spans
}

internal fun highlightingOff(language: CodeLanguage, length: Int, rows: Boolean): Boolean = when {
    language == CodeLanguage.PLAIN_TEXT -> false
    rows -> language.custom == null || length > MAX_LAZY_HIGHLIGHT_CHARS
    else -> length > MAX_CODE_HIGHLIGHT_CHARS
}

/** Reads [reveal] in its own scope, so a new request does not recompose the caller. */
@Composable
internal fun RevealEffect(reveal: () -> TextReveal?, scroll: suspend (TextReveal) -> Unit) {
    val target = reveal()
    LaunchedEffect(target) { if (target != null) scroll(target) }
}

/** Without wrapping, all rows scroll horizontally as one block. [rowText] runs only for composed rows. */
@Composable
internal fun LazyTextRows(
    document: TextDocument,
    wrap: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    rowsTag: String = "textRows",
    rowTag: String = "textRow",
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    scroll: LazyListState = rememberLazyListState(),
    horizontal: ScrollState = rememberScrollState(),
    reveal: () -> TextReveal? = { null },
    onRevealed: (TextReveal) -> Unit = {},
    rowText: (Int) -> AnnotatedString,
) {
    val measurer = rememberTextMeasurer()
    val cell = measurer.measure("M", style).size.width
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    BoxWithConstraints(modifier.fastScroll(scroll)) {
        val width = if (wrap) maxWidth else maxOf(maxWidth,
            with(density) { (cell.toFloat() * document.columns).toDp() } + 32.dp)
        val viewportWidth = constraints.maxWidth
        RevealEffect(reveal) { target ->
            val row = document.rowAt(target.offset)
            val local = document.offsetInRow(row, target.offset)
            val startPadding = with(density) { contentPadding.calculateStartPadding(direction).toPx() }
            val endPadding = with(density) { contentPadding.calculateEndPadding(direction).toPx() }
            val layout = measurer.measure(rowText(row), style, softWrap = wrap, constraints = Constraints(
                maxWidth = if (wrap) (viewportWidth - startPadding - endPadding).roundToInt().coerceAtLeast(0) else Constraints.Infinity))
            val line = layout.getLineForOffset(local)
            val top = layout.getLineTop(line)
            val info = scroll.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == row }
            // A wrapped row can be taller than the viewport, so the match's own line is what has to show.
            if (item == null || item.offset + top < info.viewportStartOffset ||
                item.offset + layout.getLineBottom(line) > info.viewportEndOffset) {
                scroll.scrollToItem(row, (top - info.viewportSize.height / 4f).roundToInt())
            }
            if (!wrap) {
                val x = layout.getHorizontalPosition(local, true) + startPadding
                val margin = with(density) { 48.dp.toPx() }
                if (x < horizontal.value + margin || x > horizontal.value + horizontal.viewportSize - margin) {
                    horizontal.scrollTo((x - horizontal.viewportSize / 3f).roundToInt())
                }
            }
            onRevealed(target)
        }
        LazyColumn(state = scroll,
            modifier = Modifier.fillMaxHeight()
                .then(if (wrap) Modifier else Modifier.fastHorizontalScroll(horizontal))
                .width(width).testTag(rowsTag),
            contentPadding = contentPadding) {
            items(document.rowCount) { index ->
                Text(rowText(index), Modifier.testTag(rowTag), style = style, softWrap = wrap)
            }
        }
    }
}
