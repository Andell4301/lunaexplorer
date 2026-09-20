@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lunaexplorer.core.BatchRename
import com.lunaexplorer.core.CaseMode
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.RenameStep

@Composable
fun BatchRenameDialog(
    entries: List<Entry>,
    existing: Set<String>,
    includeExtension: Boolean,
    onRemember: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onApply: (List<RenameStep>, Boolean) -> Unit,
) {
    var steps by remember { mutableStateOf<List<RenameStep>>(emptyList()) }
    var include by rememberSaveable { mutableStateOf(includeExtension) }
    val preview = remember(entries, steps, existing, include) {
        BatchRename.preview(entries, steps, existing, keepExtension = !include)
    }
    val blocked = preview.count { it.blocked }
    val changing = preview.count { it.changed && !it.blocked }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("Rename ${entries.size} items", style = MaterialTheme.typography.titleLarge)
                        Text(
                            buildString {
                                append("$changing will change")
                                if (blocked > 0) append("  ·  $blocked blocked")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (blocked > 0) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { onApply(steps, include) },
                        enabled = changing > 0 && blocked == 0,
                    ) { Text("Rename") }
                }
                HorizontalDivider()

                Column(Modifier.fastVerticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    RenameHint("Steps run top to bottom.", Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).heightIn(min = 40.dp)
                            .combinedClickable(onClick = { include = !include }, role = Role.Checkbox),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = include, onCheckedChange = { include = it })
                            Text("Include extension", style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick = { onRemember(include) }, enabled = include != includeExtension,
                            contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Remember") }
                    }
                    steps.forEachIndexed { index, step ->
                        StepCard(
                            step = step,
                            includeExtension = include,
                            onChange = { updated -> steps = steps.toMutableList().also { it[index] = updated } },
                            onRemove = { steps = steps.filterIndexed { at, _ -> at != index } },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AddStep("Replace") { steps = steps + RenameStep.Replace("", "") }
                        AddStep("Case") { steps = steps + RenameStep.ChangeCase(CaseMode.LOWER) }
                        AddStep("Number") { steps = steps + RenameStep.Numbering() }
                        AddStep("Insert") { steps = steps + RenameStep.Insert("", 0) }
                        AddStep("Remove") { steps = steps + RenameStep.Remove(0, 1) }
                        AddStep("Template") { steps = steps + RenameStep.Template("{name}") }
                        AddStep("Trim") { steps = steps + RenameStep.Trim() }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                HorizontalDivider()
                Text("PREVIEW", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                FastLazyColumn(Modifier.weight(1f)) {
                    items(preview, key = { it.entry.ref.key }) { row ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                            Text(row.from, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (row.changed) "→ ${row.to}" else "→ unchanged",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = when {
                                    row.blocked -> MaterialTheme.colorScheme.error
                                    row.changed -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            row.problem?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .25f))
                    }
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

@Composable
private fun AddStep(label: String, onClick: () -> Unit) {
    SuggestionChip(onClick = onClick, label = { Text(label) })
}

@Composable
private fun StepCard(step: RenameStep, includeExtension: Boolean, onChange: (RenameStep) -> Unit, onRemove: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stepName(step), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Close, "Remove step", Modifier.size(16.dp))
                }
            }
            RenameHint(stepHint(step, includeExtension), Modifier.padding(bottom = 6.dp))
            when (step) {
                is RenameStep.Replace -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(step.find, { onChange(step.copy(find = it)) },
                            label = { Text("Find") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(step.with, { onChange(step.copy(with = it)) },
                            label = { Text("Replace with") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(step.regex, { onChange(step.copy(regex = !step.regex)) },
                            label = { Text("Regex") })
                        FilterChip(!step.ignoreCase, { onChange(step.copy(ignoreCase = !step.ignoreCase)) },
                            label = { Text("Match case") })
                        FilterChip(step.firstOnly, { onChange(step.copy(firstOnly = !step.firstOnly)) },
                            label = { Text("First only") })
                    }
                    if (step.regex) {
                        RenameHint("Patterns: [0-9]+ matches digits. Groups: \$1, \$2…")
                    } else {
                        RenameHint("Match case checks capitals. First only changes one match per name.")
                    }
                }
                is RenameStep.ChangeCase -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CaseMode.entries.forEach { mode ->
                        FilterChip(step.mode == mode, { onChange(RenameStep.ChangeCase(mode)) },
                            label = { Text(mode.label) })
                    }
                }
                is RenameStep.Numbering -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Start", step.start, "First number") { onChange(step.copy(start = it)) }
                        NumberField("Step", step.step, "Count by") { onChange(step.copy(step = it)) }
                        NumberField("Digits", step.padding, "3 gives 001") { onChange(step.copy(padding = it.coerceIn(1, 12))) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(step.separator, { onChange(step.copy(separator = it)) },
                            label = { Text("Separator") }, supportingText = { Text("Between name and number") },
                            singleLine = true, modifier = Modifier.weight(1f))
                        FilterChip(step.append, { onChange(step.copy(append = !step.append)) },
                            label = { Text(if (step.append) "At end" else "At start") })
                    }
                }
                is RenameStep.Insert -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(step.text, { onChange(step.copy(text = it)) },
                        label = { Text("Text") }, singleLine = true, modifier = Modifier.weight(1f))
                    NumberField("At", step.position) { onChange(step.copy(position = it)) }
                }
                is RenameStep.Remove -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("From", step.position) { onChange(step.copy(position = it)) }
                    NumberField("Count", step.count, "How many") { onChange(step.copy(count = it)) }
                }
                is RenameStep.Template -> {
                    OutlinedTextField(step.pattern, { onChange(RenameStep.Template(it)) },
                        label = { Text("Pattern") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    RenameHint("{name} current name · {ext} extension without dot\n" +
                        "{n} sequence number · {index} position from 0\n" +
                        "{date} modified date · {time} modified time\n" +
                        "{size} bytes")
                    if (step.pattern.contains("{n}")) {
                        RenameHint("Number sets {n}'s Start and Step; Digits padding isn't used.")
                    }
                }
                is RenameStep.Trim -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(step.leading, { onChange(step.copy(leading = !step.leading)) },
                        label = { Text("Leading") })
                    FilterChip(step.trailing, { onChange(step.copy(trailing = !step.trailing)) },
                        label = { Text("Trailing") })
                }
            }
        }
    }
}

