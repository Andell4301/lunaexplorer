package com.lunaexplorer.app.ui

import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LongPressModifierTest {
    @get:Rule val compose = createComposeRule()

    private var taps = 0
    private var holds = 0

    private fun chip() {
        compose.setContent {
            val view = LocalView.current
            FilterChip(
                selected = false,
                onClick = { taps++ },
                label = { Text("MKV") },
                modifier = Modifier.lunaLongPress(view, "mkv") { holds++ },
            )
        }
    }

    @Test fun `an ordinary tap still reaches the chip's own click`() {
        chip()
        compose.onNodeWithText("MKV").performClick()
        compose.waitForIdle()

        assertEquals("The tap must arrive", 1, taps)
        assertEquals("And must not be read as a hold", 0, holds)
    }

    @Test fun `a long press is not also a click`() {
        chip()
        compose.onNodeWithText("MKV").performTouchInput { longClick() }
        compose.waitForIdle()

        assertEquals("The hold must arrive", 1, holds)
        assertEquals("And the release must not also click what it was held on", 0, taps)
    }
}
