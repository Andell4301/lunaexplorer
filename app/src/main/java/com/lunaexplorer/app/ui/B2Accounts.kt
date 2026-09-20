package com.lunaexplorer.app.ui

import com.lunaexplorer.app.AppGraph
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.storage.b2.B2Account
import com.lunaexplorer.app.storage.b2.B2Probe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Account metadata lives in the session; application keys live in the vault, which may be closed. */
class B2Accounts(
    private val state: MutableStateFlow<BrowserState>,
    private val graph: AppGraph,
    private val vault: VaultAccess,
    private val scope: CoroutineScope,
    private val persist: () -> Unit,
    private val rootsChanged: () -> Unit,
) {
    private val _cachedBytes = MutableStateFlow(0L)
    /** Bytes the saved listings occupy, as of the last change made here or [measureCache]. */
    val cachedBytes: StateFlow<Long> = _cachedBytes

    fun apply() { graph.b2Accounts = state.value.b2Accounts }

    /** Null once saved, otherwise why not. Nothing is kept, not even an edit, if the key cannot be stored. A null [key] keeps the stored one. */
    fun save(account: B2Account, key: String?): String? {
        if (key != null) vault.openOrWhy()?.let { return it }
        val before = state.value.b2Accounts
        val held = before.firstOrNull { it.id == account.id }
        state.update { s -> s.copy(b2Accounts = if (held != null) before.map { if (it.id == account.id) account else it } else before + account) }
        if (key != null) {
            val known = state.value.b2Accounts.map { it.id }.toSet()
            val failed = vault.write { it.copy(b2Keys = (it.b2Keys + (account.id to key)).filterKeys { id -> id in known }) }
            if (failed != null) {
                state.update { it.copy(b2Accounts = before) }
                return failed
            }
        }
        disconnect(account.id)
        // Another key or bucket sees other files, so what was saved under the old one no longer describes it.
        if (held != null && (held.keyId != account.keyId || held.bucket != account.bucket)) clearCache(account.id)
        apply(); persist(); rootsChanged()
        return null
    }

    fun remove(id: String) {
        state.update { s -> s.copy(b2Accounts = s.b2Accounts.filter { it.id != id }) }
        disconnect(id)
        // If this cannot be written now, the next save prunes it.
        if (id in graph.vault.secrets.value?.b2Keys.orEmpty()) vault.write { it.copy(b2Keys = it.b2Keys - id) }
        clearCache(id)
        apply(); persist(); rootsChanged()
    }

    /** Off the main thread: it waits for a request in flight on that account, which can take the whole timeout. */
    private fun disconnect(id: String) { scope.launch(Dispatchers.IO) { graph.b2.disconnect(id) } }

    /** A null [key] tests the stored one. */
    suspend fun test(account: B2Account, key: String?): Result<B2Probe> {
        val kept = graph.vault.secrets.value?.b2Keys?.get(account.id).orEmpty()
        return try {
            Result.success(graph.b2.probe(account.copy(applicationKey = key ?: kept)))
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            Result.failure(failure)
        }
    }

    fun clearCache(accountId: String? = null, then: () -> Unit = {}) {
        scope.launch {
            if (accountId == null) graph.b2.forget(null) else graph.b2.forgetAccount(accountId)
            _cachedBytes.value = graph.b2.cachedBytes()
            then()
        }
    }

    fun measureCache() { scope.launch { _cachedBytes.value = graph.b2.cachedBytes() } }
}
