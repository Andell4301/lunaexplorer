package com.lunaexplorer.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalStreamLeasesTest {
    private var time = 0L
    private val leases = ExternalStreamLeases { time }
    private val uri = "content://luna.stream/smb/movie"

    private fun prepare() = leases.prepare(uri, "movie.mkv", ExternalStreamLeases.Purpose.MEDIA)

    @Test fun `cancelled chooser or unopened URI eventually releases foreground protection`() {
        prepare()
        assertFalse(leases.snapshot().active.isEmpty())
        time += ExternalStreamLeases.HANDOFF_TIMEOUT_MS
        assertTrue(leases.snapshot().active.isEmpty())
        assertNull(leases.acquire(uri) {})
    }

    @Test fun `failed external launch releases its handoff immediately`() {
        val handoff = prepare()
        leases.cancel(handoff)
        assertTrue(leases.snapshot().active.isEmpty())
    }

    @Test fun `an open descriptor retains protection through long playback and pauses`() {
        prepare()
        val descriptor = requireNotNull(leases.acquire(uri) {})
        time += 8 * 60 * 60 * 1_000L
        assertEquals(1, leases.snapshot().active.size)
        assertNull(leases.snapshot().nextExpiry)
        descriptor.close()
        time += ExternalStreamLeases.REOPEN_GRACE_MS
        assertTrue(leases.snapshot().active.isEmpty())
    }

    @Test fun `concurrent descriptors release independently and closing twice is harmless`() {
        prepare()
        val first = requireNotNull(leases.acquire(uri) {})
        val second = requireNotNull(leases.acquire(uri) {})
        first.close()
        first.close()
        time += ExternalStreamLeases.HANDOFF_TIMEOUT_MS
        assertEquals(1, leases.snapshot().active.size)
        assertNull(leases.snapshot().nextExpiry)
        second.close()
        time += ExternalStreamLeases.REOPEN_GRACE_MS
        assertTrue(leases.snapshot().active.isEmpty())
    }

    @Test fun `metadata probe may close before the player opens its playback descriptor`() {
        prepare()
        requireNotNull(leases.acquire(uri) {}).close()
        time += ExternalStreamLeases.REOPEN_GRACE_MS - 1
        val playback = leases.acquire(uri) {}
        assertNotNull(playback)
        time += ExternalStreamLeases.HANDOFF_TIMEOUT_MS
        assertEquals(1, leases.snapshot().active.size)
        playback!!.close()
    }

    @Test fun `a failed second launch does not release an existing playback of the same URI`() {
        prepare()
        val playback = requireNotNull(leases.acquire(uri) {})
        val failedLaunch = prepare()
        leases.cancel(failedLaunch)
        time += ExternalStreamLeases.HANDOFF_TIMEOUT_MS
        assertEquals(1, leases.snapshot().active.size)
        playback.close()
    }

    @Test fun `unprepared or expired URIs do not start background services from the provider`() {
        assertNull(leases.acquire(uri) {})
        prepare()
        assertNull(leases.acquire("content://luna.stream/smb/another-file") {})
        time += ExternalStreamLeases.HANDOFF_TIMEOUT_MS
        assertNull(leases.acquire(uri) {})
    }

    @Test fun `system timeout cleanup tolerates a descriptor closing later`() {
        prepare()
        var changes = 0
        val playback = requireNotNull(leases.acquire(uri) { changes++ })
        leases.clear(ExternalStreamLeases.Purpose.MEDIA)
        playback.close()
        playback.close()
        assertTrue(leases.snapshot().active.isEmpty())
        assertEquals(1, changes)
    }

    @Test fun `generic transfer timeout does not remove protection from a playing video`() {
        prepare()
        val playback = requireNotNull(leases.acquire(uri) {})
        val transferUri = "content://luna.stream/smb/document"
        leases.prepare(transferUri, "document.pdf", ExternalStreamLeases.Purpose.TRANSFER)
        val transfer = requireNotNull(leases.acquire(transferUri) {})
        leases.clear(ExternalStreamLeases.Purpose.TRANSFER)
        assertEquals(listOf(ExternalStreamLeases.Purpose.MEDIA), leases.snapshot().active.map { it.purpose })
        transfer.close()
        assertEquals(1, leases.snapshot().active.size)
        playback.close()
    }
}
