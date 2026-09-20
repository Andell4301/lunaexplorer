package com.lunaexplorer.app.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FastScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `thumb drag traverses a long column and content taps still work`() {
        lateinit var state: ScrollState
        var taps = 0
        compose.setContent {
            MaterialTheme {
                state = rememberScrollState()
                Column(Modifier.size(300.dp).testTag("scroll").fastVerticalScroll(state)) {
                    repeat(200) { index ->
                        Text("Row $index", Modifier.fillMaxWidth().height(40.dp).clickable { taps++ })
                    }
                }
            }
        }
        compose.onNodeWithText("Row 0").performClick()
        assertEquals(1, taps)
        compose.onNodeWithTag("scroll").performTouchInput {
            swipe(Offset(width - 4f, 12f), Offset(width - 4f, height - 2f), durationMillis = 500)
        }
        compose.runOnIdle {
            assertEquals("Dragging to the track end must reach the final content", state.maxValue, state.value)
            assertEquals("Dragging the thumb must not click a row", 1, taps)
        }
    }

    @Test fun `list thumb jumps over hundreds of items and can return to the beginning`() {
        lateinit var state: LazyListState
        compose.setContent {
            MaterialTheme {
                state = rememberLazyListState()
                FastLazyColumn(Modifier.size(300.dp).testTag("scroll"), state = state) {
                    items(1_000) { Text("Row $it", Modifier.fillMaxWidth().height(40.dp)) }
                }
            }
        }
        compose.onNodeWithTag("scroll").performTouchInput {
            swipe(Offset(width - 4f, 12f), Offset(width - 4f, height - 1f), durationMillis = 500)
        }
        compose.runOnIdle {
            assertFalse(state.canScrollForward)
            assertTrue(state.firstVisibleItemIndex > 980)
        }
        val actions = compose.onNodeWithTag("scroll").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.first { it.label == "Scroll to top" }.action()) }
        compose.runOnIdle { assertEquals(0, state.firstVisibleItemIndex) }
    }

    @Test fun `grid thumb reaches the final row`() {
        lateinit var state: LazyGridState
        compose.setContent {
            MaterialTheme {
                state = rememberLazyGridState()
                FastLazyVerticalGrid(GridCells.Fixed(3), Modifier.size(300.dp).testTag("scroll"), state = state) {
                    items(1_000) { Text("Cell $it", Modifier.height(70.dp)) }
                }
            }
        }
        compose.onNodeWithTag("scroll").performTouchInput {
            swipe(Offset(width - 4f, 12f), Offset(width - 4f, height - 1f), durationMillis = 500)
        }
        compose.runOnIdle {
            assertFalse(state.canScrollForward)
            assertTrue(state.layoutInfo.visibleItemsInfo.any { it.index == 999 })
        }
    }

    @Test fun `horizontal thumb scrolls long rows`() {
        lateinit var state: ScrollState
        compose.setContent {
            MaterialTheme {
                state = rememberScrollState()
                Row(Modifier.width(300.dp).height(70.dp).testTag("scroll").fastHorizontalScroll(state)) {
                    repeat(100) { Text("Column $it", Modifier.width(100.dp)) }
                }
            }
        }
        compose.onNodeWithTag("scroll").performTouchInput {
            swipe(Offset(12f, height - 4f), Offset(width - 1f, height - 4f), durationMillis = 500)
        }
        compose.runOnIdle { assertEquals(state.maxValue, state.value) }
    }

    @Test fun `horizontal thumb does not steal center taps from short tab strips`() {
        var taps = 0
        compose.setContent {
            MaterialTheme {
                Row(Modifier.width(300.dp).testTag("strip").fastHorizontalScroll(rememberScrollState())) {
                    repeat(100) { index ->
                        Box(Modifier.width(80.dp).height(48.dp).testTag("tab$index").clickable { taps++ })
                    }
                }
            }
        }
        // performClick's semantic action would bypass the pointer overlap.
        compose.onNodeWithTag("tab0").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, taps) }
    }

    @Test fun `both document thumbs stay reachable without scrolling to the bottom first`() {
        lateinit var vertical: ScrollState
        lateinit var horizontal: ScrollState
        compose.setContent {
            MaterialTheme {
                vertical = rememberScrollState()
                horizontal = rememberScrollState()
                Box(Modifier.size(300.dp).testTag("scroll").fastTwoDimensionalScroll(vertical, horizontal)) {
                    Box(Modifier.size(3_000.dp))
                }
            }
        }
        compose.onNodeWithTag("scroll").performTouchInput {
            swipe(Offset(12f, height - 4f), Offset(width - 1f, height - 4f), durationMillis = 500)
        }
        compose.runOnIdle {
            assertEquals(horizontal.maxValue, horizontal.value)
            assertEquals(0, vertical.value)
        }
    }

    @Test fun `content that fits has no scrollbar interception`() {
        var taps = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.size(300.dp).testTag("scroll").fastVerticalScroll(rememberScrollState())) {
                    Box(Modifier.fillMaxWidth().height(60.dp).clickable { taps++ })
                }
            }
        }
        compose.onNodeWithTag("scroll").performTouchInput { click(Offset(width - 4f, 12f)) }
        compose.runOnIdle { assertEquals(1, taps) }
    }

    @Test fun `unknown content is hidden and even a short track has draggable travel`() {
        assertNull(fastScrollThumb(FastScrollMetrics(0f, 100f, Int.MAX_VALUE.toFloat()), 100f, 48f))
        assertNull(fastScrollThumb(FastScrollMetrics(0f, 100f, 100f), 100f, 48f))
        val thumb = fastScrollThumb(FastScrollMetrics(0f, 30f, 5_000f), 30f, 48f)!!
        assertTrue(thumb.travel > 0)
        assertEquals(1f, thumb.fractionAt(30f, 0f))
    }

    @Test fun `native debug log scrollbar jumps through long selectable content`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = FastScrollView(context)
        // ScrollView measures its child with an unspecified height, so a plain View needs minimumHeight.
        view.addView(View(context).apply { minimumHeight = 10_000 }, ViewGroup.LayoutParams(300, 10_000))
        view.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 300, 300)
        assertEquals(10_000, view.getChildAt(0).height)
        fun event(action: Int, y: Float) {
            val event = MotionEvent.obtain(0, 10, action, 298f, y, 0)
            try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, 12f)
        event(MotionEvent.ACTION_MOVE, 299f)
        event(MotionEvent.ACTION_UP, 299f)
        assertEquals(9_700, view.scrollY)
    }
}
