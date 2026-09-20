package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.core.ArchiveFormat
import com.lunaexplorer.core.ArchiveOptions
import com.lunaexplorer.core.Entry
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import com.lunaexplorer.app.model.*

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class ExtractDestinationTest {
    @get:Rule val harness = BrowserViewModelHarness().startingWith { dir ->
        File(dir, "payload.txt").writeText("inside the archive")
        File(dir, "elsewhere").mkdirs()
    }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    private fun ref(file: File) = requireNotNull(harness.graph.local.referenceTo(file.path))

    private fun archived(): Entry {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { state.entries.size == 2 })
        runBlocking {
            harness.graph.archives.create(
                listOf(ref(File(harness.directory, "payload.txt"))), ref(harness.directory),
                "bundle.zip", ArchiveOptions(ArchiveFormat.ZIP),
            ).last()
        }
        viewModel.refresh()
        assertTrue("The archive never appeared in the listing",
            harness.awaitUntil { state.entries.any { it.name == "bundle.zip" } })
        return state.entries.first { it.name == "bundle.zip" }
    }

    @Test fun `an archive can be carried and put down in another folder`() {
        val archive = archived()

        viewModel.operations.carryExtract(archive, ExtractPlan(intoFolder = "unpacked"))
        val carried = requireNotNull(state.clipboard)
        assertNotNull("It is carried as an extraction, not a copy", carried.extract)
        assertEquals(listOf("bundle.zip"), carried.entries.map { it.name })

        val target = state.entries.first { it.name == "elsewhere" }
        viewModel.openFolder(target)
        assertTrue("The chosen folder has to settle before anything lands in it",
            harness.awaitUntil { state.location?.title == "elsewhere" && state.canPasteHere })
        assertNotNull("The archive survives the journey", state.clipboard)

        viewModel.operations.paste()
        assertTrue("The unpacking is queued against the folder that was chosen",
            harness.awaitUntil { state.operations.any { it.title == "Extract bundle.zip to elsewhere" } })
        assertNull("An archive is unpacked once, so it is not still being carried", state.clipboard)
    }

    @Test fun `a carried archive cannot be put down from another screen`() {
        val archive = archived()
        viewModel.operations.carryExtract(archive, ExtractPlan(intoFolder = "unpacked"))

        viewModel.showScreen(Screen.HOME)
        viewModel.operations.paste()
        harness.idle()
        assertTrue("Nothing may be unpacked into a folder that is not on screen",
            state.operations.none { it.title.startsWith("Extract") })
        assertNotNull("And the archive stays in hand", state.clipboard)
    }
}
