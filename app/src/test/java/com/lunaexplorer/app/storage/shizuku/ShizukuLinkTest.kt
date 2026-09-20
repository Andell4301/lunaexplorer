package com.lunaexplorer.app.storage.shizuku

import com.lunaexplorer.app.storage.LocalRoot
import com.lunaexplorer.core.RootKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShizukuLinkTest {
    @get:Rule val temporary = TemporaryFolder()
    private val shizuku = FakeShizuku()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val link by lazy {
        ShizukuLink(shizuku, scope, { listOf(LocalRoot("primary", "Internal storage", temporary.root, RootKind.INTERNAL, followLinks = true)) },
            startTimeoutMillis = 300)
    }

    @After fun stop() = scope.cancel()

    private fun await(state: HelperState) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (link.state.value != state && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(state, link.state.value)
    }

    @Test fun `it says what stands in the way, one thing at a time`() {
        link.setEnabled(true)
        await(HelperState.NOT_INSTALLED)
        shizuku.installed = true; link.evaluate()
        await(HelperState.NOT_RUNNING)
        shizuku.start()
        await(HelperState.NEEDS_PERMISSION)
        assertNull("Nothing is started before Shizuku allows it", link.files)
        shizuku.grant()
        await(HelperState.READY)
        assertNotNull(link.files)
        assertEquals(1, shizuku.binds)
    }

    @Test fun `a user who told Shizuku not to ask again is sent to Shizuku, not asked`() {
        shizuku.refused = true
        shizuku.start(permitted = false)

        link.setEnabled(true, ask = true)

        await(HelperState.REFUSED)
        assertEquals("Asking would show nothing", 0, shizuku.requests)
    }

    @Test fun `switched off it asks Shizuku for nothing and lets a running helper go`() {
        shizuku.start(permitted = true)
        assertEquals(HelperState.OFF, link.state.value)
        assertEquals(0, shizuku.binds)

        link.setEnabled(true)
        await(HelperState.READY)
        link.setEnabled(false)

        await(HelperState.OFF)
        assertNull(link.files)
        assertEquals(1, shizuku.unbinds)
    }

    @Test fun `when Shizuku stops the helper is gone, and it comes back with Shizuku`() {
        shizuku.start(permitted = true)
        link.setEnabled(true)
        await(HelperState.READY)

        shizuku.stop()
        await(HelperState.NOT_RUNNING)
        assertNull(link.files)

        shizuku.start()
        await(HelperState.READY)
        assertEquals(2, shizuku.binds)
    }

    @Test fun `a helper that never starts is given up on, since Shizuku will not say so`() {
        shizuku.helper = { null }
        shizuku.start(permitted = true)

        link.setEnabled(true)

        await(HelperState.FAILED)
        assertEquals("And it is not asked for again until someone says to", 1, shizuku.binds)
        shizuku.helper = { MortalHelper() }
        link.retry()
        await(HelperState.READY)
    }

    @Test fun `a helper that dies as it starts is not restarted forever`() {
        shizuku.helper = { MortalHelper(answers = false) }
        shizuku.start(permitted = true)

        link.setEnabled(true)

        await(HelperState.FAILED)
        assertEquals(3, shizuku.binds)
    }

    @Test fun `a helper that dies later is started again`() {
        shizuku.start(permitted = true)
        link.setEnabled(true)
        await(HelperState.READY)

        shizuku.killHelper()

        val deadline = System.nanoTime() + 5_000_000_000
        while (shizuku.binds < 2 && System.nanoTime() < deadline) Thread.sleep(10)
        await(HelperState.READY)
        assertEquals(2, shizuku.binds)
    }

    @Test fun `word of some other helper's death does not cost the one in hand`() {
        shizuku.start(permitted = true)
        link.setEnabled(true)
        await(HelperState.READY)

        shizuku.echoLoss()
        link.evaluate()
        Thread.sleep(100)

        assertEquals(HelperState.READY, link.state.value)
        assertEquals(1, shizuku.binds)
    }

    @Test fun `allowing Luna inside Shizuku is noticed when the link is asked to look again`() {
        shizuku.refused = true
        shizuku.start(permitted = false)
        link.setEnabled(true)
        await(HelperState.REFUSED)

        shizuku.grantSilently()
        assertEquals("Shizuku sends no word of it", HelperState.REFUSED, link.state.value)
        link.evaluate()

        await(HelperState.READY)
    }
}
