@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lunaexplorer.app.model.*
import com.lunaexplorer.core.*

@Composable
internal fun QueueDialog(
    operations: List<QueueItem>,
    includeExtension: Boolean,
    onRemember: (Boolean) -> Unit,
    onCancel: (String) -> Unit,
    onConflict: (String, ConflictPolicy, String?) -> Unit,
    onInspect: (String) -> Unit,
    onReport: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp).heightIn(max = 680.dp),
            shape = RoundedCornerShape(20.dp), tonalElevation = 4.dp) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, top = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Operations", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    ToolIcon(Icons.Outlined.Close, "Close", onClick = onDismiss)
                }
                HorizontalDivider()
                if (operations.isEmpty()) {
                    Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.TaskAlt, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(10.dp))
                        Text("Nothing running", style = MaterialTheme.typography.titleMedium)
                    }
                } else FastLazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(operations, key = { it.id }) { operation ->
                        OperationRow(operation, includeExtension, onRemember, onCancel, onConflict, onInspect, onReport)
                    }
                }
            }
        }
    }
}

@Composable
private fun OperationRow(
    operation: QueueItem,
    includeExtension: Boolean,
    onRemember: (Boolean) -> Unit,
    onCancel: (String) -> Unit,
    onConflict: (String, ConflictPolicy, String?) -> Unit,
    onInspect: (String) -> Unit,
    onReport: (String) -> Unit,
) {
    var expanded by rememberSaveable(operation.id) { mutableStateOf(false) }
    val status = operation.status.uppercase()
    val conflict = status == "CONFLICT"
    val running = status == "RUNNING"
    val queued = status == "QUEUED"
    val cancellable = running || queued || conflict
    val failed = status in setOf("FAILED", "INTERRUPTED", "PARTIAL")
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { expanded = !expanded }, role = Role.Button).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(when {
                conflict -> Icons.AutoMirrored.Outlined.HelpOutline
                failed -> Icons.Outlined.ErrorOutline
                running || queued -> Icons.Outlined.SyncAlt
                status == "CANCELLED" -> Icons.Outlined.Cancel
                else -> Icons.Outlined.TaskAlt
            }, null, tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(operation.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(operation.status.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (cancellable) ToolIcon(Icons.Outlined.Close, "Cancel", onClick = { onCancel(operation.id) })
            else Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
        if (operation.currentName.isNotBlank() || operation.bytes > 0) {
            Text(listOfNotNull(operation.currentName.takeIf { it.isNotBlank() },
                operation.bytes.takeIf { it > 0 }?.let { formatBytes(it) }).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (conflict) {
            if (operation.detail.isNotBlank()) {
                Text(operation.detail, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
            var renaming by rememberSaveable(operation.id) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().fastHorizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (operation.conflictFolders) {
                    TextButton(onClick = { onConflict(operation.id, ConflictPolicy.MERGE, null) }) { Text("Merge") }
                }
                if (operation.conflictFiles || !operation.conflictFolders) {
                    TextButton(onClick = { onConflict(operation.id, ConflictPolicy.REPLACE, null) }) { Text("Overwrite") }
                }
                TextButton(onClick = { onConflict(operation.id, ConflictPolicy.KEEP_BOTH, null) }) { Text("Keep both") }
                // A typed name resolves only the first waiting item; the rest of the batch keeps waiting.
                if (operation.conflictNames.firstOrNull()?.isNotBlank() == true) {
                    TextButton(onClick = { renaming = true }) { Text("Rename…") }
                }
                TextButton(onClick = { onConflict(operation.id, ConflictPolicy.SKIP, null) }) { Text("Skip") }
            }
            // The conflict list can empty while the dialog is open, if another decision lands first.
            val waiting = operation.conflicts.firstOrNull()
            if (renaming && waiting != null) {
                RenameDialog(
                    original = waiting.name,
                    directory = waiting.directory,
                    stateKey = "${operation.id}:${waiting.name}",
                    includeExtension = includeExtension,
                    onRemember = onRemember,
                    onDismiss = { renaming = false },
                    onConfirm = { renamed -> renaming = false; onConflict(operation.id, ConflictPolicy.ASK, renamed) },
                )
            }
        } else if (operation.detail.isNotBlank() && (failed || expanded)) {
            Text(operation.detail, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (expanded) {
            operation.results.forEach { result ->
                Text(result, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (operation.artifacts.isNotEmpty()) {
                Text("Recorded objects", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge)
                operation.artifacts.forEach { artifact ->
                    SelectionContainer {
                        Text("${artifact.provider}: ${artifact.key}", Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().fastHorizontalScroll(rememberScrollState())) {
                TextButton(onClick = { onInspect(operation.id) }) { Text("Inspect destination") }
                TextButton(onClick = { onReport(operation.id) }) { Text("Share report") }
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
}
