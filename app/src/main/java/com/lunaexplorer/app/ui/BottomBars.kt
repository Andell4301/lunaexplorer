@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lunaexplorer.core.*
import com.lunaexplorer.app.model.*

@Composable
internal fun FilterStrip(state: BrowserState, viewModel: BrowserViewModel, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.FilterAlt, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = state.filter,
                onValueChange = viewModel::setFilter,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).padding(vertical = 12.dp).focusRequester(focus),
                decorationBox = { field ->
                    if (state.filter.isEmpty()) {
                        Text("Filter this folder", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    field()
                },
            )
            Text("${state.entries.size}", Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToolIcon(Icons.Outlined.Close, "Close filter", onClick = { viewModel.setFilter(""); onClose() })
        }
    }
}

@Composable
internal fun ArchiveOpeningStrip(opening: ArchiveOpening, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FolderZip, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text("Opening ${opening.name}", style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val total = opening.total
                    Text(when {
                        opening.fromStart && total != null -> "Reading from the start  ·  ${formatBytes(opening.bytesRead)} of ${formatBytes(total)}"
                        opening.fromStart -> "Reading from the start  ·  ${formatBytes(opening.bytesRead)}"
                        else -> "Reading the index  ·  ${formatBytes(opening.bytesRead)}"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            val total = opening.total
            if (opening.fromStart && total != null && total > 0) {
                LinearProgressIndicator(progress = { (opening.bytesRead.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun OperationsStrip(operation: QueueItem, active: Int, onQueue: () -> Unit) {
    val status = operation.status.uppercase()
    val waiting = status == "CONFLICT" || status == "INTERRUPTED"
    val colors = MaterialTheme.colorScheme
    val accent = if (waiting) colors.onErrorContainer else colors.primary
    val muted = if (waiting) colors.onErrorContainer else colors.onSurfaceVariant
    Surface(color = if (waiting) colors.errorContainer else colors.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(onClickLabel = "Open operations", role = Role.Button, onClick = onQueue)
                .heightIn(min = 48.dp).padding(start = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (waiting) Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = accent)
            else CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(operation.title, style = MaterialTheme.typography.bodyLarge,
                    color = if (waiting) colors.onErrorContainer else colors.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (waiting) status.lowercase().replaceFirstChar { it.uppercase() }
                    else listOfNotNull("$active running", operation.bytes.takeIf { it > 0 }?.let { formatBytes(it) })
                        .joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall, color = muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(if (waiting) "Resolve" else "Operations",
                style = MaterialTheme.typography.labelLarge, color = accent)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = accent)
        }
    }
}

@Composable
internal fun SearchStrip(state: BrowserState, viewModel: BrowserViewModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(state.searchSummary, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                if (state.searching) TextButton(onClick = viewModel::cancelSearch) { Text("Stop") }
                ToolIcon(Icons.Outlined.Close, "Close search", onClick = viewModel::closeSearch)
            }
            val category = (state.view as? View.Category)?.category
            if (category != null) {
                var adding by remember(category) { mutableStateOf(false) }
                var typed by remember(category) { mutableStateOf("") }
                val active = state.extensions
                val extras = state.preferences.categoryExtras[category.name].orEmpty()
                val view = LocalView.current
                FlowRow(
                    Modifier.padding(start = 40.dp, end = 14.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(active.isEmpty(), { viewModel.clearExtensions() }, label = { Text("All") })
                    (category.commonExtensions + extras).distinct().forEach { extension ->
                        val remove = { viewModel.removeCategoryExtra(category, extension) }
                        val removable = if (extension !in extras) Modifier else Modifier
                            .lunaLongPress(view, extension, remove)
                            // Accessibility equivalent of the long press.
                            .semantics { onLongClick(label = "Remove $extension") { remove(); true } }
                        FilterChip(
                            selected = extension in active,
                            onClick = { viewModel.toggleExtension(extension) },
                            label = { Text(extension.uppercase()) },
                            modifier = removable,
                        )
                    }
                    AssistChip(onClick = { adding = !adding }, label = { Text("+") })
                }
                if (adding) {
                    Row(Modifier.padding(start = 40.dp, end = 14.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(typed, { typed = it }, singleLine = true,
                            label = { Text("Extension") }, modifier = Modifier.weight(1f))
                        TextButton(enabled = typed.isNotBlank(), onClick = {
                            val cleaned = typed.trim().trimStart('.').lowercase()
                            if (cleaned.isNotEmpty()) {
                                viewModel.setPreferences(state.preferences.copy(
                                    categoryExtras = state.preferences.categoryExtras +
                                        (category.name to (extras + cleaned)),
                                ))
                                viewModel.setExtensions(active + cleaned)
                                viewModel.showCategory(category)
                            }
                            typed = ""; adding = false
                        }) { Text("Add") }
                    }
                }
            }
            if (state.searchNote.isNotEmpty()) {
                Text(state.searchNote, Modifier.padding(start = 40.dp, end = 14.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun SelectionBar(
    state: BrowserState,
    viewModel: BrowserViewModel,
    actions: LunaActions,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit,
    onOpenAs: (Entry) -> Unit,
    onArchive: () -> Unit,
    onExtract: () -> Unit,
) {
    val entries = state.selectedEntries
    val single = entries.singleOrNull()
    val extractable = single != null && LocalArchives.current?.canExtract(single) == true
    val browsable = single != null && LocalArchives.current?.canBrowse(single) == true
    val insideArchive = viewModel.insideArchive()
    val readable = { entry: Entry -> entry.directory || Capability.READ in entry.capabilities }
    val shareable = single != null && !single.directory && Capability.READ in single.capabilities
    var more by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 3.dp) {
        Column {
            FlowRow(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                ToolIcon(Icons.Outlined.Close, "Clear selection", onClick = viewModel::clearSelection)
                Text("${state.selected.size} of ${state.entries.size}",
                    Modifier.padding(end = 4.dp), style = MaterialTheme.typography.labelLarge)
                ScopeChip("All", "Select all", onClick = viewModel::selectAll)
                ScopeChip("Files", "Select all files",
                    enabled = state.entries.any { !it.directory }, onClick = viewModel::selectAllFiles)
                ScopeChip("Folders", "Select all folders",
                    enabled = state.entries.any { it.directory }, onClick = viewModel::selectAllFolders)
                ScopeChip("Invert", "Invert selection", onClick = viewModel::invertSelection)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (insideArchive) {
                    BarAction(Icons.Outlined.Unarchive, "Extract",
                        enabled = entries.isNotEmpty() && entries.all(readable),
                        onClick = onExtract)
                } else {
                    BarAction(Icons.Outlined.ContentCopy, "Copy",
                        enabled = entries.isNotEmpty() && entries.all(readable),
                        onClick = { viewModel.operations.copySelection(false) })
                }
                BarAction(Icons.AutoMirrored.Outlined.DriveFileMove, "Move",
                    enabled = entries.isNotEmpty() && entries.all { Capability.DELETE in it.capabilities && readable(it) },
                    onClick = { viewModel.operations.copySelection(true) })
                BarAction(Icons.Outlined.Share, "Share",
                    enabled = shareable, onClick = { single?.let { actions.open(it, true) } })
                BarAction(Icons.Outlined.Edit, "Rename",
                    enabled = entries.isNotEmpty() && entries.all { Capability.RENAME in it.capabilities },
                    onClick = onRename)
                BarAction(Icons.Outlined.DeleteOutline, "Delete",
                    enabled = entries.isNotEmpty() && entries.all { Capability.DELETE in it.capabilities },
                    onClick = onDelete)
                BarAction(Icons.Outlined.MoreHoriz, "More", onClick = { more = true })
            }
        }
    }
    if (more) {
        val close = { more = false }
        BottomSheet(onDismiss = close, expanded = true) {
          Column(Modifier.fastVerticalScroll(rememberScrollState())) {
            if (insideArchive) {
                SheetAction(Icons.Outlined.ContentCopy, "Copy", "Take a copy somewhere else",
                    enabled = entries.isNotEmpty() && entries.all(readable)) {
                    close(); viewModel.operations.copySelection(false)
                }
            }
            SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "Open", "Choose an app, or another type",
                enabled = shareable) { close(); single?.let(onOpenAs) }
            SheetAction(Icons.Outlined.ContentPaste, "Copy to clipboard", "To paste into another app",
                enabled = entries.isNotEmpty() && entries.all { !it.directory && Capability.READ in it.capabilities }) {
                close(); viewModel.files.copyToClipboard(entries); viewModel.clearSelection()
            }
            SheetAction(Icons.Outlined.MyLocation, "Go to location", "Open the folder it is in",
                enabled = single != null && state.searchActive) { close(); single?.let { viewModel.revealEntry(it) } }
            SheetAction(Icons.Outlined.Archive, "Add to archive", "Make a zip or tar of these",
                enabled = entries.isNotEmpty()) { close(); onArchive() }
            if (!insideArchive) {
                SheetAction(Icons.Outlined.Unarchive, "Extract", "Unpack it, here or somewhere you choose",
                    enabled = extractable) { close(); onExtract() }
            }
            SheetAction(Icons.Outlined.FolderZip, "Look inside", "Browse it without unpacking it",
                enabled = browsable) { close(); single?.let { viewModel.browseArchive(it) } }
            SheetAction(Icons.Outlined.Info, "Properties", "Size, dates and everything else known",
                enabled = entries.isNotEmpty()) { close(); onProperties() }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
          }
        }
    }
}

@Composable
private fun ScopeChip(label: String, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        modifier = Modifier.semantics { contentDescription = description },
    )
}

@Composable
private fun RowScope.BarAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val ink = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.38f)
    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
        .heightIn(min = 48.dp).padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(21.dp), tint = ink)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ClipboardBar(clipboard: Clipboard, state: BrowserState, viewModel: BrowserViewModel) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val unpacking = clipboard.extract != null || viewModel.carryingArchiveMembers()
            Icon(when {
                unpacking -> Icons.Outlined.Unarchive
                clipboard.move -> Icons.AutoMirrored.Outlined.DriveFileMove
                else -> Icons.Outlined.ContentCopy
            }, null, Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            var listing by remember { mutableStateOf(false) }
            Column(Modifier.weight(1f).combinedClickable(onClick = { listing = !listing }, role = Role.Button)
                .padding(vertical = 6.dp)) {
                Text(when {
                    clipboard.extract != null -> "Extracting"
                    unpacking -> "Extracting ${clipboard.entries.size}"
                    clipboard.move -> "Moving ${clipboard.entries.size}"
                    else -> "Copying ${clipboard.entries.size}"
                }, style = MaterialTheme.typography.labelLarge)
                Text(clipboard.entries.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodySmall, maxLines = if (listing) 6 else 1,
                    overflow = TextOverflow.Ellipsis)
            }
            val canPaste = state.canPasteHere
            // The archive password is asked at paste, not at copy: the clipboard outlives navigation.
            if (!clipboard.move && clipboard.extract == null) {
                TextButton(onClick = {
                    viewModel.requireArchivePassword(clipboard.entries) { viewModel.operations.paste(keep = true) }
                }, enabled = canPaste) { Text("Paste, keep") }
            }
            TextButton(onClick = {
                viewModel.requireArchivePassword(clipboard.entries) { viewModel.operations.paste() }
            }, enabled = canPaste) { Text(if (unpacking) "Extract here" else "Paste here") }
            ToolIcon(Icons.Outlined.Close, "Cancel", onClick = viewModel.operations::clearClipboard)
        }
    }
}
