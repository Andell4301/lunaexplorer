package com.lunaexplorer.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.Overlay
import com.lunaexplorer.app.storage.transfer.TransferAccount
import com.lunaexplorer.app.storage.transfer.TransferCredentials
import com.lunaexplorer.app.storage.transfer.TransferProtocol
import com.lunaexplorer.app.terminal.LocalShellConnection
import com.lunaexplorer.app.terminal.SshTerminalConnector
import com.lunaexplorer.app.terminal.shellInputQuote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
internal fun TerminalWorkspace(state: BrowserState, viewModel: BrowserViewModel, actions: LunaActions) {
    val scope = rememberCoroutineScope()
    val workspace: TerminalWorkspaceModel = viewModel()
    val android = LocalContext.current.applicationContext
    val request = state.overlay as? Overlay.Terminal
    val active = workspace.active
    var context by remember { mutableStateOf<TerminalContext?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var resolution by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<TransferAccount?>(null) }
    var disconnect by remember { mutableStateOf(false) }
    var replaceSession by remember { mutableStateOf(false) }
    val liveRequest by rememberUpdatedState(request)
    SideEffect {
        active?.surface?.setDisableSuggestions(state.preferences.terminalDisableSuggestions)
        active?.surface?.setTapOpensKeyboard(state.preferences.terminalTapOpensKeyboard)
    }

    fun start(account: TransferAccount?, here: TerminalContext? = null) {
        if (active != null) return
        failure = null
        val surface = TerminalSurfaceController().apply {
            setDisableSuggestions(state.preferences.terminalDisableSuggestions)
            setTapOpensKeyboard(state.preferences.terminalTapOpensKeyboard)
        }
        val credentials = account?.let { viewModel.vault.secrets.value?.transferCredentials?.get(it.id) }
            ?: TransferCredentials()
        try {
            workspace.start(surface, account?.name?.ifEmpty { account.host } ?: "Local shell", account?.id) { columns, rows ->
                if (account == null) LocalShellConnection.open(android, here?.directory, columns, rows)
                else SshTerminalConnector().connect(account, credentials, columns, rows, here?.directory)
            }
        } catch (cancelled: CancellationException) {
            surface.close()
            throw cancelled
        } catch (error: IllegalStateException) {
            surface.close()
            failure = error.message ?: "Could not open terminal"
            return
        }
        actions.requestNotifications()
    }

    fun connect(account: TransferAccount?, here: TerminalContext? = null) {
        val requested = liveRequest
        val generation = resolution
        if (account == null) start(null, here)
        else withVault(viewModel, actions, { if (resolution == generation) failure = it }) {
            if (liveRequest == requested && requested != null && resolution == generation) {
                if (viewModel.vault.open()) start(account, here) else failure = "The vault stayed shut"
            }
        }
    }

    LaunchedEffect(request) {
        val generation = ++resolution
        failure = null
        context = null
        resolving = false
        val launch = request?.takeIf { it.directory != null } ?: return@LaunchedEffect
        fun resolve() {
            scope.launch {
                resolving = true
                try {
                    val resolved = viewModel.terminalContext(launch)
                    if (liveRequest != launch || resolution != generation) return@launch
                    context = resolved
                    if (active == null && resolved != null) {
                        val account = resolved.accountId?.let { id -> state.transferAccounts.firstOrNull { it.id == id } }
                        if (resolved.accountId != null && account == null) error("That server is no longer set up")
                        connect(account, resolved)
                    }
                } catch (error: Exception) {
                    ensureActive()
                    if (liveRequest == launch && resolution == generation) failure = error.message ?: "Could not open terminal"
                } finally { if (liveRequest == launch && resolution == generation) resolving = false }
            }
        }
        if (viewModel.terminalNeedsCredentials(requireNotNull(launch.directory))) {
            withVault(viewModel, actions, { if (resolution == generation) failure = it }) {
                if (liveRequest == launch && resolution == generation) {
                    if (viewModel.vault.open()) resolve() else failure = "The vault stayed shut"
                }
            }
        } else resolve()
    }

    if (request == null) return
    val shown = active
    val files = {
        viewModel.dismissOverlay()
        if (shown != null) viewModel.refresh()
    }
    Dialog(onDismissRequest = files, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Files", onClick = files)
                    Text(shown?.session?.title ?: "Terminal", Modifier.weight(1f).padding(start = 8.dp),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (shown != null) {
                        val running by shown.session.running.collectAsState()
                        TextButton(onClick = shown.surface::showKeyboard) { Text("Keyboard") }
                        ToolIcon(Icons.Outlined.Close, if (running) "Disconnect" else "Close terminal") {
                            if (running) disconnect = true else workspace.close(shown)
                        }
                    }
                }
                if (resolving) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
                failure?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
                if (shown == null) {
                    Column(Modifier.weight(1f).fastVerticalScroll(rememberScrollState()).padding(20.dp)) {
                        SettingsLink("Local shell", "") { connect(null) }
                        state.transferAccounts.filter { it.protocol == TransferProtocol.SFTP }.forEach { account ->
                            SettingsLink(account.name.ifEmpty { account.host }, "${account.username}@${account.host}:${account.port}") {
                                connect(account)
                            }
                        }
                        OutlinedButton(onClick = { editing = TransferAccount() }) { Text("Add an SSH server") }
                    }
                } else {
                    val status by shown.session.status.collectAsState()
                    val running by shown.session.running.collectAsState()
                    Text(status, Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("terminalStatus"),
                        style = MaterialTheme.typography.labelSmall)
                    TerminalSurface(shown.surface, onInput = shown.session::input, onResize = shown.session::resize,
                        modifier = Modifier.weight(1f).fillMaxWidth())
                    TerminalKeys(shown.surface, running)
                    val clipboard = android.getSystemService(ClipboardManager::class.java)
                    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        item { TextButton(onClick = {
                            scope.launch {
                                try {
                                    val text = shown.surface.selection()
                                    if (text.isNotEmpty()) clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))
                                } catch (error: Exception) { ensureActive(); failure = "Could not copy selection" }
                            }
                        }) { Text("Copy") } }
                        item { TextButton(enabled = running, onClick = {
                            clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(android)
                                ?.let { shown.surface.paste(it.toString()) }
                        }) { Text("Paste") } }
                        item {
                            FilterChip(selected = state.preferences.terminalDisableSuggestions, onClick = {
                                viewModel.setPreferences(state.preferences.copy(
                                    terminalDisableSuggestions = !state.preferences.terminalDisableSuggestions))
                            }, label = { Text("No suggestions") })
                        }
                        item {
                            FilterChip(selected = state.preferences.terminalTapOpensKeyboard, onClick = {
                                viewModel.setPreferences(state.preferences.copy(
                                    terminalTapOpensKeyboard = !state.preferences.terminalTapOpensKeyboard))
                            }, label = { Text("Tap for keyboard") })
                        }
                        val available = context?.takeIf { it.accountId == shown.session.accountId }
                        if (context != null && available == null) item {
                            TextButton(onClick = { replaceSession = true; disconnect = true }) { Text("New session here") }
                        }
                        if (available != null) {
                            item { TextButton(enabled = running, onClick = {
                                shown.surface.paste("cd -- ${shellInputQuote(available.directory)}")
                            }) { Text("Insert cd") } }
                            if (available.paths.isNotEmpty()) item {
                                TextButton(enabled = running, onClick = {
                                    shown.surface.paste(available.paths.joinToString(" ", transform = ::shellInputQuote))
                                }) { Text("Insert paths") }
                            }
                        }
                        item { TextButton(onClick = files) { Text("Files") } }
                    }
                }
            }
        }
    }
    editing?.let { account ->
        TransferAccountEditor(account, true, viewModel, actions, terminal = true) { editing = null }
    }
    if (disconnect && shown != null) {
        AlertDialog(onDismissRequest = { disconnect = false; replaceSession = false }, title = { Text("Disconnect terminal?") },
            confirmButton = { TextButton(onClick = {
                disconnect = false
                workspace.close(shown)
                if (replaceSession) {
                    replaceSession = false
                    context?.let { here ->
                        val account = here.accountId?.let { id -> state.transferAccounts.firstOrNull { it.id == id } }
                        if (here.accountId == null || account != null) connect(account, here)
                        else failure = "That server is no longer set up"
                    }
                }
            }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { disconnect = false; replaceSession = false }) { Text("Cancel") } })
    }
}

@Composable
private fun TerminalKeys(surface: TerminalSurfaceController, enabled: Boolean) {
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 4.dp)) {
        item {
            FilterChip(surface.controlPressed, onClick = surface::toggleControl, enabled = enabled, label = { Text("Ctrl") })
        }
        val keys = listOf(
            "Esc" to TerminalKey.ESCAPE, "Tab" to TerminalKey.TAB,
            "↑" to TerminalKey.UP, "↓" to TerminalKey.DOWN, "←" to TerminalKey.LEFT, "→" to TerminalKey.RIGHT,
            "Home" to TerminalKey.HOME, "End" to TerminalKey.END,
            "PgUp" to TerminalKey.PAGE_UP, "PgDn" to TerminalKey.PAGE_DOWN,
            "Del" to TerminalKey.DELETE, "Enter" to TerminalKey.ENTER,
        ) + (1..12).map { "F$it" to TerminalKey.valueOf("F$it") }
        keys.forEach { (label, key) ->
            item { TextButton(enabled = enabled, onClick = { surface.sendKey(key) },
                contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.widthIn(min = 44.dp)) { Text(label) } }
        }
    }
}
