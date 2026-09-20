package com.lunaexplorer.app.ui

import androidx.compose.foundation.ScrollIndicatorState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal data class FastScrollMetrics(val offset: Float, val viewport: Float, val content: Float) {
    // ScrollState.maxValue is Int.MAX_VALUE until measured.
    val scrollable: Boolean get() = viewport > 0 && content > viewport && content < Int.MAX_VALUE.toFloat()
    val fraction: Float get() = if (scrollable) (offset / (content - viewport)).coerceIn(0f, 1f) else 0f
}

internal data class FastScrollThumb(val start: Float, val length: Float, val travel: Float) {
    fun fractionAt(pointer: Float, grabOffset: Float): Float =
        if (travel > 0) ((pointer - grabOffset) / travel).coerceIn(0f, 1f) else 0f
}

internal fun fastScrollThumb(metrics: FastScrollMetrics, track: Float, minimum: Float): FastScrollThumb? {
    if (!metrics.scrollable || track <= 0) return null
    val length = (track * metrics.viewport / metrics.content).coerceIn(minimum.coerceAtMost(track * .6f), track)
    val travel = track - length
    return FastScrollThumb(metrics.fraction * travel, length, travel)
}

private fun ScrollIndicatorState?.metrics(): FastScrollMetrics = this?.let {
    FastScrollMetrics(it.scrollOffset.toFloat(), it.viewportSize.toFloat(), it.contentSize.toFloat())
} ?: FastScrollMetrics(0f, 0f, 0f)

@Composable
private fun Modifier.scrollbarFor(state: ScrollState, horizontal: Boolean = false): Modifier {
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    return fastScrollbar(metrics = {
        FastScrollMetrics(state.value.toFloat(), state.viewportSize.toFloat(), state.viewportSize.toFloat() + state.maxValue)
    }, onScrollToFraction = { fraction ->
        scrollJob?.cancel()
        scrollJob = scope.launch { state.scrollTo((fraction * state.maxValue).roundToInt()) }
    }, horizontal = horizontal)
}

/** verticalScroll plus a scrollbar. The scrollbar goes first so the thumb stays on the viewport. */
internal fun Modifier.fastVerticalScroll(state: ScrollState): Modifier =
    composed { scrollbarFor(state).verticalScroll(state) }

internal fun Modifier.fastHorizontalScroll(state: ScrollState): Modifier =
    composed { scrollbarFor(state, horizontal = true).horizontalScroll(state) }

/** Scrollbar only, for components such as DropdownMenu that apply their own scroll modifier. */
internal fun Modifier.fastScroll(state: ScrollState): Modifier = composed { scrollbarFor(state) }

internal fun Modifier.fastScroll(state: PagerState): Modifier = fastScrollbar(horizontal = true,
    metrics = { FastScrollMetrics(state.currentPage + state.currentPageOffsetFraction, 1f, state.pageCount.toFloat()) },
    onScrollToFraction = { fraction ->
        if (state.pageCount > 0) state.requestScrollToPage((fraction * (state.pageCount - 1)).roundToInt())
    },
)

@Composable
internal fun FastDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberScrollState()
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest,
        modifier = Modifier.fastScroll(state), scrollState = state, content = content)
}

/** Both scrollbars come before both scroll modifiers so the thumbs stay on the viewport. */
internal fun Modifier.fastTwoDimensionalScroll(vertical: ScrollState, horizontal: ScrollState?): Modifier = composed {
    val bars = scrollbarFor(vertical)
        .then(if (horizontal == null) Modifier else Modifier.scrollbarFor(horizontal, horizontal = true))
    bars.verticalScroll(vertical).then(if (horizontal == null) Modifier else Modifier.horizontalScroll(horizontal))
}

internal fun Modifier.fastScroll(state: LazyListState): Modifier = fastScrollbar(
    metrics = { state.scrollIndicatorState.metrics() },
    onScrollToFraction = { fraction ->
        val info = state.layoutInfo
        val count = info.totalItemsCount
        if (count > 0) {
            val metrics = state.scrollIndicatorState.metrics()
            val average = (metrics.content / count).coerceAtLeast(1f)
            val position = fraction * (metrics.content - metrics.viewport).coerceAtLeast(0f)
            val index = if (fraction >= 1f) count - 1 else (position / average).toInt().coerceIn(0, count - 1)
            val offset = if (fraction >= 1f) 0 else (position - index * average).roundToInt().coerceAtLeast(0)
            state.requestScrollToItem(index, offset)
        }
    },
)

