package com.lunaexplorer.app.ui

import android.net.Uri
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.data.*
import com.lunaexplorer.app.storage.b2.B2Account
import com.lunaexplorer.app.storage.smb.SmbAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import com.lunaexplorer.app.model.*

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class SettingsTransferTest {
    @get:Rule val harness = BrowserViewModelHarness().startingWith { dir ->
        File(dir, "kept").mkdirs()
    }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state
    private val transfer get() = viewModel.transfer

    private fun ready() = assertTrue(harness.awaitUntil { state.ready })

    private fun fileUri(name: String): Uri = Uri.fromFile(File(harness.directory, name))

    private fun exportAll(name: String = "out.json"): File {
        val ids = SettingsRegistry.units.mapTo(HashSet()) { it.id }
        transfer.prepareExport(ids, withPasswords = false)
        var done = false
        transfer.onExportTarget(fileUri(name)) { done = true }
        assertTrue("The export never finished", harness.awaitUntil { done })
        return File(harness.directory, name)
    }

    @Test fun `a picked file with no request behind it writes nothing`() {
        ready()
        // The Activity can be recreated while the save dialog is up, so a target can arrive with no request.
        var done = true
        transfer.onExportTarget(fileUri("orphan.json")) { done = it }
        assertTrue(harness.awaitUntil { !done })
        assertFalse("Nothing may be written", File(harness.directory, "orphan.json").exists())
        assertNotNull(state.message)
    }

    @Test fun `an export carries the settings as they are and reads back as no change`() {
        ready()
        viewModel.setPreferences(state.preferences.copy(
            theme = ThemeMode.DARK, uiScale = 1.25f, confirmDelete = false,
            warnLargeDelete = true, largeDeleteGb = 7,
        ))
        harness.idle()

        val written = exportAll()
        assertTrue("The file must exist", written.exists())
        val document = requireNotNull(TransferCodec.decode(written.readText()))
        assertEquals(TRANSFER_FORMAT, document.luna)
        val expected = SettingsRegistry.units.filter { it.read(transfer.snapshot()) != null }.map { it.id }
        assertEquals("Every setting that had something to say was written",
            emptyList<String>(), expected.filterNot { it in document.values.keys })

        var opened = false
        transfer.openForImport(Uri.fromFile(written)) { opened = it }
        assertTrue(harness.awaitUntil { opened && transfer.preview.value != null })
        val preview = requireNotNull(transfer.preview.value)
        val changing = preview.changing
        assertTrue("A file just written here must propose no changes: ${changing.map { it.id }}",
            changing.isEmpty())
        transfer.dropImport()
    }

    @Test fun `importing puts the settings from the file into effect`() {
        ready()
        viewModel.setPreferences(state.preferences.copy(theme = ThemeMode.DARK, largeDeleteGb = 7,
            warnLargeDelete = true, videoExitBehavior = VideoExitBehavior.BACKGROUND, audioBackground = false))
        harness.idle()
        val written = exportAll()

        viewModel.setPreferences(state.preferences.copy(theme = ThemeMode.LIGHT, largeDeleteGb = 3,
            warnLargeDelete = false, videoExitBehavior = VideoExitBehavior.OFF, audioBackground = true))
        harness.idle()

        var opened = false
        transfer.openForImport(Uri.fromFile(written)) { opened = it }
        assertTrue(harness.awaitUntil { opened && transfer.preview.value != null })
        val preview = requireNotNull(transfer.preview.value)
        assertTrue("The theme must be offered as a replacement",
            preview.rows.any { it.id == "appearance.theme" && it.verdict == TransferVerdict.REPLACES })

        var done = false
        transfer.import(preview.suggested, emptyMap()) { done = true }
        assertTrue(harness.awaitUntil { done })

        assertEquals(ThemeMode.DARK, state.preferences.theme)
        assertEquals(7, state.preferences.largeDeleteGb)
        assertTrue(state.preferences.warnLargeDelete)
        assertEquals(VideoExitBehavior.BACKGROUND, state.preferences.videoExitBehavior)
        assertFalse(state.preferences.audioBackground)
        assertNull("The file is put down once it has been read", transfer.preview.value)
    }

    @Test fun `only the ticked settings are taken`() {
        ready()
        viewModel.setPreferences(state.preferences.copy(theme = ThemeMode.DARK, sections = true))
        harness.idle()
        val written = exportAll()

        viewModel.setPreferences(state.preferences.copy(theme = ThemeMode.LIGHT, sections = false))
        harness.idle()

        var opened = false
        transfer.openForImport(Uri.fromFile(written)) { opened = it }
        assertTrue(harness.awaitUntil { opened && transfer.preview.value != null })

        var done = false
        transfer.import(setOf("appearance.theme"), emptyMap()) { done = true }
        assertTrue(harness.awaitUntil { done })

        assertEquals("The one that was ticked", ThemeMode.DARK, state.preferences.theme)
        assertFalse("And nothing else", state.preferences.sections)
    }

    @Test fun `a bookmark whose folder is not here still imports, and says so`() {
        ready()
        viewModel.bookmarks.add("Gone", "/nowhere/at/all/on/this/device")
        assertTrue(harness.awaitUntil { state.bookmarks.any { it.title == "Gone" } })
        val written = exportAll()

        var opened = false
        transfer.openForImport(Uri.fromFile(written)) { opened = it }
        assertTrue(harness.awaitUntil { opened && transfer.preview.value != null })

        val row = requireNotNull(transfer.preview.value).rows.first { it.id == "bookmarks.sidebar" }
        assertTrue("The warning the file cannot give itself",
            row.missing.any { it.label == "Gone" })
        assertTrue("But it is still offered, not refused", row.usable)
        transfer.dropImport()
    }

    @Test fun `a single entry can be left behind`() {
        ready()
        viewModel.bookmarks.add("First", harness.directory.absolutePath)
        viewModel.bookmarks.add("Second", File(harness.directory, "kept").absolutePath)
        assertTrue(harness.awaitUntil { state.bookmarks.size == 2 })
        val written = exportAll()

        val dropped = state.bookmarks.first { it.title == "Second" }.id
        viewModel.bookmarks.remove(state.bookmarks.first { it.title == "First" })
        viewModel.bookmarks.remove(state.bookmarks.first { it.title == "Second" })
        assertTrue(harness.awaitUntil { state.bookmarks.isEmpty() })

        var opened = false
        transfer.openForImport(Uri.fromFile(written)) { opened = it }
        assertTrue(harness.awaitUntil { opened && transfer.preview.value != null })

        var done = false
        transfer.import(setOf("bookmarks.sidebar"),
            mapOf("bookmarks.sidebar" to (requireNotNull(transfer.preview.value)
                .rows.first { it.id == "bookmarks.sidebar" }
                .items.map { it.key }.filterNot { it == dropped }.toSet()))) { done = true }
        assertTrue(harness.awaitUntil { done })

        assertEquals("Only the entry that stayed ticked", listOf("First"), state.bookmarks.map { it.title })
    }

    @Test fun `a value the app would never produce is refused rather than saved`() {
        ready()
        // uiScale is otherwise clamped only by its slider; a bad networkThumbnails would throw while
        // the session is decoded and lose every other setting.
        val hostile = """
            {"luna":1,"app":"hand written","written":0,"values":{
              "appearance.uiScale": 40.0,
              "network.smbThumbnails": "not a number",
              "deleting.largeDeleteGb": 0,
              "appearance.theme": "DARK"
            }}
        """.trimIndent()
        val file = File(harness.directory, "hostile.json").apply { writeText(hostile) }

        var opened = false
        transfer.openForImport(Uri.fromFile(file)) { opened = it }
        assertTrue(harness.awaitUntil { opened && transfer.preview.value != null })
        val preview = requireNotNull(transfer.preview.value)

        listOf("appearance.uiScale", "network.smbThumbnails", "deleting.largeDeleteGb").forEach { id ->
            val row = preview.rows.first { it.id == id }
            assertEquals("$id must be refused", TransferVerdict.UNREADABLE, row.verdict)
            assertTrue("And say what it would have taken", row.refusal.isNotBlank())
        }
        assertFalse("A refused row cannot be ticked by default", preview.suggested.contains("appearance.uiScale"))

        var done = false
        transfer.import(preview.suggested, emptyMap()) { done = true }
        assertTrue(harness.awaitUntil { done })
        assertEquals(ThemeMode.DARK, state.preferences.theme)
        assertEquals("The hostile size never reached the model", 1f, state.preferences.uiScale, 0.001f)
        assertEquals(NetworkThumbnails.MB_25, state.preferences.thumbnailsOn("smb"))
    }

    @Test fun `the vault lock is only ever changed by rewriting the vault`() {
        ready()
        // With no screen lock the vault cannot be rewritten under the protected key, so setLocked refuses.
        harness.vaultKeys.screenLock = false
        // vaultLocked names the key the vault file is sealed with; setting it without rewriting the
        // vault would leave every password undecryptable.
        val claim = """
            {"luna":1,"app":"another device","written":0,"values":{"network.vaultLocked": true}}
        """.trimIndent()
        val file = File(harness.directory, "locked.json").apply { writeText(claim) }

        var opened = false
        transfer.openForImport(Uri.fromFile(file)) { opened = it }
        assertTrue(harness.awaitUntil { opened && transfer.preview.value != null })

        var done = false
        transfer.import(setOf("network.vaultLocked"), emptyMap()) { done = true }
        assertTrue(harness.awaitUntil { done })

        assertFalse("The flag may not run ahead of the vault it describes", state.vaultLocked)
        assertNotNull("And the refusal is reported rather than swallowed", state.message)
    }

    @Test fun `a file that is not an export is refused without a preview`() {
        ready()
        val file = File(harness.directory, "notes.txt").apply { writeText("just some text") }
        var opened = true
        transfer.openForImport(Uri.fromFile(file)) { opened = it }
        assertTrue(harness.awaitUntil { !opened })
        assertNull(transfer.preview.value)
        assertNotNull("And it says so", state.message)
    }

    @Test fun `a plain JSON file is not mistaken for an export`() {
        ready()
        // The format stamp must not default, or any JSON object would decode as an export.
        val file = File(harness.directory, "other.json").apply { writeText("""{"hello":"world"}""") }
        var opened = true
        transfer.openForImport(Uri.fromFile(file)) { opened = it }
        assertTrue(harness.awaitUntil { !opened })
        assertNull(transfer.preview.value)
    }

    @Test fun `an SMB server travels without its password unless it was asked for`() {
        ready()
        viewModel.smb.save(SmbAccount(id = "server-1", name = "Vault", host = "example.test"), password = null)
        assertTrue(harness.awaitUntil { state.smbAccounts.any { it.id == "server-1" } })

        val written = exportAll()
        val document = requireNotNull(TransferCodec.decode(written.readText()))

        assertNotNull("The server itself travels", document.values["network.smb"])
        assertNull("But the file carries no passwords key at all without authentication",
            document.values["network.smbPasswords"])
    }

    @Test fun `a B2 account travels without a field for its application key`() {
        ready()
        viewModel.b2.save(B2Account(id = "cloud-1", name = "Cloud", keyId = "0012ab"), key = null)
        assertTrue(harness.awaitUntil { state.b2Accounts.any { it.id == "cloud-1" } })

        val written = exportAll()
        val document = requireNotNull(TransferCodec.decode(written.readText()))

        assertNotNull("The account itself travels", document.values["network.b2"])
        assertFalse("With no application key tucked inside it", written.readText().contains("applicationKey"))
    }
}
