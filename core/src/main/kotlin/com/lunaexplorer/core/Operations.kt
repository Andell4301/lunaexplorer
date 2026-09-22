package com.lunaexplorer.core

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class OperationType { COPY, MOVE, DELETE, RENAME, CREATE_FOLDER, CREATE_ARCHIVE, EXTRACT_ARCHIVE }
enum class ConflictPolicy { ASK, REPLACE, SKIP, KEEP_BOTH, MERGE }
enum class ItemStatus { SUCCESS, SKIPPED, CONFLICT, FAILED, CANCELLED }

@Serializable
data class OperationRequest(
    val id: String = UUID.randomUUID().toString(),
    val type: OperationType,
    val sources: List<NodeRef> = emptyList(),
    val destination: NodeRef? = null,
    val name: String? = null,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
    val archive: ArchiveSpec? = null,
    /** On a [Feature.VERSIONED] provider, hide what the user asked removed (a delete, a move's or rename's source) instead of removing every version. */
    val keepVersions: Boolean = false,
)

data class ItemOutcome(
    val source: NodeRef?,
    val destination: NodeRef? = null,
    val status: ItemStatus,
    val message: String? = null,
    val error: StorageError? = null,
    val artifacts: List<NodeRef> = emptyList(),
    /** Set only on a CONFLICT outcome: whether the colliding source is a directory. */
    val sourceDirectory: Boolean = false,
    val conflictName: String? = null,
)

data class OperationResult(val requestId: String, val outcomes: List<ItemOutcome>) {
    val successful: Boolean get() = outcomes.isNotEmpty() && outcomes.all { it.status == ItemStatus.SUCCESS }
}

enum class JournalPhase { STAGING, IN_PLACE, VERIFIED, COMMITTING, PUBLISHED, DELETING_SOURCE, CLEANUP, RETAINED_ARTIFACT }

/** A [Journal] event must be durably recorded before the callback returns; the engine acts only after it does. */
sealed interface OperationEvent {
    val requestId: String
    data class Started(override val requestId: String, val totalItems: Int) : OperationEvent
    data class Progress(
        override val requestId: String,
        val source: NodeRef?,
        val currentName: String,
        val bytesCopied: Long,
        val totalBytes: Long?,
        val completedItems: Int,
        val totalItems: Int,
    ) : OperationEvent
    data class Journal(
        override val requestId: String,
        val phase: JournalPhase,
        val source: NodeRef?,
        val artifact: NodeRef? = null,
        val destination: NodeRef? = null,
        val message: String = "",
    ) : OperationEvent
    data class ItemFinished(override val requestId: String, val outcome: ItemOutcome) : OperationEvent
    data class Finished(val result: OperationResult) : OperationEvent {
        override val requestId: String get() = result.requestId
    }
}

/** Cap on re-listing a folder during delete, so a folder that keeps being written to cannot loop forever. */
private const val DELETE_LIST_PASSES = 64

