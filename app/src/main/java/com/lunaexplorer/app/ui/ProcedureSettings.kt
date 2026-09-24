package com.lunaexplorer.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    ProcedurePageStart("overview")
    Button(onClick = { editing = StoredProcedure(name = "", steps = emptyList()) }) {
        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Add procedure")
    }
    if (saved.isEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text("Save a sequence of file actions. Run it yourself or on a schedule.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    failure?.let { ProcedureFailure(it) }
    saved.forEach { procedure ->
        val latest = runs.firstOrNull { it.procedureId == procedure.id }
        OutlinedCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(procedure.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    ToolIcon(Icons.Outlined.DeleteOutline, "Remove ${procedure.name}", onClick = { deleting = procedure })
                }
                Text(procedure.steps.mapIndexed { index, step -> "${index + 1}. ${stepTitle(step)}" }.joinToString(" → "),
                    style = MaterialTheme.typography.bodyMedium)
                procedure.steps.firstOrNull()?.let { ProcedureStepSummary(it, procedures) }
                procedure.schedule?.takeIf { it.enabled }?.let { schedule ->
                    Text(scheduleSummary(schedule), style = MaterialTheme.typography.bodySmall)
                    if (latest?.status in setOf("FAILED", "PARTIAL", "INTERRUPTED", "CANCELLED", "CONFLICT")) {
                        Text("Schedule paused", color = MaterialTheme.colorScheme.error)
                    }
                }
                latest?.let { Text("Last run: ${it.status.lowercase()}", style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { procedures.run(procedure) },
                        enabled = latest?.status !in setOf("QUEUED", "RUNNING"),
                        modifier = Modifier.semantics { contentDescription = "Run ${procedure.name}" }) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Run")
                    }
                    TextButton(onClick = { editing = procedure },
                        modifier = Modifier.semantics { contentDescription = "Edit ${procedure.name}" }) { Text("Edit") }
                    TextButton(onClick = { history = if (history == procedure.id) null else procedure.id },
                        modifier = Modifier.semantics { contentDescription = "History ${procedure.name}" }) { Text("History") }
                }
                if (history == procedure.id) {
                    HorizontalDivider()
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
    var clonedStep by remember { mutableStateOf<ProcedureStep?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var failureStep by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val index = stepIndex
    if (index != null) {
        val clone = clonedStep
        key(index, clone?.id) {
            ProcedureStepEditor(clone ?: steps.getOrNull(index), index, steps.take(index), state, procedures, onBackChanged,
                onClose = { stepIndex = null; clonedStep = null },
                onSave = { step ->
                    steps = when {
                        clone != null -> steps.toMutableList().also { it.add(index, step) }
                        index == steps.size -> steps + step
                        else -> steps.toMutableList().also { it[index] = step }
                    }
                    failure = null
                    failureStep = null
                    stepIndex = null
                    clonedStep = null
                })
        }
        return
    }
    SideEffect { onBackChanged(onClose) }
    ProcedurePageStart(original.id)
    Text(if (original.name.isEmpty()) "New procedure" else "Edit procedure", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(name, { name = it }, label = { Text("Procedure name") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("procedure-name"))
    Spacer(Modifier.height(20.dp))
    Text("Steps", style = MaterialTheme.typography.titleMedium)
    Text("Steps run in order. Add conditions to choose which run.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    fun moveStep(from: Int, to: Int) {
        val reordered = steps.toMutableList().also { it.add(to, it.removeAt(from)) }
        try {
            validateProcedureSteps(reordered)
            steps = reordered
            failure = null
            failureStep = null
        } catch (error: IllegalArgumentException) {
            failure = "A condition must follow the step it checks"
            failureStep = steps[from].id
        }
    }
    steps.forEachIndexed { at, step ->
        OutlinedCard(Modifier.fillMaxWidth().padding(top = 12.dp),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${at + 1}. ${stepTitle(step)}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    ToolIcon(Icons.Outlined.Edit, "Edit step ${at + 1}", onClick = { stepIndex = at })
                }
                ProcedureStepSummary(step, procedures)
                if (step.conditions.isNotEmpty()) Text(conditionSummary(step, steps),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (step.onFailure == ProcedureFailurePolicy.CONTINUE && step.control == null) {
                    Text("On failure: continue", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ToolIcon(Icons.Outlined.ContentCopy, "Clone step ${at + 1}", onClick = {
                        clonedStep = step.copy(id = java.util.UUID.randomUUID().toString())
                        stepIndex = at + 1
                    })
                    ToolIcon(Icons.Outlined.ArrowUpward, "Move step ${at + 1} up", enabled = at > 0,
                        onClick = { moveStep(at, at - 1) })
                    ToolIcon(Icons.Outlined.ArrowDownward, "Move step ${at + 1} down", enabled = at < steps.lastIndex,
                        onClick = { moveStep(at, at + 1) })
                    ToolIcon(Icons.Outlined.Close, "Remove step ${at + 1}", onClick = {
                        if (steps.any { candidate -> candidate.conditions.any { it.stepId == step.id } }) {
                            failure = "Remove conditions that use step ${at + 1} first"
                            failureStep = step.id
                        } else {
                            steps = steps.filterIndexed { position, _ -> position != at }
                            failure = null
                            failureStep = null
                        }
                    })
                }
                if (failureStep == step.id) failure?.let { ProcedureFailure(it) }
            }
        }
    }
    OutlinedButton(onClick = { stepIndex = steps.size }, modifier = Modifier.padding(top = 12.dp)) {
        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Add step")
    }
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    ProcedureScheduleEditor(schedule, onChange = { schedule = it }, onValid = { scheduleValid = it })
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text("Notifications", style = MaterialTheme.typography.titleMedium)
    SwitchRow("Notify on success", notifyOnSuccess) {
        notifyOnSuccess = it
        if (it) actions.requestNotifications()
    }
    SwitchRow("Notify on failure", notifyOnFailure) {
        notifyOnFailure = it
        if (it) actions.requestNotifications()
    }
    if (failureStep == null) failure?.let { ProcedureFailure(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy && name.isNotEmpty() && steps.isNotEmpty() && (!schedule.enabled || scheduleValid), onClick = {
            busy = true; failure = null; failureStep = null
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

private data class ProcedureSourceInput(
    val place: ProcedurePlaceInput = ProcedurePlaceInput(),
    val matching: Boolean = false,
    val pattern: String = "*",
    val recursive: Boolean = false,
    val kind: ProcedureEntryKind = ProcedureEntryKind.FILES,
)

private enum class ProcedureAction(val type: OperationType?, val title: String) {
    COPY(OperationType.COPY, "Copy"), MOVE(OperationType.MOVE, "Move"),
    DELETE(OperationType.DELETE, "Delete permanently"), RENAME(OperationType.RENAME, "Rename"),
    CREATE_FOLDER(OperationType.CREATE_FOLDER, "Create folder"),
    CREATE_ARCHIVE(OperationType.CREATE_ARCHIVE, "Create archive"),
    EXTRACT_ARCHIVE(OperationType.EXTRACT_ARCHIVE, "Extract archive"), STOP(null, "Stop procedure"),
}

@Composable
private fun ProcedureStepEditor(
    original: ProcedureStep?,
    index: Int,
    preceding: List<ProcedureStep>,
    state: BrowserState,
    procedures: Procedures,
    onBackChanged: ((() -> Unit)?) -> Unit,
    onClose: () -> Unit,
    onSave: (ProcedureStep) -> Unit,
) {
    val id = remember { original?.id ?: java.util.UUID.randomUUID().toString() }
    var action by remember { mutableStateOf(if (original?.control == ProcedureControl.STOP) ProcedureAction.STOP
        else ProcedureAction.entries.first { it.type == (original?.type ?: OperationType.COPY) }) }
    var label by remember { mutableStateOf(original?.label.orEmpty()) }
    var sources by remember {
        mutableStateOf(original?.sources?.map { source ->
            ProcedureSourceInput(procedures.input(source.location), source.pattern != null,
                source.pattern ?: "*", source.recursive, source.kind)
        }?.takeIf { it.isNotEmpty() } ?: listOf(ProcedureSourceInput()))
    }
    var destination by remember { mutableStateOf(original?.destination?.let(procedures::input) ?: ProcedurePlaceInput()) }
    var createDestination by remember { mutableStateOf(original?.createDestination ?: false) }
    var ignoreMissingSources by remember { mutableStateOf(original?.ignoreMissingSources ?: false) }
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var conflict by remember { mutableStateOf(original?.conflictPolicy ?: ConflictPolicy.ASK) }
    var keepVersions by remember { mutableStateOf(original?.keepVersions ?: false) }
    var format by remember { mutableStateOf(original?.archive?.format ?: ArchiveFormat.ZIP) }
    var compression by remember { mutableStateOf(original?.archive?.level ?: 6) }
    var conditions by remember { mutableStateOf(original?.conditions.orEmpty()) }
    var conditionMatch by remember { mutableStateOf(original?.conditionMatch ?: ProcedureConditionMatch.ALL) }
    var onFailure by remember { mutableStateOf(original?.onFailure ?: ProcedureFailurePolicy.STOP) }
    var advanced by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf<Int?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val browse = browsing
    if (browse != null) {
        ProcedureLocationPicker(state, procedures, foldersOnly = browse == -1 || sources.getOrNull(browse)?.matching == true,
            onBackChanged = onBackChanged, onClose = { browsing = null }, onChoose = { ref, selectedLabel ->
                val location = ProcedureLocation(ref)
                val chosen = procedures.input(location, selectedLabel)
                if (browse == -1) destination = chosen
                else sources = sources.toMutableList().also { it[browse] = it[browse].copy(place = chosen) }
                browsing = null
            })
        return
    }
    SideEffect { onBackChanged(onClose) }
    ProcedurePageStart(id)
    Text("Step ${index + 1}", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(12.dp))
    ProcedureChoice("Action", action, ProcedureAction.entries, { it.title }) { chosen ->
        action = chosen
        if (chosen.type == OperationType.RENAME || chosen.type == OperationType.EXTRACT_ARCHIVE) {
            sources = sources.take(1).map { it.copy(matching = false) }
        }
    }
    val type = action.type
    val stopped = action == ProcedureAction.STOP
    val needsSources = !stopped && type != OperationType.CREATE_FOLDER
    val singleSource = type == OperationType.RENAME || type == OperationType.EXTRACT_ARCHIVE
    val needsDestination = !stopped && type !in listOf(OperationType.DELETE, OperationType.RENAME)
    val needsName = type in listOf(OperationType.RENAME, OperationType.CREATE_FOLDER, OperationType.CREATE_ARCHIVE)
    if (stopped) {
        Text("End the run here. Earlier errors stay in the report.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (needsSources) {
        ProcedureLocationGroup("From", destination = false) {
            sources.forEachIndexed { sourceIndex, source ->
                ProcedureLocationCard(Modifier.testTag("procedure-source-$sourceIndex")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small) {
                            Text("${sourceIndex + 1}", Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(Modifier.weight(1f))
                        if (!singleSource && sources.size > 1) ToolIcon(Icons.Outlined.Close,
                            "Remove source ${sourceIndex + 1}", onClick = {
                                sources = sources.filterIndexed { at, _ -> at != sourceIndex }
                            })
                    }
                    if (!singleSource) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !source.matching, onClick = {
                                sources = sources.toMutableList().also { it[sourceIndex] = source.copy(matching = false) }
                            }, label = { Text("File or folder") }, modifier = Modifier.testTag("procedure-source-item-$sourceIndex"))
                            FilterChip(selected = source.matching, onClick = {
                                sources = sources.toMutableList().also { it[sourceIndex] = source.copy(matching = true) }
                            }, label = { Text("Match names") }, modifier = Modifier.testTag("procedure-source-folder-$sourceIndex"))
                        }
                    }
                    ProcedurePlaceField("Source ${sourceIndex + 1}", source.place, procedures,
                        onChange = { changed -> sources = sources.toMutableList().also { it[sourceIndex] = source.copy(place = changed) } },
                        onBrowse = { browsing = sourceIndex })
                    if (source.matching && !singleSource) {
                        OutlinedTextField(source.pattern,
                            { value -> sources = sources.toMutableList().also { it[sourceIndex] = source.copy(pattern = value) } },
                            label = { Text("Name pattern") }, supportingText = { Text("* matches any characters; ? matches one character.") },
                            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("procedure-source-pattern-$sourceIndex"))
                        ProcedureChoice("Items", source.kind, ProcedureEntryKind.entries, ::kindLabel) { value ->
                            sources = sources.toMutableList().also { it[sourceIndex] = source.copy(kind = value) }
                        }
                        SwitchRow("Include subfolders ${sourceIndex + 1}", source.recursive) { value ->
                            sources = sources.toMutableList().also { it[sourceIndex] = source.copy(recursive = value) }
                        }
                    }
                }
            }
            if (!singleSource) TextButton(onClick = { sources = sources + ProcedureSourceInput() }) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add source")
            }
            SwitchRow("Ignore missing sources", ignoreMissingSources) { ignoreMissingSources = it }
        }
    }
    if (needsDestination) {
        ProcedureLocationGroup("To", destination = true) {
            ProcedureLocationCard {
                ProcedurePlaceField("Destination", destination, procedures,
                    onChange = { destination = it }, onBrowse = { browsing = -1 })
                SwitchRow("Create missing folders", createDestination) { createDestination = it }
            }
        }
    }
    if (needsName || type == OperationType.EXTRACT_ARCHIVE) {
        Spacer(Modifier.height(12.dp))
        ProcedureTemplateField(if (type == OperationType.EXTRACT_ARCHIVE) "Extract into folder (optional)" else "New name",
            name, { name = it })
    }
    if (type == OperationType.CREATE_ARCHIVE) {
        ProcedureChoice("Format", format, ArchiveFormat.entries.filter { it.creatable }, { it.label }) { format = it }
        if (format.compressible) ProcedureChoice("Compression", compression, CompressionPreset.entries.map { it.level },
            { level -> CompressionPreset.entries.firstOrNull { it.level == level }?.label ?: level.toString() }) { compression = it }
    }
    if (!stopped && type != OperationType.DELETE) {
        Spacer(Modifier.height(8.dp))
        ProcedureChoice("If a name exists", conflict,
            listOf(ConflictPolicy.ASK, ConflictPolicy.SKIP, ConflictPolicy.KEEP_BOTH, ConflictPolicy.REPLACE), ::conflictLabel) { conflict = it }
    }
    HorizontalDivider(Modifier.padding(vertical = 16.dp))
    Text("Run when", style = MaterialTheme.typography.titleMedium)
    if (preceding.isEmpty()) {
        Text("Always", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
    } else {
        ProcedureConditions(conditions, conditionMatch, preceding,
            onChange = { conditions = it }, onMatchChange = { conditionMatch = it })
    }
    if (!stopped) {
        ProcedureChoice("On failure", onFailure, ProcedureFailurePolicy.entries,
            { if (it == ProcedureFailurePolicy.STOP) "Stop procedure" else "Continue" }) { onFailure = it }
        if (onFailure == ProcedureFailurePolicy.CONTINUE) {
            Text("Continue to handle errors in later steps. The run still reports failures.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    TextButton(onClick = { advanced = !advanced }) {
        Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        Text("Step options")
    }
    if (advanced) {
        OutlinedTextField(label, { label = it }, label = { Text("Step label (optional)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        if (type in listOf(OperationType.DELETE, OperationType.MOVE, OperationType.RENAME)) {
            SwitchRow("Keep older versions", keepVersions) { keepVersions = it }
        }
    }
    failure?.let { ProcedureFailure(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy && (!needsName || name.isNotEmpty()), onClick = {
            busy = true; failure = null
            scope.launch {
                try {
                    val step = ProcedureStep(
                        id = id, label = label,
                        type = type ?: OperationType.COPY,
                        control = if (stopped) ProcedureControl.STOP else null,
                        sources = if (!needsSources) emptyList() else sources.map { source ->
                            ProcedureSource(procedures.resolve(source.place, allowMissing = ignoreMissingSources),
                                if (source.matching && !singleSource) source.pattern else null,
                                source.recursive && !singleSource, source.kind)
                        },
                        ignoreMissingSources = needsSources && ignoreMissingSources,
                        destination = if (needsDestination) procedures.resolve(destination, createDestination) else null,
                        createDestination = needsDestination && createDestination,
                        name = if (needsName || type == OperationType.EXTRACT_ARCHIVE) name.takeIf { it.isNotEmpty() } else null,
                        conflictPolicy = if (stopped) ConflictPolicy.ASK else conflict,
                        archive = if (type == OperationType.CREATE_ARCHIVE) ArchiveSpec(format = format, level = compression) else null,
                        keepVersions = !stopped && type in listOf(OperationType.DELETE, OperationType.MOVE, OperationType.RENAME) && keepVersions,
                        conditions = conditions, conditionMatch = conditionMatch,
                        onFailure = if (stopped) ProcedureFailurePolicy.STOP else onFailure,
                    )
                    validateProcedureSteps(preceding + step)
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
private fun ProcedureLocationGroup(
    title: String,
    destination: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().padding(top = 20.dp)
        .testTag(if (destination) "procedure-to" else "procedure-from"),
        shape = MaterialTheme.shapes.large,
        color = if (destination) colors.secondaryContainer else colors.surfaceContainerHigh,
        contentColor = if (destination) colors.onSecondaryContainer else colors.onSurface,
        border = BorderStroke(1.dp, if (destination) colors.secondary.copy(alpha = 0.4f) else colors.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ProcedureLocationCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun ProcedureConditions(
    conditions: List<ProcedureCondition>,
    match: ProcedureConditionMatch,
    preceding: List<ProcedureStep>,
    onChange: (List<ProcedureCondition>) -> Unit,
    onMatchChange: (ProcedureConditionMatch) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = conditions.isEmpty(), onClick = { onChange(emptyList()) }, label = { Text("Always") })
        FilterChip(selected = conditions.isNotEmpty(), onClick = {
            if (conditions.isEmpty()) onChange(listOf(ProcedureCondition(preceding.last().id, ProcedureConditionTest.SUCCEEDED)))
        }, label = { Text("Conditions") })
    }
    if (conditions.isEmpty()) return
    Text("Check an earlier step's result. Unmatched steps are skipped.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (conditions.size > 1) ProcedureChoice("Match", match, ProcedureConditionMatch.entries,
        { if (it == ProcedureConditionMatch.ALL) "All conditions" else "Any condition" }, onMatchChange)
    conditions.forEachIndexed { index, condition ->
        OutlinedCard(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Condition ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    ToolIcon(Icons.Outlined.Close, "Remove condition ${index + 1}", onClick = {
                        onChange(conditions.filterIndexed { at, _ -> at != index })
                    })
                }
                ProcedureChoice("Step", condition.stepId, preceding.map { it.id }, { id ->
                    val at = preceding.indexOfFirst { it.id == id }
                    "${at + 1}. ${preceding.getOrNull(at)?.let(::stepTitle).orEmpty()}"
                }) { id -> onChange(conditions.toMutableList().also { it[index] = condition.copy(stepId = id) }) }
                ProcedureChoice("Result", condition.test, ProcedureConditionTest.entries, ::conditionLabel) { test ->
                    onChange(conditions.toMutableList().also { it[index] = condition.copy(test = test) })
                }
                if (condition.test == ProcedureConditionTest.FAILED &&
                    preceding.firstOrNull { it.id == condition.stepId }?.onFailure == ProcedureFailurePolicy.STOP) {
                    val checked = preceding.indexOfFirst { it.id == condition.stepId } + 1
                    Text("Step $checked stops on failure. Set its On failure option to Continue to reach this step.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (condition.test in listOf(ProcedureConditionTest.HAS_OUTPUT, ProcedureConditionTest.NO_OUTPUT)) {
                    Text("Counts completed file actions; creating a destination alone doesn't count.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (condition.test == ProcedureConditionTest.NO_OUTPUT) Text("Skipped steps don't match.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    TextButton(onClick = { onChange(conditions + ProcedureCondition(preceding.last().id, ProcedureConditionTest.SUCCEEDED)) }) {
        Text("Add condition")
    }
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
    ProcedurePageStart(here?.first ?: "locations")
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
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${title(selected)}", Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, null, Modifier.padding(start = 8.dp).size(18.dp))
        }
        FastDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(text = { Text(title(choice)) }, onClick = { expanded = false; onChoose(choice) })
            }
        }
    }
}

@Composable
private fun ProcedurePageStart(page: Any) {
    val requester = remember { BringIntoViewRequester() }
    Spacer(Modifier.fillMaxWidth().height(1.dp).bringIntoViewRequester(requester))
    LaunchedEffect(page) {
        withFrameNanos { }
        requester.bringIntoView()
    }
}

@Composable
private fun ProcedureFailure(message: String) {
    Text(message, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error)
}

@Composable
private fun ProcedureStepSummary(step: ProcedureStep, procedures: Procedures) {
    val summary by produceState("", step, procedures) {
        val source = step.sources.take(2).map { selected ->
            val place = procedures.label(selected.location)
            selected.pattern?.let { "$it in $place" } ?: place
        }.joinToString("\n") + if (step.sources.size > 2) "\n+${step.sources.size - 2} sources" else ""
        val destination = step.destination?.let { procedures.label(it) }
        value = when {
            step.control == ProcedureControl.STOP -> "Ends this run"
            step.type == OperationType.CREATE_FOLDER -> "$destination → ${step.name}"
            step.type == OperationType.RENAME -> "$source → ${step.name}"
            destination != null -> "$source → $destination" + (step.name?.let { "/$it" } ?: "")
            else -> source
        }
    }
    if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    if (step.createDestination) Text("Create missing folders", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun stepTitle(step: ProcedureStep): String {
    val action = if (step.control == ProcedureControl.STOP) "Stop procedure" else operationLabel(step.type)
    return if (step.label.isEmpty()) action else "${step.label} · $action"
}

private fun conditionSummary(step: ProcedureStep, steps: List<ProcedureStep>): String = "When " +
    step.conditions.joinToString(if (step.conditionMatch == ProcedureConditionMatch.ALL) " and " else " or ") { condition ->
        "step ${steps.indexOfFirst { it.id == condition.stepId } + 1}: ${conditionLabel(condition.test).lowercase()}"
    }

private fun conditionLabel(test: ProcedureConditionTest): String = when (test) {
    ProcedureConditionTest.SUCCEEDED -> "Succeeded"
    ProcedureConditionTest.FAILED -> "Failed"
    ProcedureConditionTest.SKIPPED -> "Skipped"
    ProcedureConditionTest.HAS_OUTPUT -> "Completed items"
    ProcedureConditionTest.NO_OUTPUT -> "No completed items"
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
