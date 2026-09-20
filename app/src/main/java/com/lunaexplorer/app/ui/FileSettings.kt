@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.data.OpenDefault
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.DropAction
import com.lunaexplorer.app.model.Preferences
import com.lunaexplorer.app.model.SortOrder
import com.lunaexplorer.app.model.ViewMode
import com.lunaexplorer.app.storage.MediaCategory
import kotlin.math.round

@Composable
internal fun FileSettings(
    state: BrowserState,
    onChange: (Preferences) -> Unit,
    onMediaCategories: () -> Unit,
    onOpenDefaults: () -> Unit,
    onFolderViews: () -> Unit,
) {
    val preferences = state.preferences
    SectionHeading("Default view")
    Text("What a folder looks like and how it is ordered; individual folder settings take priority.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ViewMode.entries.forEach { mode ->
            FilterChip(preferences.view == mode, { onChange(preferences.copy(view = mode)) },
                label = { Text(mode.label) })
        }
    }
    if (preferences.view == ViewMode.GRID_CUSTOM) {
        val cell = preferences.gridCell.takeIf { it in TILE_DP } ?: ViewMode.GRID_MEDIUM.cell
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${cell}dp", Modifier.width(56.dp), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = cell.toFloat(),
                onValueChange = { onChange(preferences.copy(gridCell = round(it).toInt())) },
                valueRange = TILE_DP.first.toFloat()..TILE_DP.last.toFloat(),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Tile size" },
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Text("Sort by", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SortOrder.entries.forEach { order ->
            FilterChip(preferences.sort == order, { onChange(preferences.copy(sort = order)) },
                label = { Text(order.label) })
        }
    }
    SortDirectionRow(preferences.descending) { onChange(preferences.copy(descending = it)) }
    SwitchRow("Sections", preferences.sections) { onChange(preferences.copy(sections = it)) }
    SwitchRow("Show hidden files", preferences.showHidden) { onChange(preferences.copy(showHidden = it)) }
    SwitchRow("Thumbnails", preferences.thumbnails) { onChange(preferences.copy(thumbnails = it)) }
    SectionHeading("Drag and drop")
    Text("What a dragged file does when it lands on a folder. Long press one to pick it up.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    DropAction.entries.forEach { action ->
        ChoiceRow(action.label, action == preferences.dropAction) {
            onChange(preferences.copy(dropAction = action))
        }
    }
    SectionHeading("Rename")
    SwitchRow("Rename includes extension", preferences.renameIncludesExtension) {
        onChange(preferences.copy(renameIncludesExtension = it))
    }
    Spacer(Modifier.height(8.dp))
    SettingsLink("Media categories", "Choose which files appear in Photos, Videos, Documents and other categories", onMediaCategories)
    SettingsLink("Default apps", "What opens each kind of file", onOpenDefaults)
    SettingsLink("Per-folder display settings", when (state.folderViews.size) {
        0 -> "No folder keeps a layout of its own"
        1 -> "One folder keeps a layout or ordering of its own"
        else -> "${state.folderViews.size} folders keep a layout or ordering of their own"
    }, onFolderViews)
    Text("The default layout, sorting, thumbnails and hidden files live in View and sort, on the folder menu.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
}


@Composable
internal fun OpenDefaultsSetting(viewModel: BrowserViewModel) {
    var defaults by remember { mutableStateOf<List<OpenDefault>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { defaults = viewModel.openWith.defaults() }

    val current = defaults
    when {
        current == null -> Text("Reading", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        current.isEmpty() -> Text("No file type has a standing choice.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> {
            current.forEach { entry ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.key.substringAfter(':'), style = MaterialTheme.typography.bodyLarge)
                        Text(entry.label.ifEmpty { entry.target }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { viewModel.openWith.forget(entry.key) { reload++ } }) { Text("Reset") }
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { viewModel.openWith.forgetAll { reload++ } }) { Text("Reset all") }
        }
    }
}

@Composable
internal fun FolderViews(state: BrowserState, viewModel: BrowserViewModel) {
    val views = state.folderViews
    if (views.isEmpty()) {
        Text("No folder keeps a layout or ordering of its own. Give one to the folder you are in " +
            "from View and sort, on the folder menu.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var open by remember { mutableStateOf<String?>(null) }
    views.forEach { (key, own) ->
        val name = folderName(key)
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .combinedClickable(onClick = { open = key.takeIf { open != key } }, role = Role.Button),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${own.view.label} · ${own.sort.label}${if (own.descending) ", descending" else ""}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ToolIcon(Icons.Outlined.Close, "Forget $name") { viewModel.forgetFolderView(key) }
            }
            if (open == key) {
                Text(key, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ViewMode.entries.forEach { mode ->
                        FilterChip(own.view == mode, { viewModel.setFolderView(key, own.copy(view = mode)) },
                            label = { Text(mode.label) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SortOrder.entries.forEach { order ->
                        FilterChip(own.sort == order, { viewModel.setFolderView(key, own.copy(sort = order)) },
                            label = { Text(order.label) })
                    }
                }
                SortDirectionRow(own.descending) { viewModel.setFolderView(key, own.copy(descending = it)) }
            }
            HorizontalDivider()
        }
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = { viewModel.forgetFolderViews() }) { Text("Forget all ${views.size}") }
}

private fun folderName(key: String): String = key.trimEnd('/').substringAfterLast('/')
    .substringAfterLast("%3A").substringAfterLast(':').ifEmpty { key }

@Composable
internal fun CategoryExtras(preferences: Preferences, onChange: (Preferences) -> Unit) {
    var category by remember { mutableStateOf(MediaCategory.AUDIO) }
    var typed by remember { mutableStateOf("") }
    val current = preferences.categoryExtras[category.name].orEmpty()

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MediaCategory.entries.forEach { option ->
            FilterChip(category == option, { category = option }, label = { Text(option.label) })
        }
    }
    Spacer(Modifier.height(6.dp))
    Text("Defaults", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
    Text(category.commonExtensions.joinToString(", ") { ".$it" },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (category.extensions.isEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(if (category.mediaType != null) category.defaultsDescription
            else "Includes ${category.defaultsDescription}.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(12.dp))
    Text("Additional extensions", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("Extension") },
            placeholder = { Text("m4b") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                val cleaned = typed.trim().trimStart('.').lowercase()
                if (cleaned.isNotEmpty()) {
                    onChange(preferences.copy(
                        categoryExtras = preferences.categoryExtras + (category.name to (current + cleaned)),
                    ))
                    typed = ""
                }
            },
            enabled = typed.isNotBlank(),
        ) { Text("Add") }
    }
    if (current.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            current.sorted().forEach { extension ->
                InputChip(
                    selected = false,
                    onClick = {
                        onChange(preferences.copy(
                            categoryExtras = preferences.categoryExtras + (category.name to (current - extension)),
                        ))
                    },
                    label = { Text(".$extension") },
                    trailingIcon = { Icon(Icons.Outlined.Close, "Remove", Modifier.size(14.dp)) },
                )
            }
        }
    }
}
