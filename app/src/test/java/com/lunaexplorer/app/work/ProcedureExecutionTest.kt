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

    @Test fun `conditions can combine an earlier empty result with a skipped step`() = runBlocking {
        val file = storage.file(source, "keep.txt", "contents")
        val selection = ProcedureStep(OperationType.DELETE,
            listOf(ProcedureSource(ProcedureLocation(source), "*.tmp")))
        val skipped = ProcedureStep(OperationType.DELETE, listOf(exact(file)), conditions = listOf(
            ProcedureCondition(selection.id, ProcedureConditionTest.HAS_OUTPUT)))
        val fallback = ProcedureStep(OperationType.CREATE_FOLDER,
            destination = ProcedureLocation(destination), name = "fallback", conditions = listOf(
                ProcedureCondition(selection.id, ProcedureConditionTest.NO_OUTPUT),
                ProcedureCondition(skipped.id, ProcedureConditionTest.SKIPPED)))
        val started = mutableListOf<Int>()
        val finished = mutableListOf<ProcedureStepResult>()

        val result = execution.run(OperationRequest(type = OperationType.PROCEDURE,
            steps = listOf(selection, skipped, fallback)),
            onStep = { number, _ -> started += number }, onEvent = {},
            onStepFinished = { _, _, step -> finished += step })

        assertEquals(listOf(1, 3), started)
        assertEquals(listOf(ProcedureStepStatus.SUCCEEDED, ProcedureStepStatus.SKIPPED,
            ProcedureStepStatus.SUCCEEDED), result.steps.map { it.status })
        assertEquals(listOf(0, 0, 1), result.steps.map { it.outputCount })
        assertEquals(result.steps, finished)
        assertTrue(storage.exists(file))
        assertNotNull(storage.childRef(destination, "fallback"))
    }

    @Test fun `a failure branch sees partial output and a later stop retains the failure`() = runBlocking {
        val copied = storage.file(source, "copied.txt", "contents")
        val collision = storage.file(source, "collision.txt", "new")
        val existing = storage.file(destination, "collision.txt", "existing")
        val copy = ProcedureStep(OperationType.COPY, listOf(exact(copied), exact(collision)),
            ProcedureLocation(destination), onFailure = ProcedureFailurePolicy.CONTINUE)
        val fallback = ProcedureStep(OperationType.CREATE_FOLDER,
            destination = ProcedureLocation(destination), name = "partial", conditions = listOf(
                ProcedureCondition(copy.id, ProcedureConditionTest.FAILED),
                ProcedureCondition(copy.id, ProcedureConditionTest.HAS_OUTPUT)))
        val stop = ProcedureStep(control = ProcedureControl.STOP, conditions = listOf(
            ProcedureCondition(copy.id, ProcedureConditionTest.FAILED)))
        val cleanup = ProcedureStep(OperationType.DELETE, listOf(exact(source)))

        val result = execution.run(OperationRequest(type = OperationType.PROCEDURE,
            steps = listOf(copy, fallback, stop, cleanup)), onStep = { _, _ -> }, onEvent = {})

        assertEquals(listOf(ItemStatus.SUCCESS, ItemStatus.CONFLICT, ItemStatus.SUCCESS),
            result.outcomes.map { it.status })
        assertEquals(listOf(ProcedureStepStatus.FAILED, ProcedureStepStatus.SUCCEEDED,
            ProcedureStepStatus.SUCCEEDED), result.steps.map { it.status })
        assertEquals(1, result.steps.first().outputCount)
        assertEquals(3, result.stoppedAt)
        assertTrue(result.stoppedByControl)
        assertFalse(result.successful)
        assertTrue(result.summary().contains("1 failed"))
        assertEquals("contents", storage.text(storage.childRef(destination, "copied.txt")!!))
        assertEquals("existing", storage.text(existing))
        assertNotNull(storage.childRef(destination, "partial"))
        assertTrue(storage.exists(source))
    }

    @Test fun `a conditional stop after an empty selection succeeds without running later steps`() = runBlocking {
        val file = storage.file(source, "keep.txt", "contents")
        val select = ProcedureStep(OperationType.COPY,
            listOf(ProcedureSource(ProcedureLocation(source), "*.tmp")), ProcedureLocation(destination))
        val stop = ProcedureStep(control = ProcedureControl.STOP, conditions = listOf(
            ProcedureCondition(select.id, ProcedureConditionTest.NO_OUTPUT)))
        val remove = ProcedureStep(OperationType.DELETE, listOf(exact(file)))

        val result = execution.run(OperationRequest(type = OperationType.PROCEDURE,
            steps = listOf(select, stop, remove)), onStep = { _, _ -> }, onEvent = {})

        assertTrue(result.successful)
        assertTrue(result.outcomes.isEmpty())
        assertEquals(2, result.stoppedAt)
        assertEquals(listOf(select.id, stop.id), result.steps.map { it.stepId })
        assertTrue(storage.exists(file))
    }

    @Test fun `missing destination folders use the engine without becoming step output`() = runBlocking {
        val file = storage.file(source, "file.txt", "contents")
        val copy = ProcedureStep(OperationType.COPY, listOf(exact(file)),
            ProcedureLocation(destination, listOf("2026", "September")), createDestination = true)
        val events = mutableListOf<OperationEvent>()
        val request = OperationRequest(type = OperationType.PROCEDURE, steps = listOf(copy))

        val result = execution.run(request, onStep = { _, _ -> }, onEvent = { events += it })

        val year = requireNotNull(storage.childRef(destination, "2026"))
        val month = requireNotNull(storage.childRef(year, "September"))
        assertEquals("contents", storage.text(storage.childRef(month, "file.txt")!!))
        assertEquals(listOf(ItemStatus.SUCCESS, ItemStatus.SUCCESS, ItemStatus.SUCCESS),
            result.outcomes.map { it.status })
        assertEquals(1, result.steps.single().outputCount)
        assertEquals(result.outcomes, events.filterIsInstance<OperationEvent.ItemFinished>().map { it.outcome })
        assertTrue(events.all { it.requestId == request.id })
        assertTrue(events.filterIsInstance<OperationEvent.Journal>().any {
            it.phase == JournalPhase.PUBLISHED && it.artifact == year
        })
        assertFalse(storage.hasStages())
    }

    @Test fun `an empty selection does not create the destination`() = runBlocking {
        val copy = ProcedureStep(OperationType.COPY,
            listOf(ProcedureSource(ProcedureLocation(source), "*.txt")),
            ProcedureLocation(destination, listOf("unused")), createDestination = true)

        val result = execution.run(OperationRequest(type = OperationType.PROCEDURE, steps = listOf(copy)),
            onStep = { _, _ -> }, onEvent = {})

        assertTrue(result.successful)
        assertTrue(result.outcomes.isEmpty())
        assertEquals(0, result.steps.single().outputCount)
        assertNull(storage.childRef(destination, "unused"))
    }

    @Test fun `destination creation failure is reported once and can trigger a failure branch`() = runBlocking {
        val file = storage.file(source, "file.txt", "contents")
        storage.deniedCapabilities[destination] = setOf(Capability.CREATE)
        val copy = ProcedureStep(OperationType.COPY, listOf(exact(file)),
            ProcedureLocation(destination, listOf("missing")), createDestination = true,
            onFailure = ProcedureFailurePolicy.CONTINUE)
        val stop = ProcedureStep(control = ProcedureControl.STOP, conditions = listOf(
            ProcedureCondition(copy.id, ProcedureConditionTest.FAILED)))
        val events = mutableListOf<OperationEvent>()

        val result = execution.run(OperationRequest(type = OperationType.PROCEDURE, steps = listOf(copy, stop)),
            onStep = { _, _ -> }, onEvent = { events += it })

        assertEquals(listOf(ItemStatus.FAILED), result.outcomes.map { it.status })
        assertEquals(result.outcomes, events.filterIsInstance<OperationEvent.ItemFinished>().map { it.outcome })
        assertEquals(ProcedureStepStatus.FAILED, result.steps.first().status)
        assertEquals(0, result.steps.first().outputCount)
        assertEquals(2, result.stoppedAt)
        assertNull(storage.childRef(destination, "missing"))
        assertTrue(storage.exists(file))
    }

    @Test fun `invalid branches are rejected before any earlier mutation`() = runBlocking {
        val file = storage.file(source, "keep.txt", "contents")
        val remove = ProcedureStep(OperationType.DELETE, listOf(exact(file)))
        val stop = ProcedureStep(control = ProcedureControl.STOP, conditions = listOf(
            ProcedureCondition("missing step", ProcedureConditionTest.FAILED)))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                execution.run(OperationRequest(type = OperationType.PROCEDURE, steps = listOf(remove, stop)),
                    onStep = { _, _ -> }, onEvent = {})
            }
        }
        assertTrue(storage.exists(file))
    }
}
