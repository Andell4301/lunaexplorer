package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.data.SessionCodec
import com.lunaexplorer.app.model.Overlay
import com.lunaexplorer.core.OperationType
import com.lunaexplorer.core.ArchiveFormat
import com.lunaexplorer.core.ArchiveOptions
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import com.lunaexplorer.core.ZipEncryption
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// SDK 35: Robolectric's SDK 36 framework jar bundles an older commons-compress that shadows the
// one the archive engine is built against.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class ArchivePasswordTest {
    @get:Rule val harness = BrowserViewModelHarness().startingWith { folder ->
        File(folder, "secret.txt").writeText("top secret")
        File(folder, "hdr.7z").writeBytes(TestArchives.HEADER_ENCRYPTED_7Z)
    }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state
    private val graph get() = harness.graph

    private fun ref(file: File): NodeRef = requireNotNull(graph.local.referenceTo(file.path))
    private fun ref(name: String): NodeRef = ref(File(harness.directory, name))
    private fun entry(name: String): Entry = runBlocking { graph.local.stat(ref(name)) }

    private fun encryptedZip(password: String = "hunter2"): Entry = runBlocking {
        val produced = graph.archives.create(listOf(ref("secret.txt")), ref(harness.directory), "locked.zip",
            ArchiveOptions(ArchiveFormat.ZIP, encryption = ZipEncryption.AES_256, password = password)).last().produced
        graph.local.stat(requireNotNull(produced))
    }

    private fun browseInto(archive: Entry): List<String> {
        viewModel.browseArchive(archive)
        assertTrue("Lands inside ${archive.name}", harness.awaitUntil {
            state.location?.ref?.provider == graph.insideArchives.id && state.entries.isNotEmpty() && !state.loading
        })
        assertNull(state.openingArchive)
        return state.entries.map { it.name }.sorted()
    }

    @Test fun `an encrypted zip lists its members and a member is unlocked through the prompt`() {
        assertTrue(harness.awaitUntil { state.ready })
        val archive = encryptedZip()

        assertEquals("Listing needs no password", listOf("secret.txt"), browseInto(archive))
        val member = state.entries.single()
        assertTrue(graph.insideArchives.needsPassword(member.ref))

        var ran = 0
        viewModel.requireArchivePassword(member) { ran++ }
        harness.idle()
        val prompt = state.overlay as? Overlay.ArchivePassword
        assertEquals("The prompt names the member", member.name, prompt?.title)
        assertEquals("Nothing runs before the password is verified", 0, ran)

        viewModel.unlockArchive("wrong")
        assertTrue(harness.awaitUntil { (state.overlay as? Overlay.ArchivePassword)?.error != null })
        assertFalse((state.overlay as Overlay.ArchivePassword).verifying)
        assertEquals(0, ran)
        assertFalse("A wrong password is not kept", graph.insideArchives.hasPassword(member.ref))

        viewModel.unlockArchive("hunter2")
        assertTrue(harness.awaitUntil { ran == 1 })
        assertNull("The prompt closes once the password is verified", state.overlay)
        assertTrue(graph.insideArchives.hasPassword(member.ref))

        viewModel.requireArchivePassword(member) { ran++ }
        assertEquals(2, ran)
        assertNull(state.overlay)
    }

    @Test fun `giving up on the prompt runs nothing`() {
        assertTrue(harness.awaitUntil { state.ready })
        browseInto(encryptedZip())
        val member = state.entries.single()
        var ran = 0
        viewModel.requireArchivePassword(listOf(member)) { ran++ }
        assertTrue(state.overlay is Overlay.ArchivePassword)
        viewModel.dismissArchivePassword()
        harness.idle()
        assertNull(state.overlay)
        assertEquals(0, ran)
        assertFalse(graph.insideArchives.hasPassword(member.ref))
    }

    @Test fun `a header-encrypted 7z asks for its password before it opens`() {
        assertTrue(harness.awaitUntil { state.ready })
        val archive = entry("hdr.7z")
        val home = state.location?.ref
        val message = state.message

        viewModel.browseArchive(archive)
        assertTrue(harness.awaitUntil { state.overlay is Overlay.ArchivePassword })
        assertNull("The opening strip is gone while the prompt waits", state.openingArchive)
        assertEquals(archive.name, (state.overlay as Overlay.ArchivePassword).title)
        assertEquals("Locked archives are not reported as failures", message, state.message)

        viewModel.unlockArchive("wrong")
        assertTrue(harness.awaitUntil { (state.overlay as? Overlay.ArchivePassword)?.error != null })
        assertEquals(home, state.location?.ref)

        viewModel.unlockArchive(TestArchives.HEADER_ENCRYPTED_7Z_PASSWORD)
        assertTrue("Lands inside once the right password opens the index", harness.awaitUntil {
            state.location?.ref?.provider == graph.insideArchives.id &&
                state.entries.map { it.name }.sorted() == listOf("docs", "readme.txt")
        })
        assertNull(state.overlay)
        assertTrue("The password given at opening is kept for member reads",
            graph.insideArchives.hasPassword(state.entries.first().ref))
    }

    @Test fun `a wrong password is refused by the engine with its own reason`() {
        assertTrue(harness.awaitUntil { state.ready })
        val archive = encryptedZip()

        val refused = runCatching {
            runBlocking { graph.archives.extract(archive.ref, ref(""), "probe", "wrong").last() }
        }.exceptionOrNull() as StorageException
        assertEquals(StorageError.AUTH, refused.reason)
        assertFalse("Nothing is extracted with a wrong password", File(harness.directory, "probe/secret.txt").exists())

        runBlocking { graph.archives.extract(archive.ref, ref(""), "unpacked", "hunter2").last() }
        val extracted = File(harness.directory, "unpacked/secret.txt")
        assertTrue(extracted.exists() && extracted.readText() == "top secret")
    }

    @Test fun `extracting an encrypted zip is queued without writing its password down`() {
        assertTrue(harness.awaitUntil { state.ready })
        val archive = encryptedZip()

        viewModel.operations.extractArchive(archive, "unpacked", "hunter2")
        assertTrue(harness.awaitUntil { state.operations.any { it.title == "Extract locked.zip" } })
        val id = state.operations.single { it.title == "Extract locked.zip" }.id
        val stored = requireNotNull(runBlocking { graph.database.request(id) })
        assertEquals(OperationType.EXTRACT_ARCHIVE, stored.type)
        assertEquals("unpacked", stored.name)
        assertTrue("The spec must record that a password is needed", requireNotNull(stored.archive).needsPassword)
        assertFalse("The password must not reach storage",
            SessionCodec.json.encodeToString(stored).contains("hunter2"))
    }

    @Test fun `creating an encrypted archive queues a spec without the password`() {
        assertTrue(harness.awaitUntil { state.ready })
        val source = entry("secret.txt")

        viewModel.operations.createArchive(listOf(source), "kept.zip",
            ArchiveOptions(ArchiveFormat.ZIP, encryption = ZipEncryption.AES_256, password = "hunter2"))
        assertTrue(harness.awaitUntil { state.operations.any { it.title == "Create archive kept.zip" } })
        val id = state.operations.single { it.title == "Create archive kept.zip" }.id
        val stored = requireNotNull(runBlocking { graph.database.request(id) })
        assertEquals(OperationType.CREATE_ARCHIVE, stored.type)
        assertEquals(ZipEncryption.AES_256, requireNotNull(stored.archive).encryption)
        assertTrue(requireNotNull(stored.archive).needsPassword)
        assertFalse("The password must not reach storage",
            SessionCodec.json.encodeToString(stored).contains("hunter2"))
    }
}
