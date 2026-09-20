package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class SettingsResetTest {
    private var chosen = ""

    @get:Rule val harness = BrowserViewModelHarness()
        .startingWith { dir -> chosen = File(dir, "begin here").apply { mkdirs() }.absolutePath }
        .withSession { it.copy(preferences = it.preferences.copy(startPath = chosen)) }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    @Test fun `a chosen start folder is where Luna opens`() {
        assertTrue(harness.awaitUntil { state.ready })
        assertTrue("Luna opens on the folder that was chosen",
            harness.awaitUntil { state.screen == Screen.BROWSER && state.directoryPath == chosen })
        assertFalse("Which is where it began, so back leaves rather than going further",
            viewModel.canGoBack())
    }

    @Test fun `reset puts back every setting and leaves what is not one alone`() {
        assertTrue(harness.awaitUntil { state.ready })
        assertTrue("The start folder must have landed first",
            harness.awaitUntil { state.directoryPath == chosen && state.folderKey != null })

        viewModel.setPreferences(state.preferences.copy(
            theme = ThemeMode.DARK, uiScale = 1.4f, showHidden = true, view = ViewMode.GRID_LARGE))
        viewModel.setViewOptions(state.preferences.copy(sort = SortOrder.SIZE), thisFolderOnly = true)
        viewModel.rememberForSession(confirmDelete = false)
        viewModel.bookmarks.add("Mine", harness.directory.absolutePath)
        viewModel.setAppDataBookmark(true)
        assertTrue("A folder's own view must have been recorded",
            harness.awaitUntil { state.folderViews.isNotEmpty() })
        assertTrue("The switch bookmark must have been added",
            harness.awaitUntil { viewModel.bookmarks.appDataOn(state) })
        assertTrue("And an ordinary bookmark with it",
            harness.awaitUntil { state.bookmarks.any { it.title == "Mine" } })

        viewModel.resetSettings()
        assertTrue(harness.awaitUntil { state.preferences.theme == ThemeMode.SYSTEM })

        assertEquals("The page's own settings", Preferences(), state.preferences)
        assertTrue("A folder's own view and sort", state.folderViews.isEmpty())
        assertTrue("What was chosen for this session", state.confirmDelete)
        assertFalse("And the bookmark a switch owns goes with its switch",
            viewModel.bookmarks.appDataOn(state))
        assertTrue("But a bookmark someone added is not a setting",
            state.bookmarks.any { it.title == "Mine" })
    }
}
