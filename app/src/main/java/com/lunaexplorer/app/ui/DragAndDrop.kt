@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lunaexplorer.app.ui

import android.content.ClipData
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import com.lunaexplorer.core.Entry
import kotlinx.coroutines.delay

/** Drag payload, carried as the drag's localState. The ClipData holds only a label. */
internal class LunaDrag(val entries: List<Entry>)

private fun DragAndDropEvent.lunaEntries(): List<Entry>? =
    (toAndroidDragEvent().localState as? LunaDrag)?.entries?.takeIf { it.isNotEmpty() }

private val DRAG_TRIGGER = 6.dp

/** Test seam: the platform drag needs a WindowManager, so gesture tests replace it. */
internal typealias DragLift = (View, ClipData, View.DragShadowBuilder, LunaDrag) -> Unit

internal val platformDrag: DragLift = { view, clip, shadow, payload ->
    // No DRAG_FLAG_GLOBAL: the drag stays inside the app.
    view.startDragAndDrop(clip, shadow, payload, View.DRAG_FLAG_OPAQUE)
}

internal var liftDrag: DragLift = platformDrag

internal class DragVisual(val painter: Painter?, val tint: Color?, val photo: Boolean)

internal class DragPickup(val entries: List<Entry>, val visual: DragVisual)

internal data class DragColours(val tile: Int, val second: Int, val third: Int, val badge: Int, val badgeInk: Int)

@Composable
internal fun dragColours(): DragColours = DragColours(
    tile = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb(),
    second = MaterialTheme.colorScheme.tertiaryContainer.toArgb(),
    third = MaterialTheme.colorScheme.primaryContainer.toArgb(),
    badge = MaterialTheme.colorScheme.primary.toArgb(),
    badgeInk = MaterialTheme.colorScheme.onPrimary.toArgb(),
)

internal fun dragVisual(thumbnail: ImageBitmap?, appIcon: ImageBitmap?, glyph: Painter, tint: Color): DragVisual = when {
    thumbnail != null -> DragVisual(BitmapPainter(thumbnail, filterQuality = FilterQuality.High), null, photo = true)
    appIcon != null -> DragVisual(BitmapPainter(appIcon), null, photo = false)
    else -> DragVisual(glyph, tint, photo = false)
}

/**
 * Compose's own drag-source detector competes with clickable for the consumed press, so the drag is started
 * through the platform; Compose still routes it to its drop targets. [pickUp] is read at drag start so the
 * payload includes selection changes not yet recomposed. [scale] is the density including UI zoom.
 */
internal fun Modifier.lunaDragSource(
    view: View,
    key: Any,
    scale: Float,
    direction: LayoutDirection,
    colours: DragColours,
    pickUp: () -> DragPickup,
): Modifier = pointerInput(view, key, scale, direction, colours) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture

        val picked = pickUp()
        if (picked.entries.isNotEmpty()) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        val anchor = held.position
        val travel = DRAG_TRIGGER.toPx()
        var dragging = false
        // Consume the rest of the press even if no drag starts, so the release doesn't click. A platform
        // drag cancels this pointer stream.
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
            val moved = event.changes.firstOrNull { it.id == down.id }
            if (!dragging && picked.entries.isNotEmpty() && moved != null &&
                (moved.position - anchor).getDistance() >= travel
            ) {
                dragging = true
                val label = if (picked.entries.size == 1) picked.entries.first().name else "${picked.entries.size} items"
                liftDrag(view, ClipData.newPlainText("Luna", label),
                    DragTile(view, scale, direction, picked.visual, picked.entries.size, colours),
                    LunaDrag(picked.entries))
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Long press on top of a Material component's own clickable. The down arrives already consumed, so accept it;
 * after the long press, consume on the Initial pass so the clickable never sees the release and drops its click.
 */
internal fun Modifier.lunaLongPress(view: View, key: Any, onLongPress: () -> Unit): Modifier =
    pointerInput(view, key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }

/** A transparent band below the tile holds it above the finger while the touch point stays inside the shadow. */
internal class DragTile(
    view: View,
    private val scale: Float,
    private val direction: LayoutDirection,
    private val visual: DragVisual,
    private val count: Int,
    private val colours: DragColours,
) : View.DragShadowBuilder(view) {
    private val tile = (96 * scale).toInt()
    private val step = (10 * scale).toInt()
    private val behind = (count - 1).coerceIn(0, 2)
    private val band = (40 * scale).toInt()
    private val width = tile + step * behind
    private val height = tile + step * behind + band

    override fun onProvideShadowMetrics(size: Point, touch: Point) {
        size.set(width, height)
        touch.set(width / 2, height - 1)
    }

    override fun onDrawShadow(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = 22 * scale
        for (i in behind downTo 1) {
            paint.color = if (i == 1) colours.second else colours.third
            val off = (step * i).toFloat()
            canvas.drawRoundRect(off, off, off + tile, off + tile, radius, radius, paint)
        }
        paint.color = colours.tile
        canvas.drawRoundRect(0f, 0f, tile.toFloat(), tile.toFloat(), radius, radius, paint)
        visual.painter?.let { drawInto(canvas, it, radius) }
        if (count > 1) {
            val r = 14 * scale
            // The badge stays inside the shadow bounds; anything drawn above y = 0 is clipped.
            val cx = tile - r + 2 * scale
            val cy = r
            paint.color = colours.badge
            canvas.drawCircle(cx, cy, r, paint)
            val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
            text.textSize = 13 * scale
            text.typeface = Typeface.DEFAULT_BOLD
            text.color = colours.badgeInk
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(count.toString(), cx, cy - (text.descent() + text.ascent()) / 2, text)
        }
    }

    private fun drawInto(canvas: Canvas, painter: Painter, radius: Float) {
        val side = tile.toFloat()
        CanvasDrawScope().draw(Density(scale), direction, ComposeCanvas(canvas), Size(side, side)) {
            if (visual.photo) {
                clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, side, side, CornerRadius(radius))) }) {
                    val source = painter.intrinsicSize
                    val (w, h) = if (source.isSpecified && source.width > 0f && source.height > 0f) {
                        val s = maxOf(side / source.width, side / source.height)
                        source.width * s to source.height * s
                    } else side to side
                    translate((side - w) / 2, (side - h) / 2) { with(painter) { draw(Size(w, h)) } }
                }
            } else {
                // No tint means an app icon, which gets a smaller inset than a glyph.
                val inset = side * if (visual.tint == null) 0.16f else 0.27f
                val span = side - 2 * inset
                translate(inset, inset) {
                    with(painter) { draw(Size(span, span), colorFilter = visual.tint?.let { ColorFilter.tint(it) }) }
                }
            }
        }
    }
}

