package com.lunaexplorer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OperationEngineTest {
    private val storage = MemoryStorageProvider("device")
    private val engine = OperationEngine(ProviderRegistry(listOf(storage)))
    private val sourceFolder = storage.folder(storage.root, "source")
    private val destination = storage.folder(storage.root, "destination")

    private fun request(source: NodeRef, type: OperationType = OperationType.COPY, policy: ConflictPolicy = ConflictPolicy.ASK) =
        OperationRequest(type = type, sources = listOf(source), destination = destination, conflictPolicy = policy)

    @Test fun copyVerifiesBytesAndKeepsSource() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "trustworthy data")
        val events = mutableListOf<OperationEvent>()
        val result = engine.run(request(source)) { events += it }
        assertTrue(result.successful)
        assertEquals("trustworthy data", storage.text(storage.childRef(destination, "notes.txt")!!))
        assertTrue(storage.exists(source))
        assertFalse(storage.hasStages())
        val phases = events.filterIsInstance<OperationEvent.Journal>().map { it.phase }
        assertTrue(phases.indexOf(JournalPhase.VERIFIED) < phases.indexOf(JournalPhase.COMMITTING))
        assertTrue(phases.indexOf(JournalPhase.COMMITTING) < phases.indexOf(JournalPhase.PUBLISHED))
    }

    @Test fun keepingVersionsCoversWhatTheUserRemovesAndNeverStagingData() = runBlocking {
        val kept = mutableListOf<Pair<String, Boolean>>()
        val watching = object : StorageProvider by storage {
            override suspend fun delete(ref: NodeRef) {
                kept += storage.stat(ref).name.substringBefore('-') to (currentCoroutineContext()[KeepVersions] != null)
                storage.delete(ref)
            }
        }
        val engine = OperationEngine(ProviderRegistry(listOf(watching)))
        val hidden = storage.file(sourceFolder, "hidden.txt", "x")
        val moved = storage.file(sourceFolder, "moved.txt", "y")
        val gone = storage.file(sourceFolder, "gone.txt", "z")
        val unlucky = storage.file(sourceFolder, "unlucky.txt", "never arrives")

        engine.run(OperationRequest(type = OperationType.DELETE, sources = listOf(hidden), keepVersions = true))
        engine.run(OperationRequest(type = OperationType.MOVE, sources = listOf(moved), destination = destination, keepVersions = true))
        engine.run(OperationRequest(type = OperationType.DELETE, sources = listOf(gone)))
        storage.writeFailure = StorageError.IO
        engine.run(OperationRequest(type = OperationType.COPY, sources = listOf(unlucky), destination = destination, keepVersions = true))

        assertEquals("The failed copy's staging file is the engine's own, and must go outright",
            listOf("hidden.txt" to true, "moved.txt" to true, "gone.txt" to false, ".luna" to false), kept)
    }

    /** [storage] as a store where folders are name prefixes: one goes with its last child. */
    private fun prefixFolders(relocate: (suspend () -> Entry?)? = null) = object : StorageProvider by storage {
        override val features = storage.features + Feature.PREFIX_FOLDERS
        override suspend fun delete(ref: NodeRef) {
            val parent = storage.parentOf(ref)
            storage.delete(ref)
            if (parent != null && parent != storage.root && parent != destination && storage.list(parent).toList().flatten().isEmpty()) storage.delete(parent)
        }
        override suspend fun relocate(ref: NodeRef, parent: NodeRef, name: String): Entry? =
            if (relocate != null) relocate() else storage.relocate(ref, parent, name)
    }

    @Test fun aFolderIsWrittenInPlaceWhereFoldersArePrefixes() = runBlocking {
        val folder = storage.folder(sourceFolder, "album")
        storage.file(folder, "a.jpg", "a")
        val events = mutableListOf<OperationEvent>()

        val result = OperationEngine(ProviderRegistry(listOf(prefixFolders())))
            .run(request(folder)) { events += it }

        assertTrue(result.successful)
        val made = storage.childRef(destination, "album")!!
        assertEquals("a", storage.text(storage.childRef(made, "a.jpg")!!))
        val phases = events.filterIsInstance<OperationEvent.Journal>().map { it.phase }
        assertTrue("Publishing a staged prefix would copy every object again", JournalPhase.COMMITTING !in phases)
        assertFalse(storage.hasStages())
    }

    @Test fun aMovedTreeWhoseFoldersGoWithTheirLastChildStillFinishes() = runBlocking {
        val other = MemoryStorageProvider("elsewhere")
        val folder = storage.folder(sourceFolder, "trip")
        storage.file(storage.folder(folder, "day1"), "a.jpg", "a")
        val engine = OperationEngine(ProviderRegistry(listOf(prefixFolders(), other)))

        val result = engine.run(OperationRequest(type = OperationType.MOVE, sources = listOf(folder), destination = other.root))

        assertTrue(result.outcomes.joinToString { it.message.orEmpty() }, result.successful)
        assertFalse(storage.exists(folder))
    }

    @Test fun aRelocationThatMayBeHalfDoneIsReportedNotCopiedAnotherWay() = runBlocking {
        val file = storage.file(sourceFolder, "big.bin", "bytes")
        val halfDone = prefixFolders { throw StorageException(StorageError.IO, "Copied 1 of 2 items before failing") }

        val result = OperationEngine(ProviderRegistry(listOf(halfDone))).run(request(file, OperationType.MOVE))

        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertEquals("Copied 1 of 2 items before failing", result.outcomes.single().message)
        assertNull("Nothing was copied through the device behind the failure", storage.childRef(destination, "big.bin"))
        assertTrue(storage.exists(file))
    }

    @Test fun aMoveInsideOneStorageIsARenameRatherThanACopy() = runBlocking {
        val folder = storage.folder(sourceFolder, "photos")
        val nested = storage.folder(folder, "year")
        val photo = storage.file(nested, "photo.jpg", "image bytes")
        storage.canRelocate = true
        val result = engine.run(request(folder, OperationType.MOVE))
        assertTrue(result.successful)
        assertEquals(listOf(folder), storage.relocated)
        assertTrue("The moved tree keeps its identity; nothing was recreated", storage.exists(photo))
        assertEquals("image bytes", storage.text(photo))
        assertNotNull(storage.childRef(destination, "photos"))
        assertNull(storage.childRef(sourceFolder, "photos"))
        assertTrue("A rename copies nothing, so nothing is staged", storage.deleted.isEmpty())
        assertFalse(storage.hasStages())
    }

    @Test fun `a relocation whose reply was lost is not retried as a copy`() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "kept")
        storage.canRelocate = true
        var reads = 0
        val uncertain = object : StorageProvider by storage {
            override suspend fun relocate(ref: NodeRef, parent: NodeRef, name: String): Entry? {
                storage.relocate(ref, parent, name)
                throw StorageException(StorageError.DISCONNECTED, "Reply lost")
            }
            override suspend fun openRead(ref: NodeRef): java.io.InputStream {
                reads++
                return storage.openRead(ref)
            }
        }

        val result = OperationEngine(ProviderRegistry(listOf(uncertain))).run(request(source, OperationType.MOVE))

        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertEquals(0, reads)
        assertEquals("kept", storage.text(storage.childRef(destination, "notes.txt")!!))
        assertEquals(listOf(source), storage.relocated)
        assertFalse(storage.hasStages())
    }

    @Test fun aCollidingRenameStillAsksBeforeTouchingAnything() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "new")
        val old = storage.file(destination, "notes.txt", "old")
        storage.canRelocate = true
        val result = engine.run(request(source, OperationType.MOVE))
        assertEquals(ItemStatus.CONFLICT, result.outcomes.single().status)
        assertEquals("old", storage.text(old))
        assertEquals("new", storage.text(source))
        assertTrue("A conflict must be raised before any rename is attempted", storage.relocated.isEmpty())
    }

    @Test fun aDestinationThatCannotRenameIsWrittenUnderTheFinalNameInstead() = runBlocking {
        val source = storage.file(sourceFolder, "app-release.apk", "package bytes")
        storage.canRename = false
        val events = mutableListOf<OperationEvent>()

        val result = engine.run(request(source)) { events += it }

        assertTrue(result.outcomes.single().message, result.successful)
        assertEquals("package bytes", storage.text(storage.childRef(destination, "app-release.apk")!!))
        assertTrue("Nothing may be left behind under a staging name", storage.hasStages().not())
        val phases = events.filterIsInstance<OperationEvent.Journal>().map { it.phase }
        assertTrue("The name is recorded before the item exists, or an interruption says nothing",
            phases.contains(JournalPhase.IN_PLACE))
        assertTrue("It is still reread and verified", phases.contains(JournalPhase.VERIFIED))
        assertFalse("There is nothing to publish afterwards", phases.contains(JournalPhase.COMMITTING))
    }

    @Test fun aMoveOntoADestinationThatCannotRenameRemovesTheSourceOnlyAfterVerifying() = runBlocking {
        val source = storage.file(sourceFolder, "clip.mp4", "video bytes")
        storage.canRename = false
        storage.canRelocate = false
        val events = mutableListOf<OperationEvent>()

        val result = engine.run(request(source, OperationType.MOVE)) { events += it }

        assertTrue(result.successful)
        assertEquals("video bytes", storage.text(storage.childRef(destination, "clip.mp4")!!))
        assertFalse("The source goes, because the copy was proven first", storage.exists(source))
        val phases = events.filterIsInstance<OperationEvent.Journal>().map { it.phase }
        assertTrue(phases.indexOf(JournalPhase.VERIFIED) < phases.indexOf(JournalPhase.DELETING_SOURCE))
    }

    @Test fun onlyTheFirstItemIntoAFolderPaysForFindingOutThatItCannotRename() = runBlocking {
        val first = storage.file(sourceFolder, "one.txt", "1")
        val second = storage.file(sourceFolder, "two.txt", "2")
        val third = storage.file(sourceFolder, "three.txt", "3")
        storage.canRename = false

        val result = engine.run(OperationRequest(type = OperationType.COPY,
            sources = listOf(first, second, third), destination = destination))

        assertTrue(result.successful)
        assertEquals(listOf("1", "2", "3"), listOf("one.txt", "two.txt", "three.txt")
            .map { storage.text(storage.childRef(destination, it)!!) })
        assertEquals("One throwaway document for the run, not one for each item",
            1, storage.deleted.size)
    }

    @Test fun anOverwriteOntoADestinationThatCannotRenameIsRefusedBeforeAnythingIsCreated() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "new bytes")
        val existing = storage.file(destination, "notes.txt", "old bytes")
        storage.canRename = false
        storage.atomicReplace = false

        val result = engine.run(request(source, policy = ConflictPolicy.REPLACE))

        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertEquals("The file that is there is untouched", "old bytes", storage.text(existing))
        assertTrue("And it says which half of the swap it cannot do: ${result.outcomes.single().message}",
            result.outcomes.single().message?.contains("move notes.txt aside") == true)
        assertTrue("Nothing may be created for an overwrite that cannot finish", storage.deleted.isEmpty())
        assertFalse(storage.hasStages())
    }

    @Test fun anInterruptedInPlaceCopyIsNamedAsTheRealFileItLeftBehind() = runBlocking {
        val source = storage.file(sourceFolder, "big.iso", "bytes that never arrive")
        storage.canRename = false
        storage.writeFailure = StorageError.IO
        val events = mutableListOf<OperationEvent>()

        val failed = engine.run(request(source)) { events += it }

        assertEquals(ItemStatus.FAILED, failed.outcomes.single().status)
        assertNull("The half-written file is taken away", storage.childRef(destination, "big.iso"))
        val cleanup = events.filterIsInstance<OperationEvent.Journal>()
            .last { it.phase == JournalPhase.CLEANUP }
        assertEquals("Removing the incomplete big.iso", cleanup.message)

        // Refuse to delete the in-place file but still allow the staging probe to be deleted.
        storage.beforeDelete = { ref ->
            if (ref == storage.childRef(destination, "big.iso")) {
                throw StorageException(StorageError.PERMISSION, "Delete access revoked")
            }
        }
        val stranded = engine.run(request(source)) { events += it }
        val retained = events.filterIsInstance<OperationEvent.Journal>()
            .last { it.phase == JournalPhase.RETAINED_ARTIFACT }
        assertTrue("Said plainly, not filed as staging data: ${retained.message}",
            retained.message.contains("Incomplete big.iso retained under its real name"))
        assertEquals(1, stranded.outcomes.single().artifacts.size)
    }

    @Test fun movePublishesWholeVerifiedTreeBeforeDeletingSources() = runBlocking {
        val folder = storage.folder(sourceFolder, "photos")
        val nested = storage.folder(folder, "year")
        storage.file(nested, "photo.jpg", "image bytes")
        var observedPublish = false
        storage.beforeDelete = { ref ->
            if (ref == folder || storage.under(ref, folder)) {
                assertNotNull(storage.childRef(destination, "photos"))
                observedPublish = true
            }
        }
        val result = engine.run(request(folder, OperationType.MOVE))
        assertTrue(result.successful)
        assertTrue(observedPublish)
        assertFalse(storage.exists(folder))
        val copied = storage.childRef(storage.childRef(storage.childRef(destination, "photos")!!, "year")!!, "photo.jpg")!!
        assertEquals("image bytes", storage.text(copied))
    }

    @Test fun asksBeforeAnyCollisionMutation() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "new")
        val old = storage.file(destination, "notes.txt", "old")
        val result = engine.run(request(source))
        assertEquals(ItemStatus.CONFLICT, result.outcomes.single().status)
        assertEquals("old", storage.text(old))
        assertFalse(storage.hasStages())
    }

    @Test fun skippedMoveFolderRetainsEntireSource() = runBlocking {
        val source = storage.folder(sourceFolder, "notes")
        val child = storage.file(source, "first.txt", "important")
        storage.folder(destination, "notes")
        val result = engine.run(request(source, OperationType.MOVE, ConflictPolicy.SKIP))
        assertEquals(ItemStatus.SKIPPED, result.outcomes.single().status)
        assertTrue(storage.exists(source))
        assertEquals("important", storage.text(child))
        assertTrue(storage.deleted.isEmpty())
    }

    @Test fun keepBothPreservesExtensionAndExistingFiles() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "new")
        val old = storage.file(destination, "notes.txt", "old")
        storage.file(destination, "notes (1).txt", "older")
        assertTrue(engine.run(request(source, policy = ConflictPolicy.KEEP_BOTH)).successful)
        assertEquals("new", storage.text(storage.childRef(destination, "notes (2).txt")!!))
        assertEquals("old", storage.text(old))
    }

    @Test fun providerCaseRulesApplyToConflictsAndKeepBoth() = runBlocking {
        storage.caseInsensitive = true
        val source = storage.file(sourceFolder, "Notes.txt", "new")
        val old = storage.file(destination, "notes.txt", "old")
        storage.file(destination, "NOTES (1).TXT", "older")
        assertEquals(ItemStatus.CONFLICT, engine.run(request(source)).outcomes.single().status)
        assertTrue(engine.run(request(source, policy = ConflictPolicy.KEEP_BOTH)).successful)
        assertEquals("new", storage.text(storage.childRef(destination, "Notes (2).txt")!!))
        assertEquals("old", storage.text(old))
    }

    @Test fun atomicReplacementPublishesNewFile() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "new")
        storage.file(destination, "notes.txt", "old")
        assertTrue(engine.run(request(source, policy = ConflictPolicy.REPLACE)).successful)
        assertEquals("new", storage.text(storage.childRef(destination, "notes.txt")!!))
        assertTrue(storage.exists(source))
    }

    @Test fun failedCommitNeverDeletesExistingTargetOrMoveSource() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "new")
        val old = storage.file(destination, "notes.txt", "old")
        storage.commitFailure = StorageError.IO
        val result = engine.run(request(source, OperationType.MOVE, ConflictPolicy.REPLACE))
        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertEquals("old", storage.text(old))
        assertEquals("new", storage.text(source))
        assertFalse(storage.hasStages())
    }

    @Test fun providerStateConflictDoesNotOfferAnUnsafeAutomaticPolicyRetry() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "new")
        storage.commitFailure = StorageError.CONFLICT
        val result = engine.run(request(source, OperationType.MOVE))
        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertEquals(StorageError.CONFLICT, result.outcomes.single().error)
        assertEquals("new", storage.text(source))
        assertFalse(storage.hasStages())
    }

    @Test fun providerOfferingNoReplacementGuaranteeRejectsReplaceWithoutMutation() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "new")
        val old = storage.file(destination, "notes.txt", "old")
        storage.atomicReplace = false
        storage.recoverableReplace = false
        val result = engine.run(request(source, policy = ConflictPolicy.REPLACE))
        assertEquals(StorageError.UNSUPPORTED, result.outcomes.single().error)
        assertEquals("old", storage.text(old))
        assertFalse(storage.hasStages())
    }

    @Test fun recoverableReplacementIsEnoughToOverwriteAnExistingFile() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "new")
        storage.file(destination, "notes.txt", "old")
        storage.atomicReplace = false
        val result = engine.run(request(source, policy = ConflictPolicy.REPLACE))
        assertTrue(result.successful)
        assertEquals("new", storage.text(storage.childRef(destination, "notes.txt")!!))
        assertFalse(storage.hasStages())
    }

    @Test fun recoverableReplacementIsRefusedBeforeStagingWhenTheTargetCannotBeMovedAside() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "new")
        val old = storage.file(destination, "notes.txt", "old")
        storage.atomicReplace = false
        storage.deniedCapabilities[old] = setOf(Capability.RENAME)
        val result = engine.run(request(source, policy = ConflictPolicy.REPLACE))
        assertEquals(StorageError.UNSUPPORTED, result.outcomes.single().error)
        assertEquals("old", storage.text(old))
        assertFalse("Nothing may be staged for an overwrite that cannot complete", storage.hasStages())
    }

    @Test fun aBackupTheProviderCannotRemoveIsReportedRatherThanLeftUnrecorded() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "new")
        val old = storage.file(destination, "notes.txt", "old")
        storage.atomicReplace = false
        storage.retainReplacedTarget = true
        val events = mutableListOf<OperationEvent>()
        val result = engine.run(request(source, policy = ConflictPolicy.REPLACE)) { events += it }
        val outcome = result.outcomes.single()
        assertEquals(ItemStatus.SUCCESS, outcome.status)
        assertEquals("new", storage.text(storage.childRef(destination, "notes.txt")!!))
        assertEquals("old", storage.text(old))
        assertTrue("The surviving copy must be reported as an artifact", old in outcome.artifacts)
        assertTrue("and journalled for review", events.filterIsInstance<OperationEvent.Journal>()
            .any { it.phase == JournalPhase.RETAINED_ARTIFACT && it.artifact == old })
    }

    @Test fun aBackupRetainedByAFailedReplacementIsIncludedInItsOutcome() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "new")
        val old = storage.file(destination, "notes.txt", "old")
        val retaining = object : StorageProvider by storage {
            override suspend fun commit(staged: NodeRef, parent: NodeRef, name: String,
                replace: Entry?, onRetained: RetainedObjects?): Entry {
                storage.rename(old, ".luna-replaced-notes.txt")
                onRetained?.invoke(old, "Previous file retained")
                throw StorageException(StorageError.DISCONNECTED, "Connection lost while publishing")
            }
        }
        val result = OperationEngine(ProviderRegistry(listOf(retaining)))
            .run(request(source, OperationType.MOVE, ConflictPolicy.REPLACE))

        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertTrue(old in result.outcomes.single().artifacts)
        assertEquals("old", storage.text(old))
        assertEquals("new", storage.text(source))
    }

    @Test fun aBackupRetainedByAFailedRenameReplacementIsIncludedInItsOutcome() = runBlocking {
        val source = storage.file(destination, "new.txt", "new")
        val old = storage.file(destination, "notes.txt", "old")
        val retaining = object : StorageProvider by storage {
            override suspend fun commit(staged: NodeRef, parent: NodeRef, name: String,
                replace: Entry?, onRetained: RetainedObjects?): Entry {
                storage.rename(old, ".luna-replaced-notes.txt")
                onRetained?.invoke(old, "Previous file retained")
                throw StorageException(StorageError.DISCONNECTED, "Connection lost while publishing")
            }
        }
        val result = OperationEngine(ProviderRegistry(listOf(retaining)))
            .run(request(source, OperationType.RENAME, ConflictPolicy.REPLACE).copy(name = "notes.txt"))

        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertTrue(old in result.outcomes.single().artifacts)
        assertEquals("old", storage.text(old))
        assertEquals("new", storage.text(source))
        assertEquals(setOf(source, old), storage.list(destination).toList().flatten().map { it.ref }.toSet())
    }

    @Test fun aFailedDirectoryRenameReportsBackupsRetainedByItsMergedChildren() = runBlocking {
        val source = storage.folder(destination, "before")
        val fresh = storage.file(source, "notes.txt", "new")
        val target = storage.folder(destination, "after")
        val old = storage.file(target, "notes.txt", "old")
        val retaining = object : StorageProvider by storage {
            override suspend fun commit(staged: NodeRef, parent: NodeRef, name: String,
                replace: Entry?, onRetained: RetainedObjects?): Entry {
                storage.rename(old, ".luna-replaced-notes.txt")
                onRetained?.invoke(old, "Previous file retained")
                throw StorageException(StorageError.DISCONNECTED, "Connection lost while publishing")
            }
        }
        val result = OperationEngine(ProviderRegistry(listOf(retaining)))
            .run(request(source, OperationType.RENAME, ConflictPolicy.MERGE).copy(name = "after"))

        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertTrue(old in result.outcomes.single().artifacts)
        assertEquals("old", storage.text(old))
        assertEquals("new", storage.text(fresh))
        assertEquals(listOf(old), storage.list(target).toList().flatten().map { it.ref })
    }

    @Test fun folderCollisionMergesWithoutDisturbingUnrelatedChildren() = runBlocking {
        val source = storage.folder(sourceFolder, "notes")
        storage.file(source, "added.txt", "added")
        storage.file(source, "shared.txt", "fresh")
        val old = storage.folder(destination, "notes")
        val untouched = storage.file(old, "keep.txt", "keep")
        val overwritten = storage.file(old, "shared.txt", "stale")
        val result = engine.run(request(source, OperationType.MOVE, ConflictPolicy.MERGE))
        assertTrue(result.outcomes.first { it.source == source }.status == ItemStatus.SUCCESS)
        assertEquals("keep", storage.text(untouched))
        assertEquals("added", storage.text(storage.childRef(old, "added.txt")!!))
        assertEquals("fresh", storage.text(storage.childRef(old, "shared.txt")!!))
        assertFalse("The replaced document is gone, not merely renamed", storage.exists(overwritten))
        assertFalse("A completed merge-move leaves no source folder behind", storage.exists(source))
        assertFalse(storage.hasStages())
    }

    @Test fun aMergingMoveThatCannotEmptyItsSourceSaysSoInsteadOfReportingSuccess() = runBlocking {
        val source = storage.folder(sourceFolder, "notes")
        val moved = storage.file(source, "moved.txt", "moved")
        val old = storage.folder(destination, "notes")
        // A new file appears in the source folder as its last child is being removed.
        storage.beforeDelete = { ref -> if (ref == moved) storage.file(source, "late.txt", "late") }
        val result = engine.run(request(source, OperationType.MOVE, ConflictPolicy.MERGE))
        val outcome = result.outcomes.single()
        assertEquals(ItemStatus.FAILED, outcome.status)
        assertTrue("The surviving source must be named", outcome.message!!.contains("notes"))
        assertTrue("The source folder is kept, not silently removed", storage.exists(source))
        assertEquals("The destination keeps everything that did move", "moved", storage.text(storage.childRef(old, "moved.txt")!!))
        assertFalse(storage.hasStages())
    }

    @Test fun mergingMovePreflightsTheWholeTreeBeforeMovingAnything() = runBlocking {
        val source = storage.folder(sourceFolder, "notes")
        val movable = storage.file(source, "movable.txt", "movable")
        val stuck = storage.file(source, "stuck.txt", "stuck")
        val old = storage.folder(destination, "notes")
        storage.deniedCapabilities[stuck] = setOf(Capability.DELETE)
        val result = engine.run(request(source, OperationType.MOVE, ConflictPolicy.MERGE))
        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertEquals(StorageError.UNSUPPORTED, result.outcomes.single().error)
        assertTrue(storage.exists(stuck))
        assertTrue(storage.exists(movable))
        assertNull("A rejected preflight moves nothing at all", storage.childRef(old, "movable.txt"))
        assertFalse(storage.hasStages())
    }

    @Test fun mergingCopyReportsTheFailedChildAndKeepsWhatSucceeded() = runBlocking {
        val source = storage.folder(sourceFolder, "notes")
        storage.file(source, "readable.txt", "readable")
        val blocked = storage.file(source, "blocked.txt", "blocked")
        val old = storage.folder(destination, "notes")
        storage.deniedCapabilities[blocked] = setOf(Capability.READ)
        val result = engine.run(request(source, policy = ConflictPolicy.MERGE))
        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertEquals("readable", storage.text(storage.childRef(old, "readable.txt")!!))
        assertNull(storage.childRef(old, "blocked.txt"))
        assertEquals("blocked", storage.text(blocked))
        assertFalse(storage.hasStages())
    }

    @Test fun insufficientSpaceIsReportedBeforeStaging() = runBlocking {
        val source = storage.file(sourceFolder, "large.bin", "123456789")
        storage.freeBytes = 2
        val result = engine.run(request(source, OperationType.MOVE))
        assertEquals(StorageError.NO_SPACE, result.outcomes.single().error)
        assertTrue(storage.exists(source))
        assertNull(storage.childRef(destination, "large.bin"))
        assertFalse(storage.hasStages())
    }

    @Test fun diskFillingMidWriteCleansPartialDataAndRetainsSource() = runBlocking {
        val source = storage.file(sourceFolder, "large.bin", "123456789")
        storage.writeFailure = StorageError.NO_SPACE
        val result = engine.run(request(source, OperationType.MOVE))
        assertEquals(StorageError.NO_SPACE, result.outcomes.single().error)
        assertEquals("123456789", storage.text(source))
        assertNull(storage.childRef(destination, "large.bin"))
        assertFalse(storage.hasStages())
    }

    @Test fun rawStreamNoSpaceFailureIsClassifiedEvenOutsideProviderMethod() = runBlocking {
        val source = storage.file(sourceFolder, "large.bin", "123456789")
        storage.rawWriteFailure = java.io.IOException("write failed: ENOSPC (No space left on device)")
        val result = engine.run(request(source, OperationType.MOVE))
        assertEquals(StorageError.NO_SPACE, result.outcomes.single().error)
        assertTrue(storage.exists(source))
        assertFalse(storage.hasStages())
    }

    @Test fun verificationDetectsCorruptionAndNeverPublishesIt() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "data")
        storage.corruptStageReads = true
        val result = engine.run(request(source, OperationType.MOVE))
        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertTrue(result.outcomes.single().message!!.contains("Verification failed"))
        assertTrue(storage.exists(source))
        assertNull(storage.childRef(destination, "notes.txt"))
        assertFalse(storage.hasStages())
    }

    @Test fun cancellationDuringTransferCleansStageAndEmitsCancelledOutcome() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "data".repeat(100_000))
        val events = mutableListOf<OperationEvent>()
        try {
            engine.run(request(source, OperationType.MOVE)) { event ->
                events += event
                if (event is OperationEvent.Progress) throw CancellationException("User cancelled")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(storage.exists(source))
        assertFalse(storage.hasStages())
        assertNull(storage.childRef(destination, "notes.txt"))
        assertEquals(ItemStatus.CANCELLED, events.filterIsInstance<OperationEvent.ItemFinished>().last().outcome.status)
    }

    @Test fun cancellationAfterPublishRetainsBothCopiesAndReportsDestination() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "data")
        val outcomes = mutableListOf<ItemOutcome>()
        try {
            engine.run(request(source, OperationType.MOVE)) { event ->
                if (event is OperationEvent.ItemFinished) outcomes += event.outcome
                if (event is OperationEvent.Journal && event.phase == JournalPhase.PUBLISHED) throw CancellationException("Cancel")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(storage.exists(source))
        assertEquals("data", storage.text(storage.childRef(destination, "notes.txt")!!))
        assertNotNull(outcomes.single().destination)
    }

    @Test fun realJobCancellationStillCleansStageInNonCancellableContext() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "data".repeat(100_000))
        val enteredTransfer = CompletableDeferred<Unit>()
        val outcomes = mutableListOf<ItemOutcome>()
        val job = launch {
            engine.run(request(source, OperationType.MOVE)) { event ->
                if (event is OperationEvent.ItemFinished) outcomes += event.outcome
                if (event is OperationEvent.Progress) {
                    enteredTransfer.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        enteredTransfer.await()
        job.cancelAndJoin()
        assertTrue(storage.exists(source))
        assertFalse(storage.hasStages())
        assertEquals(ItemStatus.CANCELLED, outcomes.single().status)
    }

    @Test fun failedCleanupReturnsRecoverableArtifact() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "data")
        storage.writeFailure = StorageError.DISCONNECTED
        storage.failStageCleanup = true
        val result = engine.run(request(source, OperationType.MOVE))
        val outcome = result.outcomes.single()
        assertEquals(StorageError.DISCONNECTED, outcome.error)
        assertEquals(1, outcome.artifacts.size)
        assertTrue(storage.exists(outcome.artifacts.single()))
        assertTrue(storage.exists(source))
    }

    @Test fun journalFailureBeforeCommitPreservesTargetAndSource() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "new")
        val old = storage.file(destination, "notes.txt", "old")
        val result = engine.run(request(source, OperationType.MOVE, ConflictPolicy.REPLACE)) { event ->
            if (event is OperationEvent.Journal && event.phase == JournalPhase.COMMITTING) error("Journal unavailable")
        }
        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertEquals("old", storage.text(old))
        assertTrue(storage.exists(source))
        assertFalse(storage.hasStages())
    }

    @Test fun sourceChangedAfterPublishIsRetained() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "original")
        storage.afterCommit = { storage.setText(source, "new edit") }
        val result = engine.run(request(source, OperationType.MOVE))
        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertEquals("new edit", storage.text(source))
        assertEquals("original", storage.text(storage.childRef(destination, "notes.txt")!!))
        assertNotNull(result.outcomes.single().destination)
    }

    @Test fun moveDetectsContentChangeEvenWithUnknownSizeAndTimestamp() = runBlocking {
        val source = storage.file(sourceFolder, "notes.txt", "original")
        storage.unknownMetadata = true
        storage.afterCommit = { storage.setText(source, "new edit") }
        val result = engine.run(request(source, OperationType.MOVE))
        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertEquals("new edit", storage.text(source))
        assertEquals("original", storage.text(storage.childRef(destination, "notes.txt")!!))
    }

    @Test fun fileAddedToSourceFolderAfterPublishPreventsSourceDeletion() = runBlocking {
        val source = storage.folder(sourceFolder, "notes")
        val child = storage.file(source, "first.txt", "one")
        storage.afterCommit = { storage.file(source, "new.txt", "new") }
        val result = engine.run(request(source, OperationType.MOVE))
        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertTrue(storage.exists(child))
        assertNotNull(storage.childRef(source, "new.txt"))
        assertNotNull(result.outcomes.single().destination)
    }

    @Test fun moveWithUndeletableDescendantDoesNotStartCopy() = runBlocking {
        val source = storage.folder(sourceFolder, "notes")
        val child = storage.file(source, "locked.txt", "one")
        storage.deniedCapabilities[child] = setOf(Capability.DELETE)
        val result = engine.run(request(source, OperationType.MOVE))
        assertEquals(StorageError.UNSUPPORTED, result.outcomes.single().error)
        assertTrue(storage.exists(child))
        assertNull(storage.childRef(destination, "notes"))
        assertFalse(storage.hasStages())
    }

    @Test fun recursiveDeleteReportsPartialResultsWithoutDeletingNonemptyParent() = runBlocking {
        val source = storage.folder(sourceFolder, "notes")
        val first = storage.file(source, "first.txt", "one")
        val second = storage.file(source, "second.txt", "two")
        storage.deleteFailures += second
        val events = mutableListOf<OperationEvent>()
        val result = engine.run(OperationRequest(type = OperationType.DELETE, sources = listOf(source))) { events += it }
        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertFalse(storage.exists(first))
        assertTrue(storage.exists(second))
        assertTrue(storage.exists(source))
        val children = events.filterIsInstance<OperationEvent.ItemFinished>().map { it.outcome }
        assertTrue(children.any { it.source == first && it.status == ItemStatus.SUCCESS })
        assertTrue(children.any { it.source == second && it.status == ItemStatus.FAILED })
    }

    @Test fun moveSourceDeletionFailureRetainsCompleteDestinationAndReportsRemovedChildren() = runBlocking {
        val source = storage.folder(sourceFolder, "notes")
        val first = storage.file(source, "first.txt", "one")
        val second = storage.file(source, "second.txt", "two")
        // Source deletion walks the manifest in reverse, so second.txt goes before first.txt is tried.
        storage.deleteFailures += first
        val events = mutableListOf<OperationEvent>()
        val result = engine.run(request(source, OperationType.MOVE)) { events += it }
        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertTrue(storage.exists(first))
        assertFalse(storage.exists(second))
        assertTrue(storage.exists(source))
        val published = result.outcomes.single().destination!!
        assertEquals("one", storage.text(storage.childRef(published, "first.txt")!!))
        assertEquals("two", storage.text(storage.childRef(published, "second.txt")!!))
        assertTrue(events.filterIsInstance<OperationEvent.ItemFinished>()
            .any { it.outcome.source == second && it.outcome.status == ItemStatus.SUCCESS })
    }

    @Test fun folderPopulatedDuringDeleteFailsWithoutOfferingNamePolicyRetry() = runBlocking {
        val source = storage.folder(sourceFolder, "notes")
        storage.beforeDelete = { ref -> if (ref == source) storage.file(source, "new.txt", "new content") }
        val result = engine.run(OperationRequest(type = OperationType.DELETE, sources = listOf(source)))
        assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
        assertEquals(StorageError.CONFLICT, result.outcomes.single().error)
        assertTrue(storage.exists(source))
        assertEquals("new content", storage.text(storage.childRef(source, "new.txt")!!))
    }

    @Test fun cannotCopyFolderIntoItsOwnDescendant() = runBlocking {
        val source = storage.folder(sourceFolder, "notes")
        val child = storage.folder(source, "nested")
        val result = engine.run(request(source).copy(destination = child))
        assertEquals(StorageError.UNSUPPORTED, result.outcomes.single().error)
        assertFalse(storage.hasStages())
    }

    @Test fun disconnectedProviderIsExplicitFailure() = runBlocking {
        val result = engine.run(request(NodeRef("removed", "opaque")))
        assertEquals(StorageError.DISCONNECTED, result.outcomes.single().error)
    }

    @Test fun oneItemFailureDoesNotStopIndependentItems() = runBlocking {
        val first = storage.file(sourceFolder, "first.txt", "one")
        val second = storage.file(sourceFolder, "second.txt", "two")
        storage.file(destination, "first.txt", "collision")
        val result = engine.run(request(first).copy(sources = listOf(first, second)))
        assertEquals(listOf(ItemStatus.CONFLICT, ItemStatus.SUCCESS), result.outcomes.map { it.status })
        assertEquals("two", storage.text(storage.childRef(destination, "second.txt")!!))
    }

    @Test fun renameRejectsCollisionThenSupportsKeepBoth() = runBlocking {
        val source = storage.file(sourceFolder, "before.txt", "data")
        storage.file(sourceFolder, "after.txt", "old")
        val rename = OperationRequest(type = OperationType.RENAME, sources = listOf(source), destination = sourceFolder, name = "after.txt")
        assertEquals(ItemStatus.CONFLICT, engine.run(rename).outcomes.single().status)
        assertTrue(engine.run(rename.copy(conflictPolicy = ConflictPolicy.KEEP_BOTH)).successful)
        assertNotNull(storage.childRef(sourceFolder, "after (1).txt"))
    }

    @Test fun createFolderHandlesInvalidNamesAndKeepBoth() = runBlocking {
        val create = OperationRequest(type = OperationType.CREATE_FOLDER, destination = destination, name = "../bad")
        assertEquals(StorageError.INVALID_NAME, engine.run(create).outcomes.single().error)
        storage.folder(destination, "projects")
        assertTrue(engine.run(create.copy(name = "projects", conflictPolicy = ConflictPolicy.KEEP_BOTH)).successful)
        assertNotNull(storage.childRef(destination, "projects (1)"))
    }

    @Test fun aFolderWhoseListingComesBackShortIsStillEmptiedCompletely() = runBlocking {
        val folder = storage.folder(sourceFolder, "META-INF")
        val files = (1..10).map { storage.file(folder, "entry$it.version", "x") }
        storage.listCap[folder] = 4

        val result = engine.run(OperationRequest(type = OperationType.DELETE, sources = listOf(folder))) {}

        assertTrue("The folder must actually be gone", result.successful)
        assertFalse(storage.exists(folder))
        files.forEach { assertFalse("Every child must be gone, not just the listed ones", storage.exists(it)) }
    }

    @Test fun aFolderThatKeepsListingItemsGivesUpWithSomethingWorthReading() = runBlocking {
        val folder = storage.folder(sourceFolder, "busy")
        storage.file(folder, "kept.txt", "x")
        // Each deleted child is replaced by a new one, so the folder never empties.
        var replaced = 0
        storage.beforeDelete = { ref ->
            if (storage.exists(ref) && ref != folder && replaced < 500) {
                replaced++
                storage.file(folder, "replacement$replaced.txt", "x")
            }
        }

        val result = engine.run(OperationRequest(type = OperationType.DELETE, sources = listOf(folder))) {}
        storage.beforeDelete = null

        assertFalse(result.successful)
        val message = result.outcomes.mapNotNull { it.message }.joinToString(" ")
        assertTrue("The report should say why it stopped, not just 'not empty': $message",
            message.contains("passes"))
    }

    @Test fun emptyingAFolderListsItOnceMoreThanItHasLevels() = runBlocking {
        val folder = storage.folder(sourceFolder, "plain")
        (1..6).forEach { storage.file(folder, "file$it.txt", "x") }
        storage.listings = 0

        engine.run(OperationRequest(type = OperationType.DELETE, sources = listOf(folder))) {}

        // One capability walk, one deletion listing and one emptiness check.
        assertTrue("Listed $storage.listings times, which is more than emptying one folder needs",
            storage.listings <= 4)
    }
}
