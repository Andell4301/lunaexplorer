package com.lunaexplorer.app.work

import com.lunaexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class ProcedureExecution(
    private val planner: ProcedurePlanner,
    private val engine: OperationEngine,
) {
    suspend fun run(
        request: OperationRequest,
        onStep: suspend (Int, ProcedureStep) -> Unit,
        onEvent: suspend (OperationEvent) -> Unit,
    ): OperationResult = withContext(Dispatchers.IO) {
        require(request.steps.isNotEmpty()) { "Add at least one action" }
        val outcomes = mutableListOf<ItemOutcome>()
        for ((index, step) in request.steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            onStep(index + 1, step)
            try {
                val actions = planner.plan(step)
                for (action in actions) {
                    currentCoroutineContext().ensureActive()
                    val result = engine.run(action.copy(id = request.id), onEvent = onEvent)
                    outcomes += result.outcomes
                    if (result.outcomes.any { it.status != ItemStatus.SUCCESS && it.status != ItemStatus.SKIPPED }) {
                        return@withContext OperationResult(request.id, outcomes)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                val failure = ItemOutcome(null, status = ItemStatus.FAILED,
                    message = "Step ${index + 1}: ${error.message ?: "Operation failed"}",
                    error = (error as? StorageException)?.reason)
                outcomes += failure
                onEvent(OperationEvent.ItemFinished(request.id, failure))
                break
            }
        }
        OperationResult(request.id, outcomes)
    }
}
