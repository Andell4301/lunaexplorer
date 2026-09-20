package com.lunaexplorer.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.Overlay
import com.lunaexplorer.app.model.ViewerKind
import com.lunaexplorer.core.BinaryXmlFixture
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class ReadOnlyEditorUiTest : RobolectricBrowserUiTest() {
    private val viewModel get() = compose.runOnUiThread {
        ViewModelProvider(compose.activity)[BrowserViewModel::class.java]
    }

    private fun open(name: String, content: String, kind: ViewerKind) = open(name, content.toByteArray(), kind)

    private fun open(name: String, content: ByteArray, kind: ViewerKind) {
        File(fixture.directory, name).writeBytes(content)
        awaitListing()
        val model = viewModel
        compose.runOnUiThread { model.refresh() }
        awaitText(name)
        val entry = model.state.value.entries.first { it.name == name }
        compose.runOnUiThread { model.showOverlay(Overlay.Viewer(entry, kind)) }
    }

    private fun composedRows() = compose.onAllNodesWithTag("editorRow", useUnmergedTree = true).fetchSemanticsNodes()

    private fun awaitReadOnly() {
        awaitCondition("Read-only rows composed", 15_000) {
            compose.onAllNodesWithTag("editorRows").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Read-only: too large to edit here").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Save").assertCountEquals(0)
        assertViewportOnly()
    }

    private fun assertViewportOnly() {
        val rows = composedRows()
        assertTrue("Only visible and nearby rows should be composed, got ${rows.size}", rows.size in 1..150)
    }

    private fun close() {
        compose.onNodeWithContentDescription("Close").performClick()
        awaitCondition("Editor closed", 10_000) {
            viewModel.state.value.overlay == null && compose.onAllNodesWithTag("editorRows").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("editorText").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun aLargeXmlFileOpensAsLazyColoredRowsThatWrapAndScroll() {
        val source = buildString {
            append("<root>\n")
            repeat(10_000) { append("  <item index=\"").append(it).append("\">value</item>\n") }
            append("</root>\n")
        }
        assertTrue(source.length > MAX_EDITABLE_CHARS)
        open("large.xml", source, ViewerKind.CODE)
        awaitReadOnly()
        compose.onNodeWithText("Language: XML").assertIsDisplayed()
        awaitCondition("Rows colored", 10_000) {
            composedRows().any { row -> row.config[SemanticsProperties.Text].any { it.spanStyles.isNotEmpty() } }
        }

        compose.onNodeWithText("Wrap", substring = false).performClick()
        compose.onNodeWithText("No wrap", substring = false).assertIsDisplayed()
        assertViewportOnly()
        compose.onNodeWithTag("editorRows").performScrollToIndex(10_001)
        compose.onNodeWithText("</root>").assertIsDisplayed()
        assertViewportOnly()
        close()
    }

    @Test fun oneOverlongLineOpensReadOnlyInTheTextEditor() {
        val line = "0123456789".repeat(2_500)
        open("line.txt", line, ViewerKind.TEXT)
        awaitReadOnly()
        compose.onNodeWithText("No wrap", substring = false).performClick()
        compose.onNodeWithText("Wrap", substring = false).assertIsDisplayed()
        val rows = composedRows()
        assertTrue("Segments of one line: ${rows.size}", rows.size in 1..25)
        compose.onAllNodesWithText(line.substring(0, TextDocument.ROW_CHARS), useUnmergedTree = true).onFirst().assertExists()
        close()
    }

    @Test fun aLargeFileWithoutRowColorsShowsBothNotices() {
        val source = "{\"key\": \"value\"}\n".repeat(MAX_EDITABLE_CHARS / 16)
        assertTrue(source.length > MAX_EDITABLE_CHARS)
        open("large.json", source, ViewerKind.CODE)
        awaitReadOnly()
        compose.onNodeWithText("Highlighting is off for large files.").assertIsDisplayed()
        close()
    }

    @Test fun compiledXmlOpensDecodedAndReadOnly() {
        open("compiled.xml", BinaryXmlFixture.manifest(utf8 = true), ViewerKind.CODE)
        awaitCondition("Decoded rows composed", 15_000) {
            composedRows().any { row -> row.config[SemanticsProperties.Text].any { it.text.startsWith("<manifest") } }
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Save").assertCountEquals(0)
        compose.onAllNodesWithText("too large to edit here", substring = true).assertCountEquals(0)
        close()
    }

    @Test fun compiledXmlThatFailsToDecodeFallsBackToItsText() {
        open("broken.xml", BinaryXmlFixture.manifest(utf8 = true).copyOf(12), ViewerKind.CODE)
        awaitCondition("Raw text shown", 15_000) {
            compose.onAllNodesWithTag("editorText").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Save").assertCountEquals(0)
        close()
    }
}
