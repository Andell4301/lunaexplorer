package com.lunaexplorer.app.ui

internal enum class PipPhase { NONE, ACTIVE, LEAVING }

internal enum class PipAction { BACK, TOGGLE, FORWARD }

/**
 * [published] is a media session being published, not the setting, so a stop that beats the session
 * into existence still pauses. [interactive] is PowerManager.isInteractive: a pinned activity stops
 * only when its window is closed or the screen goes off.
 */
internal fun pauseOnStop(published: Boolean, pip: PipPhase, interactive: Boolean, closing: Boolean): Boolean = when {
    closing -> true
    pip == PipPhase.LEAVING -> true
    pip == PipPhase.ACTIVE && interactive -> true
    else -> !published
}

/** [armed] is the media viewer having armed a video; the browser and the audio viewer never arm. */
internal fun autoEnterPip(armed: Boolean, playing: Boolean, launching: Boolean, autoEnter: Boolean = true): Boolean =
    armed && playing && autoEnter && !launching

/** Android accepts 1:2.39 to 2.39:1; 2.38 rather than an exact match at the boundary. */
internal fun pipAspect(width: Int, height: Int): Pair<Int, Int> = when {
    width <= 0 || height <= 0 -> 16 to 9
    width.toLong() * 50 > height.toLong() * 119 -> 119 to 50
    height.toLong() * 50 > width.toLong() * 119 -> 50 to 119
    else -> width to height
}

internal fun pipActions(seekable: Boolean, slots: Int): List<PipAction> = when {
    slots < 1 -> emptyList()
    seekable && slots >= 3 -> listOf(PipAction.BACK, PipAction.TOGGLE, PipAction.FORWARD)
    else -> listOf(PipAction.TOGGLE)
}

internal fun serviceWanted(published: Boolean, onScreen: Boolean, playWhenReady: Boolean, failed: Boolean): Boolean =
    published && !onScreen && playWhenReady && !failed

internal enum class ServiceAction { START, STOP, LEAVE }

/**
 * A pause off screen leaves the service alone: media3 keeps its notification up, with a Play that
 * prepares again, and drops the foreground itself after its own timeout.
 */
internal fun serviceAction(published: Boolean, onScreen: Boolean, playWhenReady: Boolean, failed: Boolean): ServiceAction = when {
    serviceWanted(published, onScreen, playWhenReady, failed) -> ServiceAction.START
    onScreen -> ServiceAction.STOP
    else -> ServiceAction.LEAVE
}

/** Off screen and about to be paused the tracks are left alone, rather than reselected twice. */
internal fun decodePicture(onScreen: Boolean, carryingOn: Boolean, decoding: Boolean): Boolean =
    onScreen || (decoding && !carryingOn)

/**
 * Android reports a closed and an expanded picture-in-picture window alike. Closing stops the
 * activity, before or after the report; expanding resumes it.
 */
internal class PipExit(from: PipPhase = PipPhase.NONE) {
    var phase = from
        private set

    fun modeChanged(inPip: Boolean, started: Boolean, resumed: Boolean): Boolean {
        if (inPip) {
            phase = PipPhase.ACTIVE
            return false
        }
        val closed = phase == PipPhase.ACTIVE && !started
        phase = if (resumed) PipPhase.NONE else PipPhase.LEAVING
        return closed
    }

    fun resumed() {
        if (phase == PipPhase.LEAVING) phase = PipPhase.NONE
    }
}
