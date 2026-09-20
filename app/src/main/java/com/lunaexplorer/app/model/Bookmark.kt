package com.lunaexplorer.app.model

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Bookmark(
    val title: String,
    val destination: Destination,
    // Always written, even with encodeDefaults = false: the default is a fresh UUID and never equals it.
    val id: String = UUID.randomUUID().toString(),
)

@Serializable
sealed interface Destination {
    fun sameAs(other: Destination): Boolean

    @Serializable
    @SerialName("tool")
    data class Tool(val screen: Screen) : Destination {
        override fun sameAs(other: Destination): Boolean = other is Tool && other.screen == screen
    }

    /** [location] is an optional cached reference. [path] is resolved on use, so an unreachable path can still be bookmarked. */
    @Serializable
    @SerialName("place")
    data class Place(
        val path: String? = null,
        val location: Location? = null,
        val file: Boolean = false,
    ) : Destination {
        override fun sameAs(other: Destination): Boolean = other is Place &&
            ((path != null && path == other.path) ||
                (location?.ref != null && location.ref == other.location?.ref))
    }
}

enum class BookmarkList(val label: String) { SIDEBAR("Sidebar"), HOME("Home") }
