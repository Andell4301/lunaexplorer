package com.lunaexplorer.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.lunaexplorer.app.model.SyntaxScheme
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

internal enum class CodeLanguage(val label: String, val syntax: SyntaxLanguage?, vararg val extensions: String) {
    PLAIN_TEXT("Plain text", null),
    C("C", SyntaxLanguage.C, "c", "h"),
    CPP("C++", SyntaxLanguage.CPP, "cc", "cpp", "cxx", "hh", "hpp", "hxx"),
    CSHARP("C#", SyntaxLanguage.CSHARP, "cs"),
    DART("Dart", SyntaxLanguage.DART, "dart"),
    GO("Go", SyntaxLanguage.GO, "go"),
    HTML("HTML", null, "html", "htm", "xhtml"),
    JAVA("Java", SyntaxLanguage.JAVA, "java"),
    JAVASCRIPT("JavaScript", SyntaxLanguage.JAVASCRIPT, "js", "mjs", "cjs", "jsx"),
    // JSON is tokenized with the JavaScript rules.
    JSON("JSON", SyntaxLanguage.JAVASCRIPT, "json", "jsonc", "jsonl", "webmanifest"),
    KOTLIN("Kotlin", SyntaxLanguage.KOTLIN, "kt", "kts"),
    PERL("Perl", SyntaxLanguage.PERL, "pl", "pm"),
    PHP("PHP", SyntaxLanguage.PHP, "php", "phtml"),
    PYTHON("Python", SyntaxLanguage.PYTHON, "py", "pyi", "pyw"),
    RUBY("Ruby", SyntaxLanguage.RUBY, "rb", "rake", "gemspec"),
    RUST("Rust", SyntaxLanguage.RUST, "rs"),
    SHELL("Shell", SyntaxLanguage.SHELL, "sh", "bash", "zsh", "ksh"),
    SWIFT("Swift", SyntaxLanguage.SWIFT, "swift"),
    TYPESCRIPT("TypeScript", SyntaxLanguage.TYPESCRIPT, "ts", "tsx", "mts", "cts"),
    XML("XML", null, "xml", "xsd", "xsl", "xslt", "svg", "plist", "kml", "gpx", "rss", "atom", "opml", "wsdl", "xaml");

    /** Luna's own tokenizer for markup, which Highlights does not cover. */
    val custom: XmlSyntax? get() = when (this) {
        XML -> XmlSyntax.XML
        HTML -> XmlSyntax.HTML
        else -> null
    }

    companion object {
        fun fromFilename(name: String): CodeLanguage {
            val lower = name.lowercase(Locale.ROOT)
            return when (lower) {
                ".bashrc", ".bash_profile", ".profile", ".zshrc", ".zprofile" -> SHELL
                "gemfile", "rakefile" -> RUBY
                else -> entries.firstOrNull { lower.substringAfterLast('.', "") in it.extensions } ?: PLAIN_TEXT
            }
        }
    }
}

internal const val MAX_CODE_HIGHLIGHT_CHARS = 65_536
private const val MAX_CODE_HIGHLIGHT_SPANS = 12_000

// The Highlights tokenizer is synchronous and cannot be cancelled; a single worker and the size cap
// bound the stale work queued up by edits.
private val codeHighlightDispatcher = Dispatchers.Default.limitedParallelism(1)

@Composable
internal fun CodeLanguagePicker(language: CodeLanguage, onChange: (CodeLanguage) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("Language: ${language.label}") }
        FastDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CodeLanguage.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = {
                    onChange(option)
                    expanded = false
                })
            }
        }
    }
}

@Composable
internal fun SyntaxSchemePicker(scheme: SyntaxScheme, onChange: (SyntaxScheme) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("Colors: ${scheme.label}") }
        FastDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SyntaxScheme.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = {
                    onChange(option)
                    expanded = false
                })
            }
        }
    }
}

@Composable
internal fun rememberCodeHighlighting(text: String, language: CodeLanguage): VisualTransformation {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val scheme = LocalSyntaxScheme.current
    var highlighted by remember(text, language, dark, scheme) { mutableStateOf<AnnotatedString?>(null) }
    LaunchedEffect(text, language, dark, scheme) {
        if (language == CodeLanguage.PLAIN_TEXT || text.length > MAX_CODE_HIGHLIGHT_CHARS) return@LaunchedEffect
        delay(250)
        highlighted = withContext(codeHighlightDispatcher) { highlightCode(text, language, dark, scheme) }
    }
    return remember(highlighted) { CodeHighlightTransformation(highlighted) }
}

internal class CodeHighlightTransformation(private val highlighted: AnnotatedString?) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(
        highlighted?.takeIf { it.text == text.text } ?: text,
        OffsetMapping.Identity,
    )
}

/** Run on a worker thread; never call from filter() or composition. */
internal fun highlightCode(
    text: String, language: CodeLanguage, dark: Boolean, scheme: SyntaxScheme = SyntaxScheme.DARCULA,
): AnnotatedString {
    val plain = AnnotatedString(text)
    if (text.length > MAX_CODE_HIGHLIGHT_CHARS) return plain
    val theme = scheme.theme(dark)
    return try {
        language.custom?.let { custom ->
            val spans = custom.highlight(text, theme)
            return if (spans.size > MAX_CODE_HIGHLIGHT_SPANS) plain else spans.annotate(text, 0, text.length)
        }
        val syntax = language.syntax ?: return plain
        // A fresh Highlights per call: its incremental cache only handles appends, and an edit inside a
        // string or comment changes every later token.
        val highlights = Highlights.Builder().code(text).language(syntax)
            .theme(theme).build().getHighlights()
        if (highlights.size > MAX_CODE_HIGHLIGHT_SPANS) return plain
        AnnotatedString.Builder(text).apply {
            highlights.filterIsInstance<ColorHighlight>().forEach { highlight ->
                val (start, end) = highlight.location
                // Ignore reversed ranges that Highlights can produce for unmatched comment delimiters.
                if (start >= 0 && end <= text.length && start < end) {
                    addStyle(SpanStyle(color = Color(highlight.rgb or 0xFF000000.toInt())), start, end)
                }
            }
        }.toAnnotatedString()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        plain
    }
}
