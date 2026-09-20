package com.lunaexplorer.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.lunaexplorer.app.LunaApplication
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class TransferBackTest {
    private val harness = BrowserViewModelHarness()
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(harness).around(compose)

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    // The settings screen is a Compose Dialog with its own back dispatcher; the Activity's is not listening.
    private fun back() {
        compose.runOnUiThread {
            val dialog = ShadowDialog.getLatestDialog()
            (dialog as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    @Test fun `back steps out of a submenu, then out of the export tree, then out of settings`() {
        assertTrue(harness.awaitUntil { state.ready })
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                val current by viewModel.state.collectAsState()
                SettingsScreen(current, viewModel, LunaActions(
                    grantFolder = {}, open = { _, _ -> }, openWith = { _, _, _ -> },
                    shareReport = {}, requestFullAccess = {}, openSystemBrowser = {},
                ), onRecycleBin = {}) { dismissed = true }
            }
        }

        compose.onNodeWithText("Export and import").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Export").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Appearance").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Theme").performScrollTo().assertExists()

        back()
        compose.onAllNodesWithText("Theme").assertCountEquals(0)
        compose.onNodeWithText("Appearance").performScrollTo()
            .assertExists("Back from a submenu must land on the page list, not leave the export")
        assertTrue("And it must not have closed settings", !dismissed)

        back()
        compose.onNodeWithText("Write settings to a file").performScrollTo()
            .assertExists("Back from the page list returns to the export and import choice")

        back()
        compose.onNodeWithText("Deleting").performScrollTo()
            .assertExists("Back from there returns to the settings index")
        assertTrue("Settings stays open throughout", !dismissed)
    }
}
