package com.lunaexplorer.app.data

import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.NetworkThumbnails
import com.lunaexplorer.app.model.Preferences
import com.lunaexplorer.app.storage.transfer.FtpSecurity
import com.lunaexplorer.app.storage.transfer.TransferAccount
import com.lunaexplorer.app.storage.transfer.TransferAuthentication
import com.lunaexplorer.app.storage.transfer.TransferCredentials
import com.lunaexplorer.app.storage.transfer.TransferProtocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Test

class TransferSettingsTest {
    private val ftp = TransferAccount(id = "ftp", name = " FTP ", protocol = TransferProtocol.FTP,
        host = "ftp.test", port = 990, rootPath = "/ folder /", username = " ftp-user ",
        anonymous = true, security = FtpSecurity.IMPLICIT_TLS, timeoutSeconds = 75)
    private val sftp = TransferAccount(id = "sftp", name = " SFTP ", protocol = TransferProtocol.SFTP,
        host = "sftp.test", port = 2222, rootPath = "/ backups /", username = " ssh-user ",
        authentication = TransferAuthentication.PRIVATE_KEY, timeoutSeconds = 90,
        hostKeyFingerprint = "SHA256:accepted-host-key")
    private val source = TransferSource(transferAccounts = listOf(ftp, sftp), preferences = Preferences(
        networkThumbnails = mapOf("ftp" to NetworkThumbnails.OFF, "sftp" to NetworkThumbnails.ANY)))
    private val ids = setOf("network.transfer", "network.ftpThumbnails", "network.sftpThumbnails")

    @Test fun `saved sessions restore server metadata including trust without credential fields`() {
        val raw = SessionCodec.encode(BrowserState(transferAccounts = source.transferAccounts, preferences = source.preferences))
        val restored = SessionCodec.decode(raw)

        assertEquals(source.transferAccounts, restored.transferAccounts)
        assertEquals(source.preferences.networkThumbnails, restored.preferences.networkThumbnails)
        assertFalse(raw.contains("\"password\""))
        assertFalse(raw.contains("\"privateKey\""))
        assertFalse(raw.contains("\"passphrase\""))
    }

    @Test fun `server settings and independent thumbnail limits export and import without credentials`() {
        val document = TransferCodec.export(source, ids, "test", 0)
        val restoredDocument = requireNotNull(TransferCodec.decode(TransferCodec.encode(document)))
        val current = TransferSource()
        val preview = SettingsPreviewer.of(restoredDocument, current)
        val restored = SettingsPreviewer.apply(preview, current, ids).source

        assertEquals(ids, document.values.keys)
        assertEquals(source.transferAccounts, restored.transferAccounts)
        assertEquals(NetworkThumbnails.OFF, restored.preferences.thumbnailsOn("ftp"))
        assertEquals(NetworkThumbnails.ANY, restored.preferences.thumbnailsOn("sftp"))
        assertTrue(restored.transferCredentials.isEmpty())
    }

    @Test fun `a selected imported server updates its settings and keeps other servers`() {
        val changed = sftp.copy(name = " Changed ", rootPath = "/ new /", port = 2200)
        val document = TransferCodec.export(source.copy(transferAccounts = listOf(ftp.copy(host = "other.test"), changed)),
            ids, "test", 0)
        val preview = SettingsPreviewer.of(document, source)
        val restored = SettingsPreviewer.apply(preview, source, setOf("network.transfer"),
            mapOf("network.transfer" to setOf(sftp.id))).source

        assertEquals(listOf(ftp, changed), restored.transferAccounts)
    }

    @Test fun `credential exports import selected passwords private keys and passphrases`() {
        val credentials = mapOf(ftp.id to TransferCredentials(password = "ftp password"),
            sftp.id to TransferCredentials(privateKey = "key bytes\n", passphrase = "pass phrase"))
        val id = "network.transferCredentials"
        val document = TransferCodec.export(source.copy(transferCredentials = credentials), setOf(id), "test", 0)
        val preview = SettingsPreviewer.of(document, source)
        val restored = SettingsPreviewer.apply(preview, source, setOf(id), mapOf(id to setOf(sftp.id))).source

        assertTrue(SettingsRegistry.unit(id)!!.sensitive)
        assertEquals(mapOf(sftp.id to credentials.getValue(sftp.id)), restored.transferCredentials)
        assertTrue(preview.rows.single().items.none { it.detail.contains("key bytes") || it.detail.contains("pass phrase") })
    }

    @Test fun `malformed imported credentials cannot replace a kept private key`() {
        val id = "network.transferCredentials"
        val original = source.copy(transferCredentials = mapOf(sftp.id to TransferCredentials(privateKey = "kept")))
        val document = TransferDocument(luna = TRANSFER_FORMAT, values = mapOf(id to JsonObject(mapOf(
            sftp.id to JsonObject(mapOf("privateKey" to JsonPrimitive(false))),
        ))))
        val preview = SettingsPreviewer.of(document, original)
        val result = SettingsPreviewer.apply(preview, original, setOf(id))

        assertEquals(original.transferCredentials, result.source.transferCredentials)
        assertEquals(TransferVerdict.UNREADABLE, preview.rows.single().verdict)
    }

    @Test fun `invalid imported endpoints cannot replace a working server`() {
        val invalid = listOf(ftp.copy(id = "bad:id"), ftp.copy(id = ""), ftp.copy(host = ""),
            ftp.copy(port = 0), ftp.copy(port = 65_536), ftp.copy(timeoutSeconds = 0),
            ftp.copy(timeoutSeconds = 601), ftp.copy(rootPath = ""), ftp.copy(rootPath = "/bad\r\npath"))
        invalid.forEach { account ->
            val document = TransferDocument(luna = TRANSFER_FORMAT, values = mapOf(
                "network.transfer" to JsonArray(listOf(TransferCodec.json.encodeToJsonElement(TransferAccount.serializer(), account)))))
            val preview = SettingsPreviewer.of(document, source)
            val result = SettingsPreviewer.apply(preview, source, setOf("network.transfer"))

            assertEquals(TransferVerdict.UNREADABLE, preview.rows.single().verdict)
            assertEquals(source.transferAccounts, result.source.transferAccounts)
        }
    }

    @Test fun `malformed server structures are reported instead of importing an empty list`() {
        listOf(JsonObject(emptyMap()), JsonArray(listOf(JsonPrimitive("server")))).forEach { malformed ->
            val document = TransferDocument(luna = TRANSFER_FORMAT, values = mapOf("network.transfer" to malformed))
            val preview = SettingsPreviewer.of(document, source)

            assertEquals(TransferVerdict.UNREADABLE, preview.rows.single().verdict)
            assertEquals(source.transferAccounts,
                SettingsPreviewer.apply(preview, source, setOf("network.transfer")).source.transferAccounts)
        }
    }
}
