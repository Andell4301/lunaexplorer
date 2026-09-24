@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.storage.b2.B2Account
import com.lunaexplorer.app.storage.smb.SmbAccount
import com.lunaexplorer.app.storage.transfer.TransferAccount

@Composable
internal fun SettingsScreen(
    state: BrowserState,
    viewModel: BrowserViewModel,
    actions: LunaActions,
    onRecycleBin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val preferences = state.preferences
    val onChange = viewModel::setPreferences
    var mediaCategories by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf(false) }
    var defaults by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    var network by remember { mutableStateOf<NetworkKind?>(null) }
    var editing by remember { mutableStateOf<Pair<SmbAccount, Boolean>?>(null) }
    var editingB2 by remember { mutableStateOf<Pair<B2Account, Boolean>?>(null) }
    var editingTransfer by remember { mutableStateOf<Pair<TransferAccount, Boolean>?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var viewingLog by remember { mutableStateOf(false) }
    val procedureBack = remember { ProcedureSettingsBack() }
    val recordingLog by viewModel.debugLog.recording.collectAsState()
    val leave: () -> Unit = {
        when {
            page == SettingsPage.PROCEDURES && procedureBack.action != null -> procedureBack.action?.invoke()
            mediaCategories -> mediaCategories = false
            network != null -> network = null
            page != null -> page = null
            else -> onDismiss()
        }
    }
    val scroll = rememberScrollState()
    val categoriesScroll = rememberScrollState()
    LaunchedEffect(mediaCategories) { if (mediaCategories) categoriesScroll.scrollTo(0) }
    val networkScroll = rememberScrollState()
    val networkMenuScroll = rememberScrollState()
    LaunchedEffect(network) { if (network != null) networkScroll.scrollTo(0) }
    // This window covers the one the browser's own message bar is drawn in.
    val messages = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            messages.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long)
            viewModel.dismissMessage()
        }
    }
    Dialog(onDismissRequest = leave, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = leave)
                    Text(if (mediaCategories) "Media categories" else network?.title ?: page?.title ?: "Settings", Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.weight(1f).fastVerticalScroll(when {
                    mediaCategories -> categoriesScroll
                    network != null -> networkScroll
                    page == SettingsPage.NETWORK -> networkMenuScroll
                    else -> scroll
                }).padding(horizontal = 20.dp)) {
                    when (page) {
                        null -> {
                            Spacer(Modifier.height(6.dp))
                            SettingsPage.entries.forEach { entry ->
                                SettingsLink(entry.title, summaryOf(entry, state, recordingLog)) { page = entry }
                            }
                            Spacer(Modifier.height(32.dp))
                        }

                        SettingsPage.ACCESS -> StorageAccessSettings(state, viewModel, actions)
                        SettingsPage.START -> StartSettings(preferences, state.directoryPath, onChange)
                        SettingsPage.APPEARANCE -> AppearanceSettings(preferences, onChange)
                        // A page of its own, not an AlertDialog: a text field in a dialog nested in this
                        // Dialog never goes idle.
                        SettingsPage.FILES -> if (mediaCategories) {
                            SwitchRow("Ignore dot files", preferences.ignoreDotFiles,
                                "Leave names beginning with a dot out of the category views") {
                                onChange(preferences.copy(ignoreDotFiles = it))
                            }
                            SwitchRow("Include .nomedia folders", preferences.includeNomedia) {
                                onChange(preferences.copy(includeNomedia = it))
                            }
                            CategoryExtras(preferences, onChange)
                            Spacer(Modifier.height(32.dp))
                        } else FileSettings(
                            state, onChange,
                            onMediaCategories = { mediaCategories = true },
                            onOpenDefaults = { defaults = true },
                            onFolderViews = { folders = true },
                        )
                        SettingsPage.OPEN_WITH -> OpenWithLunaSettings(preferences, onChange)
                        SettingsPage.DOCUMENTS -> DocumentProviderSettings(preferences, viewModel, onChange)
                        SettingsPage.NETWORK -> when (network) {
                            null -> NetworkSettings(state, viewModel, actions, onKind = { network = it },
                                onDiscardVault = { confirmDiscard = true })
                            NetworkKind.SMB -> SmbSettings(state, onChange) { account, isNew -> editing = account to isNew }
                            NetworkKind.B2 -> B2Settings(state, viewModel) { account, isNew -> editingB2 = account to isNew }
                            NetworkKind.FTP, NetworkKind.SFTP -> TransferSettings(requireNotNull(network), state, onChange) {
                                account, isNew -> editingTransfer = account to isNew
                            }
                        }
                        SettingsPage.PLAYER -> PlayerSettings(preferences, onChange)
                        SettingsPage.AUDIO -> AudioPlayerSettings(preferences, onChange)
                        SettingsPage.DELETING -> DeletingSettings(state, viewModel, onRecycleBin)
                        SettingsPage.PROCEDURES -> ProcedureSettings(state, viewModel.procedures, actions) { procedureBack.action = it }
                        SettingsPage.TRANSFER -> SettingsTransferPage(state, viewModel, actions)
                        SettingsPage.LOGS -> DebugLogSettings(recordingLog, viewModel) { viewingLog = true }
                        SettingsPage.ABOUT -> AboutSettings { confirmReset = true }
                    }
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))

                if (folders) {
                    AlertDialog(
                        onDismissRequest = { folders = false },
                        title = { Text("Per-folder display settings") },
                        text = {
                            Column(Modifier.fastVerticalScroll(rememberScrollState())) {
                                FolderViews(state, viewModel)
                            }
                        },
                        confirmButton = { TextButton(onClick = { folders = false }) { Text("Done") } },
                    )
                }
                if (defaults) {
                    AlertDialog(
                        onDismissRequest = { defaults = false },
                        title = { Text("Default apps") },
                        text = {
                            Column(Modifier.fastVerticalScroll(rememberScrollState())) { OpenDefaultsSetting(viewModel) }
                        },
                        confirmButton = { TextButton(onClick = { defaults = false }) { Text("Done") } },
                    )
                }
                editing?.let { (account, isNew) ->
                    SmbAccountEditor(account, isNew, viewModel, actions) { editing = null }
                }
                editingB2?.let { (account, isNew) ->
                    B2AccountEditor(account, isNew, viewModel, actions) { editingB2 = null }
                }
                editingTransfer?.let { (account, isNew) ->
                    TransferAccountEditor(account, isNew, viewModel, actions) { editingTransfer = null }
                }
                if (viewingLog) DebugLogScreen(viewModel, state.directoryPath) { viewingLog = false }
                if (confirmDiscard) {
                    AlertDialog(
                        onDismissRequest = { confirmDiscard = false },
                        title = { Text("Discard the vault?") },
                        text = { Text("Every kept password and key is thrown away. The accounts stay set up and each " +
                            "asks for its own again the next time it is edited.") },
                        confirmButton = {
                            TextButton(onClick = { confirmDiscard = false; viewModel.vault.discard() }) { Text("Discard") }
                        },
                        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } },
                    )
                }
                if (confirmReset) {
                    AlertDialog(
                        onDismissRequest = { confirmReset = false },
                        title = { Text("Reset all settings?") },
                        text = {
                            Text("Every setting here, every folder's own view and sort, and the bookmarks " +
                                "these switches own go back to how they arrive. Your files, your own " +
                                "bookmarks and the access you have granted are untouched.")
                        },
                        confirmButton = {
                            TextButton(onClick = { confirmReset = false; viewModel.resetSettings() }) { Text("Reset") }
                        },
                        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
                    )
                }
            }
            SnackbarHost(messages, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            }
        }
    }
}

