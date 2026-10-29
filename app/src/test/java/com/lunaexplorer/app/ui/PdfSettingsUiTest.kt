package com.lunaexplorer.app.ui

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.PdfDirection
import com.lunaexplorer.app.model.PdfPageLayout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class PdfSettingsUiTest : RobolectricBrowserUiTest() {
    @Test fun fullScreenDefaultCanBeChangedFromSettings() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        compose.runOnUiThread {
            viewModel.setPreferences(viewModel.state.value.preferences.copy(pdfFullScreen = false))
        }
        openSettingsPage("PDF viewer")

        compose.onNodeWithText("Full screen").performScrollTo().performClick()
        awaitCondition("PDF full screen default enabled", 10_000) { viewModel.state.value.preferences.pdfFullScreen }
        compose.onNodeWithText("Full screen").performScrollTo().performClick()
        awaitCondition("PDF full screen default disabled", 10_000) { !viewModel.state.value.preferences.pdfFullScreen }
    }

    @Test fun pageTurningGesturesCanBeChangedIndependentlyFromSettings() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        compose.runOnUiThread {
            viewModel.setPreferences(viewModel.state.value.preferences.copy(pdfTapToTurn = true, pdfSwipeToTurn = true))
        }
        openSettingsPage("PDF viewer")

        compose.onNodeWithText("Tap to turn pages").performScrollTo().performClick()
        awaitCondition("PDF tap disabled independently", 10_000) {
            viewModel.state.value.preferences.let { !it.pdfTapToTurn && it.pdfSwipeToTurn }
        }
        compose.onNodeWithText("Swipe to turn pages").performScrollTo().performClick()
        awaitCondition("both PDF gestures disabled", 10_000) {
            viewModel.state.value.preferences.let { !it.pdfTapToTurn && !it.pdfSwipeToTurn }
        }
        compose.onNodeWithText("Tap to turn pages").performScrollTo().performClick()
        awaitCondition("PDF tap enabled independently", 10_000) {
            viewModel.state.value.preferences.let { it.pdfTapToTurn && !it.pdfSwipeToTurn }
        }
        compose.onNodeWithText("Swipe to turn pages").performScrollTo().performClick()
        awaitCondition("both PDF gestures enabled", 10_000) {
            viewModel.state.value.preferences.let { it.pdfTapToTurn && it.pdfSwipeToTurn }
        }
    }

    @Test fun eachPdfLayoutCanUseEitherDirectionFromSettings() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        openSettingsPage("PDF viewer")

        for (layout in listOf(PdfPageLayout.CONTINUOUS, PdfPageLayout.SINGLE)) {
            compose.onNodeWithText(layout.label).performScrollTo().performClick()
            for (direction in listOf(PdfDirection.VERTICAL, PdfDirection.HORIZONTAL)) {
                compose.onNodeWithText(direction.label).performScrollTo().performClick()
                awaitCondition("PDF $layout $direction selected", 10_000) {
                    viewModel.state.value.preferences.let { it.pdfPageLayout == layout && it.pdfDirection == direction }
                }
            }
        }
    }
}