@Composable
private fun RowScope.NumberField(label: String, value: Int, hint: String? = null, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        supportingText = hint?.let { { Text(it) } },
        singleLine = true,
        isError = text.toIntOrNull() == null,
        modifier = Modifier.weight(1f),
    )
}

@Composable
private fun RenameHint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun stepHint(step: RenameStep, includeExtension: Boolean): String = when (step) {
    is RenameStep.Replace -> "Replace matching text. Leave replacement blank to delete it."
    is RenameStep.ChangeCase -> when (step.mode) {
        CaseMode.LOWER -> "Make all letters lowercase."
        CaseMode.UPPER -> "Make all letters uppercase."
        CaseMode.TITLE -> "Capitalize each word's first character; leave the rest unchanged."
        CaseMode.SENTENCE -> "Capitalize the first character; lowercase the rest."
    }
    is RenameStep.Numbering -> "Add a number to each name, in preview order."
    is RenameStep.Insert -> "Insert text: 0 = start, −1 = before the last character."
    is RenameStep.Remove -> "Remove characters: 0 = first, −1 = last."
    is RenameStep.Template ->
        if (includeExtension) "Build a name with placeholders." else "Build a name with placeholders. Extension kept."
    is RenameStep.Trim -> "Remove spaces at the start (Leading) or end (Trailing)."
}

private fun stepName(step: RenameStep): String = when (step) {
    is RenameStep.Replace -> "REPLACE"
    is RenameStep.ChangeCase -> "CASE"
    is RenameStep.Numbering -> "NUMBER"
    is RenameStep.Insert -> "INSERT"
    is RenameStep.Remove -> "REMOVE"
    is RenameStep.Template -> "TEMPLATE"
    is RenameStep.Trim -> "TRIM"
}
