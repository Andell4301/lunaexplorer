package com.lunaexplorer.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveRewriteTest {
    @get:Rule val temporary = TemporaryFolder()

    private val provider = MemoryStorageProvider("local")

    private fun place(name: String, bytes: ByteArray): NodeRef = runBlocking {
        val entry = provider.create(provider.root, name, directory = false, mimeType = "application/zip")
        provider.openWrite(entry.ref).use { it.write(bytes) }
        entry.ref
    }

    /** Names are stored exactly as given; a null body writes a folder entry ("name/"). */
    private fun zip(vararg members: Pair<String, String?>): ByteArray = zip4jBytes(
        members.toMap().mapValues { (_, body) -> body?.toByteArray() },
        encrypted = emptySet(), password = "", encryption = ZipEncryption.NONE, stored = false,
    )

    private fun lockedZip(members: Map<String, String?>, encrypted: Set<String>): ByteArray = zip4jBytes(
        members.mapValues { (_, body) -> body?.toByteArray() },
        encrypted = encrypted, password = "hunter2", encryption = ZipEncryption.AES_256, stored = false,
    )

    private fun rewriter() =
        ArchiveRewriter(ProviderRegistry(listOf(provider)), scratchDirectory = temporary.newFolder())

    /** Returns the published archive's ref; commit may hand back a new one, so the old ref must not be reused. */
    private fun rewritten(archive: NodeRef, edits: ArchiveEdits): NodeRef = runBlocking {
        val done = rewriter().rewrite(archive, edits).toList().last()
        assertTrue("The rewrite must finish", done.complete)
        requireNotNull(done.produced) { "A finished rewrite names the archive it published" }
    }

    private fun refusal(archive: NodeRef, edits: ArchiveEdits): Throwable? =
        runCatching { runBlocking { rewriter().rewrite(archive, edits).toList() } }.exceptionOrNull()

    private fun localCopy(ref: NodeRef): File = runBlocking {
        File(temporary.newFolder(), "read.zip").also { copy ->
            provider.openRead(ref).use { input -> copy.outputStream().use { input.copyTo(it) } }
        }
    }

    private fun membersOf(ref: NodeRef): Map<String, Boolean> =
        ZipFile(localCopy(ref)).use { zip -> zip.fileHeaders.associate { it.fileName to it.isEncrypted } }

    private fun decrypted(ref: NodeRef, member: String): String =
        ZipFile(localCopy(ref), "hunter2".toCharArray()).use { zip ->
            zip.getInputStream(zip.getFileHeader(member)).use { it.readBytes() }.decodeToString()
        }

    @Test fun `removing a member leaves the others exactly as they were`() {
        val archive = place("bundle.zip", zip("keep.txt" to "kept", "drop.txt" to "dropped"))

        val now = rewritten(archive, ArchiveEdits(removed = setOf("drop.txt")))

        assertEquals(setOf("keep.txt"), membersOf(now).keys)
    }

    @Test fun `renaming a member does not rename one whose name it prefixes`() {
        // zip4j renames by prefix: renameFile("doc", "paper") beside "document.txt" also produces
        // "paperument.txt". The rewriter must refuse up front instead of calling the library.
        val archive = place("bundle.zip", zip("doc" to "short", "document.txt" to "longer"))

        val failure = refusal(archive, ArchiveEdits(renamed = mapOf("doc" to "paper")))

        assertTrue("A rename that would touch another member must be refused, not attempted",
            failure is StorageException)
        // The message proves the prefix guard refused it, not the post-rewrite verification.
        assertTrue("The guard must be what refuses it, naming the member it would have hit: " +
            "${failure?.message}",
            failure?.message?.contains("would also rename") == true &&
                failure?.message?.contains("document.txt") == true)
        assertEquals("Nothing may have changed", setOf("doc", "document.txt"), membersOf(archive).keys)
    }

    @Test fun `renaming a member that prefixes nothing goes through`() {
        val archive = place("bundle.zip", zip("notes.txt" to "body", "other.txt" to "body"))

        val now = rewritten(archive, ArchiveEdits(renamed = mapOf("notes.txt" to "renamed.txt")))

        assertEquals(setOf("renamed.txt", "other.txt"), membersOf(now).keys)
    }

    @Test fun `an encrypted member survives a rewrite still encrypted`() {
        val archive = place("locked.zip", lockedZip(
            mapOf("secret.txt" to "classified", "spare.txt" to "also secret"),
            encrypted = setOf("secret.txt", "spare.txt"),
        ))
        assertTrue("The fixture has to actually be encrypted", membersOf(archive).getValue("secret.txt"))

        val now = rewritten(archive, ArchiveEdits(removed = setOf("spare.txt")))

        val after = membersOf(now)
        assertEquals(setOf("secret.txt"), after.keys)
        assertTrue("The survivor must still be encrypted", after.getValue("secret.txt"))
        assertEquals("classified", decrypted(now, "secret.txt"))
    }

    @Test fun `a format with no writer is refused before anything happens`() {
        val archive = place("bundle.rar", byteArrayOf(1, 2, 3))

        val failure = refusal(archive, ArchiveEdits(removed = setOf("x")))

        assertTrue(failure is StorageException)
        assertTrue("It must name the limitation rather than fail obscurely",
            failure?.message?.contains("not edited") == true || failure?.message?.contains("zip") == true)
    }

    @Test fun `a folder that cannot replace its children refuses the rewrite up front`() {
        provider.atomicReplace = false
        provider.recoverableReplace = false
        val archive = place("bundle.zip", zip("a.txt" to "a", "b.txt" to "b"))

        val failure = refusal(archive, ArchiveEdits(removed = setOf("b.txt")))

        assertTrue("Refused before rewriting anything", failure is StorageException)
        assertEquals("And the archive is untouched", setOf("a.txt", "b.txt"), membersOf(archive).keys)
    }

    @Test fun `a member name cannot be given a bar, which would strand its reference`() {
        val archive = place("bundle.zip", zip("a.txt" to "a"))

        val failure = refusal(archive, ArchiveEdits(renamed = mapOf("a.txt" to "we|rd.txt")))

        assertTrue(failure is StorageException)
        assertEquals(setOf("a.txt"), membersOf(archive).keys)
    }

    @Test fun `renaming a folder renames its entry and everything inside it`() {
        val archive = place("bundle.zip", zip(
            "docs" to null, "docs/a.txt" to "a", "docs/deep/b.txt" to "b", "top.txt" to "t",
        ))

        val now = rewritten(archive, ArchiveEdits(renamed = mapOf("docs" to "papers")))

        assertEquals(setOf("papers/", "papers/a.txt", "papers/deep/b.txt", "top.txt"), membersOf(now).keys)
    }

    @Test fun `renaming a folder the archive never recorded still re-files its contents`() {
        val archive = place("bundle.zip", zip("docs/a.txt" to "a", "docs/b.txt" to "b"))

        val now = rewritten(archive, ArchiveEdits(renamed = mapOf("docs" to "papers")))

        assertEquals(setOf("papers/a.txt", "papers/b.txt"), membersOf(now).keys)
    }

    @Test fun `renaming a folder leaves a file whose name it merely prefixes alone`() {
        // The stored folder name is "doc/", which does not prefix "document.txt".
        val archive = place("bundle.zip", zip("doc" to null, "doc/a.txt" to "a", "document.txt" to "d"))

        val now = rewritten(archive, ArchiveEdits(renamed = mapOf("doc" to "paper")))

        assertEquals(setOf("paper/", "paper/a.txt", "document.txt"), membersOf(now).keys)
    }

    @Test fun `removing a folder takes its entry and its contents`() {
        val archive = place("bundle.zip", zip("docs" to null, "docs/a.txt" to "a", "keep.txt" to "k"))

        val now = rewritten(archive, ArchiveEdits(removed = setOf("docs")))

        assertEquals(setOf("keep.txt"), membersOf(now).keys)
    }

    @Test fun `renaming a folder and renaming a file inside it to something else does both`() {
        // In one zip4j call the folder's prefix key would carry "docs/a.txt" to "papers/a.txt" and
        // lose the file's own new name, so the more specific rename has to go in an earlier pass.
        val archive = place("bundle.zip", zip("docs" to null, "docs/a.txt" to "a", "docs/b.txt" to "b"))

        val now = rewritten(archive, ArchiveEdits(
            renamed = mapOf("docs" to "papers", "docs/a.txt" to "papers/first.txt"),
        ))

        assertEquals(setOf("papers/", "papers/first.txt", "papers/b.txt"), membersOf(now).keys)
    }

    @Test fun `an encrypted folder survives its rename still encrypted`() {
        val archive = place("locked.zip", lockedZip(
            mapOf("docs" to null, "docs/secret.txt" to "classified"),
            encrypted = setOf("docs/secret.txt"),
        ))

        val now = rewritten(archive, ArchiveEdits(renamed = mapOf("docs" to "papers")))

        val after = membersOf(now)
        assertEquals(setOf("papers/", "papers/secret.txt"), after.keys)
        assertTrue("The renamed member must still be encrypted", after.getValue("papers/secret.txt"))
        assertEquals("classified", decrypted(now, "papers/secret.txt"))
    }

    @Test fun `an edit that reaches no member is refused rather than published unchanged`() {
        val archive = place("bundle.zip", zip("a.txt" to "a"))

        val failure = refusal(archive, ArchiveEdits(renamed = mapOf("gone" to "new")))

        assertTrue("A rename of something absent must be refused: ${failure?.message}",
            failure is StorageException && failure.reason == StorageError.NOT_FOUND)
        assertEquals(setOf("a.txt"), membersOf(archive).keys)
    }

    @Test fun `deleting a file does not take a folder that happens to share its name`() {
        val archive = place("bundle.zip", zip("docs" to "i am a file", "docs/a.txt" to "a"))

        val now = rewritten(archive, ArchiveEdits(removed = setOf("docs")))

        assertEquals(setOf("docs/a.txt"), membersOf(now).keys)
    }

    @Test fun `renaming a file is refused when a folder shares its name`() {
        // zip4j's prefix rename of the file "docs" would also re-home "docs/a.txt".
        val archive = place("bundle.zip", zip("docs" to "i am a file", "docs/a.txt" to "a"))

        val failure = refusal(archive, ArchiveEdits(renamed = mapOf("docs" to "notes")))

        assertTrue("It must say what it would have disturbed: ${failure?.message}",
            failure is StorageException && failure.message?.contains("docs/a.txt") == true)
        assertEquals("And nothing may have moved", setOf("docs", "docs/a.txt"), membersOf(archive).keys)
    }

    @Test fun `a folder stored with backslashes renames completely`() {
        // Zips written on Windows may separate names with backslashes; members are listed with slashes.
        val archive = place("bundle.zip", zip("docs/a.txt" to "a", "docs\\b.txt" to "b", "top.txt" to "t"))

        val now = rewritten(archive, ArchiveEdits(renamed = mapOf("docs" to "papers")))

        assertEquals(setOf("papers/a.txt", "papers/b.txt", "top.txt"), membersOf(now).keys)
    }

    @Test fun `a member stored behind a dot prefix can still be edited`() {
        // Some writers store "./name"; it is listed as "name".
        val archive = place("bundle.zip", zip("./notes.txt" to "n", "top.txt" to "t"))

        val now = rewritten(archive, ArchiveEdits(renamed = mapOf("notes.txt" to "renamed.txt")))

        assertEquals(setOf("renamed.txt", "top.txt"), membersOf(now).keys)
    }

    @Test fun `deleting a folder stored with backslashes takes all of it`() {
        val archive = place("bundle.zip", zip("docs/a.txt" to "a", "docs\\b.txt" to "b", "keep.txt" to "k"))

        val now = rewritten(archive, ArchiveEdits(removed = setOf("docs")))

        assertEquals(setOf("keep.txt"), membersOf(now).keys)
    }
}
