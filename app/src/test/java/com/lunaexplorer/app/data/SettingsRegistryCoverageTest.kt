package com.lunaexplorer.app.data

import com.lunaexplorer.app.model.Preferences
import com.lunaexplorer.app.model.SubtitleAppearance
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalSerializationApi::class)
class SettingsRegistryCoverageTest {

    private val claimed: Set<String> = SettingsRegistry.units.flatMapTo(HashSet()) { it.claims }

    @Test fun `every preference field is carried or excluded on purpose`() {
        val fields = Preferences.serializer().descriptor.elementNames.toList()
        val missing = fields.filterNot { it in claimed || it in SettingsRegistry.excluded }
        assertTrue(
            "These Preferences fields can be saved but not transferred. Give each a unit in " +
                "SettingsRegistry, or add it to SettingsRegistry.excluded with the reason: $missing",
            missing.isEmpty(),
        )
    }

    @Test fun `every subtitle field is carried`() {
        val fields = SubtitleAppearance.serializer().descriptor.elementNames.toList()
        val missing = fields.filterNot { it in claimed }
        assertTrue("Subtitle fields without a unit: $missing", missing.isEmpty())
    }

    @Test fun `every session field is carried or excluded on purpose`() {
        val fields = SessionDocument.serializer().descriptor.elementNames.toList()
        val missing = fields.filterNot { it in claimed || it in SettingsRegistry.excluded }
        assertTrue("Session fields without a unit: $missing", missing.isEmpty())
    }

    @Test fun `no two settings share an id`() {
        val ids = SettingsRegistry.units.map { it.id }
        assertEquals("Duplicate transfer ids: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}",
            ids.size, ids.distinct().size)
    }

    @Test fun `every setting belongs to a page the chooser shows`() {
        val stray = SettingsRegistry.units.filterNot { it.page in SettingsRegistry.pages }
        assertTrue("Settings on a page the chooser has no heading for: ${stray.map { it.id }}", stray.isEmpty())
    }

    @Test fun `a default export reads back as the same settings`() {
        val source = TransferSource()
        val all = SettingsRegistry.units.mapTo(HashSet()) { it.id }
        val document = TransferCodec.export(source, all, app = "test", written = 0)

        val preview = SettingsPreviewer.of(document, source)
        val disagreeing = preview.rows.filterNot { it.verdict == TransferVerdict.IDENTICAL }
        assertTrue(
            "These settings did not survive their own round trip: " +
                disagreeing.joinToString { "${it.id} (${it.verdict}${it.refusal.let { why -> if (why.isBlank()) "" else ": $why" }})" },
            disagreeing.isEmpty(),
        )
    }
}
