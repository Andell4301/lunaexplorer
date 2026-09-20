@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val WRITTEN = "yyyy-MM-dd HH:mm:ss"
private val READ = listOf(WRITTEN, "yyyy-MM-dd HH:mm", "yyyy-MM-dd")

internal fun formatTimestamp(millis: Long): String = SimpleDateFormat(WRITTEN, Locale.US).format(Date(millis))

/** A date, with a time or without one, in this device's zone. Null for anything else, a date that does not exist included. */
internal fun parseTimestamp(text: String): Long? = READ.firstNotNullOfOrNull { pattern ->
    val format = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
    val at = ParsePosition(0)
    // The whole text, and as many characters as the pattern: "2026-1-5" parses, and is not what was asked for.
    format.parse(text, at)?.takeIf { at.index == text.length && text.length == pattern.length }?.time
}

@Composable
internal fun TimestampField(
    text: String,
    onText: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    optional: Boolean = false,
    clearable: Boolean = false,
) {
    var showCalendar by remember { mutableStateOf(false) }
    var showClock by remember { mutableStateOf(false) }
    val parsed = remember(text) { parseTimestamp(text) }

    fun revise(block: Calendar.() -> Unit) {
        val base = Calendar.getInstance().apply {
            if (parsed != null) timeInMillis = parsed
            else { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        }
        onText(formatTimestamp(base.apply(block).timeInMillis))
    }

    Column(modifier) {
        OutlinedTextField(
            value = text, onValueChange = onText,
            label = { Text(label) }, placeholder = placeholder?.let { { Text(it) } }, singleLine = true,
            isError = parsed == null && !(optional && text.isBlank()), modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        // Wraps: four buttons do not fit the width of an AlertDialog.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showCalendar = true }) { Text("Date") }
            TextButton(onClick = { showClock = true }) { Text("Time") }
            TextButton(onClick = { onText(formatTimestamp(System.currentTimeMillis())) }) { Text("Now") }
            if (clearable && text.isNotEmpty()) TextButton(onClick = { onText("") }) { Text("Clear") }
        }
    }

    if (showCalendar) {
        // DatePicker works in UTC days, so hand it the calendar date in the field as one.
        val shown = remember {
            val local = Calendar.getInstance().apply { if (parsed != null) timeInMillis = parsed }
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
            }.timeInMillis
        }
        val dateState = rememberDatePickerState(initialSelectedDateMillis = shown)
        DatePickerDialog(
            onDismissRequest = { showCalendar = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { chosen ->
                        // Take only its calendar date, so the time already in the field is kept.
                        val picked = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = chosen }
                        revise {
                            set(Calendar.YEAR, picked.get(Calendar.YEAR))
                            set(Calendar.MONTH, picked.get(Calendar.MONTH))
                            set(Calendar.DAY_OF_MONTH, picked.get(Calendar.DAY_OF_MONTH))
                        }
                    }
                    showCalendar = false
                }) { Text("Choose") }
            },
            dismissButton = { TextButton(onClick = { showCalendar = false }) { Text("Cancel") } },
        ) { DatePicker(state = dateState) }
    }

    if (showClock) {
        val start = remember { Calendar.getInstance().apply { if (parsed != null) timeInMillis = parsed else { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) } } }
        val clockState = rememberTimePickerState(
            initialHour = start.get(Calendar.HOUR_OF_DAY), initialMinute = start.get(Calendar.MINUTE), is24Hour = true)
        AlertDialog(
            onDismissRequest = { showClock = false },
            title = { Text("Time of day") },
            text = { TimePicker(state = clockState) },
            confirmButton = {
                TextButton(onClick = {
                    revise {
                        set(Calendar.HOUR_OF_DAY, clockState.hour)
                        set(Calendar.MINUTE, clockState.minute)
                        set(Calendar.SECOND, 0)
                    }
                    showClock = false
                }) { Text("Choose") }
            },
            dismissButton = { TextButton(onClick = { showClock = false }) { Text("Cancel") } },
        )
    }
}
