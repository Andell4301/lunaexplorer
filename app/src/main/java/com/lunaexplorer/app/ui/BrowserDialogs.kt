@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lunaexplorer.core.*
import kotlin.math.round
import com.lunaexplorer.app.model.*

@Composable
internal fun NameDialog(
    title: String,
    label: String,
    action: String,
    presets: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val error = remember(name) { runCatching { validateName(name) }.exceptionOrNull()?.message }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(label) }, singleLine = true,
                    isError = name.isNotEmpty() && error != null, modifier = Modifier.fillMaxWidth(),
                    supportingText = { if (name.isNotEmpty() && error != null) Text(error) })
                if (presets.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        presets.forEach { preset ->
                            SuggestionChip(onClick = { name = preset }, label = { Text(preset) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = error == null) { Text(action) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun RenameDialog(
    entry: Entry,
    includeExtension: Boolean,
    onRemember: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) = RenameDialog(entry.name, entry.directory, entry.ref.key, includeExtension, onRemember, onDismiss, onConfirm)

/**
 * [stateKey] identifies the item being renamed; the field and checkbox reset when it changes.
 * [onConfirm] receives the full new name, extension included.
 */
@Composable
internal fun RenameDialog(
    original: String,
    directory: Boolean,
    stateKey: String,
    includeExtension: Boolean,
    onRemember: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val (initialBase, initialExtension) = remember(stateKey) { splitExtension(original, directory) }
    val splittable = initialExtension.isNotEmpty()
    var include by rememberSaveable(stateKey) { mutableStateOf(!splittable || includeExtension) }
    var text by rememberSaveable(stateKey) { mutableStateOf(if (include) original else initialBase) }
    var suffix by rememberSaveable(stateKey) { mutableStateOf(if (include) "" else initialExtension) }
    val name = if (suffix.isEmpty()) text else "$text.$suffix"
    val error = remember(name) { runCatching { validateName(name) }.exceptionOrNull()?.message }

    fun setInclude(on: Boolean) {
        if (on == include) return
        if (on) {
            text = if (suffix.isEmpty()) text else "$text.$suffix"
            suffix = ""
        } else {
            // Split the current text, not the original: the extension may have been edited.
            val (base, extension) = splitExtension(text, directory = false)
            text = base
            suffix = extension
        }
        include = on
    }

    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Name") }, singleLine = true,
                    suffix = if (suffix.isEmpty()) null else ({ Text(".$suffix") }),
                    isError = text.isNotEmpty() && error != null, modifier = Modifier.fillMaxWidth(),
                    supportingText = { if (text.isNotEmpty() && error != null) Text(error) })
                if (splittable) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).heightIn(min = 40.dp)
                            .combinedClickable(onClick = { setInclude(!include) }, role = Role.Checkbox),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = include, onCheckedChange = { setInclude(it) })
                            Text("Include extension", style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick = { onRemember(include) }, enabled = include != includeExtension,
                            contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Remember") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) },
                enabled = error == null && text.isNotBlank() && name != original) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun deleteReasons(check: DeleteCheck, largeDeleteGb: Int): List<String> = buildList {
    check.androidPaths.forEach { add("This is $it.") }
    if (check.large) add("${formatBytes(check.bytes)} — above the $largeDeleteGb GB warning you set.")
}

@Composable
internal fun DeleteDialog(state: BrowserState, viewModel: BrowserViewModel, onDismiss: () -> Unit) {
    val check = state.deleteCheck
    // Count the entries the check captured: the live selection can change while a slow measurement runs.
    val count = (check?.entries ?: state.selectedEntries).size
    val plural = if (count == 1) "item" else "items"
    // Archive members can't be binned: the recycle bin is a move within the same storage.
    val inArchive = viewModel.insideArchive()
    // Nor can anything on storage that keeps old versions: there the choice is to hide or to remove them all.
    val versioned = check?.versioned == true
    val bin = state.recycleBin && !inArchive && !versioned
    val guarded = check?.guarded == true
    val measuring = check?.measuring == true
    // Nothing can be confirmed or skipped until the Android-path lookup has answered.
    val known = check == null || check.guardKnown
    var stopAsking by remember { mutableStateOf(false) }
    var always by remember { mutableStateOf(false) }
    var skipped by rememberSaveable { mutableStateOf(false) }
    // The toBin choice made here while the guard dialog is showing; null otherwise.
    var confirming by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var confirmingHide by rememberSaveable { mutableStateOf(false) }
    val ready = known && (!measuring || guarded || skipped)
    val cancel = { viewModel.cancelDeleteCheck(); onDismiss() }
    fun run(toBin: Boolean, hide: Boolean = false) {
        // Read from the ViewModel, not the composed state: a guard can arrive between the last frame and the tap.
        if (viewModel.guardsDeletion()) { confirming = toBin; confirmingHide = hide; return }
        val choice = if (hide) VersionedDelete.HIDE else VersionedDelete.PURGE
        // A versioned delete never goes to the bin, which must not become the session's answer for other storage.
        if (stopAsking) viewModel.rememberForSession(confirmDelete = false,
            recycleBin = toBin.takeUnless { versioned }, versionedDelete = choice.takeIf { versioned })
        if (always && versioned) {
            viewModel.setPreferences(state.preferences.copy(versionedDelete = choice))
            viewModel.rememberForSession(versionedDelete = choice)
        }
        viewModel.deleteChecked(toBin, keepVersions = hide)
        onDismiss()
    }

    val pending = confirming
    if (pending != null && check != null) {
        GuardedDeleteDialog(
            reasons = deleteReasons(check, state.preferences.largeDeleteGb),
            action = when {
                pending -> "Move to bin"
                versioned -> if (confirmingHide) "Hide" else "Delete every version"
                bin -> "Delete permanently"
                else -> "Delete"
            },
            onCancel = cancel,
        ) {
            viewModel.deleteChecked(pending, keepVersions = confirmingHide)
            onDismiss()
        }
        return
    }
    AlertDialog(
        onDismissRequest = cancel,
        icon = { Icon(if (bin || versioned) Icons.Outlined.Delete else Icons.Outlined.DeleteForever, null) },
        title = { Text(if (bin) "Move $count $plural to the recycle bin?" else "Delete $count $plural?") },
        text = {
            Column {
                Text(when {
                    versioned -> "Hidden items keep every stored version, which still take up space there. " +
                        "Deleting every version cannot be undone."
                    bin -> "You can put them back from the recycle bin until you empty it."
                    inArchive -> "The archive is written out again without them. There is no recycle " +
                        "bin inside an archive, so this cannot be undone."
                    else -> "Folders and everything inside them go permanently. This cannot be undone."
                })
                if (check != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (check.totals == null && measuring) "Checking size…"
                            else "${formatBytes(check.bytes)} in ${"%,d".format(check.files)} ${if (check.files == 1L) "file" else "files"}",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        )
                        if (measuring) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        }
                    }
                    if (measuring && known && !guarded && !skipped) {
                        TextButton(onClick = { skipped = true }, contentPadding = PaddingValues(0.dp)) { Text("Skip check") }
                    }
                }
                if (!guarded) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .combinedClickable(onClick = { stopAsking = !stopAsking }, role = Role.Checkbox),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = stopAsking, onCheckedChange = { stopAsking = it })
                        Spacer(Modifier.width(4.dp))
                        Text("Don't ask again this session", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (versioned) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .combinedClickable(onClick = { always = !always }, role = Role.Checkbox),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = always, onCheckedChange = { always = it })
                            Spacer(Modifier.width(4.dp))
                            Text("Always do this", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (versioned) Row {
                TextButton(onClick = { run(false) }, enabled = ready,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Delete every version")
                }
                TextButton(onClick = { run(false, hide = true) }, enabled = ready) { Text("Hide") }
            } else Row {
                if (bin) {
                    TextButton(onClick = { run(false) }, enabled = ready,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text("Delete permanently")
                    }
                }
                TextButton(onClick = { run(bin) }, enabled = ready,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (bin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)) {
                    Text(if (bin) "Move to bin" else "Delete")
                }
            }
        },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } },
    )
}

