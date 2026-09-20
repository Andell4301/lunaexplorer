package com.lunaexplorer.core

import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.channels.SeekableByteChannel

class ArchiveMemberEditTest {
    @get:Rule val temporary = TemporaryFolder()

    /**
     * Seekable like local storage. Without a channel an encrypted zip is staged to a read-only copy
     * and cannot be edited.
     */
    private class Seekable(private val inner: MemoryStorageProvider) : StorageProvider by inner {
        var commits = 0
            private set

        override suspend fun openChannel(ref: NodeRef): SeekableByteChannel =
            SeekableInMemoryByteChannel(inner.openRead(ref).use { it.readBytes() })

        override suspend fun commit(
            staged: NodeRef,
            parent: NodeRef,
            name: String,
            replace: Entry?,
            onRetained: RetainedObjects?,
        ): Entry {
            commits++
            return inner.commit(staged, parent, name, replace, onRetained)
        }
    }

    private lateinit var archiveRoot: NodeRef
    private lateinit var provider: MemoryStorageProvider
    private lateinit var archives: ArchiveProvider
    private lateinit var registry: ProviderRegistry
    private lateinit var storage: Seekable

    private fun open(name: String, bytes: ByteArray): NodeRef {
        provider = MemoryStorageProvider("local")
        archives = ArchiveProvider({ registry }, stagingDirectory = temporary.newFolder())
        storage = Seekable(provider)
        registry = ProviderRegistry(listOf(storage, archives))
        return alsoOpen(name, bytes).also { archiveRoot = it }
    }

    private fun alsoOpen(name: String, bytes: ByteArray): NodeRef = runBlocking {
        val entry = provider.create(provider.root, name, directory = false, mimeType = "application/zip")
        provider.openWrite(entry.ref).use { it.write(bytes) }
        archives.open(entry.ref, name)
    }

    /**
     * The stored archive file, read with zip4j. The provider lists held writes as if applied, so
     * only this shows what was written.
     */
    private fun <T> stored(root: NodeRef, read: (ZipFile) -> T): T = runBlocking {
        // A rewrite's commit may return a new ref for the archive file.
        val file = requireNotNull(archives.sourceOf(root))
        val copy = File(temporary.newFolder(), "read.zip")
        provider.openRead(file).use { input -> copy.outputStream().use { input.copyTo(it) } }
        ZipFile(copy).use { read(it) }
    }

    private fun onDisk(root: NodeRef = archiveRoot): List<String> =
        stored(root) { zip -> zip.fileHeaders.map { it.fileName }.sorted() }

    private fun bodiesOnDisk(): Map<String, String> = stored(archiveRoot) { zip ->
        zip.fileHeaders.filter { !it.isDirectory }.associate { header ->
            header.fileName to zip.getInputStream(header).use { it.readBytes() }.decodeToString()
        }
    }

    /** A null body writes a folder entry ("name/"). */
    private fun zip(vararg members: Pair<String, String?>): ByteArray = zip4jBytes(
        members.toMap().mapValues { (_, body) -> body?.toByteArray() },
        encrypted = emptySet(), password = "", encryption = ZipEncryption.NONE, stored = false,
    )

    /**
     * Opened without its password this lists and is editable, but any addition fails when the run
     * is flushed.
     */
    private fun lockedZip(vararg members: Pair<String, String>): ByteArray = zip4jBytes(
        members.associate { (name, body) -> name to body.toByteArray() },
        encrypted = members.map { it.first }.toSet(), password = "hunter2",
        encryption = ZipEncryption.AES_256, stored = false,
    )

    private fun members(root: NodeRef): List<Entry> =
        runBlocking { archives.list(root, complete = true).last() }

    private fun names(root: NodeRef): List<String> = members(root).map { it.name }.sorted()

    private fun refIn(root: NodeRef, path: String): NodeRef =
        members(root).first { it.name == path }.ref

    private fun textOf(ref: NodeRef): String = runBlocking { archives.text(ref) }

    private suspend fun listing(root: NodeRef): List<String> =
        archives.list(root, complete = true).last().map { it.name }.sorted()

    private suspend fun within(root: NodeRef, name: String): NodeRef =
        archives.list(root, complete = true).last().first { it.name == name }.ref

