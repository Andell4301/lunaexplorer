@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lunaexplorer.app.storage.AppFilter
import com.lunaexplorer.app.storage.InstalledApp
import com.lunaexplorer.core.ApkFacts
import com.lunaexplorer.core.ApkReport
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppsScreen(viewModel: BrowserViewModel) {
    var filter by remember { mutableStateOf(AppFilter.USER) }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var chosen by remember { mutableStateOf<InstalledApp?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(filter) {
        apps = emptyList()
        viewModel.packages.listApps(filter).collect { apps = it }
    }
    val shown = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppFilter.entries.forEach { option ->
                FilterChip(selected = filter == option, onClick = { filter = option },
                    label = { Text(option.label) })
            }
        }
        OutlinedTextField(query, { query = it }, singleLine = true,
            label = { Text("Filter by name or package") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        HorizontalDivider()
        FastLazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.packageName }) { app ->
                Row(Modifier.fillMaxWidth()
                    .combinedClickable(onClick = { chosen = app }, role = Role.Button)
                    .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app.packageName, app.system, 32.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text("${app.packageName}  ·  ${app.versionName ?: app.versionCode}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(formatBytes(app.apkBytes), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(Modifier.padding(start = 58.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f))
            }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }

    chosen?.let { app -> AppDetailScreen(app, viewModel) { chosen = null } }
}

@Composable
internal fun AppDetailScreen(app: InstalledApp, viewModel: BrowserViewModel, onDismiss: () -> Unit) {
    val copy = rememberCopyText()
    var extracting by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf<String?>(null) }
    var manifest by remember(app.packageName) { mutableStateOf(false) }

    destination?.let { start ->
        ExtractDestinationDialog(
            initial = start,
            label = if (app.hasSplits) "Extract as XAPK" else "Extract APK",
            onDismiss = { destination = null },
        ) { into ->
            destination = null
            extracting = true
            viewModel.packages.extract(app, into) { extracting = false }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                ToolBar(app.label, onDismiss)
                Column(Modifier.weight(1f).fastVerticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 12.dp)) {
                        AppIcon(app.packageName, app.system, 56.dp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(app.label, style = MaterialTheme.typography.titleLarge,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${app.versionName ?: ""} (${app.versionCode})".trim(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        viewModel.packages.launchable(app.packageName)?.let {
                            FilledTonalButton(onClick = { viewModel.packages.launch(app.packageName) }) {
                                Icon(Icons.AutoMirrored.Outlined.Launch, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Open")
                            }
                        }
                        OutlinedButton(onClick = { destination = viewModel.currentFolderPath() },
                            enabled = !extracting) {
                            Text(if (app.hasSplits) "Extract as XAPK" else "Extract APK")
                        }
                        OutlinedButton(onClick = { viewModel.packages.openSettings(app.packageName) }) { Text("App info") }
                        OutlinedButton(onClick = { manifest = true }, enabled = app.sourceDir != null) { Text("Manifest") }
                        OutlinedButton(onClick = {
                            viewModel.exploreAppFiles(app)
                            onDismiss()
                        }, enabled = app.sourceDir != null) { Text("Explore app files") }
                        OutlinedButton(onClick = { viewModel.packages.openStore(app.packageName) }) { Text("Store") }
                        if (!app.system) {
                            OutlinedButton(onClick = { viewModel.packages.uninstall(app.packageName) }) { Text("Uninstall") }
                        }
                    }
                    if (extracting) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }

                    ToolGroup("Package")
                    ToolFact("Package", app.packageName) { copy(app.packageName) }
                    ToolFact("Version", "${app.versionName ?: "?"}  (${app.versionCode})")
                    ToolFact("Kind", buildString {
                        append(if (app.system) "System" else "Installed")
                        if (!app.enabled) append("  ·  disabled")
                        if (app.debuggable) append("  ·  debuggable")
                    })
                    ToolFact("Built for", "API ${app.minSdk} to ${app.targetSdk}")
                    ToolFact("Installer", app.installer ?: "Not recorded")
                    ToolFact("Installed", DateFormat.getDateTimeInstance().format(Date(app.installed)))
                    ToolFact("Updated", DateFormat.getDateTimeInstance().format(Date(app.updated)))

                    ToolGroup("Identity")
                    ToolFact("UID", app.uid.toString())
                    app.sharedUserId?.let { ToolFact("Shared user", it) }
                    app.certificates.forEachIndexed { index, fingerprint ->
                        ToolFact(if (app.certificates.size == 1) "Signing certificate (SHA-256)" else "Certificate ${index + 1} (SHA-256)",
                            fingerprint, monospace = true) { copy(fingerprint) }
                    }

                    ToolGroup("On disk")
                    ToolFact("Total apk size", formatBytes(app.apkBytes))
                    app.sourceDir?.let { ToolFact("Base apk", it, monospace = true) { copy(it) } }
                    if (app.splitDirs.isNotEmpty()) {
                        ToolFact("Splits", "${app.splitDirs.size}")
                        app.splitDirs.forEach { ToolFact("", it, monospace = true) }
                    }
                    app.dataDir?.let { ToolFact("Data directory", it, monospace = true) }

                    var report by remember(app.packageName) { mutableStateOf<ApkReport?>(null) }
                    LaunchedEffect(app.packageName) {
                        report = app.sourceDir?.let { path ->
                            withContext(Dispatchers.IO) { runCatching { ApkFacts.read(File(path)) }.getOrNull() }
                        }
                    }
                    // The installed package knows which permissions are granted, so the report's
                    // own permission sections are dropped.
                    val (aboutPackage, insideApk) = report?.sections.orEmpty()
                        .filterNot { it.name.startsWith("Permissions") }
                        .partition { it.name == "Package" }
                    aboutPackage.forEach { section ->
                        ToolGroup(section.name)
                        section.facts.forEach { (label, value) -> ToolFact(label, value) }
                    }

                    ToolGroup("Permissions (${app.grantedPermissions.size} of ${app.permissions.size} granted)")
                    app.permissions.sorted().forEach { permission ->
                        val granted = permission in app.grantedPermissions
                        Text(
                            "${if (granted) "✓" else "·"}  ${permission.substringAfterLast('.')}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (granted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (app.permissions.isNotEmpty()) Spacer(Modifier.height(10.dp))

                    insideApk.forEach { section ->
                        ToolGroup(section.name)
                        section.facts.forEach { (label, value) -> ToolFact(label, value) }
                    }
                    report?.anomalies?.takeIf { it.isNotEmpty() }?.let { problems ->
                        ToolGroup("Structure")
                        problems.forEach { Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error) }
                    }
                    Spacer(Modifier.height(40.dp))
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
    if (manifest) ManifestScreen(app, viewModel) { manifest = false }
}

@Composable
private fun ToolGroup(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ToolFact(label: String, value: String, monospace: Boolean = false, onCopy: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).then(
            if (onCopy == null) Modifier
            else Modifier.combinedClickable(onClick = onCopy, role = Role.Button),
        ),
    ) {
        if (label.isNotEmpty()) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif)
    }
}

@Composable
internal fun AppIcon(packageName: String, system: Boolean, size: Dp) {
    val context = LocalContext.current
    var image by remember(packageName) { mutableStateOf(cachedAppIcon(packageName)) }
    LaunchedEffect(packageName) {
        if (image == null) image = withContext(Dispatchers.IO) { appIcon(context, packageName) }
    }
    val current = image
    if (current != null) {
        Image(current, null, Modifier.size(size))
    } else {
        Icon(Icons.Outlined.Android, null, Modifier.size(size),
            tint = if (system) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ExtractDestinationDialog(
    initial: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var path by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column {
                OutlinedTextField(path, { path = it }, singleLine = true,
                    label = { Text("Destination folder") },
                    placeholder = { Text("/storage/emulated/0/Download") },
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "/storage/emulated/0/Download",
                        "/storage/emulated/0",
                        "/storage/emulated/0/Documents",
                    ).forEach { option ->
                        SuggestionChip(onClick = { path = option },
                            label = { Text(option.substringAfterLast('/').ifBlank { "Internal" }) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(path) }, enabled = path.isNotBlank()) { Text("Extract") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
