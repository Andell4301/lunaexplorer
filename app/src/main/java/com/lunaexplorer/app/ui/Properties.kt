@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
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
import com.lunaexplorer.app.storage.FileFacts
import com.lunaexplorer.app.storage.formatDevice
import com.lunaexplorer.app.storage.formatMode
import com.lunaexplorer.app.storage.formatOctalMode
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Digest
import com.lunaexplorer.core.EditableTag
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.FolderTotals
import com.lunaexplorer.core.MetadataSection
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

@Composable
fun PropertiesScreen(entries: List<Entry>, viewModel: BrowserViewModel, onDismiss: () -> Unit) {
    val single = entries.singleOrNull()
    val measurable = entries.any { it.directory }
    var totals by remember(entries) { mutableStateOf<FolderTotals?>(null) }
    var measuring by remember(entries) { mutableStateOf(measurable) }
    var facts by remember(entries) { mutableStateOf<FileFacts?>(null) }
    var message by remember(entries) { mutableStateOf<String?>(null) }

    LaunchedEffect(entries) {
        if (single != null) facts = runCatching { viewModel.files.facts(single) }.getOrNull()
    }
    LaunchedEffect(entries) {
        if (!measurable) return@LaunchedEffect
        try { viewModel.files.measure(entries.map { it.ref }).collect { totals = it } } finally { measuring = false }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(single?.name ?: "${entries.size} items", style = MaterialTheme.typography.titleLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                message?.let {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(it, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Column(Modifier.weight(1f).fastVerticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                    if (single == null) {
                        SelectionFacts(entries, totals, measuring)
                    } else {
                        SingleFacts(single, facts, totals, measuring, viewModel) { message = it }
                    }
                    Spacer(Modifier.height(40.dp))
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

@Composable
private fun SelectionFacts(entries: List<Entry>, totals: FolderTotals?, measuring: Boolean) {
    Group("Selection")
    Fact("Items", "${entries.count { it.directory }} folders, ${entries.count { !it.directory }} files")
    val known = entries.filter { !it.directory }.mapNotNull { it.size }
    Fact("Known file sizes", "${formatBytes(known.sum())} across ${known.size} files")
    ContentsFact(totals, measuring)
    Group("Included")
    entries.take(50).forEach {
        Text(it.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (entries.size > 50) {
        Text("and ${entries.size - 50} more", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SingleFacts(
    entry: Entry,
    facts: FileFacts?,
    totals: FolderTotals?,
    measuring: Boolean,
    viewModel: BrowserViewModel,
    onMessage: (String) -> Unit,
) {
    Group("Item")
    Fact("Name", entry.name)
    Fact("Type", if (entry.directory) "Folder" else (facts?.mimeType ?: entry.mimeType))
    Fact("Hidden", if (entry.hidden) "Yes" else "No")
    facts?.linkTarget?.let {
        Fact("Link target", it + if (facts.brokenLink) "  (target is missing)" else "")
    }

    Group("Location")
    Copyable("Path", facts?.path, "Not exposed by this storage provider")
    facts?.realPath?.let { Copyable("Resolved path", it) }
    Copyable("Content URI", facts?.contentUri)
    facts?.documentId?.let { Copyable("Document ID", it) }

    Group("Size")
    if (!entry.directory) {
        val size = facts?.size ?: entry.size
        Fact("Size", size?.let { "${formatBytes(it)}  (${"%,d".format(it)} bytes)" } ?: "Unknown")
        Fact("On disk", facts?.allocated?.let { "${formatBytes(it)}  (${"%,d".format(it)} bytes)" } ?: unavailable(facts))
        if (facts?.sparse == true) Fact("Sparse", "Yes — fewer blocks are allocated than the file's length")
        facts?.blockSize?.let { Fact("Block size", "${"%,d".format(it)} bytes") }
    }
    ContentsFact(totals, measuring)

    facts?.details?.takeIf { it.isNotEmpty() }?.let { details ->
        Group(detailsHeading(entry))
        details.forEach { (label, value) -> Fact(label, value) }
    }

    if (!entry.directory && viewModel.files.canEditTags(entry)) {
        Group("Tags")
        TagEditorSection(entry, viewModel)
    }

    facts?.apk?.let { report ->
        report.sections.forEach { section ->
            Group(section.name)
            section.facts.forEach { (label, value) -> Fact(label, value) }
        }
        if (report.anomalies.isNotEmpty()) {
            Group("Structure")
            report.anomalies.forEach { problem ->
                Text(problem, Modifier.padding(vertical = 3.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
        report.failure?.let { Fact("Could not be parsed", it) }
    }

    facts?.metadata?.takeIf { !it.isEmpty }?.let { report ->
        Group("Metadata  ·  ${report.tagCount} tags in ${report.sections.size} sections")
        report.sections.forEach { section -> MetadataSectionRows(section) }
    }

    Group("Times")
    ModifiedFact(entry, facts, viewModel, onMessage)
    Fact("Changed", facts?.changed?.let(::absoluteAndRelative) ?: unavailable(facts))
    Fact("Accessed", facts?.accessed?.let(::absoluteAndRelative) ?: unavailable(facts))

    Group("Permissions")
    Fact("Mode", facts?.mode?.let { "${formatMode(it)}   ${formatOctalMode(it)}" } ?: unavailable(facts))
    Fact("Owner", facts?.let { owner(it.owner, it.uid) } ?: unavailable(facts))
    Fact("Group", facts?.let { owner(it.group, it.gid) } ?: unavailable(facts))
    Fact("Access", facts?.let {
        listOfNotNull(
            if (it.readable == true) "read" else null,
            if (it.writable == true) "write" else null,
            if (it.executable == true) "execute" else null,
        ).joinToString(", ").ifBlank { "none" }
    } ?: unavailable(facts))

    Group("Filesystem")
    Fact("Type", facts?.filesystem ?: unavailable(facts))
    facts?.mountOptions?.let { Fact("Mount options", it) }
    Fact("Inode", facts?.inode?.toString() ?: unavailable(facts))
    Fact("Device", facts?.device?.let { formatDevice(it) } ?: unavailable(facts))
    Fact("Hard links", facts?.links?.toString() ?: unavailable(facts))
    facts?.selinux?.let { Copyable("SELinux context", it) }
    if (facts?.extendedAttributes?.isNotEmpty() == true) {
        Fact("Extended attributes", facts.extendedAttributes.joinToString(", "))
    }

    if (!entry.directory && Capability.READ in entry.capabilities) Checksums(entry, viewModel)
}

private fun detailsHeading(entry: Entry) = when {
    entry.mimeType.startsWith("image/") -> "Image"
    entry.mimeType.startsWith("audio/") -> "Audio"
    entry.mimeType.startsWith("video/") -> "Video"
    entry.mimeType == "application/vnd.android.package-archive" -> "Package"
    else -> "Contents"
}

private fun owner(name: String?, id: Int?): String = when {
    name == null && id == null -> "Not exposed by this storage provider"
    // The platform reports an unresolved owner's numeric id as its name.
    name == null || name == id?.toString() -> "${id ?: "?"}  (no name)"
    else -> "$name  ($id)"
}

private fun unavailable(facts: FileFacts?) = if (facts == null) "Reading…" else "Not exposed by this storage provider"

@Composable
private fun ContentsFact(totals: FolderTotals?, measuring: Boolean) {
    if (totals == null && !measuring) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Contents", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (totals == null) "Measuring…" else buildString {
                    append(formatBytes(totals.bytes))
                    append("  ·  ${"%,d".format(totals.files)} files, ${"%,d".format(totals.folders)} folders")
                    if (totals.unknownSizes > 0) append("  ·  ${totals.unknownSizes} of unknown size")
                    if (totals.unreadable > 0) append("  ·  ${totals.unreadable} unreadable")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (measuring) {
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun ModifiedFact(entry: Entry, facts: FileFacts?, viewModel: BrowserViewModel, onMessage: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val modified = facts?.modified ?: entry.modified
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Modified", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionText(modified?.let(::absoluteAndRelative) ?: "Not reported")
        }
        if (facts?.modifiableTime == true) {
            IconButton(onClick = { editing = true }) { Icon(Icons.Outlined.EditCalendar, "Change the modification time") }
        }
    }
    Spacer(Modifier.height(10.dp))
    if (editing) {
        TimestampDialog(modified ?: System.currentTimeMillis(), onDismiss = { editing = false }) { chosen ->
            editing = false
            viewModel.files.setModified(entry, chosen, onMessage)
        }
    }
}

@Composable
private fun TimestampDialog(initial: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var text by remember { mutableStateOf(formatTimestamp(initial)) }
    val parsed = remember(text) { parseTimestamp(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modification time") },
        text = {
            Column {
                TimestampField(text, { text = it }, label = "yyyy-MM-dd HH:mm:ss")
                Text("The filesystem may round this; the stored value is shown afterwards.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Checksums(entry: Entry, viewModel: BrowserViewModel) {
    val copy = rememberCopyText()
    var running by remember(entry.ref) { mutableStateOf(false) }
    var read by remember(entry.ref) { mutableLongStateOf(0L) }
    var results by remember(entry.ref) { mutableStateOf<List<Digest>>(emptyList()) }

    Group("Checksums")
    if (results.isEmpty() && !running) {
        Text("Compute checksums of the file with a variety of algorithms.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = { running = true }) { Text("Compute") }
    }
    if (running) {
        LaunchedEffect(entry.ref) {
            try {
                viewModel.files.digestsFor(entry).collect { progress ->
                    read = progress.bytesRead
                    if (progress.complete) { results = progress.results; running = false }
                }
            } catch (_: Exception) {
                running = false
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                entry.size?.takeIf { it > 0 }?.let { "${(read * 100 / it).coerceAtMost(100)}%  ·  ${formatBytes(read)}" }
                    ?: formatBytes(read),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    results.forEach { digest ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .combinedClickable(onClick = { copy(digest.value) }, role = Role.Button),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(digest.algorithm, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(digest.value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Icon(Icons.Outlined.ContentCopy, "Copy ${digest.algorithm}", Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (results.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = {
            copy(results.joinToString("\n") { "${it.algorithm}  ${it.value}" })
        }) { Text("Copy all") }
    }
}

@Composable
private fun Group(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Fact(label: String, value: String) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionText(value)
    }
}

@Composable
private fun Copyable(label: String, value: String?, absent: String = "Not available") {
    val copy = rememberCopyText()
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).then(
        if (value == null) Modifier
        else Modifier.combinedClickable(onClick = { copy(value) }, role = Role.Button),
    ), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(bottom = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value ?: absent, style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (value == null) FontFamily.SansSerif else FontFamily.Monospace,
                color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
        if (value != null) {
            Icon(Icons.Outlined.ContentCopy, "Copy $label", Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SelectionText(value: String) {
    SelectionContainer {
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun absoluteAndRelative(millis: Long): String {
    val absolute = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(millis))
    return "$absolute  ·  ${relativeTime(millis)}"
}

private fun relativeTime(millis: Long): String {
    val delta = System.currentTimeMillis() - millis
    val future = delta < 0
    val seconds = abs(delta) / 1000
    val text = when {
        seconds < 60 -> "moments"
        seconds < 3600 -> "${seconds / 60} minute${plural(seconds / 60)}"
        seconds < 86_400 -> "${seconds / 3600} hour${plural(seconds / 3600)}"
        seconds < 2_592_000 -> "${seconds / 86_400} day${plural(seconds / 86_400)}"
        seconds < 31_536_000 -> "${seconds / 2_592_000} month${plural(seconds / 2_592_000)}"
        else -> "${seconds / 31_536_000} year${plural(seconds / 31_536_000)}"
    }
    return if (future) "in $text" else "$text ago"
}

private fun plural(value: Long) = if (value == 1L) "" else "s"

@Composable
private fun MetadataSectionRows(section: MetadataSection) {
    var open by rememberSaveable(section.name) { mutableStateOf(false) }
    val copy = rememberCopyText()
    Row(
        Modifier.fillMaxWidth()
            .clickable { open = !open }
            .heightIn(min = 44.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text("[${section.name}]", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Text("${section.tags.size}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) {
        section.tags.forEach { tag ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable { copy("${tag.name}: ${tag.value}") }
                    .padding(start = 26.dp, top = 3.dp, bottom = 3.dp),
            ) {
                Text(tag.name, Modifier.weight(0.42f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tag.value.ifEmpty { "—" }, Modifier.weight(0.58f),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        section.errors.forEach { problem ->
            Text(problem, Modifier.padding(start = 26.dp, top = 3.dp, bottom = 3.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TagEditorSection(entry: Entry, viewModel: BrowserViewModel) {
    var tags by remember(entry.ref) { mutableStateOf<List<EditableTag>?>(null) }
    var edits by remember(entry.ref) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var saving by remember(entry.ref) { mutableStateOf(false) }

    LaunchedEffect(entry.ref) { tags = viewModel.files.readTags(entry) }

    val current = tags
    if (current == null) {
        Text("Reading tags", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    current.forEach { tag ->
        val value = edits[tag.key] ?: tag.value
        OutlinedTextField(
            value = value,
            onValueChange = { edits = edits + (tag.key to it) },
            label = { Text(tag.label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        )
    }
    val changed = edits.any { (key, value) -> current.firstOrNull { it.key == key }?.value != value }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            enabled = changed && !saving,
            onClick = {
                saving = true
                val merged = current.associate { it.key to (edits[it.key] ?: it.value) }
                viewModel.files.writeTags(entry, merged) {
                    saving = false
                    edits = emptyMap()
                }
            },
        ) { Text(if (saving) "Saving" else "Save tags") }
        if (changed) {
            TextButton(onClick = { edits = emptyMap() }) { Text("Revert") }
        }
    }
}
