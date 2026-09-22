package com.lunaexplorer.app.data

import com.lunaexplorer.app.model.ProcedureSchedule
import com.lunaexplorer.app.model.ProcedureScheduleKind
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ProcedureScheduleTest {
    private val newYork = ZoneId.of("America/New_York")

    @Test fun `daily schedules keep wall time across daylight saving changes`() {
        val schedule = ProcedureSchedule(enabled = true, hour = 10)
        val now = Instant.parse("2026-03-07T15:00:00Z")
        assertEquals(Instant.parse("2026-03-08T14:00:00Z"), schedule.nextRunAfter(now, newYork))
    }

    @Test fun `a time inside the spring gap advances to an existing local time`() {
        val schedule = ProcedureSchedule(enabled = true, hour = 2, minute = 30)
        val now = Instant.parse("2026-03-08T06:00:00Z")
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), schedule.nextRunAfter(now, newYork))
    }

    @Test fun `the repeated hour in autumn does not run a daily schedule twice`() {
        val schedule = ProcedureSchedule(enabled = true, hour = 1, minute = 30)
        val now = Instant.parse("2026-11-01T05:30:00Z")
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), schedule.nextRunAfter(now, newYork))
    }

    @Test fun `weekly schedules skip unselected days and do not repeat the current occurrence`() {
        val schedule = ProcedureSchedule(enabled = true, kind = ProcedureScheduleKind.WEEKLY,
            hour = 10, days = setOf(1, 5))
        val now = Instant.parse("2026-09-21T14:00:00Z")
        assertEquals(Instant.parse("2026-09-25T14:00:00Z"), schedule.nextRunAfter(now, newYork))
    }

    @Test fun `disabled schedules and expired one-time schedules have no next occurrence`() {
        val now = Instant.parse("2026-09-21T14:00:00Z")
        val once = ProcedureSchedule(enabled = true, kind = ProcedureScheduleKind.ONCE, atMillis = now.toEpochMilli())
        assertNull(once.nextRunAfter(now, newYork))
        assertNull(ProcedureSchedule(enabled = false).nextRunAfter(now, newYork))
        assertEquals(now, once.nextRunAfter(now.minusSeconds(1), newYork))
    }
}
