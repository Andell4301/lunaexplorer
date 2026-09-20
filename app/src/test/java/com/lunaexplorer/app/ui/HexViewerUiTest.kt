package com.lunaexplorer.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.Overlay
import com.lunaexplorer.app.model.ViewerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Rows are found by tag, never by the file's name: the browser row behind the dialog carries it too.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HexViewerUiTest : RobolectricBrowserUiTest() {
    private val viewModel get() = compose.runOnUiThread {
        ViewModelProvider(compose.activity)[BrowserViewModel::class.java]
    }

    private fun sample() = ByteArray(4096).also { bytes ->
        "LUNA".toByteArray().copyInto(bytes, 0)
        byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()).copyInto(bytes, 0x200)
        "needle".toByteArray().copyInto(bytes, 0x300)
    }

    private fun place(name: String, content: ByteArray) {
        File(fixture.directory, name).writeBytes(content)
        awaitListing()
        val model = viewModel
        compose.runOnUiThread { model.refresh() }
        awaitText(name)
    }

    private fun tap(name: String) =
        compose.onNode(hasText(name) and hasClickAction() and hasAnyDescendant(hasContentDescription("Select $name"))).performClick()

    private fun rows(): List<SemanticsNode> = compose.onAllNodesWithTag("hexRow").fetchSemanticsNodes()
    private fun SemanticsNode.line() = config[SemanticsProperties.Text].first()

    private fun awaitRows() = awaitCondition("Hex rows read", 15_000) { rows().firstOrNull()?.line()?.text?.contains("  ") == true }

    private fun awaitMarked(offset: String) {
        awaitCondition("Row $offset shown with a mark", 15_000) {
            rows().any { row -> row.line().text.startsWith(offset) && row.line().spanStyles.any { it.item.background != Color.Unspecified } }
        }
        compose.onNode(hasTestTag("hexRow") and hasText(offset, substring = true)).assertIsDisplayed()
    }

    private fun awaitClosed() = awaitCondition("Hex viewer closed", 10_000) {
        viewModel.state.value.overlay == null && compose.onAllNodesWithTag("hexRows").fetchSemanticsNodes().isEmpty()
    }

    @Test fun hexViewerOpensFromTheChooserAndGoesToAndFindsBytes() {
        place("sample.bin", sample())
        tap("sample.bin")
        awaitText("Hex viewer")
        compose.onNodeWithText("Hex viewer").performScrollTo().performClick()
        awaitRows()
        assertEquals(ViewerKind.HEX, (viewModel.state.value.overlay as Overlay.Viewer).kind)
        val first = rows().first().line().text
        assertTrue(first, first.startsWith("0000") && first.contains("4C 55 4E 41") && first.contains("LUNA"))

        compose.onNodeWithText("Go to").performClick()
        compose.onNodeWithTag("hexGoTo").performTextReplacement("1000")
        compose.onNodeWithText("Go").performClick()
        compose.onNodeWithTag("hexGoTo").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithTag("hexGoTo").performTextReplacement("100")
        compose.onNodeWithText("Go").performClick()
        awaitMarked("0100")

        compose.onNodeWithText("Find").performClick()
        compose.onNodeWithTag("hexGoTo").assertDoesNotExist()
        compose.onNodeWithText("Hex").performClick()
        compose.onNodeWithTag("hexFind").performTextReplacement("DE AD BE EF")
        compose.onNodeWithContentDescription("Next match").performClick()
        awaitMarked("0200")
        compose.onNodeWithText("Hex").performClick()
        compose.onNodeWithTag("hexFind").performTextReplacement("needle")
        compose.onNodeWithContentDescription("Next match").performClick()
        awaitMarked("0300")

        compose.onNodeWithContentDescription("Close").performClick()
        awaitClosed()
    }

    @Test fun aRememberedHexDefaultOpensStraightInTheViewer() {
        place("sample.bin", sample())
        tap("sample.bin")
        awaitText("Hex viewer")
        compose.onNodeWithText("Always for .bin files").performScrollTo().performClick()
        compose.onNodeWithText("Hex viewer").performScrollTo().performClick()
        awaitRows()
        compose.onNodeWithContentDescription("Close").performClick()
        awaitClosed()

        tap("sample.bin")
        awaitRows()
        compose.onAllNodesWithText("Always for .bin files").assertCountEquals(0)
    }

    @Test fun anArchiveMemberIsCopiedOutAndTheCopyGoesWithTheViewer() {
        val zip = File(fixture.directory, "bundle.zip")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("inner.bin"))
            it.write(sample())
            it.closeEntry()
        }
        awaitListing()
        val model = viewModel
        compose.runOnUiThread { model.refresh() }
        awaitText("bundle.zip")
        val archive = model.state.value.entries.first { it.name == "bundle.zip" }
        compose.runOnUiThread { model.browseArchive(archive) }
        awaitCondition("Inside the archive", 15_000) { model.state.value.entries.any { it.name == "inner.bin" } }
        val member = model.state.value.entries.first { it.name == "inner.bin" }
        compose.runOnUiThread { model.showOverlay(Overlay.Viewer(member, ViewerKind.HEX)) }
        awaitRows()
        assertTrue(rows().first().line().text.contains("4C 55 4E 41"))
        val copies = File(compose.activity.cacheDir, "viewers")
        assertEquals(1, copies.listFiles().orEmpty().size)

        compose.onNodeWithContentDescription("Close").performClick()
        awaitClosed()
        awaitCondition("The copy is deleted", 10_000) { copies.listFiles().orEmpty().isEmpty() }
    }
}
