package com.lunaexplorer.app.data

import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.Preferences
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalSettingsTransferTest {
    @Test fun `terminal tap keyboard preference survives sessions and settings transfer`() {
        for (tapOpensKeyboard in listOf(false, true)) {
            val preferences = Preferences(terminalTapOpensKeyboard = tapOpensKeyboard)
            val session = SessionCodec.decode(SessionCodec.encode(BrowserState(preferences = preferences)))
            assertEquals(tapOpensKeyboard, session.preferences.terminalTapOpensKeyboard)

            val units = setOf("terminal.tapOpensKeyboard")
            val document = TransferCodec.export(TransferSource(preferences = preferences), units, "test", 0)
            val decoded = requireNotNull(TransferCodec.decode(TransferCodec.encode(document)))
            val current = TransferSource(preferences = Preferences(terminalTapOpensKeyboard = !tapOpensKeyboard))
            val restored = SettingsPreviewer.apply(SettingsPreviewer.of(decoded, current), current, units).source
            assertEquals(tapOpensKeyboard, restored.preferences.terminalTapOpensKeyboard)
        }
    }

    @Test fun `terminal suggestion suppression survives sessions and settings transfer`() {
        for (disable in listOf(false, true)) {
            val preferences = Preferences(terminalDisableSuggestions = disable)
            val session = SessionCodec.decode(SessionCodec.encode(BrowserState(preferences = preferences)))
            assertEquals(disable, session.preferences.terminalDisableSuggestions)

            val source = TransferSource(preferences = preferences)
            val units = setOf("terminal.disableSuggestions")
            val document = TransferCodec.export(source, units, "test", 0)
            val decoded = requireNotNull(TransferCodec.decode(TransferCodec.encode(document)))
            val current = TransferSource(preferences = Preferences(terminalDisableSuggestions = !disable))
            val restored = SettingsPreviewer.apply(SettingsPreviewer.of(decoded, current), current, units).source
            assertEquals(disable, restored.preferences.terminalDisableSuggestions)
        }
    }
}
