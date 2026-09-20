package com.lunaexplorer.app.ui

import com.lunaexplorer.app.AppGraph
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.storage.smb.SmbAccount
import com.lunaexplorer.app.storage.smb.SmbProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Account metadata lives in the session; passwords live in the vault, which may be closed. */
class SmbAccounts(
    private val state: MutableStateFlow<BrowserState>,
    private val graph: AppGraph,
    private val vault: VaultAccess,
    private val persist: () -> Unit,
    private val rootsChanged: () -> Unit,
) {
    fun apply() { graph.smbAccounts = state.value.smbAccounts }

    // Account edits are rolled back if the password cannot be stored.
    fun save(account: SmbAccount, password: String?): String? {
        if (password != null) vault.openOrWhy()?.let { return it }
        val before = state.value.smbAccounts
        state.update { s ->
            val accounts = s.smbAccounts
            s.copy(smbAccounts = if (accounts.any { it.id == account.id }) accounts.map { if (it.id == account.id) account else it }
            else accounts + account)
        }
        if (password != null) {
            val known = state.value.smbAccounts.map { it.id }.toSet()
            // Also prunes passwords of accounts that were removed while the vault was closed.
            val failed = vault.write { it.copy(smbPasswords = (it.smbPasswords + (account.id to password)).filterKeys { id -> id in known }) }
            if (failed != null) {
                state.update { it.copy(smbAccounts = before) }
                return failed
            }
        }
        // Drop the connection so the next one uses the new settings.
        graph.smb.disconnect(account.id)
        apply(); persist(); rootsChanged()
        return null
    }

    fun remove(id: String) {
        state.update { s -> s.copy(smbAccounts = s.smbAccounts.filter { it.id != id }) }
        graph.smb.disconnect(id)
        // If this cannot be written now, the next save prunes it.
        if (id in graph.vault.secrets.value?.smbPasswords.orEmpty()) vault.write { it.copy(smbPasswords = it.smbPasswords - id) }
        apply(); persist(); rootsChanged()
    }

    /** A null [password] tests the stored one. */
    suspend fun test(account: SmbAccount, password: String?): Result<SmbProbe> {
        val kept = graph.vault.secrets.value?.smbPasswords?.get(account.id).orEmpty()
        return try {
            Result.success(graph.smb.probe(account.copy(password = password ?: kept)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            Result.failure(error)
        }
    }
}
