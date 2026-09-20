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
import com.lunaexplorer.app.model.*

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class AppDataBookmarkTest {
    @get:Rule val harness = BrowserViewModelHarness()

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    @Test fun `the switch adds a bookmark and never a location`() {
        assertTrue(harness.awaitUntil { state.ready })
        assertFalse("Off to begin with", viewModel.bookmarks.appDataOn(state))
        assertTrue("And nothing served for it", state.roots.none { it.hidden })

        viewModel.setAppDataBookmark(true)
        assertTrue("The bookmark must arrive", harness.awaitUntil { viewModel.bookmarks.appDataOn(state) })
        assertTrue("The folder must be served, or the bookmark resolves nowhere",
            harness.awaitUntil { state.roots.any { it.hidden } })
        assertEquals("And served exactly once", 1, state.roots.count { it.hidden })

        val bookmark = state.bookmarks.single()
        assertEquals("Luna app data", bookmark.title)
        val path = requireNotNull((bookmark.destination as? Destination.Place)?.path)

        viewModel.openBookmark(bookmark)
        assertTrue("And opening it lands in the folder",
            harness.awaitUntil { state.screen == Screen.BROWSER && state.directoryPath == path })
    }

    @Test fun `turning it off takes the bookmark away again`() {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.setAppDataBookmark(true)
        assertTrue(harness.awaitUntil { viewModel.bookmarks.appDataOn(state) })

        viewModel.setAppDataBookmark(false)
        assertTrue(harness.awaitUntil { !viewModel.bookmarks.appDataOn(state) })
        assertTrue("And the drawer is empty again", state.bookmarks.isEmpty())
    }
}
