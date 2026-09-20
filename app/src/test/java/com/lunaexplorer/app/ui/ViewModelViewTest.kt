package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.storage.MediaCategory
import org.junit.Assert.assertEquals
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
class ViewModelViewTest {
    @get:Rule val harness = BrowserViewModelHarness().startingWith { dir ->
        File(dir, "x.txt").writeText("x")
        File(dir, "a.txt").writeText("a")
        File(dir, "b.txt").writeText("b")
    }

    @Test fun `a folder, a category and a search are each exactly one view`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        harness.viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { harness.state.view is View.Folder })

        harness.viewModel.showCategory(MediaCategory.IMAGES)
        assertTrue(harness.awaitUntil { harness.state.view is View.Category })
        assertEquals(MediaCategory.IMAGES, (harness.state.view as View.Category).category)
        assertTrue(harness.state.searchActive)

        harness.viewModel.closeSearch()
        assertTrue(harness.awaitUntil { harness.state.view is View.Folder })

        harness.viewModel.search("x", "ALL", null, null, null)
        assertTrue(harness.awaitUntil { harness.state.view is View.Search })
    }

    @Test fun `a folder's own view can be read and changed away from the folder`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        harness.viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil {
            harness.state.view is View.Folder && harness.state.folderKey != null &&
                harness.state.entries.size == 3
        })
        val key = requireNotNull(harness.state.folderKey)
        harness.viewModel.setViewOptions(
            harness.state.preferences.copy(view = ViewMode.GRID_LARGE), thisFolderOnly = true)
        assertEquals("The folder must have one before it can be listed", ViewMode.GRID_LARGE,
            harness.state.folderViews[key]?.view)

        harness.viewModel.setFolderView(key, requireNotNull(harness.state.folderViews[key])
            .copy(sort = SortOrder.NAME, descending = true))

        assertEquals(SortOrder.NAME, harness.state.folderViews[key]?.sort)
        assertEquals("The layout it already had is left alone", ViewMode.GRID_LARGE,
            harness.state.folderViews[key]?.view)
        assertTrue("The folder on screen is laid out from this, so it follows at once",
            harness.awaitUntil { harness.state.entries.map { it.name } == listOf("x.txt", "b.txt", "a.txt") })

        harness.viewModel.forgetFolderView(key)
        assertNull("And it can be given back", harness.state.folderViews[key])
        assertTrue("Leaving the folder in the order everywhere else is in",
            harness.awaitUntil { harness.state.entries.map { it.name } == listOf("a.txt", "b.txt", "x.txt") })
    }

    @Test fun `a folder's own view carries the settings beside it that are not the folder's`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        harness.viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { harness.state.view is View.Folder && harness.state.folderKey != null })
        val sheet = harness.state.preferences.copy(
            view = ViewMode.GRID_CUSTOM, gridCell = 173, showHidden = true)

        harness.viewModel.setViewOptions(sheet, thisFolderOnly = true)

        assertEquals("The layout is this folder's", ViewMode.GRID_CUSTOM,
            harness.state.folderViews[harness.state.folderKey]?.view)
        assertEquals("The tile size is not, so it has to reach the settings themselves",
            173, harness.state.preferences.gridCell)
        assertTrue("As do hidden files, which are set from the same sheet",
            harness.state.preferences.showHidden)
    }

    @Test
    fun changingASettingInsideADeviceWideViewStaysInThatView() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        harness.viewModel.showScreen(Screen.BROWSER)

        harness.viewModel.showCategory(MediaCategory.IMAGES)
        assertTrue(harness.awaitUntil { harness.viewModel.state.value.searchActive })

        val preferences = harness.viewModel.state.value.preferences
        harness.viewModel.setPreferences(preferences.copy(showHidden = !preferences.showHidden))
        harness.idle()

        assertTrue("The device-wide view must survive a preference change",
            harness.viewModel.state.value.searchActive)
        assertEquals(MediaCategory.IMAGES, (harness.viewModel.state.value.view as? View.Category)?.category)

        harness.viewModel.refresh()
        harness.idle()
        assertTrue("Refreshing must not drop out either", harness.viewModel.state.value.searchActive)
    }
}
