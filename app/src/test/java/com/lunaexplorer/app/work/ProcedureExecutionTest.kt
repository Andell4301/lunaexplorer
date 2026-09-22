package com.lunaexplorer.app.work

import com.lunaexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProcedureExecutionTest {
    private val storage = MemoryStorageProvider("device")
    private val registry = ProviderRegistry(listOf(storage))
    private val execution = ProcedureExecution(ProcedurePlanner(registry), OperationEngine(registry))
    private val source = storage.folder(storage.root, "source")
    private val destination = storage.folder(storage.root, "destination")

    private fun exact(ref: NodeRef) = ProcedureSource(ProcedureLocation(ref))

    @Test fun `a conflict stops later actions and retains the existing target`() = runBlocking {
        val file = storage.file(source, "file", "new")
        val existing = storage.file(destination, "file", "existing")
        val request = OperationRequest(type = OperationType.PROCEDURE, steps = listOf(
            ProcedureStep(OperationType.COPY, listOf(exact(file)), ProcedureLocation(destination)),
            ProcedureStep(OperationType.DELETE, listOf(exact(file))),
        ))
        val started = mutableListOf<Int>()

        val result = execution.run(request, onStep = { index, _ -> started += index }, onEvent = {})

        assertEquals(listOf(1), started)
        assertEquals(listOf(ItemStatus.CONFLICT), result.outcomes.map { it.status })
        assertTrue(storage.exists(file))
        assertEquals("existing", storage.text(existing))
    }

    @Test fun `an operation failure stops later actions without deleting its source`() = runBlocking {
        val file = storage.file(source, "file", "contents")
        storage.writeFailure = StorageError.IO
        val request = OperationRequest(type = OperationType.PROCEDURE, steps = listOf(
            ProcedureStep(OperationType.COPY, listOf(exact(file)), ProcedureLocation(destination)),
            ProcedureStep(OperationType.DELETE, listOf(exact(file))),
        ))
        val started = mutableListOf<Int>()

        val result = execution.run(request, onStep = { index, _ -> started += index }, onEvent = {})

        assertEquals(listOf(1), started)
        assertEquals(listOf(ItemStatus.FAILED), result.outcomes.map { it.status })
        assertEquals(StorageError.IO, result.outcomes.single().error)
        assertTrue(storage.exists(file))
        assertNull(storage.childRef(destination, "file"))
        assertFalse(storage.hasStages())
    }

    @Test fun `a failed selection retains earlier results and stops dependent actions`() = runBlocking {
        val file = storage.file(source, "file", "contents")
        val request = OperationRequest(type = OperationType.PROCEDURE, steps = listOf(
            ProcedureStep(OperationType.COPY, listOf(exact(file)), ProcedureLocation(destination)),
            ProcedureStep(OperationType.DELETE,
                listOf(ProcedureSource(ProcedureLocation(source, listOf("missing"))))),
            ProcedureStep(OperationType.DELETE, listOf(exact(file))),
        ))
        val events = mutableListOf<OperationEvent>()
        val started = mutableListOf<Int>()

        val result = execution.run(request, onStep = { index, _ -> started += index }, onEvent = { events += it })

        assertEquals(listOf(1, 2), started)
        assertEquals(listOf(ItemStatus.SUCCESS, ItemStatus.FAILED), result.outcomes.map { it.status })
        assertEquals(StorageError.NOT_FOUND, result.outcomes.last().error)
        assertEquals(result.outcomes, events.filterIsInstance<OperationEvent.ItemFinished>().map { it.outcome })
        assertEquals("contents", storage.text(storage.childRef(destination, "file")!!))
        assertTrue(storage.exists(file))
    }

    @Test fun `cancellation propagates and never starts the next action`() = runBlocking {
        val file = storage.file(source, "file", "contents")
        registry.register(object : StorageProvider by storage {
            override suspend fun openRead(ref: NodeRef): java.io.InputStream {
                if (ref == file) throw CancellationException("Cancelled")
                return storage.openRead(ref)
            }
        })
        val request = OperationRequest(type = OperationType.PROCEDURE, steps = listOf(
            ProcedureStep(OperationType.COPY, listOf(exact(file)), ProcedureLocation(destination)),
            ProcedureStep(OperationType.DELETE, listOf(exact(file))),
        ))
        val started = mutableListOf<Int>()
        val events = mutableListOf<OperationEvent>()

        try {
            execution.run(request, onStep = { index, _ -> started += index }, onEvent = { events += it })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(listOf(1), started)
            assertTrue(storage.exists(file))
            assertNull(storage.childRef(destination, "file"))
            assertFalse(storage.hasStages())
            assertEquals(listOf(ItemStatus.CANCELLED),
                events.filterIsInstance<OperationEvent.ItemFinished>().map { it.outcome.status })
        }
    }

    @Test fun `an empty match allows the following action to run`() = runBlocking {
        val file = storage.file(source, "keep.txt", "contents")
        val request = OperationRequest(type = OperationType.PROCEDURE, steps = listOf(
            ProcedureStep(OperationType.DELETE, listOf(ProcedureSource(ProcedureLocation(source), "*.tmp"))),
            ProcedureStep(OperationType.COPY, listOf(exact(file)), ProcedureLocation(destination)),
        ))
        val started = mutableListOf<Int>()

        val result = execution.run(request, onStep = { index, _ -> started += index }, onEvent = {})

        assertEquals(listOf(1, 2), started)
        assertEquals(listOf(ItemStatus.SUCCESS), result.outcomes.map { it.status })
        assertEquals("contents", storage.text(storage.childRef(destination, "keep.txt")!!))
        assertTrue(storage.exists(file))
    }

    @Test fun `dependent actions resolve the state produced by earlier actions`() = runBlocking {
        val file = storage.file(source, "original.txt", "contents")
        val folder = ProcedureLocation(destination, listOf("created"))
        val copied = ProcedureSource(ProcedureLocation(destination, listOf("created", "original.txt")))
        val renamed = ProcedureSource(ProcedureLocation(destination, listOf("created", "renamed.txt")))
        val request = OperationRequest(type = OperationType.PROCEDURE, steps = listOf(
            ProcedureStep(OperationType.CREATE_FOLDER, destination = ProcedureLocation(destination), name = "created"),
            ProcedureStep(OperationType.COPY, listOf(exact(file)), folder),
            ProcedureStep(OperationType.RENAME, listOf(copied), name = "renamed.txt"),
            ProcedureStep(OperationType.MOVE, listOf(renamed), ProcedureLocation(destination)),
            ProcedureStep(OperationType.DELETE, listOf(ProcedureSource(folder))),
        ))
        val events = mutableListOf<OperationEvent>()
        val started = mutableListOf<Int>()

        val result = execution.run(request, onStep = { index, _ -> started += index }, onEvent = { events += it })

        assertEquals(listOf(1, 2, 3, 4, 5), started)
        assertEquals(5, result.outcomes.size)
        assertTrue(result.successful)
        assertEquals(request.id, result.requestId)
        assertTrue(events.all { it.requestId == request.id })
        assertEquals("contents", storage.text(storage.childRef(destination, "renamed.txt")!!))
        assertNull(storage.childRef(destination, "created"))
        assertTrue(storage.exists(file))
    }

    @Test fun `explicitly skipped conflicts continue and remain visible in aggregate results`() = runBlocking {
        val first = storage.file(source, "first", "one")
        val second = storage.file(source, "second", "two")
        val existing = storage.file(destination, "second", "existing")
        val request = OperationRequest(type = OperationType.PROCEDURE, steps = listOf(
            ProcedureStep(OperationType.COPY, listOf(exact(first)), ProcedureLocation(destination)),
            ProcedureStep(OperationType.COPY, listOf(exact(second)), ProcedureLocation(destination),
                conflictPolicy = ConflictPolicy.SKIP),
            ProcedureStep(OperationType.CREATE_FOLDER, destination = ProcedureLocation(destination), name = "last"),
        ))

        val result = execution.run(request, onStep = { _, _ -> }, onEvent = {})

        assertEquals(listOf(ItemStatus.SUCCESS, ItemStatus.SKIPPED, ItemStatus.SUCCESS), result.outcomes.map { it.status })
        assertEquals("one", storage.text(storage.childRef(destination, "first")!!))
        assertEquals("existing", storage.text(existing))
        assertTrue(storage.stat(storage.childRef(destination, "last")!!).directory)
    }
}
