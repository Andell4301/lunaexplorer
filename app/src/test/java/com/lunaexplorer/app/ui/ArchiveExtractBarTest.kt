package com.lunaexplorer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.core.ArchiveFormat
import com.lunaexplorer.core.ArchiveOptions
import com.lunaexplorer.core.NodeRef
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import com.lunaexplorer.app.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class ArchiveExtractBarTest {
    private val harness = BrowserViewModelHarness().startingWith { folder ->
        File(folder, "payload.txt").writeText("inside")
    }.withSession { it.copy(preferences = it.preferences.copy(thumbnails = false, introSeen = true)) }
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(harness).around(compose)

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state
    private val graph get() = harness.graph

    private fun ref(file: File): NodeRef = requireNotNull(graph.local.referenceTo(file.path))

    private fun memberSelected() {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { state.entries.isNotEmpty() })
        val archive = runBlocking {
            val produced = graph.archives.create(
                listOf(ref(File(harness.directory, "payload.txt"))), ref(harness.directory),
                "bundle.zip", ArchiveOptions(ArchiveFormat.ZIP),
            ).last().produced
            graph.local.stat(requireNotNull(produced))
        }
        viewModel.browseArchive(archive)
        assertTrue("Lands inside the archive", harness.awaitUntil {
            state.location?.ref?.provider == graph.insideArchives.id && state.entries.isNotEmpty() && !state.loading
        })
        viewModel.toggleSelection(state.entries.single { it.name == "payload.txt" }.ref)
    }

    @Composable
    private fun SelectedFilesBar() {
        MaterialTheme {
            val current by viewModel.state.collectAsState()
            SelectionBar(current, viewModel, LunaActions(
                grantFolder = {}, open = { _, _ -> }, openWith = { _, _, _ -> },
                shareReport = {}, requestFullAccess = {}, openSystemBrowser = {},
            ), onRename = {}, onDelete = {}, onProperties = {}, onOpenAs = {}, onArchive = {},
                onExtract = { viewModel.operations.copySelection(move = false) })
        }
    }

    @Test fun `extract is on the bar itself, not behind More`() {
        memberSelected()
        compose.setContent { SelectedFilesBar() }

        compose.onNodeWithText("Extract").assertExists()
        compose.onNodeWithText("Extract").performClick()
        compose.waitForIdle()
        assertEquals(listOf("payload.txt"), state.clipboard?.entries?.map { it.name })
    }

    @Test fun `Copy is still reachable inside an archive, under More`() {
        memberSelected()
        compose.setContent { SelectedFilesBar() }

        compose.onNodeWithText("More").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Copy").performClick()
        compose.waitForIdle()
        assertEquals("It picks the members up exactly as Extract does",
            listOf("payload.txt"), state.clipboard?.entries?.map { it.name })
    }

    @Test fun `outside an archive the same place offers Copy`() {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { state.entries.isNotEmpty() })
        viewModel.toggleSelection(state.entries.first().ref)
        compose.setContent { SelectedFilesBar() }

        compose.onNodeWithText("Copy").assertExists()
        compose.onAllNodesWithText("Extract").assertCountEquals(0)
    }
}
