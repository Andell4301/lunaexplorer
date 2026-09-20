package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.*
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.RootKind
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val Bookmark.place get() = destination as? Destination.Place
private val Bookmark.path get() = place?.path
private val Bookmark.location get() = place?.location

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class BookmarkActionsTest {

    @get:Rule val harness = BrowserViewModelHarness().startingWith { directory ->
        File(directory, "alpha").mkdir()
        File(directory, "beta.txt").writeText("b")
    }
    private val viewModel get() = harness.viewModel

    private fun listed() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { harness.state.entries.size == 2 })
    }

    @Test
    fun starringAFolderAlreadyBookmarkedByPathDoesNotDuplicateIt() {
        listed()
        val here = requireNotNull(viewModel.state.value.directoryPath)

        viewModel.bookmarks.add("By path", here)
        assertTrue(harness.awaitUntil { viewModel.state.value.bookmarks.any { it.path == here } })

        viewModel.bookmarks.add("By path again", here)
        // Path resolution runs off the main thread, so idling the main looper alone is not enough.
        assertTrue(harness.awaitUntil {
            harness.idle()
            viewModel.state.value.bookmarks.singleOrNull { it.path == here }?.title == "By path again"
        })

        val bookmarks = viewModel.state.value.bookmarks
        assertEquals("Bookmarking a path twice is one row, brought up to date", listOf("By path again"), bookmarks.map { it.title })
        val ids = bookmarks.map { it.id }
        assertEquals("Bookmark ids must stay unique or the drawer cannot render", ids.size, ids.toSet().size)
    }

    @Test
    fun aBookmarkWithADeadLocationStillOpensThroughItsPath() {
        listed()
        val here = requireNotNull(viewModel.state.value.directoryPath)
        serveDeviceRoot()

        viewModel.goTo("/")
        assertTrue(harness.awaitUntil { viewModel.state.value.directoryPath != here })

        val dead = Bookmark("Stale", Destination.Place(here, Location(listOf(Crumb(NodeRef("local", "no-such-root"), "gone")))))
        viewModel.openBookmark(dead)
        assertTrue(harness.awaitUntil { viewModel.state.value.directoryPath == here })

        assertEquals("The path must be used when the reference no longer answers",
            here, viewModel.state.value.directoryPath)
    }

    @Test
    fun theTwoBookmarkListsAreIndependent() {
        listed()
        val here = requireNotNull(viewModel.state.value.directoryPath)
        fun state() = viewModel.state.value

        viewModel.bookmarks.add("Sidebar copy", here, BookmarkList.SIDEBAR)
        assertTrue(harness.awaitUntil { state().bookmarks.any { it.path == here } })
        assertTrue("The home list must not have gained anything", state().homeBookmarks.isEmpty())

        val bookmark = state().bookmarks.single { it.path == here }
        viewModel.bookmarks.copyTo(bookmark, BookmarkList.HOME)
        assertTrue(harness.awaitUntil { state().homeBookmarks.isNotEmpty() })
        assertEquals("Copying must leave the original alone", 1, state().bookmarks.count { it.path == here })

        viewModel.bookmarks.copyTo(bookmark, BookmarkList.HOME)
        harness.idle()
        assertEquals(1, state().homeBookmarks.size)

        val copy = state().homeBookmarks.single()
        assertNotEquals("A copy is a row of its own", bookmark.id, copy.id)
        viewModel.bookmarks.rename(copy, "Renamed on home", BookmarkList.HOME)
        assertTrue(harness.awaitUntil { state().homeBookmarks.single().title == "Renamed on home" })
        assertEquals("Renaming one list must not touch the other",
            "Sidebar copy", state().bookmarks.single { it.path == here }.title)

        viewModel.bookmarks.remove(copy, BookmarkList.HOME)
        assertTrue(harness.awaitUntil { state().homeBookmarks.isEmpty() })
        assertEquals("Removing from one list must not touch the other",
            1, state().bookmarks.count { it.path == here })
    }

    @Test
    fun bookmarksCanBeReorderedAndTheOrderIsKept() {
        listed()
        fun titles() = viewModel.state.value.homeBookmarks.map { it.title }

        listOf("/one" to "One", "/two" to "Two", "/three" to "Three").forEach { (path, title) ->
            viewModel.bookmarks.add(title, path, BookmarkList.HOME)
            assertTrue(harness.awaitUntil { titles().contains(title) })
        }
        assertEquals(listOf("One", "Two", "Three"), titles())

        viewModel.bookmarks.move(2, 0, BookmarkList.HOME)
        assertTrue(harness.awaitUntil { titles().first() == "Three" })
        assertEquals(listOf("Three", "One", "Two"), titles())

        viewModel.bookmarks.move(0, 9, BookmarkList.HOME)
        viewModel.bookmarks.move(-1, 0, BookmarkList.HOME)
        harness.idle()
        assertEquals(listOf("Three", "One", "Two"), titles())
        assertTrue("The order must reach the saved session", harness.awaitUntil {
            runBlocking { harness.graph.database.loadSession() }
                ?.homeBookmarks?.map { it.title } == listOf("Three", "One", "Two")
        })
    }

    @Test
    fun aToolPageCanBeBookmarkedAndOpened() {
        listed()

        viewModel.bookmarks.addScreen(Screen.RECYCLE_BIN, "Bin", BookmarkList.HOME)
        assertTrue(harness.awaitUntil { viewModel.state.value.homeBookmarks.isNotEmpty() })

        val bookmark = viewModel.state.value.homeBookmarks.single()
        assertEquals(Destination.Tool(Screen.RECYCLE_BIN), bookmark.destination)

        viewModel.openBookmark(bookmark)
        assertTrue(harness.awaitUntil { viewModel.state.value.screen == Screen.RECYCLE_BIN })

        viewModel.bookmarks.addScreen(Screen.RECYCLE_BIN, "Bin again", BookmarkList.HOME)
        harness.idle()
        assertEquals(listOf("Bin again"), viewModel.state.value.homeBookmarks.map { it.title })
    }

    @Test
    fun aFileBookmarkRevealsTheFileInItsFolder() {
        listed()
        val folder = requireNotNull(viewModel.state.value.directoryPath)
        val file = "$folder/beta.txt"

        viewModel.bookmarks.add("Beta", file)
        assertTrue(harness.awaitUntil { viewModel.state.value.bookmarks.any { it.path == file } })
        assertTrue(harness.awaitUntil {
            harness.idle()
            viewModel.state.value.bookmarks.single { it.path == file }.place?.file == true
        })

        viewModel.showScreen(Screen.HOME)
        assertTrue(harness.awaitUntil { viewModel.state.value.screen == Screen.HOME })

        val bookmark = viewModel.state.value.bookmarks.single { it.path == file }
        viewModel.openBookmark(bookmark)
        assertTrue(harness.awaitUntil {
            harness.idle()
            viewModel.state.value.directoryPath == folder && viewModel.state.value.selected.isNotEmpty()
        })
        assertEquals("A file bookmark lands in its folder, not on the file",
            folder, viewModel.state.value.directoryPath)
        assertEquals(1, viewModel.state.value.selected.size)
    }

    @Test
    fun aBookmarkPathCanBeEdited() {
        listed()
        val folder = requireNotNull(viewModel.state.value.directoryPath)

        viewModel.bookmarks.add("Somewhere", "$folder/alpha")
        assertTrue(harness.awaitUntil { viewModel.state.value.bookmarks.any { it.title == "Somewhere" } })

        val bookmark = viewModel.state.value.bookmarks.single { it.title == "Somewhere" }
        viewModel.bookmarks.edit(bookmark, "Moved", folder, BookmarkList.SIDEBAR)
        assertTrue(harness.awaitUntil { viewModel.state.value.bookmarks.any { it.title == "Moved" } })

        val edited = viewModel.state.value.bookmarks.single { it.title == "Moved" }
        assertEquals(folder, edited.path)
        assertNotNull("A changed path must be resolved afresh", edited.location)
    }

    /** Serves "/" so paths outside the harness directory resolve. */
    private fun serveDeviceRoot() {
        val preferences = viewModel.state.value.preferences
        if (!preferences.showDeviceRoot) {
            viewModel.setPreferences(preferences.copy(showDeviceRoot = true))
        }
        assertTrue(harness.awaitUntil {
            harness.idle()
            viewModel.state.value.roots.any { it.kind == RootKind.SYSTEM }
        })
    }
}
