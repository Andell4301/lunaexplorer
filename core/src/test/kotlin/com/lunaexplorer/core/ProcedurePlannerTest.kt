package com.lunaexplorer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProcedurePlannerTest {
    private val storage = MemoryStorageProvider("device")
    private val registry = ProviderRegistry(listOf(storage))
    private val planner = ProcedurePlanner(registry)
    private val source = storage.folder(storage.root, "source")
    private val destination = storage.folder(storage.root, "destination")

    @Test fun `matching reads fresh complete listings and respects recursion`() = runBlocking {
        val top = storage.file(source, "invoice-1.pdf", "one")
        storage.file(source, "invoice-2.txt", "two")
        val child = storage.folder(source, "child")
        val nested = storage.file(child, "invoice-3.pdf", "three")
        storage.folder(source, "invoice-4.pdf")
        val completeListings = mutableListOf<Boolean>()
        registry.register(object : StorageProvider by storage {
            override fun list(parent: NodeRef, complete: Boolean) = storage.list(parent, complete).also {
                completeListings += complete
            }
        })
        val selection = ProcedureSource(ProcedureLocation(source), "invoice-?.pdf")
        val step = ProcedureStep(OperationType.COPY, listOf(selection), ProcedureLocation(destination))

        assertEquals(listOf(top), planner.plan(step).single().sources)
        assertEquals(setOf(top, nested), planner.plan(step.copy(sources = listOf(selection.copy(recursive = true))))
            .single().sources.toSet())
        val later = storage.file(source, "invoice-5.pdf", "five")
        assertEquals(setOf(top, later), planner.plan(step).single().sources.toSet())
        assertTrue(completeListings.isNotEmpty())
        assertTrue(completeListings.all { it })
    }

    @Test fun `patterns treat regex characters and spaces literally`() = runBlocking {
        val literal = storage.file(source, " [draft]+.txt ", "one")
        storage.file(source, "draft.txt", "two")
        val selection = ProcedureSource(ProcedureLocation(source), " [draft]+.?xt ")

        assertEquals(listOf(literal), planner.plan(ProcedureStep(OperationType.DELETE, listOf(selection))).single().sources)
    }

    @Test fun `multiple exact locations omit children already covered by a selected folder`() = runBlocking {
        val first = storage.folder(source, "first")
        val file = storage.file(first, "file", "one")
        val second = storage.folder(source, "second")
        val selections = listOf(file, first, second, first).map { ProcedureSource(ProcedureLocation(it)) }

        assertEquals(listOf(first, second), planner.plan(ProcedureStep(OperationType.DELETE, selections)).single().sources)
    }

    @Test fun `recursive matching selects a matching folder once and does not follow links`() = runBlocking {
        val selected = storage.folder(source, "remove-me")
        storage.folder(selected, "remove-too")
        val link = storage.folder(source, "link")
        storage.folder(link, "remove-linked")
        val listed = mutableListOf<NodeRef>()
        registry.register(object : StorageProvider by storage {
            override fun list(parent: NodeRef, complete: Boolean) = storage.list(parent, complete).map { entries ->
                listed += parent
                entries.map { if (it.ref == link) it.copy(link = true) else it }
            }
        })
        val selection = ProcedureSource(ProcedureLocation(source), "remove-*", true, ProcedureEntryKind.FOLDERS)

        assertEquals(listOf(selected), planner.plan(ProcedureStep(OperationType.DELETE, listOf(selection))).single().sources)
        assertFalse(selected in listed)
        assertFalse(link in listed)
    }

    @Test fun `a partial listing failure never returns the matches it already emitted`() = runBlocking {
        val file = storage.file(source, "file", "one")
        registry.register(object : StorageProvider by storage {
            override fun list(parent: NodeRef, complete: Boolean) = flow {
                emit(listOf(storage.stat(file)))
                throw StorageException(StorageError.OFFLINE, "Disconnected")
            }
        })

        try {
            planner.plan(ProcedureStep(OperationType.DELETE,
                listOf(ProcedureSource(ProcedureLocation(source), "*"))))
            fail("An incomplete selection must fail")
        } catch (failure: StorageException) {
            assertEquals(StorageError.OFFLINE, failure.reason)
        }
    }

    @Test fun `cancelled selection remains cancelled`() = runBlocking {
        registry.register(object : StorageProvider by storage {
            override fun list(parent: NodeRef, complete: Boolean) = flow<List<Entry>> {
                throw CancellationException("Cancelled")
            }
        })

        try {
            planner.plan(ProcedureStep(OperationType.DELETE,
                listOf(ProcedureSource(ProcedureLocation(source), "*"))))
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            Unit
        }
    }

    @Test fun `no matches skips a step but a missing exact location fails`() = runBlocking {
        val missing = ProcedureLocation(source, listOf("missing"))
        assertTrue(planner.plan(ProcedureStep(OperationType.DELETE,
            listOf(ProcedureSource(ProcedureLocation(source), "*.pdf")))).isEmpty())

        try {
            planner.plan(ProcedureStep(OperationType.DELETE, listOf(ProcedureSource(missing))))
            fail("A missing exact location must fail")
        } catch (failure: StorageException) {
            assertEquals(StorageError.NOT_FOUND, failure.reason)
        }
    }

    @Test fun `later steps resolve names created by previous steps through provider lookups`() = runBlocking {
        val file = storage.file(source, "original.txt", "contents")
        val created = ProcedureLocation(destination, listOf("new folder"))
        val copied = ProcedureLocation(destination, listOf("new folder", "original.txt"))
        val renamed = ProcedureLocation(destination, listOf("new folder", "renamed.txt"))
        val steps = listOf(
            ProcedureStep(OperationType.CREATE_FOLDER, destination = ProcedureLocation(destination), name = "new folder"),
            ProcedureStep(OperationType.COPY, listOf(ProcedureSource(ProcedureLocation(file))), created),
            ProcedureStep(OperationType.RENAME, listOf(ProcedureSource(copied)), name = "renamed.txt"),
            ProcedureStep(OperationType.MOVE, listOf(ProcedureSource(renamed)), ProcedureLocation(destination)),
            ProcedureStep(OperationType.DELETE, listOf(ProcedureSource(created))),
        )
        val engine = OperationEngine(registry)

        for (step in steps) {
            for (request in planner.plan(step)) assertTrue(engine.run(request).successful)
        }

        assertEquals("contents", storage.text(storage.childRef(destination, "renamed.txt")!!))
        assertNull(storage.childRef(destination, "new folder"))
        assertTrue(storage.exists(file))
    }

    @Test fun `rename supplies the actual parent for conflict handling`() = runBlocking {
        val file = storage.file(source, "file", "one")
        val request = planner.plan(ProcedureStep(OperationType.RENAME,
            listOf(ProcedureSource(ProcedureLocation(file))), name = "renamed", conflictPolicy = ConflictPolicy.SKIP)).single()

        assertEquals(source, request.destination)
        storage.file(source, "renamed", "two")
        assertEquals(ItemStatus.SKIPPED, OperationEngine(registry).run(request).outcomes.single().status)
        assertTrue(storage.exists(file))
    }

    @Test fun `rename rejects multiple matches before any mutation`() = runBlocking {
        storage.file(source, "first", "one")
        storage.file(source, "second", "two")

        try {
            planner.plan(ProcedureStep(OperationType.RENAME,
                listOf(ProcedureSource(ProcedureLocation(source), "*")), name = "same"))
            fail("Rename must select exactly one item")
        } catch (_: IllegalArgumentException) {
            assertNotNull(storage.childRef(source, "first"))
            assertNotNull(storage.childRef(source, "second"))
        }
    }

    @Test fun `archive steps can consume a file created by a prior step`() = runBlocking {
        val file = storage.file(source, "notes.txt", "archived contents")
        val extracted = storage.folder(storage.root, "extracted")
        val steps = listOf(
            ProcedureStep(OperationType.CREATE_ARCHIVE, listOf(ProcedureSource(ProcedureLocation(file))),
                ProcedureLocation(destination), "saved.zip", archive = ArchiveSpec()),
            ProcedureStep(OperationType.EXTRACT_ARCHIVE,
                listOf(ProcedureSource(ProcedureLocation(destination, listOf("saved.zip")))),
                ProcedureLocation(extracted)),
        )
        val engine = OperationEngine(registry)

        for (step in steps) {
            for (request in planner.plan(step)) assertTrue(engine.run(request).successful)
        }

        assertEquals("archived contents", storage.text(storage.childRef(extracted, "notes.txt")!!))
    }

    @Test fun `password archives and direct procedure execution fail before mutation`() = runBlocking {
        val file = storage.file(source, "notes.txt", "contents")
        val step = ProcedureStep(OperationType.CREATE_ARCHIVE, listOf(ProcedureSource(ProcedureLocation(file))),
            ProcedureLocation(destination), "saved.zip", archive = ArchiveSpec(needsPassword = true))

        try {
            planner.plan(step)
            fail("A saved archive password cannot be supplied")
        } catch (_: IllegalArgumentException) {
            assertNull(storage.childRef(destination, "saved.zip"))
        }
        try {
            OperationEngine(registry).run(OperationRequest(type = OperationType.PROCEDURE,
                steps = listOf(ProcedureStep(OperationType.DELETE, listOf(ProcedureSource(ProcedureLocation(file)))))))
            fail("Procedures must run through the queue")
        } catch (_: IllegalArgumentException) {
            assertTrue(storage.exists(file))
        }
    }
}
