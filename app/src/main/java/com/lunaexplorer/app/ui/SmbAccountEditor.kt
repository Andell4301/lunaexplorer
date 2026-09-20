@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lunaexplorer.app.storage.smb.SmbAccount
import com.lunaexplorer.app.storage.smb.SmbDialect
import kotlinx.coroutines.launch

@Composable
internal fun SmbAccountEditor(
    account: SmbAccount,
    isNew: Boolean,
    viewModel: BrowserViewModel,
    actions: LunaActions,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(account.name) }
    var host by remember { mutableStateOf(account.host) }
    var port by remember { mutableStateOf(account.port.toString()) }
    var share by remember { mutableStateOf(account.share) }
    var domain by remember { mutableStateOf(account.domain) }
    var username by remember { mutableStateOf(account.username) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var guest by remember { mutableStateOf(account.guest) }
    var options by remember { mutableStateOf(account.options) }
    var timeout by remember { mutableStateOf(account.options.timeoutSeconds.toString()) }
    var advanced by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    // The result sits under the fields, which can fill the screen.
    LaunchedEffect(result) { if (result != null) scroll.animateScrollTo(scroll.maxValue) }

    fun built(): SmbAccount? {
        val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val seconds = timeout.toIntOrNull()?.takeIf { it in 1..600 } ?: return null
        if (host.isBlank()) return null
        return account.copy(
            name = name.trim().ifEmpty { listOf(host.trim(), share.trim().trim('/')).filter { it.isNotEmpty() }.joinToString("/") },
            host = host.trim(), port = portNumber, share = share.trim().trim('/'),
            domain = domain.trim(), username = if (guest) "" else username.trim(), guest = guest,
            options = options.copy(timeoutSeconds = seconds),
        )
    }
    // null writes nothing to the vault: a guest has no password, and an empty field on an existing
    // account keeps the stored one.
    val passwordToKeep: String? = when {
        guest -> null
        password.isNotEmpty() -> password
        isNew -> ""
        else -> null
    }

    fun withVault(then: () -> Unit) = withVault(viewModel, actions, { result = it }, then)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onDismiss)
                    Text(if (isNew) "Add an SMB server" else account.name, Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.weight(1f).fastVerticalScroll(scroll).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") },
                        placeholder = { Text("NAS media") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(host, { host = it }, singleLine = true, label = { Text("Server") },
                            placeholder = { Text("nas.local or 192.168.1.10") }, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                        OutlinedTextField(port, { port = it.filter { c -> c.isDigit() }.take(5) }, singleLine = true,
                            label = { Text("Port") }, modifier = Modifier.width(96.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    OutlinedTextField(share, { share = it }, singleLine = true, label = { Text("Share") },
                        placeholder = { Text("All shares") },
                        supportingText = { Text("Leave blank for all shares") },
                        modifier = Modifier.fillMaxWidth())
                    SwitchRow("Connect as a guest", guest) { guest = it }
                    if (!guest) {
                        OutlinedTextField(username, { username = it }, singleLine = true, label = { Text("User") },
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it }, singleLine = true,
                            label = { Text("Password") },
                            placeholder = { Text(if (isNew) "" else "Unchanged") },
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                ToolIcon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    if (showPassword) "Hide password" else "Show password") { showPassword = !showPassword }
                            },
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(domain, { domain = it }, singleLine = true, label = { Text("Domain or workgroup") },
                            placeholder = { Text("Usually empty") }, modifier = Modifier.fillMaxWidth())
                    }

                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Fewer options" else "More options") }
                    if (advanced) {
                        Text("Encryption (SMB3 Only)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        ChoiceRow("Encrypt where the server can", options.encrypt && !options.requireEncryption) {
                            options = options.copy(encrypt = true, requireEncryption = false)
                        }
                        ChoiceRow("Require encryption", options.requireEncryption) {
                            options = options.copy(encrypt = true, requireEncryption = true)
                        }
                        ChoiceRow("Never encrypt", !options.encrypt) {
                            options = options.copy(encrypt = false, requireEncryption = false)
                        }
                        SwitchRow("Require signing", options.requireSigning) { options = options.copy(requireSigning = it) }
                        SwitchRow("Follow DFS referrals", options.dfs) { options = options.copy(dfs = it) }

                        Text("Lowest dialect", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        DialectRow(options.minDialect) { chosen ->
                            options = options.copy(minDialect = chosen,
                                maxDialect = if (chosen > options.maxDialect) chosen else options.maxDialect)
                        }
                        Text("Highest dialect", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        DialectRow(options.maxDialect) { chosen ->
                            options = options.copy(maxDialect = chosen,
                                minDialect = if (chosen < options.minDialect) chosen else options.minDialect)
                        }
                        OutlinedTextField(timeout, { timeout = it.filter { c -> c.isDigit() }.take(3) }, singleLine = true,
                            label = { Text("Timeout, seconds") }, modifier = Modifier.width(180.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }

                    result?.let {
                        Text(it, Modifier.testTag("editorResult"), style = MaterialTheme.typography.bodyMedium,
                            color = if (it.startsWith("Connected")) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(24.dp))
                }
                HorizontalDivider()
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = built() != null && !testing, onClick = {
                        val candidate = built() ?: return@OutlinedButton
                        // Testing with a stored password requires the vault to be open.
                        val test: () -> Unit = {
                            testing = true; result = null
                            scope.launch {
                                result = viewModel.smb.test(candidate, password.takeIf { it.isNotEmpty() }).fold(
                                    { probe ->
                                        val session = probe.summary
                                        "Connected  ·  SMB ${session.dialect}  ·  ${if (session.encrypted) "encrypted" else "not encrypted"}" +
                                            "  ·  ${if (session.signed) "signed" else "unsigned"}" +
                                            (probe.shares?.let { "  ·  ${if (it == 1) "one share" else "$it shares"}" } ?: "")
                                    },
                                    { it.message ?: "Could not connect" })
                                testing = false
                            }
                        }
                        if (password.isEmpty() && !guest && username.isNotBlank()) withVault(test) else test()
                    }) { Text(if (testing) "Testing…" else "Test connection") }
                    FilledTonalButton(enabled = built() != null, onClick = {
                        val candidate = built() ?: return@FilledTonalButton
                        // Shown here: a message on the screen behind this window would not be seen.
                        val save = { result = viewModel.smb.save(candidate, passwordToKeep); if (result == null) onDismiss() }
                        if (passwordToKeep != null) withVault(save) else save()
                    }) { Text("Save") }
                    if (!isNew) TextButton(onClick = { confirmRemove = true }) { Text("Remove") }
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${account.name}?") },
            text = { Text("The account and its password are forgotten. Nothing on the server is touched, " +
                "and bookmarks into it stop working until it is added again.") },
            confirmButton = { TextButton(onClick = { viewModel.smb.remove(account.id); onDismiss() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DialectRow(chosen: SmbDialect, onPick: (SmbDialect) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmbDialect.entries.forEach { dialect ->
            FilterChip(chosen == dialect, { onPick(dialect) }, label = { Text(dialect.label) })
        }
    }
}
