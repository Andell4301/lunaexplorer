package com.lunaexplorer.app.ui

import android.content.ClipboardManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.lunaexplorer.app.model.SyntaxScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestViewerTest {
    @get:Rule val compose = createComposeRule()

    private class FakeClipboard : Clipboard {
        var text = "Previous clipboard"
        var refuse = false
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            check(!refuse) { "Clipboard unavailable" }
            text = clipEntry?.clipData?.getItemAt(0)?.text?.toString().orEmpty()
        }
        @Suppress("OVERRIDE_DEPRECATION")
        override val nativeClipboard: ClipboardManager get() = error("Not used")
    }

    private fun show(
        text: String,
        clipboard: FakeClipboard,
        scheme: MutableState<SyntaxScheme> = mutableStateOf(SyntaxScheme.DARCULA),
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalClipboard provides clipboard,
                LocalSyntaxScheme provides scheme.value,
            ) {
                MaterialTheme {
                    val scope = rememberCoroutineScope()
                    val session = remember {
                        TextEditorSession.forText("test.apk", "Test · manifest", scope, "This manifest could not be read") { text }
                    }
                    TextEditorScreen(session, code = true, onDismiss = {}, onScheme = { scheme.value = it })
                }
            }
        }
        try {
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.waitForIdle()
                compose.onAllNodesWithTag("editorRows").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: ComposeTimeoutException) {
            val visible = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
                .fetchSemanticsNodes().joinToString("\n") { it.config[SemanticsProperties.Text].joinToString() }
            throw AssertionError("The manifest never became ready. Visible UI:\n$visible", timeout)
        }
    }

    private fun assertViewportOnly() {
        val rows = compose.onAllNodesWithTag("editorRow", useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("Only visible and nearby rows should be composed, got ${rows.size}", rows.size in 1..150)
    }

    @Test fun `twelve thousand lines stay lazy while scrolling wrapping and copying exact XML`() {
        val source = buildString {
            append("<manifest>\r\n")
            repeat(12_000) { append("<p n=\"").append(it).append("\"/>\r\n") }
            append("</manifest>\r\n")
        }
        val clipboard = FakeClipboard()
        show(source, clipboard)
        assertViewportOnly()

        compose.onNodeWithTag("editorRows").performScrollToIndex(11_981)
        compose.onNodeWithText("<p n=\"11980\"/>").assertIsDisplayed()
        assertViewportOnly()

        compose.onNodeWithText("Wrap", substring = false).performClick()
        compose.onNodeWithText("No wrap", substring = false).assertIsDisplayed()
        compose.onNodeWithTag("editorRows").performScrollToIndex(12_001)
        compose.onNodeWithText("</manifest>").assertIsDisplayed()
        assertViewportOnly()

        compose.onNodeWithText("Copy", substring = false).performClick()
        compose.onNodeWithText("Manifest copied").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Copy must preserve CRLF and the trailing newline", source, clipboard.text) }
    }

    @Test fun `manifest rows are colored as XML once the background scan finishes`() {
        show("<manifest package=\"com.example\">\n  <!-- note -->\n  <application/>\n</manifest>", FakeClipboard())
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithTag("editorRow", useUnmergedTree = true).fetchSemanticsNodes()
                .any { node -> node.config[SemanticsProperties.Text].any { it.spanStyles.isNotEmpty() } }
        }
        val rows = compose.onAllNodesWithTag("editorRow", useUnmergedTree = true).fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].single() }
        assertEquals(4, rows.size)
        assertEquals("<manifest package=\"com.example\">", rows[0].text)
        // Tag name, attribute name and attribute value each carry their own color.
        assertEquals(3, rows[0].spanStyles.map { it.item.color }.distinct().size)
        assertEquals("  <!-- note -->", rows[1].text)
        val commentSpan = rows[1].spanStyles.single()
        assertEquals(2 to rows[1].text.length, commentSpan.start to commentSpan.end)
        assertEquals("</manifest>", rows[3].text)
        assertTrue(rows[3].spanStyles.isNotEmpty())
    }

    private fun firstRowColor(): Color? = compose.onAllNodesWithTag("editorRow", useUnmergedTree = true)
        .fetchSemanticsNodes().firstOrNull()
        ?.config?.get(SemanticsProperties.Text)?.single()?.spanStyles?.firstOrNull()?.item?.color

    @Test fun `the colors picker recolors the manifest and reports the chosen scheme`() {
        val scheme = mutableStateOf(SyntaxScheme.ONE)
        show("<manifest package=\"com.example\"/>", FakeClipboard(), scheme)
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); firstRowColor() != null }
        val before = firstRowColor()

        compose.onNodeWithText("Colors: One").assertIsDisplayed().performClick()
        compose.onNodeWithText("Monokai").performClick()

        assertEquals(SyntaxScheme.MONOKAI, scheme.value)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle()
            firstRowColor().let { it != null && it != before }
        }
        compose.onNodeWithText("Colors: Monokai").assertIsDisplayed()
    }

    @Test fun `oversized clipboard copy reports the limit while keeping the complete manifest readable`() {
        val source = "<manifest>\n" + "<p/>\n".repeat(50_000) + "</manifest>"
        val clipboard = FakeClipboard()
        show(source, clipboard)
        compose.onNodeWithText("Copy", substring = false).performClick()
        compose.onNodeWithText("This manifest is too large for the clipboard.", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals("Previous clipboard", clipboard.text) }
        compose.onNodeWithTag("editorRows").performScrollToIndex(50_001)
        compose.onNodeWithText("</manifest>").assertIsDisplayed()
        assertViewportOnly()
    }

    @Test fun `clipboard service failure is visible and does not dismiss the manifest`() {
        val clipboard = FakeClipboard().apply { refuse = true }
        show("<manifest/>", clipboard)
        compose.onNodeWithText("Copy", substring = false).performClick()
        compose.onNodeWithText("Clipboard unavailable").assertIsDisplayed()
        compose.onNodeWithText("<manifest/>").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Previous clipboard", clipboard.text) }
    }
}
