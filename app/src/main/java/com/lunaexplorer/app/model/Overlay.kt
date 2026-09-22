package com.lunaexplorer.app.model

import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.ConflictPolicy
import com.lunaexplorer.core.NodeRef

enum class ViewerKind { IMAGE, TEXT, CODE, MEDIA, DATABASE, PDF, DOCUMENT, HEX, NONE }

sealed interface Overlay {
    data object Create : Overlay
    data object BatchRename : Overlay
    data object Rename : Overlay
    data object Delete : Overlay
    data object NewFile : Overlay
    data object Archive : Overlay
    data object Extract : Overlay
    data object PinPath : Overlay
    data object GoTo : Overlay
    data object Search : Overlay
    data object ViewOptions : Overlay
    data object Settings : Overlay
    data object Queue : Overlay
    data class Overwrite(val operationId: String, val policy: ConflictPolicy) : Overlay
    data class EditBookmark(val bookmark: Bookmark, val list: BookmarkList) : Overlay
    data class AddBookmark(val proposal: Bookmark, val list: BookmarkList = BookmarkList.SIDEBAR) : Overlay
    data class Properties(val entries: List<Entry>) : Overlay
    data class Viewer(val entry: Entry, val kind: ViewerKind) : Overlay
    data class Package(val entry: Entry) : Overlay
    data class Install(val entry: Entry) : Overlay
    data class Manifest(val entry: Entry) : Overlay
    data class Opening(val entry: Entry) : Overlay
    data class ArchivePassword(
        val entry: Entry,
        val title: String,
        val error: String? = null,
        val verifying: Boolean = false,
    ) : Overlay
    data class Drop(val entries: List<Entry>, val destination: NodeRef, val title: String) : Overlay
}
