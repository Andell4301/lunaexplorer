package com.lunaexplorer.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lunaexplorer.app.storage.transfer.FtpSecurity
import com.lunaexplorer.app.storage.transfer.HostKeyRequired
import com.lunaexplorer.app.storage.transfer.TransferAccount
import com.lunaexplorer.app.storage.transfer.TransferAuthentication
import com.lunaexplorer.app.storage.transfer.TransferCredentials
import com.lunaexplorer.app.storage.transfer.TransferProtocol
import com.lunaexplorer.app.storage.transfer.validationError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

@Composable
internal fun TransferAccountEditor(
    account: TransferAccount,
    isNew: Boolean,
    viewModel: BrowserViewModel,
    actions: LunaActions,
    onDismiss: () -> Unit,
) {
    val sftp = account.protocol == TransferProtocol.SFTP
    var name by remember { mutableStateOf(account.name) }
    var host by remember { mutableStateOf(account.host) }
    var port by remember { mutableStateOf(account.port.toString()) }
    var rootPath by remember { mutableStateOf(account.rootPath) }
    var username by remember { mutableStateOf(account.username) }
    var anonymous by remember { mutableStateOf(account.anonymous) }
    var security by remember { mutableStateOf(account.security) }
    var authentication by remember { mutableStateOf(account.authentication) }
    var timeout by remember { mutableStateOf(account.timeoutSeconds.toString()) }
    var password by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var passwordEdited by remember { mutableStateOf(false) }
    var passphraseEdited by remember { mutableStateOf(false) }
    var importedKey by remember { mutableStateOf<String?>(null) }
    var fingerprint by remember { mutableStateOf(account.hostKeyFingerprint) }
    var offeredKey by remember { mutableStateOf<HostKeyRequired?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val busy = testing || importing
    val credentialsChanged = passwordEdited || passphraseEdited || importedKey != null
    LaunchedEffect(result, offeredKey) {
        if (result != null || offeredKey != null) scroll.animateScrollTo(scroll.maxValue)
    }

    fun built(): TransferAccount? {
        val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val seconds = timeout.toIntOrNull()?.takeIf { it in 1..600 } ?: return null
        if (host.isEmpty() || rootPath.isEmpty()) return null
        if ((!anonymous || sftp) && username.isEmpty()) return null
        if (sftp && authentication == TransferAuthentication.PRIVATE_KEY && importedKey == null &&
            (isNew || account.authentication != TransferAuthentication.PRIVATE_KEY)) return null
        return account.copy(name = name, host = host, port = portNumber,
            rootPath = rootPath, username = username, anonymous = anonymous && !sftp,
            security = security, authentication = authentication, timeoutSeconds = seconds,
            hostKeyFingerprint = fingerprint).takeIf { it.validationError() == null }
    }

    fun credentials(): TransferCredentials? {
        if (!isNew && !credentialsChanged) return null
        val stored = viewModel.vault.secrets.value?.transferCredentials?.get(account.id) ?: TransferCredentials()
        return stored.copy(
            password = if (passwordEdited || isNew) password else stored.password,
            privateKey = importedKey ?: stored.privateKey,
            passphrase = if (passphraseEdited || importedKey != null || isNew) passphrase else stored.passphrase,
        )
    }

    fun test(candidate: TransferAccount) {
        val run = {
            testing = true; result = null; offeredKey = null; connected = false
            scope.launch {
                try {
                    viewModel.servers.test(candidate, credentials()).fold(
                        onSuccess = { connected = true; result = "Connected" },
                        onFailure = {
                            if (it is HostKeyRequired) {
                                offeredKey = it
                                result = if (it.changed) "Host key changed" else "Unknown host key"
                            } else result = it.message ?: "Could not connect"
                        },
                    )
                } finally { testing = false }
            }
            Unit
        }
        if (isNew || (anonymous && !sftp)) run()
        else withVault(viewModel, actions, { result = it }, run)
    }

    fun hostChanged() {
        fingerprint = ""; offeredKey = null; result = null; connected = false
    }

    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true; result = null; connected = false
            scope.launch {
                try {
                    importedKey = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use(::readTransferPrivateKey)
                            ?: error("Could not read private key")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    ensureActive()
                    result = "Could not read private key"
                } finally { importing = false }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onDismiss)
                    Text(if (isNew) "Add an ${if (sftp) "SFTP" else "FTP"} server" else account.name.ifEmpty { account.host },
                        Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.weight(1f).fastVerticalScroll(scroll).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, enabled = !busy, singleLine = true,
                        label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(host, { host = it; hostChanged() }, enabled = !busy, singleLine = true,
                            label = { Text("Server") }, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                        OutlinedTextField(port, { port = it; hostChanged() }, enabled = !busy, singleLine = true,
                            label = { Text("Port") }, modifier = Modifier.width(96.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    OutlinedTextField(rootPath, { rootPath = it }, enabled = !busy, singleLine = true,
                        label = { Text("Root folder") }, modifier = Modifier.fillMaxWidth())
                    if (!sftp) {
                        Text("Security", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FtpSecurity.entries.forEach { choice ->
                                FilterChip(security == choice, enabled = !busy, onClick = {
                                    val previousDefault = if (security == FtpSecurity.IMPLICIT_TLS) 990 else 21
                                    if (port == previousDefault.toString()) port = if (choice == FtpSecurity.IMPLICIT_TLS) "990" else "21"
                                    security = choice
                                }, label = { Text(when (choice) {
                                    FtpSecurity.PLAIN -> "FTP"
                                    FtpSecurity.EXPLICIT_TLS -> "Explicit TLS"
                                    FtpSecurity.IMPLICIT_TLS -> "Implicit TLS"
                                }) })
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Anonymous")
                            Switch(anonymous, { anonymous = it }, enabled = !busy)
                        }
                    }
                    if (sftp || !anonymous) {
                        OutlinedTextField(username, { username = it }, enabled = !busy, singleLine = true,
                            label = { Text("User") }, modifier = Modifier.fillMaxWidth())
                        if (sftp) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TransferAuthentication.entries.forEach { choice ->
                                    FilterChip(authentication == choice, enabled = !busy,
                                        onClick = { authentication = choice },
                                        label = { Text(if (choice == TransferAuthentication.PASSWORD) "Password" else "SSH key") })
                                }
                            }
                        }
                        if (!sftp || authentication == TransferAuthentication.PASSWORD) {
                            TransferSecretField(password, { password = it; passwordEdited = true }, "Password", !busy, !isNew)
                        } else {
                            OutlinedButton(enabled = !busy, onClick = { keyPicker.launch(arrayOf("*/*")) }) {
                                Text(if (importing) "Loading…" else if (importedKey != null) "Replace private key" else "Import private key")
                            }
                            if (importedKey != null) Text("Private key loaded", style = MaterialTheme.typography.bodySmall)
                            TransferSecretField(passphrase, { passphrase = it; passphraseEdited = true }, "Passphrase", !busy, !isNew && importedKey == null)
                        }
                    }
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Fewer options" else "More options") }
                    if (advanced) {
                        OutlinedTextField(timeout, { timeout = it }, enabled = !busy, singleLine = true,
                            label = { Text("Timeout, seconds") }, modifier = Modifier.width(180.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        if (sftp && fingerprint.isNotEmpty()) {
                            Text("Host key", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(fingerprint, Modifier.testTag("hostKeyFingerprint"), style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = !busy, onClick = { fingerprint = ""; result = null; connected = false }) {
                                Text("Forget host key")
                            }
                        }
                    }
                    result?.let {
                        Text(it, Modifier.testTag("editorResult"), style = MaterialTheme.typography.bodyMedium,
                            color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                    offeredKey?.let { key ->
                        Text(key.algorithm, style = MaterialTheme.typography.labelSmall)
                        Text(key.fingerprint, Modifier.testTag("hostKeyFingerprint"), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(enabled = !busy, onClick = {
                            fingerprint = key.fingerprint
                            offeredKey = null
                            built()?.let(::test)
                        }) { Text("Trust host key") }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                HorizontalDivider()
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = built() != null && !busy, onClick = { built()?.let(::test) }) {
                        Text(if (testing) "Testing…" else "Test connection")
                    }
                    FilledTonalButton(enabled = built() != null && !busy && (!sftp || fingerprint.isNotEmpty()), onClick = {
                        val candidate = built() ?: return@FilledTonalButton
                        val save = {
                            result = viewModel.servers.save(candidate, credentials())
                            if (result == null) onDismiss()
                        }
                        if (isNew || credentialsChanged) withVault(viewModel, actions, { result = it }, save) else save()
                    }) { Text("Save") }
                    if (!isNew) TextButton(enabled = !busy, onClick = { confirmRemove = true }) { Text("Remove") }
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("Remove ${account.name.ifEmpty { account.host }}?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    withVault(viewModel, actions, { result = it }) {
                        result = viewModel.servers.remove(account.id)
                        if (result == null) onDismiss()
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } })
    }
}

@Composable
private fun TransferSecretField(value: String, onValue: (String) -> Unit, label: String, enabled: Boolean, kept: Boolean) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(value, onValue, enabled = enabled, singleLine = true, label = { Text(label) },
        placeholder = { if (kept) Text("Unchanged") },
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            ToolIcon(if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                if (shown) "Hide ${label.lowercase()}" else "Show ${label.lowercase()}") { shown = !shown }
        }, modifier = Modifier.fillMaxWidth())
}

internal fun readTransferPrivateKey(input: InputStream): String {
    val bytes = ByteArray(65_537)
    var size = 0
    while (size < bytes.size) {
        val read = input.read(bytes, size, bytes.size - size)
        if (read < 0) break
        if (read == 0) {
            val next = input.read()
            if (next < 0) break
            bytes[size++] = next.toByte()
        } else size += read
    }
    require(size in 1..65_536) { "Private key must be at most 64 KiB" }
    return bytes.decodeToString(0, size)
}
