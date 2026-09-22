package com.lunaexplorer.app.ui

import androidx.compose.ui.test.*
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.ProcedureSchedule
import com.lunaexplorer.app.model.ProcedureScheduleKind
import com.lunaexplorer.app.model.StoredProcedure
import com.lunaexplorer.core.OperationType
import com.lunaexplorer.core.ProcedureLocation
import com.lunaexplorer.core.ProcedureSource
import com.lunaexplorer.core.ProcedureStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class, qualifiers = "w393dp-h852dp-xhdpi")
class ProcedureSettingsUiTest : RobolectricBrowserUiTest() {
    @Test
    fun savesAndEditsOrderedActionsWithMultipleSourcesAndASchedule() {
        awaitListing()
        openSettingsPage("Stored procedures")
        click("Add procedure")
        compose.onNodeWithText("Procedure name").performTextReplacement("  Nightly  ")
        click("Add step")
        chooseAction("Create folder")
        click("Browse Destination")
        val inPicker = hasAnyAncestor(hasTestTag("procedure-locations"))
        compose.onNodeWithTag("procedure-locations").performScrollToNode(hasText("Test fixture"))
        compose.onNode(hasText("Test fixture") and inPicker).performClick()
        awaitCondition("Picker folder loaded", 10_000) {
            compose.onAllNodes(hasText(fixture.token) and inPicker).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("procedure-locations").performScrollToNode(hasText(fixture.token))
        compose.onNode(hasText(fixture.token) and inPicker).performClick()
        click("Choose this folder")
        replace("New name", " target ")
        click("Save step")
        awaitText("1. Create folder")

        click("Add step")
        replace("Source 1", File(fixture.directory, "beta.txt").absolutePath)
        click("Add source")
        replace("Source 2", File(fixture.directory, "gamma.txt").absolutePath)
        replace("Destination", File(fixture.directory, " target ").absolutePath)
        click("Save step")
        awaitText("2. Copy")
        compose.onNodeWithContentDescription("Move step 2 up").performScrollTo().performClick()
        compose.onNodeWithText("1. Copy").assertExists()
        compose.onNodeWithContentDescription("Move step 1 down").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Edit step 2").performScrollTo().performClick()
        replace("Source 2", File(fixture.directory, ".hidden.txt").absolutePath)
        click("Save step")
        awaitText("2. Copy")

        click("Schedule")
        click("Repeat: Daily")
        compose.onNodeWithText("Interval").performClick()
        replace("Minutes (15 minimum)", "1")
        compose.onNodeWithText("Save procedure").assertIsNotEnabled()
        replace("Minutes (15 minimum)", "999999999999999999")
        compose.onNodeWithText("Save procedure").assertIsNotEnabled()
        replace("Minutes (15 minimum)", "30")
        click("Notify on failure")
        click("Save procedure")
        awaitCondition("Procedure saved", 10_000) { fixture.graph.procedures.procedures.value.size == 1 }
        val saved = fixture.graph.procedures.procedures.value.single()
        assertEquals("  Nightly  ", saved.name)
        assertEquals(listOf(OperationType.CREATE_FOLDER, OperationType.COPY), saved.steps.map { it.type })
        assertEquals(" target ", saved.steps.first().name)
        assertEquals(2, saved.steps.last().sources.size)
        assertEquals(File(fixture.directory, ".hidden.txt").absolutePath,
            fixture.graph.local.pathOf(saved.steps.last().sources.last().location.ref))
        assertEquals(ProcedureScheduleKind.INTERVAL, saved.schedule?.kind)
        assertEquals(30L, saved.schedule?.intervalMinutes)
        assertTrue(saved.schedule?.enabled == true)
        assertTrue(saved.notifyOnFailure)
        assertEquals(saved, runBlocking { fixture.graph.procedures.get(saved.id) })

        click("Edit   Nightly  ")
        compose.onNodeWithContentDescription("Remove step 2").performScrollTo().performClick()
        replace("Minutes (15 minimum)", "0")
        compose.onNodeWithText("Save procedure").assertIsNotEnabled()
        click("Schedule")
        click("Save procedure")
        awaitCondition("Edited procedure saved", 10_000) { fixture.graph.procedures.procedures.value.single().steps.size == 1 }
        val edited = fixture.graph.procedures.procedures.value.single()
        assertEquals(false, edited.schedule?.enabled)
        assertEquals(30L, edited.schedule?.intervalMinutes)
    }

    @Test
    fun runsOrderedActionsAndShowsTheirHistory() {
        awaitListing()
        val root = requireNotNull(fixture.graph.local.refFor(fixture.directory.absolutePath))
        val source = requireNotNull(fixture.graph.local.refFor(File(fixture.directory, "beta.txt").absolutePath))
        val procedure = StoredProcedure(name = "File beta", steps = listOf(
            ProcedureStep(OperationType.CREATE_FOLDER, destination = ProcedureLocation(root), name = "output"),
            ProcedureStep(OperationType.COPY, sources = listOf(ProcedureSource(ProcedureLocation(source))),
                destination = ProcedureLocation(root, listOf("output"))),
        ))
        runBlocking { fixture.graph.procedures.save(procedure) }
        openSettingsPage("Stored procedures")
        click("Run File beta")
        awaitCondition("Procedure finished", 15_000) {
            fixture.graph.database.procedureRuns.value.any { it.procedureId == procedure.id && it.status == "SUCCEEDED" }
        }
        assertEquals(File(fixture.directory, "beta.txt").readText(), File(fixture.directory, "output/beta.txt").readText())
        click("History File beta")
        compose.onNodeWithText("Last run: succeeded").assertExists()
        compose.onNodeWithText("Report").performScrollTo().assertExists()
    }

    @Test
    fun showsFailedRunsAndPausedSchedules() {
        awaitListing()
        val root = requireNotNull(fixture.graph.local.refFor(fixture.directory.absolutePath))
        val procedure = StoredProcedure(name = "Missing source", steps = listOf(
            ProcedureStep(OperationType.DELETE,
                sources = listOf(ProcedureSource(ProcedureLocation(root, listOf("missing"))))),
        ), schedule = ProcedureSchedule(enabled = true, kind = ProcedureScheduleKind.INTERVAL, intervalMinutes = 60))
        runBlocking { fixture.graph.procedures.save(procedure) }
        openSettingsPage("Stored procedures")
        click("Run Missing source")
        awaitCondition("Failed procedure recorded", 15_000) {
            fixture.graph.database.procedureRuns.value.any { it.procedureId == procedure.id && it.status == "FAILED" }
        }
        compose.onNodeWithText("Schedule paused").assertExists()
        click("History Missing source")
        compose.onNodeWithText("Last run: failed").assertExists()
        compose.onNodeWithText("Report").performScrollTo().assertExists()
        assertTrue(File(fixture.directory, "beta.txt").exists())
    }

    private fun click(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }
    private fun replace(label: String, text: String) {
        compose.onNodeWithText(label).performScrollTo().performTextReplacement(text)
    }
    private fun chooseAction(label: String) {
        click("Action: Copy")
        compose.onNodeWithText(label).performClick()
    }
}
