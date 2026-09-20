@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lunaexplorer.app.storage.SqlObject
import com.lunaexplorer.app.storage.SqlPage
import com.lunaexplorer.app.storage.SqliteBrowser
import com.lunaexplorer.core.Entry

@Composable
fun SqliteScreen(entry: Entry, viewModel: BrowserViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val browser = remember { SqliteBrowser(context) }
    var handle by remember(entry.ref) { mutableStateOf<SqliteBrowser.Handle?>(null) }
    var failure by remember(entry.ref) { mutableStateOf<String?>(null) }
    var objects by remember(entry.ref) { mutableStateOf<List<SqlObject>>(emptyList()) }
    var table by remember(entry.ref) { mutableStateOf<String?>(null) }
    var page by remember(entry.ref) { mutableStateOf<SqlPage?>(null) }
    var offset by remember(entry.ref) { mutableIntStateOf(0) }
    var query by remember(entry.ref) { mutableStateOf("") }
    var running by remember(entry.ref) { mutableStateOf(false) }
    var editing by remember(entry.ref) { mutableStateOf<CellEdit?>(null) }

    LaunchedEffect(entry.ref) {
        val path = viewModel.files.absolutePathOf(entry)
        if (path == null) {
            failure = "A database has to be a real file; this one is only reachable as a stream."
            return@LaunchedEffect
        }
        runCatching { browser.open(path, forWriting = false) }.fold(
            onSuccess = {
                handle = it
                objects = browser.objects(it)
                table = objects.firstOrNull { candidate -> candidate.kind == "table" }?.name
            },
            onFailure = { failure = it.message ?: "This database could not be opened" },
        )
    }
    DisposableEffect(entry.ref) { onDispose { handle?.close() } }

    LaunchedEffect(table, offset, handle) {
        val open = handle ?: return@LaunchedEffect
        val name = table ?: return@LaunchedEffect
        page = runCatching { browser.page(open, name, offset) }.getOrNull()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(entry.name, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            handle?.let {
                                if (it.writable) "${objects.size} objects · editable" else "${objects.size} objects · read-only"
                            } ?: "Opening",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (handle?.writable == false) {
                        TextButton(onClick = {
                            val path = handle?.path ?: return@TextButton
                            handle?.close()
                            viewModel.runInBackground {
                                runCatching { browser.open(path, forWriting = true) }.fold(
                                    onSuccess = { handle = it; objects = browser.objects(it) },
                                    onFailure = { failure = it.message },
                                )
                            }
                        }) { Text("Edit") }
                    }
                }
                HorizontalDivider()

                failure?.let {
                    Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
                }
                handle?.notes?.forEach { note ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text(note, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (objects.isNotEmpty()) {
                    Row(Modifier.fastHorizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        objects.forEach { item ->
                            FilterChip(
                                selected = table == item.name,
                                onClick = { table = item.name; offset = 0 },
                                label = {
                                    Text(item.rowCount?.let { "${item.name} (${it})" } ?: item.name)
                                },
                                enabled = item.kind == "table" || item.kind == "view",
                            )
                        }
                    }
                    HorizontalDivider()
                }

                val shown = page
                if (shown != null) {
                    val rowsState = rememberLazyListState()
                    // fastScroll sits outside the horizontal scroller so the vertical thumb stays on screen.
                    Column(Modifier.weight(1f).fastScroll(rowsState)) {
                        Row(Modifier.fastHorizontalScroll(rememberScrollState())) {
                            Column {
                                Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                    shown.columns.forEach { name ->
                                        Text(name, Modifier.width(COLUMN_WIDTH).padding(8.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                LazyColumn(Modifier.weight(1f, fill = false), state = rowsState) {
                                    items(shown.rows) { row ->
                                        Row {
                                            row.values.forEachIndexed { index, value ->
                                                val column = shown.columns.getOrNull(index).orEmpty()
                                                val identity = row.identity
                                                Text(
                                                    value.text,
                                                    Modifier.width(COLUMN_WIDTH)
                                                        .clickable(enabled = handle?.writable == true &&
                                                            identity != null && !value.isBlob) {
                                                            editing = CellEdit(column, identity!!, value.text, value.type)
                                                        }
                                                        .padding(8.dp),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = when {
                                                        value.isNull -> MaterialTheme.colorScheme.onSurfaceVariant
                                                        value.isBlob -> MaterialTheme.colorScheme.tertiary
                                                        else -> MaterialTheme.colorScheme.onSurface
                                                    },
                                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .25f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { offset = (offset - 100).coerceAtLeast(0) },
                            enabled = offset > 0) { Text("Previous") }
                        Text("${offset + 1}–${offset + shown.rows.size}", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { offset += 100 }, enabled = shown.more) { Text("Next") }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }

                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("SQL") },
                        placeholder = { Text("select * from …") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = query.isNotBlank() && handle != null && !running,
                        onClick = {
                            val open = handle ?: return@TextButton
                            running = true
                            viewModel.runInBackground {
                                browser.execute(open, query).fold(
                                    onSuccess = { page = it; table = null },
                                    onFailure = { failure = it.message },
                                )
                                objects = browser.objects(open)
                                running = false
                            }
                        },
                    ) { Text("Run") }
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }

    editing?.let { cell ->
        val column = cell.column
        var value by remember(cell) { mutableStateOf(cell.initial) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit $column") },
            text = {
                OutlinedTextField(value, { value = it }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    val open = handle
                    val name = table
                    editing = null
                    if (open != null && name != null) {
                        viewModel.runInBackground {
                            browser.updateCell(open, name, column, cell.identity, value, cell.type)
                                .onFailure { failure = it.message }
                            page = runCatching { browser.page(open, name, offset) }.getOrNull()
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

private val COLUMN_WIDTH = 150.dp

/** [type] is the cell's SQLite storage class, used to bind the new value. */
private data class CellEdit(val column: String, val identity: String, val initial: String, val type: Int)
