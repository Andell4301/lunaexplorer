package com.lunaexplorer.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsInputTest {
    @Test fun `a malformed scalar setting does not prevent other settings from importing`() {
        val source = TransferSource()
        val document = TransferDocument(luna = TRANSFER_FORMAT, values = mapOf(
            "files.showHidden" to JsonArray(emptyList()),
            "files.descending" to JsonPrimitive(true),
        ))

        val preview = SettingsPreviewer.of(document, source)
        val result = SettingsPreviewer.apply(preview, source, document.values.keys)

        assertEquals(TransferVerdict.UNREADABLE, preview.rows.single { it.id == "files.showHidden" }.verdict)
        assertEquals(1, result.refused.size)
        assertEquals(source.preferences.copy(descending = true), result.source.preferences)
    }

    @Test fun `nontext credentials cannot replace stored passwords or keys`() {
        val source = TransferSource(passwords = mapOf("nas" to "password"), b2Keys = mapOf("cloud" to "key"))
        val document = TransferDocument(luna = TRANSFER_FORMAT, values = mapOf(
            "network.smbPasswords" to JsonObject(mapOf("nas" to JsonArray(emptyList()))),
            "network.b2Keys" to JsonObject(mapOf("cloud" to JsonPrimitive(false))),
        ))

        val preview = SettingsPreviewer.of(document, source)
        val result = SettingsPreviewer.apply(preview, source, document.values.keys)

        assertTrue(preview.rows.all { it.verdict == TransferVerdict.UNREADABLE })
        assertEquals(source.passwords, result.source.passwords)
        assertEquals(source.b2Keys, result.source.b2Keys)
    }

    @Test fun `a nonfinite display scale is refused without changing preferences`() {
        val source = TransferSource()
        val document = TransferDocument(luna = TRANSFER_FORMAT, values = mapOf(
            "appearance.uiScale" to JsonPrimitive("NaN"),
        ))

        val preview = SettingsPreviewer.of(document, source)
        val result = SettingsPreviewer.apply(preview, source, document.values.keys)

        assertEquals(TransferVerdict.UNREADABLE, preview.rows.single().verdict)
        assertEquals(source.preferences, result.source.preferences)
    }

    @Test fun `a malformed folder view does not discard valid rows`() {
        val document = TransferDocument(luna = TRANSFER_FORMAT, values = mapOf(
            "files.folderViews" to JsonArray(listOf(
                JsonObject(mapOf("folder" to JsonObject(emptyMap()))),
                JsonObject(mapOf("folder" to JsonPrimitive("/photos"), "view" to JsonPrimitive("GRID_LARGE"),
                    "sort" to JsonPrimitive("NAME"), "descending" to JsonPrimitive(false))),
            )),
        ))

        val preview = SettingsPreviewer.of(document, TransferSource())
        val result = SettingsPreviewer.apply(preview, TransferSource(), document.values.keys)

        assertEquals(setOf("/photos"), result.source.folderViews.keys)
        assertTrue(result.refused.isEmpty())
    }
}
