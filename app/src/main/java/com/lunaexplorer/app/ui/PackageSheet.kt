@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.storage.PackageBundle
import com.lunaexplorer.app.storage.PackagePart
import com.lunaexplorer.app.storage.PartKind
import com.lunaexplorer.core.Entry

@Composable
fun PackageSheet(
    entry: Entry,
    viewModel: BrowserViewModel,
    onInstall: () -> Unit,
    onBrowse: () -> Unit,
    onManifest: () -> Unit,
    onProperties: () -> Unit,
    onOpenWith: () -> Unit,
    onDismiss: () -> Unit,
) {
    var packageName by remember(entry.ref) { mutableStateOf<String?>(null) }
    var installed by remember(entry.ref) { mutableStateOf<Pair<String?, Long>?>(null) }

    LaunchedEffect(entry.ref) {
        packageName = viewModel.packages.packageNameOf(entry)
        installed = packageName?.let { viewModel.packages.installedVersionOf(it) }
    }

    BottomSheet(onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Android, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            append(formatBytes(entry.size))
                            packageName?.let { append("  ·  $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            installed?.let { (name, code) ->
                Text("Already installed: ${name ?: code}", Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SheetAction(Icons.Outlined.GetApp, "Install", "Put this package on the device", onClick = onInstall)
            packageName?.let { name ->
                if (installed != null) {
                    SheetAction(Icons.AutoMirrored.Outlined.Launch, "Open the installed app", name) { viewModel.packages.launch(name) }
                }
                SheetAction(Icons.Outlined.Storefront, "View in the Play Store", name) { viewModel.packages.openStore(name) }
            }
            SheetAction(Icons.Outlined.FolderZip, "Look inside", "Browse it as an archive", onClick = onBrowse)
            SheetAction(Icons.Outlined.Description, "View the manifest", "What the package declares", onClick = onManifest)
            SheetAction(Icons.Outlined.Info, "Details", "Version, certificate and everything else", onClick = onProperties)
            SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "Open", "Choose a handler or a type", onClick = onOpenWith)
        }
    }
}

@Composable
fun InstallDialog(
    entry: Entry,
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit,
) {
    var bundle by remember(entry.ref) { mutableStateOf<PackageBundle?>(null) }
    var chosen by remember(entry.ref) { mutableStateOf<Set<String>>(emptySet()) }
    var status by remember(entry.ref) { mutableStateOf<String?>(null) }
    var running by remember(entry.ref) { mutableStateOf(false) }
    var failure by remember(entry.ref) { mutableStateOf<String?>(null) }
    val allowed = remember { viewModel.packages.canInstall() }

    LaunchedEffect(entry.ref) {
        try {
            val read = viewModel.packages.inspect(entry)
            bundle = read
            chosen = read.parts.filter { it.recommended }.map { it.name }.toSet()
        } catch (error: Exception) {
            failure = error.message ?: "This package could not be read"
        }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(if (bundle?.xapk == true) "Install bundle" else "Install") },
        text = {
            Column(Modifier.fastVerticalScroll(rememberScrollState())) {
                if (!allowed) {
                    Text("Android needs permission for Luna to install packages.",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { viewModel.packages.requestInstallPermission() }) { Text("Allow installing") }
                    Spacer(Modifier.height(12.dp))
                }
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when {
                    bundle == null && failure == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Reading the package")
                    }
                    bundle?.xapk == true -> {
                        bundle!!.parts.forEach { part -> PartRow(part, part.name in chosen) { on ->
                            chosen = if (on) chosen + part.name else chosen - part.name
                        } }
                    }
                    else -> Text("${entry.name}  ·  ${formatBytes(entry.size)}",
                        style = MaterialTheme.typography.bodyMedium)
                }
                status?.let {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = allowed && !running && bundle != null && chosen.isNotEmpty(),
                onClick = {
                    running = true
                    viewModel.packages.install(entry, chosen, onStatus = { status = it }) { ok, message ->
                        running = false
                        status = null
                        if (ok) onDismiss() else failure = message
                    }
                },
            ) { Text("Install") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !running) { Text("Cancel") } },
    )
}

@Composable
private fun PartRow(part: PackagePart, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
        .combinedClickable(onClick = { onChange(!checked) }, role = Role.Checkbox, enabled = part.kind != PartKind.BASE),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = part.kind != PartKind.BASE)
        Column(Modifier.weight(1f)) {
            Text(part.name.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${part.detail}  ·  ${formatBytes(part.size)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
