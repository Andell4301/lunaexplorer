package com.lunaexplorer.core

import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationSourceValidationTest {
    private val source = MemoryStorageProvider("source")
    private val destination = MemoryStorageProvider("destination")
    private val watched = WatchedSource(source)
    private val engine = OperationEngine(ProviderRegistry(listOf(watched, destination)))

    private fun request(ref: NodeRef, type: OperationType = OperationType.COPY) =
        OperationRequest(type = type, sources = listOf(ref), destination = destination.root)

    @Test fun `unchanged metadata needs only the original source read for a copy`() = runBlocking {
        val file = source.file(source.root, "notes.txt", "original")

        val result = engine.run(request(file))

        assertTrue(result.successful)
        assertEquals(1, watched.reads[file])
        assertEquals("original", destination.text(requireNotNull(destination.childRef(destination.root, "notes.txt"))))
        assertTrue(source.exists(file))
    }

    @Test fun `a timestamp settled by the first read is checked against the copied bytes`() = runBlocking {
        val file = source.file(source.root, "notes.txt", "original")
        watched.times[file] = 10L
        watched.afterOpen = { ref, count -> if (ref == file && count == 1) watched.times[file] = 20L }

        val result = engine.run(request(file))

        assertTrue(result.outcomes.joinToString { it.message.orEmpty() }, result.successful)
        assertEquals(2, watched.reads[file])
        assertEquals("original", destination.text(requireNotNull(destination.childRef(destination.root, "notes.txt"))))
        assertTrue(source.exists(file))
        assertFalse(destination.hasStages())
    }

    @Test fun `a same sized edit with a changed timestamp fails before publication`() = runBlocking {
        val file = source.file(source.root, "notes.txt", "original")
        watched.times[file] = 10L
        watched.afterOpen = { ref, count ->
            if (ref == file && count == 1) {
                source.setText(file, "new edit")
                watched.times[file] = 20L
            }
        }

        val result = engine.run(request(file))

        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertEquals(2, watched.reads[file])
        assertEquals("new edit", source.text(file))
        assertNull(destination.childRef(destination.root, "notes.txt"))
        assertFalse(destination.hasStages())
    }

    @Test fun `an edit during the fallback hash is caught even when the open stream has old bytes`() = runBlocking {
        val file = source.file(source.root, "notes.txt", "original")
        watched.times[file] = 10L
        watched.afterOpen = { ref, count ->
            if (ref == file) {
                if (count == 1) watched.times[file] = 20L
                if (count == 2) {
                    source.setText(file, "new edit")
                    watched.times[file] = 30L
                }
            }
        }

        val result = engine.run(request(file))

        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertEquals(2, watched.reads[file])
        assertEquals("new edit", source.text(file))
        assertNull(destination.childRef(destination.root, "notes.txt"))
        assertFalse(destination.hasStages())
    }

    @Test fun `a timestamp only change during a cross provider move keeps every content check`() = runBlocking {
        val file = source.file(source.root, "notes.txt", "original")
        watched.times[file] = 10L
        watched.afterOpen = { ref, count -> if (ref == file && count == 1) watched.times[file] = 20L }

        val result = engine.run(request(file, OperationType.MOVE))

        assertTrue(result.outcomes.joinToString { it.message.orEmpty() }, result.successful)
        assertEquals(4, watched.reads[file])
        assertFalse(source.exists(file))
        assertEquals("original", destination.text(requireNotNull(destination.childRef(destination.root, "notes.txt"))))
        assertFalse(destination.hasStages())
    }

    @Test fun `a later source edit is retained after a move accepted the earlier timestamp drift`() = runBlocking {
        val file = source.file(source.root, "notes.txt", "original")
        watched.times[file] = 10L
        watched.afterOpen = { ref, count -> if (ref == file && count == 1) watched.times[file] = 20L }

        val result = engine.run(request(file, OperationType.MOVE)) { event ->
            if (event is OperationEvent.Journal && event.phase == JournalPhase.PUBLISHED) {
                source.setText(file, "new edit")
                watched.times[file] = 30L
            }
        }

        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertNotNull(result.outcomes.single().destination)
        assertEquals("new edit", source.text(file))
        assertEquals("original", destination.text(requireNotNull(destination.childRef(destination.root, "notes.txt"))))
        assertFalse(destination.hasStages())
    }

    @Test fun `a source size change is rejected without a fallback read`() = runBlocking {
        val file = source.file(source.root, "notes.txt", "original")
        watched.afterOpen = { ref, count -> if (ref == file && count == 1) source.setText(file, "a longer edit") }

        val result = engine.run(request(file))

        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertEquals(1, watched.reads[file])
        assertEquals("a longer edit", source.text(file))
        assertNull(destination.childRef(destination.root, "notes.txt"))
        assertFalse(destination.hasStages())
    }

    @Test fun `directory timestamp changes still prevent publication`() = runBlocking {
        val folder = source.folder(source.root, "documents")
        val file = source.file(folder, "notes.txt", "original")
        watched.times[folder] = 10L
        watched.afterOpen = { ref, _ -> if (ref == file) watched.times[folder] = 20L }

        val result = engine.run(request(folder))

        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertEquals(1, watched.reads[file])
        assertEquals("original", source.text(file))
        assertNull(destination.childRef(destination.root, "documents"))
        assertFalse(destination.hasStages())
    }

    @Test fun `new directory members still prevent publication without a timestamp change`() = runBlocking {
        val folder = source.folder(source.root, "documents")
        val file = source.file(folder, "notes.txt", "original")
        watched.afterOpen = { ref, count ->
            if (ref == file && count == 1) source.file(folder, "later.txt", "new")
        }

        val result = engine.run(request(folder))

        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertEquals("original", source.text(file))
        assertNotNull(source.childRef(folder, "later.txt"))
        assertNull(destination.childRef(destination.root, "documents"))
        assertFalse(destination.hasStages())
    }

    private class WatchedSource(private val storage: MemoryStorageProvider) : StorageProvider by storage {
        val times = mutableMapOf<NodeRef, Long>()
        val reads = mutableMapOf<NodeRef, Int>()
        var afterOpen: (NodeRef, Int) -> Unit = { _, _ -> }

        override suspend fun stat(ref: NodeRef): Entry = storage.stat(ref).let { entry ->
            times[ref]?.let { entry.copy(modified = it) } ?: entry
        }

        override suspend fun openRead(ref: NodeRef): InputStream {
            val input = storage.openRead(ref)
            val count = reads.getOrDefault(ref, 0) + 1
            reads[ref] = count
            afterOpen(ref, count)
            return input
        }
    }
}
