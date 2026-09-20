package com.lunaexplorer.app.ui

import android.graphics.Point
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.selection.PageSelection
import android.graphics.pdf.models.selection.SelectionBoundary
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** [point] is in PDF page points, not bitmap pixels. */
internal data class PdfTextBoundary(val index: Int = -1, val point: Offset? = null, val rtl: Boolean = false)
internal data class PdfTextSelection(
    val text: String,
    val bounds: List<Rect>,
    val start: PdfTextBoundary,
    val end: PdfTextBoundary,
)

internal data class PdfSelectionRequest(val start: PdfTextBoundary, val end: PdfTextBoundary, val serial: Long)

/** Bounds each Text layout in the page-text dialog; the chunks concatenate back to [text]. */
internal fun pdfTextChunks(text: String, maximum: Int = 2048): List<String> {
    require(maximum >= 2)
    return buildList {
        var start = 0
        while (start < text.length) {
            var end = (start + maximum).coerceAtMost(text.length)
            if (end < text.length) {
                val boundary = (end - 1 downTo start + maximum / 2).firstOrNull { text[it].isWhitespace() }
                if (boundary != null) end = boundary + 1
                if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            }
            add(text.substring(start, end))
            start = end
        }
    }
}

/**
 * Pointer positions are local to the drawn page, inside the scroll container. [drawnSize] already
 * reflects density, fit and zoom, and scroll offsets must not be added.
 */
internal data class PdfPageCoordinates(val pageWidth: Int, val pageHeight: Int, val drawnSize: IntSize) {
    fun toPage(position: Offset): Offset = Offset(
        (position.x * pageWidth / drawnSize.width.coerceAtLeast(1)).coerceIn(0f, pageWidth.toFloat()),
        (position.y * pageHeight / drawnSize.height.coerceAtLeast(1)).coerceIn(0f, pageHeight.toFloat()),
    )

    fun toScreen(point: Offset): Offset = Offset(
        point.x * drawnSize.width / pageWidth.coerceAtLeast(1),
        point.y * drawnSize.height / pageHeight.coerceAtLeast(1),
    )

    fun toScreen(bounds: Rect): Rect = Rect(toScreen(bounds.topLeft), toScreen(bounds.bottomRight))
}

internal fun PdfTextSelection.handleAnchor(startHandle: Boolean): Offset? {
    val boundary = if (startHandle) start else end
    val point = boundary.point
    val line = if (point == null) {
        if (startHandle) bounds.firstOrNull() else bounds.lastOrNull()
    } else bounds.minByOrNull { rect ->
        val dx = if (point.x < rect.left) rect.left - point.x else if (point.x > rect.right) point.x - rect.right else 0f
        val dy = if (point.y < rect.top) rect.top - point.y else if (point.y > rect.bottom) point.y - rect.bottom else 0f
        dx + dy * 4
    }
    if (line == null) return point
    val x = point?.x ?: if (startHandle != boundary.rtl) line.left else line.right
    return Offset(x, line.bottom)
}

internal fun nearestPdfHandle(position: Offset, start: Offset?, end: Offset?, radius: Float): Boolean? {
    val startDistance = start?.let { (it - position).getDistance() } ?: Float.POSITIVE_INFINITY
    val endDistance = end?.let { (it - position).getDistance() } ?: Float.POSITIVE_INFINITY
    return when {
        minOf(startDistance, endDistance) > radius -> null
        startDistance <= endDistance -> true
        else -> false
    }
}

/** All references to API 35 selection classes stay behind the caller's SDK check. */
@RequiresApi(35)
internal object PdfTextApi {
    fun pageText(page: PdfRenderer.Page): String = buildString {
        for (content in page.textContents) {
            require(length.toLong() + content.text.length <= 1_000_000) { "This page has too much text to select at once." }
            append(content.text)
        }
    }

    fun select(page: PdfRenderer.Page, request: PdfSelectionRequest): PdfTextSelection? =
        page.selectContent(boundary(request.start), boundary(request.end))?.let(::fromPlatform)

    private fun boundary(value: PdfTextBoundary): SelectionBoundary = if (value.index >= 0) SelectionBoundary(value.index)
        else SelectionBoundary(Point(value.point!!.x.roundToInt(), value.point.y.roundToInt()))

    internal fun fromPlatform(selection: PageSelection): PdfTextSelection? {
        val text = selection.selectedTextContents.joinToString("\n") { it.text }
        if (text.isBlank()) return null
        val bounds = selection.selectedTextContents.flatMap { content -> content.bounds }.mapNotNull { rect ->
            if (!rect.left.isFinite() || !rect.top.isFinite() || !rect.right.isFinite() || !rect.bottom.isFinite() || rect.isEmpty) null
            else Rect(rect.left, rect.top, rect.right, rect.bottom)
        }
        fun copy(boundary: SelectionBoundary) = PdfTextBoundary(boundary.index,
            boundary.point?.let { Offset(it.x.toFloat(), it.y.toFloat()) }, boundary.isRtl)
        return PdfTextSelection(text, bounds, copy(selection.start), copy(selection.stop))
    }
}
