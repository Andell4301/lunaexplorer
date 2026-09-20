package com.lunaexplorer.app.ui

import dev.snipme.highlights.model.SyntaxTheme

/**
 * Single-pass XML/HTML tokenizer. Every step consumes at least one character, so malformed input ends
 * at the text's end instead of failing. [SyntaxTheme] mapping: element and declaration names `keyword`,
 * attribute names `metadata`, values and CDATA content `string`, comments `comment`, brackets
 * `punctuation`, entities `literal`.
 */
internal class XmlSyntax private constructor(private val html: Boolean) {
    fun highlight(text: String, theme: SyntaxTheme, ensureActive: () -> Unit = {}): ColorSpans =
        Scan(text, html, theme, ensureActive).run()

    private class Scan(private val text: String, private val html: Boolean, theme: SyntaxTheme, private val ensureActive: () -> Unit) {
        private val out = ColorSpans.Builder()
        private val length = text.length
        private var pos = 0
        private var steps = 0
        private val name = theme.keyword.opaque()
        private val attribute = theme.metadata.opaque()
        private val value = theme.string.opaque()
        private val comment = theme.comment.opaque()
        private val punctuation = theme.punctuation.opaque()
        private val entity = theme.literal.opaque()

        fun run(): ColorSpans {
            while (pos < length) {
                if (++steps and 0x3FF == 0) ensureActive()
                when (text[pos]) {
                    '<' -> markup()
                    '&' -> entity()
                    else -> content()
                }
            }
            return out.build()
        }

        private fun content() {
            var end = pos + 1
            while (end < length && text[end] != '<' && text[end] != '&') end++
            pos = end
        }

        private fun entity() {
            val start = pos
            var end = start + 1
            if (end < length && text[end] == '#') end++
            val nameStart = end
            while (end < length && end - start < 64 && text[end].isLetterOrDigit()) end++
            if (end > nameStart && end < length && text[end] == ';') {
                span(start, end + 1, entity)
                pos = end + 1
            } else pos = start + 1
        }

        private fun markup() {
            val start = pos
            when {
                text.startsWith("<!--", start) -> {
                    val close = text.indexOf("-->", start + 4)
                    val end = if (close < 0) length else close + 3
                    span(start, end, comment)
                    pos = end
                }
                text.startsWith("<![CDATA[", start) -> {
                    span(start, start + 9, punctuation)
                    val close = text.indexOf("]]>", start + 9)
                    if (close < 0) {
                        span(start + 9, length, value)
                        pos = length
                    } else {
                        span(start + 9, close, value)
                        span(close, close + 3, punctuation)
                        pos = close + 3
                    }
                }
                text.startsWith("<?", start) -> {
                    span(start, start + 2, punctuation)
                    pos = start + 2
                    val nameEnd = nameEnd(pos)
                    span(pos, nameEnd, name)
                    pos = nameEnd
                    attributes()
                }
                text.startsWith("<!", start) -> declaration()
                start + 1 < length && text[start + 1] == '/' -> {
                    span(start, start + 2, punctuation)
                    pos = start + 2
                    val nameEnd = nameEnd(pos)
                    span(pos, nameEnd, name)
                    pos = nameEnd
                    attributes()
                }
                start + 1 < length && isNameStart(text[start + 1]) -> {
                    span(start, start + 1, punctuation)
                    pos = start + 1
                    val nameEnd = nameEnd(pos)
                    span(pos, nameEnd, name)
                    val element = text.substring(pos, nameEnd)
                    pos = nameEnd
                    val closed = attributes()
                    if (html && !closed && (element.equals("script", true) || element.equals("style", true))) rawText(element)
                }
                else -> pos++ // A lone '<' is character data.
            }
        }

        private fun attributes(): Boolean {
            var expectValue = false
            while (pos < length) {
                val char = text[pos]
                when {
                    char.isWhitespace() -> pos++
                    char == '>' -> {
                        span(pos, pos + 1, punctuation)
                        pos++
                        return false
                    }
                    (char == '/' || char == '?') && text.startsWith(">", pos + 1) -> {
                        span(pos, pos + 2, punctuation)
                        pos += 2
                        return true
                    }
                    char == '<' -> return true // Unclosed tag: the next markup starts fresh.
                    char == '=' -> {
                        span(pos, pos + 1, punctuation)
                        pos++
                        expectValue = true
                    }
                    char == '"' || char == '\'' -> {
                        quoted(char)
                        expectValue = false
                    }
                    expectValue -> {
                        var end = pos + 1
                        while (end < length && !text[end].isWhitespace() && text[end] != '>' && text[end] != '<' &&
                            !text.startsWith("/>", end) && !text.startsWith("?>", end)) end++
                        span(pos, end, value)
                        pos = end
                        expectValue = false
                    }
                    isNameStart(char) -> {
                        val end = nameEnd(pos)
                        span(pos, end, attribute)
                        pos = end
                    }
                    else -> pos++
                }
            }
            return true
        }

        /** XML forbids '<' inside values, so in XML an unterminated quote ends at the next tag. */
        private fun quoted(quote: Char) {
            var end = pos + 1
            while (end < length && text[end] != quote && (html || text[end] != '<')) end++
            if (end < length && text[end] == quote) end++
            span(pos, end, value)
            pos = end
        }

        /** `<!DOCTYPE …>` and similar declarations, including an internal subset in brackets. */
        private fun declaration() {
            span(pos, pos + 2, punctuation)
            pos += 2
            val nameEnd = nameEnd(pos)
            span(pos, nameEnd, name)
            pos = nameEnd
            var depth = 0
            while (pos < length) {
                val char = text[pos]
                when {
                    char == '"' || char == '\'' -> quoted(char)
                    char == '[' -> {
                        depth++
                        span(pos, pos + 1, punctuation)
                        pos++
                    }
                    char == ']' -> {
                        if (depth > 0) depth--
                        span(pos, pos + 1, punctuation)
                        pos++
                    }
                    char == '>' -> {
                        span(pos, pos + 1, punctuation)
                        pos++
                        if (depth == 0) return
                    }
                    char == '<' && text.startsWith("<!--", pos) -> markup()
                    char == '<' && depth > 0 && text.startsWith("<!", pos) -> {
                        span(pos, pos + 2, punctuation)
                        pos += 2
                        val end = nameEnd(pos)
                        span(pos, end, name)
                        pos = end
                    }
                    char == '<' -> return // Malformed: a tag interrupts the declaration.
                    else -> pos++
                }
            }
        }

        /** Script and style bodies are not markup in HTML; leave them uncolored up to their end tag. */
        private fun rawText(element: String) {
            val close = text.indexOf("</$element", pos, ignoreCase = true)
            pos = if (close < 0) length else close
        }

        private fun nameEnd(from: Int): Int {
            var end = from
            while (end < length && isNameChar(text[end])) end++
            return end
        }

        private fun span(start: Int, end: Int, color: Int) = out.add(start, end, color)
    }

    companion object {
        val XML = XmlSyntax(html = false)
        val HTML = XmlSyntax(html = true)

        private fun Int.opaque(): Int = this or 0xFF000000.toInt()

        private fun isNameStart(char: Char): Boolean = char.isLetter() || char == '_' || char == ':' || char.code > 127

        private fun isNameChar(char: Char): Boolean = isNameStart(char) || char.isDigit() || char == '-' || char == '.'
    }
}
