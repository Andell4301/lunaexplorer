package com.lunaexplorer.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.model.PdfDirection
import com.lunaexplorer.app.model.PdfPageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfViewportSelectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `long press after zoom and pan selects the page point under the finger`() {
        val image = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).asImageBitmap()
        val state = PdfViewportState(List(3) { IntSize(600, 800) }, PdfDirection.HORIZONTAL, PdfPageLayout.SINGLE)
        lateinit var viewport: LayoutCoordinates
        lateinit var page: LayoutCoordinates
        var selected: Offset? = null
        compose.setContent {
            MaterialTheme {
                PdfViewport(state, tapToTurn = true, swipeToTurn = true,
                    modifier = Modifier.size(300.dp, 400.dp).testTag("viewport")
                        .onGloballyPositioned { viewport = it }) { index ->
                    PdfSelectablePage(image, index + 1, 600, 800, true, null,
                        onSelect = { start, _, _ -> selected = start.point },
                        displayScale = state.zoom,
                        modifier = Modifier.fillMaxSize().onGloballyPositioned { page = it })
                }
            }
        }
        compose.runOnIdle {
            state.zoomBy(2f)
            state.panBy(Offset(-35f, -50f), allowPageChange = false)
        }
        var expected = Offset.Zero
        compose.runOnIdle {
            val position = Offset(viewport.size.width * .6f, viewport.size.height * .55f)
            val local = page.localPositionOf(viewport, position)
            expected = Offset(local.x * 600 / page.size.width, local.y * 800 / page.size.height)
        }
        compose.onNodeWithTag("viewport").performTouchInput {
            longClick(Offset(width * .6f, height * .55f))
        }
        compose.runOnIdle {
            assertNotNull(selected)
            assertEquals(expected.x, selected!!.x, 1f)
            assertEquals(expected.y, selected!!.y, 1f)
            assertEquals(0, state.page)
        }
    }

    @Test fun `dragging a selection handle neither pans nor turns the zoomed page`() {
        val image = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).asImageBitmap()
        val state = PdfViewportState(List(3) { IntSize(600, 800) }, PdfDirection.HORIZONTAL, PdfPageLayout.SINGLE)
        val initial = PdfTextSelection("selected", listOf(Rect(260f, 360f, 340f, 400f)),
            PdfTextBoundary(index = 2, point = Offset(260f, 380f)),
            PdfTextBoundary(index = 9, point = Offset(340f, 380f)))
        lateinit var viewport: LayoutCoordinates
        lateinit var page: LayoutCoordinates
        var density = 1f
        var request: Pair<PdfTextBoundary, PdfTextBoundary>? = null
        compose.setContent {
            density = LocalDensity.current.density
            MaterialTheme {
                PdfViewport(state, tapToTurn = true, swipeToTurn = true,
                    modifier = Modifier.size(300.dp, 400.dp).testTag("viewport")
                        .onGloballyPositioned { viewport = it }) { index ->
                    PdfSelectablePage(image, index + 1, 600, 800, true, initial,
                        onSelect = { start, end, _ -> request = start to end },
                        displayScale = state.zoom,
                        modifier = Modifier.fillMaxSize().onGloballyPositioned { page = it })
                }
            }
        }
        compose.runOnIdle { state.zoomBy(2f) }
        var touch = Offset.Zero
        var before = Rect.Zero
        compose.runOnIdle {
            val handle = Offset(page.size.width * 340f / 600, page.size.height * 400f / 800 + 9f * density / state.zoom)
            touch = viewport.localPositionOf(page, handle)
            before = state.bounds(0)
        }
        compose.onNodeWithTag("viewport").performTouchInput {
            down(touch)
            advanceEventTime(50)
            moveTo(touch + Offset(35f, -20f))
            advanceEventTime(50)
            up()
        }
        compose.runOnIdle {
            assertNotNull(request)
            assertEquals(initial.start, request!!.first)
            assertTrue(request!!.second.point!!.x > initial.end.point!!.x)
            assertEquals(before, state.bounds(0))
            assertEquals(0, state.page)
        }
    }

    @Test fun `zoomed selection handles do not intercept drags outside their screen touch radius`() {
        val image = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).asImageBitmap()
        val state = PdfViewportState(List(3) { IntSize(600, 800) }, PdfDirection.HORIZONTAL, PdfPageLayout.SINGLE)
        val initial = PdfTextSelection("selected", listOf(Rect(260f, 360f, 340f, 400f)),
            PdfTextBoundary(index = 2, point = Offset(260f, 380f)),
            PdfTextBoundary(index = 9, point = Offset(340f, 380f)))
        lateinit var viewport: LayoutCoordinates
        lateinit var page: LayoutCoordinates
        var density = 1f
        var selections = 0
        compose.setContent {
            density = LocalDensity.current.density
            MaterialTheme {
                PdfViewport(state, tapToTurn = false, swipeToTurn = true,
                    modifier = Modifier.size(300.dp, 400.dp).testTag("viewport")
                        .onGloballyPositioned { viewport = it }) { index ->
                    PdfSelectablePage(image, index + 1, 600, 800, true, initial,
                        onSelect = { _, _, _ -> selections++ },
                        displayScale = state.zoom,
                        modifier = Modifier.fillMaxSize().onGloballyPositioned { page = it })
                }
            }
        }
        compose.runOnIdle { state.zoomBy(4f) }
        var touch = Offset.Zero
        var before = Rect.Zero
        var delta = Offset.Zero
        compose.runOnIdle {
            val handle = Offset(page.size.width * 340f / 600, page.size.height * 400f / 800 + 9f * density / state.zoom)
            touch = viewport.localPositionOf(page, handle) - Offset(40f * density, 0f)
            before = state.bounds(0)
            delta = Offset(-35f * density, -45f * density)
        }
        compose.onNodeWithTag("viewport").performTouchInput {
            down(touch)
            advanceEventTime(50)
            moveTo(touch + delta)
            advanceEventTime(300)
            up()
        }
        compose.runOnIdle {
            assertEquals(0, selections)
            assertEquals(delta.x, state.bounds(0).left - before.left, 1f)
            assertEquals(delta.y, state.bounds(0).top - before.top, 1f)
        }
    }

    @Test fun `long press targets the second visible page in a continuous stream`() {
        val image = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888).asImageBitmap()
        val state = PdfViewportState(List(3) { IntSize(600, 400) }, PdfDirection.VERTICAL, PdfPageLayout.CONTINUOUS)
        var selectedPage: Int? = null
        var selectedPoint: Offset? = null
        val pageCoordinates = mutableMapOf<Int, LayoutCoordinates>()
        lateinit var viewport: LayoutCoordinates
        compose.setContent {
            MaterialTheme {
                PdfViewport(state, tapToTurn = true, swipeToTurn = true,
                    modifier = Modifier.size(300.dp, 400.dp).testTag("viewport")
                        .onGloballyPositioned { viewport = it }) { index ->
                    PdfSelectablePage(image, index + 1, 600, 400, true, null,
                        onSelect = { start, _, _ -> selectedPage = index; selectedPoint = start.point },
                        modifier = Modifier.fillMaxSize().onGloballyPositioned { pageCoordinates[index] = it })
                }
            }
        }
        var expected = Offset.Zero
        compose.runOnIdle {
            val second = pageCoordinates.getValue(1)
            val position = Offset(viewport.size.width * .5f, viewport.size.height * .85f)
            val local = second.localPositionOf(viewport, position)
            expected = Offset(local.x * 600 / second.size.width, local.y * 400 / second.size.height)
        }
        compose.onNodeWithTag("viewport").performTouchInput {
            longClick(Offset(width * .5f, height * .85f))
        }
        compose.runOnIdle {
            assertEquals(1, selectedPage)
            assertNotNull(selectedPoint)
            assertEquals(expected.x, selectedPoint!!.x, 1f)
            assertEquals(expected.y, selectedPoint!!.y, 1f)
        }
    }
}
