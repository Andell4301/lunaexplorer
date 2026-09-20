package com.lunaexplorer.core

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Provider behaviour the operation engine relies on, exercised through [StorageProvider] calls only
 * (no path assumptions).
 */
abstract class StorageProviderContract {

    protected abstract val provider: StorageProvider
    /** A fresh empty folder the test may fill and mutate freely. */
    protected abstract suspend fun freshRoot(): NodeRef

    private suspend fun folder(parent: NodeRef, name: String) = provider.create(parent, name, directory = true).ref

    private suspend fun file(parent: NodeRef, name: String, text: String): NodeRef {
        val created = provider.create(parent, name, directory = false)
        provider.openWrite(created.ref).use { it.write(text.toByteArray()) }
        return created.ref
    }

    private suspend fun read(ref: NodeRef) = provider.openRead(ref).use { it.readBytes().decodeToString() }

    protected suspend fun expect(reason: StorageError, action: suspend () -> Unit) {
        try { action(); fail("Expected $reason") }
        catch (error: StorageException) { assertEquals(reason, error.reason) }
    }

    @Test fun `every root the provider reports is a folder that can be listed`() = runBlocking {
        val roots = provider.roots()
        assertTrue("A provider with nothing to serve is not one worth registering", roots.isNotEmpty())
        roots.forEach { root ->
            assertTrue("${root.title} must stat as a folder", provider.stat(root.ref).directory)
            provider.list(root.ref).toList()
        }
    }

    @Test fun `setModified is honoured where declared and refused where not`() = runBlocking {
        val root = freshRoot()
        val ref = file(root, "dated.txt", "x")
        val wanted = 1_600_000_000_000L
        if (Feature.SET_TIMES in provider.features) {
            val stored = provider.setModified(ref, wanted)
            assertTrue("Reported $stored for $wanted; filesystems may round, but not by more than two seconds",
                abs(stored - wanted) <= 2_000)
            assertEquals(stored, provider.stat(ref).modified)
        } else {
            expect(StorageError.UNSUPPORTED) { provider.setModified(ref, wanted) }
        }
    }

    @Test fun `child finds the one item of that name and nothing for an absent one`() = runBlocking {
        val root = freshRoot()
        val wanted = file(root, "wanted.txt", "x")
        file(root, "other.txt", "y")
        assertEquals(wanted, provider.child(root, "wanted.txt")?.ref)
        assertEquals(null, provider.child(root, "missing.txt"))
    }

    @Test fun `parentOf walks up to the folder holding an item, and stops at a root`() = runBlocking {
        val root = freshRoot()
        val sub = folder(root, "sub")
        val inside = file(sub, "deep.txt", "x")
        assertEquals(sub, provider.parentOf(inside))
        assertEquals(root, provider.parentOf(sub))
        val top = provider.roots().first().ref
        assertEquals("A root has nothing above it", null, provider.parentOf(top))
    }

    @Test fun `openChannel is offered where declared, and agrees with openRead`() = runBlocking {
        val root = freshRoot()
        val ref = file(root, "bytes.bin", "0123456789")
        val channel = provider.openChannel(ref)
        if (Feature.RANGE_READ in provider.features) {
            assertTrue("RANGE_READ is declared, so a channel must be offered", channel != null)
            channel!!.use {
                assertEquals(10L, it.size())
                it.position(4)
                val buffer = ByteBuffer.allocate(3)
                it.read(buffer)
                assertEquals("456", String(buffer.array()))
            }
        } else {
            assertEquals("Undeclared means not offered, not a channel that lies", null, channel)
        }
    }

    @Test fun `a listing names every child exactly once`() = runBlocking {
        val root = freshRoot()
        val names = listOf("one.txt", "two.txt", "sub")
        file(root, "one.txt", "1"); file(root, "two.txt", "2"); folder(root, "sub")
        val listed = provider.list(root).toList().flatten().map { it.name }
        assertEquals(names.sorted(), listed.sorted())
    }

    @Test fun `what was written is what is read back`() = runBlocking {
        val root = freshRoot()
        val ref = file(root, "notes.txt", "trustworthy data")
        assertEquals("trustworthy data", read(ref))
        assertEquals("notes.txt", provider.stat(ref).name)
        assertFalse(provider.stat(ref).directory)
    }

