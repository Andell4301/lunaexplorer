package com.lunaexplorer.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDocumentTest {
    private fun rows(document: TextDocument) = (0 until document.rowCount).map(document::row)

    @Test fun `rows follow every line ending style and keep the original text`() {
        val text = "one\r\ntwo\nthree\r\r\n\nfour"
        val document = TextDocument.prepare(text)
        assertEquals(listOf("one", "two", "three", "", "", "four"), rows(document))
        assertEquals(text, document.text)
        assertEquals(5, document.columns)
        assertEquals(listOf(""), rows(TextDocument.prepare("")))
        assertEquals(listOf("a", ""), rows(TextDocument.prepare("a\n")))
    }

    @Test fun `long lines split into bounded rows without breaking surrogate pairs`() {
        val line = "x".repeat(TextDocument.ROW_CHARS - 1) + "🌓" + "y".repeat(10)
        val document = TextDocument.prepare("$line\nshort")
        val rows = rows(document)
        assertEquals(listOf(TextDocument.ROW_CHARS - 1, 12, 5), rows.map { it.length })
        assertTrue(rows[1].startsWith("🌓"))
        assertEquals(line, rows[0] + rows[1])
        // Wide characters and tabs widen the estimate used for horizontal scrolling.
        assertEquals(2 + 8 + 1, TextDocument.prepare("é\ta").columns)
    }

    @Test fun `row slicing clips global spans to the row and rebases their offsets`() {
        val text = "ab\ncdefg\nhi"
        val spans = ColorSpans.Builder().apply {
            add(1, 5, 0xFF0000FF.toInt()) // "b" and the break, through "cd"
            add(5, 6, 0xFF00FF00.toInt()) // "e"
            add(8, 11, 0xFFFF0000.toInt()) // the break, then "hi"
        }.build()
        val document = TextDocument.prepare(text)
        val first = document.row(0, spans)
        assertEquals("ab", first.text)
        assertEquals(listOf(Triple(1, 2, Color(0xFF0000FF))), first.spanStyles.map { Triple(it.start, it.end, it.item.color) })
        val second = document.row(1, spans)
        assertEquals("cdefg", second.text)
        assertEquals(listOf(Triple(0, 2, Color(0xFF0000FF)), Triple(2, 3, Color(0xFF00FF00))),
            second.spanStyles.map { Triple(it.start, it.end, it.item.color) })
        val third = document.row(2, spans)
        assertEquals(listOf(Triple(0, 2, Color(0xFFFF0000))), third.spanStyles.map { Triple(it.start, it.end, it.item.color) })
        assertTrue(document.row(1, null).spanStyles.isEmpty())
        assertEquals(document.row(1), document.row(1, null).text)

        assertNull(spans.colorAt(0))
        assertEquals(0xFF0000FF.toInt(), spans.colorAt(4))
        assertEquals(0xFF00FF00.toInt(), spans.colorAt(5))
        assertNull(spans.colorAt(7))
        assertEquals(0xFFFF0000.toInt(), spans.colorAt(10))
        assertNull(spans.colorAt(11))
    }

    @Test fun `touching spans of one color merge and empty ranges are dropped`() {
        val spans = ColorSpans.Builder().apply {
            add(0, 2, 1)
            add(2, 4, 1)
            add(4, 4, 1)
            add(4, 6, 2)
        }.build()
        assertEquals(2, spans.size)
        val annotated = spans.annotate("abcdefg", 0, 7)
        assertEquals(listOf(0 to 4, 4 to 6), annotated.spanStyles.map { it.start to it.end })
    }

    @Test fun `editing limits count characters and the longest line`() {
        val atLimit = ("x".repeat(1023) + "\n").repeat(MAX_EDITABLE_CHARS / 1024)
        assertEquals(MAX_EDITABLE_CHARS, atLimit.length)
        assertFalse(tooLargeToEdit(atLimit))
        assertTrue(tooLargeToEdit(atLimit + "x"))
        val longest = "y".repeat(MAX_EDITABLE_LINE_CHARS)
        assertFalse(tooLargeToEdit("$longest\n$longest\r$longest"))
        assertTrue(tooLargeToEdit("short\n${longest}y"))
    }

    @Test fun `offsets map to rows across line endings and long-line segments`() {
        val text = "ab\r\ncd\n" + "x".repeat(TextDocument.ROW_CHARS + 5)
        val document = TextDocument.prepare(text)
        assertEquals(listOf(0, 4, 7, 7 + TextDocument.ROW_CHARS), (0 until document.rowCount).map(document::rowStart))
        assertEquals(0, document.rowAt(0))
        assertEquals(0, document.rowAt(1))
        assertEquals(1, document.rowAt(4))
        assertEquals(1, document.rowAt(6))
        assertEquals(2, document.rowAt(7))
        assertEquals(2, document.rowAt(6 + TextDocument.ROW_CHARS))
        assertEquals(3, document.rowAt(7 + TextDocument.ROW_CHARS))
        assertEquals(3, document.rowAt(text.length))
        assertEquals(5, document.offsetInRow(3, text.length))
    }

    @Test fun `an offset inside a CRLF maps to the end of the row before it`() {
        val document = TextDocument.prepare("ab\r\ncd")
        assertEquals(0, document.rowAt(2))
        assertEquals(0, document.rowAt(3))
        assertEquals(2, document.offsetInRow(0, 2))
        assertEquals(2, document.offsetInRow(0, 3))
        assertEquals(0, document.offsetInRow(1, 4))
    }

    @Test fun `a match straddling two rows of one line is highlighted in both and the current match differs`() {
        val line = "x".repeat(TextDocument.ROW_CHARS - 2) + "needle needle"
        val document = TextDocument.prepare(line)
        val matches = findMatches(line, "needle", caseSensitive = true, pattern = null)
        val spans = ColorSpans.Builder().apply { add(0, 4, 0xFF0000FF.toInt()) }.build()
        val match = Color(0xFFFFFF00)
        val current = Color(0xFF00FF00)
        fun backgrounds(row: Int) = document.row(row, spans, matches, 0, match, current).spanStyles
            .filter { it.item.background != Color.Unspecified }.map { Triple(it.start, it.end, it.item.background) }

        assertEquals(listOf(Triple(TextDocument.ROW_CHARS - 2, TextDocument.ROW_CHARS, current)), backgrounds(0))
        assertEquals(listOf(Triple(0, 4, current), Triple(5, 11, match)), backgrounds(1))
        val colors = document.row(0, spans, matches, 0, match, current).spanStyles.filter { it.item.color != Color.Unspecified }
        assertEquals(listOf(0 to 4), colors.map { it.start to it.end })
        assertEquals(listOf(Triple(0, 4, match), Triple(5, 11, current)),
            document.row(1, null, matches, 1, match, current).spanStyles.map { Triple(it.start, it.end, it.item.background) })
        assertTrue(document.row(1, spans, null, -1, match, current).spanStyles.isEmpty())
    }

    @Test fun `highlighting is off above each mode's limit and for a language without a row tokenizer`() {
        assertFalse(highlightingOff(CodeLanguage.PLAIN_TEXT, Int.MAX_VALUE, rows = false))
        assertFalse(highlightingOff(CodeLanguage.PLAIN_TEXT, Int.MAX_VALUE, rows = true))
        assertFalse(highlightingOff(CodeLanguage.KOTLIN, MAX_CODE_HIGHLIGHT_CHARS, rows = false))
        assertTrue(highlightingOff(CodeLanguage.KOTLIN, MAX_CODE_HIGHLIGHT_CHARS + 1, rows = false))
        assertTrue(highlightingOff(CodeLanguage.KOTLIN, 1, rows = true))
        assertFalse(highlightingOff(CodeLanguage.XML, MAX_LAZY_HIGHLIGHT_CHARS, rows = true))
        assertTrue(highlightingOff(CodeLanguage.XML, MAX_LAZY_HIGHLIGHT_CHARS + 1, rows = true))
    }
}
