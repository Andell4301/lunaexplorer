package com.lunaexplorer.app.work

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.lunaexplorer.app.AppGraph
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.data.ProcedureStore
import com.lunaexplorer.app.model.ProcedureSchedule
import com.lunaexplorer.app.model.ProcedureScheduleKind
import com.lunaexplorer.app.model.StoredProcedure
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.OperationType
import com.lunaexplorer.core.ProcedureLocation
import com.lunaexplorer.core.ProcedureSource
import com.lunaexplorer.core.ProcedureStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class, sdk = [35])
class ProcedureSchedulerTest {
    private lateinit var application: LunaApplication
    private lateinit var graph: AppGraph
    private lateinit var store: ProcedureStore
    private lateinit var scheduler: ProcedureScheduler
    private lateinit var manager: WorkManager
    private val step = ProcedureStep(OperationType.DELETE,
        listOf(ProcedureSource(ProcedureLocation(NodeRef("test", "selected-item")))))

    @Before fun prepare() {
        application = RuntimeEnvironment.getApplication() as LunaApplication
        Dispatchers.setMain(Handler(Looper.getMainLooper()).asCoroutineDispatcher())
        val executor = SynchronousExecutor()
        WorkManagerTestInitHelper.initializeTestWorkManager(application, Configuration.Builder()
            .setExecutor(executor).setTaskExecutor(executor)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    object : Worker(appContext, workerParameters) {
                        override fun doWork(): Result = Result.success()
                    }
            }).build())
        manager = WorkManager.getInstance(application)
        graph = application.graph
        val savedAt = Instant.now().minusSeconds(120)
        store = ProcedureStore(application, now = { savedAt })
        scheduler = ProcedureScheduler(application, store)
    }

    @After fun release() {
        try {
            manager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            val processor = WorkManagerImpl.getInstance(application).processor
            do {
                shadowOf(Looper.getMainLooper()).idle()
                check(System.nanoTime() < deadline) { "WorkManager did not stop" }
            } while (processor.hasWork())
            WorkManagerTestInitHelper.closeWorkDatabase()
        } finally {
            store.close()
            graph.procedures.close()
            graph.database.close()
            graph.debugLog.close()
            Dispatchers.resetMain()
        }
    }

    private fun work(name: String): List<WorkInfo> = manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)

    private fun recurring() = StoredProcedure(name = "Scheduled cleanup", steps = listOf(step), schedule =
        ProcedureSchedule(enabled = true, kind = ProcedureScheduleKind.INTERVAL, intervalMinutes = 15))

    private suspend fun scan(): ListenableWorker.Result =
        TestListenableWorkerBuilder<ProcedureScheduleWorker>(application).build().doWork()

    @Test fun `enabling a schedule registers one periodic scan and an immediate check`() = runBlocking {
        store.save(recurring())

        scheduler.synchronize()
        val original = work("luna-procedure-schedules").single { !it.state.isFinished }
        assertTrue(work("luna-procedure-check").isNotEmpty())
        scheduler.synchronize()

        assertEquals(original.id, work("luna-procedure-schedules").single { !it.state.isFinished }.id)
        assertTrue(graph.database.procedureRuns.value.isEmpty())
    }

    @Test fun `disabling or removing the last schedule cancels pending scans`() = runBlocking {
        val procedure = recurring()
        store.save(procedure)
        scheduler.synchronize()

        store.save(procedure.copy(schedule = procedure.schedule!!.copy(enabled = false)))
        scheduler.synchronize()
        assertTrue(work("luna-procedure-schedules").all { it.state.isFinished })
        assertTrue(work("luna-procedure-check").all { it.state.isFinished })

        store.save(procedure)
        scheduler.synchronize()
        assertEquals(1, work("luna-procedure-schedules").count { !it.state.isFinished })
        store.remove(procedure.id)
        scheduler.synchronize()
        assertTrue(work("luna-procedure-schedules").all { it.state.isFinished })
        assertTrue(work("luna-procedure-check").all { it.state.isFinished })
    }

    @Test fun `the worker queues a due occurrence once and stops scanning after a one-time run`() = runBlocking {
        val procedure = StoredProcedure(name = "Once", steps = listOf(step), schedule = ProcedureSchedule(
            enabled = true, kind = ProcedureScheduleKind.ONCE, atMillis = Instant.now().plusSeconds(60).toEpochMilli()))
        store.save(procedure)
        scheduler.synchronize()

        assertEquals(ListenableWorker.Result.success(), scan())
        assertTrue(graph.database.procedureRuns.value.isEmpty())
        assertTrue(work("luna-procedure-schedules").any { !it.state.isFinished })

        val due = procedure.copy(schedule = procedure.schedule!!.copy(atMillis = Instant.now().minusSeconds(60).toEpochMilli()))
        store.save(due)
        assertEquals(ListenableWorker.Result.success(), scan())
        assertEquals(ListenableWorker.Result.success(), scan())

        val run = graph.database.procedureRuns.value.single()
        assertEquals(procedure.id, run.procedureId)
        assertEquals("QUEUED", run.status)
        assertEquals(procedure.steps, graph.database.request(run.id)?.steps)
        assertFalse(store.hasScheduled())
        assertTrue(work("luna-procedure-schedules").all { it.state.isFinished })
        assertTrue(manager.getWorkInfosByTag("luna-file-operations").get(5, TimeUnit.SECONDS).isNotEmpty())
    }

    @Test fun `the worker observes disabled and removed procedures before dispatch`() = runBlocking {
        val due = ProcedureSchedule(enabled = true, kind = ProcedureScheduleKind.ONCE,
            atMillis = Instant.now().minusSeconds(60).toEpochMilli())
        val disabled = StoredProcedure(name = "Disabled", steps = listOf(step), schedule = due)
        val removed = StoredProcedure(name = "Removed", steps = listOf(step), schedule = due)
        store.save(disabled)
        store.save(removed)
        scheduler.synchronize()
        store.save(disabled.copy(schedule = due.copy(enabled = false)))
        store.remove(removed.id)

        assertEquals(ListenableWorker.Result.success(), scan())

        assertTrue(graph.database.procedureRuns.value.isEmpty())
        assertTrue(work("luna-procedure-schedules").all { it.state.isFinished })
        assertTrue(manager.getWorkInfosByTag("luna-file-operations").get(5, TimeUnit.SECONDS).isEmpty())
    }
}
