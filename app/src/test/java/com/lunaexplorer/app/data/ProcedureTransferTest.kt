package com.lunaexplorer.app.data

import com.lunaexplorer.app.model.ProcedureSchedule
import com.lunaexplorer.app.model.StoredProcedure
import com.lunaexplorer.core.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ProcedureTransferTest {
    private val unitId = "procedures.saved"
    private val step = ProcedureStep(OperationType.COPY,
        listOf(ProcedureSource(ProcedureLocation(NodeRef("local", "opaque source"), listOf(" folder ")),
            pattern = " invoice *.pdf", recursive = true)),
        destination = ProcedureLocation(NodeRef("b2", "opaque destination"), listOf(" invoices ")),
        label = " File invoices ", createDestination = true, onFailure = ProcedureFailurePolicy.CONTINUE)
    private val stop = ProcedureStep(control = ProcedureControl.STOP, conditions = listOf(
        ProcedureCondition(step.id, ProcedureConditionTest.FAILED),
        ProcedureCondition(step.id, ProcedureConditionTest.NO_OUTPUT)),
        conditionMatch = ProcedureConditionMatch.ANY)
    private val procedure = StoredProcedure(name = " File invoices ", steps = listOf(step, stop),
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

        val forwardReference = before.copy(procedures = listOf(procedure.copy(steps = listOf(stop, step))))
        val invalidBranches = TransferCodec.export(forwardReference, setOf(unitId), "test", 0)
        val invalidPreview = SettingsPreviewer.of(invalidBranches, before)
        assertEquals(TransferVerdict.UNREADABLE, invalidPreview.rows.single().verdict)
        assertEquals(before, SettingsPreviewer.apply(invalidPreview, before, setOf(unitId)).source)
    }

    @Test fun `legacy steps can gain conditions and retain their references through export`() {
        val legacy = Json.decodeFromString<StoredProcedure>("""
            {"id":"legacy","name":"Clean","steps":[{"type":"DELETE","sources":[
                {"location":{"ref":{"provider":"local","key":"opaque"}}}]}]}
        """)
        legacy.validate()
        val updated = legacy.copy(steps = legacy.steps + ProcedureStep(control = ProcedureControl.STOP,
            conditions = listOf(ProcedureCondition(legacy.steps.single().id, ProcedureConditionTest.NO_OUTPUT))))
        val document = TransferCodec.export(TransferSource(procedures = listOf(updated)), setOf(unitId), "test", 0)
        val decoded = requireNotNull(TransferCodec.decode(TransferCodec.encode(document)))
        val before = TransferSource()
        val preview = SettingsPreviewer.of(decoded, before)

        assertEquals(listOf(updated), SettingsPreviewer.apply(preview, before, setOf(unitId)).source.procedures)
    }
}
