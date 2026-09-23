package com.lunaexplorer.app.ui

import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.StoredProcedure
import com.lunaexplorer.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class, qualifiers = "w393dp-h852dp-xhdpi")
class ProcedureCloneUiTest : RobolectricBrowserUiTest() {
    @Test
    fun clonesConfiguredStepWithoutChangingItsSourcesOrReferences() {
        awaitListing()
        val root = location(fixture.directory)
        val procedures = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java].procedures }
        val destination = runBlocking { procedures.resolve(root.copy(children = listOf("new", "daily")), allowMissing = true) }
        val first = ProcedureStep(OperationType.CREATE_FOLDER, destination = root, name = "ready",
            onFailure = ProcedureFailurePolicy.CONTINUE)
        val move = ProcedureStep(OperationType.MOVE, label = "Gather",
            sources = listOf(ProcedureSource(root, "*.txt", recursive = true, kind = ProcedureEntryKind.ALL),
                ProcedureSource(location(File(fixture.directory, "beta.txt")))),
            destination = destination, createDestination = true,
            conflictPolicy = ConflictPolicy.KEEP_BOTH, keepVersions = true,
            conditions = listOf(ProcedureCondition(first.id, ProcedureConditionTest.SUCCEEDED),
                ProcedureCondition(first.id, ProcedureConditionTest.FAILED)),
            conditionMatch = ProcedureConditionMatch.ANY, onFailure = ProcedureFailurePolicy.CONTINUE)
        val stop = ProcedureStep(control = ProcedureControl.STOP,
            conditions = listOf(ProcedureCondition(move.id, ProcedureConditionTest.NO_OUTPUT)))
        val procedure = StoredProcedure(name = "Clone actions", steps = listOf(first, move, stop))
        runBlocking { fixture.graph.procedures.save(procedure) }

        openSettingsPage("Stored procedures")
        clickDescription("Edit Clone actions")
        clickDescription("Clone step 2")
        compose.onNodeWithText("Step 3").assertExists()
        val replacement = File(fixture.directory, "gamma.txt")
        replace("Source 2", replacement.absolutePath)
        click("Step options")
        replace("Step label (optional)", "Gather again")
        click("Save step")
        awaitText("3. Gather again · Move")
        compose.onNodeWithText("4. Stop procedure").assertExists()
        click("Save procedure")
        awaitCondition("Cloned step saved", 10_000) {
            fixture.graph.procedures.procedures.value.single().steps.size == 4
        }

        val saved = runBlocking { requireNotNull(fixture.graph.procedures.get(procedure.id)) }
        assertEquals(listOf(first, move, stop), listOf(saved.steps[0], saved.steps[1], saved.steps[3]))
        val clone = saved.steps[2]
        assertNotEquals(move.id, clone.id)
        assertEquals(4, saved.steps.map { it.id }.toSet().size)
        assertEquals(move.copy(id = clone.id, label = "Gather again",
            sources = listOf(move.sources[0], ProcedureSource(location(replacement)))), clone)

        clickDescription("Edit Clone actions")
        clickDescription("Edit step 3")
        compose.onNodeWithText("Source 2").performScrollTo().assertTextContains(replacement.absolutePath)
        click("Save step")
        awaitText("3. Gather again · Move")
        click("Save procedure")
        awaitCondition("Procedure editor closed", 10_000) {
            compose.onAllNodesWithContentDescription("Edit Clone actions").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(saved.steps, runBlocking { requireNotNull(fixture.graph.procedures.get(procedure.id)).steps })
    }

    @Test
    fun cancelsCloneDraftAndCanCloneAConditionalStop() {
        awaitListing()
        val first = ProcedureStep(OperationType.CREATE_FOLDER, destination = location(fixture.directory), name = "ready")
        val stop = ProcedureStep(control = ProcedureControl.STOP,
            conditions = listOf(ProcedureCondition(first.id, ProcedureConditionTest.NO_OUTPUT)))
        val procedure = StoredProcedure(name = "Clone stop", steps = listOf(first, stop))
        runBlocking { fixture.graph.procedures.save(procedure) }

        openSettingsPage("Stored procedures")
        clickDescription("Edit Clone stop")
        clickDescription("Clone step 2")
        click("Cancel step")
        compose.onNodeWithContentDescription("Edit step 3").assertDoesNotExist()
        click("Save procedure")
        awaitCondition("Cancelled clone discarded", 10_000) {
            compose.onAllNodesWithContentDescription("Edit Clone stop").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(procedure.steps, runBlocking { requireNotNull(fixture.graph.procedures.get(procedure.id)).steps })

        clickDescription("Edit Clone stop")
        clickDescription("Clone step 2")
        click("Save step")
        awaitText("3. Stop procedure")
        click("Save procedure")
        awaitCondition("Stop clone saved", 10_000) {
            fixture.graph.procedures.procedures.value.single().steps.size == 3
        }
        val saved = runBlocking { requireNotNull(fixture.graph.procedures.get(procedure.id)) }
        assertEquals(procedure.steps, saved.steps.take(2))
        val clone = saved.steps.last()
        assertNotEquals(stop.id, clone.id)
        assertEquals(stop.copy(id = clone.id), clone)
    }

    private fun location(file: File) = ProcedureLocation(requireNotNull(fixture.graph.local.refFor(file.absolutePath)))

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
}