    private fun inOneRun(body: suspend () -> Unit) = runBlocking {
        val run = StorageRun()
        withContext(run) { body() }
        archives.flush(run)
    }

    private fun run(
        type: OperationType,
        sources: List<NodeRef>,
        destination: NodeRef,
        policy: ConflictPolicy = ConflictPolicy.ASK,
        name: String? = null,
    ): OperationResult = runBlocking {
        OperationEngine(registry).run(
            OperationRequest(type = type, sources = sources, destination = destination,
                conflictPolicy = policy, name = name),
        )
    }

    private fun loose(name: String, body: String): Entry = runBlocking {
        val entry = provider.create(provider.root, name, directory = false, mimeType = "text/plain")
        provider.openWrite(entry.ref).use { it.write(body.toByteArray()) }
        provider.stat(entry.ref)
    }

    private fun folder(name: String, files: Map<String, String>, empty: List<String> = emptyList()): Entry =
        runBlocking {
            val top = provider.create(provider.root, name, directory = true, mimeType = "")
            for ((path, body) in files) {
                var parent = top.ref
                val parts = path.split('/')
                for (segment in parts.dropLast(1)) {
                    parent = provider.create(parent, segment, directory = true, mimeType = "").ref
                }
                val file = provider.create(parent, parts.last(), directory = false, mimeType = "text/plain")
                provider.openWrite(file.ref).use { it.write(body.toByteArray()) }
            }
            for (path in empty) provider.create(top.ref, path, directory = true, mimeType = "")
            provider.stat(top.ref)
        }

    @Test fun `a zip on writable storage offers rename and delete on members and create on its root`() {
        val root = open("bundle.zip", zip("one.txt" to "first", "two.txt" to "second"))
        val member = members(root).first { it.name == "one.txt" }

        assertTrue("Rewritable, so its members offer rename", Capability.RENAME in member.capabilities)
        assertTrue(Capability.DELETE in member.capabilities)
        assertTrue("Which is what lets files be pasted or dropped in",
            Capability.CREATE in runBlocking { archives.stat(root) }.capabilities)
    }

    @Test fun `a tar can be browsed but never edited`() {
        val root = open("bundle.tar", tarBytes(ArchiveFormat.TAR, mapOf("one.txt" to "first".toByteArray())))
        val member = members(root).first()

        assertFalse("No writer exists for tar, so nothing may be renamed in it",
            Capability.RENAME in member.capabilities)
        assertFalse(Capability.DELETE in member.capabilities)

        val failure = runCatching { runBlocking { archives.delete(member.ref) } }.exceptionOrNull()
        assertTrue("And asking anyway is refused", failure is StorageException)
    }

    @Test fun `an archive on storage that cannot replace a child is not editable`() {
        provider = MemoryStorageProvider("local").apply { atomicReplace = false; recoverableReplace = false }
        archives = ArchiveProvider({ registry }, stagingDirectory = temporary.newFolder())
        registry = ProviderRegistry(listOf(provider, archives))
        val root = alsoOpen("bundle.zip", zip("a.txt" to "a"))
        val member = members(root).first()

        // Publishing a rewrite would have to delete the original first.
        assertFalse(Capability.DELETE in member.capabilities)
    }

    @Test fun `a file can be written into an archive`() {
        val root = open("bundle.zip", zip("one.txt" to "first"))
        val loose = loose("added.txt", "new body")

        run(OperationType.COPY, listOf(loose.ref), root)

        assertEquals(listOf("added.txt", "one.txt"), names(root))
        assertEquals("new body", textOf(refIn(root, "added.txt")))
    }

    @Test fun `a name already in the archive is refused rather than duplicated`() {
        val root = open("bundle.zip", zip("one.txt" to "first"))
        val clash = loose("one.txt", "different")

        val result = run(OperationType.COPY, listOf(clash.ref), root)

        assertEquals(ItemStatus.CONFLICT, result.outcomes.single().status)
        assertEquals(listOf("one.txt"), names(root))
    }

    @Test fun `adding to an encrypted archive without its password is refused`() {
        val root = open("locked.zip", lockedZip("secret.txt" to "classified"))
        val loose = loose("added.txt", "plain")

        // The refusal comes from the flush at the end of the run, so it is thrown, not reported per item.
        val failure = runCatching { run(OperationType.COPY, listOf(loose.ref), root) }.exceptionOrNull()

        assertTrue("Refused rather than added unprotected", failure is StorageException)
        assertTrue("And it says the archive has to be unlocked first: ${failure?.message}",
            failure?.message?.contains("Unlock") == true)
        assertEquals(listOf("secret.txt"), names(root))
    }