// Publish only verified copies; a move deletes its source after publication.
class OperationEngine(
    private val registry: ProviderRegistry,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val archives: ArchiveEngine = ArchiveEngine(registry, dispatcher),
) {
    suspend fun run(
        request: OperationRequest,
        /** Archive password; deliberately not part of the serializable [request]. */
        password: String = "",
        onEvent: suspend (OperationEvent) -> Unit = {},
    ): OperationResult = withContext(dispatcher) {
        val sources = when (request.type) {
            OperationType.CREATE_FOLDER -> listOf(null)
            // All sources go into one archive, so the run has a single item.
            OperationType.CREATE_ARCHIVE -> {
                if (request.sources.isEmpty()) throw IllegalArgumentException("Select at least one item")
                listOf(null)
            }
            else -> request.sources.distinct()
        }
        if (sources.isEmpty()) throw IllegalArgumentException("Select at least one item")
        if (request.type == OperationType.RENAME && sources.size != 1) {
            throw IllegalArgumentException("Rename accepts exactly one item")
        }
        if (request.type == OperationType.EXTRACT_ARCHIVE && sources.size != 1) {
            throw IllegalArgumentException("Extract accepts exactly one archive")
        }
        onEvent(OperationEvent.Started(request.id, sources.size))
        // DeferredWrites providers read this context element to group the run's writes until flush.
        val run = StorageRun()
        // Source deletions for moves into a DeferredWrites provider; they run only after the flush.
        val removeAfterFlush = mutableListOf<suspend () -> Unit>()
        var applied = false
        val outcomes = mutableListOf<ItemOutcome>()
        // (folder, isDirectory) pairs found unable to rename what they create; later items skip the staging probe.
        val inPlaceDestinations = mutableSetOf<Pair<NodeRef, Boolean>>()
        try {
            withContext(run) {
                for (source in sources) {
                    currentCoroutineContext().ensureActive()
                    val session = Session(request, source, outcomes.size, sources.size, onEvent,
                        inPlaceDestinations = inPlaceDestinations, password = password,
                        removeAfterFlush = removeAfterFlush)
                    val outcome = try {
                        when (request.type) {
                            OperationType.COPY, OperationType.MOVE -> session.transfer(requireNotNull(source))
                            OperationType.DELETE -> session.delete(requireNotNull(source))
                            OperationType.RENAME -> session.rename(requireNotNull(source))
                            OperationType.CREATE_FOLDER -> session.createFolder()
                            OperationType.CREATE_ARCHIVE -> session.createArchive()
                            OperationType.EXTRACT_ARCHIVE -> session.extractArchive(requireNotNull(source))
                        }
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) {
                            val artifacts = session.cleanup()
                            onEvent(OperationEvent.ItemFinished(request.id, ItemOutcome(source, session.published,
                                ItemStatus.CANCELLED, "Cancelled. Completed changes are retained; review any published destination.",
                                artifacts = artifacts)))
                        }
                        throw cancelled
                    } catch (failure: Exception) {
                        val artifacts = withContext(NonCancellable) { session.cleanup() }
                        val reason = classify(failure)
                        val conflict = failure as? NameConflictException
                        ItemOutcome(source, session.published, if (conflict != null) ItemStatus.CONFLICT else ItemStatus.FAILED,
                            failure.message ?: "Storage operation failed", reason, artifacts,
                            sourceDirectory = conflict?.directory == true, conflictName = conflict?.name)
                    }
                    outcomes += outcome
                    onEvent(OperationEvent.ItemFinished(request.id, outcome))
                }
            }
            // Deferred writes are applied once per run. A flush failure must propagate: items have
            // already reported success on the strength of it.
            registry.all.filterIsInstance<DeferredWrites>().forEach { it.flush(run) }
            // Sources of deferred moves go only after the flush, so a failed flush cannot lose the file.
            removeAfterFlush.forEach { it() }
            applied = true
            OperationResult(request.id, outcomes.toList()).also { onEvent(OperationEvent.Finished(it)) }
        } finally {
            // On failure or cancellation, drop the run's unapplied deferred writes.
            if (!applied) withContext(NonCancellable) {
                registry.all.filterIsInstance<DeferredWrites>().forEach { runCatching { it.discardPending(run) } }
            }
        }
    }

    private inner class Session(
        val request: OperationRequest,
        val source: NodeRef?,
        val completedItems: Int,
        val totalItems: Int,
        val emit: suspend (OperationEvent) -> Unit,
        val depth: Int = 0,
        /** Destination listing shared by a merge's children, instead of one lookup per child. */
        val destinationChildren: List<Entry>? = null,
        val inPlaceDestinations: MutableSet<Pair<NodeRef, Boolean>> = mutableSetOf(),
        val removeAfterFlush: MutableList<suspend () -> Unit> = mutableListOf(),
        val password: String = "",
    ) {
        private var staged: NodeRef? = null
        /** The archive engine's staging file. It deletes it itself; tracked only to report a leftover. */
        private var archiveStaging: NodeRef? = null
        /** Set when [staged] was created directly under its final name because the destination cannot rename. */
        private var inPlaceName: String? = null
        var published: NodeRef? = null
            private set
        private var bytesCopied = 0L
        private var totalBytes: Long? = null
        private var lastProgressAt = 0L
        private val manifest = mutableListOf<Snapshot>()
        private val visited = mutableSetOf<NodeRef>()
        private val retainedByCommit = linkedSetOf<NodeRef>()
        private val nestedArtifacts = mutableListOf<NodeRef>()

        private suspend fun journal(phase: JournalPhase, artifact: NodeRef? = staged, message: String = "") {
            emit(OperationEvent.Journal(request.id, phase, source, artifact, request.destination, message))
        }

        /** Runs a removal the user asked for. Staging data and probes are removed outside it, so they never linger hidden. */
        private suspend fun <T> removing(block: suspend () -> T): T =
            if (request.keepVersions) withContext(KeepVersions()) { block() } else block()

        suspend fun transfer(ref: NodeRef): ItemOutcome {
            val origin = registry.provider(ref)
            val original = origin.stat(ref)
            val parent = request.destination ?: throw IllegalArgumentException("A destination folder is required")
            val target = registry.provider(parent)
            val targetFolder = target.stat(parent)
            requireDirectory(targetFolder)
            requireCapability(targetFolder, Capability.CREATE)
            if (original.directory && parent.provider == ref.provider && origin.isDescendant(parent, ref)) {
                throw StorageException(StorageError.UNSUPPORTED, "A folder cannot be copied or moved into itself")
            }
            val wantedName = request.name ?: original.name
            validateName(wantedName)
            val existing = if (destinationChildren != null) {
                destinationChildren.firstOrNull { target.namesEqual(it.name, wantedName) }
            } else {
                target.child(parent, wantedName)
            }
            if (existing != null && existing.directory && original.directory && mergesDirectories()) {
                // Check source deletability before a merge move can leave the tree partially moved.
                if (request.type == OperationType.MOVE) requireDeletableTree(origin, original, mutableSetOf())
                return mergeInto(origin, original, target, existing)
            }
            val finalName = resolveName(target, parent, wantedName, existing, original.directory) ?: return skipped(ref)
            val replace = if (existing != null && finalName == wantedName) existing else null
            if (replace?.ref == ref) throw StorageException(StorageError.UNSUPPORTED, "The source and destination are the same item")
            if (replace != null) requireSafeReplacement(targetFolder, replace, original.directory)

            // Try a provider-side relocation first. relocate() must not replace, so only without a target.
            if (request.type == OperationType.MOVE && replace == null && parent.provider == ref.provider) {
                val relocated = try {
                    removing { origin.relocate(ref, parent, finalName) }
                } catch (failed: StorageException) {
                    // Only an unsupported operation establishes that no mutation took place.
                    if (failed.reason != StorageError.UNSUPPORTED || Feature.PREFIX_FOLDERS in origin.features) throw failed else null
                }
                if (relocated != null) {
                    published = relocated.ref
                    journal(JournalPhase.PUBLISHED, relocated.ref, "Moved $finalName within its storage")
                    progress(original.name, force = true)
                    return ItemOutcome(ref, relocated.ref, ItemStatus.SUCCESS, "Moved ${original.name}")
                }
            }

            // A copying move must be able to delete the whole source tree afterwards; check before copying.
            if (request.type == OperationType.MOVE) requireDeletableTree(origin, original, mutableSetOf())
            totalBytes = if (original.directory) null else original.size
            if (!original.directory) checkSpace(target, parent, original.size)
            val created = createDestination(target, parent, targetFolder, original, finalName, replace)
            copyTree(origin, original, target, created, 0)
            validateSourceTree(origin, verifyContent = false)
            journal(JournalPhase.VERIFIED, message = "All copied bytes were reread and SHA-256 verified")
            currentCoroutineContext().ensureActive()
            val committed = if (inPlaceName != null) target.stat(created.ref) else {
                journal(JournalPhase.COMMITTING, message = "Publishing as $finalName")
                target.commit(created.ref, parent, finalName, replace) { retained, message ->
                    retainedByCommit += retained
                    journal(JournalPhase.RETAINED_ARTIFACT, retained, message)
                }
            }
            published = committed.ref
            // Published: cleanup must not delete it if a later step fails.
            staged = null
            journal(JournalPhase.PUBLISHED, committed.ref,
                if (inPlaceName != null) "$finalName is complete, having been written in place"
                else "Published $finalName")
            if (request.type == OperationType.MOVE) {
                // Detect edits and newly added children before starting irreversible source cleanup.
                validateSourceTree(origin, verifyContent = true)
                journal(JournalPhase.DELETING_SOURCE, committed.ref, "Verified destination exists; removing source tree")
                // A DeferredWrites destination is not written until the run flushes; deleting the
                // source now would lose the file if that flush failed.
                if (target is DeferredWrites) removeAfterFlush += { removing { deleteMovedTree(origin) } }
                else removing { deleteMovedTree(origin) }
            }
            progress(original.name, force = true)
            return ItemOutcome(ref, committed.ref, ItemStatus.SUCCESS,
                "${if (request.type == OperationType.MOVE) "Moved" else "Copied"} ${original.name}",
                artifacts = retainedByCommit.toList())
        }

        // Probe the created item's rename support; MTP folders can create children that cannot be renamed.
        private suspend fun createDestination(target: StorageProvider, parent: NodeRef, targetFolder: Entry,
            original: Entry, finalName: String, replace: Entry?): Entry {
            val known = parent to original.directory
            // A prefix folder cannot be staged: publishing it would copy every object a second time, and each arrives whole anyway.
            if (original.directory && Feature.PREFIX_FOLDERS in target.features) inPlaceDestinations += known
            if (known !in inPlaceDestinations) {
                val extension = if (original.directory) "" else original.name.substringAfterLast('.', "")
                    .takeIf { it.isNotEmpty() && it.length <= 32 }?.let { ".$it" }.orEmpty()
                val stageName = ".luna-${request.id.take(12)}-${UUID.randomUUID()}.partial$extension"
                // Journal the name before create so an interruption between create and stat is traceable.
                journal(JournalPhase.STAGING, null, "Creating $stageName in ${targetFolder.name}")
                val stage = target.create(parent, stageName, original.directory, original.mimeType)
                staged = stage.ref
                if (Capability.RENAME in stage.capabilities) {
                    journal(JournalPhase.STAGING, stage.ref, "Staging ${original.name}")
                    return stage
                }
                journal(JournalPhase.CLEANUP, stage.ref,
                    "${targetFolder.name} cannot rename what it creates, so nothing can be staged in it")
                // Delete the empty probe. [staged] is still set, so cleanup reports it if this fails.
                target.delete(stage.ref)
                staged = null
                inPlaceDestinations += known
            }
            // Without rename, replacing would mean deleting the existing file first.
            if (replace != null) throw StorageException(StorageError.UNSUPPORTED,
                "${targetFolder.name} cannot overwrite ${replace.name}; choose keep both or skip")
            journal(JournalPhase.IN_PLACE, null,
                "Creating $finalName in ${targetFolder.name}; it cannot be staged, so it is incomplete until verified")
            val created = target.create(parent, finalName, original.directory, original.mimeType)
            staged = created.ref
            inPlaceName = finalName
            journal(JournalPhase.IN_PLACE, created.ref, "Writing ${original.name} straight into $finalName")
            return created
        }

        private fun mergesDirectories() =
            request.conflictPolicy == ConflictPolicy.MERGE || request.conflictPolicy == ConflictPolicy.REPLACE

        // Merge children independently; remove the source folder only after every child moved.
        private suspend fun mergeInto(origin: StorageProvider, original: Entry, target: StorageProvider, destination: Entry): ItemOutcome {
            if (depth > 128) throw StorageException(StorageError.UNSUPPORTED, "Folder tree is too deeply nested to merge")
            requireCapability(original, Capability.LIST)
            requireDirectory(destination)
            requireCapability(destination, Capability.CREATE)
            // Ref equality is not enough: the same directory can be reached under two roots or through a link.
            if (destination.ref == original.ref ||
                (origin === target && origin.isDescendant(destination.ref, original.ref))) {
                throw StorageException(StorageError.UNSUPPORTED, "A folder cannot be merged into itself")
            }
            journal(JournalPhase.STAGING, null, "Merging ${original.name} into the existing folder ${destination.name}")
            val children = mutableListOf<Entry>()
            origin.list(original.ref, complete = true).collect { children.addAll(it) }
            val present = mutableListOf<Entry>()
            target.list(destination.ref, complete = true).collect { present.addAll(it) }
            var succeeded = 0
            var skippedChildren = 0
            val failures = mutableListOf<String>()
            for (child in children) {
                currentCoroutineContext().ensureActive()
                val nested = Session(
                    request.copy(destination = destination.ref, name = null, conflictPolicy = ConflictPolicy.MERGE),
                    child.ref, completedItems, totalItems, emit, depth + 1, present, inPlaceDestinations,
                    removeAfterFlush = removeAfterFlush,
                )
                val outcome = try {
                    nested.transfer(child.ref)
                } catch (cancelled: CancellationException) {
                    nestedArtifacts += withContext(NonCancellable) { nested.cleanup() }
                    throw cancelled
                } catch (failure: Exception) {
                    val artifacts = withContext(NonCancellable) { nested.cleanup() }
                    nestedArtifacts += artifacts
                    ItemOutcome(child.ref, nested.published, ItemStatus.FAILED,
                        failure.message ?: "Merge failed", classify(failure), artifacts)
                }
                emit(OperationEvent.ItemFinished(request.id, outcome))
                when (outcome.status) {
                    ItemStatus.SUCCESS -> succeeded++
                    ItemStatus.SKIPPED -> skippedChildren++
                    else -> failures += "${child.name}: ${outcome.message.orEmpty()}"
                }
                bytesCopied += nested.bytesCopied
                progress(child.name)
            }
            published = destination.ref
            if (failures.isNotEmpty()) {
                throw StorageException(StorageError.IO,
                    "Merged $succeeded of ${children.size} items into ${destination.name}; ${failures.first()}")
            }
            if (request.type == OperationType.MOVE && skippedChildren == 0) {
                var remaining = false
                origin.list(original.ref, complete = true).collect { if (it.isNotEmpty()) remaining = true }
                if (remaining) {
                    throw StorageException(StorageError.CONFLICT,
                        "${original.name} gained items while it was being merged and was kept; " +
                            "everything moved is in ${destination.name}")
                }
                journal(JournalPhase.DELETING_SOURCE, destination.ref, "Removing the emptied source folder ${original.name}")
                removing { origin.delete(original.ref) }
            }
            progress(original.name, force = true)
            return ItemOutcome(original.ref, destination.ref, ItemStatus.SUCCESS,
                "Merged $succeeded item(s) into ${destination.name}" +
                    if (skippedChildren > 0) "; $skippedChildren kept in the source folder" else "")
        }

        private suspend fun copyTree(origin: StorageProvider, item: Entry, target: StorageProvider, created: Entry, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (depth > 256 || !visited.add(item.ref)) {
                throw StorageException(StorageError.UNSUPPORTED, "Cyclic or excessively deep folder tree")
            }
            if (item.directory) {
                requireCapability(item, Capability.LIST)
                requireCapability(created, Capability.CREATE)
                val snapshot = Snapshot(item, null, mutableListOf())
                manifest += snapshot
                origin.list(item.ref, complete = true).collect { batch ->
                    for (child in batch) {
                        currentCoroutineContext().ensureActive()
                        validateName(child.name)
                        snapshot.children!!.add(child.ref)
                        if (!child.directory) checkSpace(target, created.ref, child.size)
                        val newChild = target.create(created.ref, child.name, child.directory, child.mimeType)
                        copyTree(origin, child, target, newChild, depth + 1)
                    }
                }
            } else {
                requireCapability(item, Capability.READ)
                requireCapability(created, Capability.WRITE)
                requireCapability(created, Capability.READ)
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                origin.openRead(item.ref).use { input ->
                    target.openWrite(created.ref).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            count += read
                            bytesCopied += read
                            progress(item.name)
                        }
                        output.flush()
                    }
                }
                if (item.size != null && count != item.size) {
                    throw StorageException(StorageError.IO, "${item.name} changed size while being copied")
                }
                val writtenDigest = digest.digest()
                val checked = hash(target, created.ref)
                if (checked.size != count || !checked.digest.contentEquals(writtenDigest)) {
                    throw StorageException(StorageError.IO, "Verification failed for ${item.name}; source retained")
                }
                manifest += Snapshot(item, Hash(count, writtenDigest), null)
            }
        }

        private suspend fun validateSourceTree(origin: StorageProvider, verifyContent: Boolean) {
            for (snapshot in manifest) {
                currentCoroutineContext().ensureActive()
                val current = origin.stat(snapshot.entry.ref)
                if (current.directory != snapshot.entry.directory || current.name != snapshot.entry.name ||
                    current.size != snapshot.entry.size || current.modified != snapshot.entry.modified) {
                    throw StorageException(StorageError.IO, "${snapshot.entry.name} changed during the operation; source retained")
                }
                if (current.directory) {
                    val children = mutableSetOf<NodeRef>()
                    origin.list(current.ref, complete = true).collect { batch -> children.addAll(batch.map { it.ref }) }
                    if (children != snapshot.children!!.toSet()) {
                        throw StorageException(StorageError.IO, "${current.name} contents changed; source retained")
                    }
                } else if (verifyContent) {
                    val currentHash = hash(origin, current.ref)
                    if (currentHash.size != snapshot.hash!!.size || !currentHash.digest.contentEquals(snapshot.hash.digest)) {
                        throw StorageException(StorageError.IO, "${current.name} changed after copying; source retained")
                    }
                }
            }
        }

        private suspend fun deleteMovedTree(origin: StorageProvider) {
            // The manifest lists parents before children; reversed, each directory is empty when deleted.
            for (snapshot in manifest.asReversed()) {
                currentCoroutineContext().ensureActive()
                val current = try { origin.stat(snapshot.entry.ref) } catch (gone: StorageException) {
                    // A prefix folder goes with its last child, which was just removed.
                    if (snapshot.entry.directory && gone.reason == StorageError.NOT_FOUND) continue
                    throw gone
                }
                if (!current.directory) {
                    val latest = hash(origin, current.ref)
                    if (latest.size != snapshot.hash!!.size || !latest.digest.contentEquals(snapshot.hash.digest)) {
                        throw StorageException(StorageError.IO, "${current.name} changed before deletion; both copies retained")
                    }
                }
                emit(OperationEvent.Journal(request.id, JournalPhase.DELETING_SOURCE, current.ref, published,
                    request.destination, "Removing verified source ${current.name}"))
                origin.delete(current.ref)
                if (current.ref != source) emit(OperationEvent.ItemFinished(request.id,
                    ItemOutcome(current.ref, published, ItemStatus.SUCCESS, "Removed source ${current.name} after verified move")))
            }
        }

        suspend fun delete(ref: NodeRef): ItemOutcome {
            val provider = registry.provider(ref)
            val entry = provider.stat(ref)
            // Check capabilities for the whole tree before deleting anything.
            requireDeletableTree(provider, entry, mutableSetOf())
            journal(JournalPhase.DELETING_SOURCE, ref, "Explicit recursive deletion requested")
            removing { deleteTree(provider, ref, 0, reportChildren = true) }
            return ItemOutcome(ref, status = ItemStatus.SUCCESS, message = "Deleted ${entry.name}")
        }

        private suspend fun deleteTree(provider: StorageProvider, ref: NodeRef, depth: Int, reportChildren: Boolean) {
            currentCoroutineContext().ensureActive()
            if (depth > 256) throw StorageException(StorageError.UNSUPPORTED, "Folder tree is too deep")
            val entry = provider.stat(ref)
            requireCapability(entry, Capability.DELETE)
            // Do not recursively delete a link's target.
            if (entry.directory && !entry.link) {
                requireCapability(entry, Capability.LIST)
                // Re-list until empty: a listing may be partial, and children can arrive during deletion.
                var passes = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val children = mutableListOf<Entry>()
                    provider.list(ref, complete = true).collect { children.addAll(it) }
                    if (children.isEmpty()) break
                    if (++passes > DELETE_LIST_PASSES) throw StorageException(StorageError.IO,
                        "${entry.name} still lists items after $DELETE_LIST_PASSES passes; " +
                            "it may be being written to, or it cannot be listed completely")
                    for (child in children) {
                        try {
                            deleteTree(provider, child.ref, depth + 1, reportChildren)
                            if (reportChildren) emit(OperationEvent.ItemFinished(request.id,
                                ItemOutcome(child.ref, status = ItemStatus.SUCCESS, message = "Deleted ${child.name}")))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            if (reportChildren) emit(OperationEvent.ItemFinished(request.id,
                                ItemOutcome(child.ref, status = ItemStatus.FAILED, message = failure.message,
                                    error = classify(failure))))
                            throw failure
                        }
                    }
                }
            }
            if (reportChildren) emit(OperationEvent.Journal(request.id, JournalPhase.DELETING_SOURCE,
                ref, destination = request.destination, message = "Deleting ${entry.name}"))
            provider.delete(ref)
        }

        suspend fun rename(ref: NodeRef): ItemOutcome {
            val provider = registry.provider(ref)
            val item = provider.stat(ref)
            requireCapability(item, Capability.RENAME)
            val name = request.name ?: throw IllegalArgumentException("A new name is required")
            validateName(name)
            if (name == item.name) return ItemOutcome(ref, ref, ItemStatus.SUCCESS, "Name already is $name")
            // Refs are opaque, so conflict handling is only possible when the caller supplies the parent.
            val parent = request.destination
            if (parent != null) {
                if (parent.provider != ref.provider) throw StorageException(StorageError.UNSUPPORTED, "Rename must stay in the same storage provider")
                if (provider.child(parent, item.name)?.ref != ref) throw StorageException(StorageError.UNSUPPORTED, "Rename requires the item's current parent")
                val existing = provider.child(parent, name)
                val resolved = resolveName(provider, parent, name, existing, item.directory) ?: return skipped(ref)
                if (existing != null && resolved == name) {
                    // Replace through the verified move path; never delete the existing target first.
                    return Session(request.copy(type = OperationType.MOVE), source, completedItems, totalItems, emit,
                        removeAfterFlush = removeAfterFlush)
                        .let { replacement ->
                            try { replacement.transfer(ref).copy(message = "Renamed ${item.name} to $name") }
                            finally {
                                staged = replacement.staged
                                published = replacement.published
                                retainedByCommit += replacement.retainedByCommit
                                nestedArtifacts += replacement.nestedArtifacts
                            }
                        }
                }
                journal(JournalPhase.COMMITTING, ref, "Renaming ${item.name} to $resolved")
                val renamed = removing { provider.rename(ref, resolved) }
                published = renamed.ref
                journal(JournalPhase.PUBLISHED, renamed.ref, "Renamed to $resolved")
                return ItemOutcome(ref, renamed.ref, ItemStatus.SUCCESS, "Renamed ${item.name} to $resolved")
            }
            journal(JournalPhase.COMMITTING, ref, "Renaming ${item.name} to $name")
            val renamed = removing { provider.rename(ref, name) }
            published = renamed.ref
            journal(JournalPhase.PUBLISHED, renamed.ref, "Renamed to $name")
            return ItemOutcome(ref, renamed.ref, ItemStatus.SUCCESS, "Renamed ${item.name} to $name")
        }

        suspend fun createFolder(): ItemOutcome {
            val parent = request.destination ?: throw IllegalArgumentException("A parent folder is required")
            val provider = registry.provider(parent)
            val folder = provider.stat(parent)
            requireDirectory(folder)
            requireCapability(folder, Capability.CREATE)
            val name = request.name ?: throw IllegalArgumentException("A folder name is required")
            validateName(name)
            val existing = provider.child(parent, name)
            if (existing != null && existing.directory && mergesDirectories()) {
                published = existing.ref
                return ItemOutcome(null, existing.ref, ItemStatus.SUCCESS, "Folder $name already exists")
            }
            val resolved = resolveName(provider, parent, name, existing, directory = true) ?: return skipped(null)
            if (existing != null && resolved == name) {
                throw StorageException(StorageError.UNSUPPORTED, "An item named $resolved already exists here")
            }
            journal(JournalPhase.COMMITTING, null, "Creating folder $resolved")
            val created = provider.create(parent, resolved, true)
            published = created.ref
            journal(JournalPhase.PUBLISHED, created.ref, "Created folder $resolved")
            return ItemOutcome(null, created.ref, ItemStatus.SUCCESS, "Created folder $resolved")
        }

        suspend fun createArchive(): ItemOutcome {
            val spec = request.archive ?: throw IllegalArgumentException("Archive options are required")
            val parent = request.destination ?: throw IllegalArgumentException("A destination folder is required")
            val name = request.name ?: throw IllegalArgumentException("An archive name is required")
            // Never fall back to writing an unencrypted archive when the password is missing.
            if (spec.needsPassword && password.isEmpty()) {
                throw StorageException(StorageError.AUTH, "The password is no longer held. Create the archive again.")
            }
            val target = registry.provider(parent)
            val folder = target.stat(parent)
            requireDirectory(folder)
            requireCapability(folder, Capability.CREATE)
            // The archive engine commits without replace; check here for a clearer conflict error.
            if (target.child(parent, name) != null) {
                throw StorageException(StorageError.CONFLICT, "An item named $name already exists here")
            }
            archives.create(request.sources, parent, name, spec.options(password)).collect { step -> archive(step) }
            return ItemOutcome(null, published, ItemStatus.SUCCESS, "Created $name")
        }

        /** Extracts into the destination, or into a new folder there when the request has a name. */
        suspend fun extractArchive(ref: NodeRef): ItemOutcome {
            val parent = request.destination ?: throw IllegalArgumentException("A destination folder is required")
            if (request.archive?.needsPassword == true && password.isEmpty()) {
                throw StorageException(StorageError.AUTH, "The password is no longer held. Extract again.")
            }
            val target = registry.provider(parent)
            val folder = target.stat(parent)
            requireDirectory(folder)
            requireCapability(folder, Capability.CREATE)
            val item = registry.provider(ref).stat(ref)
            archives.extract(ref, parent, request.name, password).collect { step -> archive(step) }
            return ItemOutcome(ref, published, ItemStatus.SUCCESS, "Extracted ${item.name}")
        }

        private suspend fun archive(step: ArchiveProgress) {
            step.target?.let { ref ->
                if (request.type == OperationType.CREATE_ARCHIVE) {
                    archiveStaging = ref
                    journal(JournalPhase.STAGING, ref, "Writing ${request.name}")
                } else {
                    // Members are extracted directly into this folder; record it before they are written.
                    published = ref
                    journal(JournalPhase.IN_PLACE, ref, "Extracting into ${request.name ?: "this folder"}")
                }
            }
            bytesCopied = step.bytesDone
            totalBytes = step.bytesTotal
            if (step.complete) {
                published = step.produced ?: published
                journal(JournalPhase.PUBLISHED, published, "Finished ${step.currentName}")
            } else if (step.currentName.isNotEmpty()) {
                archiveProgress(step)
            }
        }

        /** Throttled like [progress]. The item counts are archive members, not request items. */
        private suspend fun archiveProgress(step: ArchiveProgress) {
            val now = System.nanoTime()
            if (now - lastProgressAt < 100_000_000L) return
            lastProgressAt = now
            emit(OperationEvent.Progress(request.id, source, step.currentName, step.bytesDone,
                step.bytesTotal, step.entriesDone, step.entriesTotal))
        }

        /** Callers must wrap this in NonCancellable so the provider calls can run after cancellation. */
        suspend fun cleanup(): List<NodeRef> {
            // The archive engine deletes its own staging file; report it only if it still exists.
            val leftover = archiveStaging?.takeIf { ref ->
                runCatching { registry.provider(ref).stat(ref) }.isSuccess
            }
            val retained = nestedArtifacts + retainedByCommit + listOfNotNull(leftover)
            val artifact = staged ?: return retained
            return try {
                journal(JournalPhase.CLEANUP, artifact,
                    inPlaceName?.let { "Removing the incomplete $it" } ?: "Removing incomplete staging data")
                deleteTree(registry.provider(artifact), artifact, 0, reportChildren = false)
                staged = null
                retained
            } catch (failure: Exception) {
                try { journal(JournalPhase.RETAINED_ARTIFACT, artifact, inPlaceName?.let {
                    "Incomplete $it retained under its real name and must be deleted or replaced: ${failure.message}"
                } ?: "Incomplete staging data retained: ${failure.message}") }
                catch (_: Exception) { /* Journaling failed; the returned artifact is still the recovery record. */ }
                retained + artifact
            }
        }

        private suspend fun progress(name: String, force: Boolean = false) {
            val now = System.nanoTime()
            if (force || now - lastProgressAt >= 100_000_000L) {
                lastProgressAt = now
                emit(OperationEvent.Progress(request.id, source, name, bytesCopied, totalBytes, completedItems, totalItems))
            }
        }

        private suspend fun resolveName(provider: StorageProvider, parent: NodeRef, name: String, existing: Entry?, directory: Boolean): String? {
            if (existing == null) return name
            return when (request.conflictPolicy) {
                ConflictPolicy.ASK -> throw NameConflictException(
                    if (directory) "A folder named $name already exists. Choose merge, keep both, rename or skip."
                    else "A file named $name already exists. Choose overwrite, keep both, rename or skip.",
                    directory, name)
                ConflictPolicy.SKIP -> null
                ConflictPolicy.REPLACE, ConflictPolicy.MERGE -> name
                ConflictPolicy.KEEP_BOTH -> {
                    val names = mutableSetOf<String>()
                    provider.list(parent, complete = true).collect { batch -> names.addAll(batch.map { it.name }) }
                    val dot = if (directory) -1 else name.lastIndexOf('.').takeIf { it > 0 } ?: -1
                    val stem = if (dot > 0) name.substring(0, dot) else name
                    val suffix = if (dot > 0) name.substring(dot) else ""
                    var number = 1
                    var candidate: String
                    do {
                        currentCoroutineContext().ensureActive()
                        val addition = " ($number)$suffix"
                        candidate = stem.take((255 - addition.length).coerceAtLeast(1)) + addition
                        number++
                    } while (names.any { provider.namesEqual(it, candidate) })
                    validateName(candidate)
                    candidate
                }
            }
        }
    }

    private class Hash(val size: Long, val digest: ByteArray)
    private data class Snapshot(val entry: Entry, val hash: Hash?, val children: MutableList<NodeRef>?)
    /** A name collision, which a retry with another [ConflictPolicy] can resolve; other CONFLICT errors cannot. */
    private class NameConflictException(message: String, val directory: Boolean, val name: String) : Exception(message)

    private suspend fun hash(provider: StorageProvider, ref: NodeRef): Hash {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        provider.openRead(ref).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                count += read
            }
        }
        return Hash(count, digest.digest())
    }

    private suspend fun requireDeletableTree(provider: StorageProvider, entry: Entry, seen: MutableSet<NodeRef>, depth: Int = 0) {
        currentCoroutineContext().ensureActive()
        if (depth > 256 || !seen.add(entry.ref)) throw StorageException(StorageError.UNSUPPORTED, "Cyclic or excessively deep folder tree")
        requireCapability(entry, Capability.DELETE)
        if (entry.directory && !entry.link) {
            requireCapability(entry, Capability.LIST)
            provider.list(entry.ref, complete = true).collect { children ->
                for (child in children) requireDeletableTree(provider, child, seen, depth + 1)
            }
        }
    }

    private suspend fun checkSpace(provider: StorageProvider, parent: NodeRef, required: Long?) {
        val available = provider.availableBytes(parent)
        if (required != null && available != null && required > available) {
            throw StorageException(StorageError.NO_SPACE, "Insufficient space: need $required bytes; $available available")
        }
    }

    private fun requireDirectory(entry: Entry) {
        if (!entry.directory) throw StorageException(StorageError.UNSUPPORTED, "${entry.name} is not a folder")
        requireCapability(entry, Capability.LIST)
    }

    private fun requireCapability(entry: Entry, capability: Capability) {
        if (capability !in entry.capabilities) throw StorageException(StorageError.UNSUPPORTED,
            "${entry.name} does not support ${capability.name.lowercase().replace('_', ' ')}")
    }

    private fun requireSafeReplacement(parent: Entry, existing: Entry, sourceDirectory: Boolean) {
        if (sourceDirectory != existing.directory) throw StorageException(StorageError.UNSUPPORTED,
            "A file and a folder cannot overwrite each other; choose keep both or skip")
        if (sourceDirectory) throw StorageException(StorageError.UNSUPPORTED,
            "Folders are merged rather than replaced; choose merge, keep both, or skip")
        if (Capability.ATOMIC_REPLACE in parent.capabilities) return
        if (Capability.REPLACE !in parent.capabilities) {
            throw StorageException(StorageError.UNSUPPORTED, "This storage cannot overwrite files safely; choose keep both or skip")
        }
        // Recoverable replace renames the old target aside and deletes it afterwards; check both before copying.
        if (Capability.RENAME !in existing.capabilities) {
            throw StorageException(StorageError.UNSUPPORTED,
                "This storage cannot move ${existing.name} aside, so it cannot be overwritten; choose keep both or skip")
        }
        if (Capability.DELETE !in existing.capabilities) {
            throw StorageException(StorageError.UNSUPPORTED,
                "This storage cannot remove the previous ${existing.name}; choose keep both or skip")
        }
    }

    private fun skipped(ref: NodeRef?) = ItemOutcome(ref, status = ItemStatus.SKIPPED, message = "Skipped existing name")

    /** Stream reads and writes can throw raw exceptions that never passed through a provider's error mapping. */
    private fun classify(failure: Exception): StorageError {
        for (cause in generateSequence<Throwable>(failure) { it.cause }.take(8)) {
            if (cause is NameConflictException) return StorageError.CONFLICT
            if (cause is StorageException) return cause.reason
            if (cause is SecurityException || cause is java.nio.file.AccessDeniedException) return StorageError.PERMISSION
            if (cause is java.io.FileNotFoundException || cause is java.nio.file.NoSuchFileException) return StorageError.NOT_FOUND
            if (cause is java.net.SocketTimeoutException) return StorageError.TIMEOUT
            if (cause is java.net.UnknownHostException || cause is java.net.ConnectException || cause is java.net.NoRouteToHostException) return StorageError.OFFLINE
            val message = cause.message.orEmpty()
            if (message.contains("ENOSPC", true) || message.contains("no space", true) || message.contains("disk full", true)) return StorageError.NO_SPACE
            if (message.contains("EACCES", true) || message.contains("permission denied", true)) return StorageError.PERMISSION
            if (message.contains("ENODEV", true) || message.contains("ENXIO", true)) return StorageError.DISCONNECTED
        }
        return StorageError.IO
    }
}
