package com.lunaexplorer.app.model

import com.lunaexplorer.core.ProcedureStep
import com.lunaexplorer.core.validateProcedureSteps
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

@Serializable
data class StoredProcedure(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val steps: List<ProcedureStep>,
    val schedule: ProcedureSchedule? = null,
    val notifyOnSuccess: Boolean = false,
    val notifyOnFailure: Boolean = false,
) {
    fun validate() {
        require(id.isNotEmpty()) { "A procedure needs an ID" }
        require(name.isNotEmpty()) { "Enter a name" }
        validateProcedureSteps(steps)
        schedule?.validate()
    }
}

@Serializable
enum class ProcedureScheduleKind { INTERVAL, DAILY, WEEKLY, ONCE }

@Serializable
data class ProcedureSchedule(
    val enabled: Boolean = false,
    val kind: ProcedureScheduleKind = ProcedureScheduleKind.DAILY,
    val intervalMinutes: Long = 60,
    val hour: Int = 0,
    val minute: Int = 0,
    val days: Set<Int> = (1..7).toSet(),
    val atMillis: Long = 0,
) {
    fun validate() {
        when (kind) {
            ProcedureScheduleKind.INTERVAL -> require(intervalMinutes in 15..Long.MAX_VALUE / 60_000) {
                "The interval must be at least 15 minutes"
            }
            ProcedureScheduleKind.DAILY, ProcedureScheduleKind.WEEKLY -> {
                require(hour in 0..23 && minute in 0..59) { "Enter a valid time" }
                if (kind == ProcedureScheduleKind.WEEKLY) {
                    require(days.isNotEmpty() && days.all { it in 1..7 }) { "Select a day" }
                }
            }
            ProcedureScheduleKind.ONCE -> require(atMillis > 0) { "Enter a date and time" }
        }
    }

    fun nextRunAfter(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        validate()
        if (!enabled) return null
        when (kind) {
            ProcedureScheduleKind.ONCE -> return Instant.ofEpochMilli(atMillis).takeIf { it > now }
            ProcedureScheduleKind.INTERVAL -> return now.plusSeconds(intervalMinutes * 60)
            else -> Unit
        }
        val today = now.atZone(zone).toLocalDate()
        val time = LocalTime.of(hour, minute)
        return (0L..7L).firstNotNullOfOrNull { offset ->
            val date = today.plusDays(offset)
            if (kind == ProcedureScheduleKind.WEEKLY && date.dayOfWeek.value !in days) null
            else date.atTime(time).atZone(zone).toInstant().takeIf { it > now }
        }
    }
}
