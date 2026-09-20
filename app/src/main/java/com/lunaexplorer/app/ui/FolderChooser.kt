package com.lunaexplorer.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lunaexplorer.core.NodeRef
import kotlinx.coroutines.CancellationException

@Composable
internal fun FolderChooser(viewModel: BrowserViewModel, showHidden: Boolean, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    // The way down from a storage; empty at the list of storages.
    var trail by remember { mutableStateOf(emptyList<Pair<NodeRef, String>>()) }
    var rows by remember { mutableStateOf<List<Pair<NodeRef, String>>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf<String?>(null) }
    val here = trail.lastOrNull()?.first

    LaunchedEffect(here) {
        rows = null; failure = null; path = null
        try {
            if (here == null) rows = viewModel.files.pathRoots().map { it.ref to it.title }
            else {
                path = viewModel.files.shownPathOf(here)
                rows = viewModel.files.foldersIn(here, showHidden).map { it.ref to it.name }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            rows = emptyList(); failure = error.message ?: "This folder could not be read"
        }
    }

    val up = { if (trail.isEmpty()) onDismiss() else trail = trail.dropLast(1) }
    Dialog(onDismissRequest = up, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolIcon(Icons.AutoMirrored.Outlined.ArrowBack, if (trail.isEmpty()) "Back" else "Up", onClick = up)
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        Text(trail.lastOrNull()?.second ?: "Choose a folder", style = MaterialTheme.typography.titleLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        path?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.StartEllipsis)
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(rows.orEmpty(), key = { it.first.provider + it.first.key }) { (ref, name) ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .clickable(role = Role.Button) { trail = trail + (ref to name) }
                            .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (here == null) Icons.Outlined.Storage else Icons.Outlined.Folder, null,
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    item {
                        val note = failure ?: when {
                            rows == null -> "Reading"
                            rows.orEmpty().isEmpty() -> "No folders in here"
                            else -> null
                        }
                        note?.let {
                            Text(it, Modifier.padding(horizontal = 20.dp, vertical = 16.dp), style = MaterialTheme.typography.bodyMedium,
                                color = if (failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    FilledTonalButton(enabled = path != null, onClick = { path?.let(onChoose) }) { Text("Choose this folder") }
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}
