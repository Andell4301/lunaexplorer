package com.lunaexplorer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    @Test fun `missing destination folders are created through engine requests with exact names`() = runBlocking {
        val file = storage.file(source, "notes.txt", "contents")
        val existing = storage.folder(destination, "existing")
        val step = ProcedureStep(OperationType.COPY, listOf(ProcedureSource(ProcedureLocation(file))),
            ProcedureLocation(destination, listOf("existing", " new folder ", "final")), createDestination = true)
        val engine = OperationEngine(registry)
        val prepared = mutableListOf<OperationRequest>()

        val action = planner.plan(step) { request ->
            prepared += request
            engine.run(request)
        }.single()
        assertEquals(listOf(" new folder ", "final"), prepared.map { it.name })
        assertTrue(prepared.all { it.type == OperationType.CREATE_FOLDER })
        assertEquals(existing, prepared.first().destination)
        assertTrue(engine.run(action).successful)
        assertEquals("contents", storage.text(storage.childRef(action.destination!!, "notes.txt")!!))

        assertEquals(action.destination, planner.plan(step) {
            fail("Existing folders must not be recreated")
            engine.run(it)
        }.single().destination)
    }

    @Test fun `empty or failed selections do not create destination folders`() = runBlocking {
        val target = ProcedureLocation(destination, listOf("new"))
        val empty = ProcedureStep(OperationType.COPY,
            listOf(ProcedureSource(ProcedureLocation(source), "*.pdf")), target, createDestination = true)
        val engine = OperationEngine(registry)
        val prepare: suspend (OperationRequest) -> OperationResult = {
            fail("Selection must finish before destination preparation")
            engine.run(it)
        }

        assertTrue(planner.plan(empty, prepare).isEmpty())
        val failed = empty.copy(sources = listOf(ProcedureSource(ProcedureLocation(source, listOf("missing")))))
        try {
            planner.plan(failed, prepare)
            fail("A missing source must fail")
        } catch (failure: StorageException) {
            assertEquals(StorageError.NOT_FOUND, failure.reason)
        }
        assertNull(storage.childRef(destination, "new"))
    }

    @Test fun `destination creation never replaces a file or follows a folder link`() = runBlocking {
        val file = storage.file(source, "source.txt", "contents")
        val blocker = storage.file(destination, "blocker", "keep")
        val link = storage.folder(destination, "link")
        registry.register(object : StorageProvider by storage {
            override fun list(parent: NodeRef, complete: Boolean) = storage.list(parent, complete).map { entries ->
                entries.map { if (it.ref == link) it.copy(link = true) else it }
            }
            override suspend fun child(parent: NodeRef, name: String): Entry? =
                storage.child(parent, name)?.let { if (it.ref == link) it.copy(link = true) else it }
        })
        val engine = OperationEngine(registry)

        for (name in listOf("blocker", "link")) {
            val step = ProcedureStep(OperationType.COPY, listOf(ProcedureSource(ProcedureLocation(file))),
                ProcedureLocation(destination, listOf(name, "new")), createDestination = true)
            try {
                planner.plan(step) { fail("Blocked destinations must not create folders"); engine.run(it) }
                fail("The destination must fail")
            } catch (failure: StorageException) {
                assertEquals(StorageError.UNSUPPORTED, failure.reason)
            }
        }
        assertEquals("keep", storage.text(blocker))
        assertNull(storage.childRef(link, "new"))
    }

    @Test fun `uncertain destination creation is not retried or cleaned up`() = runBlocking {
        val file = storage.file(source, "source.txt", "contents")
        var creates = 0
        registry.register(object : StorageProvider by storage {
            override suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String): Entry {
                creates++
                storage.create(parent, name, directory, mimeType)
                throw StorageException(StorageError.IO, "Response lost")
            }
        })
        val engine = OperationEngine(registry)
        val step = ProcedureStep(OperationType.COPY, listOf(ProcedureSource(ProcedureLocation(file))),
            ProcedureLocation(destination, listOf("first", "second")), createDestination = true)

        try {
            planner.plan(step) { engine.run(it) }
            fail("An uncertain creation must fail")
        } catch (failure: StorageException) {
            assertEquals(StorageError.IO, failure.reason)
        }
        assertEquals(1, creates)
        val retained = storage.childRef(destination, "first")!!
        assertNull(storage.childRef(retained, "second"))
        assertTrue(storage.exists(file))
    }

    @Test fun `permission and cancellation failures never become missing destination folders`() = runBlocking {
        val file = storage.file(source, "source.txt", "contents")
        val step = ProcedureStep(OperationType.COPY, listOf(ProcedureSource(ProcedureLocation(file))),
            ProcedureLocation(destination, listOf("new")), createDestination = true)
        val engine = OperationEngine(registry)
        for (cancelled in listOf(false, true)) {
            registry.register(object : StorageProvider by storage {
                override suspend fun child(parent: NodeRef, name: String): Entry? {
                    if (cancelled) throw CancellationException("Cancelled")
                    throw StorageException(StorageError.PERMISSION, "Denied")
                }
            })
            try {
                planner.plan(step) { fail("A failed lookup must not create folders"); engine.run(it) }
                fail("The lookup must fail")
            } catch (failure: CancellationException) {
                assertTrue(cancelled)
            } catch (failure: StorageException) {
                assertFalse(cancelled)
                assertEquals(StorageError.PERMISSION, failure.reason)
            }
        }
        assertNull(storage.childRef(destination, "new"))
    }

    @Test fun `conditions can combine results from any earlier steps`() {
        val first = ProcedureStep(OperationType.DELETE, listOf(ProcedureSource(ProcedureLocation(source))))
        val second = first.copy(id = "second")
        val stop = ProcedureStep(control = ProcedureControl.STOP, conditions = listOf(
            ProcedureCondition(first.id, ProcedureConditionTest.HAS_OUTPUT),
            ProcedureCondition(second.id, ProcedureConditionTest.FAILED),
        ))
        validateProcedureSteps(listOf(first, second, stop))
        val partial = listOf(
            ProcedureStepResult(first.id, ProcedureStepStatus.SUCCEEDED, 3),
            ProcedureStepResult(second.id, ProcedureStepStatus.SUCCEEDED, 1),
        )

        assertFalse(stop.conditionsMet(partial))
        assertTrue(stop.copy(conditionMatch = ProcedureConditionMatch.ANY).conditionsMet(partial))
        assertTrue(stop.conditionsMet(partial.dropLast(1) + ProcedureStepResult(second.id, ProcedureStepStatus.FAILED)))
        assertFalse(stop.conditionsMet(emptyList()))
    }

    @Test fun `skipped steps are distinct from evaluated steps without output`() {
        fun branch(test: ProcedureConditionTest) = ProcedureStep(control = ProcedureControl.STOP,
            conditions = listOf(ProcedureCondition("previous", test)))
        val skipped = listOf(ProcedureStepResult("previous", ProcedureStepStatus.SKIPPED))
        val empty = listOf(ProcedureStepResult("previous", ProcedureStepStatus.SUCCEEDED))
        val partial = listOf(ProcedureStepResult("previous", ProcedureStepStatus.FAILED, 2))

        assertTrue(branch(ProcedureConditionTest.SKIPPED).conditionsMet(skipped))
        assertFalse(branch(ProcedureConditionTest.NO_OUTPUT).conditionsMet(skipped))
        assertFalse(branch(ProcedureConditionTest.HAS_OUTPUT).conditionsMet(skipped))
        assertTrue(branch(ProcedureConditionTest.NO_OUTPUT).conditionsMet(empty))
        assertTrue(branch(ProcedureConditionTest.SUCCEEDED).conditionsMet(empty))
        assertTrue(branch(ProcedureConditionTest.HAS_OUTPUT).conditionsMet(partial))
        assertTrue(branch(ProcedureConditionTest.FAILED).conditionsMet(partial))
    }

    @Test fun `invalid step references and duplicate IDs are rejected before execution`() {
        val first = ProcedureStep(control = ProcedureControl.STOP)
        val second = ProcedureStep(control = ProcedureControl.STOP,
            conditions = listOf(ProcedureCondition(first.id, ProcedureConditionTest.SUCCEEDED)))
        for (steps in listOf(listOf(first, first), listOf(second, first), listOf(second),
                listOf(first.copy(conditions = listOf(ProcedureCondition(first.id, ProcedureConditionTest.FAILED)))))) {
            assertThrows(IllegalArgumentException::class.java) { validateProcedureSteps(steps) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProcedureStep(control = ProcedureControl.STOP, destination = ProcedureLocation(destination)).validate()
        }
    }

    @Test fun `legacy procedure steps execute and retain new IDs when saved again`() = runBlocking {
        val file = storage.file(source, "legacy.txt", "contents")
        val legacy = """{"type":"COPY","sources":[{"location":{"ref":{"provider":"device","key":"${file.key}"}}}],"destination":{"ref":{"provider":"device","key":"${destination.key}"}}}"""
        val step = Json.decodeFromString<ProcedureStep>(legacy)
        val saved = Json.decodeFromString<ProcedureStep>(Json.encodeToString(step))
        validateProcedureSteps(listOf(saved))

        assertEquals(step.id, saved.id)
        assertTrue(OperationEngine(registry).run(planner.plan(saved).single()).successful)
        assertEquals("contents", storage.text(storage.childRef(destination, "legacy.txt")!!))
    }
}
