@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lunaexplorer.core.StorageRoot
import com.lunaexplorer.app.storage.MediaCategory
import com.lunaexplorer.app.model.*

@Composable
internal fun HomeScreen(
    state: BrowserState,
    viewModel: BrowserViewModel,
    onRenameBookmark: (Bookmark, BookmarkList) -> Unit,
    onAddBookmark: () -> Unit,
) {
    FastLazyColumn(Modifier.fillMaxSize().testTag("home-screen"), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { HomeHeading("STORAGE") }
        items(state.roots.filter { !it.hidden }, key = { "root:${it.ref.provider}:${it.ref.key}" }) { root ->
            LocationRow(root.title, rootIcon(root), rootHue(root), onClick = { viewModel.navigateRoot(root) },
                detail = rootDetail(root))
        }

        item { HomeHeading("ON THIS DEVICE") }
        item {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MediaCategory.entries.forEach { category ->
                    SuggestionChip(onClick = { viewModel.showCategory(category) },
                        label = { Text(category.label) },
                        icon = { ChipIcon(categoryIcon(category), categoryHue(category)) })
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("BOOKMARKS", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onAddBookmark, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Add") }
            }
        }
        if (state.homeBookmarks.isEmpty()) {
            item {
                Text("Add the places you open most.",
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            itemsIndexed(state.homeBookmarks, key = { _, it -> "home:${it.id}" }) { index, bookmark ->
                BookmarkRow(
                    bookmark = bookmark,
                    index = index,
                    count = state.homeBookmarks.size,
                    list = BookmarkList.HOME,
                    selected = false,
                    viewModel = viewModel,
                    onOpen = {},
                    onRename = { onRenameBookmark(bookmark, BookmarkList.HOME) },
                )
            }
        }

        item { HomeHeading("STORAGE USED") }
        item { HomeStorageSnapshot(state, viewModel) }

        item { HomeHeading("TOOLS") }
        item {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SuggestionChip(onClick = { viewModel.showScreen(Screen.STORAGE) },
                    label = { Text("Storage analysis") },
                    icon = { ChipIcon(Icons.Outlined.PieChart, Hue.ANALYSIS) })
                SuggestionChip(onClick = { viewModel.showScreen(Screen.APPS) },
                    label = { Text("Applications") },
                    icon = { ChipIcon(Icons.Outlined.Android, Hue.PACKAGE) })
                SuggestionChip(onClick = { viewModel.showScreen(Screen.RECYCLE_BIN) },
                    label = { Text("Recycle bin") },
                    icon = { ChipIcon(Icons.Outlined.DeleteOutline, Hue.PLAIN) })
                SuggestionChip(onClick = { viewModel.showScreen(Screen.PROCEDURES) },
                    label = { Text("Stored procedures") },
                    icon = { ChipIcon(Icons.Outlined.AccountTree, Hue.ACCENT) })
            }
        }
    }
}

private fun rootDetail(root: StorageRoot): (@Composable () -> Unit)? {
    val free = root.freeBytes
    val total = root.totalBytes
    return when {
        free != null -> ({
            Text(if (total != null) "${formatBytes(free)} free of ${formatBytes(total)}"
            else "${formatBytes(free)} free",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        })
        root.description.isNotBlank() -> ({
            Text(root.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        })
        else -> null
    }
}

@Composable
private fun HomeHeading(text: String) {
    Text(text, Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun HomeStorageSnapshot(state: BrowserState, viewModel: BrowserViewModel) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val measured = state.roots.filter { !it.hidden && it.totalBytes != null && it.freeBytes != null }
        if (measured.isEmpty()) {
            Text("No storage Luna can measure yet.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        measured.forEach { root ->
            val total = root.totalBytes ?: return@forEach
            val free = root.freeBytes ?: return@forEach
            val used = (total - free).coerceAtLeast(0L)
            Column {
                Row {
                    Text(root.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${formatBytes(free)} free of ${formatBytes(total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { if (total > 0) used.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        TextButton(onClick = { viewModel.showScreen(Screen.STORAGE) }) { Text("Analyse storage") }
    }
}
