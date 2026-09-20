package com.lunaexplorer.app.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.MainActivity
import com.lunaexplorer.app.R
import com.lunaexplorer.app.data.LunaDatabase
import com.lunaexplorer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class OperationQueue(private val context: Context, private val database: LunaDatabase) {
    internal val execution = Mutex()
    internal val running = ConcurrentHashMap<String, Job>()
    /** Archive passwords by request id. Memory only: the user can browse and copy the database. */
    private val secrets = ConcurrentHashMap<String, String>()
    suspend fun reconnect() {
        // A live worker holds this mutex. If none does, RUNNING rows belong to an earlier process.
        if (execution.tryLock()) {
            try { database.recoverInterrupted() } finally { execution.unlock() }
        }
        if (database.hasQueued()) schedule()
    }
    suspend fun enqueue(request: OperationRequest, title: String, secret: String = "") {
        if (secret.isNotEmpty()) secrets[request.id] = secret
        database.enqueue(request, title)
        schedule()
    }
    internal fun secretFor(id: String): String = secrets[id].orEmpty()
    internal fun forgetSecret(id: String) { secrets.remove(id) }
    fun schedule() {
        WorkManager.getInstance(context).enqueueUniqueWork("luna-file-operations", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<OperationWorker>().addTag("luna-file-operations").build())
    }
    suspend fun cancel(id: String) {
        forgetSecret(id)
        database.cancel(id)
        running[id]?.cancel(CancellationException("Cancelled by user"))
    }
}

class OperationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val graph get() = (applicationContext as LunaApplication).graph
    override suspend fun doWork(): Result = graph.queue.execution.withLock {
        graph.database.recoverInterrupted()
        if (!graph.database.hasQueued()) return@withLock Result.success()
        // A helper still starting would send an operation on Android/data to Android itself, which refuses it.
        graph.shizuku.settled()
        try {
            setForeground(foreground("Preparing file operations"))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            // Nothing has been mutated yet, so the requests stay queued for the retry.
            graph.database.deferQueued("Waiting for Android to allow background execution: ${error.message ?: error.javaClass.simpleName}")
            return@withLock Result.retry()
        }
        while (currentCoroutineContext().isActive) {
            val request = graph.database.claimNext() ?: break
            var completedMutation = false
            try {
                supervisorScope {
                    val job = async(start = CoroutineStart.LAZY) {
                        if (graph.database.isCancelled(request.id)) throw CancellationException("Cancelled by user")
                        graph.engine.run(request, graph.queue.secretFor(request.id)) { event ->
                            record(event)
                            if (event is OperationEvent.ItemFinished && event.outcome.status == ItemStatus.SUCCESS) completedMutation = true
                        }
                    }
                    graph.queue.running[request.id] = job
                    val result = job.await()
                    val conflicts = result.outcomes.filter { it.status == ItemStatus.CONFLICT }
                    graph.database.saveConflicts(request.id, conflicts)
                    val status = when {
                        conflicts.isNotEmpty() -> "CONFLICT"
                        result.outcomes.all { it.status == ItemStatus.SUCCESS || it.status == ItemStatus.SKIPPED } -> "SUCCEEDED"
                        completedMutation || result.outcomes.any { it.status == ItemStatus.SUCCESS || it.destination != null } -> "PARTIAL"
                        else -> "FAILED"
                    }
                    val counts = result.outcomes.groupingBy { it.status }.eachCount().entries.joinToString(" · ") {
                        "${it.value} ${it.key.name.lowercase()}"
                    }
                    // Extracting an archive that sits in the bin leaves it there, so its trash record stays.
                    graph.database.reconcileTrash(request.id,
                        if (request.type == OperationType.EXTRACT_ARCHIVE) emptyList() else result.outcomes)
                    graph.database.finish(request.id, status, conflicts.firstOrNull()?.message ?: counts)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    graph.database.reconcileTrash(request.id, emptyList())
                    val byUser = graph.database.isCancelled(request.id)
                    graph.database.finish(request.id, if (byUser) "CANCELLED" else "INTERRUPTED",
                        if (byUser) "Cancelled. Completed changes remain; inspect the results."
                        else "Background execution stopped. Inspect results and staging data before retrying.")
                }
                currentCoroutineContext().ensureActive()
            } catch (failure: Exception) {
                graph.database.reconcileTrash(request.id, emptyList())
                graph.database.finish(request.id, if (completedMutation) "PARTIAL" else "FAILED", failure.message ?: "Operation failed")
            } finally { graph.queue.running.remove(request.id); graph.queue.forgetSecret(request.id) }
        }
        Result.success()
    }
    private suspend fun record(event: OperationEvent) {
        // Only progress events cancel: the engine's NonCancellable cleanup must still reach its journal writes.
        if (event is OperationEvent.Progress && graph.database.isCancelled(event.requestId)) {
            throw CancellationException("Cancelled by user")
        }
        when (event) {
            is OperationEvent.Started -> graph.database.progress(event.requestId, 0, "", "Starting ${event.totalItems} item(s)")
            is OperationEvent.Progress -> {
                // An extraction does not know its member count up front; totalItems is then 0.
                val items = if (event.totalItems > 0) "Item ${event.completedItems + 1}/${event.totalItems}"
                    else "Item ${event.completedItems + 1}"
                graph.database.progress(event.requestId, event.bytesCopied, event.currentName,
                    items + (event.totalBytes?.let { " · ${event.bytesCopied}/$it bytes" } ?: " · total size unknown"))
                notificationManager().notify(NOTIFICATION_ID, notification("Working on ${event.currentName}"))
            }
            is OperationEvent.Journal -> graph.database.journal(event.requestId,
                "${event.phase.name.lowercase().replace('_', ' ')}: ${event.message}", listOfNotNull(event.artifact),
                if (event.phase != JournalPhase.STAGING || event.artifact == null) event.message else null,
                source = event.source, destination = event.destination)
            is OperationEvent.ItemFinished -> graph.database.journal(event.requestId, event.outcome.message.orEmpty(), event.outcome.artifacts,
                "${event.outcome.status.name.lowercase()}: ${event.outcome.message ?: "Item completed"}" +
                    (event.outcome.error?.let { " ($it)" } ?: ""), source = event.outcome.source, destination = event.outcome.destination)
            is OperationEvent.Finished -> Unit
        }
    }
    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("File operations")
    private fun notificationManager() = applicationContext.getSystemService(NotificationManager::class.java)
    private fun notification(text: String): Notification {
        notificationManager().createNotificationChannel(NotificationChannel(CHANNEL, "File operations", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_operation).setContentTitle("Luna Explorer").setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "Manage queue", open).build()
    }
    private fun foreground(text: String): ForegroundInfo = if (Build.VERSION.SDK_INT >= 29)
        ForegroundInfo(NOTIFICATION_ID, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    else ForegroundInfo(NOTIFICATION_ID, notification(text))
    private companion object { const val CHANNEL = "file_operations"; const val NOTIFICATION_ID = 101 }
}
