package com.lunaexplorer.app.storage.smb

import com.lunaexplorer.app.debug.DebugLog
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException

private const val TAG = "Smb"

// Reconnect before requests; retrying a failed request could repeat a mutation.
internal class SmbSessions(private val connector: SmbConnector) {
    private class Live(val account: SmbAccount, val server: SmbServer) {
        /** Keyed by lowercased share name. */
        val shares = HashMap<String, SmbShare>()
    }
    private val live = HashMap<String, Live>()
    private val locks = HashMap<String, Any>()

    private fun lockFor(id: String): Any = synchronized(locks) { locks.getOrPut(id) { Any() } }

    fun server(account: SmbAccount, withPassword: (SmbAccount) -> SmbAccount?): SmbServer =
        synchronized(lockFor(account.id)) { session(account, withPassword).server }

    fun share(account: SmbAccount, name: String, withPassword: (SmbAccount) -> SmbAccount?): SmbShare =
        synchronized(lockFor(account.id)) {
            val session = session(account, withPassword)
            val key = name.lowercase()
            val current = session.shares[key]
            if (current != null && current.alive()) return current
            current?.let {
                DebugLog.d(TAG) { "The connection to ${account.name}/$name was closed; opening it again" }
                session.shares.remove(key); runCatching { it.close() }
            }
            val started = System.nanoTime()
            try {
                session.server.open(name).also {
                    session.shares[key] = it
                    DebugLog.d(TAG) { "Opened ${account.name}/$name in ${DebugLog.millisSince(started)} ms" }
                }
            } catch (stale: SmbFailure) {
                if (stale.reason != StorageError.DISCONNECTED) throw stale
                DebugLog.w(TAG, stale) { "The session to ${account.name} could not open $name again; signing in again" }
                synchronized(live) { if (live[account.id] === session) live.remove(account.id) }
                close(session)
                val fresh = session(account, withPassword)
                fresh.server.open(name).also { fresh.shares[key] = it }
            }
        }

    private fun session(account: SmbAccount, withPassword: (SmbAccount) -> SmbAccount?): Live {
        val current = synchronized(live) { live[account.id] }
        if (current != null && current.server.alive() && sameWayIn(current.account, account)) return current
        DebugLog.i(TAG) {
            "Signing in to ${account.name}: " + when {
                current == null -> "no session yet"
                !current.server.alive() -> "the session was closed"
                else -> "the account's settings changed"
            }
        }
        current?.let { close(it) }
        // Guest and anonymous sign-ins need no password, so the vault can stay locked.
        val ready = if (account.guest || account.username.isEmpty()) account
        else withPassword(account) ?: throw StorageException(StorageError.AUTH,
            "Unlock the credential vault to connect to ${account.name}")
        val opened = Live(ready, connector.connect(ready))
        synchronized(live) { live[account.id] = opened }
        return opened
    }

    fun forget(account: SmbAccount, name: String, tree: SmbShare) {
        synchronized(lockFor(account.id)) {
            val shares = synchronized(live) { live[account.id] }?.shares
            // A concurrent request may already have replaced it; remove only this exact tree.
            if (shares != null && shares[name.lowercase()] === tree) {
                shares.remove(name.lowercase())
                DebugLog.d(TAG) { "Dropped the connection to ${account.name}/$name after a failure on it" }
            }
            runCatching { tree.close() }
        }
    }

    fun drop(id: String) = synchronized(lockFor(id)) {
        val gone = synchronized(live) { live.remove(id) }
        gone?.let {
            DebugLog.i(TAG) { "Closed the session to ${it.account.name}" }
            close(it)
        }
    }

    private fun close(gone: Live) {
        gone.shares.values.forEach { runCatching { it.close() } }
        runCatching { gone.server.close() }
    }

    /** Compared without the password: only the connected copy carries it. */
    private fun sameWayIn(connected: SmbAccount, wanted: SmbAccount): Boolean =
        connected.copy(password = "") == wanted.copy(password = "")
}