    @Test fun `creating a name that exists is a conflict, not a replacement`() = runBlocking {
        val root = freshRoot()
        file(root, "taken.txt", "original")
        expect(StorageError.CONFLICT) { provider.create(root, "taken.txt", directory = false) }
        expect(StorageError.CONFLICT) { provider.create(root, "taken.txt", directory = true) }
        assertEquals("original", read(provider.list(root).toList().flatten().single().ref))
    }

    @Test fun `renaming onto an existing name is a conflict`() = runBlocking {
        val root = freshRoot()
        val moving = file(root, "moving.txt", "a")
        file(root, "target.txt", "b")
        expect(StorageError.CONFLICT) { provider.rename(moving, "target.txt") }
    }

    @Test fun `stat after rename reports the new name and the same contents`() = runBlocking {
        val root = freshRoot()
        val ref = file(root, "before.txt", "kept")
        val renamed = provider.rename(ref, "after.txt")
        assertEquals("after.txt", renamed.name)
        assertEquals("after.txt", provider.stat(renamed.ref).name)
        assertEquals("kept", read(renamed.ref))
    }

    @Test fun `a folder with contents refuses to be deleted`() = runBlocking {
        val root = freshRoot()
        val sub = folder(root, "full")
        file(sub, "inside.txt", "x")
        expect(StorageError.CONFLICT) { provider.delete(sub) }
        assertTrue("The folder must still be there", provider.stat(sub).directory)
    }

    @Test fun `an empty folder and a file can be deleted, and are then gone`() = runBlocking {
        val root = freshRoot()
        val sub = folder(root, "empty")
        val ref = file(root, "gone.txt", "x")
        provider.delete(sub)
        provider.delete(ref)
        assertTrue(provider.list(root).toList().flatten().isEmpty())
        expect(StorageError.NOT_FOUND) { provider.stat(ref) }
    }

    @Test fun `descent is reflexive, true for a child, false for a stranger`() = runBlocking {
        val root = freshRoot()
        val sub = folder(root, "sub")
        val inside = file(sub, "deep.txt", "x")
        val other = folder(root, "other")
        assertTrue(provider.isDescendant(root, root))
        assertTrue(provider.isDescendant(inside, sub))
        assertTrue(provider.isDescendant(inside, root))
        assertFalse(provider.isDescendant(other, sub))
        assertFalse(provider.isDescendant(sub, inside))
    }

    @Test fun `commit publishes a staged file under its final name`() = runBlocking {
        val root = freshRoot()
        val staged = file(root, ".luna-stage.partial", "new content")
        val published = provider.commit(staged, root, "final.txt")
        assertEquals("final.txt", published.name)
        assertEquals("new content", read(published.ref))
        val names = provider.list(root).toList().flatten().map { it.name }
        assertEquals(listOf("final.txt"), names)
    }

    @Test fun `commit refuses to replace an item that changed since it was seen`() = runBlocking {
        val root = freshRoot()
        val original = file(root, "final.txt", "original")
        val seen = provider.stat(original)
        if (seen.version == null) return@runBlocking
        // Same address, different item.
        provider.delete(original)
        file(root, "final.txt", "replaced meanwhile")
        val staged = file(root, ".luna-stage.partial", "new")
        expect(StorageError.CONFLICT) { provider.commit(staged, root, "final.txt", replace = seen) }
        assertEquals("The item that took the name must survive", "replaced meanwhile",
            read(provider.list(root).toList().flatten().single { it.name == "final.txt" }.ref))
    }

    @Test fun `two references to one address are equal however each was obtained`() = runBlocking {
        val root = freshRoot()
        val sub = folder(root, "sub")
        val listed = provider.list(root).toList().flatten().single { it.name == "sub" }.ref
        assertEquals("A listing's reference and a created one name the same item", sub, listed)
        assertEquals(provider.stat(sub).ref, listed)
    }

    @Test fun `commit onto an existing name without replace is a conflict`() = runBlocking {
        val root = freshRoot()
        file(root, "final.txt", "original")
        val staged = file(root, ".luna-stage.partial", "new")
        expect(StorageError.CONFLICT) { provider.commit(staged, root, "final.txt") }
    }
}
