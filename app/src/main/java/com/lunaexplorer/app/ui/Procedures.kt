package com.lunaexplorer.app.ui

import com.lunaexplorer.app.AppGraph
import com.lunaexplorer.app.model.StoredProcedure
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.ProcedureLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    suspend fun resolve(path: String): ProcedureLocation = withContext(Dispatchers.IO) {
        require(path.isNotEmpty()) { "Choose a location" }
        ProcedureLocation(requireNotNull(resolver.refFor(path)) { "Cannot resolve this path" })
    }

    suspend fun entries(ref: NodeRef): List<Entry> = withContext(Dispatchers.IO) {
        graph.providers.provider(ref).list(ref).fold(emptyList<Entry>()) { found, batch -> found + batch }
            .sortedWith(compareByDescending<Entry> { it.directory }.thenBy { it.name })
    }
}
