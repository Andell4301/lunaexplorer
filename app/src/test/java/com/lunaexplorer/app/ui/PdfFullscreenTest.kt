package com.lunaexplorer.app.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.content.PdfPageTextContent
import android.os.ParcelFileDescriptor
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.height
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.ViewerKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class,
    shadows = [PdfFullscreenTest.Renderer::class, PdfFullscreenTest.Page::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfFullscreenTest {
    private val harness = BrowserViewModelHarness().startingWith { File(it, "manual.pdf").writeText("PDF fixture") }
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(harness).around(compose)
    private var shown by mutableStateOf(true)

    private fun show() {
        assertTrue(harness.awaitUntil(rounds = 2_000) { harness.state.ready })
        val entry = runBlocking {
            harness.graph.local.stat(requireNotNull(harness.graph.local.referenceTo(File(harness.directory, "manual.pdf").path)))
        }
        compose.setContent {
            MaterialTheme {
                if (shown) ViewerScreen(entry, ViewerKind.PDF, harness.viewModel) { shown = false }
            }
        }
        awaitPage(1)
    }

    private fun awaitPage(page: Int) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Page $page").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun reopen() {
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        compose.runOnIdle { shown = true }
        awaitPage(1)
    }

    @Test fun `full screen expands the page and preserves navigation and zoom when controls return`() {
        show()
        compose.onNodeWithContentDescription("Next page").performClick()
        awaitPage(2)
        val fittedHeight = compose.onNodeWithContentDescription("Page 2").getUnclippedBoundsInRoot().height

        compose.onNodeWithContentDescription("Full screen").performClick()
        compose.onNodeWithContentDescription("Exit full screen").assertIsDisplayed()
        listOf("Close", "Next page", "Previous page", "PDF settings", "Zoom in").forEach {
            compose.onNodeWithContentDescription(it).assertDoesNotExist()
        }
        assertTrue(compose.onNodeWithContentDescription("Page 2").getUnclippedBoundsInRoot().height > fittedHeight)
        compose.onNodeWithContentDescription("Page 2").performTouchInput {
            click(Offset(width * .9f, height * .5f))
        }
        awaitPage(3)
        compose.onNodeWithContentDescription("Exit full screen").performClick()
        compose.onNodeWithText("Page 3 of 3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zoom in").performClick()
        compose.onNodeWithContentDescription("Full screen").performClick()
        compose.onNodeWithContentDescription("Exit full screen").performClick()
        compose.onNodeWithContentDescription("Zoom out").assertIsEnabled()
        compose.onNodeWithText("Page 3 of 3").assertIsDisplayed()
    }

    @Test fun `dialog Back exits one-use full screen before closing and reopening uses the saved choice`() {
        show()
        compose.onNodeWithContentDescription("Full screen").performClick()
        assertFalse(harness.state.preferences.pdfFullScreen)
        val dialog = ShadowDialog.getLatestDialog() as OnBackPressedDispatcherOwner
        compose.runOnUiThread { dialog.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Full screen").assertIsDisplayed()
        compose.runOnIdle { assertTrue(shown) }
        compose.runOnUiThread { dialog.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertFalse(shown) }

        compose.runOnIdle { shown = true }
        awaitPage(1)
        compose.onNodeWithContentDescription("Full screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Exit full screen").assertDoesNotExist()
        compose.onNodeWithContentDescription("Full screen").performClick()
        reopen()
        compose.onNodeWithContentDescription("Full screen").assertIsDisplayed()
        assertFalse(harness.state.preferences.pdfFullScreen)
    }

    @Test fun `Remember saves either full screen choice while a temporary exit keeps the default`() {
        show()
        compose.onNodeWithContentDescription("PDF settings").performClick()
        compose.onNodeWithText("Full screen").performClick()
        assertFalse(harness.state.preferences.pdfFullScreen)
        compose.onNodeWithText("Remember").performClick()
        compose.runOnIdle { assertTrue(harness.state.preferences.pdfFullScreen) }
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription("Exit full screen").assertIsDisplayed()

        reopen()
        compose.onNodeWithContentDescription("Exit full screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Exit full screen").performClick()
        compose.runOnIdle { assertTrue(harness.state.preferences.pdfFullScreen) }
        reopen()
        compose.onNodeWithContentDescription("Exit full screen").assertIsDisplayed()

        compose.onNodeWithContentDescription("Exit full screen").performClick()
        compose.onNodeWithContentDescription("PDF settings").performClick()
        compose.onNodeWithText("Remember").performClick()
        compose.runOnIdle { assertFalse(harness.state.preferences.pdfFullScreen) }
        compose.onNodeWithText("Close").performClick()
        reopen()
        compose.onNodeWithContentDescription("Full screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Exit full screen").assertDoesNotExist()
    }

    @Implements(PdfRenderer::class)
    class Renderer {
        private var descriptor: ParcelFileDescriptor? = null
        @Implementation fun __constructor__(descriptor: ParcelFileDescriptor) { this.descriptor = descriptor }
        @Implementation fun getPageCount(): Int = 3
        @Implementation fun openPage(index: Int): PdfRenderer.Page = Shadow.newInstanceOf(PdfRenderer.Page::class.java)
        @Implementation fun close() { descriptor?.close(); descriptor = null }
    }

    @Implements(PdfRenderer.Page::class)
    class Page {
        @Implementation fun getWidth(): Int = 300
        @Implementation fun getHeight(): Int = 400
        @Implementation fun render(bitmap: Bitmap, clip: Rect?, matrix: Matrix?, mode: Int) = Unit
        @Implementation fun getTextContents(): List<PdfPageTextContent> = emptyList()
        @Implementation fun close() = Unit
    }
}
