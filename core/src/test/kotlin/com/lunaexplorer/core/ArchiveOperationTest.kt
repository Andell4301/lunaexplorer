package com.lunaexplorer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ArchiveOperationTest {
    @get:Rule val temporary = TemporaryFolder()
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

    @Test fun `archiving removes sources only after the archive is verified and published`() = runBlocking {
        val refs = sources(2)
        val request = createRequest(listOf(sourceFolder)).copy(deleteAfterArchive = true)
        var published = false
        val result = engine.run(request) { event ->
            if (event is OperationEvent.Journal && event.phase == JournalPhase.PUBLISHED) {
                published = true
                assertTrue(refs.all(storage::exists))
            }
            if (event is OperationEvent.Journal && event.phase == JournalPhase.DELETING_SOURCE) {
                assertTrue(published)
                assertNotNull(storage.childRef(destination, "bundle.zip"))
            }
        }

        assertTrue(result.successful)
        assertFalse(storage.exists(sourceFolder))
        val archive = requireNotNull(storage.childRef(destination, "bundle.zip"))
        assertTrue(engine.run(OperationRequest(type = OperationType.EXTRACT_ARCHIVE, sources = listOf(archive),
            destination = destination, name = "unpacked")).successful)
        val unpacked = requireNotNull(storage.childRef(destination, "unpacked"))
        val restored = requireNotNull(storage.childRef(unpacked, "source"))
        assertEquals("contents 2", storage.text(requireNotNull(storage.childRef(restored, "file2.txt"))))
    }

    @Test fun `a corrupt archive write retains every source`() = runBlocking {
        val refs = sources(2)
        storage.corruptStageReads = true
        val result = engine.run(createRequest(refs).copy(deleteAfterArchive = true))

        assertFalse(result.successful)
        assertTrue(refs.all(storage::exists))
        assertNull(storage.childRef(destination, "bundle.zip"))
    }

    @Test fun `failed publication and existing archive names retain sources`() = runBlocking {
        val refs = sources(2)
        storage.commitFailure = StorageError.IO
        assertFalse(engine.run(createRequest(refs).copy(deleteAfterArchive = true)).successful)
        assertTrue(refs.all(storage::exists))
        storage.commitFailure = null
        storage.file(destination, "bundle.zip", "existing")
        assertFalse(engine.run(createRequest(refs).copy(deleteAfterArchive = true)).successful)
        assertTrue(refs.all(storage::exists))
    }

    @Test fun `cancelled archive creation retains sources`() = runBlocking {
        val refs = sources(2)
        val failure = runCatching {
            engine.run(createRequest(refs).copy(deleteAfterArchive = true)) { event ->
                if (event is OperationEvent.Progress) throw CancellationException("Stop")
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(refs.all(storage::exists))
    }

    @Test fun `source edits and new children after archiving prevent source deletion`() = runBlocking {
        val refs = sources(2)
        storage.afterCommit = { storage.file(sourceFolder, "new.txt", "new") }
        val failure = runCatching {
            engine.run(createRequest(listOf(sourceFolder)).copy(deleteAfterArchive = true))
        }.exceptionOrNull()

        assertTrue(failure is StorageException)
        assertTrue(refs.all(storage::exists))
        assertNotNull(storage.childRef(sourceFolder, "new.txt"))
        assertNotNull(storage.childRef(destination, "bundle.zip"))
    }

    @Test fun `a format that omits folders cannot delete the source folder`() = runBlocking {
        val refs = sources(1)
        val result = engine.run(createRequest(listOf(sourceFolder), name = "bundle.gz",
            spec = ArchiveSpec(format = ArchiveFormat.GZIP)).copy(deleteAfterArchive = true))

        assertFalse(result.successful)
        assertTrue(refs.all(storage::exists))
        assertTrue(storage.exists(sourceFolder))
    }

    @Test fun `sources skipped as symbolic links are never deleted`() = runBlocking {
        val ref = sources(1).single()
        val linked = object : StorageProvider by storage {
            override suspend fun stat(ref: NodeRef): Entry = storage.stat(ref).copy(link = true)
        }
        val linkedEngine = OperationEngine(ProviderRegistry(listOf(linked)))
        val result = linkedEngine.run(createRequest(listOf(ref)).copy(deleteAfterArchive = true))

        assertFalse(result.successful)
        assertTrue(storage.exists(ref))
        assertNull(storage.childRef(destination, "bundle.zip"))
    }

    @Test fun `extracting with source removal retains verified files and removes the archive`() = runBlocking {
        assertTrue(engine.run(createRequest(sources(2))).successful)
        val archive = requireNotNull(storage.childRef(destination, "bundle.zip"))
        val result = engine.run(OperationRequest(type = OperationType.EXTRACT_ARCHIVE, sources = listOf(archive),
            destination = destination, name = "unpacked", deleteAfterArchive = true))

        assertTrue(result.successful)
        assertFalse(storage.exists(archive))
        val unpacked = requireNotNull(storage.childRef(destination, "unpacked"))
        assertEquals("contents 2", storage.text(requireNotNull(storage.childRef(unpacked, "file2.txt"))))
    }

    @Test fun `failed extraction retains the source archive`() = runBlocking {
        assertTrue(engine.run(createRequest(sources(2))).successful)
        val archive = requireNotNull(storage.childRef(destination, "bundle.zip"))
        storage.writeFailure = StorageError.NO_SPACE
        val result = engine.run(OperationRequest(type = OperationType.EXTRACT_ARCHIVE, sources = listOf(archive),
            destination = destination, name = "unpacked", deleteAfterArchive = true))

        assertFalse(result.successful)
        assertTrue(storage.exists(archive))
    }

    @Test fun `corrupt extracted bytes retain the source archive`() = runBlocking {
        assertTrue(engine.run(createRequest(sources(2))).successful)
        val archive = requireNotNull(storage.childRef(destination, "bundle.zip"))
        val corrupt = object : StorageProvider by storage {
            override suspend fun openRead(ref: NodeRef) = if (storage.parentOf(ref)?.let {
                storage.stat(it).name == "unpacked"
            } == true) ByteArrayInputStream("corrupt".toByteArray()) else storage.openRead(ref)
        }
        val checked = OperationEngine(ProviderRegistry(listOf(corrupt)))
        val result = checked.run(OperationRequest(type = OperationType.EXTRACT_ARCHIVE, sources = listOf(archive),
            destination = destination, name = "unpacked", deleteAfterArchive = true))

        assertFalse(result.successful)
        assertTrue(storage.exists(archive))
    }

    @Test fun `source removal waits for deferred storage to flush`() = runBlocking {
        val refs = sources(1)
        val deferred = object : StorageProvider by storage, DeferredWrites {
            override suspend fun flush(run: StorageRun) {
                assertTrue(refs.all(storage::exists))
                throw StorageException(StorageError.IO, "Flush failed")
            }
            override suspend fun discardPending(run: StorageRun) = Unit
        }
        val deferredEngine = OperationEngine(ProviderRegistry(listOf(deferred)))
        val failure = runCatching {
            deferredEngine.run(createRequest(refs).copy(deleteAfterArchive = true))
        }.exceptionOrNull()

        assertTrue(failure is StorageException)
        assertTrue(refs.all(storage::exists))
    }

    @Test fun `a changed read during archiving cannot delete the original bytes`() = runBlocking {
        val ref = sources(1).single()
        var reads = 0
        val changing = object : StorageProvider by storage {
            override suspend fun openRead(source: NodeRef) =
                if (source == ref && ++reads == 2) ByteArrayInputStream("truncated".toByteArray())
                else storage.openRead(source)
        }
        val result = OperationEngine(ProviderRegistry(listOf(changing)))
            .run(createRequest(listOf(ref)).copy(deleteAfterArchive = true))

        assertFalse(result.successful)
        assertEquals("contents 1", storage.text(ref))
    }

    @Test fun `a source omitted from the archive plan is retained`() = runBlocking {
        val refs = sources(2)
        var listings = 0
        val changing = object : StorageProvider by storage {
            override fun list(parent: NodeRef, complete: Boolean): Flow<List<Entry>> {
                val omit = parent == sourceFolder && ++listings == 2
                return storage.list(parent, complete).map { batch ->
                    if (omit) batch.filter { it.ref != refs.last() } else batch
                }
            }
        }
        val result = OperationEngine(ProviderRegistry(listOf(changing)))
            .run(createRequest(listOf(sourceFolder)).copy(deleteAfterArchive = true))

        assertFalse(result.successful)
        assertTrue(refs.all(storage::exists))
    }

    @Test fun `same named sources from separate folders are retained`() = runBlocking {
        val first = storage.file(sourceFolder, "same.txt", "one")
        val second = storage.file(destination, "same.txt", "two")
        val result = engine.run(createRequest(listOf(first, second)).copy(deleteAfterArchive = true))

        assertFalse(result.successful)
        assertEquals("one", storage.text(first))
        assertEquals("two", storage.text(second))
    }

    @Test fun `sources whose member names extract to the same path are retained`() = runBlocking {
        val flat = storage.file(sourceFolder, "a\\b.txt", "one")
        val folder = storage.folder(sourceFolder, "a")
        val nested = storage.file(folder, "b.txt", "two")
        val result = engine.run(createRequest(listOf(flat, folder)).copy(deleteAfterArchive = true))

        assertFalse(result.successful)
        assertEquals("one", storage.text(flat))
        assertEquals("two", storage.text(nested))
    }

    @Test fun `duplicate extracted members retain the archive`() = runBlocking {
        val archive = storage.create(destination, "duplicate.tar", false).ref
        storage.openWrite(archive).use { output ->
            TarArchiveOutputStream(output).use { tar ->
                for (text in listOf("one", "two")) {
                    tar.putArchiveEntry(TarArchiveEntry("same.txt").apply { size = 3 })
                    tar.write(text.toByteArray())
                    tar.closeArchiveEntry()
                }
            }
        }
        val result = engine.run(OperationRequest(type = OperationType.EXTRACT_ARCHIVE, sources = listOf(archive),
            destination = destination, name = "unpacked", deleteAfterArchive = true))

        assertFalse(result.successful)
        assertTrue(storage.exists(archive))
    }

    @Test fun `an archive containing only an empty folder can be extracted and removed`() = runBlocking {
        assertTrue(engine.run(createRequest(listOf(sourceFolder))).successful)
        val archive = requireNotNull(storage.childRef(destination, "bundle.zip"))
        val result = engine.run(OperationRequest(type = OperationType.EXTRACT_ARCHIVE, sources = listOf(archive),
            destination = destination, name = "unpacked", deleteAfterArchive = true))

        assertTrue(result.successful)
        assertFalse(storage.exists(archive))
        val unpacked = requireNotNull(storage.childRef(destination, "unpacked"))
        assertTrue(storage.stat(requireNotNull(storage.childRef(unpacked, "source"))).directory)
    }

    @Test fun `an extracted empty folder disappearing before cleanup retains the archive`() = runBlocking {
        assertTrue(engine.run(createRequest(listOf(sourceFolder))).successful)
        val archive = requireNotNull(storage.childRef(destination, "bundle.zip"))
        val failure = runCatching {
            engine.run(OperationRequest(type = OperationType.EXTRACT_ARCHIVE, sources = listOf(archive),
                destination = destination, name = "unpacked", deleteAfterArchive = true)) { event ->
                if (event is OperationEvent.Journal && event.phase == JournalPhase.PUBLISHED) {
                    val unpacked = requireNotNull(storage.childRef(destination, "unpacked"))
                    storage.delete(requireNotNull(storage.childRef(unpacked, "source")))
                }
            }
        }.exceptionOrNull()

        assertTrue(failure is StorageException)
        assertTrue(storage.exists(archive))
    }

    @Test fun `cancelled extraction retains the source archive`() = runBlocking {
        assertTrue(engine.run(createRequest(sources(2))).successful)
        val archive = requireNotNull(storage.childRef(destination, "bundle.zip"))
        val failure = runCatching {
            engine.run(OperationRequest(type = OperationType.EXTRACT_ARCHIVE, sources = listOf(archive),
                destination = destination, name = "unpacked", deleteAfterArchive = true)) { event ->
                if (event is OperationEvent.Progress) throw CancellationException("Stop")
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(storage.exists(archive))
    }

    @Test fun `an unsupported seven z link retains the archive`() = runBlocking {
        val channel = SeekableInMemoryByteChannel()
        SevenZOutputFile(channel).use { output ->
            output.putArchiveEntry(SevenZArchiveEntry().apply {
                name = "link"
                hasWindowsAttributes = true
                windowsAttributes = (0xa1ff shl 16) or 0x8000
            })
            output.write("target".toByteArray())
            output.closeArchiveEntry()
        }
        val archive = storage.create(destination, "links.7z", false).ref
        storage.openWrite(archive).use { it.write(channel.array(), 0, channel.size().toInt()) }
        val result = engine.run(OperationRequest(type = OperationType.EXTRACT_ARCHIVE, sources = listOf(archive),
            destination = destination, name = "unpacked", deleteAfterArchive = true))

        assertFalse(result.successful)
        assertTrue(storage.exists(archive))
    }

    @Test fun `different valid archive bytes during extraction cannot delete the original archive`() = runBlocking {
        for (format in listOf(ArchiveFormat.ZIP, ArchiveFormat.TAR)) {
            fun bytes(text: String): ByteArray {
                val members = mapOf("readme.txt" to text.toByteArray())
                return if (format == ArchiveFormat.ZIP) {
                    zip4jBytes(members, emptySet(), "", ZipEncryption.NONE, stored = false)
                } else tarBytes(format, members)
            }
            val original = bytes("original")
            val other = bytes("different")
            val archive = storage.create(destination, "source.${format.extension}", false).ref
            storage.openWrite(archive).use { it.write(original) }
            var reads = 0
            val changing = object : StorageProvider by storage {
                override suspend fun openRead(ref: NodeRef) =
                    if (ref == archive && ++reads == 2) ByteArrayInputStream(other) else storage.openRead(ref)
            }
            val providers = ProviderRegistry(listOf(changing))
            val staging = temporary.newFolder(format.name)
            val checked = OperationEngine(providers, archives = ArchiveEngine(providers, stagingDirectory = staging))
            val result = checked.run(OperationRequest(type = OperationType.EXTRACT_ARCHIVE, sources = listOf(archive),
                destination = destination, name = "unpacked-${format.name}", deleteAfterArchive = true))

            assertFalse(result.successful)
            assertTrue(storage.exists(archive))
            assertArrayEquals(original, storage.openRead(archive).use { it.readBytes() })
            assertTrue(staging.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun `verified extraction stages are removed after success or cancellation`() = runBlocking {
        assertTrue(engine.run(createRequest(sources(2))).successful)
        val archive = requireNotNull(storage.childRef(destination, "bundle.zip"))
        for (cancel in listOf(true, false)) {
            val staging = temporary.newFolder("stage-$cancel")
            val checked = OperationEngine(registry, archives = ArchiveEngine(registry, stagingDirectory = staging))
            val result = runCatching {
                checked.run(OperationRequest(type = OperationType.EXTRACT_ARCHIVE, sources = listOf(archive),
                    destination = destination, name = "unpacked-$cancel", deleteAfterArchive = true)) { event ->
                    if (cancel && event is OperationEvent.Progress) throw CancellationException("Stop")
                }
            }

            if (cancel) assertTrue(result.exceptionOrNull() is CancellationException)
            else assertTrue(result.getOrThrow().successful)
            assertEquals(cancel, storage.exists(archive))
            assertTrue(staging.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun `verified extraction consumes the checked bytes when a provider channel disagrees`() = runBlocking {
        assertTrue(engine.run(createRequest(sources(1))).successful)
        val archive = requireNotNull(storage.childRef(destination, "bundle.zip"))
        val other = zip4jBytes(mapOf("file1.txt" to "different".toByteArray()),
            emptySet(), "", ZipEncryption.NONE, stored = false)
        val changing = object : StorageProvider by storage {
            override suspend fun openChannel(ref: NodeRef) = if (ref == archive) SeekableInMemoryByteChannel(other) else null
        }
        val checked = OperationEngine(ProviderRegistry(listOf(changing)))
        val result = checked.run(OperationRequest(type = OperationType.EXTRACT_ARCHIVE, sources = listOf(archive),
            destination = destination, name = "unpacked", deleteAfterArchive = true))

        assertTrue(result.successful)
        val unpacked = requireNotNull(storage.childRef(destination, "unpacked"))
        assertEquals("contents 1", storage.text(requireNotNull(storage.childRef(unpacked, "file1.txt"))))
        assertFalse(storage.exists(archive))
    }
}
