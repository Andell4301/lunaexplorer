package com.lunaexplorer.app.storage.b2

import com.lunaexplorer.app.debug.DebugLog
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException

private const val TAG = "B2"

// Resolving a bucket can be retried; a failed object request may already have mutated storage.
internal class B2Sessions(private val connector: B2Connector) {
    private class Live(val account: B2Account, val session: B2Session) {
        val buckets = HashMap<String, B2Bucket>()
    }

    private val live = HashMap<String, Live>()
    private val locks = HashMap<String, Any>()

    private fun lockFor(id: String): Any = synchronized(locks) { locks.getOrPut(id) { Any() } }

    fun session(account: B2Account, withKey: (B2Account) -> B2Account?): B2Session =
        synchronized(lockFor(account.id)) { authorized(account, withKey).session }

    fun bucket(account: B2Account, name: String, withKey: (B2Account) -> B2Account?): B2Bucket =
        synchronized(lockFor(account.id)) {
            val current = authorized(account, withKey)
            current.buckets[name]?.let { if (it.alive()) return it else current.buckets.remove(name) }
            val started = System.nanoTime()
            try {
                current.session.bucket(name).also {
                    current.buckets[name] = it
                    DebugLog.d(TAG) { "Opened ${account.name}/$name in ${DebugLog.millisSince(started)} ms" }
                }
            } catch (stale: B2Failure) {
                if (stale.reason != StorageError.AUTH) throw stale
                DebugLog.w(TAG, stale) { "The authorization for ${account.name} would not open $name; authorizing again" }
                synchronized(live) { if (live[account.id] === current) live.remove(account.id) }
                close(current)
                val fresh = authorized(account, withKey)
                fresh.session.bucket(name).also { fresh.buckets[name] = it }
            }
        }

    /** What the live authorization allows, or null when there is none. Never connects. */
    fun known(accountId: String): B2Auth? = synchronized(live) { live[accountId] }?.session?.summary

    /** Whether a request can be made without the account's key. Never connects. */
    fun holds(accountId: String): Boolean = synchronized(live) { live[accountId] }?.session?.alive() == true

    private fun authorized(account: B2Account, withKey: (B2Account) -> B2Account?): Live {
        val current = synchronized(live) { live[account.id] }
        if (current != null && current.session.alive() && sameWayIn(current.account, account)) return current
        DebugLog.i(TAG) {
            "Authorizing ${account.name}: " + when {
                current == null -> "no authorization yet"
                !current.session.alive() -> "the authorization was spent"
                else -> "the account's settings changed"
            }
        }
        current?.let { close(it) }
        val ready = withKey(account) ?: throw StorageException(StorageError.AUTH,
            "Unlock the credential vault to connect to ${account.name}")
        val opened = Live(ready, connector.connect(ready))
        synchronized(live) { live[account.id] = opened }
        return opened
    }

    /** Drops a spent authorization so the next request makes a new one. The failed request is not retried. */
    fun forget(account: B2Account, session: B2Session) {
        synchronized(lockFor(account.id)) {
            val held = synchronized(live) { live[account.id] }
            // A concurrent request may already have replaced it; remove only this exact one.
            if (held != null && held.session === session) {
                synchronized(live) { live.remove(account.id) }
                DebugLog.d(TAG) { "Dropped the authorization for ${account.name} after a failure on it" }
                close(held)
            }
        }
    }

    fun drop(id: String) = synchronized(lockFor(id)) {
        val gone = synchronized(live) { live.remove(id) }
        gone?.let {
            DebugLog.i(TAG) { "Closed the authorization for ${it.account.name}" }
            close(it)
        }
    }

    private fun close(gone: Live) {
        gone.buckets.clear()
        runCatching { gone.session.close() }
    }

    /** Compared without the key: only the authorized copy carries it. */
    private fun sameWayIn(connected: B2Account, wanted: B2Account): Boolean =
        connected.copy(applicationKey = "") == wanted.copy(applicationKey = "")
}