    @Test fun `an unlocked encrypted archive protects what is added to it`() {
        val root = open("locked.zip", lockedZip("secret.txt" to "classified"))
        runBlocking { archives.unlock(root, "hunter2") }
        val loose = loose("added.txt", "also secret")

        run(OperationType.COPY, listOf(loose.ref), root)

        assertEquals(listOf("added.txt", "secret.txt"), names(root))
        runBlocking { archives.unlock(root, "hunter2") }
        assertEquals("The new member joins encrypted and still reads back", "also secret", textOf(refIn(root, "added.txt")))
    }

    @Test fun `a folder goes into the archive with everything inside it`() {
        val root = open("bundle.zip", zip("one.txt" to "first"))
        val tree = folder("assets", mapOf("top.txt" to "top", "nested/deep.txt" to "buried body"))

        run(OperationType.COPY, listOf(tree.ref), root)

        assertEquals(listOf("assets", "one.txt"), names(root))
        val assets = refIn(root, "assets")
        assertEquals(listOf("nested", "top.txt"), names(assets))
        assertEquals("buried body", textOf(refIn(refIn(assets, "nested"), "deep.txt")))
    }

    @Test fun `an empty folder survives being added`() {
        val root = open("bundle.zip", zip("one.txt" to "first"))
        val tree = folder("assets", emptyMap(), empty = listOf("hollow"))

        run(OperationType.COPY, listOf(tree.ref), root)

        val inside = members(refIn(root, "assets"))
        assertEquals("The empty folder is still there", listOf("hollow"), inside.map { it.name })
        assertTrue("And still a folder", inside.single().directory)
    }

    @Test fun `a name with a bar in it cannot be added, even buried in a folder`() {
        val root = open("bundle.zip", zip("one.txt" to "first"))
        val tree = folder("assets", mapOf("we|rd.txt" to "body"))

        val result = run(OperationType.COPY, listOf(tree.ref), root)

        // Member refs are "<handle>|<path>", split at the last bar, so such a name would be unreachable.
        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertEquals("And nothing half-written is left behind", listOf("one.txt"), names(root))
    }

    @Test fun `packing the folder the archive lives in embeds it as it was`() {
        // The archive is copied before it is rewritten, so this terminates: the new archive holds a
        // snapshot of the old one.
        val root = open("bundle.zip", zip("one.txt" to "first"))
        val holding = runBlocking { provider.stat(provider.root) }

        val result = run(OperationType.COPY, listOf(holding.ref), root)

        assertEquals(ItemStatus.SUCCESS, result.outcomes.single().status)
        assertTrue("The folder went in", names(root).contains("storage"))
    }

    @Test fun `keep both renames the arrival, as it does outside an archive`() {
        val root = open("bundle.zip", zip("one.txt" to "first"))
        val clash = loose("one.txt", "different")

        run(OperationType.COPY, listOf(clash.ref), root, policy = ConflictPolicy.KEEP_BOTH)

        val names = names(root)
        assertEquals("Both survive, the arrival under a name of its own", 2, names.size)
        assertTrue(names.contains("one.txt"))
        assertTrue("And the new one is named the way the engine names any kept-both copy",
            names.any { it != "one.txt" && it.startsWith("one") })
    }

    @Test fun `overwrite replaces the member that was there`() {
        val root = open("bundle.zip", zip("one.txt" to "first", "two.txt" to "second"))
        val replacement = loose("one.txt", "replaced body")

        run(OperationType.COPY, listOf(replacement.ref), root, policy = ConflictPolicy.REPLACE)

        assertEquals(listOf("one.txt", "two.txt"), names(root))
        assertEquals("replaced body", textOf(refIn(root, "one.txt")))
    }

    @Test fun `a folder pasted over a folder merges, as it does outside an archive`() {
        val root = open("bundle.zip", zip("docs" to null, "docs/old.txt" to "old"))
        val incoming = folder("docs", mapOf("new.txt" to "new"))

        val result = run(OperationType.COPY, listOf(incoming.ref), root, policy = ConflictPolicy.REPLACE)

        assertTrue("The run must succeed", result.successful)
        assertEquals(listOf("docs/", "docs/new.txt", "docs/old.txt"), onDisk())
    }

