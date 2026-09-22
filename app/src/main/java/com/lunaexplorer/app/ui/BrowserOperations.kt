package com.lunaexplorer.app.ui

import com.lunaexplorer.app.AppGraph
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.Clipboard
import com.lunaexplorer.app.model.ExtractPlan
import com.lunaexplorer.app.model.Overlay
import com.lunaexplorer.app.model.Screen
import com.lunaexplorer.app.model.VersionedDelete
import com.lunaexplorer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

class BrowserOperations internal constructor(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val _state: MutableStateFlow<BrowserState>,
    private val resolver: PathResolver,
    private val showMessage: (String) -> Unit,
) {
    fun copySelection(move: Boolean) {
        val entries = _state.value.selectedEntries
        if (entries.isEmpty()) return
        _state.update { state ->
            val existing = state.clipboard
            // The same mode adds to the clipboard; switching between copy and move replaces it.
            val combined = if (existing != null && existing.move == move) {
                val known = existing.entries.mapTo(HashSet()) { it.ref }
                Clipboard(existing.entries + entries.filter { it.ref !in known }, move)
            } else {
                Clipboard(entries, move)
            }
            state.copy(clipboard = combined, selected = emptySet())
        }
    }

    fun carryExtract(archive: Entry, plan: ExtractPlan) {
        _state.update {
            it.copy(clipboard = Clipboard(listOf(archive), move = false, extract = plan), selected = emptySet())
        }
    }

    fun clearClipboard() { _state.update { it.copy(clipboard = null) } }

    /** [keep] retains a copied selection for another paste; a move always clears the clipboard. */
    fun paste(keep: Boolean = false) {
        val clipboard = _state.value.clipboard ?: return
        // Never write into a folder that is not on screen. Only the screen is checked: a paste deferred
        // behind an archive-password prompt can land while the folder is still reloading.
        if (_state.value.screen != Screen.BROWSER) return
        val destination = _state.value.location?.ref ?: return
        val unpacking = clipboard.extract
        if (unpacking != null) {
            val archive = clipboard.entries.firstOrNull() ?: return
            submit(
                OperationRequest(type = OperationType.EXTRACT_ARCHIVE, sources = listOf(archive.ref),
                    destination = destination, name = unpacking.intoFolder,
                    archive = ArchiveSpec(needsPassword = unpacking.password.isNotEmpty())),
                "Extract ${archive.name} to ${_state.value.location?.title}", unpacking.password,
            )
            clearClipboard()
            return
        }
        scope.launch {
            // Luna's own databases are live and use a write-ahead log; copy a consistent snapshot instead.
            val sources = try {
                clipboard.entries.map { entry ->
                    if (!clipboard.move) snapshotOfLiveDatabase(entry) ?: entry.ref else entry.ref
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ensureActive()
                showMessage(error.message ?: "Could not prepare the files for copying")
                return@launch
            }
            submit(OperationRequest(type = if (clipboard.move) OperationType.MOVE else OperationType.COPY,
                sources = sources, destination = destination),
                "${if (clipboard.move) "Move" else "Copy"} ${clipboard.entries.size} item(s) to ${_state.value.location?.title}")
            if (clipboard.move || !keep) clearClipboard()
        }
    }

    private suspend fun snapshotOfLiveDatabase(entry: Entry): NodeRef? {
        val path = resolver.pathOf(entry.ref) ?: return null
        val copy = graph.database.snapshot(path, entry.name) ?: return null
        return resolver.refFor(copy.absolutePath) ?: run {
            withContext(Dispatchers.IO) { copy.parentFile?.deleteRecursively() }
            throw StorageException(StorageError.IO, "Could not access the database snapshot for copying")
        }
    }

    fun renameEntry(entry: Entry, name: String) {
        submit(OperationRequest(type = OperationType.RENAME, sources = listOf(entry.ref),
            destination = _state.value.location?.ref, name = name),
            "Rename ${entry.name} to $name")
    }

    /** From a viewer, which asks only whether. Nothing but a standing choice to delete every version destroys one. */
    fun deleteEntry(entry: Entry) = deleteEntries(listOf(entry), _state.value.recycleBin,
        keepVersions = _state.value.versionedDelete != VersionedDelete.PURGE)

    fun createFolder(name: String) {
        val destination = _state.value.location?.ref ?: return
        submit(OperationRequest(type = OperationType.CREATE_FOLDER, destination = destination, name = name), "Create folder $name")
    }

    /** The password goes to the queue separately from the request and is never persisted or logged. */
    fun createArchive(sources: List<Entry>, name: String, options: ArchiveOptions) {
        if (sources.isEmpty()) return
        val destination = _state.value.location?.ref ?: return
        submit(OperationRequest(type = OperationType.CREATE_ARCHIVE, sources = sources.map { it.ref },
            destination = destination, name = name, archive = options.spec()),
            "Create archive $name", options.password)
    }

    /** [into] names a new folder; null extracts in place. */
    fun extractArchive(archive: Entry, into: String?, password: String = "") {
        val destination = _state.value.location?.ref ?: return
        submit(OperationRequest(type = OperationType.EXTRACT_ARCHIVE, sources = listOf(archive.ref),
            destination = destination, name = into,
            archive = ArchiveSpec(needsPassword = password.isNotEmpty())),
            "Extract ${archive.name}", password)
    }

    fun renameSelection(name: String) {
        val entry = _state.value.selectedEntries.singleOrNull() ?: return
        if (_state.value.searchActive) { showMessage("Open the containing folder before renaming a search result"); return }
        renameEntry(entry, name)
    }

    internal fun keepsVersions(entries: List<Entry>): Boolean = entries.isNotEmpty() && entries.all { entry ->
        runCatching { Feature.VERSIONED in graph.providers.provider(entry.ref).features }.getOrDefault(false)
    }

    /** With [toBin], entries move to a bin on their own storage, so recycling is a rename rather than a copy. */
    internal fun deleteEntries(entries: List<Entry>, toBin: Boolean, keepVersions: Boolean = false) {
        if (entries.isEmpty()) return
        // Archives have no recycle bin: deleting members is always permanent.
        val insideArchive = entries.all { it.ref.provider == graph.insideArchives.id }
        // Nor does versioned storage: hiding is its recoverable delete, and a bin there would be a copy of every file.
        val versioned = keepsVersions(entries)
        if (!toBin || insideArchive || versioned) {
            val hide = versioned && keepVersions
            submit(OperationRequest(type = OperationType.DELETE, sources = entries.map { it.ref }, keepVersions = hide),
                "${if (hide) "Hide" else "Delete"} ${entries.size} item(s)")
            return
        }
        val shown = _state.value.location?.ref
        _state.update { it.copy(selected = emptySet()) }
        scope.launch {
            val byBin = LinkedHashMap<NodeRef, MutableList<Entry>>()
            val homeless = mutableListOf<Entry>()
            for (entry in entries) {
                val bin = try {
                    graph.recycleBin.folderFor(entry.ref)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ensureActive()
                    null
                }
                if (bin == null) homeless += entry else byBin.getOrPut(bin.ref) { mutableListOf() } += entry
            }
            for ((bin, items) in byBin) {
                val request = OperationRequest(type = OperationType.MOVE, sources = items.map { it.ref },
                    destination = bin, conflictPolicy = ConflictPolicy.KEEP_BOTH)
                val now = System.currentTimeMillis()
                graph.database.stageTrash(request.id, items.map { entry ->
                    // Search results, categories and the storage tools list entries from many folders,
                    // so the folder on screen is only a fallback.
                    val parent = try {
                        graph.providers.provider(entry.ref).parentOf(entry.ref)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        ensureActive()
                        null
                    }
                    TrashedItem(
                        id = UUID.randomUUID().toString(), name = entry.name, ref = entry.ref,
                        originalParent = parent ?: shown ?: bin, originalName = entry.name,
                        originalPath = resolver.shownPathOf(entry.ref), directory = entry.directory,
                        size = entry.size, deletedAt = now,
                    )
                })
                try { graph.queue.enqueue(request, "Recycle ${items.size} item(s)") }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { ensureActive(); showMessage("Could not queue: ${error.message}") }
            }
            if (homeless.isNotEmpty()) {
                showMessage("${homeless.size} item(s) have no recycle bin on their storage. Delete permanently to remove them.")
            }
        }
    }

    fun restoreFromBin(items: List<TrashedItem>) {
        if (items.isEmpty()) return
        scope.launch {
            // Each item goes back under its own original name, so each is its own request.
            for (item in items) {
                val request = OperationRequest(type = OperationType.MOVE, sources = listOf(item.ref),
                    destination = item.originalParent, name = item.originalName)
                try { graph.queue.enqueue(request, "Restore ${item.originalName}") }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { ensureActive(); showMessage("Could not queue: ${error.message}") }
            }
        }
    }

    fun emptyBin(items: List<TrashedItem>) {
        if (items.isEmpty()) return
        submit(OperationRequest(type = OperationType.DELETE, sources = items.map { it.ref }),
            "Empty recycle bin (${items.size})")
    }

    fun pruneBin() {
        scope.launch {
            val stale = withContext(Dispatchers.IO) {
                graph.database.trash.value.filter { item ->
                    try {
                        graph.providers.provider(item.ref).stat(item.ref)
                        false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        ensureActive()
                        error is StorageException && error.reason == StorageError.NOT_FOUND
                    }
                }
            }
            if (stale.isNotEmpty()) graph.database.forgetTrash(stale.map { it.id })
        }
    }

    internal fun submit(request: OperationRequest, title: String, secret: String = "") {
        _state.update { it.copy(selected = emptySet()) }
        // A delete was answered for in its dialog. A move or rename is not asked about, so its old name
        // loses its versions only under a standing choice to delete them.
        val asked = if (request.type == OperationType.DELETE) request
            else request.copy(keepVersions = _state.value.versionedDelete != VersionedDelete.PURGE)
        scope.launch {
            try { graph.queue.enqueue(asked, title, secret) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { ensureActive(); showMessage("Could not queue operation: ${error.message}") }
        }
    }

    fun cancelOperation(id: String) { scope.launch { graph.queue.cancel(id) } }

    fun resolveConflict(id: String, policy: ConflictPolicy, name: String? = null) {
        resolveConflict(id, policy, name, confirmed = false)
    }

    fun confirmOverwrite(confirmation: Overlay.Overwrite) {
        if (_state.value.overlay != confirmation) return
        _state.update { it.copy(overlay = Overlay.Queue) }
        resolveConflict(confirmation.operationId, confirmation.policy, null, confirmed = true)
    }

    private fun resolveConflict(id: String, policy: ConflictPolicy, name: String?, confirmed: Boolean) {
        val overlay = _state.value.overlay
        scope.launch {
            try {
                if (!confirmed && name == null && (policy == ConflictPolicy.REPLACE || policy == ConflictPolicy.MERGE)) {
                    val request = graph.database.request(id) ?: return@launch
                    val destinations = request.destination?.let(::listOf) ?: request.sources
                    if (destinations.any { Feature.UNGUARDED_REPLACE in graph.providers.provider(it).features }) {
                        _state.update {
                            if (it.overlay == overlay) it.copy(overlay = Overlay.Overwrite(id, policy)) else it
                        }
                        return@launch
                    }
                }
                if (graph.database.resolveConflict(id, policy, name)) graph.queue.schedule()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { ensureActive(); showMessage("Could not resolve conflict: ${error.message}") }
        }
    }
}
