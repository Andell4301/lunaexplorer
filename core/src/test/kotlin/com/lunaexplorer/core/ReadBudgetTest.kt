package com.lunaexplorer.core

import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadBudgetTest {
    @Test fun `short reads and EOF refund unused payload but exhaustion never pretends to be EOF`() {
        val budget = ReadBudget(10)
        assertEquals(3, budget.read(8) { 3 })
        assertEquals(7L, budget.remaining)
        assertEquals(-1, budget.read(20) { allowed -> assertEquals(7, allowed); -1 })
        assertEquals(3L, budget.bytesRead)
        assertEquals(7, budget.read(20) { allowed -> allowed })
        assertEquals(0, budget.read(0) { error("Empty reads need no I/O") })
        assertThrows(ReadBudgetExceeded::class.java) { budget.read(1) { error("No allowance remains") } }
        assertEquals(10L, budget.bytesRead)
    }

    @Test fun `failed requests keep their reservation so retrying cannot bypass the limit`() {
        val budget = ReadBudget(10)
        assertThrows(IOException::class.java) { budget.read(7) { throw IOException("Partial response lost") } }
        assertEquals(7L, budget.bytesRead)
        assertEquals(3, budget.read(7) { allowed -> assertEquals(3, allowed); allowed })
        assertThrows(ReadBudgetExceeded::class.java) { budget.read(7) { error("No retry can read more") } }
    }

    @Test fun `concurrent handles reserve from one allowance`() {
        val budget = ReadBudget(10)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                workers.submit<Int> {
                    check(start.await(5, TimeUnit.SECONDS))
                    budget.read(8) { allowed -> allowed }
                }
            }
            start.countDown()
            assertEquals(listOf(2, 8), results.map { it.get(5, TimeUnit.SECONDS) }.sorted())
            assertEquals(10L, budget.bytesRead)
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun `cancellation stops requests and still charges a response that arrived while cancelling`() {
        val cancelled = AtomicBoolean()
        val budget = ReadBudget(10) { if (cancelled.get()) throw CancellationException() }
        assertThrows(CancellationException::class.java) {
            budget.read(8) { cancelled.set(true); 3 }
        }
        assertEquals(3L, budget.bytesRead)
        assertThrows(CancellationException::class.java) { budget.checkCancelled() }
        assertThrows(CancellationException::class.java) { budget.read(1) { error("Cancelled before I/O") } }
    }
}
