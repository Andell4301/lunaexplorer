package com.lunaexplorer.app.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Robolectric has no PDF text engine, so this runs on a device. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class PdfTextSelectionInstrumentedTest {
    @Test fun longPressPointAndPageRangeSelectRenderedText() = runBlocking {
        withContext(Dispatchers.IO) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val file = File.createTempFile("pdf-selection-", ".pdf", context.cacheDir)
            try {
                val document = PdfDocument()
                try {
                    val page = document.startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create())
                    page.canvas.drawText("Hello selection world", 40f, 80f, Paint().apply { textSize = 20f })
                    document.finishPage(page)
                    file.outputStream().use(document::writeTo)
                } finally { document.close() }
                PdfReadingFile(file).use { reader ->
                    val frame = reader.render(0)
                    assertEquals(300, frame.width)
                    assertEquals(400, frame.height)
                    assertTrue(frame.text.orEmpty().contains("Hello selection world"))
                    val point = PdfTextBoundary(point = Offset(60f, 72f))
                    val word = reader.select(0, PdfSelectionRequest(point, point, 1))
                    assertNotNull(word)
                    assertEquals("Hello", word!!.text.trim())
                    assertTrue(word.bounds.isNotEmpty())
                    val all = reader.select(0, PdfSelectionRequest(PdfTextBoundary(index = 0),
                        PdfTextBoundary(index = frame.text!!.length), 2))
                    assertTrue(all!!.text.contains("Hello selection world"))
                    reader.close() // A second close from use must be safe.
                }
            } finally { file.delete() }
            Unit
        }
    }
}
