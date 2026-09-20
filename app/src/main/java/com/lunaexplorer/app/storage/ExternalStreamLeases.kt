package com.lunaexplorer.app.storage

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal class ExternalStreamLeases(private val now: () -> Long) {
    enum class Purpose { MEDIA, TRANSFER }
    data class Active(val id: Long, val name: String, val purpose: Purpose)
    data class Snapshot(val active: List<Active>, val nextExpiry: Long?)

    private data class Session(
        val uri: String,
        val active: Active,
        var expires: Long,
        var readers: Int = 0,
    )

    private val sessions = linkedMapOf<Long, Session>()
    private var nextId = 0L

    @Synchronized fun prepare(uri: String, name: String, purpose: Purpose): Long {
        val id = ++nextId
        sessions[id] = Session(uri, Active(id, name, purpose), now() + HANDOFF_TIMEOUT_MS)
        return id
    }

    @Synchronized fun cancel(id: Long) {
        val session = sessions[id] ?: return
        if (session.readers == 0) sessions.remove(id)
    }

    @Synchronized fun acquire(uri: String, changed: () -> Unit): Closeable? {
        expire()
        val session = sessions.values.lastOrNull { it.uri == uri } ?: return null
        session.readers++
        val closed = AtomicBoolean()
        return Closeable {
            if (closed.compareAndSet(false, true)) {
                synchronized(this) {
                    if (sessions[session.active.id] === session) {
                        session.readers--
                        // Players often close the file after reading metadata and reopen it to play.
                        if (session.readers == 0) session.expires = now() + REOPEN_GRACE_MS
                    }
                }
                changed()
            }
        }
    }

    @Synchronized fun snapshot(): Snapshot {
        expire()
        return Snapshot(sessions.values.map { it.active },
            sessions.values.filter { it.readers == 0 }.minOfOrNull { it.expires })
    }

    @Synchronized fun clear(purpose: Purpose) {
        sessions.values.removeAll { it.active.purpose == purpose }
    }

    private fun expire() {
        val time = now()
        sessions.values.removeAll { it.readers == 0 && it.expires <= time }
    }

    companion object {
        // Covers a dismissed chooser or a receiving app that never opens the URI.
        const val HANDOFF_TIMEOUT_MS = 120_000L
        const val REOPEN_GRACE_MS = 10_000L
    }
}
