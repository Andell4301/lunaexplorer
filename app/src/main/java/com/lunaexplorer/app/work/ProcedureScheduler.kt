package com.lunaexplorer.app.work

import android.content.Context
import androidx.work.*
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.data.ProcedureStore
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProcedureScheduler(private val context: Context, private val store: ProcedureStore) {
    private val lock = Mutex()

    suspend fun synchronize() = lock.withLock {
        val manager = WorkManager.getInstance(context)
        if (store.hasScheduled()) {
            manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ProcedureScheduleWorker>(15, TimeUnit.MINUTES).build()).await()
            manager.enqueueUniqueWork(CHECK, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ProcedureScheduleWorker>().build()).await()
        } else {
            manager.cancelUniqueWork(PERIODIC).await()
            manager.cancelUniqueWork(CHECK).await()
        }
    }

    suspend fun stopIfIdle() = lock.withLock {
        if (!store.hasScheduled()) WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    private companion object {
        const val PERIODIC = "luna-procedure-schedules"
        const val CHECK = "luna-procedure-check"
    }
}

class ProcedureScheduleWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as LunaApplication).graph
        return try {
            graph.procedures.dispatchDue(Instant.now()) { procedure, due ->
                graph.queue.enqueueProcedure(procedure, due)
            }
            graph.procedureScheduler.stopIfIdle()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            Result.retry()
        }
    }
}
