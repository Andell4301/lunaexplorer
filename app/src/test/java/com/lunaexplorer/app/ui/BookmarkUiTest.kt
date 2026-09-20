package com.lunaexplorer.app.ui

import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class BookmarkUiTest : RobolectricBrowserUiTest() {

    @Test
    fun lunasOwnDataNeverAppearsAmongTheStorageLocations() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }

        compose.runOnUiThread { viewModel.setAppDataBookmark(true) }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.bookmarks.appDataOn(viewModel.state.value) && viewModel.state.value.roots.any { it.hidden }
        }

        compose.runOnUiThread { viewModel.showScreen(Screen.HOME) }
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.screen == Screen.HOME }
        compose.onAllNodesWithText("Luna app data").assertCountEquals(0)
    }

    @Test
    fun addingABookmarkFromHomeOffersTheHomeList() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        compose.runOnUiThread { viewModel.showScreen(Screen.HOME) }
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.screen == Screen.HOME }

        compose.onAllNodesWithText("Add").onFirst().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.overlay is Overlay.AddBookmark }

        val overlay = viewModel.state.value.overlay as Overlay.AddBookmark
        assertEquals(BookmarkList.HOME, overlay.list)
    }
}
