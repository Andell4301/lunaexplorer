@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lunaexplorer.app.storage.AppSize
import com.lunaexplorer.app.storage.CategoryTotal
import com.lunaexplorer.app.storage.DuplicateReport
import com.lunaexplorer.app.storage.MediaCategory
import com.lunaexplorer.core.Entry

@Composable
internal fun DuplicateReview(report: DuplicateReport, viewModel: BrowserViewModel, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    val back = { if (chosen.isNotEmpty()) chosen = emptySet() else onDismiss() }
    val bytes = report.groups.sumOf { group -> group.bytesEach * group.paths.count { it in chosen } }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                ToolBar("Duplicate files", back)
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        // Paths are newest first; select all older copies.
                        chosen = report.groups.flatMapTo(mutableSetOf()) { it.paths.drop(1) }
                    }) { Text("Tick all but one of each") }
                    TextButton(enabled = chosen.isNotEmpty(),
                        onClick = { chosen = emptySet() }) { Text("Tick none") }
                    FilledTonalButton(enabled = chosen.isNotEmpty(), onClick = {
                        viewModel.tools.deleteFiles(chosen.toList())
                        onDismiss()
                    }) { Text("Remove ${chosen.size}  ·  ${formatBytes(bytes)}") }
                }
                Text("Tap a copy to see where it is; press and hold to pick it out.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                FastLazyColumn(Modifier.weight(1f)) {
                    items(report.groups, key = { it.paths.first() }) { group ->
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(group.paths.first().substringAfterLast('/'),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${group.paths.size} copies  ·  ${formatBytes(group.bytesEach)} each  ·  " +
                                "${formatBytes(group.reclaimable)} recoverable",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            group.paths.forEach { path ->
                                val picked = path in chosen
                                val toggle = { chosen = if (picked) chosen - path else chosen + path }
                                Row(Modifier.fillMaxWidth()
                                    .combinedClickable(
                                        role = Role.Button,
                                        onClick = { if (chosen.isEmpty()) viewModel.reveal(path) else toggle() },
                                        onLongClick = toggle,
                                        onLongClickLabel = "Select $path")
                                    .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (chosen.isNotEmpty()) {
                                        Checkbox(checked = picked, onCheckedChange = null)
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    Text(path, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

@Composable
internal fun AnalysisCard(
    title: String,
    icon: ImageVector? = null,
    hue: Hue = Hue.PLAIN,
    headline: String? = null,
    subtitle: String? = null,
    loading: Boolean = false,
    loadingLabel: String = "Reading",
    action: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Surface(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    if (LocalColorfulIcons.current) {
                        KindIcon(icon, hue, null, plainSize = 19.dp, badgeSize = 34.dp, plainTint = Color.Unspecified)
                    } else {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center) {
                            Icon(icon, null, Modifier.size(19.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (headline != null) {
                    Text(headline, style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                action()
            }
            if (loading) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(6.dp))
                Text(loadingLabel, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
internal fun StorageCategoryBar(
    totals: Map<MediaCategory, CategoryTotal>?,
    usedBytes: Long,
    apps: List<AppSize>?,
) {
    if (totals == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Counting", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val appBytes = apps?.sumOf { it.totalBytes } ?: 0L
    val counted = totals.values.sumOf { it.bytes } + appBytes
    val other = (usedBytes - counted).coerceAtLeast(0L)
    val slices = buildList {
        totals.forEach { (category, total) -> if (total.bytes > 0) add(category.label to total.bytes) }
        if (appBytes > 0) add("Applications" to appBytes)
        if (other > 0) add("System and other" to other)
    }.sortedByDescending { it.second }
    if (slices.isEmpty()) {
        Text("Nothing measurable yet.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val whole = slices.sumOf { it.second }.coerceAtLeast(1L)
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.surfaceContainerHighest,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(6.dp))) {
            slices.forEachIndexed { index, (_, bytes) ->
                Box(Modifier.weight(bytes.toFloat() / whole).fillMaxHeight()
                    .background(palette[index % palette.size]))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            slices.forEachIndexed { index, (label, bytes) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp))
                        .background(palette[index % palette.size]))
                    Spacer(Modifier.width(6.dp))
                    Text("$label ${formatBytes(bytes)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

internal fun rowsOf(entries: List<Entry>): List<AnalysisRow> = entries.map { entry ->
    AnalysisRow(
        key = "${entry.ref.provider}:${entry.ref.key}",
        name = entry.name,
        detail = entry.mimeType,
        bytes = entry.size,
        entry = entry,
    )
}

internal data class AnalysisRow(
    val key: String,
    val name: String,
    val detail: String,
    val bytes: Long?,
    val entry: Entry? = null,
    val path: String? = null,
)

private const val COLLAPSED_ROWS = 10

@Composable
internal fun AnalysisFiles(
    rows: List<AnalysisRow>,
    viewModel: BrowserViewModel? = null,
    onReveal: (AnalysisRow) -> Unit,
    onDelete: (List<AnalysisRow>) -> Unit,
) {
    if (rows.isEmpty()) {
        Text("Nothing found.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var expanded by remember(rows) { mutableStateOf(false) }
    var picked by remember(rows) { mutableStateOf(emptySet<String>()) }
    ToolSelection(viewModel, picked.isNotEmpty()) { picked = emptySet() }
    val selecting = picked.isNotEmpty()
    rows.take(if (expanded) rows.size else COLLAPSED_ROWS).forEach { row ->
        val ticked = row.key in picked
        val toggle = { picked = if (ticked) picked - row.key else picked + row.key }
        Row(Modifier.fillMaxWidth()
            .combinedClickable(
                role = Role.Button,
                onClick = { if (selecting) toggle() else onReveal(row) },
                onLongClick = toggle,
                onLongClickLabel = "Select ${row.name}")
            .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                Checkbox(checked = ticked, onCheckedChange = null)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            row.bytes?.let { Text(formatBytes(it), style = MaterialTheme.typography.labelLarge) }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        if (rows.size > COLLAPSED_ROWS) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show fewer" else "Show all ${rows.size}")
            }
        }
        if (selecting) {
            TextButton(onClick = { picked = emptySet() }) { Text("Tick none") }
            TextButton(onClick = {
                onDelete(rows.filter { it.key in picked })
                picked = emptySet()
            }) { Text("Remove ${picked.size}") }
        }
    }
}

/** Analysis results arrive independently so slower scans do not delay earlier results. */
sealed interface StorageAnalysisPart {
    data class Categories(val totals: Map<MediaCategory, CategoryTotal>) : StorageAnalysisPart
    data class Older(val entries: List<Entry>) : StorageAnalysisPart
    data class Largest(val entries: List<Entry>) : StorageAnalysisPart
    data class Apps(val apps: List<AppSize>, val accurate: Boolean) : StorageAnalysisPart
    data class Duplicates(val report: DuplicateReport) : StorageAnalysisPart
}
