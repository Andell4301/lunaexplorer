package com.lunaexplorer.app.ui

import android.net.Uri
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.data.TransferCodec
import com.lunaexplorer.app.model.ProcedureSchedule
import com.lunaexplorer.app.model.StoredProcedure
import com.lunaexplorer.core.OperationType
import com.lunaexplorer.core.ProcedureLocation
import com.lunaexplorer.core.ProcedureSource
import com.lunaexplorer.core.ProcedureStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class StoredProcedureTransferTest {
    @get:Rule val harness = BrowserViewModelHarness()

    @Test fun `settings export and import persist procedure definitions with schedules disabled`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        val source = requireNotNull(harness.graph.local.referenceTo(harness.directory.path))
        val procedure = StoredProcedure(name = "Clean", steps = listOf(ProcedureStep(OperationType.DELETE,
            listOf(ProcedureSource(ProcedureLocation(source), pattern = "*.tmp")))),
            schedule = ProcedureSchedule(enabled = true, hour = 11), notifyOnSuccess = true)
        runBlocking { harness.graph.procedures.save(procedure) }
        val transfer = harness.viewModel.transfer
        val file = File(harness.directory, "settings.json")
        var exported = false
        transfer.export(Uri.fromFile(file), setOf("procedures.saved"), withPasswords = false) { exported = it }
        assertTrue(harness.awaitUntil { exported })
        assertTrue(requireNotNull(TransferCodec.decode(file.readText())).values.containsKey("procedures.saved"))
        runBlocking { harness.graph.procedures.remove(procedure.id) }

        var opened = false
        transfer.openForImport(Uri.fromFile(file)) { opened = it }
        assertTrue(harness.awaitUntil { opened })
        var imported = false
        transfer.import(setOf("procedures.saved"), emptyMap()) { imported = true }
        assertTrue(harness.awaitUntil { imported })
        assertEquals(procedure.copy(schedule = procedure.schedule!!.copy(enabled = false)),
            runBlocking { harness.graph.procedures.get(procedure.id) })
    }
}
