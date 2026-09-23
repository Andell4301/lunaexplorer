@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.storage.MediaCategory
import com.lunaexplorer.core.*
import com.lunaexplorer.app.model.*

@Composable
internal fun LocationsPanel(
    state: BrowserState,
    viewModel: BrowserViewModel,
    actions: LunaActions,
    listState: LazyListState,
    onSettings: () -> Unit,
    onQueue: () -> Unit,
    onRecycleBin: () -> Unit,
    onAddBookmark: () -> Unit,
    onRenameBookmark: (Bookmark, BookmarkList) -> Unit,
    onStorage: () -> Unit,
    onApps: () -> Unit,
    onProcedures: () -> Unit,
    onNavigated: () -> Unit,
) {
    var disconnect by remember { mutableStateOf<StorageRoot?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.NightlightRound, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text("Luna", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        }
        FastLazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 12.dp)) {
            item {
                LocationRow("Home", Icons.Outlined.Home, Hue.ACCENT, selected = state.screen == Screen.HOME,
                    onClick = { viewModel.showScreen(Screen.HOME); onNavigated() })
            }
            if (!state.fullAccess) {
                item {
                    FullAccessCard(onGrant = { onNavigated(); actions.requestFullAccess() })
                }
            }
            item { SectionLabel("STORAGE") }
            items(state.roots.filter { !it.hidden }, key = { "root:${it.ref.provider}:${it.ref.key}" }) { root ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        LocationRow(root.title, rootIcon(root), rootHue(root),
                            selected = state.location?.crumbs?.firstOrNull()?.ref == root.ref,
                            onClick = { viewModel.navigateRoot(root); onNavigated() }) {
                            if (root.totalBytes != null && root.freeBytes != null) {
                                UsageBar(root.freeBytes!!, root.totalBytes!!)
                            } else if (root.description.isNotBlank()) {
                                Text(root.description, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (root.removable) {
                        ToolIcon(Icons.Outlined.LinkOff, "Disconnect ${root.title}",
                            onClick = { disconnect = root })
                    }
                }
            }
            item {
                TextButton(onClick = { onNavigated(); actions.grantFolder(state.directoryPath) },
                    modifier = Modifier.padding(horizontal = 12.dp)) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add a location")
                }
            }
            if (state.trashCount > 0) {
                item {
                    LocationRow("Recycle bin", Icons.Outlined.DeleteOutline,
                        onClick = { onNavigated(); onRecycleBin() }) {
                        Text("${state.trashCount} ${if (state.trashCount == 1) "item" else "items"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("BOOKMARKS", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = onAddBookmark,
                        contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Add") }
                }
            }
            if (state.bookmarks.isNotEmpty()) {
                itemsIndexed(state.bookmarks, key = { _, it -> "bookmark:${it.id}" }) { index, bookmark ->
                    BookmarkRow(
                        bookmark = bookmark,
                        index = index,
                        count = state.bookmarks.size,
                        list = BookmarkList.SIDEBAR,
                        selected = state.location?.ref?.let { here ->
                            (bookmark.destination as? Destination.Place)?.location?.ref == here
                        } == true,
                        viewModel = viewModel,
                        onOpen = onNavigated,
                        onRename = { onRenameBookmark(bookmark, BookmarkList.SIDEBAR) },
                    )
                }
            }
            item { SectionLabel("ON THIS DEVICE") }
            item {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MediaCategory.entries.forEach { category ->
                        SuggestionChip(
                            onClick = { onNavigated(); viewModel.showCategory(category) },
                            label = { Text(category.label) },
                            icon = { ChipIcon(categoryIcon(category), categoryHue(category)) },
                        )
                    }
                }
            }
            item { SectionLabel("TOOLS") }
            item {
                LocationRow("Storage", Icons.Outlined.PieChart, Hue.ANALYSIS, onClick = { onNavigated(); onStorage() })
            }
            item {
                LocationRow("Applications", Icons.Outlined.Android, Hue.PACKAGE, onClick = { onNavigated(); onApps() })
            }
            item {
                LocationRow("Stored procedures", Icons.Outlined.AccountTree, Hue.ACCENT,
                    selected = state.screen == Screen.PROCEDURES, onClick = { onNavigated(); onProcedures() })
            }
            if (state.recent.isNotEmpty()) {
                item { SectionLabel("RECENT") }
                items(state.recent.take(4), key = { "recent:${it.ref}" }) { location ->
                    LocationRow(location.title, Icons.Outlined.History,
                        onClick = { viewModel.navigate(location); onNavigated() })
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onNavigated(); onQueue() }, modifier = Modifier.weight(1f)) {
                BadgedBox(badge = { if (state.activeOperations > 0) Badge { Text(state.activeOperations.toString()) } }) {
                    Icon(Icons.Outlined.SyncAlt, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text("Operations")
            }
            ToolIcon(Icons.Outlined.Settings, "Settings", onClick = { onNavigated(); onSettings() })
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
    disconnect?.let { root ->
        AlertDialog(onDismissRequest = { disconnect = null }, title = { Text("Disconnect ${root.title}?") },
            text = { Text("Luna releases its permission to this folder. Your files stay where they are.") },
            confirmButton = { TextButton(onClick = { viewModel.forgetRoot(root); disconnect = null }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { disconnect = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FullAccessCard(onGrant: () -> Unit) {
    Surface(Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth(),
        shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(14.dp)) {
            Text("Turn on full storage access", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(4.dp))
            Text("Browse every folder without granting them one at a time.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onGrant) { Text("Turn on") }
        }
    }
}

@Composable
private fun UsageBar(free: Long, total: Long) {
    val used = (total - free).coerceIn(0, total)
    Column(Modifier.padding(top = 4.dp)) {
        LinearProgressIndicator(
            progress = { if (total > 0) used.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(3.dp))
        Text("${formatBytes(free)} free of ${formatBytes(total)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun LocationRow(
    title: String,
    icon: ImageVector,
    hue: Hue = Hue.PLAIN,
    selected: Boolean = false,
    onClick: () -> Unit,
    detail: (@Composable () -> Unit)? = null,
) {
    Surface(onClick = onClick, shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp).fillMaxWidth().semantics { this.selected = selected }) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            KindIcon(icon, hue, null, plainSize = 20.dp, badgeSize = 30.dp,
                plainTint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                detail?.invoke()
            }
        }
    }
}

@Composable
internal fun ChipIcon(icon: ImageVector, hue: Hue) {
    KindIcon(icon, hue, null, plainSize = SuggestionChipDefaults.IconSize, badgeSize = 22.dp,
        plainTint = LocalContentColor.current)
}

internal fun categoryIcon(category: MediaCategory): ImageVector = when (category) {
    MediaCategory.IMAGES -> Icons.Outlined.Image
    MediaCategory.VIDEO -> Icons.Outlined.Movie
    MediaCategory.AUDIO -> Icons.Outlined.AudioFile
    MediaCategory.DOCUMENTS -> Icons.Outlined.Description
    MediaCategory.ARCHIVES -> Icons.Outlined.FolderZip
    MediaCategory.PACKAGES -> Icons.Outlined.Android
}

internal fun rootIcon(root: StorageRoot): ImageVector = when (root.kind) {
    RootKind.INTERNAL -> Icons.Outlined.Smartphone
    RootKind.SD_CARD -> Icons.Outlined.SdCard
    RootKind.USB -> Icons.Outlined.Usb
    RootKind.SYSTEM -> Icons.Outlined.DeveloperBoard
    RootKind.APP -> Icons.Outlined.Inventory2
    RootKind.FOLDER -> Icons.Outlined.FolderShared
    RootKind.NETWORK -> Icons.Outlined.Lan
}