internal fun Modifier.lunaDropTarget(target: DragAndDropTarget): Modifier =
    dragAndDropTarget(shouldStartDragAndDrop = { it.lunaEntries() != null }, target = target)

/** Remembered once, callbacks read through rememberUpdatedState: a new target instance mid-drag loses the drag. */
@Composable
internal fun rememberDropTarget(
    onHover: (Boolean) -> Unit,
    onDrop: (List<Entry>) -> Unit,
): DragAndDropTarget {
    val hover = rememberUpdatedState(onHover)
    val drop = rememberUpdatedState(onDrop)
    return remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { hover.value(true) }
            override fun onExited(event: DragAndDropEvent) { hover.value(false) }
            override fun onEnded(event: DragAndDropEvent) { hover.value(false) }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover.value(false)
                val entries = event.lunaEntries() ?: return false
                drop.value(entries)
                return true
            }
        }
    }
}

@Composable
internal fun rememberAreaDropTarget(
    onHover: (Offset?) -> Unit,
    onDrop: (List<Entry>, Offset) -> Boolean,
): DragAndDropTarget {
    val hover = rememberUpdatedState(onHover)
    val drop = rememberUpdatedState(onDrop)
    return remember {
        object : DragAndDropTarget {
            override fun onMoved(event: DragAndDropEvent) { hover.value(event.rootPosition()) }
            override fun onEntered(event: DragAndDropEvent) { hover.value(event.rootPosition()) }
            override fun onExited(event: DragAndDropEvent) { hover.value(null) }
            override fun onEnded(event: DragAndDropEvent) { hover.value(null) }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover.value(null)
                val entries = event.lunaEntries() ?: return false
                return drop.value(entries, event.rootPosition())
            }
        }
    }
}

private fun DragAndDropEvent.rootPosition(): Offset =
    toAndroidDragEvent().let { Offset(it.x, it.y) }

@Composable
internal fun SpringLoad(hovering: Boolean, onOpen: () -> Unit) {
    val open = rememberUpdatedState(onOpen)
    val wait = LocalViewConfiguration.current.longPressTimeoutMillis
    if (hovering) LaunchedEffect(Unit) {
        delay(wait)
        open.value()
    }
}

@Composable
internal fun DropDialog(
    count: Int,
    destination: String,
    onDismiss: () -> Unit,
    onChoose: (move: Boolean, keep: Boolean) -> Unit,
) {
    var keep by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Drop into $destination" else "Drop $count items into $destination") },
        text = {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Checkbox) { keep = !keep },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = keep, onCheckedChange = { keep = it })
                Spacer(Modifier.width(4.dp))
                Text("Remember for this session", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onChoose(true, keep) }) { Text("Move") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { onChoose(false, keep) }) { Text("Copy") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
