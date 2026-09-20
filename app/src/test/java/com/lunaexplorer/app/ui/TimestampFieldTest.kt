package com.lunaexplorer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class TimestampFieldTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `a date reads with a time or without one, and nothing else reads as a date`() {
        val day = requireNotNull(parseTimestamp("2026-03-09"))
        assertEquals(0, Calendar.getInstance().apply { timeInMillis = day }.get(Calendar.HOUR_OF_DAY))
        assertEquals(day + 14 * 3_600_000 + 5 * 60_000, parseTimestamp("2026-03-09 14:05"))
        assertEquals(day + 14 * 3_600_000 + 5 * 60_000 + 7_000, parseTimestamp("2026-03-09 14:05:07"))
        listOf("", "2026-3-9", "2026-02-30", "2026-03-09 25:00", "2026-03-09T14:05", "yesterday").forEach {
            assertNull("'$it' is not a date", parseTimestamp(it))
        }
    }

    // On its own: a text field in an AlertDialog never goes idle under Robolectric, and the search dialog is one.
    @Test fun `the field takes typing as it comes, and its buttons write a time that reads back`() {
        var text by mutableStateOf("")
        compose.setContent { MaterialTheme { TimestampField(text, { text = it }, label = "Modified on or after", optional = true, clearable = true) } }

        compose.onNodeWithText("Modified on or after").performTextInput("last week")
        compose.runOnIdle { assertEquals("last week", text) }

        val before = System.currentTimeMillis()
        compose.onNodeWithText("Now").performClick()
        compose.runOnIdle {
            val written = requireNotNull(parseTimestamp(text)) { "'$text' does not read back" }
            assertTrue("To the second, so within one of the tap", written in (before - 1000)..System.currentTimeMillis())
        }

        compose.onNodeWithText("Clear").performClick()
        compose.runOnIdle { assertEquals("", text) }
    }
}
