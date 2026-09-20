package com.lunaexplorer.app.storage

import com.lunaexplorer.core.*
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalStorageProviderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `commit preserves a target edited without changing its inode`() = runTest {
        for (replacement in listOf("a different size", "new")) {
            val folder = temporary.newFolder()
            val provider = LocalStorageProvider(confined("workspace", folder))
            val root = provider.root("workspace")
            val original = provider.create(root, "original.txt", false)
            provider.openWrite(original.ref).use { it.write("old".toByteArray()) }
            val seen = provider.stat(original.ref)
            val target = folder.resolve("original.txt").toPath()
            val modified = Files.getLastModifiedTime(target)
            folder.resolve("original.txt").writeText(replacement)
            Files.setLastModifiedTime(target, if (replacement.length == 3) {
                FileTime.fromMillis(modified.toMillis() + 2_000)
            } else modified)
            val staged = provider.create(root, ".stage", false)
            provider.openWrite(staged.ref).use { it.write("staged".toByteArray()) }

            expectStorageError(StorageError.CONFLICT) { provider.commit(staged.ref, root, "original.txt", seen) }

            assertEquals(replacement, folder.resolve("original.txt").readText())
            assertEquals("staged", folder.resolve(".stage").readText())
        }
    }

    @Test fun `atomic replacement publishes staging and stale target identity is rejected`() = runTest {
        val folder = temporary.newFolder()
        val provider = LocalStorageProvider(confined("workspace", folder))
        val root = provider.root("workspace")
        val created = provider.create(root, "original.txt", false)
        provider.openWrite(created.ref).use { it.write("old".toByteArray()) }
        val original = provider.stat(created.ref)
        val staged = provider.create(root, ".stage", false)
        provider.openWrite(staged.ref).use { it.write("new".toByteArray()) }
        val published = provider.commit(staged.ref, root, "original.txt", original)
        assertEquals("new", provider.openRead(published.ref).bufferedReader().use { it.readText() })
        assertFalse(folder.resolve(".stage").exists())
        // The stale version captured in `original` must not authorize replacing the new file.
        val again = provider.create(root, ".stage2", false)
        provider.openWrite(again.ref).use { it.write("newer".toByteArray()) }
        expectStorageError(StorageError.CONFLICT) { provider.commit(again.ref, root, "original.txt", original) }
        assertEquals("new", provider.openRead(published.ref).bufferedReader().use { it.readText() })
        assertTrue(folder.resolve("original.txt").exists())
    }

    @Test fun `a storage root cannot be deleted`() = runTest {
        val folder = temporary.newFolder()
        val provider = LocalStorageProvider(confined("workspace", folder))
        expectStorageError(StorageError.UNSUPPORTED) { provider.delete(provider.root("workspace")) }
        assertTrue(folder.isDirectory)
    }

    @Test fun `references cannot traverse or follow symbolic links outside a root`() = runTest {
        val folder = temporary.newFolder("allowed")
        val outside = temporary.newFolder("outside")
        outside.resolve("private").writeText("secret")
        val provider = LocalStorageProvider(confined("workspace", folder))
        val root = provider.root("workspace")
        val traversal = NodeRef("local", "workspace ../outside/private")
        expectStorageError(StorageError.PERMISSION) { provider.openRead(traversal) }
        Files.createSymbolicLink(folder.toPath().resolve("escape"), outside.toPath())
        val symlink = NodeRef("local", "workspace escape/private")
        expectStorageError(StorageError.PERMISSION) { provider.openRead(symlink) }
        val unsupported = provider.list(root).toList().flatten().single()
        assertEquals("escape", unsupported.name)
        assertEquals("inode/symlink", unsupported.mimeType)
        assertTrue(unsupported.capabilities.isEmpty())
        assertEquals("secret", outside.resolve("private").readText())
    }

    @Test fun `copy and move of a folder containing a symlink fail without omitting or deleting contents`() = runTest {
        val workspace = temporary.newFolder("workspace")
        val outside = temporary.newFolder("outside")
        outside.resolve("private.txt").writeText("outside content")
        val provider = LocalStorageProvider(confined("workspace", workspace))
        val root = provider.root("workspace")
        val source = provider.create(root, "source", true)
        val file = provider.create(source.ref, "ordinary.txt", false)
        provider.openWrite(file.ref).use { it.write("original content".toByteArray()) }
        Files.createSymbolicLink(workspace.resolve("source/linked-folder").toPath(), outside.toPath())
        val destination = provider.create(root, "destination", true)
        val engine = OperationEngine(ProviderRegistry(listOf(provider)))
        for (type in listOf(OperationType.COPY, OperationType.MOVE)) {
            val result = engine.run(OperationRequest(type = type, sources = listOf(source.ref), destination = destination.ref))
            assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
            assertEquals(StorageError.UNSUPPORTED, result.outcomes.single().error)
            assertTrue("No partial or published copy may remain", provider.list(destination.ref).toList().flatten().isEmpty())
            assertEquals("original content", workspace.resolve("source/ordinary.txt").readText())
            assertTrue(Files.isSymbolicLink(workspace.resolve("source/linked-folder").toPath()))
            assertEquals("outside content", outside.resolve("private.txt").readText())
        }
    }

    @Test fun `directory ancestry is structural and streams cannot truncate existing data`() = runTest {
        val provider = LocalStorageProvider(confined("workspace", temporary.newFolder()))
        val root = provider.root("workspace")
        val parent = provider.create(root, "parent", true)
        val child = provider.create(parent.ref, "child", false)
        val sibling = provider.create(root, "parent-two", true)
        assertTrue(provider.isDescendant(child.ref, parent.ref))
        assertTrue(provider.isDescendant(parent.ref, parent.ref))
        assertFalse(provider.isDescendant(sibling.ref, parent.ref))
        provider.openWrite(child.ref).use { it.write(byteArrayOf(1, 2, 3)) }
        expectStorageError(StorageError.CONFLICT) { provider.openWrite(child.ref) }
        assertEquals(3L, provider.stat(child.ref).size)
    }

    @Test fun `unavailable volume does not hide reachable roots`() = runTest {
        val available = temporary.newFolder("available")
        val blocker = temporary.newFile("not-a-directory")
        val provider = LocalStorageProvider(listOf(LocalRoot("available", "available", available), LocalRoot("offline", "offline", blocker.resolve("workspace"))))
        val roots = provider.roots()
        assertEquals(1, roots.size)
        val reachable = roots.first { it.title == "available" }
        assertTrue(provider.stat(reachable.ref).directory)
        assertFalse(roots.any { it.title == "offline" })
    }

    @Test fun `a link-following root resolves a linked folder like the platform does`() = runTest {
        val volume = temporary.newFolder("volume")
        volume.resolve("real").mkdir()
        volume.resolve("real/note.txt").writeText("reachable through the link")
        Files.createSymbolicLink(volume.toPath().resolve("shortcut"), volume.toPath().resolve("real"))
        val provider = LocalStorageProvider(listOf(LocalRoot("primary", "Internal storage", volume, followLinks = true)))
        val root = provider.root("primary")

        val shortcut = provider.list(root).toList().flatten().single { it.name == "shortcut" }
        assertTrue("A link to a folder must browse as that folder", shortcut.directory)
        assertTrue(Capability.LIST in shortcut.capabilities)
        val child = provider.list(shortcut.ref).toList().flatten().single()
        assertEquals("note.txt", child.name)
        assertEquals("reachable through the link", provider.openRead(child.ref).bufferedReader().use { it.readText() })
    }

    @Test fun `a link-following root still refuses relative traversal above itself`() = runTest {
        val volume = temporary.newFolder("volume")
        val outside = temporary.newFolder("outside")
        outside.resolve("private").writeText("secret")
        val provider = LocalStorageProvider(listOf(LocalRoot("primary", "Internal storage", volume, followLinks = true)))
        val traversal = NodeRef("local", "primary ../outside/private")
        expectStorageError(StorageError.PERMISSION) { provider.openRead(traversal) }
        assertEquals("secret", outside.resolve("private").readText())
    }

    @Test fun `a broken link stays visible and inert without failing its folder`() = runTest {
        val volume = temporary.newFolder("volume")
        volume.resolve("ordinary.txt").writeText("still listed")
        Files.createSymbolicLink(volume.toPath().resolve("dangling"), volume.toPath().resolve("missing-target"))
        val provider = LocalStorageProvider(listOf(LocalRoot("primary", "Internal storage", volume, followLinks = true)))
        val root = provider.root("primary")

        val listed = provider.list(root).toList().flatten().associateBy { it.name }
        assertEquals("A broken link must not hide its siblings", setOf("ordinary.txt", "dangling"), listed.keys)
        val dangling = listed.getValue("dangling")
        assertEquals("inode/symlink", dangling.mimeType)
        assertTrue("An unresolvable entry offers no operations", dangling.capabilities.isEmpty())
    }

    @Test fun `overlapping roots describe the same file and agree on ancestry`() = runTest {
        val device = temporary.newFolder("device")
        val volume = device.resolve("volume").apply { mkdir() }
        volume.resolve("nested").mkdir()
        volume.resolve("nested/file.txt").writeText("one file, two routes")
        val provider = LocalStorageProvider(listOf(
            LocalRoot("primary", "Internal storage", volume, followLinks = true),
            LocalRoot("device", "Device root", device, RootKind.SYSTEM, followLinks = true),
        ))
        val viaVolume = provider.list(provider.root("primary")).toList().flatten().single { it.name == "nested" }
        val viaDevice = provider.list(provider.list(provider.root("device")).toList().flatten()
            .single { it.name == "volume" }.ref).toList().flatten().single { it.name == "nested" }

        assertNotEquals("The two routes are different references", viaVolume.ref, viaDevice.ref)
        assertTrue(provider.isDescendant(viaVolume.ref, viaDevice.ref))
        assertTrue(provider.isDescendant(viaDevice.ref, viaVolume.ref))
        assertEquals(provider.absolutePath(viaVolume.ref), provider.absolutePath(viaDevice.ref))
    }

    @Test fun `an app workspace keeps refusing links even beside link-following roots`() = runTest {
        val workspace = temporary.newFolder("workspace")
        val outside = temporary.newFolder("outside")
        outside.resolve("private").writeText("secret")
        Files.createSymbolicLink(workspace.toPath().resolve("escape"), outside.toPath())
        val provider = LocalStorageProvider(listOf(
            LocalRoot("workspace", "Luna workspace", workspace),
            LocalRoot("device", "Device root", temporary.newFolder("device"), RootKind.SYSTEM, followLinks = true),
        ))
        val through = NodeRef("local", "workspace escape/private")
        expectStorageError(StorageError.PERMISSION) { provider.openRead(through) }
        val listed = provider.list(provider.root("workspace")).toList().flatten().single()
        assertEquals("inode/symlink", listed.mimeType)
        assertTrue(listed.capabilities.isEmpty())
        assertEquals("secret", outside.resolve("private").readText())
    }

    @Test fun `a read-only folder reports no mutation capabilities for its children`() = runTest {
        val volume = temporary.newFolder("volume")
        val locked = volume.resolve("locked").apply { mkdir() }
        locked.resolve("child.txt").writeText("read me")
        assumeTrue("Requires a filesystem that enforces write permission", locked.setWritable(false))
        assumeFalse("Permissions are not enforced for this user", Files.isWritable(locked.toPath()))
        try {
            val provider = LocalStorageProvider(listOf(LocalRoot("primary", "Internal storage", volume, followLinks = true)))
            val folder = provider.list(provider.root("primary")).toList().flatten().single { it.name == "locked" }
            assertFalse(Capability.CREATE in folder.capabilities)
            assertFalse(Capability.REPLACE in folder.capabilities)
            val child = provider.list(folder.ref).toList().flatten().single()
            assertTrue(Capability.READ in child.capabilities)
            assertFalse("A child of an unwritable folder cannot be removed or renamed", Capability.DELETE in child.capabilities)
            assertFalse(Capability.RENAME in child.capabilities)
        } finally {
            locked.setWritable(true)
        }
    }

    @Test fun `a path resolves even when a parent cannot be listed`() = runTest {
        val workspace = temporary.newFolder()
        val opaque = File(workspace, "opaque").apply { mkdirs() }
        val inside = File(opaque, "reachable.txt").apply { writeText("found me") }

        // Permit search while denying readdir.
        assumeTrue("Needs POSIX permissions", opaque.setReadable(false, false))
        try {
            assumeTrue("Parent must actually refuse enumeration", opaque.list() == null)

            val provider = LocalStorageProvider(confined("workspace", workspace))
            val ref = provider.referenceTo(inside.absolutePath)
            assertNotNull("A reference must be constructible without listing the parent", ref)
            assertEquals("found me", provider.openRead(ref!!).bufferedReader().use { it.readText() })
        } finally {
            opaque.setReadable(true, false)
        }
    }

    @Test fun `a reference built from a path costs no filesystem access and keeps the name exactly`() = runTest {
        val workspace = temporary.newFolder()
        // A trailing space and an invisible mark are both legal in a directory name.
        val awkward = File(workspace, "odd name \u200E")
        assumeTrue("Filesystem must accept the name", awkward.mkdirs())

        val provider = LocalStorageProvider(confined("workspace", workspace))
        val ref = provider.referenceTo(awkward.absolutePath)
        assertNotNull(ref)
        assertEquals(awkward.absolutePath, provider.absolutePath(ref!!))

        val missing = provider.referenceTo(File(workspace, "not-here").absolutePath)
        assertNotNull("A reference is a name, not a promise that it exists", missing)
    }

    private fun confined(id: String, path: File) = listOf(LocalRoot(id, id, path))

    private suspend fun expectStorageError(reason: StorageError, action: suspend () -> Unit) {
        try { action(); fail("Expected $reason") }
        catch (error: StorageException) { assertEquals(reason, error.reason) }
    }

    @Test fun `a folder that will not delete names what is still inside it`() = runTest {
        val workspace = temporary.newFolder()
        val provider = LocalStorageProvider(confined("workspace", workspace))
        val folder = File(workspace, "META-INF").apply { mkdirs() }
        File(folder, "MANIFEST.MF").writeText("x")
        File(folder, "CERT.SF").writeText("x")

        val ref = requireNotNull(provider.referenceTo(folder.absolutePath))
        val failure = runCatching { provider.delete(ref) }.exceptionOrNull() as? StorageException
        assertNotNull("Deleting a non-empty folder must fail", failure)
        assertEquals(StorageError.CONFLICT, failure!!.reason)
        val message = failure.message.orEmpty()
        assertTrue("Should name what remains, not just the path: $message",
            message.contains("MANIFEST.MF") || message.contains("CERT.SF"))
    }

    private fun failingAfter(good: Int): (Path) -> DirectoryStream<Path> = { path ->
        val real = Files.newDirectoryStream(path)
        object : DirectoryStream<Path> {
            override fun iterator(): MutableIterator<Path> {
                val inner = real.iterator(); var handed = 0
                return object : MutableIterator<Path> {
                    override fun hasNext() = inner.hasNext()
                    override fun next(): Path {
                        if (handed++ >= good) throw DirectoryIteratorException(IOException("readdir failed part way"))
                        return inner.next()
                    }
                    override fun remove() = throw UnsupportedOperationException()
                }
            }
            override fun close() = real.close()
        }
    }

    @Test fun `a listing that fails part way is short for browsing and refused when completeness is demanded`() = runTest {
        val folder = temporary.newFolder()
        repeat(5) { folder.resolve("f$it.txt").writeText("x") }
        val provider = LocalStorageProvider(confined("workspace", folder), openDirectory = failingAfter(3))
        val root = provider.root("workspace")

        val browsed = provider.list(root).toList().flatten()
        assertEquals("What could be read is shown", 3, browsed.size)

        expectStorageError(StorageError.IO) { provider.list(root, complete = true).toList() }
    }
}
