package com.lunaexplorer.app.ui

import android.database.Cursor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.storage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
internal fun SqlChoice(label: String, value: String, choices: List<String>, modifier: Modifier = Modifier, enabled: Boolean = true, onChoose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { expanded = true }, enabled = enabled) { Text("$label: $value") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(text = { Text(choice) }, onClick = { expanded = false; onChoose(choice) })
            }
        }
    }
}

@Composable
internal fun SqlFilterEditor(
    columns: List<SqlColumn>,
    filter: SqlFilter?,
    sort: SqlSort?,
    modifier: Modifier,
    onApply: (SqlFilter?, SqlSort?) -> Unit,
) {
    var column by remember { mutableStateOf(filter?.column ?: columns.firstOrNull()?.name.orEmpty()) }
    var operator by remember { mutableStateOf(filter?.operator ?: SqlFilterOperator.CONTAINS) }
    var value by remember { mutableStateOf(filter?.value.orEmpty()) }
    var useFilter by remember { mutableStateOf(filter != null) }
    var sortColumn by remember { mutableStateOf(sort?.column) }
    var descending by remember { mutableStateOf(sort?.descending ?: false) }
    var choosingSort by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row {
            Checkbox(useFilter, { useFilter = it })
            TextButton(onClick = { useFilter = !useFilter }) { Text("Filter rows") }
        }
        SqlChoice("Column", column, columns.map { it.name }, Modifier.testTag("sqliteFilterColumn")) { column = it; useFilter = true }
        SqlChoice("Operator", operator.label, SqlFilterOperator.entries.map { it.label }, Modifier.testTag("sqliteFilterOperator")) { label ->
            operator = SqlFilterOperator.entries.first { it.label == label }; useFilter = true
        }
        if (operator != SqlFilterOperator.IS_NULL && operator != SqlFilterOperator.IS_NOT_NULL) {
            OutlinedTextField(value, { value = it; useFilter = true }, label = { Text("Value") },
                modifier = Modifier.fillMaxWidth().testTag("sqliteFilterValue"))
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Box(Modifier.testTag("sqliteSortColumn")) {
            TextButton(onClick = { choosingSort = true }) { Text("Sort: ${sortColumn ?: "None"}") }
            DropdownMenu(choosingSort, onDismissRequest = { choosingSort = false }) {
                DropdownMenuItem(text = { Text("None") }, onClick = { sortColumn = null; choosingSort = false })
                columns.forEach { item ->
                    DropdownMenuItem(text = { Text(item.name) }, onClick = { sortColumn = item.name; choosingSort = false })
                }
            }
        }
        Row {
            FilterChip(!descending, { descending = false }, label = { Text("Ascending") })
            Spacer(Modifier.width(8.dp))
            FilterChip(descending, { descending = true }, label = { Text("Descending") })
        }
        Row {
            TextButton(onClick = { onApply(null, null) }) { Text("Clear") }
            TextButton(enabled = !useFilter || columns.any { it.name == column }, onClick = {
                onApply(if (useFilter) SqlFilter(column, operator, value) else null, sortColumn?.let { SqlSort(it, descending) })
            }) { Text("Apply") }
        }
    }
}

