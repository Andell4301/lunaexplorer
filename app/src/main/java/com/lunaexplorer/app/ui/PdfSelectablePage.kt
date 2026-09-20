package com.lunaexplorer.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** Plain drags are left to the enclosing scroll container; only a long press or a handle claims the pointer. */
@Composable
internal fun PdfSelectablePage(
    image: ImageBitmap,
    pageNumber: Int,
    widthPoints: Int,
    heightPoints: Int,
    textSelectable: Boolean,
    selection: PdfTextSelection?,
    onSelect: (PdfTextBoundary, PdfTextBoundary, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var drawnSize by remember(image) { mutableStateOf(IntSize.Zero) }
    val coordinates = PdfPageCoordinates(widthPoints, heightPoints, drawnSize)
    val currentCoordinates by rememberUpdatedState(coordinates)
    val currentSelection by rememberUpdatedState(selection)
    val currentSelect by rememberUpdatedState(onSelect)
    val handleRadius = with(LocalDensity.current) { 7.dp.toPx() }
    val handleStem = with(LocalDensity.current) { 9.dp.toPx() }
    val handleTouchRadius = with(LocalDensity.current) { 24.dp.toPx() }
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = .3f)
    val handleColor = MaterialTheme.colorScheme.primary

    Box(modifier.onSizeChanged { drawnSize = it }.then(if (!textSelectable) Modifier else Modifier.pointerInput(image) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            val selected = currentSelection
            fun handlePosition(start: Boolean): Offset? = selected?.handleAnchor(start)?.let {
                currentCoordinates.toScreen(it) + Offset(0f, handleStem)
            }
            val handle = nearestPdfHandle(down.position, handlePosition(true), handlePosition(false), handleTouchRadius)
            if (selected != null && handle != null) {
                val moving = if (handle) selected.start else selected.end
                val fixed = if (handle) selected.end else selected.start
                // Track from the text boundary plus the finger's grab offset, not from the handle
                // circle drawn below the line; otherwise a drag selects the following line.
                val boundaryPoint = moving.point ?: selected.handleAnchor(handle) ?: return@awaitEachGesture
                val origin = currentCoordinates.toScreen(boundaryPoint)
                down.consume()
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1) break
                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!pointer.pressed) break
                    val point = currentCoordinates.toPage(origin + pointer.position - down.position)
                    val boundary = PdfTextBoundary(point = point)
                    if (handle) currentSelect(boundary, fixed, false) else currentSelect(fixed, boundary, false)
                    pointer.consume()
                }
            } else {
                val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                val anchor = PdfTextBoundary(point = currentCoordinates.toPage(press.position))
                currentSelect(anchor, anchor, true)
                press.consume()
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1) break
                    val pointer = event.changes.firstOrNull { it.id == press.id } ?: break
                    if (!pointer.pressed) break
                    if (pointer.position != pointer.previousPosition) {
                        currentSelect(anchor, PdfTextBoundary(point = currentCoordinates.toPage(pointer.position)), false)
                    }
                    pointer.consume()
                }
            }
        }
    })) {
        Image(image, "Page $pageNumber", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.matchParentSize()) {
            selection?.let { selected ->
                selected.bounds.forEach { bounds ->
                    val rect = coordinates.toScreen(bounds)
                    drawRect(highlight, rect.topLeft, rect.size)
                }
                listOf(true, false).forEach { start ->
                    selected.handleAnchor(start)?.let { point ->
                        val anchor = coordinates.toScreen(point)
                        val circle = anchor + Offset(0f, handleStem)
                        drawLine(handleColor, anchor, circle, strokeWidth = 2.dp.toPx())
                        drawCircle(handleColor, handleRadius, circle)
                    }
                }
            }
        }
    }
}
