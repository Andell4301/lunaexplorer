package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.core.ArchiveFormat
import com.lunaexplorer.core.ArchiveOptions
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.ItemStatus
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.OperationRequest
import com.lunaexplorer.core.OperationType
import com.lunaexplorer.core.RenameStep
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile
import com.lunaexplorer.app.model.*

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class ArchiveEditTest {
    @get:Rule val harness = BrowserViewModelHarness().startingWith { dir ->
        File(dir, "one.txt").writeText("first")
        File(dir, "two.txt").writeText("second")
        File(dir, "out").mkdirs()
    }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state
    private val graph get() = harness.graph

    private fun ref(file: File): NodeRef = requireNotNull(graph.local.referenceTo(file.path))

    private fun listed() {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { state.entries.isNotEmpty() })
    }

    /** Zips [sources], which must make two top-level members, as [name] and browses into it. */
    private fun browseZipOf(name: String, vararg sources: File) {
        val archive = runBlocking {
            val made = graph.archives.create(sources.map { ref(it) }, ref(harness.directory), name,
                ArchiveOptions(ArchiveFormat.ZIP)).last().produced
            graph.local.stat(requireNotNull(made))
        }
        viewModel.browseArchive(archive)
        assertTrue("Lands inside the archive", harness.awaitUntil {
            state.location?.ref?.provider == graph.insideArchives.id &&
                state.entries.size == 2 && !state.loading
        })
    }

    private fun insideArchive() {
        listed()
        browseZipOf("bundle.zip", File(harness.directory, "one.txt"), File(harness.directory, "two.txt"))
    }

    private fun insideArchiveWithFolder() {
        listed()
        val docs = File(harness.directory, "docs").apply { mkdirs() }
        File(docs, "inner.txt").writeText("inner")
        browseZipOf("folders.zip", docs, File(harness.directory, "one.txt"))
    }

    private fun engine(type: OperationType, sources: List<NodeRef>, destination: NodeRef, name: String? = null) =
        runBlocking {
            graph.engine.run(OperationRequest(type = type, sources = sources,
                destination = destination, name = name))
        }

    private fun zipNames(name: String): List<String> =
        ZipFile(File(harness.directory, name)).use { zip ->
            zip.entries().toList().map { it.name }.sorted()
        }

    @Test fun `renaming a member really rewrites the archive on local storage`() {
        insideArchive()
        val member = state.entries.first { it.name == "one.txt" }
        val folder = requireNotNull(state.location).ref

        val outcome = engine(OperationType.RENAME, listOf(member.ref), folder, name = "renamed.txt")
            .outcomes.single()

        assertEquals("the engine said: ${outcome.message}", ItemStatus.SUCCESS, outcome.status)
        viewModel.refresh()
        assertTrue("The listing shows the new name",
            harness.awaitUntil(rounds = 80) {
                state.entries.map { it.name }.sorted() == listOf("renamed.txt", "two.txt")
            })
    }

    @Test fun `members of an editable zip offer rename and delete`() {
        insideArchive()
        val member = state.entries.first { it.name == "one.txt" }

        assertTrue("A zip on local storage can be rewritten, so its members can be renamed",
            Capability.RENAME in member.capabilities)
        assertTrue(Capability.DELETE in member.capabilities)
    }

    @Test fun `a member can be moved out of the archive`() {
        insideArchive()
        val member = state.entries.first { it.name == "one.txt" }
        viewModel.toggleSelection(member.ref)
        viewModel.operations.copySelection(move = true)
        assertTrue("It is carried as a move", state.clipboard?.move == true)

        viewModel.goTo(File(harness.directory, "out").absolutePath)
        assertTrue(harness.awaitUntil { state.location?.title == "out" && state.canPasteHere })
        viewModel.operations.paste()

        assertTrue("The move reaches the queue rather than being refused",
            harness.awaitUntil { state.operations.any { it.title.startsWith("Move 1 item(s) to out") } })
    }

    @Test fun `renaming a folder inside an archive really lands on disk`() {
        // The zip stores the folder as "docs/" while its Entry is named "docs".
        insideArchiveWithFolder()
        val folder = state.entries.first { it.name == "docs" }
        val location = requireNotNull(state.location).ref

        val outcome = engine(OperationType.RENAME, listOf(folder.ref), location, name = "papers")
            .outcomes.single()

        assertEquals("the engine said: ${outcome.message}", ItemStatus.SUCCESS, outcome.status)
        assertEquals(listOf("one.txt", "papers/", "papers/inner.txt"), zipNames("folders.zip"))
    }

    @Test fun `deleting a folder inside an archive really removes it`() {
        insideArchiveWithFolder()
        val folder = state.entries.first { it.name == "docs" }

        val outcome = runBlocking {
            graph.engine.run(OperationRequest(type = OperationType.DELETE, sources = listOf(folder.ref)))
        }.outcomes

        assertTrue("the engine said: ${outcome.map { it.message }}", outcome.all { it.status == ItemStatus.SUCCESS })
        assertEquals(listOf("one.txt"), zipNames("folders.zip"))
    }

    @Test fun `batch renaming members inside an archive lands every new name`() {
        insideArchive()

        viewModel.batchRename(state.entries, listOf(RenameStep.Replace("txt", "md")), includeExtension = true)

        assertTrue("the browser said: ${state.message}",
            harness.awaitUntil { state.message == "Renamed 2 items" })
        assertEquals(listOf("one.md", "two.md"), zipNames("bundle.zip"))
    }
}
