package com.lunaexplorer.app.data

import com.lunaexplorer.app.model.Bookmark
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.BrowserTab
import com.lunaexplorer.app.model.Crumb
import com.lunaexplorer.app.model.Destination
import com.lunaexplorer.app.model.Screen
import com.lunaexplorer.app.model.Location
import com.lunaexplorer.app.model.FolderView
import com.lunaexplorer.app.model.SortOrder
import com.lunaexplorer.app.model.ViewMode
import com.lunaexplorer.core.NodeRef
import org.junit.Assert.*
import org.junit.Test

class BookmarkCodecTest {
    private val root = Location(listOf(Crumb(NodeRef("local", "primary "), "Internal")))
    private val state = BrowserState(
        tabs = listOf(BrowserTab("t", listOf(root), 0)),
        activeTabId = "t",
        ready = true,
    )

    private fun roundTrip(bookmarks: List<Bookmark>): List<Bookmark> =
        SessionCodec.decode(SessionCodec.encode(state.copy(bookmarks = bookmarks))).bookmarks

    private fun place(path: String, location: Location? = null) = Destination.Place(path, location)
    private val Bookmark.place get() = destination as Destination.Place

    @Test fun `a bookmark's id survives a round trip`() {
        val original = listOf(Bookmark("Music", place("/storage/emulated/0/Music")),
            Bookmark("Bin", Destination.Tool(Screen.RECYCLE_BIN)))
        assertEquals(original, roundTrip(original))
    }

    @Test fun `awkward characters in a bookmark path come back exactly`() {
        val awkward = listOf(
            "/storage/emulated/0/Notes\u200e",   // trailing left-to-right mark
            "/storage/emulated/0/trailing space ",
            "/storage/emulated/0/100% done",
            "/storage/emulated/0/tag#1",
            "/storage/emulated/0/日本語 🎵",
            "/storage/emulated/0/a\"quote'",
        )
        val saved = roundTrip(awkward.mapIndexed { index, path -> Bookmark("b$index", place(path)) })
        assertEquals(awkward, saved.map { it.place.path })
    }

    @Test fun `destinations are the same place by path or by resolved reference, tools by page`() {
        assertTrue(place("/a").sameAs(place("/a")))
        assertFalse(place("/a").sameAs(place("/b")))
        assertTrue("Two spellings that resolved to one reference are one place",
            place("/a/", root).sameAs(place("/a", root)))
        assertTrue("A path-only bookmark and a resolved one to the same path are one place",
            place("/a").sameAs(place("/a", root)))
        assertFalse(place("/a").sameAs(Destination.Tool(Screen.RECYCLE_BIN)))
        assertTrue(Destination.Tool(Screen.RECYCLE_BIN).sameAs(Destination.Tool(Screen.RECYCLE_BIN)))
        assertFalse(Destination.Tool(Screen.RECYCLE_BIN).sameAs(Destination.Tool(Screen.STORAGE)))
    }

    @Test fun `folder view overrides round trip in order`() {
        val views = linkedMapOf(
            "/storage/emulated/0/DCIM" to FolderView(ViewMode.GRID_LARGE, SortOrder.MODIFIED, true),
            "/storage/emulated/0/Download" to FolderView(ViewMode.DETAILED, SortOrder.SIZE, false),
            "saf:tree-abc" to FolderView(ViewMode.COMPACT, SortOrder.NAME, false),
        )
        val saved = SessionCodec.decode(SessionCodec.encode(
            state.copy(folderViews = views))).folderViews
        assertEquals(views.keys.toList(), saved.keys.toList())
        assertEquals(FolderView(ViewMode.GRID_LARGE, SortOrder.MODIFIED, true), saved["/storage/emulated/0/DCIM"])
        assertEquals(FolderView(ViewMode.COMPACT, SortOrder.NAME, false), saved["saf:tree-abc"])
    }

    @Test fun `the two bookmark lists round trip separately`() {
        val sidebar = listOf(Bookmark("Music", place("/storage/emulated/0/Music")))
        val home = listOf(Bookmark("Camera", place("/storage/emulated/0/DCIM")),
            Bookmark("Downloads", place("/storage/emulated/0/Download")))
        val saved = SessionCodec.decode(SessionCodec.encode(
            state.copy(bookmarks = sidebar, homeBookmarks = home)))
        assertEquals(listOf("/storage/emulated/0/Music"), saved.bookmarks.map { it.place.path })
        assertEquals(listOf("/storage/emulated/0/DCIM", "/storage/emulated/0/Download"),
            saved.homeBookmarks.map { it.place.path })
    }
}
