package com.lunaexplorer.app.ui

import android.view.ViewConfiguration as AndroidViewConfiguration

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewConfigurationCompat
import com.lunaexplorer.app.model.PdfDirection
import com.lunaexplorer.app.model.PdfPageLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal class PdfViewportState(
    private val pageSizes: List<IntSize>,
    val direction: PdfDirection,
    val layout: PdfPageLayout,
    initialPage: Int = 0,
) {
    var page by mutableIntStateOf(initialPage.coerceIn(0, (pageSizes.size - 1).coerceAtLeast(0)))
        private set
    var zoom by mutableFloatStateOf(1f)
        private set
    private var viewport by mutableStateOf(IntSize.Zero)
    private var scrollX by mutableStateOf(0.0)
    private var scrollY by mutableStateOf(0.0)
    var motionGeneration = 0
        private set
    private var gap = 8f
    private var fitted by mutableStateOf(emptyList<Size>())
    private var starts = DoubleArray(0)
    private val vertical get() = direction == PdfDirection.VERTICAL
    private val continuous get() = layout == PdfPageLayout.CONTINUOUS
    private val contentWidth: Double get() = if (continuous) {
        if (vertical) viewport.width.toDouble() else starts.lastOrNull() ?: 0.0
    } else fitted.getOrNull(page)?.width?.toDouble() ?: 0.0
    private val contentHeight: Double get() = if (continuous) {
        if (vertical) starts.lastOrNull() ?: 0.0 else viewport.height.toDouble()
    } else fitted.getOrNull(page)?.height?.toDouble() ?: 0.0
    private val originX get() = ((viewport.width - contentWidth * zoom) / 2).coerceAtLeast(0.0)
    private val originY get() = ((viewport.height - contentHeight * zoom) / 2).coerceAtLeast(0.0)

    fun updateViewport(size: IntSize, pageGap: Float = gap) {
        if (viewport == size && gap == pageGap) return
        motionGeneration++
        val previous = if (fitted.isEmpty()) null else bounds(page)
        val centre = Offset(viewport.width / 2f, viewport.height / 2f)
        val fraction = previous?.let { (centre - it.topLeft).let { p -> Offset(p.x / it.width, p.y / it.height) } }
        viewport = size
        gap = pageGap
        if (size.width <= 0 || size.height <= 0) return
        fitted = pageSizes.map {
            val factor = if (continuous) {
                if (vertical) size.width.toFloat() / it.width else size.height.toFloat() / it.height
            } else minOf(size.width.toFloat() / it.width, size.height.toFloat() / it.height)
            val bounded = minOf(factor, 16_000f / maxOf(it.width, it.height))
            Size((it.width * bounded).coerceAtLeast(1f), (it.height * bounded).coerceAtLeast(1f))
        }
        starts = DoubleArray(fitted.size + 1)
        fitted.forEachIndexed { index, fittedSize ->
            starts[index + 1] = starts[index] + (if (vertical) fittedSize.height else fittedSize.width) +
                if (index == fitted.lastIndex) 0f else gap
        }
        if (fraction == null) positionPage(page)
        else {
            val rect = documentBounds(page)
            scrollX = originX + (rect.left + rect.width * fraction.x) * zoom - size.width / 2.0
            scrollY = originY + (rect.top + rect.height * fraction.y) * zoom - size.height / 2.0
            boundScroll()
        }
    }

    fun goToPage(index: Int) {
        motionGeneration++
        page = index.coerceIn(0, (pageSizes.size - 1).coerceAtLeast(0))
        zoom = 1f
        positionPage(page)
    }

    private fun positionPage(index: Int) {
        scrollX = if (continuous && !vertical) starts.getOrElse(index) { 0.0 } * zoom else 0.0
        scrollY = if (continuous && vertical) starts.getOrElse(index) { 0.0 } * zoom else 0.0
        boundScroll()
    }

    fun zoomBy(factor: Float) {
        motionGeneration++
        transform(Offset(viewport.width / 2f, viewport.height / 2f), Offset.Zero, factor)
    }

    fun scrollMetrics(horizontal: Boolean): FastScrollMetrics = if (horizontal) {
        FastScrollMetrics(scrollX.toFloat(), viewport.width.toFloat(), (contentWidth * zoom).toFloat())
    } else FastScrollMetrics(scrollY.toFloat(), viewport.height.toFloat(), (contentHeight * zoom).toFloat())

    fun scrollToFraction(fraction: Float, horizontal: Boolean) {
        motionGeneration++
        if (horizontal) scrollX = (contentWidth * zoom - viewport.width).coerceAtLeast(0.0) * fraction.coerceIn(0f, 1f)
        else scrollY = (contentHeight * zoom - viewport.height).coerceAtLeast(0.0) * fraction.coerceIn(0f, 1f)
        updatePage()
    }

    fun transform(centroid: Offset, pan: Offset, zoomChange: Float) {
        val next = (zoom * zoomChange).coerceIn(1f, 6f)
        val ratio = next / zoom
        val previousOriginX = originX
        val previousOriginY = originY
        zoom = next
        scrollX = (scrollX + centroid.x - previousOriginX) * ratio - centroid.x - pan.x + originX
        scrollY = (scrollY + centroid.y - previousOriginY) * ratio - centroid.y - pan.y + originY
        boundScroll()
        updatePage()
    }

    fun panBy(delta: Offset, allowPageChange: Boolean): Offset {
        val beforeX = scrollX
        val beforeY = scrollY
        scrollX -= delta.x
        scrollY -= delta.y
        boundScroll()
        if (continuous && !allowPageChange && fitted.isNotEmpty()) {
            val start = starts[page] * zoom
            val length = (if (vertical) fitted[page].height else fitted[page].width) * zoom
            val previous = if (vertical) beforeY else beforeX
            val extent = if (vertical) viewport.height else viewport.width
            val minimum = minOf(previous, start)
            val maximum = maxOf(previous, start + length - extent)
            if (vertical) scrollY = scrollY.coerceIn(minimum, maximum)
            else scrollX = scrollX.coerceIn(minimum, maximum)
        }
        if (allowPageChange) updatePage()
        return Offset((beforeX - scrollX).toFloat(), (beforeY - scrollY).toFloat())
    }

    private fun boundScroll() {
        scrollX = scrollX.coerceIn(0.0, (contentWidth * zoom - viewport.width).coerceAtLeast(0.0))
        scrollY = scrollY.coerceIn(0.0, (contentHeight * zoom - viewport.height).coerceAtLeast(0.0))
    }

    private fun updatePage() {
        if (!continuous || fitted.isEmpty()) return
        val centre = if (vertical) (scrollY + viewport.height / 2.0 - originY) / zoom
            else (scrollX + viewport.width / 2.0 - originX) / zoom
        page = pageAt(centre)
    }

    private fun pageAt(position: Double): Int {
        var low = 0
        var high = pageSizes.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (starts[middle] <= position) low = middle + 1 else high = middle
        }
        return (low - 1).coerceIn(0, pageSizes.lastIndex.coerceAtLeast(0))
    }

    private fun documentBounds(index: Int): Rect {
        val size = fitted.getOrNull(index) ?: return Rect.Zero
        val x = if (!continuous) 0f else if (vertical) (viewport.width - size.width) / 2 else starts[index].toFloat()
        val y = if (!continuous) 0f else if (vertical) starts[index].toFloat() else (viewport.height - size.height) / 2
        return Rect(Offset(x, y), size)
    }

    fun bounds(index: Int): Rect {
        val size = fitted.getOrNull(index) ?: return Rect.Zero
        val x = if (!continuous) 0.0 else if (vertical) (viewport.width - size.width) / 2.0 else starts[index]
        val y = if (!continuous) 0.0 else if (vertical) starts[index] else (viewport.height - size.height) / 2.0
        return Rect(Offset((originX + x * zoom - scrollX).toFloat(), (originY + y * zoom - scrollY).toFloat()),
            Size(size.width * zoom, size.height * zoom))
    }

    fun visiblePages(): List<Int> {
        if (fitted.isEmpty()) return emptyList()
        if (!continuous) return listOf(page)
        val start = if (vertical) (scrollY - originY) / zoom else (scrollX - originX) / zoom
        val end = start + (if (vertical) viewport.height else viewport.width) / zoom
        return (pageAt(start)..pageAt(end)).toList()
    }

    fun fittedSize(index: Int): IntSize = fitted.getOrNull(index)?.let {
        IntSize(it.width.roundToInt().coerceAtLeast(1), it.height.roundToInt().coerceAtLeast(1))
    } ?: IntSize.Zero

    fun turn(delta: Int) { if (page + delta in pageSizes.indices) goToPage(page + delta) }
}

