package com.lunaexplorer.app.ui

import com.lunaexplorer.app.AppGraph
import com.lunaexplorer.app.model.StoredProcedure
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.ProcedureLocation
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import com.lunaexplorer.core.containsProcedurePlaceholder
import com.lunaexplorer.core.escapeProcedurePlaceholders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Procedures internal constructor(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val resolver: PathResolver,
    private val message: (String) -> Unit,
) {
    val saved = graph.procedures.procedures
    val runs = graph.database.procedureRuns

    suspend fun save(procedure: StoredProcedure) {
        procedure.validate()
        graph.procedures.save(procedure)
        graph.procedureScheduler.synchronize()
    }

    suspend fun remove(id: String) {
        graph.procedures.remove(id)
        graph.procedureScheduler.synchronize()
    }

    fun run(procedure: StoredProcedure) {
        scope.launch {
            try {
                message(if (graph.queue.enqueueProcedure(procedure)) "Procedure queued" else "Procedure is already queued")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                ensureActive()
                message(error.message ?: "Could not queue the procedure")
            }
        }
    }

    fun cancel(id: String) {
        scope.launch {
            try { graph.queue.cancel(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { ensureActive(); message(error.message ?: "Could not cancel the procedure") }
        }
    }

    fun shownPath(location: ProcedureLocation): String? = resolver.shownPathOf(location.ref)?.let { base ->
        if (location.children.isEmpty()) base else base + (if (base.endsWith('/')) "" else "/") + location.children.joinToString("/")
    }

    suspend fun label(location: ProcedureLocation): String = withContext(Dispatchers.IO) {
        shownPath(location) ?: try {
            val name = graph.providers.provider(location.ref).stat(location.ref).name
            if (location.children.isEmpty()) name else "$name/${location.children.joinToString("/")}"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            ensureActive()
            "${location.ref.provider}: ${location.children.lastOrNull() ?: "Selected location"}"
        }
    }

    suspend fun resolve(path: String, allowMissing: Boolean = false): ProcedureLocation = withContext(Dispatchers.IO) {
        require(path.isNotEmpty()) { "Choose a location" }
        val ref = requireNotNull(resolver.refFor(path)) { "Cannot resolve this path" }
        val templated = containsProcedurePlaceholder(path)
        if (!allowMissing && !templated) return@withContext ProcedureLocation(ref)
        if (!templated) existing(ref)?.let { return@withContext anchor(it) }
        val ancestors = ancestors(ref)
        for (separator in path.indices.reversed().filter { path[it] == '/' }) {
            ensureActive()
            val base = if (separator == 0) "/" else path.substring(0, separator)
            if (containsProcedurePlaceholder(base)) continue
            val parent = resolver.refFor(base) ?: continue
            if (parent !in ancestors) continue
            val entry = existing(parent) ?: continue
            require(entry.directory) { "Not a folder" }
            val anchored = anchor(entry)
            return@withContext anchored.copy(children = anchored.children + path.substring(separator + 1).split('/'))
        }
        throw StorageException(StorageError.NOT_FOUND, "Choose an existing parent folder")
    }

    suspend fun resolve(location: ProcedureLocation, allowMissing: Boolean = false): ProcedureLocation = withContext(Dispatchers.IO) {
        if (!allowMissing) return@withContext location
        val entry = existing(location.ref)
        val anchored = if (entry != null) anchor(entry) else {
            val path = resolver.shownPathOf(location.ref)
                ?: throw StorageException(StorageError.NOT_FOUND, "Choose an existing parent folder")
            resolve(path, allowMissing = true)
        }
        anchored.copy(children = anchored.children + location.children)
    }

    private suspend fun existing(ref: NodeRef): Entry? = try {
        graph.providers.provider(ref).stat(ref)
    } catch (error: StorageException) {
        currentCoroutineContext().ensureActive()
        if (error.reason != StorageError.NOT_FOUND) throw error
        null
    }

    private suspend fun ancestors(ref: NodeRef): Set<NodeRef> {
        val seen = mutableSetOf<NodeRef>()
        var current: NodeRef? = ref
        while (current != null) {
            currentCoroutineContext().ensureActive()
            check(seen.add(current)) { "Folder cycle detected" }
            current = graph.providers.provider(current).parentOf(current)
        }
        return seen
    }

    private suspend fun anchor(start: Entry): ProcedureLocation {
        var entry = start
        val names = ArrayDeque<String>()
        val seen = mutableSetOf<NodeRef>()
        while (true) {
            currentCoroutineContext().ensureActive()
            check(seen.add(entry.ref)) { "Folder cycle detected" }
            val parent = graph.providers.provider(entry.ref).parentOf(entry.ref)
                ?: run {
                    require(entry.directory) { "Not a folder" }
                    return ProcedureLocation(entry.ref, names.toList())
                }
            names.addFirst(escapeProcedurePlaceholders(entry.name))
            entry = graph.providers.provider(parent).stat(parent)
            require(entry.directory) { "Not a folder" }
        }
    }

    suspend fun entries(ref: NodeRef): List<Entry> = withContext(Dispatchers.IO) {
        graph.providers.provider(ref).list(ref).fold(emptyList<Entry>()) { found, batch -> found + batch }
            .sortedWith(compareByDescending<Entry> { it.directory }.thenBy { it.name })
    }
}