    @Test fun `a file pasted over a folder is refused exactly as it is outside an archive`() {
        // Compared against the same operation on plain storage instead of a hard-coded message.
        val root = open("bundle.zip", zip("docs" to null, "docs/old.txt" to "old", "keep.txt" to "k"))
        val incoming = loose("docs", "a file now").ref

        val inside = run(OperationType.COPY, listOf(incoming), root, policy = ConflictPolicy.REPLACE)
        val plain = runBlocking {
            provider.create(provider.root, "outside", directory = true).also {
                provider.create(it.ref, "docs", directory = true)
            }
        }
        val outside = run(OperationType.COPY, listOf(incoming), plain.ref, policy = ConflictPolicy.REPLACE)

        assertEquals("An archive must refuse this for the same reason, and say the same thing",
            outside.outcomes.map { it.status to it.message },
            inside.outcomes.map { it.status to it.message })
        assertEquals("And leave the archive alone", listOf("docs/", "docs/old.txt", "keep.txt"), onDisk())
    }

    @Test fun `a move into an archive takes the original away once it has landed`() {
        val root = open("bundle.zip", zip("one.txt" to "first"))
        val moving = loose("moved.txt", "body")

        run(OperationType.MOVE, listOf(moving.ref), root)

        assertEquals(listOf("moved.txt", "one.txt"), names(root))
        val stillThere = runCatching { runBlocking { provider.stat(moving.ref) } }.isSuccess
        assertFalse("The original is gone, but only after the archive was written", stillThere)
    }

    @Test fun `a file moved into an archive survives the archive refusing it`() {
        // The archive is only rewritten when the run ends; the source must not be removed before that
        // rewrite succeeds.
        val root = open("locked.zip", lockedZip("secret.txt" to "classified"))
        val file = loose("only-copy.txt", "the only copy")

        val outcome = runCatching { run(OperationType.MOVE, listOf(file.ref), root) }

        assertTrue("The archive cannot take it without its password", outcome.isFailure)
        val outside = runBlocking { provider.list(provider.root, complete = true).last() }.map { it.name }
        assertTrue("So the file must still be where it was: $outside", "only-copy.txt" in outside)
    }

    @Test fun `a member moved between archives is not removed until it has arrived`() {
        // Two archives cannot be rewritten atomically, so the destination must be written before the
        // source loses the member.
        val source = open("from.zip", zip("doc.txt" to "the document", "keep.txt" to "k"))
        val destination = alsoOpen("locked.zip", lockedZip("secret.txt" to "classified"))

        val outcome = runCatching {
            run(OperationType.MOVE, listOf(refIn(source, "doc.txt")), destination)
        }

        assertTrue("The destination cannot take it", outcome.isFailure)
        assertEquals("And the source still holds it", listOf("doc.txt", "keep.txt"), onDisk(source))
    }

    @Test fun `moving a folder out of an archive takes it out of the archive`() {
        val root = open("bundle.zip", zip("docs" to null, "docs/a.txt" to "a", "keep.txt" to "k"))

        val result = run(OperationType.MOVE, listOf(refIn(root, "docs")), provider.root)

        assertTrue("The move must succeed", result.successful)
        assertEquals("Only the folder leaves", listOf("keep.txt"), onDisk())
        val moved = runBlocking { provider.list(provider.root, complete = true).last() }.map { it.name }
        assertTrue("And it arrives outside: $moved", "docs" in moved)
    }

    @Test fun `moving a folder to another folder in the same archive moves it`() {
        // Within one archive a move is a rename (relocate), not a copy.
        val root = open("bundle.zip", zip("docs" to null, "docs/a.txt" to "a", "away" to null))

        val result = run(OperationType.MOVE, listOf(refIn(root, "docs")), refIn(root, "away"))

        assertTrue("The run must succeed", result.successful)
        assertEquals(listOf("away/", "away/docs/", "away/docs/a.txt"), onDisk())
    }