@Composable
internal fun PdfViewport(
    state: PdfViewportState,
    tapToTurn: Boolean,
    swipeToTurn: Boolean,
    modifier: Modifier = Modifier,
    pageContent: @Composable (index: Int) -> Unit,
) {
    val currentTap by rememberUpdatedState(tapToTurn)
    val currentSwipe by rememberUpdatedState(swipeToTurn)
    val context = LocalContext.current
    val wheelFactors = remember(context) {
        val configuration = AndroidViewConfiguration.get(context)
        Offset(ViewConfigurationCompat.getScaledHorizontalScrollFactor(configuration, context),
            ViewConfigurationCompat.getScaledVerticalScrollFactor(configuration, context))
    }
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Offset>()
    var fling by remember(state) { mutableStateOf<Job?>(null) }
    DisposableEffect(state) { onDispose { fling?.cancel() } }
    val gap = with(LocalDensity.current) { 8.dp.toPx() }
    val visible = state.visiblePages()
    Layout(content = {
        visible.forEach { index -> key(index) { Box { pageContent(index) } } }
    }, modifier = modifier.clipToBounds().onSizeChanged { state.updateViewport(it, gap) }
        .fastScrollbar({ state.scrollMetrics(false) }, { state.scrollToFraction(it, false) })
        .fastScrollbar({ state.scrollMetrics(true) }, { state.scrollToFraction(it, true) }, horizontal = true)
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction("Previous page") { state.turn(-1); true },
                CustomAccessibilityAction("Next page") { state.turn(1); true },
                CustomAccessibilityAction("Zoom in") { state.zoomBy(1.3f); true },
                CustomAccessibilityAction("Zoom out") { state.zoomBy(1 / 1.3f); true },
            )
            scrollBy { x, y -> state.panBy(Offset(-x, -y), currentSwipe) != Offset.Zero }
        }
        .pointerInput(state, wheelFactors) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll || event.changes.any { it.isConsumed }) continue
                    fling?.cancel()
                    val delta = event.changes.fold(Offset.Zero) { sum, change -> sum + change.scrollDelta }
                    val consumed = state.panBy(Offset(-delta.x * wheelFactors.x, -delta.y * wheelFactors.y), currentSwipe)
                    if (consumed != Offset.Zero) event.changes.forEach { it.consume() }
                }
            }
        }
        .pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                fling?.cancel()
                if (down.isConsumed) return@awaitEachGesture
                var claimed = false
                var pinched = false
                var total = Offset.Zero
                val startZoom = state.zoom
                val velocity = VelocityTracker()
                velocity.addPosition(down.uptimeMillis, down.position)
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    event.changes.firstOrNull { it.id == down.id }?.let { velocity.addPosition(it.uptimeMillis, it.position) }
                    if (event.changes.any { it.isConsumed }) break
                    if (pressed == 0) {
                        if (!claimed && currentTap && event.changes.first().uptimeMillis - down.uptimeMillis <
                            viewConfiguration.longPressTimeoutMillis) {
                            val position = if (state.direction == PdfDirection.VERTICAL) down.position.y else down.position.x
                            val extent = if (state.direction == PdfDirection.VERTICAL) size.height else size.width
                            if (position < extent / 3f) state.turn(-1)
                            else if (position > extent * 2f / 3f) state.turn(1)
                        } else if (claimed && !pinched) {
                            if (state.layout == PdfPageLayout.SINGLE && startZoom <= 1f && currentSwipe) {
                                val primary = if (state.direction == PdfDirection.VERTICAL) total.y else total.x
                                val cross = if (state.direction == PdfDirection.VERTICAL) total.x else total.y
                                val extent = if (state.direction == PdfDirection.VERTICAL) size.height else size.width
                                if (abs(primary) > maxOf(viewConfiguration.touchSlop * 2, extent * .12f) && abs(primary) > abs(cross)) {
                                    state.turn(if (primary < 0) 1 else -1)
                                }
                            } else {
                                val speed = velocity.calculateVelocity()
                                val initial = Offset(speed.x.coerceIn(-20_000f, 20_000f), speed.y.coerceIn(-20_000f, 20_000f))
                                val generation = state.motionGeneration
                                fling = scope.launch {
                                    var previous = Offset.Zero
                                    AnimationState(Offset.VectorConverter, Offset.Zero, initial).animateDecay(decay) {
                                        if (generation != state.motionGeneration) { cancelAnimation(); return@animateDecay }
                                        val delta = value - previous
                                        previous = value
                                        val consumed = state.panBy(delta, currentSwipe)
                                        if (consumed.getDistance() < .1f && delta.getDistance() > .5f) cancelAnimation()
                                    }
                                }
                            }
                        }
                        break
                    }
                    val pan = event.calculatePan()
                    total += pan
                    if (pressed >= 2) {
                        claimed = true
                        pinched = true
                        state.transform(event.calculateCentroid(useCurrent = false), pan, event.calculateZoom())
                    } else {
                        var movement = pan
                        if (!claimed) {
                            if (total.getDistance() <= viewConfiguration.touchSlop) continue
                            claimed = true
                            movement = total
                        }
                        if (state.layout == PdfPageLayout.CONTINUOUS || state.zoom > 1f) state.panBy(movement, currentSwipe)
                    }
                    event.changes.forEach { it.consume() }
                }
            }
        }) { measurables, constraints ->
        val placeables = measurables.mapIndexed { index, measurable ->
            val size = state.fittedSize(visible[index])
            measurable.measure(Constraints.fixed(size.width, size.height))
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val bounds = state.bounds(visible[index])
                placeable.placeWithLayer(bounds.left.roundToInt(), bounds.top.roundToInt()) {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = bounds.width / placeable.width
                    scaleY = bounds.height / placeable.height
                }
            }
        }
    }
}
