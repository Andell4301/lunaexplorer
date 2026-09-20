package com.lunaexplorer.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

internal enum class TapZone { BACK, MIDDLE, FORWARD }

private enum class Drag { UNDECIDED, IGNORED, SEEK, VOLUME, BRIGHTNESS }

// Separate tap and drag detectors compete for the first touch; report motion in viewer fractions, upwards positive.
internal fun Modifier.playerGestures(
    // Changing captured callbacks requires a new key because pointerInput retains its original callbacks.
    key: Any?,
    locked: Boolean,
    doubleTap: Boolean,
    seek: Boolean,
    volume: Boolean,
    brightness: Boolean,
    onLockedTouch: () -> Unit,
    onTap: () -> Unit,
    onDoubleTap: (TapZone) -> Unit,
    onSeekStart: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekEnd: () -> Unit,
    onVolumeStart: () -> Unit,
    onVolume: (Float) -> Unit,
    onBrightnessStart: () -> Unit,
    onBrightness: (Float) -> Unit,
    onAdjustEnd: () -> Unit,
): Modifier = pointerInput(key, locked, doubleTap, seek, volume, brightness) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (locked) {
            do {
                val event = awaitPointerEvent()
            } while (event.changes.any { it.pressed })
            onLockedTouch()
            return@awaitEachGesture
        }
        val onTheLeft = down.position.x < size.width / 2f
        var mode = Drag.UNDECIDED
        var travelled = Offset.Zero
        var liftedAt = 0L
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    liftedAt = change.uptimeMillis
                    break
                }
                // Read positionChange() before consume(): a consumed change reports no movement.
                val moved = change.positionChange()
                travelled += moved
                if (mode == Drag.UNDECIDED && travelled.getDistance() >= viewConfiguration.touchSlop) {
                    // Fixed once at touch slop, so a wandering drag cannot switch from seek to volume.
                    val sideways = abs(travelled.x) > abs(travelled.y)
                    mode = when {
                        sideways && seek -> Drag.SEEK
                        sideways -> Drag.IGNORED
                        !onTheLeft && volume -> Drag.VOLUME
                        onTheLeft && brightness -> Drag.BRIGHTNESS
                        else -> Drag.IGNORED
                    }
                    when (mode) {
                        Drag.SEEK -> onSeekStart()
                        Drag.VOLUME -> onVolumeStart()
                        Drag.BRIGHTNESS -> onBrightnessStart()
                        else -> Unit
                    }
                }
                if (mode == Drag.UNDECIDED || mode == Drag.IGNORED) continue
                change.consume()
                when (mode) {
                    Drag.SEEK -> onSeek(moved.x / size.width)
                    Drag.VOLUME -> onVolume(-moved.y / size.height)
                    Drag.BRIGHTNESS -> onBrightness(-moved.y / size.height)
                    else -> Unit
                }
            }
        } finally {
            // Also runs on cancellation; otherwise an abandoned scrub leaves the seek bar stuck
            // at the dragged position.
            when (mode) {
                Drag.SEEK -> onSeekEnd()
                Drag.VOLUME, Drag.BRIGHTNESS -> onAdjustEnd()
                else -> Unit
            }
        }
        if (mode != Drag.UNDECIDED) return@awaitEachGesture
        if (!doubleTap) {
            onTap()
            return@awaitEachGesture
        }
        var last = tapAfter(liftedAt)
        if (last == null) {
            onTap()
            return@awaitEachGesture
        }
        var zone = zoneOf(last)
        onDoubleTap(zone)
        // Each further tap carries a skip on. Asking for a pair every time would halve the taps that count.
        while (zone != TapZone.MIDDLE) {
            last = tapAfter(liftOf(last ?: break) ?: break) ?: break
            zone = zoneOf(last)
            if (zone == TapZone.MIDDLE) onTap() else onDoubleTap(zone)
        }
    }
}

private fun AwaitPointerEventScope.zoneOf(tap: PointerInputChange): TapZone {
    val third = size.width / 3f
    return when {
        tap.position.x < third -> TapZone.BACK
        tap.position.x > size.width - third -> TapZone.FORWARD
        else -> TapZone.MIDDLE
    }
}

private suspend fun AwaitPointerEventScope.tapAfter(liftedAt: Long): PointerInputChange? =
    withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
        var candidate = awaitFirstDown(requireUnconsumed = false)
        // A down within doubleTapMinTimeMillis of the lift is touchscreen bounce, not another tap.
        while (candidate.uptimeMillis < liftedAt + viewConfiguration.doubleTapMinTimeMillis) {
            candidate = awaitFirstDown(requireUnconsumed = false)
        }
        candidate
    }

private suspend fun AwaitPointerEventScope.liftOf(down: PointerInputChange): Long? {
    var travelled = Offset.Zero
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return null
        if (!change.pressed) return change.uptimeMillis
        travelled += change.positionChange()
        if (travelled.getDistance() >= viewConfiguration.touchSlop) return null
    }
}

internal fun skipTotal(running: Long, step: Long): Long =
    if (running != 0L && (running < 0) == (step < 0)) running + step else step
