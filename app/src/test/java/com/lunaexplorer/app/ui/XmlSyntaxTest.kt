package com.lunaexplorer.app.ui

import dev.snipme.highlights.model.SyntaxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlSyntaxTest {
    // Distinct values, so a token's role can be read from its color.
    private val theme = SyntaxTheme(key = "test", code = 0x000001, keyword = 0x000002, string = 0x000003, literal = 0x000004,
        comment = 0x000005, metadata = 0x000006, multilineComment = 0x000007, punctuation = 0x000008, mark = 0x000009)
    private val name = theme.keyword or 0xFF000000.toInt()
    private val attribute = theme.metadata or 0xFF000000.toInt()
    private val value = theme.string or 0xFF000000.toInt()
    private val comment = theme.comment or 0xFF000000.toInt()
    private val punctuation = theme.punctuation or 0xFF000000.toInt()
    private val entity = theme.literal or 0xFF000000.toInt()

    private fun colors(text: String, syntax: XmlSyntax = XmlSyntax.XML): (String) -> Int? {
        val spans = syntax.highlight(text, theme)
        return { token -> spans.colorAt(text.indexOf(token).also { assertTrue("$token in $text", it >= 0) }) }
    }

    @Test fun `each markup construct receives its theme role`() {
        val text = """
            <?xml version="1.0" encoding='UTF-8'?>
            <!DOCTYPE note SYSTEM "note.dtd">
            <note id="7" lang='en' xmlns:x="urn:x">
              <!-- remark -->
              <to>Tove &amp; Jani &#169;</to>
              <x:body><![CDATA[ raw <text> ]]></x:body>
              <empty/>
            </note>
        """.trimIndent()
        val at = colors(text)
        assertEquals(punctuation, at("<?"))
        assertEquals(name, at("xml version"))
        assertEquals(attribute, at("version"))
        assertEquals(value, at("\"1.0\""))
        assertEquals(value, at("'UTF-8'"))
        assertEquals(punctuation, at("?>"))
        assertEquals(name, at("DOCTYPE"))
        assertNull(at("SYSTEM"))
        assertEquals(value, at("\"note.dtd\""))
        assertEquals(name, at("note id"))
        assertEquals(punctuation, at("=\"7\""))
        assertEquals(attribute, at("xmlns:x"))
        assertEquals(comment, at("remark"))
        assertNull(at("Tove"))
        assertEquals(entity, at("&amp;"))
        assertEquals(entity, at("&#169;"))
        assertEquals(punctuation, at("</to>"))
        assertEquals(name, at("to>Tove"))
        assertEquals(name, at("x:body"))
        assertEquals(punctuation, at("<![CDATA["))
        assertEquals(value, at(" raw <text> "))
        assertEquals(punctuation, at("]]>"))
        assertEquals(punctuation, at("/>"))
        assertEquals(name, at("note>"))
    }

    @Test fun `malformed input never throws and stays within the text`() {
        val cases = listOf("", "<", "<<<", "<a", "<a href=", "<a href=\"unterminated", "<!--", "<![CDATA[", "<?", "<!",
            "<!DOCTYPE [", "&", "&amp", "&;", "<a <b>", "</", "<1>", "<a>&#x1F600;</a>", "🌓<🌓>",
            "<a b='x' c=\"y\"/><!-- unclosed", "<script>if (a<b) {}</script>", "]]>-->?>")
        cases.forEach { text ->
            listOf(XmlSyntax.XML, XmlSyntax.HTML).forEach { syntax ->
                val spans = syntax.highlight(text, theme)
                for (offset in text.indices) spans.colorAt(offset)
                assertNull(spans.colorAt(text.length))
            }
        }
    }

    @Test fun `an unclosed tag ends at the next tag and a lone bracket is plain text`() {
        val text = "<a href=\"x\" <b>1 < 2</b>"
        val at = colors(text)
        assertEquals(name, at("a href"))
        assertEquals(value, at("\"x\""))
        assertEquals(name, at("b>1"))
        assertNull(at("1 < 2"))
        assertNull(at("< 2"))
        assertEquals(punctuation, at("</b>"))
    }

    @Test fun `HTML keeps script bodies plain and accepts unquoted or bare attributes`() {
        val text = "<input disabled value=plain><script type=\"module\">let a = \"<b>\";</script><p>ok</p>"
        val at = colors(text, XmlSyntax.HTML)
        assertEquals(attribute, at("disabled"))
        assertEquals(value, at("plain"))
        assertNull(at("let a"))
        assertNull(at("<b>"))
        assertEquals(punctuation, at("</script>"))
        assertEquals(name, at("p>ok"))
        // The same body under XML rules is markup, since XML has no raw text elements.
        assertEquals(name, colors(text)("b>\";"))
    }

    @Test fun `a large malformed prefix does not hide valid trailing markup`() {
        val prefix = "<".repeat(200_000) + "&".repeat(200_000) + "<a ".repeat(100_000) + "\"".repeat(200_000)
        val text = prefix + "<tail value=\"done\"/>"
        val spans = XmlSyntax.XML.highlight(text, theme)
        assertEquals(name, spans.colorAt(prefix.length + 1))
        assertEquals(attribute, spans.colorAt(prefix.length + 6))
        assertEquals(value, spans.colorAt(text.indexOf("done")))
        assertNull(spans.colorAt(text.length))
    }

    @Test fun `a cancelled scan stops early`() {
        var calls = 0
        val cancel = RuntimeException("cancelled")
        try {
            XmlSyntax.XML.highlight("<a/>".repeat(50_000), theme) { if (++calls == 3) throw cancel }
            throw AssertionError("The scan ignored cancellation")
        } catch (thrown: RuntimeException) {
            assertEquals(cancel, thrown)
        }
        assertEquals(3, calls)
    }
}