@Composable
internal fun SqlStructure(columns: List<SqlColumn>, sql: String?, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
            items(columns, key = { it.name }) { column ->
                Text(column.name, style = MaterialTheme.typography.titleSmall)
                Text(listOfNotNull(column.type.takeIf { it.isNotEmpty() },
                    "PRIMARY KEY ${column.primaryKey}".takeIf { column.primaryKey > 0 },
                    "NOT NULL".takeIf { column.notNull }, column.defaultValue?.let { "DEFAULT $it" },
                    "GENERATED".takeIf { column.hidden > 0 }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
        }
        sql?.let { SqlText(it, Modifier.weight(1f)) }
    }
}

@Composable
internal fun SqlObjects(objects: List<SqlObject>, modifier: Modifier, onOpen: ((String) -> Unit)? = null) {
    var selected by remember { mutableStateOf<SqlObject?>(null) }
    Column(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp)) {
            if (objects.isEmpty()) item { Text("None", Modifier.padding(8.dp)) }
            items(objects, key = { it.name }) { item ->
                Column(Modifier.fillMaxWidth().clickable {
                    if (onOpen != null) onOpen(item.name) else selected = item
                }.padding(8.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall)
                    if (item.tableName != item.name) Text(item.tableName, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        selected?.let { item ->
            HorizontalDivider()
            SqlText(item.sql ?: "Implicit index", Modifier.weight(1f))
        }
    }
}

private enum class SqlInputType(val label: String, val storage: Int) {
    DEFAULT("Default", -1), TEXT("Text", Cursor.FIELD_TYPE_STRING), INTEGER("Integer", Cursor.FIELD_TYPE_INTEGER),
    REAL("Real", Cursor.FIELD_TYPE_FLOAT), NULL("NULL", Cursor.FIELD_TYPE_NULL), BLOB("BLOB (hex)", Cursor.FIELD_TYPE_BLOB)
}

private data class SqlDraft(val type: SqlInputType = SqlInputType.DEFAULT, val value: String = "") {
    fun input() = SqlInput(if (type == SqlInputType.NULL) null else value, type.storage)
}

@Composable
internal fun SqlInsertEditor(columns: List<SqlColumn>, enabled: Boolean, modifier: Modifier, onSave: (Map<String, SqlInput>) -> Unit) {
    val drafts = remember { mutableStateMapOf<String, SqlDraft>() }
    Column(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(columns, key = { it.name }) { column ->
                val draft = drafts[column.name] ?: SqlDraft()
                Text(column.name, style = MaterialTheme.typography.titleSmall)
                SqlChoice("Type", draft.type.label, SqlInputType.entries.map { it.label }, Modifier.testTag("sqliteInsertType:${column.name}"), enabled) { label ->
                    drafts[column.name] = draft.copy(type = SqlInputType.entries.first { it.label == label })
                }
                if (draft.type != SqlInputType.DEFAULT && draft.type != SqlInputType.NULL) {
                    OutlinedTextField(draft.value, { drafts[column.name] = draft.copy(value = it) }, label = { Text(column.name) },
                        enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("sqliteInsertValue:${column.name}"))
                }
                HorizontalDivider()
            }
        }
        TextButton(enabled = enabled, onClick = {
            onSave(drafts.filterValues { it.type != SqlInputType.DEFAULT }.mapValues { it.value.input() })
        }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Save") }
    }
}

@Composable
internal fun SqlCellEditor(cell: SqlCell, enabled: Boolean, modifier: Modifier, onSave: (SqlInput) -> Unit) {
    val editable = remember(cell) { cell.editable && !cell.value.isBlob && !tooLargeToEdit(cell.value.text) }
    var value by remember(cell) { mutableStateOf(if (cell.value.isNull) "" else cell.value.text) }
    var type by remember(cell) { mutableStateOf(SqlInputType.entries.first { it.storage == cell.value.type }) }
    Column(modifier.fillMaxWidth()) {
        Text(if (editable) "Edit ${cell.column}" else cell.column, Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
        if (editable) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                SqlChoice("Type", type.label, SqlInputType.entries.filter { it != SqlInputType.DEFAULT && it != SqlInputType.BLOB }.map { it.label }, enabled = enabled) { label ->
                    type = SqlInputType.entries.first { it.label == label }
                }
                if (type != SqlInputType.NULL) OutlinedTextField(value, { value = it }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("sqliteCellValue"))
            }
            TextButton(enabled = enabled, onClick = {
                onSave(SqlInput(if (type == SqlInputType.NULL) null else value, type.storage))
            }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Save") }
        } else SqlText(cell.value.text, Modifier.weight(1f))
    }
}

@Composable
private fun SqlText(text: String, modifier: Modifier) {
    var document by remember(text) { mutableStateOf<TextDocument?>(null) }
    LaunchedEffect(text) {
        document = withContext(Dispatchers.Default) { TextDocument.prepare(text) { ensureActive() } }
    }
    val ready = document
    if (ready == null) Box(modifier) else LazyTextRows(ready, wrap = true,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = modifier,
        rowText = { ready.row(it, null) })
}
