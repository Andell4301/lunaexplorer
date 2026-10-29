package com.lunaexplorer.app.data

import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.PdfDirection
import com.lunaexplorer.app.model.PdfPageLayout
import com.lunaexplorer.app.model.Preferences
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfSettingsTransferTest {
    @Test fun `PDF reading options survive sessions and settings transfer independently`() {
        val units = setOf("pdf.tapToTurn", "pdf.swipeToTurn", "pdf.direction", "pdf.pageLayout", "pdf.fullScreen")
        for (fullScreen in listOf(false, true)) for (tap in listOf(false, true)) for (swipe in listOf(false, true)) {
            for (direction in PdfDirection.entries) for (layout in PdfPageLayout.entries) {
                val preferences = Preferences(pdfTapToTurn = tap, pdfSwipeToTurn = swipe,
                    pdfDirection = direction, pdfPageLayout = layout, pdfFullScreen = fullScreen)
                val session = SessionCodec.decode(SessionCodec.encode(BrowserState(preferences = preferences)))
                assertEquals(preferences, session.preferences)

                val document = TransferCodec.export(TransferSource(preferences = preferences), units, "test", 0)
                val decoded = requireNotNull(TransferCodec.decode(TransferCodec.encode(document)))
                val current = TransferSource(preferences = Preferences(pdfTapToTurn = !tap, pdfSwipeToTurn = !swipe,
                    pdfDirection = PdfDirection.entries.first { it != direction },
                    pdfPageLayout = PdfPageLayout.entries.first { it != layout }, pdfFullScreen = !fullScreen))
                val restored = SettingsPreviewer.apply(SettingsPreviewer.of(decoded, current), current, units).source
                assertEquals(preferences, restored.preferences)
            }
        }
    }
}
