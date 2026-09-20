package com.lunaexplorer.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.lunaexplorer.app.storage.InstalledApp
import com.lunaexplorer.core.Entry

@Composable
fun ManifestScreen(entry: Entry, viewModel: BrowserViewModel, onDismiss: () -> Unit) {
    val session = remember(viewModel, entry.ref) { viewModel.manifestSession(entry) }
    TextEditorScreen(session, code = true, onDismiss,
        onScheme = { viewModel.setPreferences(viewModel.state.value.preferences.copy(syntaxScheme = it)) })
}

@Composable
internal fun ManifestScreen(app: InstalledApp, viewModel: BrowserViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Not the ViewModel's editor session: this viewer is not an overlay, and any overlay shown under it would release that one.
    val session = remember(app.packageName) {
        TextEditorSession.forText(ManifestOf(app.packageName), "${app.label} · manifest", scope, "This manifest could not be read") {
            viewModel.packages.manifestOf(app)
        }
    }
    DisposableEffect(session) { onDispose { session.close() } }
    TextEditorScreen(session, code = true, onDismiss,
        onScheme = { viewModel.setPreferences(viewModel.state.value.preferences.copy(syntaxScheme = it)) })
}
