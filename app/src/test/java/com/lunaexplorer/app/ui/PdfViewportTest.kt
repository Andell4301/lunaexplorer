package com.lunaexplorer.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.model.PdfDirection
import com.lunaexplorer.app.model.PdfPageLayout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfViewportTest {
    @get:Rule val compose = createComposeRule()

    private fun state(direction: PdfDirection = PdfDirection.HORIZONTAL,
        layout: PdfPageLayout = PdfPageLayout.SINGLE, count: Int = 4) =
        PdfViewportState(List(count) { IntSize(300, 400) }, direction, layout)

    @Test fun `pinch keeps the touched page point under the moving fingers`() {
        val state = state()
        state.updateViewport(IntSize(300, 400))
        val focus = Offset(220f, 290f)
        val pan = Offset(-12f, -18f)
        val before = state.bounds(0)
        val local = focus - before.topLeft
        state.transform(focus, pan, 2f)
        val after = state.bounds(0)
        assertEquals(focus.x + pan.x, after.left + local.x * 2, .01f)
        assertEquals(focus.y + pan.y, after.top + local.y * 2, .01f)
    }

    @Test fun `two finger pinch anchors the page away from the viewport centre`() {
        val state = state()
        compose.setContent {
            PdfViewport(state, true, true, Modifier.size(300.dp, 400.dp).testTag("viewport")) {
                Box(Modifier.fillMaxSize())
            }
        }
        var focus = Offset.Zero
        var pan = Offset.Zero
        val before = state.bounds(0)
        compose.onNodeWithTag("viewport").performTouchInput {
            focus = Offset(width * .65f, height * .65f)
            pan = Offset(-width * .04f, -height * .05f)
            val start = Offset(width * .06f, 0f)
            pinch(focus - start, focus - start * 2f + pan,
                focus + start, focus + start * 2f + pan, durationMillis = 300)
        }
        compose.runOnIdle {
            val local = focus - before.topLeft
            val after = state.bounds(0)
            assertEquals(2f, state.zoom, .01f)
            assertEquals(focus.x + pan.x, after.left + local.x * 2, 1f)
            assertEquals(focus.y + pan.y, after.top + local.y * 2, 1f)
            assertEquals(0, state.page)
        }
    }

    @Test fun `continuous pinch keeps its anchor on a later page in both directions`() {
        PdfDirection.entries.forEach { direction ->
            val state = state(direction, PdfPageLayout.CONTINUOUS)
            state.updateViewport(IntSize(300, 400))
            state.goToPage(2)
            val focus = Offset(160f, 230f)
            val before = state.bounds(2)
            val local = focus - before.topLeft
            state.transform(focus, Offset.Zero, 1.8f)
            val after = state.bounds(2)
            assertEquals(focus.x, after.left + local.x * 1.8f, .01f)
            assertEquals(focus.y, after.top + local.y * 1.8f, .01f)
        }
    }

    @Test fun `one drag can turn from horizontal to vertical while zoomed`() {
        val state = state()
        compose.setContent {
            PdfViewport(state, true, true, Modifier.size(300.dp, 400.dp).testTag("viewport")) {
                Box(Modifier.fillMaxSize())
            }
        }
        compose.runOnIdle { state.zoomBy(3f) }
        val before = state.bounds(0)
        compose.onNodeWithTag("viewport").performTouchInput {
            down(center)
            moveTo(center + Offset(65f, 0f))
            moveTo(center + Offset(65f, 70f))
            advanceEventTime(300)
            up()
        }
        compose.runOnIdle {
            val after = state.bounds(0)
            assertEquals(65f, after.left - before.left, 1f)
            assertEquals(70f, after.top - before.top, 1f)
            assertEquals(0, state.page)
        }
    }

    @Test fun `mouse wheel pans both axes while page gestures are disabled`() {
        val state = state()
        compose.setContent {
            PdfViewport(state, false, false, Modifier.size(300.dp, 400.dp).testTag("viewport")) {
                Box(Modifier.fillMaxSize())
            }
        }
        compose.runOnIdle { state.zoomBy(3f) }
        val before = state.bounds(0)
        compose.onNodeWithTag("viewport").performMouseInput {
            moveTo(center)
            scroll(Offset(1f, 1f))
        }
        compose.runOnIdle {
            val after = state.bounds(0)
            assertTrue(after.left < before.left)
            assertTrue(after.top < before.top)
            assertEquals(0, state.page)
        }
    }

    @Test fun `tap and swipe page controls can each be disabled independently`() {
        val state = state()
        val tap = mutableStateOf(true)
        val swipe = mutableStateOf(true)
        compose.setContent {
            PdfViewport(state, tap.value, swipe.value, Modifier.size(300.dp, 400.dp).testTag("viewport")) {
                Box(Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithTag("viewport").performTouchInput { click(Offset(width * .9f, height / 2f)) }
        compose.runOnIdle { assertEquals(1, state.page); tap.value = false }
        compose.onNodeWithTag("viewport").performTouchInput { click(Offset(width * .9f, height / 2f)) }
        compose.runOnIdle { assertEquals(1, state.page) }
        compose.onNodeWithTag("viewport").performTouchInput {
            swipe(Offset(width * .8f, height / 2f), Offset(width * .2f, height / 2f))
        }
        compose.runOnIdle { assertEquals(2, state.page); tap.value = true; swipe.value = false }
        compose.onNodeWithTag("viewport").performTouchInput {
            swipe(Offset(width * .8f, height / 2f), Offset(width * .2f, height / 2f))
        }
        compose.runOnIdle { assertEquals(2, state.page) }
        compose.onNodeWithTag("viewport").performTouchInput { click(Offset(width * .1f, height / 2f)) }
        compose.runOnIdle { assertEquals(1, state.page) }
    }

    @Test fun `vertical single pages turn with vertical taps and swipes`() {
        val state = state(PdfDirection.VERTICAL)
        compose.setContent {
            PdfViewport(state, true, true, Modifier.size(300.dp, 400.dp).testTag("viewport")) {
                Box(Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithTag("viewport").performTouchInput { click(Offset(width / 2f, height * .9f)) }
        compose.runOnIdle { assertEquals(1, state.page) }
        compose.onNodeWithTag("viewport").performTouchInput {
            swipe(Offset(width / 2f, height * .8f), Offset(width / 2f, height * .2f))
        }
        compose.runOnIdle { assertEquals(2, state.page) }
    }

    @Test fun `continuous pages can remain partly visible and pan through either axis`() {
        PdfDirection.entries.forEach { direction ->
            val state = state(direction, PdfPageLayout.CONTINUOUS)
            state.updateViewport(IntSize(300, 400))
            val delta = if (direction == PdfDirection.HORIZONTAL) Offset(-180f, 0f) else Offset(0f, -240f)
            state.panBy(delta, true)
            val first = state.bounds(0)
            val second = state.bounds(1)
            assertTrue(state.visiblePages().containsAll(listOf(0, 1)))
            if (direction == PdfDirection.HORIZONTAL) {
                assertTrue(first.left < 0); assertTrue(second.left in 0f..300f)
            } else {
                assertTrue(first.top < 0); assertTrue(second.top in 0f..400f)
            }
            assertEquals(1, state.page)
        }
    }

    @Test fun `disabling page swipes still permits zoomed panning within the current page`() {
        val state = state(PdfDirection.VERTICAL, PdfPageLayout.CONTINUOUS)
        state.updateViewport(IntSize(300, 400))
        state.goToPage(1)
        state.zoomBy(3f)
        val before = state.bounds(1)
        state.panBy(Offset(40f, -60f), false)
        val after = state.bounds(1)
        assertEquals(40f, after.left - before.left, .01f)
        assertEquals(-60f, after.top - before.top, .01f)
        state.panBy(Offset(0f, -10_000f), false)
        assertEquals(1, state.page)
        assertEquals(400f, state.bounds(1).bottom, .01f)
    }
}
