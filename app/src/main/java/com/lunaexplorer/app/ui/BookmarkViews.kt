@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.model.*

/** Reordering is by menu: drag-to-reorder would conflict with list scrolling and the drawer swipe. */
@Composable
internal fun BookmarkRow(
    bookmark: Bookmark,
    index: Int,
    count: Int,
    list: BookmarkList,
    selected: Boolean,
    viewModel: BrowserViewModel,
    onOpen: () -> Unit,
    onRename: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val other = if (list == BookmarkList.HOME) BookmarkList.SIDEBAR else BookmarkList.HOME
    val unresolved = (bookmark.destination as? Destination.Place)?.takeIf { it.location == null }?.path
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            LocationRow(bookmark.title, Icons.Outlined.BookmarkBorder, Hue.ACCENT,
                selected = selected,
                onClick = { viewModel.openBookmark(bookmark); onOpen() },
                detail = unresolved?.let { path ->
                    {
                        Text(path, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                })
        }
        Box {
            ToolIcon(Icons.Outlined.MoreVert, "Options for ${bookmark.title}", onClick = { menu = true })
            FastDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") },
                    onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Move up") }, enabled = index > 0,
                    onClick = { menu = false; viewModel.bookmarks.move(index, index - 1, list) })
                DropdownMenuItem(text = { Text("Move down") }, enabled = index < count - 1,
                    onClick = { menu = false; viewModel.bookmarks.move(index, index + 1, list) })
                DropdownMenuItem(text = { Text("Add to ${other.label.lowercase()}") },
                    onClick = { menu = false; viewModel.bookmarks.copyTo(bookmark, other) })
                DropdownMenuItem(text = { Text("Remove") },
                    onClick = { menu = false; viewModel.bookmarks.remove(bookmark, list) })
            }
        }
    }
}

internal fun bookmarkProposal(state: BrowserState): Bookmark {
    val picked = state.selectedEntries.singleOrNull()
    val folder = state.directoryPath
    val here = state.location
    // A path is preferred because it survives root ref changes; crumbs are for providers without paths.
    return when {
        picked != null -> Bookmark(picked.name, Destination.Place(
            path = folder?.let { "${it.trimEnd('/')}/${picked.name}" },
            location = if (folder == null) here?.let { Location(it.crumbs + Crumb(picked.ref, picked.name)) } else null,
            file = !picked.directory,
        ))
        state.screen != Screen.BROWSER && state.screen != Screen.HOME ->
            Bookmark(state.screen.title, Destination.Tool(state.screen))
        else -> Bookmark(here?.title.orEmpty(),
            Destination.Place(path = folder, location = if (folder == null) here else null))
    }
}

@Composable
internal fun AddBookmarkDialog(
    proposal: Bookmark,
    initialList: BookmarkList,
    viewModel: BrowserViewModel,
    showHidden: Boolean,
    onDismiss: () -> Unit,
    onAdd: (String, Destination, BookmarkList) -> Unit,
) {
    BookmarkFields(
        heading = "Add bookmark",
        confirm = "Add",
        initialName = proposal.title,
        destination = proposal.destination,
        showList = true,
        initialList = initialList,
        viewModel = viewModel,
        showHidden = showHidden,
        onDismiss = onDismiss,
        onConfirm = onAdd,
    )
}

@Composable
internal fun EditBookmarkDialog(
    bookmark: Bookmark,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    BookmarkFields(
        heading = "Edit bookmark",
        confirm = "Save",
        initialName = bookmark.title,
        destination = bookmark.destination,
        showList = false,
        onDismiss = onDismiss,
    ) { name, destination, _ -> onSave(name, (destination as? Destination.Place)?.path.orEmpty()) }
}

@Composable
private fun BookmarkFields(
    heading: String,
    confirm: String,
    initialName: String,
    destination: Destination,
    showList: Boolean,
    initialList: BookmarkList = BookmarkList.SIDEBAR,
    viewModel: BrowserViewModel? = null,
    showHidden: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String, Destination, BookmarkList) -> Unit,
) {
    val place = destination as? Destination.Place
    var name by rememberSaveable { mutableStateOf(initialName) }
    var path by rememberSaveable { mutableStateOf(place?.path.orEmpty()) }
    var list by rememberSaveable { mutableStateOf(initialList) }
    var pickedFolder by rememberSaveable { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val usePath = pickedFolder || (place != null && (place.path != null || place.location == null))
    if (choosing && viewModel != null) {
        FolderChooser(viewModel, showHidden, onDismiss = { choosing = false }) { chosen ->
            path = chosen
            pickedFolder = true
            choosing = false
        }
        return
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                when {
                    usePath -> OutlinedTextField(value = path, onValueChange = { path = it }, singleLine = true,
                        label = { Text("Path") }, placeholder = { Text("/storage/emulated/0/Music") },
                        modifier = Modifier.fillMaxWidth())
                    destination is Destination.Place && destination.path == null && destination.location != null ->
                        Text("Opens " + destination.location.crumbs.joinToString(" › ") { it.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    destination is Destination.Tool -> Text("This bookmark opens ${destination.screen.title}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (viewModel != null) {
                    AssistChip(onClick = { choosing = true }, label = { Text("Browse") },
                        leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)) })
                }
                if (showList) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        BookmarkList.entries.forEachIndexed { index, option ->
                            SegmentedButton(selected = list == option, onClick = { list = option },
                                shape = SegmentedButtonDefaults.itemShape(index, BookmarkList.entries.size)) {
                                Text(option.label)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            // Name and path are passed on untrimmed: trailing spaces can be part of a filename.
            TextButton(onClick = { onConfirm(name, if (usePath) Destination.Place(path = path) else destination, list) },
                enabled = !usePath || path.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
