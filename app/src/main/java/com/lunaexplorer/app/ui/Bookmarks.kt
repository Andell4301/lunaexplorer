package com.lunaexplorer.app.ui

import com.lunaexplorer.app.model.Bookmark
import com.lunaexplorer.app.model.BookmarkList
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.Destination
import com.lunaexplorer.app.model.Location
import com.lunaexplorer.app.model.Screen
import com.lunaexplorer.core.ProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class Bookmarks(
    private val state: MutableStateFlow<BrowserState>,
    private val scope: CoroutineScope,
    private val resolver: PathResolver,
    private val providers: ProviderRegistry,
    private val persist: () -> Unit,
    private val message: (String) -> Unit,
    private val ownDataPath: () -> String?,
) {
    /** For providers without paths: the bookmark keeps the breadcrumbs instead. */
    fun addLocation(title: String, location: Location, file: Boolean, list: BookmarkList = BookmarkList.SIDEBAR) {
        val name = title.trim().ifEmpty { location.title }
        state.update { s ->
            s.withBookmarks(list, s.bookmarksIn(list).adding(Bookmark(name, Destination.Place(location = location, file = file))))
        }
        persist()
    }

    fun add(title: String, path: String, list: BookmarkList = BookmarkList.SIDEBAR) {
        scope.launch {
            val located = resolver.locate(path)?.location
            val name = title.trim().ifEmpty {
                located?.title ?: path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
            }
            state.update { state ->
                state.withBookmarks(list, state.bookmarksIn(list).adding(Bookmark(name, Destination.Place(path, located))))
            }
            persist()
            refreshKind(path, list)
            if (located == null) message("Saved. Luna cannot reach $path at the moment.")
        }
    }

    fun addScreen(screen: Screen, title: String, list: BookmarkList = BookmarkList.SIDEBAR) {
        val name = title.trim().ifEmpty { screen.title }
        state.update { state ->
            state.withBookmarks(list, state.bookmarksIn(list).adding(Bookmark(name, Destination.Tool(screen))))
        }
        persist()
    }

    /** Tool and breadcrumb-only bookmarks have no editable path; for them this is a rename. */
    fun edit(bookmark: Bookmark, title: String, path: String, list: BookmarkList = BookmarkList.SIDEBAR) {
        val place = when (val destination = bookmark.destination) {
            is Destination.Tool -> { rename(bookmark, title, list); return }
            is Destination.Place -> destination
        }
        if (place.path == null && place.location != null) { rename(bookmark, title, list); return }
        val name = title.trim().ifEmpty { path.trimEnd('/').substringAfterLast('/').ifEmpty { path } }
        scope.launch {
            val changed = path != place.path
            // Resolve only changed paths so a rename preserves the cached location.
            val located = if (!changed) place.location else resolver.locate(path)?.location
            val edited = Destination.Place(path, located, file = if (changed) false else place.file)
            state.update { state ->
                state.withBookmarks(list, state.bookmarksIn(list).map {
                    if (it.id == bookmark.id) it.copy(title = name, destination = edited) else it
                })
            }
            persist()
            if (changed) refreshKind(path, list)
        }
    }

    fun rename(bookmark: Bookmark, title: String, list: BookmarkList = BookmarkList.SIDEBAR) {
        val name = title.trim().ifEmpty { return }
        state.update { state ->
            state.withBookmarks(list, state.bookmarksIn(list).map {
                if (it.id == bookmark.id) it.copy(title = name) else it
            })
        }
        persist()
    }

    fun move(from: Int, to: Int, list: BookmarkList = BookmarkList.SIDEBAR) {
        state.update { state ->
            val current = state.bookmarksIn(list)
            if (from !in current.indices || to !in current.indices || from == to) return@update state
            val reordered = current.toMutableList()
            reordered.add(to, reordered.removeAt(from))
            state.withBookmarks(list, reordered)
        }
        persist()
    }

    fun copyTo(bookmark: Bookmark, list: BookmarkList) {
        val there = state.value.bookmarksIn(list).any { it.destination.sameAs(bookmark.destination) }
        if (!there) {
            // A new id, so the copy can be edited independently of the original.
            state.update { state ->
                state.withBookmarks(list, state.bookmarksIn(list) + bookmark.copy(id = UUID.randomUUID().toString()))
            }
            persist()
        }
        message(if (there) "Already in ${list.label.lowercase()}" else "Added to ${list.label.lowercase()}")
    }

    fun remove(bookmark: Bookmark, list: BookmarkList = BookmarkList.SIDEBAR) {
        state.update { state ->
            state.withBookmarks(list, state.bookmarksIn(list).filterNot { it.id == bookmark.id })
        }
        persist()
    }

    fun setAppData(on: Boolean) {
        scope.launch {
            val paths = appDataPaths()
            val path = paths.firstOrNull()
            val located = if (on && path != null) resolver.locate(path)?.location else null
            state.update { state ->
                val kept = state.bookmarks.filterNot { it.pathIn(paths) }
                state.copy(
                    bookmarks = if (on && path != null) kept + Bookmark(APP_DATA_TITLE, Destination.Place(path, located))
                    else kept,
                )
            }
            persist()
            if (on && path == null) message("Luna cannot see its own data folder.")
        }
    }

    fun appDataOn(current: BrowserState): Boolean = current.bookmarks.any { it.pathIn(appDataPaths()) }

    /**
     * Canonical path first: the app-data root reports it, so the bookmark is written under it. The unresolved
     * spelling (/data/user/0/…) still matches. Not looked up by RootKind.APP, which any local root defaults to.
     */
    private fun appDataPaths(): List<String> {
        val own = ownDataPath() ?: return emptyList()
        val canonical = runCatching { File(own).canonicalPath }.getOrNull()
        return listOfNotNull(canonical, own).distinct()
    }

    private fun List<Bookmark>.adding(bookmark: Bookmark): List<Bookmark> {
        val first = firstOrNull { it.destination.sameAs(bookmark.destination) } ?: return this + bookmark
        return mapNotNull {
            when {
                it.id == first.id -> bookmark.copy(id = it.id)
                it.destination.sameAs(bookmark.destination) -> null
                else -> it
            }
        }
    }

    /** Sets the file flag after saving, so an unreachable destination can still be bookmarked. */
    private suspend fun refreshKind(path: String, list: BookmarkList) {
        val ref = resolver.refFor(path) ?: return
        val isFile = withContext(Dispatchers.IO) {
            try {
                !providers.provider(ref).stat(ref).directory
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ensureActive()
                null
            }
        } ?: return
        if (!isFile) return
        state.update { state ->
            state.withBookmarks(list, state.bookmarksIn(list).map {
                val place = it.destination as? Destination.Place
                if (place != null && place.path == path) it.copy(destination = place.copy(file = true, location = null)) else it
            })
        }
        persist()
    }

    private fun BrowserState.bookmarksIn(list: BookmarkList): List<Bookmark> =
        if (list == BookmarkList.HOME) homeBookmarks else bookmarks

    private fun BrowserState.withBookmarks(list: BookmarkList, value: List<Bookmark>): BrowserState =
        if (list == BookmarkList.HOME) copy(homeBookmarks = value) else copy(bookmarks = value)

    private fun Bookmark.pathIn(paths: Collection<String>): Boolean =
        (destination as? Destination.Place)?.path in paths

    /** Bookmarks owned by a settings switch; a settings reset removes them. */
    fun isSwitchBookmark(bookmark: Bookmark): Boolean = bookmark.pathIn(appDataPaths())

    private companion object {
        const val APP_DATA_TITLE = "Luna app data"
    }
}
