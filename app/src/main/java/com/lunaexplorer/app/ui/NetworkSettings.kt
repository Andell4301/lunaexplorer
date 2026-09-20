@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.NetworkThumbnails
import com.lunaexplorer.app.model.Preferences
import com.lunaexplorer.app.model.VersionedDelete
import com.lunaexplorer.app.storage.b2.B2Account
import com.lunaexplorer.app.storage.smb.SmbAccount

internal enum class NetworkKind(val id: String, val title: String) {
    SMB("smb", "SMB"),
    B2("b2", "Backblaze B2");

    fun summary(state: BrowserState): String = when (this) {
        SMB -> when (val servers = state.smbAccounts.size) { 0 -> "No servers"; 1 -> "One server"; else -> "$servers servers" }
        B2 -> when (val accounts = state.b2Accounts.size) { 0 -> "No accounts"; 1 -> "One account"; else -> "$accounts accounts" }
    }
}

@Composable
internal fun NetworkSettings(
    state: BrowserState,
    viewModel: BrowserViewModel,
    actions: LunaActions,
    onKind: (NetworkKind) -> Unit,
    onDiscardVault: () -> Unit,
) {
    Spacer(Modifier.height(6.dp))
    NetworkKind.entries.forEach { kind -> SettingsLink(kind.title, kind.summary(state)) { onKind(kind) } }

    SectionHeading("Credential vault")
    val secrets by viewModel.vault.secrets.collectAsState()
    Text(when {
        secrets != null -> "Unlocked. Passwords are kept encrypted on this device and are read only to connect."
        state.vaultLocked -> "Locked. Passwords stay unreadable until you unlock the vault, once per run."
        else -> "Closed. It opens by itself when an account needs it."
    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    SwitchRow("Lock behind biometrics or device credential", state.vaultLocked,
        "Ask before the passwords can be used — a fingerprint, a face, or the device's own PIN") { on ->
        if (on && !viewModel.vault.canLock()) {
            viewModel.showMessage("Set a screen lock on this device first")
        } else {
            // Both directions need auth: enabling writes with the protected key, disabling reads with it.
            actions.unlockVault { proved -> if (proved) viewModel.vault.setLocked(on) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.vaultLocked && secrets != null) {
            OutlinedButton(onClick = { viewModel.vault.close() }) { Text("Lock now") }
        }
        if (state.vaultLocked && secrets == null) {
            OutlinedButton(onClick = {
                actions.unlockVault { proved -> if (proved) viewModel.vault.open() }
            }) { Text("Unlock") }
        }
        TextButton(onClick = onDiscardVault) { Text("Discard the vault") }
    }
    Text("Discarding removes all stored credentials. A biometrics change will discard the vault",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SmbSettings(state: BrowserState, onChange: (Preferences) -> Unit, onEdit: (SmbAccount, Boolean) -> Unit) {
    SectionHeading("Servers")
    if (state.smbAccounts.isEmpty()) {
        Text("Folders on other machines, reached over the network as if they were here. " +
            "A server's top level is every share it has; name one share instead to make that the root.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.smbAccounts.forEach { account ->
        SettingsLink(account.name, account.address + when {
            account.guest -> "  ·  guest"
            account.username.isNotEmpty() -> "  ·  ${account.username}"
            else -> ""
        }) { onEdit(account, false) }
    }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = { onEdit(SmbAccount(name = "", host = ""), true) }) { Text("Add an SMB server") }

    ThumbnailsOn(NetworkKind.SMB, state.preferences, onChange)
}

@Composable
internal fun B2Settings(state: BrowserState, viewModel: BrowserViewModel, onEdit: (B2Account, Boolean) -> Unit) {
    val preferences = state.preferences
    val onChange = viewModel::setPreferences
    SectionHeading("Accounts")
    if (state.b2Accounts.isEmpty()) {
        Text("Backblaze B2 buckets, reached with an application key. An account's top level is every bucket " +
            "the key can see; name one bucket instead to make that the root.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.b2Accounts.forEach { account -> SettingsLink(account.name, account.address) { onEdit(account, false) } }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = { onEdit(B2Account(name = "", keyId = ""), true) }) { Text("Add a B2 account") }

    ThumbnailsOn(NetworkKind.B2, preferences, onChange)

    SectionHeading("Cached lists")
    Text("Folder lists are kept on this device until refreshed.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val cached by viewModel.b2.cachedBytes.collectAsState()
    LaunchedEffect(Unit) { viewModel.b2.measureCache() }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(enabled = cached > 0, onClick = { viewModel.b2.clearCache() }) {
        Text(if (cached > 0) "Clear cached lists  ·  ${formatBytes(cached)}" else "Clear cached lists")
    }

    SectionHeading("Deleting")
    VersionedDelete.entries.forEach { choice ->
        ChoiceRow(choice.label, choice == preferences.versionedDelete) {
            onChange(preferences.copy(versionedDelete = choice))
            viewModel.rememberForSession(versionedDelete = choice)
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ThumbnailsOn(kind: NetworkKind, preferences: Preferences, onChange: (Preferences) -> Unit) {
    Spacer(Modifier.height(22.dp))
    NetworkThumbnailSetting(preferences.thumbnailsOn(kind.id)) { limit ->
        onChange(preferences.copy(networkThumbnails = preferences.networkThumbnails + (kind.id to limit)))
    }
}

private val networkThumbnailPresets = listOf(
    NetworkThumbnails.OFF, NetworkThumbnails.MB_10, NetworkThumbnails.MB_25, NetworkThumbnails.ANY,
)

@Composable
internal fun NetworkThumbnailSetting(current: NetworkThumbnails, onChange: (NetworkThumbnails) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    Text("Network thumbnails", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    Text("Data read per thumbnail. Large files can still have previews.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        networkThumbnailPresets.forEach { limit ->
            FilterChip(current == limit, { onChange(limit) }, label = { Text(limit.label) })
        }
        val custom = current !in networkThumbnailPresets
        FilterChip(custom, { editing = !editing },
            label = { Text(if (custom) "Custom: ${current.megabytes} MB" else "Custom") })
    }
    // Inline, not an AlertDialog: a text field in a dialog nested in the settings Dialog never goes idle.
    if (editing) {
        var typed by rememberSaveable {
            mutableStateOf((if (current == NetworkThumbnails.OFF || current == NetworkThumbnails.ANY)
                NetworkThumbnails.MB_25 else current).megabytes.toString())
        }
        val limit = typed.trim().toLongOrNull()?.let(NetworkThumbnails::forMegabytes)
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("MB per thumbnail") },
            supportingText = {
                if (limit == null && typed.isNotEmpty()) Text("Enter a whole number from 1 to ${NetworkThumbnails.MAX_MEGABYTES}.")
            },
            isError = limit == null && typed.isNotEmpty(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = limit != null, onClick = {
                limit?.let(onChange)
                editing = false
            }) { Text("Save") }
            TextButton(onClick = { editing = false }) { Text("Cancel") }
        }
    }
}