@Composable
private fun GuardedDeleteDialog(
    reasons: List<String>,
    action: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Are you sure?") },
        text = {
            Column {
                reasons.forEachIndexed { index, reason ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    Text(reason, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(action) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
internal fun GoToPathDialog(initial: String, onDismiss: () -> Unit, onGo: (String) -> Unit) {
    var path by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Go to path") },
        text = {
            OutlinedTextField(value = path, onValueChange = { path = it }, singleLine = true,
                label = { Text("Absolute path") }, placeholder = { Text("/storage/emulated/0") },
                modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(onClick = { onGo(path) }, enabled = path.isNotBlank()) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ViewOptionsSheet(
    preferences: Preferences,
    scopable: Boolean,
    startScoped: Boolean,
    onChange: (Preferences, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var thisFolderOnly by remember { mutableStateOf(scopable && startScoped) }
    val change: (Preferences) -> Unit = { onChange(it, thisFolderOnly) }
    BottomSheet(onDismiss) {
        Column(Modifier.fastVerticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            if (scopable) {
                Text("Applies to", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = thisFolderOnly, shape = SegmentedButtonDefaults.itemShape(0, 2),
                        onClick = {
                            thisFolderOnly = true
                            onChange(preferences, true)
                        }) { Text("This folder") }
                    SegmentedButton(selected = !thisFolderOnly, shape = SegmentedButtonDefaults.itemShape(1, 2),
                        onClick = {
                            thisFolderOnly = false
                            onChange(preferences, false)
                        }) { Text("All folders") }
                }
                Spacer(Modifier.height(18.dp))
            }
            Text("Layout", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ViewMode.entries.forEach { mode ->
                    FilterChip(selected = preferences.view == mode,
                        onClick = { change(preferences.copy(view = mode)) },
                        label = { Text(mode.label) })
                }
            }
            if (preferences.view == ViewMode.GRID_CUSTOM) {
                Spacer(Modifier.height(10.dp))
                val cell = preferences.gridCell.takeIf { it in TILE_DP } ?: ViewMode.GRID_MEDIUM.cell
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    var typed by remember { mutableStateOf(cell.toString()) }
                    LaunchedEffect(cell) { typed = cell.toString() }
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { text ->
                            val digits = text.filter { it.isDigit() }.take(3)
                            typed = digits
                            digits.toIntOrNull()?.takeIf { it in TILE_DP }
                                ?.let { change(preferences.copy(gridCell = it)) }
                        },
                        label = { Text("Tile") },
                        suffix = { Text("dp") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(118.dp).semantics { contentDescription = "Tile size" },
                    )
                    Slider(
                        value = cell.toFloat(),
                        onValueChange = { change(preferences.copy(gridCell = round(it).toInt())) },
                        valueRange = TILE_DP.first.toFloat()..TILE_DP.last.toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Sort by", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortOrder.entries.forEach { order ->
                    FilterChip(selected = preferences.sort == order, onClick = { change(preferences.copy(sort = order)) },
                        label = { Text(order.label) })
                }
            }
            Spacer(Modifier.height(8.dp))
            SortDirectionRow(preferences.descending) { change(preferences.copy(descending = it)) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SwitchRow("Thumbnails", preferences.thumbnails) { change(preferences.copy(thumbnails = it)) }
            SwitchRow("Sections", preferences.sections) { change(preferences.copy(sections = it)) }
            SwitchRow("Hidden files", preferences.showHidden) { change(preferences.copy(showHidden = it)) }
        }
    }
}

internal val TILE_DP = 64..240

private enum class SizeUnit(val label: String, val bytes: Long) {
    KIB("KiB", 1024L),
    KB("KB", 1000L),
    MIB("MiB", 1024L * 1024),
    MB("MB", 1_000_000L),
    GIB("GiB", 1024L * 1024 * 1024),
    GB("GB", 1_000_000_000L),
}

@Composable
internal fun SearchDialog(
    scope: String,
    textSearch: Boolean,
    onDismiss: () -> Unit,
    onSearch: (String, String, Long?, Long?, Long?, Boolean, Boolean, String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("ALL") }
    var min by rememberSaveable { mutableStateOf("") }
    var max by rememberSaveable { mutableStateOf("") }
    var after by rememberSaveable { mutableStateOf("") }
    var sizeUnit by rememberSaveable { mutableStateOf(SizeUnit.KIB) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var regex by rememberSaveable { mutableStateOf(false) }
    var caseSensitive by rememberSaveable { mutableStateOf(false) }
    val patternError = remember(query, regex) {
        if (!regex || query.isEmpty()) null
        else runCatching { Regex(query) }.exceptionOrNull()?.message
    }
    val textError = remember(text, regex) {
        if (!regex || text.isEmpty()) null
        else runCatching { Regex(text) }.exceptionOrNull()?.message
    }
    val types = linkedMapOf("ALL" to "Anything", "FILE" to "Files", "FOLDER" to "Folders")
    // Bounded by the unit so the multiplication cannot overflow.
    fun bytes(value: String): Long? = value.trim().toLongOrNull()
        ?.takeIf { it >= 0 && it <= Long.MAX_VALUE / sizeUnit.bytes }?.times(sizeUnit.bytes)
    val minBytes = bytes(min)
    val maxBytes = bytes(max)
    val afterDate = remember(after) { parseTimestamp(after) }
    val valid = (min.isBlank() || minBytes != null) && (max.isBlank() || maxBytes != null) &&
        (minBytes == null || maxBytes == null || minBytes <= maxBytes) && (after.isBlank() || afterDate != null) &&
        patternError == null && textError == null
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Search") },
        text = {
            Column(Modifier.fastVerticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Everything inside $scope", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(query, { query = it },
                    label = { Text(if (regex) "Pattern" else "Name contains") }, singleLine = true,
                    isError = patternError != null, modifier = Modifier.fillMaxWidth(),
                    supportingText = { patternError?.let { Text(it) } })
                if (textSearch) {
                    // Exactly as typed: the space at the end of a phrase is part of what is looked for.
                    OutlinedTextField(text, { text = it },
                        label = { Text(if (regex) "Text pattern" else "Contains text") }, singleLine = true,
                        isError = textError != null, modifier = Modifier.fillMaxWidth(),
                        supportingText = { textError?.let { Text(it) } })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    types.forEach { (value, label) ->
                        FilterChip(selected = type == value, onClick = { type = value }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = regex, onClick = { regex = !regex }, label = { Text("Regex") })
                    FilterChip(selected = caseSensitive, onClick = { caseSensitive = !caseSensitive },
                        label = { Text("Match case") })
                }
                TextButton(onClick = { advanced = !advanced }, contentPadding = PaddingValues(0.dp)) {
                    Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Size and date")
                }
                if (advanced) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(min, { min = it }, label = { Text("Min") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f), isError = min.isNotBlank() && minBytes == null)
                        OutlinedTextField(max, { max = it }, label = { Text("Max") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f), isError = max.isNotBlank() && maxBytes == null)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SizeUnit.entries.forEach { unit ->
                            FilterChip(selected = sizeUnit == unit, onClick = { sizeUnit = unit },
                                label = { Text(unit.label) })
                        }
                    }
                    TimestampField(after, { after = it }, label = "Modified on or after",
                        placeholder = "YYYY-MM-DD", optional = true, clearable = true, modifier = Modifier.fillMaxWidth())
                }
                if (!valid) {
                    Text("Check the size range and date format.", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSearch(query.trim(), type, minBytes, maxBytes, afterDate, regex, caseSensitive, text) },
                enabled = valid,
            ) { Text("Search") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun PinPathDialog(initial: String, onDismiss: () -> Unit, onPin: (String, String) -> Unit) {
    var path by rememberSaveable(initial) { mutableStateOf(initial) }
    var label by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pin a path") },
        text = {
            Column {
                OutlinedTextField(path, { path = it }, singleLine = true,
                    label = { Text("Absolute path") },
                    placeholder = { Text("/storage/emulated/0/Music") },
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(label, { label = it }, singleLine = true,
                    label = { Text("Name (optional)") },
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onPin(path, label.trim()) }, enabled = path.isNotBlank()) { Text("Pin") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
