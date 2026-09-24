package com.lunaexplorer.app.work

import com.lunaexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

internal data class ProcedureExecutionResult(
    val operation: OperationResult,
    val steps: List<ProcedureStepResult>,
    val stoppedAt: Int? = null,
    val stoppedByControl: Boolean = false,
) {
    val outcomes get() = operation.outcomes
    val requestId get() = operation.requestId
    val successful get() = steps.none { it.status == ProcedureStepStatus.FAILED } &&
        outcomes.all { it.status == ItemStatus.SUCCESS || it.status == ItemStatus.SKIPPED }

    fun summary(): String = buildList {
        stoppedAt?.let { add(if (stoppedByControl) "Stopped by step $it" else "Stopped at step $it") }
        val completed = steps.count { it.status == ProcedureStepStatus.SUCCEEDED }
        val skipped = steps.count { it.status == ProcedureStepStatus.SKIPPED }
        val failed = steps.count { it.status == ProcedureStepStatus.FAILED }
        add("$completed steps completed")
        if (skipped > 0) add("$skipped skipped")
        if (failed > 0) add("$failed failed")
        outcomes.lastOrNull { it.status != ItemStatus.SUCCESS && it.status != ItemStatus.SKIPPED }
            ?.message?.let(::add)
    }.joinToString(" · ")
}

internal class ProcedureExecution(
    private val planner: ProcedurePlanner,
    private val engine: OperationEngine,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    suspend fun run(
        request: OperationRequest,
        onStep: suspend (Int, ProcedureStep) -> Unit,
        onEvent: suspend (OperationEvent) -> Unit,
        onStepFinished: suspend (Int, ProcedureStep, ProcedureStepResult) -> Unit = { _, _, _ -> },
    ): ProcedureExecutionResult = withContext(Dispatchers.IO) {
        validateProcedureSteps(request.steps)
        val names = ProcedureNames(now())
        val outcomes = mutableListOf<ItemOutcome>()
        val steps = mutableListOf<ProcedureStepResult>()
        var stoppedAt: Int? = null
        var stoppedByControl = false
        for ((index, step) in request.steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            val number = index + 1
            if (!step.conditionsMet(steps)) {
                val skipped = ProcedureStepResult(step.id, ProcedureStepStatus.SKIPPED, 0)
                steps += skipped
                onStepFinished(number, step, skipped)
                continue
            }
            onStep(number, step)
            if (step.control == ProcedureControl.STOP) {
                val stopped = ProcedureStepResult(step.id, ProcedureStepStatus.SUCCEEDED, 0)
                steps += stopped
                onStepFinished(number, step, stopped)
                stoppedAt = number
                stoppedByControl = true
                break
            }
            var failed = false
            var outputCount = 0
            try {
                val actions = planner.plan(step, names) { preparation ->
                    val result = engine.run(preparation.copy(id = request.id), onEvent = onEvent)
                    outcomes += result.outcomes
                    if (!result.successful) throw StepFailed()
                    result
                }
                for (action in actions) {
                    currentCoroutineContext().ensureActive()
                    val result = engine.run(action.copy(id = request.id), onEvent = onEvent)
                    outcomes += result.outcomes
                    outputCount += result.outcomes.count { it.status == ItemStatus.SUCCESS }
                    if (result.outcomes.any { it.status != ItemStatus.SUCCESS && it.status != ItemStatus.SKIPPED }) {
                        failed = true
                        break
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: StepFailed) {
                currentCoroutineContext().ensureActive()
                failed = true
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                val failure = ItemOutcome(null, status = ItemStatus.FAILED,
                    message = "Step $number: ${error.message ?: "Operation failed"}",
                    error = (error as? StorageException)?.reason)
                outcomes += failure
                onEvent(OperationEvent.ItemFinished(request.id, failure))
                failed = true
            }
            val result = ProcedureStepResult(step.id,
                if (failed) ProcedureStepStatus.FAILED else ProcedureStepStatus.SUCCEEDED, outputCount)
            steps += result
            onStepFinished(number, step, result)
            if (failed && step.onFailure == ProcedureFailurePolicy.STOP) {
                stoppedAt = number
                break
            }
        }
        ProcedureExecutionResult(OperationResult(request.id, outcomes), steps, stoppedAt, stoppedByControl)
    }

    private class StepFailed : Exception()
}
