package com.lunaexplorer.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.ProcedureSchedule
import com.lunaexplorer.app.model.ProcedureScheduleKind
import com.lunaexplorer.app.model.StoredProcedure
import com.lunaexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle

@Composable
internal fun ProcedureSettings(
    state: BrowserState,
    procedures: Procedures,
    actions: LunaActions,
    onBackChanged: ((() -> Unit)?) -> Unit,
) {
    val saved by procedures.saved.collectAsState()
    val runs by procedures.runs.collectAsState()
    var history by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<StoredProcedure?>(null) }
    var deleting by remember { mutableStateOf<StoredProcedure?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { onBackChanged(null) } }
    val edit = editing
    if (edit != null) {
        key(edit.id) {
            ProcedureEditor(edit, state, procedures, actions, onBackChanged) { editing = null }
        }
        return
    }
    SideEffect { onBackChanged(null) }
    Button(onClick = { editing = StoredProcedure(name = "", steps = emptyList()) }) { Text("Add procedure") }
    failure?.let { ProcedureFailure(it) }
    saved.forEach { procedure ->
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(procedure.name, style = MaterialTheme.typography.titleMedium)
        Text("${procedure.steps.size} steps", style = MaterialTheme.typography.bodySmall)
        val latest = runs.firstOrNull { it.procedureId == procedure.id }
        procedure.schedule?.takeIf { it.enabled }?.let { schedule ->
            Text(scheduleSummary(schedule), style = MaterialTheme.typography.bodySmall)
            if (latest?.status in setOf("FAILED", "PARTIAL", "INTERRUPTED", "CANCELLED", "CONFLICT")) {
                Text("Schedule paused", color = MaterialTheme.colorScheme.error)
            }
        }
        latest?.let { Text("Last run: ${it.status.lowercase()}", style = MaterialTheme.typography.bodySmall) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { procedures.run(procedure) }) { Text("Run ${procedure.name}") }
            TextButton(onClick = { editing = procedure }) { Text("Edit ${procedure.name}") }
            TextButton(onClick = { deleting = procedure }) { Text("Remove ${procedure.name}") }
            TextButton(onClick = { history = if (history == procedure.id) null else procedure.id }) { Text("History ${procedure.name}") }
        }
        if (history == procedure.id) {
            val matching = runs.filter { it.procedureId == procedure.id }
            if (matching.isEmpty()) Text("No runs")
            matching.forEach { run ->
                Text("${formatTimestamp(run.created)} · ${run.status.lowercase()}", style = MaterialTheme.typography.bodyMedium)
                if (run.detail.isNotEmpty()) Text(run.detail, style = MaterialTheme.typography.bodySmall)
                Row {
                    if (run.status in setOf("QUEUED", "RUNNING")) {
                        TextButton(onClick = { procedures.cancel(run.id) }) { Text("Cancel run") }
                    }
                    TextButton(onClick = { actions.shareReport(run.id) }) { Text("Report") }
                }
            }
        }
    }
    deleting?.let { procedure ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ${procedure.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        try { procedures.remove(procedure.id) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { ensureActive(); failure = error.message ?: "Could not remove the procedure" }
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun ProcedureEditor(
    original: StoredProcedure,
    state: BrowserState,
    procedures: Procedures,
    actions: LunaActions,
    onBackChanged: ((() -> Unit)?) -> Unit,
    onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(original.name) }
    var steps by remember { mutableStateOf(original.steps) }
    var schedule by remember { mutableStateOf(original.schedule ?: ProcedureSchedule()) }
    var scheduleValid by remember { mutableStateOf(true) }
    var notifyOnSuccess by remember { mutableStateOf(original.notifyOnSuccess) }
    var notifyOnFailure by remember { mutableStateOf(original.notifyOnFailure) }
    var stepIndex by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val index = stepIndex
    if (index != null) {
        key(index) {
            ProcedureStepEditor(steps.getOrNull(index), state, procedures, onBackChanged,
                onClose = { stepIndex = null },
                onSave = { step ->
                    steps = if (index == steps.size) steps + step else steps.toMutableList().also { it[index] = step }
                    stepIndex = null
                })
        }
        return
    }
    SideEffect { onBackChanged(onClose) }
    OutlinedTextField(name, { name = it }, label = { Text("Procedure name") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("procedure-name"))
    Spacer(Modifier.height(12.dp))
    steps.forEachIndexed { at, step ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${at + 1}. ${operationLabel(step.type)}", Modifier.weight(1f))
            ToolIcon(Icons.Outlined.ArrowUpward, "Move step ${at + 1} up", enabled = at > 0, onClick = {
                steps = steps.toMutableList().also { it.add(at - 1, it.removeAt(at)) }
            })
            ToolIcon(Icons.Outlined.ArrowDownward, "Move step ${at + 1} down", enabled = at < steps.lastIndex, onClick = {
                steps = steps.toMutableList().also { it.add(at + 1, it.removeAt(at)) }
            })
            ToolIcon(Icons.Outlined.Edit, "Edit step ${at + 1}", onClick = { stepIndex = at })
            ToolIcon(Icons.Outlined.Close, "Remove step ${at + 1}", onClick = {
                steps = steps.filterIndexed { position, _ -> position != at }
            })
        }
    }
    TextButton(onClick = { stepIndex = steps.size }) { Text("Add step") }
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    ProcedureScheduleEditor(schedule, onChange = { schedule = it }, onValid = { scheduleValid = it })
    SwitchRow("Notify on success", notifyOnSuccess) {
        notifyOnSuccess = it
        if (it) actions.requestNotifications()
    }
    SwitchRow("Notify on failure", notifyOnFailure) {
        notifyOnFailure = it
        if (it) actions.requestNotifications()
    }
    failure?.let { ProcedureFailure(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy && name.isNotEmpty() && steps.isNotEmpty() && (!schedule.enabled || scheduleValid), onClick = {
            busy = true; failure = null
            scope.launch {
                try {
                    procedures.save(original.copy(name = name, steps = steps, schedule = schedule,
                        notifyOnSuccess = notifyOnSuccess, notifyOnFailure = notifyOnFailure))
                    onClose()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    ensureActive(); failure = error.message ?: "Could not save the procedure"
                } finally { busy = false }
            }
        }) { Text("Save procedure") }
        TextButton(enabled = !busy, onClick = onClose) { Text("Cancel") }
    }
    Spacer(Modifier.height(24.dp))
}

private data class ProcedurePlaceInput(
    val text: String = "",
    val location: ProcedureLocation? = null,
    val children: String = "",
)

private data class ProcedureSourceInput(
    val place: ProcedurePlaceInput = ProcedurePlaceInput(),
    val matching: Boolean = false,
    val pattern: String = "*",
    val recursive: Boolean = false,
    val kind: ProcedureEntryKind = ProcedureEntryKind.FILES,
)

private fun Procedures.input(location: ProcedureLocation): ProcedurePlaceInput = ProcedurePlaceInput(
    text = shownPath(ProcedureLocation(location.ref)) ?: "${location.ref.provider}: Selected location",
    location = ProcedureLocation(location.ref),
    children = location.children.joinToString("/"),
)

private suspend fun Procedures.resolve(input: ProcedurePlaceInput): ProcedureLocation {
    val base = input.location ?: resolve(input.text)
    return base.copy(children = base.children + if (input.children.isEmpty()) emptyList() else input.children.split('/'))
}

@Composable
private fun ProcedureStepEditor(
    original: ProcedureStep?,
    state: BrowserState,
    procedures: Procedures,
    onBackChanged: ((() -> Unit)?) -> Unit,
    onClose: () -> Unit,
    onSave: (ProcedureStep) -> Unit,
) {
    var type by remember { mutableStateOf(original?.type ?: OperationType.COPY) }
    var sources by remember {
        mutableStateOf(original?.sources?.map { source ->
            ProcedureSourceInput(procedures.input(source.location), source.pattern != null,
                source.pattern ?: "*", source.recursive, source.kind)
        }?.takeIf { it.isNotEmpty() } ?: listOf(ProcedureSourceInput()))
    }
    var destination by remember { mutableStateOf(original?.destination?.let(procedures::input) ?: ProcedurePlaceInput()) }
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var conflict by remember { mutableStateOf(original?.conflictPolicy ?: ConflictPolicy.ASK) }
    var keepVersions by remember { mutableStateOf(original?.keepVersions ?: false) }
    var format by remember { mutableStateOf(original?.archive?.format ?: ArchiveFormat.ZIP) }
    var compression by remember { mutableStateOf(original?.archive?.level ?: 6) }
    var browsing by remember { mutableStateOf<Int?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val browse = browsing
    if (browse != null) {
        ProcedureLocationPicker(state, procedures, foldersOnly = browse == -1 || sources.getOrNull(browse)?.matching == true,
            onBackChanged = onBackChanged, onClose = { browsing = null }, onChoose = { ref, label ->
                val location = ProcedureLocation(ref)
                val chosen = ProcedurePlaceInput(procedures.shownPath(location) ?: label, location)
                if (browse == -1) destination = chosen
                else sources = sources.toMutableList().also { it[browse] = it[browse].copy(place = chosen) }
                browsing = null
            })
        return
    }
    SideEffect { onBackChanged(onClose) }
    ProcedureChoice("Action", type, OperationType.entries.filter { it != OperationType.PROCEDURE }, ::operationLabel) { chosen ->
        type = chosen
        if (chosen == OperationType.RENAME || chosen == OperationType.EXTRACT_ARCHIVE) {
            sources = sources.take(1).map { it.copy(matching = false) }
        }
    }
    val needsSources = type != OperationType.CREATE_FOLDER
    val singleSource = type == OperationType.RENAME || type == OperationType.EXTRACT_ARCHIVE
    val needsDestination = type !in listOf(OperationType.DELETE, OperationType.RENAME)
    val needsName = type in listOf(OperationType.RENAME, OperationType.CREATE_FOLDER, OperationType.CREATE_ARCHIVE)
    if (needsSources) {
        sources.forEachIndexed { index, source ->
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ProcedurePlaceField("Source ${index + 1}", source.place, procedures,
                onChange = { changed -> sources = sources.toMutableList().also { it[index] = source.copy(place = changed) } },
                onBrowse = { browsing = index })
            if (!singleSource) {
                SwitchRow("Match files in folder ${index + 1}", source.matching) { matching ->
                    sources = sources.toMutableList().also { it[index] = source.copy(matching = matching) }
                }
                if (source.matching) {
                    OutlinedTextField(source.pattern, { value -> sources = sources.toMutableList().also { it[index] = source.copy(pattern = value) } },
                        label = { Text("Name pattern (*, ?)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    ProcedureChoice("Items", source.kind, ProcedureEntryKind.entries, ::kindLabel) { value ->
                        sources = sources.toMutableList().also { it[index] = source.copy(kind = value) }
                    }
                    SwitchRow("Include subfolders ${index + 1}", source.recursive) { value ->
                        sources = sources.toMutableList().also { it[index] = source.copy(recursive = value) }
                    }
                }
                if (sources.size > 1) TextButton(onClick = { sources = sources.filterIndexed { at, _ -> at != index } }) {
                    Text("Remove source ${index + 1}")
                }
            }
        }
        if (!singleSource) TextButton(onClick = { sources = sources + ProcedureSourceInput() }) { Text("Add source") }
    }
    if (needsDestination) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ProcedurePlaceField("Destination", destination, procedures, onChange = { destination = it }, onBrowse = { browsing = -1 })
    }
    if (needsName || type == OperationType.EXTRACT_ARCHIVE) {
        OutlinedTextField(name, { name = it }, label = { Text(if (type == OperationType.EXTRACT_ARCHIVE) "Extract into folder (optional)" else "New name") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    if (type == OperationType.CREATE_ARCHIVE) {
        ProcedureChoice("Format", format, ArchiveFormat.entries.filter { it.creatable }, { it.label }) { format = it }
        if (format.compressible) ProcedureChoice("Compression", compression, CompressionPreset.entries.map { it.level },
            { level -> CompressionPreset.entries.firstOrNull { it.level == level }?.label ?: level.toString() }) { compression = it }
    }
    if (type != OperationType.DELETE) {
        ProcedureChoice("If a name exists", conflict,
            listOf(ConflictPolicy.ASK, ConflictPolicy.SKIP, ConflictPolicy.KEEP_BOTH, ConflictPolicy.REPLACE), ::conflictLabel) { conflict = it }
    }
    if (type in listOf(OperationType.DELETE, OperationType.MOVE, OperationType.RENAME)) {
        SwitchRow("Keep older versions", keepVersions) { keepVersions = it }
    }
    failure?.let { ProcedureFailure(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy && (!needsName || name.isNotEmpty()), onClick = {
            busy = true; failure = null
            scope.launch {
                try {
                    val step = ProcedureStep(
                        type = type,
                        sources = if (!needsSources) emptyList() else sources.map { source ->
                            ProcedureSource(procedures.resolve(source.place), if (source.matching && !singleSource) source.pattern else null,
                                source.recursive && !singleSource, source.kind)
                        },
                        destination = if (needsDestination) procedures.resolve(destination) else null,
                        name = if (needsName || type == OperationType.EXTRACT_ARCHIVE) name.takeIf { it.isNotEmpty() } else null,
                        conflictPolicy = conflict,
                        archive = if (type == OperationType.CREATE_ARCHIVE) ArchiveSpec(format = format, level = compression) else null,
                        keepVersions = keepVersions,
                    )
                    step.validate()
                    onSave(step)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    ensureActive(); failure = error.message ?: "Could not save the step"
                } finally { busy = false }
            }
        }) { Text("Save step") }
        TextButton(enabled = !busy, onClick = onClose) { Text("Cancel step") }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun ProcedurePlaceField(
    label: String,
    input: ProcedurePlaceInput,
    procedures: Procedures,
    onChange: (ProcedurePlaceInput) -> Unit,
    onBrowse: () -> Unit,
) {
    val saved = input.location
    val current by rememberUpdatedState(input)
    val change by rememberUpdatedState(onChange)
    LaunchedEffect(saved) {
        if (saved != null && procedures.shownPath(saved) == null) {
            val label = procedures.label(saved)
            if (current.location == saved) change(current.copy(text = label))
        }
    }
    OutlinedTextField(input.text, { onChange(input.copy(text = it, location = null)) }, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = onBrowse) { Text("Browse $label") }
    var childPath by remember { mutableStateOf(input.children.isNotEmpty()) }
    if (childPath) {
        OutlinedTextField(input.children, { onChange(input.copy(children = it)) }, label = { Text("$label child path") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
    } else TextButton(onClick = { childPath = true }) { Text("Add $label child path") }
}

@Composable
private fun ProcedureLocationPicker(
    state: BrowserState,
    procedures: Procedures,
    foldersOnly: Boolean,
    onBackChanged: ((() -> Unit)?) -> Unit,
    onClose: () -> Unit,
    onChoose: (NodeRef, String) -> Unit,
) {
    var trail by remember { mutableStateOf(emptyList<Pair<NodeRef, String>>()) }
    var entries by remember { mutableStateOf<List<Entry>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val here = trail.lastOrNull()
    val back = { if (trail.isEmpty()) onClose() else trail = trail.dropLast(1) }
    SideEffect { onBackChanged(back) }
    LaunchedEffect(here?.first) {
        entries = null; failure = null
        if (here != null) {
            try { entries = procedures.entries(here.first) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { ensureActive(); failure = error.message ?: "Could not read this folder" }
        }
    }
    Text(here?.second ?: "Locations", style = MaterialTheme.typography.titleMedium)
    FlowRow {
        TextButton(onClick = back) { Text(if (here == null) "Cancel browse" else "Up") }
        if (here != null) Button(onClick = { onChoose(here.first, here.second) }) { Text("Choose this folder") }
    }
    failure?.let { ProcedureFailure(it) }
    if (here != null && entries == null && failure == null) Text("Reading")
    LazyColumn(Modifier.fillMaxWidth().height(420.dp).testTag("procedure-locations")) {
        if (here == null) {
            items(state.roots.filterNot { it.hidden }, key = { "${it.ref.provider}:${it.ref.key}" }) { root ->
                ProcedureLocationRow(root.title, true) { trail = trail + (root.ref to root.title) }
            }
        } else {
            items(entries.orEmpty().filter { !foldersOnly || it.directory }, key = { "${it.ref.provider}:${it.ref.key}" }) { entry ->
                ProcedureLocationRow(entry.name, entry.directory) {
                    if (entry.directory) trail = trail + (entry.ref to entry.name) else onChoose(entry.ref, entry.name)
                }
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun ProcedureLocationRow(label: String, directory: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (directory) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile, null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun ProcedureScheduleEditor(
    schedule: ProcedureSchedule,
    onChange: (ProcedureSchedule) -> Unit,
    onValid: (Boolean) -> Unit,
) {
    var interval by remember { mutableStateOf(schedule.intervalMinutes.toString()) }
    val locale = LocalLocale.current.platformLocale
    var hour by remember { mutableStateOf(schedule.hour.toString().padStart(2, '0')) }
    var minute by remember { mutableStateOf(schedule.minute.toString().padStart(2, '0')) }
    var date by remember { mutableStateOf(formatTimestamp(schedule.atMillis.takeIf { it > 0 } ?: System.currentTimeMillis() + 3_600_000)) }
    var days by remember { mutableStateOf(schedule.days) }
    val valid = when (schedule.kind) {
        ProcedureScheduleKind.INTERVAL -> (interval.toLongOrNull() ?: 0) in 15..Long.MAX_VALUE / 60_000
        ProcedureScheduleKind.DAILY -> hour.toIntOrNull() in 0..23 && minute.toIntOrNull() in 0..59
        ProcedureScheduleKind.WEEKLY -> hour.toIntOrNull() in 0..23 && minute.toIntOrNull() in 0..59 && days.isNotEmpty()
        ProcedureScheduleKind.ONCE -> (parseTimestamp(date) ?: 0) > System.currentTimeMillis()
    }
    SideEffect { onValid(valid) }
    SwitchRow("Schedule", schedule.enabled) { enabled ->
        onChange(schedule.copy(enabled = enabled, atMillis = parseTimestamp(date)?.takeIf { it > 0 } ?: schedule.atMillis))
    }
    if (!schedule.enabled) return
    Text("Timing: approximate", style = MaterialTheme.typography.bodySmall)
    ProcedureChoice("Repeat", schedule.kind, ProcedureScheduleKind.entries, ::scheduleKindLabel) {
        onChange(schedule.copy(kind = it, atMillis = parseTimestamp(date)?.takeIf { time -> time > 0 } ?: schedule.atMillis))
    }
    when (schedule.kind) {
        ProcedureScheduleKind.INTERVAL -> OutlinedTextField(interval, {
            interval = it
            it.toLongOrNull()?.takeIf { value -> value in 15..Long.MAX_VALUE / 60_000 }
                ?.let { value -> onChange(schedule.copy(intervalMinutes = value)) }
        }, label = { Text("Minutes (15 minimum)") }, isError = !valid, singleLine = true, modifier = Modifier.fillMaxWidth())
        ProcedureScheduleKind.DAILY, ProcedureScheduleKind.WEEKLY -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(hour, {
                    hour = it
                    it.toIntOrNull()?.takeIf { value -> value in 0..23 }?.let { value -> onChange(schedule.copy(hour = value)) }
                }, label = { Text("Hour") }, isError = hour.toIntOrNull() !in 0..23, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(minute, {
                    minute = it
                    it.toIntOrNull()?.takeIf { value -> value in 0..59 }?.let { value -> onChange(schedule.copy(minute = value)) }
                }, label = { Text("Minute") }, isError = minute.toIntOrNull() !in 0..59, singleLine = true, modifier = Modifier.weight(1f))
            }
            if (schedule.kind == ProcedureScheduleKind.WEEKLY) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(selected = day.value in days, onClick = {
                            days = if (day.value in days) days - day.value else days + day.value
                            if (days.isNotEmpty()) onChange(schedule.copy(days = days))
                        }, label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) })
                    }
                }
            }
        }
        ProcedureScheduleKind.ONCE -> TimestampField(date, {
            date = it; parseTimestamp(it)?.takeIf { value -> value > 0 }?.let { value -> onChange(schedule.copy(atMillis = value)) }
        }, "Run at")
    }
}

@Composable
private fun <T> ProcedureChoice(label: String, selected: T, choices: List<T>, title: (T) -> String, onChoose: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("$label: ${title(selected)}") }
        FastDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(text = { Text(title(choice)) }, onClick = { expanded = false; onChoose(choice) })
            }
        }
    }
}

@Composable
private fun ProcedureFailure(message: String) {
    Text(message, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error)
}

private fun operationLabel(type: OperationType): String = when (type) {
    OperationType.COPY -> "Copy"
    OperationType.MOVE -> "Move"
    OperationType.DELETE -> "Delete permanently"
    OperationType.RENAME -> "Rename"
    OperationType.CREATE_FOLDER -> "Create folder"
    OperationType.CREATE_ARCHIVE -> "Create archive"
    OperationType.EXTRACT_ARCHIVE -> "Extract archive"
    OperationType.PROCEDURE -> "Stored procedure"
}

private fun kindLabel(kind: ProcedureEntryKind): String = when (kind) {
    ProcedureEntryKind.FILES -> "Files"
    ProcedureEntryKind.FOLDERS -> "Folders"
    ProcedureEntryKind.ALL -> "Files and folders"
}

private fun conflictLabel(policy: ConflictPolicy): String = when (policy) {
    ConflictPolicy.ASK -> "Stop"
    ConflictPolicy.SKIP -> "Skip"
    ConflictPolicy.REPLACE -> "Replace"
    ConflictPolicy.KEEP_BOTH -> "Keep both"
    ConflictPolicy.MERGE -> "Merge"
}

private fun scheduleKindLabel(kind: ProcedureScheduleKind): String = when (kind) {
    ProcedureScheduleKind.INTERVAL -> "Interval"
    ProcedureScheduleKind.DAILY -> "Daily"
    ProcedureScheduleKind.WEEKLY -> "Weekly"
    ProcedureScheduleKind.ONCE -> "Once"
}

private fun scheduleSummary(schedule: ProcedureSchedule): String = when (schedule.kind) {
    ProcedureScheduleKind.INTERVAL -> "Every ${schedule.intervalMinutes} minutes · approximate"
    ProcedureScheduleKind.DAILY -> "Daily · ${schedule.hour.toString().padStart(2, '0')}:${schedule.minute.toString().padStart(2, '0')} · approximate"
    ProcedureScheduleKind.WEEKLY -> "Weekly · ${schedule.hour.toString().padStart(2, '0')}:${schedule.minute.toString().padStart(2, '0')} · approximate"
    ProcedureScheduleKind.ONCE -> "${formatTimestamp(schedule.atMillis)} · approximate"
}
