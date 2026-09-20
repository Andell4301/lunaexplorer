package com.lunaexplorer.app.ui

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.RectF
import android.graphics.pdf.content.PdfPageTextContent
import android.graphics.pdf.models.selection.PageSelection
import android.graphics.pdf.models.selection.SelectionBoundary
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfTextSelectionTest {
    @get:Rule val compose = createComposeRule()

    private val bitmap get() = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).asImageBitmap()

    @Test fun `large page text is laid out in bounded chunks without losing unicode or whitespace`() {
        val text = "First paragraph\n" + "Long line 日本語 🌓 ".repeat(20_000) + "\nFinal paragraph"
        val chunks = pdfTextChunks(text)
        assertTrue(chunks.size > 100)
        assertTrue(chunks.all { it.length <= 2048 })
        assertEquals(text, chunks.joinToString(""))
        assertFalse(chunks.any { it.lastOrNull()?.isHighSurrogate() == true })
        assertFalse(chunks.any { it.firstOrNull()?.isLowSurrogate() == true })
    }

    @Test fun `PDF points and highlights use the measured page rather than bitmap resolution`() {
        val fit = PdfPageCoordinates(600, 800, IntSize(300, 400))
        assertEquals(Offset(120f, 160f), fit.toPage(Offset(60f, 80f)))
        assertEquals(Rect(30f, 40f, 90f, 60f), fit.toScreen(Rect(60f, 80f, 180f, 120f)))
        val zoom = PdfPageCoordinates(600, 800, IntSize(1200, 1600))
        assertEquals(Offset(120f, 160f), zoom.toPage(Offset(240f, 320f)))
        assertEquals(Offset(240f, 320f), zoom.toScreen(Offset(120f, 160f)))
        assertEquals(Offset(0f, 800f), zoom.toPage(Offset(-20f, 1900f)))
    }

    @Test fun `native selection preserves copied unicode text and individual line bounds`() {
        val platform = PageSelection(0, SelectionBoundary(Point(25, 32)), SelectionBoundary(Point(98, 60)),
            listOf(PdfPageTextContent("日本語\nSecond line", listOf(RectF(25f, 20f, 90f, 36f), RectF(25f, 44f, 98f, 60f)))))
        val selected = PdfTextApi.fromPlatform(platform)!!
        assertEquals("日本語\nSecond line", selected.text)
        assertEquals(2, selected.bounds.size)
        assertEquals(Offset(25f, 36f), requireNotNull(selected.handleAnchor(true)))
        assertEquals(Offset(98f, 60f), requireNotNull(selected.handleAnchor(false)))
    }

    @Test fun `empty native selection is not offered as copyable text`() {
        val platform = PageSelection(0, SelectionBoundary(0), SelectionBoundary(0), listOf(PdfPageTextContent("")))
        assertNull(PdfTextApi.fromPlatform(platform))
    }

    @Test fun `RTL handles without native points attach to the correct text edges`() {
        val selection = PdfTextSelection("עברית", listOf(Rect(20f, 10f, 100f, 30f)),
            PdfTextBoundary(index = 0, rtl = true), PdfTextBoundary(index = 5, rtl = true))
        assertEquals(Offset(100f, 30f), requireNotNull(selection.handleAnchor(true)))
        assertEquals(Offset(20f, 30f), requireNotNull(selection.handleAnchor(false)))
        assertEquals(false, nearestPdfHandle(Offset(21f, 31f), Offset(100f, 30f), Offset(20f, 30f), 24f))
        assertNull(nearestPdfHandle(Offset(150f, 100f), Offset(100f, 30f), Offset(20f, 30f), 24f))
    }

    @Test fun `long press selects the word at the corresponding page point`() {
        val image = bitmap
        val requests = mutableListOf<Pair<PdfTextBoundary, PdfTextBoundary>>()
        compose.setContent {
            MaterialTheme {
                PdfSelectablePage(image, 1, 600, 800, true, null,
                    onSelect = { start, end, _ -> requests += start to end },
                    modifier = Modifier.size(300.dp, 400.dp).testTag("page"))
            }
        }
        compose.onNodeWithTag("page").performTouchInput { longClick(Offset(width * .25f, height * .25f)) }
        compose.runOnIdle {
            assertTrue(requests.isNotEmpty())
            assertEquals(requests.first().first, requests.first().second)
            assertEquals(150f, requests.first().first.point!!.x, 1f)
            assertEquals(200f, requests.first().first.point!!.y, 1f)
        }
    }

    @Test fun `ordinary drags still scroll and do not start selecting`() {
        val image = bitmap
        lateinit var scroll: ScrollState
        var selections = 0
        compose.setContent {
            MaterialTheme {
                scroll = rememberScrollState()
                Box(Modifier.size(300.dp, 250.dp).testTag("viewport").fastVerticalScroll(scroll)) {
                    PdfSelectablePage(image, 1, 600, 800, true, null,
                        onSelect = { _, _, _ -> selections++ }, modifier = Modifier.size(300.dp, 800.dp))
                }
            }
        }
        compose.onNodeWithTag("viewport").performTouchInput {
            swipe(Offset(width * .5f, height * .8f), Offset(width * .5f, height * .2f), durationMillis = 150)
        }
        compose.runOnIdle { assertTrue(scroll.value > 0); assertEquals(0, selections) }
    }

    @Test fun `a long press after scrolling maps through the page local coordinates once`() {
        val image = bitmap
        lateinit var scroll: ScrollState
        var pageSize = IntSize.Zero
        var viewportSize = IntSize.Zero
        var selected: Offset? = null
        compose.setContent {
            MaterialTheme {
                scroll = rememberScrollState()
                Box(Modifier.size(300.dp, 250.dp).onSizeChanged { viewportSize = it }
                    .testTag("viewport").fastVerticalScroll(scroll)) {
                    PdfSelectablePage(image, 1, 600, 800, true, null,
                        onSelect = { start, _, _ -> selected = start.point },
                        modifier = Modifier.size(300.dp, 800.dp).onSizeChanged { pageSize = it })
                }
            }
        }
        compose.runOnIdle { scroll.dispatchRawDelta(200f) }
        compose.onNodeWithTag("viewport").performTouchInput { longClick(center) }
        compose.runOnIdle {
            assertNotNull(selected)
            assertEquals(300f, selected!!.x, 1f)
            assertEquals((scroll.value + viewportSize.height / 2f) * 800 / pageSize.height, selected!!.y, 1f)
        }
    }

    @Test fun `handle drag keeps the other boundary fixed and compensates for the handle stem`() {
        val image = bitmap
        var density = 1f
        var pageSize = IntSize.Zero
        val initial = PdfTextSelection("selected", listOf(Rect(100f, 80f, 200f, 110f)),
            PdfTextBoundary(index = 2, point = Offset(100f, 100f)),
            PdfTextBoundary(index = 9, point = Offset(200f, 100f)))
        var request: Pair<PdfTextBoundary, PdfTextBoundary>? = null
        compose.setContent {
            MaterialTheme {
                density = LocalDensity.current.density
                PdfSelectablePage(image, 1, 600, 800, true, initial,
                    onSelect = { start, end, _ -> request = start to end },
                    modifier = Modifier.size(300.dp, 400.dp).onSizeChanged { pageSize = it }.testTag("page"))
            }
        }
        compose.onNodeWithTag("page").performTouchInput {
            val start = Offset(pageSize.width * 200f / 600, pageSize.height * 110f / 800 + 9f * density)
            down(start)
            advanceEventTime(50)
            moveTo(start + Offset(pageSize.width * .1f, 0f))
            up()
        }
        compose.runOnIdle {
            assertNotNull(request)
            assertEquals(2, request!!.first.index)
            assertEquals(260f, request!!.second.point!!.x, 1f)
            assertEquals(100f, request!!.second.point!!.y, 1f)
        }
    }
}
