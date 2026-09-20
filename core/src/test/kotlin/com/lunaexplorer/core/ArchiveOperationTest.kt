package com.lunaexplorer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ArchiveOperationTest {
    private val storage = MemoryStorageProvider("device")
    private val registry = ProviderRegistry(listOf(storage))
    private val engine = OperationEngine(registry)
    private val sourceFolder = storage.folder(storage.root, "source")
    private val destination = storage.folder(storage.root, "destination")

    private fun sources(count: Int = 5): List<NodeRef> =
        (1..count).map { storage.file(sourceFolder, "file$it.txt", "contents $it") }

    private fun createRequest(
        refs: List<NodeRef>,
        name: String = "bundle.zip",
        spec: ArchiveSpec = ArchiveSpec(),
    ) = OperationRequest(type = OperationType.CREATE_ARCHIVE, sources = refs,
        destination = destination, name = name, archive = spec)

    @Test fun creatingAnArchiveReportsMemberProgressAndPublishesIt() = runBlocking {
        val refs = sources()
        val events = mutableListOf<OperationEvent>()
        val result = engine.run(createRequest(refs)) { events += it }

        assertTrue(result.successful)
        assertNotNull("The archive lands under the name asked for", storage.childRef(destination, "bundle.zip"))
        assertFalse("Its staging file is gone", storage.hasStages())
        val progress = events.filterIsInstance<OperationEvent.Progress>()
        assertTrue("Members are named as they are written", progress.any { it.currentName.isNotEmpty() })
        assertTrue("Every source size is known, so the total is too", progress.all { it.totalBytes != null })
        assertEquals(destination, result.outcomes.single().destination?.let { storage.parentOf(it) })
    }

    @Test fun aCancelledArchiveLeavesNoPartialFile() = runBlocking {
        val refs = sources(20)
        val failure = runCatching {
            engine.run(createRequest(refs)) { event ->
                if (event is OperationEvent.Progress) throw CancellationException("Cancelled by user")
            }
        }.exceptionOrNull()

        assertTrue("Cancellation propagates", failure is CancellationException)
        assertFalse("No staging file may survive a cancelled archive", storage.hasStages())
        assertNull("And nothing is published", storage.childRef(destination, "bundle.zip"))
    }

    @Test fun realJobCancellationAlsoRemovesTheStagingFile() = runBlocking {
        val refs = sources(20)
        // Signal from the event callback; polling the provider would race the engine's dispatcher.
        val writing = CompletableDeferred<Unit>()
        val job = launch {
            engine.run(createRequest(refs)) { event ->
                if (event is OperationEvent.Progress) { writing.complete(Unit); awaitCancellation() }
            }
        }
        writing.await()
        job.cancelAndJoin()

        assertFalse("A cancelled job cleans up after itself", storage.hasStages())
        assertNull(storage.childRef(destination, "bundle.zip"))
    }

    @Test fun anEncryptedArchiveIsRefusedWhenItsPasswordWasNotCarriedOver() = runBlocking {
        val refs = sources(1)
        val spec = ArchiveSpec(encryption = ZipEncryption.AES_256, needsPassword = true)
        val result = engine.run(createRequest(refs, spec = spec))

        val outcome = result.outcomes.single()
        assertEquals(ItemStatus.FAILED, outcome.status)
        assertEquals(StorageError.AUTH, outcome.error)
        assertNull("An encrypted archive is never written in the clear instead",
            storage.childRef(destination, "bundle.zip"))
        assertFalse(storage.hasStages())
    }

    @Test fun anArchiveNameAlreadyTakenFailsBeforeAnythingIsWritten() = runBlocking {
        val refs = sources(1)
        val existing = storage.file(destination, "bundle.zip", "not an archive")
        val result = engine.run(createRequest(refs))

        val outcome = result.outcomes.single()
        assertEquals(ItemStatus.FAILED, outcome.status)
        assertEquals(StorageError.CONFLICT, outcome.error)
        assertEquals("The file in the way is untouched", "not an archive", storage.text(existing))
        assertFalse(storage.hasStages())
    }

    @Test fun extractingAnArchiveRecordsItsDestinationBeforeWritingMembers() = runBlocking {
        val refs = sources(3)
        assertTrue(engine.run(createRequest(refs)).successful)
        val archive = requireNotNull(storage.childRef(destination, "bundle.zip"))

        val events = mutableListOf<OperationEvent>()
        val result = engine.run(OperationRequest(type = OperationType.EXTRACT_ARCHIVE, sources = listOf(archive),
            destination = destination, name = "unpacked")) { events += it }

        assertTrue(result.successful)
        val unpacked = requireNotNull(storage.childRef(destination, "unpacked"))
        assertEquals("contents 1", storage.text(requireNotNull(storage.childRef(unpacked, "file1.txt"))))
        assertEquals("contents 3", storage.text(requireNotNull(storage.childRef(unpacked, "file3.txt"))))

        val journal = events.filterIsInstance<OperationEvent.Journal>()
        assertTrue("The folder members land in is on record before any of them",
            journal.any { it.phase == JournalPhase.IN_PLACE && it.artifact == unpacked })
        // A streamed archive's member count is unknown until it ends, so totalItems stays 0.
        assertTrue(events.filterIsInstance<OperationEvent.Progress>().all { it.totalItems == 0 })
    }

    @Test fun extractingRequiresExactlyOneArchive() = runBlocking {
        val refs = sources(2)
        val failure = runCatching {
            engine.run(OperationRequest(type = OperationType.EXTRACT_ARCHIVE, sources = refs, destination = destination))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test fun creatingAnArchiveNeedsSomethingToPutInIt() = runBlocking {
        val failure = runCatching { engine.run(createRequest(emptyList())) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
