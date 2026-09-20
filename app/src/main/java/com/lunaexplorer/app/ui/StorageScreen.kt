@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.storage.AppSize
import com.lunaexplorer.app.storage.CategoryTotal
import com.lunaexplorer.app.storage.DuplicateReport
import com.lunaexplorer.app.storage.InstalledApp
import com.lunaexplorer.app.storage.MediaCategory
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.RootKind
import com.lunaexplorer.core.StorageRoot
import kotlinx.coroutines.launch

@Composable
fun StorageScreen(
    roots: List<StorageRoot>,
    viewModel: BrowserViewModel,
    actions: LunaActions,
    fullAccess: Boolean = true,
    browsing: String? = null,
) {
    var categories by remember { mutableStateOf<Map<MediaCategory, CategoryTotal>?>(null) }
    var older by remember { mutableStateOf<List<Entry>?>(null) }
    var largest by remember { mutableStateOf<List<Entry>?>(null) }
    var apps by remember { mutableStateOf<Pair<List<AppSize>, Boolean>?>(null) }
    var duplicates by remember { mutableStateOf<DuplicateReport?>(null) }
    var showAllApps by remember { mutableStateOf(false) }
    var openedApp by remember { mutableStateOf<InstalledApp?>(null) }
    val dupPlaces = remember(browsing) {
        viewModel.tools.duplicateScopes() + listOfNotNull(browsing?.let { "Custom" to listOf(it) })
    }
    var dupScope by remember { mutableStateOf(dupPlaces.firstOrNull()) }
    var dupBudget by remember { mutableLongStateOf(DUPLICATE_BUDGET) }
    var scanningDuplicates by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val reveal: (AnalysisRow) -> Unit = { row ->
        row.entry?.let { viewModel.revealEntry(it) } ?: row.path?.let { viewModel.reveal(it) }
    }
    val deleteRows: (List<AnalysisRow>) -> Unit = { chosen ->
        viewModel.tools.deleteEntries(chosen.mapNotNull { it.entry })
    }

    LaunchedEffect(Unit) {
        viewModel.tools.analyse { part ->
            when (part) {
                is StorageAnalysisPart.Categories -> categories = part.totals
                is StorageAnalysisPart.Older -> older = part.entries
                is StorageAnalysisPart.Largest -> largest = part.entries
                is StorageAnalysisPart.Apps -> apps = part.apps to part.accurate
                is StorageAnalysisPart.Duplicates -> duplicates = part.report
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fastVerticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            roots.filter { !it.hidden && it.totalBytes != null }.forEach { root ->
                val total = root.totalBytes ?: 0
                val free = root.freeBytes ?: 0
                val used = (total - free).coerceAtLeast(0)
                AnalysisCard(root.title,
                    icon = rootIconFor(root.kind), hue = rootHue(root.kind),
                    headline = formatBytes(free) + " free") {
                    LinearProgressIndicator(
                        progress = { if (total > 0) used.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        drawStopIndicator = {},
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("${formatBytes(free)} free of ${formatBytes(total)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (root.kind == RootKind.INTERNAL) {
                        Spacer(Modifier.height(12.dp))
                        StorageCategoryBar(categories, used, apps?.first)
                    }
                }
            }

            val rescan = {
                scanningDuplicates = true
                viewModel.tools.findDuplicates(dupScope?.second.orEmpty(), dupBudget) { found ->
                    scanningDuplicates = false
                    if (found != null) duplicates = found
                }
            }
            AnalysisCard("Duplicate files",
                icon = Icons.Outlined.ContentCopy, hue = Hue.DOCUMENT,
                headline = duplicates?.let { formatBytes(it.reclaimable) },
                subtitle = duplicates?.let { "${"%,d".format(it.fileCount)} files  ·  recoverable" },
                loading = duplicates == null || scanningDuplicates,
                loadingLabel = "Comparing files") {
                Text("Where to look", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    dupPlaces.forEach { place ->
                        FilterChip(dupScope?.first == place.first,
                            { dupScope = place; rescan() }, label = { Text(place.first) })
                    }
                }
                dupScope?.takeIf { it.first == "Custom" }?.let {
                    Text(it.second.joinToString(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                duplicates?.let { report ->
                    if (report.groups.isEmpty()) {
                        Text("Nothing identical found.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    report.groups.take(6).forEach { group ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(group.paths.first().substringAfterLast('/'),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${group.paths.size} copies  ·  ${formatBytes(group.bytesEach)} each",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(formatBytes(group.reclaimable), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (!report.complete) {
                        Text("It stopped after reading ${formatBytes(dupBudget)}, so there may be more.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (report.groups.isNotEmpty()) {
                            TextButton(onClick = { reviewing = true }) {
                                Text("Review all ${report.groups.size}")
                            }
                        }
                        if (!report.complete) {
                            // A scan cannot resume, so restart it with a larger budget.
                            TextButton(enabled = !scanningDuplicates, onClick = {
                                dupBudget *= 4
                                rescan()
                            }) { Text("Keep looking (read up to ${formatBytes(dupBudget * 4)})") }
                        }
                        TextButton(enabled = !scanningDuplicates, onClick = rescan) { Text("Scan again") }
                    }
                }
            }

            AnalysisCard("Older files",
                icon = Icons.Outlined.Schedule, hue = Hue.ARCHIVE,
                headline = older?.let { formatBytes(it.sumOf { e -> e.size ?: 0L }) },
                subtitle = older?.let { "${it.size} files, least recently touched" },
                loading = older == null, loadingLabel = "Reading") {
                AnalysisFiles(rowsOf(older.orEmpty()), viewModel = viewModel, onReveal = reveal, onDelete = deleteRows)
            }

            AnalysisCard("Largest apps",
                icon = Icons.Outlined.Android, hue = Hue.PACKAGE,
                headline = apps?.let { (list, _) -> formatBytes(list.sumOf { it.totalBytes }) },
                subtitle = apps?.let { (list, _) -> "${list.size} apps" },
                loading = apps == null, loadingLabel = "Measuring",
                action = {
                    ToolIcon(Icons.Outlined.Refresh, "Measure apps again", enabled = apps != null) {
                        apps = null
                        viewModel.tools.measureApps { list, accurate -> apps = list to accurate }
                    }
                }) {
                apps?.let { (list, accurate) ->
                    list.take(if (showAllApps) 20 else 6).forEach { app ->
                        Row(Modifier.fillMaxWidth()
                            .combinedClickable(role = Role.Button, onClick = {
                                scope.launch { openedApp = viewModel.packages.appDetail(app.packageName) }
                            })
                            .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(app.packageName, app.system, 28.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (app.complete)
                                    "app ${formatBytes(app.appBytes)}  ·  data ${formatBytes(app.dataBytes)}"
                                else "APK only",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(formatBytes(app.totalBytes), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (list.size > 6) {
                        TextButton(onClick = { showAllApps = !showAllApps }) {
                            Text(if (showAllApps) "Show six" else "Show top ${minOf(20, list.size)}")
                        }
                    }
                    if (!accurate) {
                        Spacer(Modifier.height(6.dp))
                        Text("Without usage access only the APK can be measured, so app data is missing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { viewModel.tools.openUsageAccessSettings() }) { Text("Grant usage access") }
                    }
                }
            }

            AnalysisCard("Largest files",
                icon = Icons.Outlined.Description, hue = Hue.ANALYSIS,
                headline = largest?.firstOrNull()?.let { formatBytes(it.size) },
                subtitle = largest?.let { "${it.size} files" },
                loading = largest == null, loadingLabel = "Measuring") {
                AnalysisFiles(rowsOf(largest.orEmpty()), viewModel = viewModel, onReveal = reveal, onDelete = deleteRows)
            }

            if (!fullAccess) {
                AnalysisCard("Permission needed for more accurate analysis",
                    icon = Icons.Outlined.Info) {
                    Text("Without all-files access only what Android has indexed can be counted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = actions.requestFullAccess) { Text("GRANT") }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }

    openedApp?.let { app -> AppDetailScreen(app, viewModel) { openedApp = null } }
    if (reviewing) {
        duplicates?.let { report -> DuplicateReview(report, viewModel) { reviewing = false } }
    }
}
