package com.lunaexplorer.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

class FolderSizerTest {
    private val storage = SizerProvider()
    private val sizer = FolderSizer(ProviderRegistry(listOf(storage)))

    @Test fun `totals cover every level of a nested tree`() = runBlocking {
        val root = storage.folder(null, "root")
        storage.file(root, "a.bin", 100)
        val nested = storage.folder(root, "nested")
        storage.file(nested, "b.bin", 250)
        val deeper = storage.folder(nested, "deeper")
        storage.file(deeper, "c.bin", 4)

        val totals = sizer.measure(listOf(root)).last()
        assertTrue(totals.complete)
        assertEquals(354L, totals.bytes)
        assertEquals(3L, totals.files)
        assertEquals("The measured folder itself is not counted among its contents", 2L, totals.folders)
    }

    @Test fun `progress is reported before the walk finishes`() = runBlocking {
        val root = storage.folder(null, "root")
        repeat(40) { storage.file(root, "file-$it.bin", 10) }
        // A zero interval emits at every traversal step.
        val snapshots = sizer.measure(listOf(root), emitEveryNanos = 0).toList()
        assertTrue("Expected progressive totals, saw ${snapshots.size}", snapshots.size > 1)
        assertTrue("Only the final emission may claim completeness", snapshots.dropLast(1).none { it.complete })
        assertTrue(snapshots.last().complete)
        assertEquals(400L, snapshots.last().bytes)
        assertEquals("Running totals must never go backwards", snapshots.map { it.bytes }.sorted(), snapshots.map { it.bytes })
    }

    @Test fun `unreadable subtrees and unknown sizes are counted rather than hidden`() = runBlocking {
        val root = storage.folder(null, "root")
        storage.file(root, "known.bin", 60)
        storage.file(root, "mystery.bin", null)
        val denied = storage.folder(root, "denied")
        storage.file(denied, "unreachable.bin", 999)
        storage.unlistable += denied

        val totals = sizer.measure(listOf(root)).last()
        assertTrue(totals.complete)
        assertEquals(60L, totals.bytes)
        assertEquals(1L, totals.unknownSizes)
        assertEquals(1L, totals.unreadable)
        assertEquals(2L, totals.files)
    }

    @Test fun `a failing listing costs one subtree rather than the whole walk`() = runBlocking {
        val root = storage.folder(null, "root")
        storage.file(root, "before.bin", 8)
        val broken = storage.folder(root, "broken")
        storage.listFailures += broken
        storage.file(root, "after.bin", 9)

        val totals = sizer.measure(listOf(root)).last()
        assertTrue(totals.complete)
        assertEquals("Siblings on both sides of the failure are still counted", 17L, totals.bytes)
        assertEquals(1L, totals.unreadable)
    }

    @Test fun `a cycle terminates instead of counting forever`() = runBlocking {
        val root = storage.folder(null, "root")
        storage.file(root, "a.bin", 5)
        val loop = storage.folder(root, "loop")
        storage.aliases[loop] = root

        val totals = sizer.measure(listOf(root)).last()
        assertTrue(totals.complete)
        assertEquals(5L, totals.bytes)
        assertEquals(1L, totals.files)
    }

    @Test fun `several selected items are totalled together`() = runBlocking {
        val first = storage.folder(null, "first")
        storage.file(first, "a.bin", 7)
        val second = storage.folder(null, "second")
        storage.file(second, "b.bin", 3)
        val loose = storage.file(null, "loose.bin", 11)

        val totals = sizer.measure(listOf(first, second, loose)).last()
        assertEquals(21L, totals.bytes)
        assertEquals(3L, totals.files)
        assertEquals(0L, totals.folders)
    }
}

/** Anything other than stat and list fails the test. */
private class SizerProvider : StorageProvider {
    override val id = "sizer"
    private data class Node(val ref: NodeRef, val parent: NodeRef?, val name: String, val directory: Boolean, val size: Long?)
    private val nodes = linkedMapOf<NodeRef, Node>()
    private var sequence = 0
    val unlistable = mutableSetOf<NodeRef>()
    val listFailures = mutableSetOf<NodeRef>()
    /** Listing the key returns the value's children, which makes a cycle. */
    val aliases = mutableMapOf<NodeRef, NodeRef>()

    fun folder(parent: NodeRef?, name: String): NodeRef = insert(parent, name, true, null)
    fun file(parent: NodeRef?, name: String, size: Long?): NodeRef = insert(parent, name, false, size)
    private fun insert(parent: NodeRef?, name: String, directory: Boolean, size: Long?): NodeRef {
        val ref = NodeRef(id, "node-${++sequence}")
        nodes[ref] = Node(ref, parent, name, directory, size)
        return ref
    }

    private fun entry(node: Node) = Entry(node.ref, node.name, node.directory, node.size,
        capabilities = if (node.directory && node.ref in unlistable) emptySet() else Capability.entries.toSet())

    override suspend fun stat(ref: NodeRef): Entry =
        entry(nodes[ref] ?: throw StorageException(StorageError.NOT_FOUND, "Gone"))

    override fun list(parent: NodeRef, complete: Boolean): Flow<List<Entry>> = flow {
        if (parent in listFailures) throw StorageException(StorageError.PERMISSION, "Refused")
        val source = aliases[parent] ?: parent
        val children = nodes.values.filter { it.parent == source }.map { entry(it) }
        for (batch in children.chunked(3)) emit(batch)
    }

    override suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String) = unsupported()
    override suspend fun rename(ref: NodeRef, name: String) = unsupported()
    override suspend fun delete(ref: NodeRef) = unsupported()
    override suspend fun openRead(ref: NodeRef): InputStream = unsupported()
    override suspend fun openWrite(ref: NodeRef): OutputStream = unsupported()
    override suspend fun isDescendant(candidate: NodeRef, ancestor: NodeRef) = false
    override suspend fun commit(staged: NodeRef, parent: NodeRef, name: String, replace: Entry?, onRetained: RetainedObjects?) = unsupported()
    private fun unsupported(): Nothing =
        throw AssertionError("Measuring a folder must not mutate storage or open its files")
}
