package com.lunaexplorer.app.ui

import androidx.compose.ui.test.*
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.ProcedureSchedule
import com.lunaexplorer.app.model.ProcedureScheduleKind
import com.lunaexplorer.app.model.StoredProcedure
import com.lunaexplorer.core.*
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
        clickDescription("Browse Destination")
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
        clickDescription("Browse Source 1")
        for (entry in listOf("Test fixture", fixture.token, "beta.txt")) {
            awaitCondition("Picker lists $entry", 10_000) {
                compose.onAllNodes(hasText(entry) and inPicker).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("procedure-locations").performScrollToNode(hasText(entry))
            compose.onNode(hasText(entry) and inPicker).performClick()
        }
        compose.onNodeWithText("Source 1").assertTextContains(File(fixture.directory, "beta.txt").absolutePath)
        click("Add source")
        replace("Source 2", File(fixture.directory, "gamma.txt").absolutePath)
        click("Add source")
        replace("Source 3", File(fixture.directory, "alpha/alpha-note.txt").absolutePath)
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
        assertEquals(3, saved.steps.last().sources.size)
        assertEquals(File(fixture.directory, ".hidden.txt").absolutePath,
            fixture.graph.local.pathOf(saved.steps.last().sources[1].location.ref))
        assertEquals(File(fixture.directory, "alpha/alpha-note.txt").absolutePath,
            fixture.graph.local.pathOf(saved.steps.last().sources.last().location.ref))
        assertEquals(ProcedureScheduleKind.INTERVAL, saved.schedule?.kind)
        assertEquals(30L, saved.schedule?.intervalMinutes)
        assertTrue(saved.schedule?.enabled == true)
        assertTrue(saved.notifyOnFailure)
        assertEquals(saved, runBlocking { fixture.graph.procedures.get(saved.id) })

        clickDescription("Edit   Nightly  ")
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
    fun savesOptionalSourcesAndMovesIntoGeneratedFolders() {
        awaitListing()
        val source = File(fixture.directory, "beta.txt")
        val content = source.readText()
        openSettingsPage("Stored procedures")
        click("Add procedure")
        replace("Procedure name", "Daily move")
        click("Add step")
        chooseAction("Create folder")
        replace("Destination", fixture.directory.absolutePath)
        replace("New name", "daily-")
        clickDescription("Insert {date} into New name")
        click("Save step")
        awaitText("1. Create folder")

        click("Add step")
        chooseAction("Move")
        replace("Source 1", source.absolutePath)
        click("Add source")
        replace("Source 2", File(fixture.directory, "absent/folder").absolutePath)
        click("Ignore missing sources")
        replace("Destination", File(fixture.directory, "daily-{date}").absolutePath + "/")
        clickDescription("Insert {time} into Destination")
        click("Create missing folders")
        click("Save step")
        awaitText("2. Move")
        click("Save procedure")
        awaitCondition("Procedure saved", 10_000) { fixture.graph.procedures.procedures.value.size == 1 }
        val saved = fixture.graph.procedures.procedures.value.single()
        assertEquals("daily-{date}", saved.steps.first().name)
        assertTrue(saved.steps.last().ignoreMissingSources)
        assertEquals(listOf("daily-{date}", "{time}"), saved.steps.last().destination!!.children.takeLast(2))

        clickDescription("Edit Daily move")
        clickDescription("Edit step 2")
        compose.onNodeWithContentDescription("Ignore missing sources").performScrollTo().assertIsOn()
        compose.onNodeWithText("Source 2").performScrollTo()
            .assertTextContains(File(fixture.directory, "absent/folder").absolutePath)
        compose.onNodeWithText("Destination").performScrollTo()
            .assertTextContains(File(fixture.directory, "daily-{date}/{time}").absolutePath)
        click("Save step")
        awaitText("2. Move")
        click("Save procedure")
        clickDescription("Run Daily move")
        awaitCondition("Procedure finished", 15_000) {
            fixture.graph.database.procedureRuns.value.any { it.procedureId == saved.id && it.status == "SUCCEEDED" }
        }
        val dated = fixture.directory.listFiles()!!.single { it.name.matches(Regex("daily-\\d{4}-\\d{2}-\\d{2}")) }
        val timed = dated.listFiles()!!.single { it.name.matches(Regex("\\d{2}-\\d{2}-\\d{2}")) }
        assertEquals(content, File(timed, "beta.txt").readText())
        assertTrue(!source.exists())
    }

    @Test
    fun editsConditionalStopAndKeepsReferencesWhenStepsMove() {
        awaitListing()
        val root = requireNotNull(fixture.graph.local.refFor(fixture.directory.absolutePath))
        val source = requireNotNull(fixture.graph.local.refFor(File(fixture.directory, "beta.txt").absolutePath))
        val first = ProcedureStep(OperationType.COPY, sources = listOf(ProcedureSource(ProcedureLocation(source))),
            destination = ProcedureLocation(root))
        val second = ProcedureStep(OperationType.CREATE_FOLDER, destination = ProcedureLocation(root), name = "spare",
            onFailure = ProcedureFailurePolicy.CONTINUE)
        val procedure = StoredProcedure(name = "Sort downloads", steps = listOf(first, second))
        runBlocking { fixture.graph.procedures.save(procedure) }
        openSettingsPage("Stored procedures")
        clickDescription("Edit Sort downloads")
        clickDescription("Edit step 1")
        compose.onNodeWithTag("procedure-source-folder-0").performScrollTo().performClick()
        replace("Source 1", fixture.directory.absolutePath)
        compose.onNodeWithTag("procedure-source-pattern-0").performScrollTo().performTextReplacement("*.txt")
        click("Include subfolders 1")
        replace("Destination", File(fixture.directory, " new /daily").absolutePath)
        click("Create missing folders")
        click("On failure: Stop procedure")
        compose.onNodeWithText("Continue").performClick()
        click("Step options")
        replace("Step label (optional)", "Gather")
        click("Save step")
        awaitText("1. Gather · Copy")

        click("Add step")
        chooseAction("Stop procedure")
        compose.onNodeWithText("Source 1").assertDoesNotExist()
        compose.onNodeWithText("Destination").assertDoesNotExist()
        click("Conditions")
        click("Step: 2. Create folder")
        compose.onNodeWithText("1. Gather · Copy").performClick()
        click("Result: Succeeded")
        compose.onNodeWithText("No completed items").performClick()
        click("Add condition")
        click("Result: Succeeded")
        compose.onNodeWithText("Failed").performClick()
        click("Match: All conditions")
        compose.onNodeWithText("Any condition").performClick()
        click("Save step")
        awaitText("3. Stop procedure")

        clickDescription("Move step 1 down")
        compose.onNodeWithText("2. Gather · Copy").assertExists()
        clickDescription("Move step 3 up")
        compose.onNodeWithText("A condition must follow the step it checks").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("3. Stop procedure").assertExists()
        clickDescription("Remove step 2")
        compose.onNodeWithText("Remove conditions that use step 2 first").performScrollTo().assertIsDisplayed()
        click("Save procedure")
        awaitCondition("Conditional procedure saved", 10_000) {
            fixture.graph.procedures.procedures.value.single().steps.size == 3
        }
        val saved = fixture.graph.procedures.procedures.value.single()
        assertEquals(listOf(second.id, first.id), saved.steps.take(2).map { it.id })
        val gather = saved.steps[1]
        assertEquals("Gather", gather.label)
        assertEquals("*.txt", gather.sources.single().pattern)
        assertTrue(gather.sources.single().recursive)
        assertTrue(gather.createDestination)
        assertEquals(listOf(" new ", "daily"), gather.destination!!.children.takeLast(2))
        assertEquals(ProcedureFailurePolicy.CONTINUE, gather.onFailure)
        val stop = saved.steps.last()
        assertEquals(ProcedureControl.STOP, stop.control)
        assertEquals(ProcedureConditionMatch.ANY, stop.conditionMatch)
        assertEquals(listOf(ProcedureCondition(first.id, ProcedureConditionTest.NO_OUTPUT),
            ProcedureCondition(second.id, ProcedureConditionTest.FAILED)), stop.conditions)

        clickDescription("Edit Sort downloads")
        clickDescription("Edit step 3")
        click("Always")
        click("Save step")
        awaitText("3. Stop procedure")
        clickDescription("Remove step 2")
        click("Save procedure")
        awaitCondition("Unused step removed", 10_000) {
            fixture.graph.procedures.procedures.value.single().steps.size == 2
        }
        val edited = fixture.graph.procedures.procedures.value.single()
        assertEquals(stop.id, edited.steps.last().id)
        assertTrue(edited.steps.last().conditions.isEmpty())
    }

    @Test
    fun runsOrderedActionsAndShowsTheirHistory() {
        awaitListing()
        val root = requireNotNull(fixture.graph.local.refFor(fixture.directory.absolutePath))
        val source = requireNotNull(fixture.graph.local.refFor(File(fixture.directory, "beta.txt").absolutePath))
        val copy = ProcedureStep(OperationType.COPY, sources = listOf(ProcedureSource(ProcedureLocation(source))),
            destination = ProcedureLocation(root, listOf("output", "daily")), createDestination = true)
        val procedure = StoredProcedure(name = "File beta", steps = listOf(copy,
            ProcedureStep(OperationType.DELETE, sources = listOf(ProcedureSource(ProcedureLocation(source))),
                conditions = listOf(ProcedureCondition(copy.id, ProcedureConditionTest.NO_OUTPUT))),
            ProcedureStep(control = ProcedureControl.STOP,
                conditions = listOf(ProcedureCondition(copy.id, ProcedureConditionTest.HAS_OUTPUT))),
            ProcedureStep(OperationType.DELETE, sources = listOf(ProcedureSource(ProcedureLocation(source)))),
        ))
        runBlocking { fixture.graph.procedures.save(procedure) }
        openSettingsPage("Stored procedures")
        clickDescription("Run File beta")
        awaitCondition("Procedure finished", 15_000) {
            fixture.graph.database.procedureRuns.value.any { it.procedureId == procedure.id && it.status == "SUCCEEDED" }
        }
        assertEquals(File(fixture.directory, "beta.txt").readText(), File(fixture.directory, "output/daily/beta.txt").readText())
        assertTrue(File(fixture.directory, "beta.txt").exists())
        clickDescription("History File beta")
        compose.onNodeWithText("Last run: succeeded").assertExists()
        compose.onNodeWithText("Report").performScrollTo().assertExists()
        val run = fixture.graph.database.procedureRuns.value.single { it.procedureId == procedure.id }
        val reportFile = File(fixture.directory, "procedure-report.txt")
        runBlocking { fixture.graph.database.exportReport(run.id, reportFile) }
        val report = reportFile.readText()
        assertTrue(report.contains("Stopped by step 3"))
        assertTrue(report.contains("skipped (condition not met)"))
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
        clickDescription("Run Missing source")
        awaitCondition("Failed procedure recorded", 15_000) {
            fixture.graph.database.procedureRuns.value.any { it.procedureId == procedure.id && it.status == "FAILED" }
        }
        compose.onNodeWithText("Schedule paused").assertExists()
        clickDescription("History Missing source")
        compose.onNodeWithText("Last run: failed").assertExists()
        compose.onNodeWithText("Report").performScrollTo().assertExists()
        assertTrue(File(fixture.directory, "beta.txt").exists())
    }

    private fun click(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }
    private fun clickDescription(description: String) {
        awaitCondition("$description is available", 10_000) {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(description).performScrollTo().performClick()
    }
    private fun replace(label: String, text: String) {
        compose.onNodeWithText(label).performScrollTo().performTextReplacement(text)
    }
    private fun chooseAction(label: String) {
        click("Action: Copy")
        compose.onNodeWithText(label).performClick()
    }
}
