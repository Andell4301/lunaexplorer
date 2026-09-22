package com.lunaexplorer.app.ui

import com.lunaexplorer.app.AppGraph
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.storage.transfer.TransferAccount
import com.lunaexplorer.app.storage.transfer.TransferCredentials
import com.lunaexplorer.app.storage.transfer.TransferProtocol
import com.lunaexplorer.app.storage.transfer.validationError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TransferAccounts(
    private val state: MutableStateFlow<BrowserState>,
    private val graph: AppGraph,
    private val vault: VaultAccess,
    private val scope: CoroutineScope,
    private val persist: () -> Unit,
    private val rootsChanged: () -> Unit,
) {
    fun apply() { graph.transferAccounts = state.value.transferAccounts }

    fun save(account: TransferAccount, credentials: TransferCredentials?): String? {
        account.validationError()?.let { return it }
        if (account.protocol == TransferProtocol.SFTP && account.hostKeyFingerprint.isEmpty()) {
            return "Trust a host key first"
        }
        if (credentials != null) {
            vault.openOrWhy()?.let { return it }
            val known = state.value.transferAccounts.map { it.id }.toSet() + account.id
            vault.write {
                it.copy(transferCredentials = (it.transferCredentials + (account.id to credentials))
                    .filterKeys { id -> id in known })
            }?.let { return it }
        }
        state.update { current ->
            val accounts = current.transferAccounts
            current.copy(transferAccounts = if (accounts.any { it.id == account.id }) {
                accounts.map { if (it.id == account.id) account else it }
            } else accounts + account)
        }
        disconnect(account.id)
        apply(); persist(); rootsChanged()
        return null
    }

    fun remove(id: String): String? {
        vault.openOrWhy()?.let { return it }
        if (id in graph.vault.secrets.value?.transferCredentials.orEmpty()) {
            vault.write { it.copy(transferCredentials = it.transferCredentials - id) }?.let { return it }
        }
        state.update { it.copy(transferAccounts = it.transferAccounts.filter { account -> account.id != id }) }
        disconnect(id)
        apply(); persist(); rootsChanged()
        return null
    }

    suspend fun test(account: TransferAccount, credentials: TransferCredentials?): Result<Unit> = try {
        val kept = graph.vault.secrets.value?.transferCredentials?.get(account.id)
        val provider = if (account.protocol == TransferProtocol.SFTP) graph.sftp else graph.ftp
        provider.probe(account, credentials ?: kept ?: TransferCredentials())
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        Result.failure(error)
    }

    private fun disconnect(id: String) {
        scope.launch(Dispatchers.IO) {
            graph.ftp.disconnect(id)
            graph.sftp.disconnect(id)
        }
    }
}
