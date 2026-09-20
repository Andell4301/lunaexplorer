package com.lunaexplorer.core

import java.io.IOException
import java.io.InterruptedIOException

// Network handles share payload reservations; short reads refund unused bytes, failed reads retain their reservation.
class ReadBudget(val max: Long, private val cancellationCheck: () -> Unit = {}) {
    init { require(max >= 0) }

    private var charged = 0L

    /** Payload returned by successful reads plus the reservations of failed ones. */
    val bytesRead: Long @Synchronized get() = charged
    val remaining: Long @Synchronized get() = max - charged

    /** For reads served from cache, which never reach [read]. */
    fun checkCancelled() = cancellationCheck()

    /** Serializes reservations across handles so concurrent readers cannot overspend. */
    @Synchronized
    fun read(requested: Int, action: (Int) -> Int): Int {
        require(requested >= 0)
        checkCancelled()
        if (requested == 0) return 0
        val allowed = minOf(requested.toLong(), max - charged).toInt()
        if (allowed == 0) throw ReadBudgetExceeded(max)
        charged += allowed
        val received = action(allowed)
        if (received < -1 || received > allowed) throw IOException("A reader returned an invalid byte count")
        charged -= allowed - received.coerceAtLeast(0)
        checkCancelled()
        return received
    }
}

/** An [InterruptedIOException] so callers do not mistake an exhausted budget for end-of-file. */
class ReadBudgetExceeded(val max: Long) : InterruptedIOException("Network read limit of $max bytes reached")
