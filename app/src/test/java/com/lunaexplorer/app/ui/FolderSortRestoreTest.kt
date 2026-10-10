package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.FolderView
import com.lunaexplorer.app.model.Screen
import com.lunaexplorer.app.model.SortOrder
import com.lunaexplorer.app.model.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class FolderSortRestoreTest {
    @get:Rule val harness: BrowserViewModelHarness = BrowserViewModelHarness().startingWith { directory ->
        File(directory, "child").mkdir()
        File(directory, "a.txt").apply { writeText("a"); assertTrue(setLastModified(20_000)) }
        File(directory, "z.txt").apply { writeText("z"); assertTrue(setLastModified(10_000)) }
    }.withSession { state ->
        state.copy(folderViews = mapOf(harness.directory.absolutePath to
            FolderView(ViewMode.LIST, SortOrder.MODIFIED, descending = false)))
    }

    private fun restored() {
        assertTrue(harness.awaitUntil { harness.state.ready && !harness.state.loading && harness.state.entries.size == 3 })
        harness.viewModel.showScreen(Screen.BROWSER)
    }

    @Test fun `the restored folder uses its saved modified order`() {
        restored()

        assertEquals(listOf("child", "z.txt", "a.txt"), harness.state.entries.map { it.name })
    }

    @Test fun `returning to the restored folder applies its sort before publishing cached rows`() {
        restored()
        val root = requireNotNull(harness.state.location)
        harness.viewModel.openFolder(harness.state.entries.single { it.name == "child" })
        assertTrue(harness.awaitUntil { !harness.state.loading && harness.state.directory?.name == "child" })

        harness.viewModel.navigate(root)

        assertTrue(harness.awaitUntil { !harness.state.loading && harness.state.listingRef == root.ref })
        assertEquals(listOf("child", "z.txt", "a.txt"), harness.state.entries.map { it.name })
    }

    @Test fun `a sort changed while cached rows are loading still finishes opening that folder`() {
        restored()
        val root = requireNotNull(harness.state.location)
        harness.viewModel.openFolder(harness.state.entries.single { it.name == "child" })
        assertTrue(harness.awaitUntil { !harness.state.loading && harness.state.directory?.name == "child" })

        harness.viewModel.navigate(root)
        harness.viewModel.setViewOptions(harness.state.preferences.copy(sort = SortOrder.NAME), thisFolderOnly = true)

        assertTrue(harness.awaitUntil { !harness.state.loading && harness.state.listingRef == root.ref })
        assertEquals(listOf("child", "a.txt", "z.txt"), harness.state.entries.map { it.name })
    }
}
