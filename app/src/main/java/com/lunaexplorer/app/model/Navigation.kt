package com.lunaexplorer.app.model

import com.lunaexplorer.app.storage.MediaCategory
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.SearchFilter
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class Crumb(val ref: NodeRef, val name: String)
@Serializable
data class Location(val crumbs: List<Crumb>) {
    val ref: NodeRef get() = crumbs.last().ref
    val title: String get() = crumbs.last().name
}
@Serializable
data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val history: List<Location>,
    val index: Int = 0,
    /** History index where this browsing session began. Back stops here and returns to the previous screen. */
    val floor: Int = 0,
    /** Transient so a restored tab does not override the configured start screen. */
    @Transient val screen: Screen = Screen.BROWSER,
    @Transient val backStack: List<Screen> = emptyList(),
) {
    val location: Location get() = history[index]

    fun navigate(location: Location): BrowserTab {
        val extended = history.take(index + 1) + location
        val kept = extended.takeLast(80)
        val dropped = extended.size - kept.size
        return copy(history = kept, index = (index + 1 - dropped).coerceAtLeast(0),
            floor = (floor - dropped).coerceAtLeast(0))
    }
}
enum class Screen(val title: String) {
    BROWSER(""), HOME("Home"), RECYCLE_BIN("Recycle bin"), APPS("Applications"), STORAGE("Storage"),
    PROCEDURES("Stored procedures"),
}

data class ArchiveOpening(val name: String, val bytesRead: Long = 0, val total: Long? = null, val fromStart: Boolean = false)

sealed interface View {
    data class Folder(
        val ref: NodeRef,
        val directory: Entry? = null,
        /** Provider path, when the provider has one. */
        val path: String? = null,
        /** Key into [BrowserState.folderViews]; null if the folder has no stable key. */
        val folderKey: String? = null,
        /** The folder the visible rows belong to; lags [ref] during a load. */
        val listingRef: NodeRef? = null,
        val listingLocation: Location? = null,
        /** Android closes this folder to file managers, so an empty listing may not mean an empty folder. */
        val closedToApps: Boolean = false,
    ) : View
    data class Search(
        val root: NodeRef,
        val filter: SearchFilter,
        val summary: String = "Searching",
        val note: String = "",
        override val from: Screen? = null,
    ) : View, OpenedFrom
    data class Category(
        val category: MediaCategory,
        val summary: String,
        val note: String,
        override val from: Screen? = null,
    ) : View, OpenedFrom
}

/** A view opened from another screen; leaving it returns to [from] instead of the folder underneath. */
sealed interface OpenedFrom {
    val from: Screen?
}

/** Null when the view was opened from the browser itself. */
val View.openedFrom: Screen? get() = (this as? OpenedFrom)?.from