    @Test fun `a rename made outside any run lands without anyone flushing it`() {
        // Batch rename calls the provider directly, outside any run, so nothing will flush: the write
        // must apply immediately.
        val root = open("bundle.zip", zip("one.txt" to "first", "two.txt" to "second"))

        runBlocking { archives.rename(refIn(root, "one.txt"), "renamed.txt") }

        assertEquals("The rename has to be in the file, not only in the provider's view",
            listOf("renamed.txt", "two.txt"), onDisk())
        assertEquals("And it is the file it was, not an empty one", "first", textOf(refIn(root, "renamed.txt")))
    }

    @Test fun `a delete made outside any run lands too`() {
        val root = open("bundle.zip", zip("one.txt" to "first", "two.txt" to "second"))

        runBlocking { archives.delete(refIn(root, "two.txt")) }

        assertEquals(listOf("one.txt"), onDisk())
        assertEquals("first", textOf(refIn(root, "one.txt")))
    }

    @Test fun `a folder made outside a batch appears in the archive`() {
        // A folder has no commit to publish it, so outside a run create must apply it.
        val root = open("bundle.zip", zip("a.txt" to "a"))

        runBlocking { archives.create(root, "new", directory = true) }

        assertEquals(listOf("a.txt", "new/"), onDisk())
    }

    @Test fun `a run still batches, so its writes land together and only once`() {
        val root = open("bundle.zip", zip("a.txt" to "a", "b.txt" to "b", "c.txt" to "c"))

        inOneRun {
            archives.delete(within(root, "a.txt"))
            archives.delete(within(root, "c.txt"))
            assertEquals("Held writes read back as though applied", listOf("b.txt"), listing(root))
            assertEquals("But nothing has been written yet",
                listOf("a.txt", "b.txt", "c.txt"), onDisk())
        }

        assertEquals("And the run's writes land together", listOf("b.txt"), onDisk())
    }

    @Test fun `renaming several members in one batch rewrites the archive once`() {
        val root = open("bundle.zip", zip("a.txt" to "a", "b.txt" to "b", "c.txt" to "c"))
        val before = storage.commits

        inOneRun {
            archives.rename(refIn(root, "a.txt"), "one.txt")
            archives.rename(refIn(root, "b.txt"), "two.txt")
            archives.rename(refIn(root, "c.txt"), "three.txt")
        }

        assertEquals("Three renames, one rewrite", 1, storage.commits - before)
        assertEquals(listOf("one.txt", "three.txt", "two.txt"), onDisk())
    }

    @Test fun `a folder renamed through a run lands in the archive`() {
        val root = open("bundle.zip", zip("docs" to null, "docs/a.txt" to "a"))

        val result = run(OperationType.RENAME, listOf(refIn(root, "docs")), root, name = "papers")

        assertTrue("The run must succeed", result.successful)
        assertEquals(listOf("papers/", "papers/a.txt"), onDisk())
    }

    @Test fun `a renamed folder can still be opened and read`() {
        val root = open("bundle.zip", zip("docs" to null, "docs/a.txt" to "first"))

        runBlocking { archives.rename(refIn(root, "docs"), "papers") }

        val folder = refIn(root, "papers")
        assertEquals(listOf("a.txt"), names(folder))
        assertEquals("first", textOf(refIn(folder, "a.txt")))
    }

    @Test fun `deleting a folder leaves no entry of it behind`() {
        val root = open("bundle.zip", zip("docs" to null, "docs/a.txt" to "a", "keep.txt" to "k"))

        val result = run(OperationType.DELETE, listOf(refIn(root, "docs")), root)

        assertTrue("The run must succeed", result.successful)
        assertEquals(listOf("keep.txt"), onDisk())
    }

    @Test fun `deleting something inside a folder that is being renamed still deletes it`() {
        // Both edits land in one rewrite; applying the folder rename first would move the file away
        // from the name the delete uses.
        val root = open("bundle.zip", zip("docs" to null, "docs/a.txt" to "a", "docs/b.txt" to "b"))

        inOneRun {
            archives.rename(within(root, "docs"), "papers")
            archives.delete(within(within(root, "papers"), "a.txt"))
        }

        assertEquals(listOf("papers/", "papers/b.txt"), onDisk())
    }

