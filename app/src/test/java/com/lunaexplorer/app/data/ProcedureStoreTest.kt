package com.lunaexplorer.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lunaexplorer.app.model.ProcedureSchedule
import com.lunaexplorer.app.model.ProcedureScheduleKind
import com.lunaexplorer.app.model.StoredProcedure
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.OperationType
import com.lunaexplorer.core.ProcedureLocation
import com.lunaexplorer.core.ProcedureSource
import com.lunaexplorer.core.ProcedureStep
import com.lunaexplorer.core.ProcedureCondition
import com.lunaexplorer.core.ProcedureConditionTest
import com.lunaexplorer.core.ProcedureControl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProcedureStoreTest {
    private lateinit var context: Context
    private lateinit var store: ProcedureStore
    private lateinit var databaseName: String
    private var now = Instant.parse("2026-09-21T10:00:00Z")
    private val step = ProcedureStep(OperationType.DELETE,
        listOf(ProcedureSource(ProcedureLocation(NodeRef("test", "opaque-ref"), listOf("{date}")),
            pattern = "{time}-*.tmp")), ignoreMissingSources = true)
    private val schedule = ProcedureSchedule(enabled = true, kind = ProcedureScheduleKind.INTERVAL,
        intervalMinutes = 15)

    @Before fun createStore() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "procedures-test-${UUID.randomUUID()}.db"
        store = ProcedureStore(context, databaseName) { now }
    }

    @After fun removeStore() {
        store.close()
        context.deleteDatabase(databaseName)
    }

    private fun reopen() {
        store.close()
        store = ProcedureStore(context, databaseName) { now }
    }

    @Test fun `editing a procedure preserves its place and scheduled occurrence after reopening`() = runBlocking {
        val stop = ProcedureStep(control = ProcedureControl.STOP, conditions = listOf(
            ProcedureCondition(step.id, ProcedureConditionTest.NO_OUTPUT)))
        val first = StoredProcedure(name = " First ", steps = listOf(step, stop), schedule = schedule,
            notifyOnFailure = true)
        val second = StoredProcedure(name = "Second", steps = listOf(step))
        store.save(first)
        store.save(second)
        val due = now.plusSeconds(15 * 60)
        now = now.plusSeconds(5 * 60)
        val edited = first.copy(name = " Edited ", notifyOnSuccess = true)
        store.save(edited)
        reopen()
        store.refresh()
        assertEquals(listOf(edited, second), store.procedures.value)
        assertEquals(edited, store.get(first.id))

        val runs = mutableListOf<Pair<StoredProcedure, Long>>()
        store.dispatchDue(due.minusSeconds(1)) { procedure, at -> runs += procedure to at }
        assertTrue(runs.isEmpty())
        store.dispatchDue(due) { procedure, at -> runs += procedure to at }
        assertEquals(listOf(edited to due.toEpochMilli()), runs)
    }

    @Test fun `a delayed scan runs once and advances beyond missed intervals`() = runBlocking {
        val procedure = StoredProcedure(name = "Clean", steps = listOf(step), schedule = schedule)
        store.save(procedure)
        val firstDue = now.plusSeconds(15 * 60)
        now = now.plusSeconds(4 * 60 * 60)
        val runs = mutableListOf<Long>()
        store.dispatchDue(now) { _, at -> runs += at }
        reopen()
        store.dispatchDue(now) { _, at -> runs += at }
        assertEquals(listOf(firstDue.toEpochMilli()), runs)

        store.dispatchDue(now.plusSeconds(15 * 60)) { _, at -> runs += at }
        assertEquals(listOf(firstDue.toEpochMilli(), now.plusSeconds(15 * 60).toEpochMilli()), runs)
    }

    @Test fun `an enqueue failure retains the occurrence for an idempotent retry`() = runBlocking {
        store.save(StoredProcedure(name = "Clean", steps = listOf(step), schedule = schedule))
        now = now.plusSeconds(15 * 60)
        var attempted = 0L
        assertThrows(IOException::class.java) {
            runBlocking {
                store.dispatchDue(now) { _, at -> attempted = at; throw IOException("Unavailable queue") }
            }
        }
        reopen()
        val runs = mutableListOf<Long>()
        store.dispatchDue(now) { _, at -> runs += at }
        assertEquals(listOf(attempted), runs)
    }

    @Test fun `replacing unrelated definitions preserves deadlines and disabling prevents dispatch`() = runBlocking {
        val first = StoredProcedure(name = "First", steps = listOf(step), schedule = schedule)
        val second = StoredProcedure(name = "Second", steps = listOf(step), schedule = schedule)
        store.replace(listOf(first, second))
        val due = now.plusSeconds(15 * 60)
        now = now.plusSeconds(5 * 60)
        store.replace(listOf(first.copy(name = "Changed"), second.copy(schedule = schedule.copy(enabled = false))))
        val runs = mutableListOf<String>()
        store.dispatchDue(due) { procedure, _ -> runs += procedure.id }
        assertEquals(listOf(first.id), runs)

        store.remove(first.id)
        reopen()
        assertNull(store.get(first.id))
        assertEquals(listOf(second.id), store.list().map { it.id })
    }

    @Test fun `a one-time occurrence remains completed after saving and reopening`() = runBlocking {
        val once = StoredProcedure(name = "Once", steps = listOf(step), schedule = ProcedureSchedule(
            enabled = true, kind = ProcedureScheduleKind.ONCE, atMillis = now.plusSeconds(60).toEpochMilli()))
        store.save(once)
        assertTrue(store.hasScheduled())
        now = now.plusSeconds(60)
        val runs = mutableListOf<Long>()
        store.dispatchDue(now) { _, at -> runs += at }
        store.save(once.copy(name = "Renamed"))
        reopen()
        store.dispatchDue(now.plusSeconds(60)) { _, at -> runs += at }
        assertEquals(listOf(now.toEpochMilli()), runs)
        assertFalse(store.hasScheduled())
    }
}
