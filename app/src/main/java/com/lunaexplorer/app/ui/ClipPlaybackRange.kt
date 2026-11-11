package com.lunaexplorer.app.ui

internal class ClipPlaybackRange(
    private val playFrom: (Long) -> Unit,
    private val pause: () -> Unit,
    private val scheduleEnd: (Long, () -> Unit) -> (() -> Unit),
) {
    private var generation = 0L
    private var cancelEnd: (() -> Unit)? = null

    fun play(start: Long, end: Long) {
        require(start >= 0 && end > start)
        cancel()
        playFrom(start)
        val current = ++generation
        val cancel = scheduleEnd(end) {
            if (current == generation) {
                generation++
                cancelEnd = null
                pause()
            }
        }
        if (current == generation) cancelEnd = cancel else cancel()
    }

    fun cancel() {
        generation++
        val pending = cancelEnd
        cancelEnd = null
        pending?.invoke()
    }
}
