@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lunaexplorer.core.TrashedItem
import java.text.DateFormat
import java.util.Date

@Composable
fun RecycleBinScreen(viewModel: BrowserViewModel) {
    val items by viewModel.trash.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmEmpty by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.operations.pruneBin() }

    var query by rememberSaveable { mutableStateOf("") }
    val shown = if (query.isBlank()) items else items.filter { it.name.contains(query.trim(), ignoreCase = true) }
    val chosen = shown.filter { it.id in selected }
    val acting = chosen.ifEmpty { shown }

    Column(Modifier.fillMaxSize()) {
        HorizontalDivider()

        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("Nothing deleted", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search the recycle bin") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
            FastLazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.id }) { item ->
                    TrashRow(item, item.id in selected) {
                        selected = if (item.id in selected) selected - item.id else selected + item.id
                    }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding()) {
                    Text(
                        when {
                            chosen.isNotEmpty() -> "${chosen.size} selected"
                            query.isBlank() -> "Acting on everything in the bin"
                            else -> "Acting on the ${shown.size} shown"
                        },
                        Modifier.padding(start = 20.dp, top = 10.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { viewModel.operations.restoreFromBin(acting); selected = emptySet() }) {
                            Icon(Icons.Outlined.RestoreFromTrash, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Put back")
                        }
                        TextButton(onClick = { confirmEmpty = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Icon(Icons.Outlined.DeleteForever, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (chosen.isEmpty()) "Empty bin" else "Delete forever")
                        }
                    }
                }
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            icon = { Icon(Icons.Outlined.DeleteForever, null) },
            title = { Text("Delete ${acting.size} ${if (acting.size == 1) "item" else "items"} forever?") },
            text = { Text("This is permanent. Nothing here can be put back afterwards.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.operations.emptyBin(acting); selected = emptySet(); confirmEmpty = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete forever") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TrashRow(item: TrashedItem, selected: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth()
        .combinedClickable(onClick = onToggle, role = Role.Checkbox)
        .heightIn(min = 62.dp).padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(
                when {
                    selected -> Icons.Outlined.CheckCircle
                    item.directory -> Icons.Outlined.Folder
                    else -> Icons.Outlined.RadioButtonUnchecked
                },
                if (selected) "Deselect ${item.originalName}" else "Select ${item.originalName}",
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(item.originalName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    if (item.directory) "Folder" else formatBytes(item.size),
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.deletedAt)),
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            item.originalPath?.let {
                Text(it.substringBeforeLast('/'), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
}
