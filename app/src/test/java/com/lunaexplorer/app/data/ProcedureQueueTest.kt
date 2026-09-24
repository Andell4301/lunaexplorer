package com.lunaexplorer.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.OperationRequest
import com.lunaexplorer.core.OperationType
import com.lunaexplorer.core.ProcedureLocation
import com.lunaexplorer.core.ProcedureSource
import com.lunaexplorer.core.ProcedureStep
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProcedureQueueTest {
    private lateinit var context: Context
    private lateinit var database: LunaDatabase
    private lateinit var databaseName: String
    private val source = NodeRef("test", "opaque-source")
    private val step = ProcedureStep(OperationType.DELETE,
        listOf(ProcedureSource(ProcedureLocation(source, listOf("{date}")), pattern = "{time}-*.tmp")),
        ignoreMissingSources = true)

    @Before fun createDatabase() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "procedure-queue-${UUID.randomUUID()}.db"
        database = LunaDatabase(context, databaseName)
    }

    @After fun removeDatabase() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    private fun request(id: String = UUID.randomUUID().toString(), procedure: String = "clean") =
        OperationRequest(id = id, type = OperationType.PROCEDURE, procedureId = procedure,
            steps = listOf(step), notifyOnSuccess = true, notifyOnFailure = true)

    private fun reopen() {
        database.close()
        database = LunaDatabase(context, databaseName)
    }

    @Test fun `a scheduled occurrence is queued only once even after completion and reopening`() = runBlocking {
        val occurrence = request(id = "procedure:clean:1000")
        assertTrue(database.enqueueProcedure(occurrence, "Clean", scheduled = true))
        reopen()
        assertFalse(database.enqueueProcedure(occurrence, "Clean", scheduled = true))
        assertEquals(occurrence, database.claimNext())
        database.finish(occurrence.id, "SUCCEEDED", "Completed")
        reopen()
        assertFalse(database.enqueueProcedure(occurrence, "Clean", scheduled = true))
        assertNull(database.claimNext())
        assertEquals(occurrence, database.request(occurrence.id))
        assertEquals(listOf(occurrence.id), database.procedureRuns.value.map { it.id })
    }

    @Test fun `queued and running procedures reject overlap while another procedure can queue`() = runBlocking {
        val first = request()
        assertTrue(database.enqueueProcedure(first, "Clean"))
        assertFalse(database.enqueueProcedure(request(), "Clean"))
        assertEquals(first, database.claimNext())
        assertFalse(database.enqueueProcedure(request(), "Clean", scheduled = true))
        val other = request(procedure = "archive")
        assertTrue(database.enqueueProcedure(other, "Archive"))
        assertEquals(other, database.claimNext())
        database.finish(first.id, "SUCCEEDED", "Completed")
        assertTrue(database.enqueueProcedure(request(), "Clean", scheduled = true))
    }

    @Test fun `failed partial and interrupted runs pause schedules until a manual run succeeds`() = runBlocking {
        for (status in listOf("FAILED", "PARTIAL", "INTERRUPTED", "CANCELLED")) {
            val procedure = "clean-$status"
            val first = request(procedure = procedure)
            assertTrue(database.enqueueProcedure(first, procedure, scheduled = true))
            assertEquals(first, database.claimNext())
            database.finish(first.id, status, "Inspect results")
            reopen()
            assertFalse(database.enqueueProcedure(request(procedure = procedure), procedure, scheduled = true))
            val manual = request(procedure = procedure)
            assertTrue(database.enqueueProcedure(manual, procedure))
            assertEquals(manual, database.claimNext())
            database.finish(manual.id, "SUCCEEDED", "Completed")
            val resumed = request(procedure = procedure)
            assertTrue(database.enqueueProcedure(resumed, procedure, scheduled = true))
            assertEquals(resumed, database.claimNext())
            database.finish(resumed.id, "SUCCEEDED", "Completed")
        }
    }

    @Test fun `interrupted recovery preserves history and leaves ordinary queued operations runnable`() = runBlocking {
        val procedure = request()
        database.enqueueProcedure(procedure, "Clean")
        database.claimNext()
        database.journal(procedure.id, "Deleted first item", emptyList(), source = source)
        val ordinary = OperationRequest(type = OperationType.DELETE, sources = listOf(source))
        database.enqueue(ordinary, "Delete")
        reopen()

        assertEquals(listOf(procedure), database.recoverInterrupted())
        val history = database.procedureRuns.value.single()
        assertEquals(procedure.id, history.id)
        assertEquals(procedure.procedureId, history.procedureId)
        assertEquals("INTERRUPTED", history.status)
        assertTrue(history.detail.isNotEmpty())
        assertEquals(listOf("Deleted first item"), database.queue.value.first { it.id == procedure.id }.results)
        assertFalse(database.enqueueProcedure(request(), "Clean", scheduled = true))
        assertEquals(ordinary, database.claimNext())
        database.finish(ordinary.id, "SUCCEEDED", "Deleted")
        assertNull(database.claimNext())
        assertEquals(listOf(procedure.id), database.procedureRuns.value.map { it.id })
    }
}
