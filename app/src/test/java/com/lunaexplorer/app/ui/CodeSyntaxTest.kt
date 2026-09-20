package com.lunaexplorer.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.lunaexplorer.app.model.SyntaxScheme
import org.junit.Assert.*
import org.junit.Test

class CodeSyntaxTest {
    @Test fun `file extensions choose common languages without guessing unknown files`() {
        val files = mapOf(
            "main.rs" to CodeLanguage.RUST,
            "SCRIPT.PY" to CodeLanguage.PYTHON,
            "build.gradle.kts" to CodeLanguage.KOTLIN,
            "main.java" to CodeLanguage.JAVA,
            "main.tsx" to CodeLanguage.TYPESCRIPT,
            "settings.json" to CodeLanguage.JSON,
            ".bashrc" to CodeLanguage.SHELL,
            "Gemfile" to CodeLanguage.RUBY,
            "README" to CodeLanguage.PLAIN_TEXT,
            "page.html" to CodeLanguage.HTML,
            "index.XHTML" to CodeLanguage.HTML,
            "AndroidManifest.xml" to CodeLanguage.XML,
            "icon.svg" to CodeLanguage.XML,
            "Info.plist" to CodeLanguage.XML,
            "feed.rss" to CodeLanguage.XML,
            "literal.py\u200B" to CodeLanguage.PLAIN_TEXT,
        )
        files.forEach { (file, language) -> assertEquals(file, language, CodeLanguage.fromFilename(file)) }
    }

    @Test fun `highlights common language tokens and leaves Unicode content intact`() {
        val snippets = mapOf(
            CodeLanguage.RUST to "fn main() { let title = \"🌓 Luna\"; }",
            CodeLanguage.PYTHON to "def main():\n    return \"🌓 Luna\"",
            CodeLanguage.KOTLIN to "fun main() { val title = \"🌓 Luna\" }",
            CodeLanguage.JAVA to "public class Luna {}",
            CodeLanguage.TYPESCRIPT to "const title: string = \"🌓 Luna\";",
            CodeLanguage.JSON to "{\"title\": \"🌓 Luna\", \"count\": 42}",
            CodeLanguage.SHELL to "echo '🌓 Luna'",
            CodeLanguage.XML to "<title lang=\"en\">🌓 Luna &amp; friends</title>",
            CodeLanguage.HTML to "<p class=note>🌓 Luna<br></p>",
        )
        snippets.forEach { (language, code) ->
            val highlighted = highlightCode(code, language, dark = true)
            assertEquals(code, highlighted.text)
            assertTrue("No colors for $language", highlighted.spanStyles.isNotEmpty())
            assertTrue("First token not highlighted for $language", highlighted.spanStyles.any { it.start == 0 })
            assertTrue(highlighted.spanStyles.all { it.start >= 0 && it.end <= code.length && it.start < it.end })
        }
    }

    @Test fun `the chosen scheme is the one that colors the code`() {
        val code = "fun main() {}"
        SyntaxScheme.entries.forEach { scheme ->
            listOf(true, false).forEach { dark ->
                val keyword = highlightCode(code, CodeLanguage.KOTLIN, dark, scheme).spanStyles.first { it.start == 0 }
                assertEquals("$scheme dark=$dark", Color(scheme.theme(dark).keyword or 0xFF000000.toInt()), keyword.item.color)
            }
        }
        assertNotEquals(SyntaxScheme.GITHUB.theme(true).keyword, SyntaxScheme.DARCULA.theme(true).keyword)
    }

    @Test fun `every scheme stays readable on both of Luna's surfaces`() {
        fun channel(value: Int): Double = (value / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        fun luminance(rgb: Int) = 0.2126 * channel(rgb shr 16 and 255) + 0.7152 * channel(rgb shr 8 and 255) + 0.0722 * channel(rgb and 255)
        fun contrast(a: Int, b: Int) = (maxOf(luminance(a), luminance(b)) + 0.05) / (minOf(luminance(a), luminance(b)) + 0.05)
        SyntaxScheme.entries.forEach { scheme ->
            listOf(true to 0x14181B, false to 0xF4F6F4).forEach { (dark, surface) ->
                val theme = scheme.theme(dark)
                mapOf("keyword" to theme.keyword, "string" to theme.string, "literal" to theme.literal,
                    "metadata" to theme.metadata, "punctuation" to theme.punctuation, "mark" to theme.mark,
                ).forEach { (token, rgb) ->
                    assertTrue("$scheme dark=$dark $token is ${contrast(rgb, surface)}:1", contrast(rgb, surface) >= 4.4)
                }
                listOf(theme.comment, theme.multilineComment).forEach { rgb ->
                    assertTrue("$scheme dark=$dark comment is ${contrast(rgb, surface)}:1", contrast(rgb, surface) >= 2.9)
                }
            }
        }
    }

    @Test fun `color transformation preserves selection offsets and rejects stale text`() {
        val code = "let moon = \"🌓\";\r\n"
        val highlighted = highlightCode(code, CodeLanguage.RUST, dark = false)
        val transformation = CodeHighlightTransformation(highlighted)
        val result = transformation.filter(AnnotatedString(code))
        assertEquals(highlighted, result.text)
        for (offset in 0..code.length) {
            assertEquals(offset, result.offsetMapping.originalToTransformed(offset))
            assertEquals(offset, result.offsetMapping.transformedToOriginal(offset))
        }
        // A same-length edit must still drop the stale colors.
        val changed = AnnotatedString(code.replace("moon", "star"))
        assertEquals(changed, transformation.filter(changed).text)
    }

    @Test fun `malformed comments and unfinished strings remain editable`() {
        listOf("*/ /* */", "/*", "\"unfinished", "let x = '\uD83C\uDF13'; /*\n*/ */ /*").forEach { code ->
            val highlighted = highlightCode(code, CodeLanguage.RUST, dark = false)
            assertEquals(code, highlighted.text)
            assertTrue(highlighted.spanStyles.all { it.start >= 0 && it.end <= code.length && it.start < it.end })
        }
    }

    @Test fun `plain text and large or token dense files skip color without truncating content`() {
        val cases = listOf(
            "fn main() {}" to CodeLanguage.PLAIN_TEXT,
            "x".repeat(MAX_CODE_HIGHLIGHT_CHARS + 1) to CodeLanguage.KOTLIN,
            "{}".repeat(7_000) to CodeLanguage.RUST,
        )
        cases.forEach { (code, language) ->
            val highlighted = highlightCode(code, language, dark = true)
            assertEquals(code, highlighted.text)
            assertTrue(highlighted.spanStyles.isEmpty())
        }
    }
}