private enum class SettingsPage(val title: String) {
    ACCESS("Storage access"),
    START("Where Luna opens"),
    APPEARANCE("Appearance"),
    FILES("Files and folders"),
    OPEN_WITH("Open with Luna"),
    DOCUMENTS("Document provider"),
    NETWORK("Network"),
    PLAYER("Video player"),
    AUDIO("Audio player"),
    DELETING("Deleting"),
    PROCEDURES("Stored procedures"),
    TRANSFER("Export and import"),
    LOGS("Debug log"),
    ABOUT("Reset and about"),
}

private fun summaryOf(page: SettingsPage, state: BrowserState, recordingLog: Boolean): String = when (page) {
    SettingsPage.ACCESS -> if (state.fullAccess) "Luna can reach all shared storage"
    else "Locations you add one at a time"
    SettingsPage.START -> if (state.preferences.startPath.isNotBlank()) "A folder of your choosing"
    else startLabel(state.preferences.startScreen)
    SettingsPage.APPEARANCE -> "Theme, colour and how big everything is"
    SettingsPage.FILES -> "Layout and sorting, what opens what, and folders that keep their own"
    SettingsPage.OPEN_WITH -> when (val kinds = state.preferences.openWithLuna.size) {
        0 -> "Off"
        1 -> "1 kind"
        else -> "$kinds kinds"
    }
    SettingsPage.DOCUMENTS -> when {
        !state.preferences.documentsProvider -> "Off"
        state.preferences.servedFolders.size == 1 -> "One folder"
        else -> "${state.preferences.servedFolders.size} folders"
    }
    SettingsPage.NETWORK -> NetworkKind.entries.joinToString("  ·  ") { "${it.title}: ${it.summary(state).lowercase()}" }
    SettingsPage.PLAYER -> "Gestures and subtitle appearance"
    SettingsPage.AUDIO -> if (state.preferences.audioBackground) "Background play" else "Off"
    SettingsPage.DELETING -> if (state.recycleBin) "Deleting moves to the recycle bin"
    else "Deleting removes for good"
    SettingsPage.PROCEDURES -> ""
    SettingsPage.TRANSFER -> "Settings to a file, and back"
    SettingsPage.LOGS -> if (recordingLog) "Recording" else "Off"
    SettingsPage.ABOUT -> "Put everything back, and what this build is"
}

private class ProcedureSettingsBack { var action: (() -> Unit)? = null }
