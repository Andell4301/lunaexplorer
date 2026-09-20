@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.data.*
import com.lunaexplorer.app.model.BrowserState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.JsonElement

// Inline pages, not nested dialogs: a text field in an AlertDialog inside the settings Dialog
// never goes idle.
@Composable
internal fun SettingsTransferPage(
    state: BrowserState,
    viewModel: BrowserViewModel,
    actions: LunaActions,
) {
    val preview by viewModel.transfer.preview.collectAsState()
    val reading = preview
    LaunchedEffect(Unit) { viewModel.transfer.loadOpenDefaults() }
    // Drop the pending import on exit; otherwise reopening the page resumes it against stale state.
    DisposableEffect(Unit) { onDispose { viewModel.transfer.dropImport() } }

    var exporting by rememberSaveable { mutableStateOf(false) }
    if (reading != null) {
        ImportTree(reading, viewModel, onClose = { viewModel.transfer.dropImport() })
        return
    }
    if (exporting) {
        ExportTree(state, viewModel, actions, onClose = { exporting = false })
        return
    }

    Text("Settings travel as one file. Choose what it carries on the way out and again on the way in.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    SettingsLink("Export", "Write settings to a file") { exporting = true }
    SettingsLink("Import", "Read settings from a file") { actions.openSettings() }
}

@Composable
private fun ExportTree(
    state: BrowserState,
    viewModel: BrowserViewModel,
    actions: LunaActions,
    onClose: () -> Unit,
) {
    val source = remember(state) { viewModel.transfer.snapshot() }
    val defaults by viewModel.transfer.openDefaults.collectAsState()
    // Sensitive units read as null while the vault is closed, so offer them whenever there are
    // accounts and a vault that can carry their passwords.
    val available = remember(source, defaults) {
        SettingsRegistry.units.filter {
            it.read(source) != null ||
                (it.sensitive && it.mayHold(source) && viewModel.transfer.canCarryPasswords())
        }
    }
    val secretIds = remember(available) { available.filter { it.sensitive }.mapTo(HashSet()) { it.id } }
    val proved by viewModel.transfer.unlocked.collectAsState()

    var chosen by rememberSaveable {
        mutableStateOf(available.filterNot { it.sensitive }.map { it.id }.toSet())
    }
    var page by rememberSaveable { mutableStateOf<String?>(null) }

    val pages = remember(available) { SettingsRegistry.pages.filter { p -> available.any { it.page == p } } }
    val open = page

    // The file picker outlives this composable, so watch the export counter instead of awaiting it.
    val exports by viewModel.transfer.exported.collectAsState()
    val started = rememberSaveable { exports }
    LaunchedEffect(exports) { if (exports != started) onClose() }

    TreeScaffold(
        title = open ?: "Export",
        onBack = { if (open != null) page = null else onClose() },
    ) {
        if (open == null) {
            TreeHeader(chosen.size, available.size,
                onAll = { chosen = available.map { it.id }.toSet() },
                onNone = { chosen = emptySet() })
            pages.forEach { name ->
                val here = available.filter { it.page == name }
                PageRow(name, here.count { it.id in chosen }, here.size,
                    onOpen = { page = name },
                    onToggle = { on ->
                        val ids = here.map { it.id }
                        chosen = if (on) chosen + ids else chosen - ids.toSet()
                    })
            }
            Spacer(Modifier.height(16.dp))
            val carryingSecrets = chosen.any { it in secretIds }
            Button(
                onClick = {
                    val write = { withPasswords: Boolean ->
                        viewModel.transfer.prepareExport(chosen, withPasswords)
                        actions.saveSettings(suggestedName())
                    }
                    // Authenticate before opening the vault or the picker, so a refusal leaves no
                    // partial file. If the vault won't open, export without the passwords.
                    if (!carryingSecrets) write(false)
                    else actions.unlockVault { allowed ->
                        write(allowed && viewModel.transfer.unlockForExport())
                    }
                },
                enabled = chosen.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (carryingSecrets) "Confirm and export ${chosen.size}" else "Export ${chosen.size}") }
            if (carryingSecrets) {
                Spacer(Modifier.height(6.dp))
                Text("Includes stored passwords, in a file anything can read.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (proved) {
                Spacer(Modifier.height(6.dp))
                Text("Unlocked", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        } else {
            val here = available.filter { it.page == open }
            TreeHeader(here.count { it.id in chosen }, here.size,
                onAll = { chosen = chosen + here.map { it.id } },
                onNone = { chosen = chosen - here.map { it.id }.toSet() })
            here.forEach { unit ->
                val value = unit.read(source)
                val items = value?.let { unit.items?.invoke(it, source) }.orEmpty()
                LeafRow(
                    label = unit.label,
                    detail = when {
                        items.isNotEmpty() -> "${items.size} ${if (items.size == 1) "entry" else "entries"}"
                        unit.sensitive -> "Read as the file is written"
                        else -> describe(value)
                    },
                    checked = unit.id in chosen,
                    warning = if (unit.sensitive) "Written in cleartext" else "",
                ) { on -> chosen = if (on) chosen + unit.id else chosen - unit.id }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun describe(value: JsonElement?): String {
    val text = value?.toString() ?: return ""
    return if (text.startsWith("[") || text.startsWith("{")) "" else text.trim('"').take(60)
}

private fun suggestedName(): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    return "luna-settings-$stamp.json"
}

@Composable
private fun ImportTree(preview: TransferPreview, viewModel: BrowserViewModel, onClose: () -> Unit) {
    var chosen by rememberSaveable { mutableStateOf(preview.suggested) }
    var dropped by rememberSaveable { mutableStateOf(mapOf<String, Set<String>>()) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyChanges by rememberSaveable { mutableStateOf(false) }
    var onlyWarnings by rememberSaveable { mutableStateOf(false) }

    val shown = remember(preview, onlyChanges, onlyWarnings) {
        preview.rows.filter {
            (!onlyChanges || it.verdict != TransferVerdict.IDENTICAL) &&
                (!onlyWarnings || it.missing.isNotEmpty() || !it.usable)
        }
    }
    val open = page

    TreeScaffold(title = open ?: "Import", onBack = { if (open != null) page = null else onClose() }) {
        if (open == null) {
            Text(preview.tally(), style = MaterialTheme.typography.bodyMedium)
            if (preview.document.app.isNotBlank()) {
                Text("From Luna ${preview.document.app}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (preview.unknown.isNotEmpty()) {
                Text("${preview.unknown.size} settings this Luna does not have are left alone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(onlyChanges, { onlyChanges = !onlyChanges }, { Text("Changes") })
                FilterChip(onlyWarnings, { onlyWarnings = !onlyWarnings }, { Text("Warnings") })
            }
            Spacer(Modifier.height(8.dp))
            // All and None touch only the rows the filters show; hidden rows keep their state.
            val takeable = shown.filter { it.usable }
            TreeHeader(takeable.count { it.id in chosen }, takeable.size,
                onAll = { chosen = chosen + takeable.map { it.id } },
                onNone = { chosen = chosen - shown.map { it.id }.toSet() })
            val pages = SettingsRegistry.pages.filter { p -> shown.any { it.page == p } }
            if (pages.isEmpty()) {
                Text("Nothing to show under these filters.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            pages.forEach { name ->
                val here = shown.filter { it.page == name }
                // Refused rows don't count toward the total, or the page checkbox could never reach "all".
                val usableIds = here.filter { it.usable }.map { it.id }
                PageRow(
                    name, usableIds.count { it in chosen }, usableIds.size,
                    warnings = here.count { it.missing.isNotEmpty() || !it.usable },
                    onOpen = { page = name },
                    onToggle = { on -> chosen = if (on) chosen + usableIds else chosen - usableIds.toSet() },
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.transfer.import(chosen, kept(preview, dropped)) { onClose() } },
                enabled = chosen.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Import ${chosen.size}") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            Spacer(Modifier.height(24.dp))
        } else {
            val here = shown.filter { it.page == open }
            val takeable = here.filter { it.usable }
            TreeHeader(takeable.count { it.id in chosen }, takeable.size,
                onAll = { chosen = chosen + takeable.map { it.id } },
                onNone = { chosen = chosen - here.map { it.id }.toSet() })
            here.forEach { row ->
                ImportRow(
                    row = row,
                    checked = row.id in chosen,
                    excluded = dropped[row.id].orEmpty(),
                    onCheck = { on -> chosen = if (on) chosen + row.id else chosen - row.id },
                    onItem = { key, keep ->
                        val without = dropped[row.id].orEmpty()
                        dropped = dropped + (row.id to if (keep) without - key else without + key)
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * [dropped] holds unticked item keys by setting id; the result holds the keys to keep.
 * A setting absent from the result is imported whole.
 */
private fun kept(preview: TransferPreview, dropped: Map<String, Set<String>>): Map<String, Set<String>> =
    dropped.filterValues { it.isNotEmpty() }.mapNotNull { (id, excluded) ->
        val row = preview.rows.firstOrNull { it.id == id } ?: return@mapNotNull null
        id to row.items.map { it.key }.filterNot { it in excluded }.toSet()
    }.toMap()

private fun TransferPreview.tally(): String {
    val new = rows.count { it.verdict == TransferVerdict.NEW }
    val replacing = rows.count { it.verdict == TransferVerdict.REPLACES }
    val same = rows.count { it.verdict == TransferVerdict.IDENTICAL }
    val unreadable = rows.count { !it.usable }
    val missing = rows.sumOf { it.missing.size }
    return listOfNotNull(
        "$new new".takeIf { new > 0 },
        "$replacing to replace".takeIf { replacing > 0 },
        "$same unchanged".takeIf { same > 0 },
        "$missing not here".takeIf { missing > 0 },
        "$unreadable unreadable".takeIf { unreadable > 0 },
    ).joinToString("  ·  ").ifEmpty { "Nothing in this file" }
}

@Composable
private fun ImportRow(
    row: TransferRow,
    checked: Boolean,
    excluded: Set<String>,
    onCheck: (Boolean) -> Unit,
    onItem: (String, Boolean) -> Unit,
) {
    var open by rememberSaveable(row.id) { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .combinedClickable(enabled = row.usable, onClick = { onCheck(!checked) }, role = Role.Checkbox),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheck, enabled = row.usable,
                modifier = Modifier.semantics { contentDescription = row.unit.label })
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.unit.label, style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Text(row.verdict.label, style = MaterialTheme.typography.labelSmall,
                        color = when (row.verdict) {
                            TransferVerdict.NEW -> MaterialTheme.colorScheme.primary
                            TransferVerdict.REPLACES -> MaterialTheme.colorScheme.secondary
                            TransferVerdict.IDENTICAL -> MaterialTheme.colorScheme.onSurfaceVariant
                            TransferVerdict.UNREADABLE -> MaterialTheme.colorScheme.error
                        })
                }
                val line = when {
                    !row.usable -> row.refusal
                    row.items.isNotEmpty() -> "${row.items.size - excluded.size} of ${row.items.size}"
                    row.verdict == TransferVerdict.REPLACES && row.current.isNotBlank() ->
                        "${row.current} → ${row.offered}"
                    else -> row.offered
                }
                if (line.isNotBlank()) {
                    Text(line, style = MaterialTheme.typography.bodySmall,
                        color = if (row.usable) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (row.missing.isNotEmpty()) {
                    Text("${row.missing.size} not on this device",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (row.items.isNotEmpty()) {
                ToolIcon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (open) "Hide entries" else "Show entries") { open = !open }
            }
        }
        if (open) {
            row.items.forEach { item ->
                Row(Modifier.fillMaxWidth().padding(start = 32.dp).heightIn(min = 48.dp)
                    .combinedClickable(onClick = { onItem(item.key, item.key in excluded) }, role = Role.Checkbox),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = item.key !in excluded,
                        onCheckedChange = { keep -> onItem(item.key, keep) })
                    Column(Modifier.weight(1f)) {
                        Text(item.label, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (item.detail.isNotBlank()) {
                            Text(item.detail, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (item.missing) {
                            Text("Not on this device", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun TreeScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // The settings Dialog owns Back and would close the whole page; handle it here so Back goes up
    // one level of the tree.
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        content()
    }
}

@Composable
private fun TreeHeader(chosen: Int, total: Int, onAll: () -> Unit, onNone: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$chosen of $total", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = onAll, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("All") }
        TextButton(onClick = onNone, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("None") }
    }
    HorizontalDivider()
}

@Composable
private fun PageRow(
    name: String,
    chosen: Int,
    total: Int,
    warnings: Int = 0,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        TriStateCheckbox(
            state = when (chosen) {
                0 -> ToggleableState.Off
                total -> ToggleableState.On
                else -> ToggleableState.Indeterminate
            },
            onClick = { onToggle(chosen < total) },
            modifier = Modifier.semantics { contentDescription = name },
        )
        Row(
            Modifier.weight(1f).heightIn(min = 56.dp)
                .combinedClickable(onClick = onOpen, role = Role.Button)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Row {
                    Text("$chosen of $total", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (warnings > 0) {
                        Text("  ·  $warnings to look at", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LeafRow(
    label: String,
    detail: String,
    checked: Boolean,
    warning: String = "",
    onCheck: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
        .combinedClickable(onClick = { onCheck(!checked) }, role = Role.Checkbox),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheck,
            modifier = Modifier.semantics { contentDescription = label })
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (warning.isNotBlank()) {
                Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