internal fun Modifier.fastScroll(state: LazyGridState): Modifier = fastScrollbar(
    metrics = { state.scrollIndicatorState.metrics() },
    onScrollToFraction = { fraction ->
        val count = state.layoutInfo.totalItemsCount
        if (count > 0) {
            val metrics = state.scrollIndicatorState.metrics()
            val visibleShare = if (metrics.content > 0) metrics.viewport / metrics.content else 0f
            val index = if (fraction >= 1f) count - 1 else
                (fraction * count * (1f - visibleShare)).toInt().coerceIn(0, count - 1)
            state.requestScrollToItem(index)
        }
    },
)

@Composable
internal fun FastLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyListScope.() -> Unit,
) = LazyColumn(modifier = modifier.fastScroll(state), state = state, contentPadding = contentPadding, content = content)

@Composable
internal fun FastLazyVerticalGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: LazyGridScope.() -> Unit,
) = LazyVerticalGrid(
    columns = columns, modifier = modifier.fastScroll(state), state = state, contentPadding = contentPadding,
    verticalArrangement = verticalArrangement, horizontalArrangement = horizontalArrangement, content = content,
)

/**
 * Draggable scrollbar. Only gestures that start on the thumb are intercepted; the horizontal hit area is just
 * the bottom 12dp so taps in compact strips still land. [metrics] is read in the draw phase, so scrolling
 * does not recompose.
 */
internal fun Modifier.fastScrollbar(
    metrics: () -> FastScrollMetrics,
    onScrollToFraction: (Float) -> Unit,
    horizontal: Boolean = false,
): Modifier = composed {
    val latestMetrics by rememberUpdatedState(metrics)
    val latestScroll by rememberUpdatedState(onScrollToFraction)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var dragging by remember { mutableStateOf(false) }
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = if (dragging) 1f else .7f)
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .10f)
    this
        .drawWithContent {
            drawContent()
            val track = if (horizontal) size.width else size.height
            val thumb = fastScrollThumb(latestMetrics(), track, 48.dp.toPx()) ?: return@drawWithContent
            val thickness = (if (dragging) 8.dp else 5.dp).toPx()
            val inset = 3.dp.toPx()
            val cross = if (horizontal) size.height - inset - thickness else
                if (rtl) inset else size.width - inset - thickness
            val start = if (horizontal && rtl) thumb.travel - thumb.start else thumb.start
            val radius = CornerRadius(thickness / 2)
            drawRoundRect(trackColor,
                topLeft = if (horizontal) Offset(0f, cross) else Offset(cross, 0f),
                size = if (horizontal) Size(track, thickness) else Size(thickness, track), cornerRadius = radius)
            drawRoundRect(thumbColor,
                topLeft = if (horizontal) Offset(start, cross) else Offset(cross, start),
                size = if (horizontal) Size(thumb.length, thickness) else Size(thickness, thumb.length), cornerRadius = radius)
        }
        .pointerInput(horizontal, rtl) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val track = if (horizontal) size.width.toFloat() else size.height.toFloat()
                val thumb = fastScrollThumb(latestMetrics(), track, 48.dp.toPx()) ?: return@awaitEachGesture
                val cross = if (horizontal) down.position.y else down.position.x
                val crossSize = if (horizontal) size.height else size.width
                val hitDepth = (if (horizontal) 12.dp else 24.dp).toPx()
                val onEdge = if (!horizontal && rtl) cross <= hitDepth else cross >= crossSize - hitDepth
                val start = if (horizontal && rtl) thumb.travel - thumb.start else thumb.start
                val pointer = if (horizontal) down.position.x else down.position.y
                if (!onEdge || pointer < start - 4.dp.toPx() || pointer > start + thumb.length + 4.dp.toPx()) {
                    return@awaitEachGesture
                }
                val grab = pointer - start
                down.consume()
                dragging = true
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) { change.consume(); break }
                        val position = if (horizontal) change.position.x else change.position.y
                        val visualFraction = thumb.fractionAt(position, grab)
                        latestScroll(if (horizontal && rtl) 1f - visualFraction else visualFraction)
                        change.consume()
                    } while (true)
                } finally { dragging = false }
            }
        }
        .semantics {
            if (latestMetrics().scrollable) customActions = listOf(
                CustomAccessibilityAction(if (horizontal) "Scroll to start" else "Scroll to top") { latestScroll(0f); true },
                CustomAccessibilityAction(if (horizontal) "Scroll to end" else "Scroll to bottom") { latestScroll(1f); true },
            )
        }
}
