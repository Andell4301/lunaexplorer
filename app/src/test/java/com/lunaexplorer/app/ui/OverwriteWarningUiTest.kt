package com.lunaexplorer.app.ui

import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverwriteWarningUiTest {
    @get:Rule val compose = createComposeRule()
    private var confirmations = 0
    private var dismissals = 0

    private fun showWarning() {
        var visible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible) OverwriteWarning(
                    onDismiss = { dismissals++; visible = false },
                    onConfirm = { confirmations++; visible = false },
                )
            }
        }
        compose.onNodeWithText("Overwrite").assertExists()
    }

    @Test fun overwriteConfirmsWithoutDismissing() {
        showWarning()
        compose.onNodeWithText("Overwrite").performClick()

        compose.onNodeWithText("Overwrite").assertDoesNotExist()
        assertEquals(1, confirmations)
        assertEquals(0, dismissals)
    }

    @Test fun cancelDismissesWithoutConfirming() {
        showWarning()
        compose.onNodeWithText("Cancel").performClick()

        compose.onNodeWithText("Overwrite").assertDoesNotExist()
        assertEquals(0, confirmations)
        assertEquals(1, dismissals)
    }

    @Test fun backDismissesWithoutConfirming() {
        showWarning()
        val dialog = ShadowDialog.getLatestDialog() as OnBackPressedDispatcherOwner
        compose.runOnUiThread { dialog.onBackPressedDispatcher.onBackPressed() }

        compose.onNodeWithText("Overwrite").assertDoesNotExist()
        assertEquals(0, confirmations)
        assertEquals(1, dismissals)
    }
}
