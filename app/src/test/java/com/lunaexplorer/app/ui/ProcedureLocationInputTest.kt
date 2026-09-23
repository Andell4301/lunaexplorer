package com.lunaexplorer.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.StoredProcedure
import com.lunaexplorer.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class, qualifiers = "w393dp-h852dp-xhdpi")
class ProcedureLocationInputTest {
    private val harness = BrowserViewModelHarness()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(harness).around(compose)
    private val procedures get() = harness.viewModel.procedures
    private var input by mutableStateOf(ProcedurePlaceInput())
    private var saved by mutableStateOf<StoredProcedure?>(null)
    private var browsed = false

    private fun show(location: ProcedureLocation) {
        assertTrue(harness.awaitUntil { harness.state.ready })
        input = procedures.input(location)
        compose.setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                Column {
                    ProcedurePlaceField("Source 1", input, procedures, { input = it }, { browsed = true })
                    Button(onClick = {
                        scope.launch {
                            val procedure = StoredProcedure(name = "Selected item", steps = listOf(
                                ProcedureStep(OperationType.DELETE, sources = listOf(ProcedureSource(procedures.resolve(input))))))
                            procedures.save(procedure)
                            saved = procedure
                        }
                    }) { Text("Save") }
                }
            }
        }
    }

    private fun save(): ProcedureLocation {
        compose.runOnIdle { saved = null }
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { saved != null }
        val procedure = requireNotNull(saved)
        assertEquals(procedure, runBlocking { harness.graph.procedures.get(procedure.id) })
        return procedure.steps.single().sources.single().location
    }

    @Test fun `opaque selections preserve literal nested paths and can return to the picked item`() {
        val storage = MemoryStorageProvider("opaque")
        val base = runBlocking { storage.folder(storage.root, "Picked location") }
        harness.graph.providers.register(storage)
        show(ProcedureLocation(base, listOf(" old.txt ")))
        compose.onNodeWithTag("procedure-place-Source 1").assertTextEquals(" old.txt ")

        compose.onNodeWithTag("procedure-place-Source 1").performTextReplacement(" nested / file.txt ")
        val selected = save()
        assertEquals(ProcedureLocation(base, listOf(" nested ", " file.txt ")), selected)

        compose.runOnIdle { input = procedures.input(selected) }
        compose.onNodeWithTag("procedure-place-Source 1").assertTextEquals(" nested / file.txt ")
        compose.onNodeWithTag("procedure-place-Source 1").performTextClearance()
        assertEquals(ProcedureLocation(base), save())
        compose.onNodeWithContentDescription("Browse Source 1").performClick()
        assertTrue(browsed)
    }

    @Test fun `ordinary full paths keep a selected reference until edited and retain literal file names`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        val base = runBlocking { harness.graph.local.root("harness") }
        val selected = ProcedureLocation(base, listOf(" old.txt "))
        show(selected)
        assertEquals(selected, save())

        val file = File(harness.directory, " new file.txt ")
        compose.onNodeWithText("Source 1").performTextReplacement(file.absolutePath)
        assertEquals(ProcedureLocation(requireNotNull(harness.graph.local.refFor(file.absolutePath))), save())
    }

    @Test fun `late labels preserve typed suffixes and clearing a selection permits a full path`() {
        val storage = MemoryStorageProvider("opaque")
        val base = runBlocking { storage.folder(storage.root, "Picked location") }
        val pendingLabel = CompletableDeferred<Unit>()
        harness.graph.providers.register(object : StorageProvider by storage {
            override suspend fun stat(ref: NodeRef): Entry {
                pendingLabel.await()
                return storage.stat(ref)
            }
        })
        show(ProcedureLocation(base))
        compose.onNodeWithTag("procedure-place-Source 1").performTextReplacement(" nested / note.txt ")
        pendingLabel.complete(Unit)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Picked location").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("procedure-place-Source 1").assertTextEquals(" nested / note.txt ")
        assertEquals(ProcedureLocation(base, listOf(" nested ", " note.txt ")), save())

        compose.onNodeWithContentDescription("Clear Source 1 selection").performClick()
        val file = File(harness.directory, " replacement.txt ")
        compose.onNodeWithText("Source 1").performTextReplacement(file.absolutePath)
        assertEquals(ProcedureLocation(requireNotNull(harness.graph.local.refFor(file.absolutePath))), save())
    }

    @Test fun `a late label cannot restore a cleared opaque selection`() {
        val storage = MemoryStorageProvider("opaque")
        val pendingLabel = CompletableDeferred<Unit>()
        harness.graph.providers.register(object : StorageProvider by storage {
            override suspend fun stat(ref: NodeRef): Entry {
                pendingLabel.await()
                return storage.stat(ref)
            }
        })
        show(ProcedureLocation(storage.root))
        compose.onNodeWithContentDescription("Clear Source 1 selection").performClick()
        val file = File(harness.directory, " direct.txt ")
        compose.onNodeWithText("Source 1").performTextReplacement(file.absolutePath)
        pendingLabel.complete(Unit)
        assertEquals(ProcedureLocation(requireNotNull(harness.graph.local.refFor(file.absolutePath))), save())
    }
}
