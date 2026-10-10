package com.lunaexplorer.app.ui

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class TerminalSettingsUiTest : RobolectricBrowserUiTest() {
    @Test
    fun tappingToOpenTheKeyboardCanBeDisabledAndEnabledFromSettings() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        compose.runOnUiThread {
            viewModel.setPreferences(viewModel.state.value.preferences.copy(terminalTapOpensKeyboard = true))
        }
        openSettingsPage("Terminal")

        compose.onNodeWithText("Tap to open keyboard").performClick()
        awaitCondition("terminal tap keyboard disabled", 10_000) {
            !viewModel.state.value.preferences.terminalTapOpensKeyboard
        }
        compose.onNodeWithText("Tap to open keyboard").performClick()
        awaitCondition("terminal tap keyboard enabled", 10_000) {
            viewModel.state.value.preferences.terminalTapOpensKeyboard
        }
    }

    @Test
    fun keyboardSuggestionSuppressionCanBeDisabledAndEnabledFromSettings() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        compose.runOnUiThread {
            viewModel.setPreferences(viewModel.state.value.preferences.copy(terminalDisableSuggestions = true))
        }
        openSettingsPage("Terminal")

        compose.onNodeWithText("Disable keyboard suggestions").performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            !viewModel.state.value.preferences.terminalDisableSuggestions
        }
        compose.onNodeWithText("Disable keyboard suggestions").performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.preferences.terminalDisableSuggestions
        }
    }
}
