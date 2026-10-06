package com.lunaexplorer.app.data

import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.Preferences
import com.lunaexplorer.app.model.SubtitleAppearance
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleSettingsTransferTest {
    @Test fun `subtitle preservation choices survive sessions and settings transfer`() {
        val appearance = SubtitleAppearance(enabled = true, preserveEmphasis = false,
            preserveRelativeSizes = false, preserveColors = false)
        val preferences = Preferences(subtitleAppearance = appearance)
        val session = SessionCodec.decode(SessionCodec.encode(BrowserState(preferences = preferences)))
        assertEquals(appearance, session.preferences.subtitleAppearance)

        val source = TransferSource(preferences = preferences)
        val units = SettingsRegistry.units.filter { it.id.startsWith("subtitles.") }.mapTo(HashSet()) { it.id }
        val document = TransferCodec.export(source, units, "test", 0)
        val decoded = requireNotNull(TransferCodec.decode(TransferCodec.encode(document)))
        val current = TransferSource()
        val restored = SettingsPreviewer.apply(SettingsPreviewer.of(decoded, current), current, units).source
        assertEquals(appearance, restored.preferences.subtitleAppearance)
    }
}
