package com.lunaexplorer.app.data

import com.lunaexplorer.app.model.ProcedureSchedule
import com.lunaexplorer.app.model.StoredProcedure
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.OperationType
import com.lunaexplorer.core.ProcedureLocation
import com.lunaexplorer.core.ProcedureSource
import com.lunaexplorer.core.ProcedureStep
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ProcedureTransferTest {
    private val unitId = "procedures.saved"
    private val step = ProcedureStep(OperationType.COPY,
        listOf(ProcedureSource(ProcedureLocation(NodeRef("local", "opaque source"), listOf(" folder ")),
            pattern = " invoice *.pdf", recursive = true)),
        destination = ProcedureLocation(NodeRef("b2", "opaque destination")))
    private val procedure = StoredProcedure(name = " File invoices ", steps = listOf(step),
        schedule = ProcedureSchedule(enabled = true, hour = 7, minute = 30), notifyOnFailure = true)

    @Test fun `procedures export every action and schedule but import schedules disabled`() {
        val source = TransferSource(procedures = listOf(procedure))
        val document = TransferCodec.export(source, setOf(unitId), "test", 0)
        val read = requireNotNull(TransferCodec.decode(TransferCodec.encode(document)))
        val before = TransferSource()
        val preview = SettingsPreviewer.of(read, before)
        assertTrue(preview.rows.single().items.single().detail.isNotEmpty())
        val after = SettingsPreviewer.apply(preview, before, setOf(unitId)).source
        assertEquals(listOf(procedure.copy(schedule = procedure.schedule!!.copy(enabled = false))), after.procedures)
    }

    @Test fun `only selected procedures replace matching definitions and other schedules stay enabled`() {
        val second = procedure.copy(id = "second", name = "Second")
        val before = TransferSource(procedures = listOf(procedure, second))
        val incoming = before.copy(procedures = listOf(procedure.copy(name = "Changed"), second.copy(name = "Other")))
        val document = TransferCodec.export(incoming, setOf(unitId), "test", 0)
        val preview = SettingsPreviewer.of(document, before)
        val after = SettingsPreviewer.apply(preview, before, setOf(unitId), mapOf(unitId to setOf(procedure.id))).source
        assertEquals(listOf(procedure.copy(name = "Changed", schedule = procedure.schedule!!.copy(enabled = false)), second),
            after.procedures)
    }

    @Test fun `invalid procedure definitions are refused without erasing current definitions`() {
        val before = TransferSource(procedures = listOf(procedure))
        val invalid = before.copy(procedures = listOf(procedure.copy(steps = emptyList())))
        val document = TransferCodec.export(invalid, setOf(unitId), "test", 0)
        val preview = SettingsPreviewer.of(document, before)
        assertEquals(TransferVerdict.UNREADABLE, preview.rows.single().verdict)
        assertEquals(before, SettingsPreviewer.apply(preview, before, setOf(unitId)).source)

        val malformed = document.copy(values = mapOf(unitId to JsonPrimitive("invalid")))
        assertEquals(TransferVerdict.UNREADABLE, SettingsPreviewer.of(malformed, before).rows.single().verdict)

        val badName = before.copy(procedures = listOf(procedure.copy(steps = listOf(step.copy(name = "/")))))
        val badNameDocument = TransferCodec.export(badName, setOf(unitId), "test", 0)
        assertEquals(TransferVerdict.UNREADABLE, SettingsPreviewer.of(badNameDocument, before).rows.single().verdict)
    }
}
