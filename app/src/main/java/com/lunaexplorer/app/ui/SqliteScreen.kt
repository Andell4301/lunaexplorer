@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lunaexplorer.app.storage.*
import com.lunaexplorer.core.Entry
import kotlinx.coroutines.*

@Composable
fun SqliteScreen(entry: Entry, viewModel: BrowserViewModel, onDismiss: () -> Unit) = key(entry.ref) {
    val context = LocalContext.current
    val browser = remember { SqliteBrowser(context) }
    val scope = rememberCoroutineScope()
    var handle by remember { mutableStateOf<SqliteBrowser.Handle?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var objects by remember { mutableStateOf<List<SqlObject>>(emptyList()) }
    var table by remember { mutableStateOf<String?>(null) }
    var columns by remember { mutableStateOf<List<SqlColumn>>(emptyList()) }
    var page by remember { mutableStateOf<SqlPage?>(null) }
    var offset by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<String?>(null) }
    var forWriting by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var pageGeneration by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf<SqlFilter?>(null) }
    var sort by remember { mutableStateOf<SqlSort?>(null) }
    var count by remember { mutableStateOf<Long?>(null) }
    var panel by remember { mutableStateOf(SqlPanel.ROWS) }
    var cell by remember { mutableStateOf<SqlCell?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val busy = opening || loading || running != null
    val selected = objects.firstOrNull { it.name == table }

    fun choose(name: String) {
        table = name
        offset = 0
        filter = null
        sort = null
        count = null
        page = null
        failure = null
        panel = SqlPanel.ROWS
        revision++
    }

    fun action(label: String, block: suspend () -> Unit) {
        if (opening || loading || running != null) return
        running = label
        failure = null
        scope.launch {
            try { block() }
            catch (error: Exception) {
                ensureActive()
                failure = error.message ?: error.javaClass.simpleName
            } finally { running = null }
        }
    }

    fun back() {
        if (panel != SqlPanel.ROWS) {
            panel = SqlPanel.ROWS
            cell = null
            failure = null
        } else onDismiss()
    }

    LaunchedEffect(forWriting) {
        opening = true
        handle = null
        page = null
        failure = null
        var opened: SqliteBrowser.Handle? = null
        try {
            val path = withContext(Dispatchers.IO) { viewModel.files.absolutePathOf(entry) }
            if (path == null) {
                failure = "A database has to be a real file; this one is only reachable as a stream."
                return@LaunchedEffect
            }
            opened = if (forWriting) viewModel.files.openSqliteForEditing(browser, path) else browser.open(path)
            objects = browser.objects(opened)
            if (table == null) table = objects.firstOrNull { it.kind == "table" && it.name != "android_metadata" }?.name
                ?: objects.firstOrNull { it.kind == "table" }?.name
            handle = opened
            opening = false
            awaitCancellation()
        } catch (error: Exception) {
            ensureActive()
            failure = error.message ?: "This database could not be opened"
        } finally {
            handle = null
            opening = false
            withContext(NonCancellable + Dispatchers.IO) { opened?.close() }
        }
    }

    LaunchedEffect(handle, table, offset, filter, sort, revision) {
        val generation = ++pageGeneration
        loading = false
        val open = handle ?: return@LaunchedEffect
        val name = table ?: return@LaunchedEffect
        loading = true
        page = null
        failure = null
        count = null
        try {
            columns = browser.columns(open, name)
            page = browser.page(open, name, offset, filter = filter, sort = sort)
        } catch (error: Exception) {
            ensureActive()
            failure = error.message ?: error.javaClass.simpleName
        } finally { if (pageGeneration == generation) loading = false }
    }

    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(if (panel == SqlPanel.ROWS) entry.name else panel.title,
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(running ?: when {
                            opening -> "Opening"
                            loading -> "Loading rows"
                            handle == null -> "Read-only"
                            handle?.writable == true -> "${objects.size} objects · editable"
                            else -> "${objects.size} objects · read-only"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (handle?.writable == false && panel == SqlPanel.ROWS) {
                        TextButton(onClick = { forWriting = true }, enabled = !busy) { Text("Edit") }
                    }
                    if (panel == SqlPanel.ROWS) Box {
                        IconButton(onClick = { menu = true }, enabled = handle != null && !busy) {
                            Icon(Icons.Outlined.MoreVert, "Database actions")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Structure") }, enabled = table != null,
                                onClick = { menu = false; panel = SqlPanel.STRUCTURE })
                            DropdownMenuItem(text = { Text("Indexes") }, onClick = { menu = false; panel = SqlPanel.INDEXES })
                            DropdownMenuItem(text = { Text("Views") }, onClick = { menu = false; panel = SqlPanel.VIEWS })
                            DropdownMenuItem(text = { Text("Count rows") }, enabled = table != null, onClick = {
                                menu = false
                                val open = handle ?: return@DropdownMenuItem
                                val name = table ?: return@DropdownMenuItem
                                action("Counting rows") { count = browser.count(open, name, filter) }
                            })
                            DropdownMenuItem(text = { Text("Add row") },
                                enabled = handle?.writable == true && selected?.kind == "table", onClick = {
                                    menu = false; panel = SqlPanel.INSERT
                                })
                            DropdownMenuItem(text = { Text("Refresh") }, onClick = {
                                menu = false
                                val open = handle ?: return@DropdownMenuItem
                                action("Loading") {
                                    objects = browser.objects(open)
                                    if (objects.none { it.name == table }) {
                                        table = objects.firstOrNull { it.kind == "table" }?.name
                                        offset = 0; filter = null; sort = null
                                    }
                                    revision++
                                }
                            })
                        }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("sqliteLoading")) else HorizontalDivider()
                failure?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }

                if (panel == SqlPanel.ROWS) {
                    handle?.notes?.forEach { note ->
                        Text(note, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    LazyRow(Modifier.fillMaxWidth().testTag("sqliteTables"), contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(objects.filter { it.kind == "table" }, key = { it.name }) { item ->
                            FilterChip(selected = table == item.name, onClick = { choose(item.name) },
                                label = { Text(item.name) }, enabled = !busy)
                        }
                    }
                    if (table != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(table.orEmpty(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge)
                            count?.let { Text("$it rows", style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = { panel = SqlPanel.FILTER }, enabled = !busy) {
                                Text(if (filter == null && sort == null) "Filter" else "Filter · on")
                            }
                        }
                    }
                    val shown = page
                    if (shown != null) {
                        key(table, offset, filter, sort, revision) {
                            SqlRows(shown, Modifier.weight(1f), sort = sort, enabled = !busy,
                                deletable = handle?.writable == true && selected?.kind == "table",
                                onSort = { column ->
                                    if (table != null) {
                                        sort = when {
                                            sort?.column != column -> SqlSort(column)
                                            sort?.descending == false -> SqlSort(column, true)
                                            else -> null
                                        }
                                        offset = 0
                                    }
                                }, onCell = { row, index ->
                                    cell = SqlCell(shown.columns[index], row.identity, row.values[index],
                                        handle?.writable == true && selected?.kind == "table" && row.identity != null &&
                                            columns.any { it.name == shown.columns[index] && it.hidden == 0 })
                                    panel = SqlPanel.CELL
                                }, onDelete = { deleting = it })
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (table != null) TextButton(onClick = { offset = (offset - 100).coerceAtLeast(0) },
                                enabled = !busy && offset > 0) { Text("Previous") }
                            Text(when {
                                shown.rows.isEmpty() -> "0 rows"
                                table == null && shown.more -> "First ${shown.rows.size} rows"
                                table == null -> "${shown.rows.size} rows"
                                else -> "${offset + 1}–${offset + shown.rows.size}"
                            }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            if (table != null) TextButton(onClick = { offset += 100 },
                                enabled = !busy && shown.more) { Text("Next") }
                        }
                    } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (!busy && failure == null) Text("No tables")
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(query, { query = it }, label = { Text("SQL") },
                            placeholder = { Text("select * from …") }, singleLine = true,
                            modifier = Modifier.weight(1f).testTag("sqliteSql"))
                        TextButton(enabled = query.isNotBlank() && handle != null && !busy, onClick = {
                            val open = handle ?: return@TextButton
                            val sql = query
                            action("Running") {
                                val result = viewModel.files.executeSqlite(browser, open, sql)
                                objects = browser.objects(open)
                                val name = table
                                if (result.isFailure && open.writable && name != null) {
                                    page = browser.page(open, name, offset, filter = filter, sort = sort)
                                    count = null
                                }
                                val resultPage = result.getOrThrow()
                                table = null; offset = 0; count = null; columns = emptyList()
                                page = resultPage
                            }
                        }) { Text("Run") }
                    }
                } else when (panel) {
                    SqlPanel.FILTER -> SqlFilterEditor(columns, filter, sort, Modifier.weight(1f)) { newFilter, newSort ->
                        filter = newFilter; sort = newSort; offset = 0; panel = SqlPanel.ROWS
                    }
                    SqlPanel.STRUCTURE -> SqlStructure(columns, selected?.sql, Modifier.weight(1f))
                    SqlPanel.INDEXES -> SqlObjects(objects.filter { it.kind == "index" }, Modifier.weight(1f))
                    SqlPanel.VIEWS -> SqlObjects(objects.filter { it.kind == "view" }, Modifier.weight(1f), onOpen = ::choose)
                    SqlPanel.INSERT -> SqlInsertEditor(columns.filter { it.hidden == 0 }, !busy, Modifier.weight(1f)) { values ->
                        val open = handle ?: return@SqlInsertEditor
                        val name = table ?: return@SqlInsertEditor
                        action("Saving") {
                            viewModel.files.insertSqliteRow(browser, open, name, values).getOrThrow()
                            page = null; panel = SqlPanel.ROWS; count = null; revision++
                        }
                    }
                    SqlPanel.CELL -> cell?.let { edited ->
                        SqlCellEditor(edited, !busy, Modifier.weight(1f)) { input ->
                            val open = handle ?: return@SqlCellEditor
                            val name = table ?: return@SqlCellEditor
                            val identity = edited.identity ?: return@SqlCellEditor
                            action("Saving") {
                                viewModel.files.updateSqliteCell(browser, open, name, edited.column, identity,
                                    input.value, input.type).getOrThrow()
                                page = null; panel = SqlPanel.ROWS; cell = null; revision++
                            }
                        }
                    }
                    SqlPanel.ROWS -> Unit
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
        deleting?.let { identity ->
            AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete row?") },
                confirmButton = { TextButton(enabled = !busy, onClick = {
                    val open = handle ?: return@TextButton
                    val name = table ?: return@TextButton
                    deleting = null
                    action("Deleting") {
                        viewModel.files.deleteSqliteRow(browser, open, name, identity).getOrThrow()
                        page = null; revision++
                    }
                }) { Text("Delete") } }, dismissButton = {
                    TextButton(onClick = { deleting = null }) { Text("Cancel") }
                })
        }
    }
}

private enum class SqlPanel(val title: String) {
    ROWS(""), FILTER("Filter"), STRUCTURE("Structure"), INDEXES("Indexes"), VIEWS("Views"), INSERT("Add row"), CELL("Cell")
}

internal data class SqlCell(val column: String, val identity: String?, val value: SqlValue, val editable: Boolean)

@Composable
private fun SqlRows(
    page: SqlPage,
    modifier: Modifier,
    sort: SqlSort?,
    enabled: Boolean,
    deletable: Boolean,
    onSort: (String) -> Unit,
    onCell: (SqlRow, Int) -> Unit,
    onDelete: (String) -> Unit,
) {
    val state = rememberLazyListState()
    Column(modifier.fastScroll(state)) {
        Row(Modifier.fastHorizontalScroll(rememberScrollState())) {
            Column {
                Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    if (deletable) Spacer(Modifier.width(48.dp))
                    page.columns.forEach { name ->
                        Text(name + if (sort?.column == name) if (sort.descending) " ↓" else " ↑" else "",
                            Modifier.width(150.dp).clickable(enabled = enabled) { onSort(name) }.padding(8.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                LazyColumn(Modifier.weight(1f).testTag("sqliteRows"), state = state) {
                    items(page.rows) { row ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (deletable) {
                                IconButton(onClick = { row.identity?.let(onDelete) }, enabled = enabled && row.identity != null) {
                                    Icon(Icons.Outlined.Delete, "Delete row ${row.identity}")
                                }
                            }
                            row.values.forEachIndexed { index, value ->
                                Text(value.text.take(512), Modifier.width(150.dp).clickable(enabled = enabled) { onCell(row, index) }.padding(8.dp),
                                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                    color = when {
                                        value.isNull -> MaterialTheme.colorScheme.onSurfaceVariant
                                        value.isBlob -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .25f))
                    }
                }
            }
        }
    }
}
