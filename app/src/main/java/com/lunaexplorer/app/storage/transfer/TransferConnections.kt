package com.lunaexplorer.app.storage.transfer

import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import java.io.Closeable
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

internal class TransferConnections(
    private val connector: TransferConnector,
    private val credentials: (TransferAccount) -> TransferCredentials,
    private val reusable: Boolean,
    private val idleMillis: Long = 60_000,
) {
    private val lock = Any()
    private val sessions = mutableSetOf<Session>()
    private val idle = ArrayDeque<Session>()
    private val generations = mutableMapOf<String, Int>()

    fun acquire(account: TransferAccount, caller: CoroutineContext): Lease {
        val generation = synchronized(lock) { generations[account.id] ?: 0 }
        while (true) {
            val session = synchronized(lock) {
                idle.firstOrNull { it.account == account }?.also {
                    idle.remove(it)
                    it.expiry?.cancel(false)
                    it.expiry = null
                }
            } ?: break
            if (healthy(session)) {
                val accepted = synchronized(lock) {
                    session in sessions && (generations[account.id] ?: 0) == generation
                }
                if (accepted) return Lease(session, caller)
            }
            close(session)
        }
        val session = Session(account, connector.connect(account, credentials(account)))
        val accepted = synchronized(lock) {
            if ((generations[account.id] ?: 0) != generation) false else { sessions.add(session); true }
        }
        if (!accepted) {
            closeClient(session.client)
            throw StorageException(StorageError.DISCONNECTED, "The server connection was closed")
        }
        return Lease(session, caller)
    }

    fun disconnect(accountId: String) {
        val closing = synchronized(lock) {
            generations[accountId] = (generations[accountId] ?: 0) + 1
            sessions.filter { it.account.id == accountId }.onEach { remove(it) }
        }
        closing.forEach { closeClient(it.client) }
    }

    private fun release(session: Session) {
        if (!reusable || !healthy(session)) { close(session); return }
        val retained = synchronized(lock) {
            if (session !in sessions || idle.size >= 4) false else {
                idle.addLast(session)
                val epoch = ++session.idleEpoch
                session.expiry = reaper.schedule({ expire(session, epoch) }, idleMillis, TimeUnit.MILLISECONDS)
                true
            }
        }
        if (!retained) close(session)
    }

    private fun expire(session: Session, epoch: Long) {
        val removed = synchronized(lock) { if (session in idle && session.idleEpoch == epoch) remove(session) else false }
        if (removed) closeClient(session.client)
    }

    private fun close(session: Session) {
        val removed = synchronized(lock) { remove(session) }
        if (removed) closeClient(session.client)
    }

    private fun remove(session: Session): Boolean {
        idle.remove(session)
        session.expiry?.cancel(false)
        session.expiry = null
        return sessions.remove(session)
    }

    private fun healthy(session: Session) = try {
        session.client.alive()
    } catch (cancelled: CancellationException) {
        close(session)
        throw cancelled
    } catch (_: Exception) {
        false
    }

    internal class Session(val account: TransferAccount, val client: TransferClient) {
        var expiry: ScheduledFuture<*>? = null
        var idleEpoch = 0L
    }

    inner class Lease internal constructor(private val session: Session, private val caller: CoroutineContext) : TransferClient by session.client {
        private val returned = AtomicBoolean()

        fun discard() {
            if (returned.compareAndSet(false, true)) close(session)
        }

        fun closeAfter(resource: Closeable) {
            try { resource.close() } catch (error: Throwable) {
                discard()
                throw error
            } finally { close() }
        }

        override fun close() {
            if (returned.compareAndSet(false, true)) {
                if (caller.isActive) release(session) else close(session)
            }
        }
    }

    private companion object {
        val reaper = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "Transfer idle connections").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

        fun closeClient(client: TransferClient) {
            try { client.close() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
        }
    }
}
