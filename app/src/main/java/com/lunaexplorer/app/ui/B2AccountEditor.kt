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
import com.lunaexplorer.app.storage.b2.B2Account
import kotlinx.coroutines.launch

@Composable
internal fun B2AccountEditor(
    account: B2Account,
    isNew: Boolean,
    viewModel: BrowserViewModel,
    actions: LunaActions,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(account.name) }
    var keyId by remember { mutableStateOf(account.keyId) }
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var bucket by remember { mutableStateOf(account.bucket) }
    var timeout by remember { mutableStateOf(account.options.timeoutSeconds.toString()) }
    var partSize by remember { mutableStateOf(account.options.partMegabytes.toString()) }
    var advanced by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    // The result sits under the fields, which can fill the screen.
    LaunchedEffect(result) { if (result != null) scroll.animateScrollTo(scroll.maxValue) }

    fun built(): B2Account? {
        val seconds = timeout.toIntOrNull()?.takeIf { it in 1..600 } ?: return null
        val megabytes = partSize.toIntOrNull()?.takeIf { it in 5..5000 } ?: return null
        if (keyId.isBlank() || (isNew && key.isBlank())) return null
        return account.copy(
            name = name.trim().ifEmpty { bucket.trim().ifEmpty { "B2" } },
            keyId = keyId.trim(), bucket = bucket.trim(),
            options = account.options.copy(timeoutSeconds = seconds, partMegabytes = megabytes),
        )
    }
    // null writes nothing to the vault: an empty field on an existing account keeps the stored key.
    val keyToKeep: String? = key.trim().ifEmpty { null }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onDismiss)
                    Text(if (isNew) "Add a B2 account" else account.name, Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.weight(1f).fastVerticalScroll(scroll).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") },
                        placeholder = { Text("Backups") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(keyId, { keyId = it }, singleLine = true, label = { Text("Key ID") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false))
                    OutlinedTextField(key, { key = it }, singleLine = true,
                        label = { Text("Application key") },
                        placeholder = { Text(if (isNew) "" else "Unchanged") },
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            ToolIcon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                if (showKey) "Hide key" else "Show key") { showKey = !showKey }
                        },
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(bucket, { bucket = it }, singleLine = true, label = { Text("Bucket") },
                        placeholder = { Text("All buckets") },
                        supportingText = { Text("Leave blank for all buckets") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false))

                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Fewer options" else "More options") }
                    if (advanced) {
                        OutlinedTextField(timeout, { timeout = it.filter { c -> c.isDigit() }.take(3) }, singleLine = true,
                            label = { Text("Timeout, seconds") }, modifier = Modifier.width(180.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(partSize, { partSize = it.filter { c -> c.isDigit() }.take(4) }, singleLine = true,
                            label = { Text("Upload part, MB") }, modifier = Modifier.width(180.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        if (!isNew) {
                            OutlinedButton(enabled = !cleared, onClick = { viewModel.b2.clearCache(account.id) { cleared = true } }) {
                                Text(if (cleared) "Cleared" else "Clear cached lists")
                            }
                        }
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
                        val test: () -> Unit = {
                            testing = true; result = null
                            scope.launch {
                                result = viewModel.b2.test(candidate, keyToKeep).fold(
                                    { probe -> "Connected" + (probe.buckets?.let { "  ·  ${if (it == 1) "one bucket" else "$it buckets"}" } ?: "") },
                                    { it.message ?: "Could not connect" })
                                testing = false
                            }
                        }
                        // Testing with the stored key requires the vault to be open.
                        if (keyToKeep == null) withVault(viewModel, actions, { result = it }, test) else test()
                    }) { Text(if (testing) "Testing…" else "Test connection") }
                    FilledTonalButton(enabled = built() != null, onClick = {
                        val candidate = built() ?: return@FilledTonalButton
                        // Shown here: a message on the screen behind this window would not be seen.
                        val save = { result = viewModel.b2.save(candidate, keyToKeep); if (result == null) onDismiss() }
                        if (keyToKeep != null) withVault(viewModel, actions, { result = it }, save) else save()
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
            text = { Text("The account and its key are forgotten. Nothing in B2 is touched, " +
                "and bookmarks into it stop working until it is added again.") },
            confirmButton = { TextButton(onClick = { viewModel.b2.remove(account.id); onDismiss() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}
