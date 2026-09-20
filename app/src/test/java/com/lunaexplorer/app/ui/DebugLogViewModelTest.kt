package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.storage.smb.FakeSmbConnector
import com.lunaexplorer.app.storage.smb.SmbAccount
import com.lunaexplorer.app.storage.smb.SmbFailure
import com.lunaexplorer.core.RootKind
import com.lunaexplorer.core.StorageError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class DebugLogViewModelTest {
    private val connector = FakeSmbConnector().apply {
        refuse = SmbFailure(StorageError.OFFLINE, "The server could not be found by that name")
    }
    private val account = SmbAccount(id = "nas", name = "NAS", host = "nas.local", guest = true)

    @get:Rule val harness = BrowserViewModelHarness()
        .startingWith { File(it, "one.txt").writeText("1"); File(it, "two.txt").writeText("2") }
        .withSmb(connector)
        .withSession { it.copy(smbAccounts = listOf(account)) }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state
    private val text get() = viewModel.debugLog.snapshot().text

    @Test fun `opening a folder is logged with what it held and how long it took`() {
        assertTrue(harness.awaitUntil { state.ready && state.entries.size == 2 })
        viewModel.setDebugLogging(true)

        viewModel.refresh()

        val listed = harness.awaitUntil { Regex("""Listed local:.+: 2 items in \d+ ms""").containsMatchIn(text) }
        assertTrue(text, listed)
        assertTrue("Recording says which build and device it is", text.contains("Recording started · Luna"))
    }

    @Test fun `a folder that cannot be opened is logged with its reason`() {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.setDebugLogging(true)

        viewModel.navigateRoot(state.roots.single { it.kind == RootKind.NETWORK })

        val logged = harness.awaitUntil { Regex("""Could not open smb:nas:: after \d+ ms: OFFLINE""").containsMatchIn(text) }
        assertTrue(text, logged)
    }

    @Test fun `the log is saved where it is asked to go, and never over a file already there`() = runBlocking {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.setDebugLogging(true)
        val folder = harness.directory.absolutePath

        val saved = viewModel.saveDebugLog(folder, "saved-log.txt")

        assertTrue("Saved: $saved", saved.isSuccess)
        assertTrue(File(harness.directory, "saved-log.txt").readText().contains("Recording started"))
        val again = viewModel.saveDebugLog(folder, "saved-log.txt")
        assertTrue(again.exceptionOrNull()?.message.orEmpty().contains("already there"))
        val nowhere = viewModel.saveDebugLog("/nowhere/luna/can/reach", "log.txt")
        assertTrue(nowhere.exceptionOrNull()?.message.orEmpty(), nowhere.isFailure)
        val missing = viewModel.saveDebugLog(File(harness.directory, "not-there").absolutePath, "log.txt")
        assertEquals("Said plainly, not as the path it failed on", "No such folder", missing.exceptionOrNull()?.message)
        val badName = viewModel.saveDebugLog(folder, "a/b.txt")
        assertTrue(badName.isFailure)
    }

    @Test fun `resetting settings stops recording`() {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.setDebugLogging(true)

        viewModel.resetSettings()

        assertFalse(viewModel.debugLog.enabled)
    }
}
