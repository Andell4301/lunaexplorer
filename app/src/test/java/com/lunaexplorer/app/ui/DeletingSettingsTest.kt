package com.lunaexplorer.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.lunaexplorer.app.LunaApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class DeletingSettingsTest {
    private val harness = BrowserViewModelHarness()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(harness).around(compose)

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    private fun show() {
        assertTrue(harness.awaitUntil { state.ready })
        compose.setContent {
            MaterialTheme {
                val current by viewModel.state.collectAsState()
                // In a Column, as on the settings page; unstacked rows take each other's taps.
                Column { DeletingSettings(current, viewModel, onRecycleBin = {}) }
            }
        }
    }

    @Test fun `the warning arrives switched off and reveals its size only once switched on`() {
        show()
        val toggle = compose.onNodeWithContentDescription("Large deletion warning")
        assertEquals(false, state.preferences.warnLargeDelete)
        compose.onAllNodesWithText("Warn at").assertCountEquals(0)

        toggle.performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); state.preferences.warnLargeDelete }
        compose.onNodeWithText("Warn at").assertExists()

        toggle.performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); !state.preferences.warnLargeDelete }
        compose.onAllNodesWithText("Warn at").assertCountEquals(0)
    }

    @Test fun `a whole number of gigabytes is saved as typed, zero is refused and shown as wrong`() {
        show()
        switchOn()
        val field = compose.onNodeWithText("Warn at")
        val wrong = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)
        assertEquals(10, state.preferences.largeDeleteGb)
        field.assert(wrong.not())

        field.performTextReplacement("25")
        compose.waitUntil(10_000) { compose.waitForIdle(); state.preferences.largeDeleteGb == 25 }
        field.assert(wrong.not())

        field.performTextReplacement("0")
        compose.waitForIdle()
        harness.idle()
        assertEquals("Nothing below one gigabyte is a warning", 25, state.preferences.largeDeleteGb)
        field.assert(wrong)

        field.performTextReplacement("x")
        compose.waitForIdle()
        assertEquals(25, state.preferences.largeDeleteGb)
        field.assert(wrong)
    }

    private fun switchOn() {
        compose.onNodeWithContentDescription("Large deletion warning").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); state.preferences.warnLargeDelete }
    }
}