    @Test fun `swapping two names in one batch keeps each body with its name`() {
        // Batch rename swaps by parking one name first. All three renames land in one rewrite, where
        // one rename's target is another's source.
        val root = open("bundle.zip", zip("a.txt" to "AAA", "b.txt" to "BBB"))

        inOneRun {
            val parked = archives.rename(refIn(root, "a.txt"), ".luna-rename-tmp")
            archives.rename(refIn(root, "b.txt"), "a.txt")
            archives.rename(parked.ref, "b.txt")
        }

        assertEquals(listOf("a.txt", "b.txt"), onDisk())
        assertEquals(mapOf("a.txt" to "BBB", "b.txt" to "AAA"), bodiesOnDisk())
    }

    @Test fun `a chain of renames in one batch keeps each body with its name`() {
        val root = open("bundle.zip", zip("a.txt" to "AAA", "b.txt" to "BBB"))

        inOneRun {
            archives.rename(refIn(root, "b.txt"), "c.txt")
            archives.rename(refIn(root, "a.txt"), "b.txt")
        }

        assertEquals(listOf("b.txt", "c.txt"), onDisk())
        assertEquals(mapOf("b.txt" to "AAA", "c.txt" to "BBB"), bodiesOnDisk())
    }

    @Test fun `two folders can trade names in one batch`() {
        val root = open("bundle.zip", zip(
            "one" to null, "one/x.txt" to "XXX", "two" to null, "two/y.txt" to "YYY",
        ))

        inOneRun {
            val parked = archives.rename(refIn(root, "one"), ".luna-rename-tmp")
            archives.rename(refIn(root, "two"), "one")
            archives.rename(parked.ref, "two")
        }

        assertEquals(listOf("one/", "one/y.txt", "two/", "two/x.txt"), onDisk())
        assertEquals(mapOf("one/y.txt" to "YYY", "two/x.txt" to "XXX"), bodiesOnDisk())
    }

    @Test fun `three members can rotate names in one batch`() {
        // "a" prefixes "abc", so zip4j carries "abc" along when "a" is renamed; later passes must ask
        // for it under the carried name.
        val root = open("bundle.zip", zip("a" to "AAA", "abc" to "BBB", "c.txt" to "CCC"))

        inOneRun {
            val parked = archives.rename(refIn(root, "a"), ".luna-rename-tmp")
            archives.rename(refIn(root, "abc"), "a")
            archives.rename(refIn(root, "c.txt"), "abc")
            archives.rename(parked.ref, "c.txt")
        }

        assertEquals(mapOf("a" to "BBB", "abc" to "CCC", "c.txt" to "AAA"), bodiesOnDisk())
    }

    @Test fun `a rename can take a name that another selected name merely begins with`() {
        // "note" prefixes "notes.txt", so zip4j carries it along. Allowed, because "notes.txt" is being
        // renamed too.
        val root = open("bundle.zip", zip("note" to "NNN", "notes.txt" to "SSS"))

        inOneRun {
            archives.rename(refIn(root, "note"), "memo")
            archives.rename(refIn(root, "notes.txt"), "notebook.txt")
        }

        assertEquals(mapOf("memo" to "NNN", "notebook.txt" to "SSS"), bodiesOnDisk())
    }

    @Test fun `one run's writes survive another run being thrown away`() {
        val root = open("bundle.zip", zip("a.txt" to "a", "b.txt" to "b"))
        val mine = StorageRun()
        val other = StorageRun()

        runBlocking {
            withContext(other) { archives.delete(within(root, "b.txt")) }
            withContext(mine) { archives.rename(within(root, "a.txt"), "renamed.txt") }
            archives.discardPending(other)
            archives.flush(mine)
        }

        assertEquals(listOf("b.txt", "renamed.txt"), onDisk())
    }

    @Test fun `a run applies its own writes while another is still collecting`() {
        val root = open("bundle.zip", zip("a.txt" to "a", "b.txt" to "b"))
        val busy = StorageRun()
        val mine = StorageRun()

        runBlocking {
            withContext(busy) { archives.delete(within(root, "b.txt")) }
            withContext(mine) { archives.rename(within(root, "a.txt"), "renamed.txt") }
            archives.flush(mine)
        }

        assertEquals("Only the finished run's change is written",
            listOf("b.txt", "renamed.txt"), onDisk())

        runBlocking { archives.flush(busy) }
        assertEquals(listOf("renamed.txt"), onDisk())
    }
}
