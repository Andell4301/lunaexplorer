package com.lunaexplorer.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.Overlay
import com.lunaexplorer.app.model.ViewerKind
import com.lunaexplorer.core.BinaryXmlFixture
import com.lunaexplorer.core.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorFindUiTest : RobolectricBrowserUiTest() {
    private val viewModel get() = compose.runOnUiThread {
        ViewModelProvider(compose.activity)[BrowserViewModel::class.java]
    }

    private fun open(name: String, content: ByteArray, overlay: (Entry) -> Overlay) {
        File(fixture.directory, name).writeBytes(content)
        awaitListing()
        val model = viewModel
        compose.runOnUiThread { model.refresh() }
        awaitText(name)
        val entry = model.state.value.entries.first { it.name == name }
        compose.runOnUiThread { model.showOverlay(overlay(entry)) }
    }

    private fun openText(name: String, content: String, kind: ViewerKind) {
        open(name, content.toByteArray()) { Overlay.Viewer(it, kind) }
        awaitCondition("Text loaded", 15_000) {
            compose.onAllNodesWithTag("editorText").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("editorRows").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun find(query: String) {
        if (compose.onAllNodesWithTag("findField").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithContentDescription("Find").performClick()
        }
        compose.onNodeWithTag("findField").performTextReplacement(query)
    }

    private fun awaitCount(expected: String) = awaitCondition("Find count $expected", 15_000) {
        compose.onAllNodes(hasTestTag("findCount") and hasText(expected)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun scrolledDown(tag: String) =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun scrolledSideways(tag: String) =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()

    private fun manyLines() = buildString {
        append("needle first\n")
        repeat(3_000) { append("line ").append(it).append('\n') }
        append("last needle\n")
    }

    @Test fun findCountsMatchesWrapsAroundAndScrollsTheEditableText() {
        openText("lines.txt", manyLines(), ViewerKind.TEXT)
        val editorWindow = ShadowDialog.getLatestDialog() as OnBackPressedDispatcherOwner
        find("NEEDLE")
        awaitCount("1 of 2")
        assertEquals(0f, scrolledDown("editorText"))

        compose.onNodeWithContentDescription("Next match").performClick()
        awaitCount("2 of 2")
        awaitCondition("Scrolled to the last line", 10_000) { scrolledDown("editorText") > 0f }
        compose.onNodeWithContentDescription("Next match").performClick()
        awaitCount("1 of 2")
        awaitCondition("Scrolled back to the first line", 10_000) { scrolledDown("editorText") == 0f }
        compose.onNodeWithContentDescription("Previous match").performClick()
        awaitCount("2 of 2")

        compose.onNodeWithText("Match case").performClick()
        awaitCount("0 of 0")
        compose.onNodeWithText("Regex").performClick()
        find("(")
        awaitCondition("Pattern error shown", 10_000) {
            compose.onAllNodesWithTag("findError").fetchSemanticsNodes().isNotEmpty()
        }

        compose.runOnUiThread { editorWindow.onBackPressedDispatcher.onBackPressed() }
        awaitCondition("Find closed", 10_000) { compose.onAllNodesWithTag("findField").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("editorText").assertIsDisplayed()
        compose.runOnUiThread { editorWindow.onBackPressedDispatcher.onBackPressed() }
        awaitCondition("Editor closed", 10_000) {
            viewModel.state.value.overlay == null && compose.onAllNodesWithTag("editorText").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun aMatchPastTheRightEdgeScrollsTheUnwrappedTextSideways() {
        openText("wide.txt", "short\n" + "x".repeat(600) + "needle\n", ViewerKind.CODE)
        find("needle")
        awaitCount("1 of 1")
        awaitCondition("Scrolled sideways", 10_000) { scrolledSideways("editorText") > 0f }
    }

    private fun dialogPixels(): Bitmap {
        compose.waitForIdle()
        // A capture of a root shows the Activity behind the editor, so the editor's own window is drawn.
        return compose.runOnUiThread {
            val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
    }

    /** Pixels that differ between the two captures over the text in [from] until [to]. */
    private fun repainted(without: Bitmap, with: Bitmap, from: Int, to: Int): Int {
        val node = compose.onNodeWithTag("editorText").fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action)(layouts)
        val layout = layouts.single()
        val first = layout.getBoundingBox(from)
        val last = layout.getBoundingBox(to - 1)
        // The node is the scrolled content, so its position already carries the scroll offset.
        val origin = node.positionInWindow
        var pixels = 0
        for (y in (origin.y + first.top).toInt() until (origin.y + last.bottom).toInt()) {
            for (x in (origin.x + first.left).toInt() until (origin.x + last.right).toInt()) {
                if (without.getPixel(x, y) != with.getPixel(x, y)) pixels++
            }
        }
        return pixels
    }

    @Test fun matchesArePaintedBehindTheEditableText() {
        openText("paint.txt", "alpha\nneedle here\nomega\n", ViewerKind.TEXT)
        find("zzzz")
        awaitCount("0 of 0")
        val without = dialogPixels()
        find("needle")
        awaitCount("1 of 1")
        val with = dialogPixels()

        assertTrue("The match is highlighted", repainted(without, with, 6, 12) > 0)
        assertEquals("Text outside the match is left alone", 0, repainted(without, with, 0, 5))
        assertEquals("Text outside the match is left alone", 0, repainted(without, with, 13, 17))
    }

    @Test fun matchesArePaintedWhereTheScrolledTextIs() {
        openText("scrolled.txt", "alpha\n".repeat(120) + "needle here\nomega\n", ViewerKind.TEXT)
        find("needle")
        awaitCount("1 of 1")
        awaitCondition("Scrolled to the match", 10_000) { scrolledDown("editorText") > 0f }
        val scroll = scrolledDown("editorText")
        val with = dialogPixels()
        find("zzzz")
        awaitCount("0 of 0")
        val without = dialogPixels()

        assertEquals("Dropping the matches must not scroll", scroll, scrolledDown("editorText"), 0f)
        assertTrue("The match is highlighted", repainted(without, with, 720, 726) > 0)
        assertEquals("The line above it is left alone", 0, repainted(without, with, 714, 719))
    }

    @Test fun editingTheTextReRunsTheFind() {
        openText("draft.txt", "needle\n", ViewerKind.TEXT)
        find("needle")
        awaitCount("1 of 1")
        compose.onNodeWithTag("editorText").performTextReplacement("needle needle\nneedle")
        awaitCount("1 of 3")
    }

    @Test fun findHighlightsReadOnlyRowsAndScrollsToAFarMatch() {
        val source = buildString {
            append("<!-- needle needle -->\n<root>\n")
            repeat(10_000) { append("  <item index=\"").append(it).append("\">value</item>\n") }
            append("  <far>").append("x".repeat(600)).append("needle</far>\n</root>\n")
        }
        assertTrue(source.length > MAX_EDITABLE_CHARS)
        openText("large.xml", source, ViewerKind.CODE)
        find("needle")
        awaitCount("1 of 3")
        fun backgrounds() = compose.onAllNodesWithTag("editorRow", useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { row -> row.config[SemanticsProperties.Text].flatMap { text -> text.spanStyles.map { it.item.background } } }
            .filter { it != Color.Unspecified }.distinct()
        awaitCondition("The current match and the other one are marked differently", 10_000) { backgrounds().size == 2 }

        compose.onNodeWithContentDescription("Previous match").performClick()
        awaitCount("3 of 3")
        awaitCondition("The far row is composed", 10_000) {
            compose.onAllNodes(hasTestTag("editorRow") and hasText("needle</far>", substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasTestTag("editorRow") and hasText("needle</far>", substring = true), useUnmergedTree = true)
            .assertIsDisplayed()
        assertTrue(scrolledSideways("editorRows") > 0f)
    }

    @Test fun findSurvivesActivityRecreation() {
        openText("lines.txt", manyLines(), ViewerKind.TEXT)
        find("needle")
        awaitCount("1 of 2")
        compose.onNodeWithContentDescription("Next match").performClick()
        awaitCount("2 of 2")
        awaitCondition("Scrolled to the last line", 10_000) { scrolledDown("editorText") > 0f }
        compose.onNodeWithTag("editorText").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -1_000_000f) }
        awaitCondition("Scrolled back by hand", 10_000) { scrolledDown("editorText") == 0f }

        compose.activityRule.scenario.recreate()
        awaitCount("2 of 2")
        compose.onNodeWithTag("findField").assertTextEquals("needle")
        assertEquals("Recreation must not scroll to the match again", 0f, scrolledDown("editorText"))
    }

    @Test fun aPackageManifestOpensInTheEditorWithFind() {
        val apk = ByteArrayOutputStream()
        ZipOutputStream(apk).use {
            it.putNextEntry(ZipEntry("AndroidManifest.xml"))
            it.write(BinaryXmlFixture.manifest(utf8 = true))
            it.closeEntry()
        }
        open("sample.apk", apk.toByteArray()) { Overlay.Manifest(it) }
        awaitText("sample.apk · manifest")
        awaitCondition("Manifest rows composed", 15_000) {
            compose.onAllNodesWithTag("editorRows").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasContentDescription("More") and hasAnyAncestor(isDialog())).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Save").assertCountEquals(0)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNode(hasText("Copy") and hasAnyAncestor(isDialog())).assertIsDisplayed()

        find("manifest")
        awaitCount("1 of 2")
    }
}
