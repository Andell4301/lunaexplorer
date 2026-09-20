package com.lunaexplorer.app.ui

import com.lunaexplorer.app.AppGraph
import com.lunaexplorer.app.debug.DebugLog
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.storage.Secrets
import com.lunaexplorer.app.storage.VaultLocked
import com.lunaexplorer.app.storage.VaultUnreadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class VaultAccess(
    private val state: MutableStateFlow<BrowserState>,
    private val graph: AppGraph,
    private val persist: () -> Unit,
    private val message: (String) -> Unit,
) {
    val locked: Boolean get() = state.value.vaultLocked
    val isOpen: Boolean get() = graph.vault.open
    val secrets: StateFlow<Secrets?> get() = graph.vault.secrets

    fun canLock(): Boolean = graph.vault.canLock()

    /** Whether secrets can be read and written now without asking the user. An open vault is not enough: see [CredentialVault.keyAnswers]. */
    fun usable(): Boolean = !locked || (isOpen && graph.vault.keyAnswers(locked = true))

    /** False when authentication is needed (the caller prompts for it) or the vault is unreadable (reported here). */
    fun open(): Boolean = when (val failed = attempt()) {
        null -> true
        is VaultLocked -> false
        else -> { message(failed.message ?: "The credential vault could not be opened"); false }
    }

    /** As [open], for a caller that shows the reason itself: null once open. */
    fun openOrWhy(): String? = when (val failed = attempt()) {
        null -> null
        is VaultLocked -> "Unlock the credential vault first"
        else -> failed.message ?: "The credential vault could not be opened"
    }

    private fun attempt(): Exception? = try {
        graph.vault.open(locked)
        DebugLog.d(VAULT) { "Opened" }
        null
    } catch (shut: VaultLocked) {
        DebugLog.i(VAULT) { "Stayed shut: it is locked" }
        shut
    } catch (broken: VaultUnreadable) {
        DebugLog.w(VAULT, broken) { "Could not be read" }
        broken
    }

    /** Open connections stay up; new ones need the vault reopened. */
    fun close() {
        graph.vault.close()
        DebugLog.i(VAULT) { "Locked" }
    }

    /** Rewrites the open vault through [change]. Null once written, otherwise why not; nothing is shown here. */
    fun write(change: (Secrets) -> Secrets): String? {
        val secrets = graph.vault.secrets.value ?: return "Unlock the credential vault first"
        return try {
            graph.vault.save(change(secrets), locked); null
        } catch (shut: VaultLocked) {
            "Unlock the credential vault first"
        } catch (broken: Exception) {
            DebugLog.w(VAULT, broken) { "Could not be written" }
            broken.message ?: "The credential vault could not be written"
        }
    }

    /** The caller must authenticate the user first: enabling writes with the protected key, disabling reads with it. */
    fun setLocked(on: Boolean): Boolean {
        if (on && !graph.vault.canLock()) { message("Set a screen lock on this device first"); return false }
        if (!open()) return false
        try { graph.vault.relock(on) } catch (broken: Exception) {
            message(broken.message ?: "The vault could not be rewritten"); return false
        }
        state.update { it.copy(vaultLocked = on) }
        persist()
        return true
    }

    fun discard() {
        graph.vault.discard()
        DebugLog.i(VAULT) { "Discarded" }
        state.update { it.copy(vaultLocked = false) }
        persist()
        message("The vault was discarded. Enter each account's password or key again to connect.")
    }
}

private const val VAULT = "Vault"

internal fun withVault(viewModel: BrowserViewModel, actions: LunaActions, refused: (String) -> Unit = viewModel::showMessage, then: () -> Unit) {
    if (viewModel.vault.usable()) { then(); return }
    actions.unlockVault { proved ->
        if (proved && viewModel.vault.open()) then() else refused("The vault stayed shut")
    }
}
