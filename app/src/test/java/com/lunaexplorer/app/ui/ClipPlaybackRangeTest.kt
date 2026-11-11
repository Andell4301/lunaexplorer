package com.lunaexplorer.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipPlaybackRangeTest {
    private data class Stop(val time: Long, val fire: () -> Unit, var cancelled: Boolean = false)
    private val starts = mutableListOf<Long>()
    private val stops = mutableListOf<Stop>()
    private var pauses = 0
    private val preview = ClipPlaybackRange(starts::add, { pauses++ }) { end, action ->
        val stop = Stop(end, action)
        stops += stop
        val cancel: () -> Unit = { stop.cancelled = true }
        cancel
    }

    @Test fun `preview starts at the chosen start and pauses only when its end is reached`() {
        preview.play(12_345, 23_456)
        assertEquals(listOf(12_345L), starts)
        assertEquals(23_456L, stops.single().time)
        assertEquals(0, pauses)
        assertFalse(stops.single().cancelled)

        stops.single().fire()
        assertEquals(1, pauses)
        stops.single().fire()
        assertEquals(1, pauses)
    }

    @Test fun `normal navigation cancels the preview stop even if its callback was already posted`() {
        preview.play(1000, 2000)
        preview.cancel()

        assertTrue(stops.single().cancelled)
        stops.single().fire()
        assertEquals(0, pauses)
    }

    @Test fun `an earlier preview cannot stop the newly selected clip`() {
        preview.play(1000, 2000)
        preview.play(3000, 4000)

        assertEquals(listOf(1000L, 3000L), starts)
        assertTrue(stops.first().cancelled)
        stops.first().fire()
        assertEquals(0, pauses)
        stops.last().fire()
        assertEquals(1, pauses)
    }
}
