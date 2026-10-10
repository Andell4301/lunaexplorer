package com.lunaexplorer.app.ui

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.Screen
import com.lunaexplorer.app.storage.transfer.TransferAccount
import com.lunaexplorer.app.storage.transfer.TransferProtocol
import com.lunaexplorer.app.terminal.TerminalService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class TerminalUiTest : RobolectricBrowserUiTest() {
    private val server = TransferAccount(id = "ssh-terminal", name = "Shared SSH server", host = "ssh.test",
        username = "user", rootPath = "/work", hostKeyFingerprint = "SHA256:trusted")
    private val viewModel get() = compose.runOnUiThread {
        ViewModelProvider(compose.activity)[BrowserViewModel::class.java]
    }

    @Test fun terminalNotificationReturnsToTheTerminal() {
        awaitListing()
        val initialIntent = compose.runOnUiThread { compose.activity.intent }
        try {
            compose.runOnUiThread {
                ReflectionHelpers.callInstanceMethod<Unit>(compose.activity, "onNewIntent",
                    ClassParameter.from(Intent::class.java, Intent(initialIntent).setAction(TerminalService.ACTION_OPEN)))
            }
            compose.onNode(hasText("Local shell") and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.onNode(hasContentDescription("Files") and hasAnyAncestor(isDialog())).performClick()
            compose.waitForIdle()
            assertNull(viewModel.state.value.overlay)
        } finally {
            compose.runOnUiThread { compose.activity.intent = initialIntent }
        }
    }

    @Test fun homeTerminalOffersLocalAndSshConnectionsAndReturnsToFiles() {
        awaitListing()
        val model = viewModel
        compose.runOnUiThread {
            assertNull(model.servers.save(server, null))
            assertNull(model.servers.save(TransferAccount(id = "ftp-only", name = "FTP only", host = "ftp.test",
                protocol = TransferProtocol.FTP, port = 21, username = "user"), null))
            model.showScreen(Screen.HOME)
        }
        compose.onNodeWithTag("home-screen").performScrollToNode(hasText("Terminal"))
        compose.onNodeWithText("Terminal").performClick()

        compose.onNode(hasText("Local shell") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(hasText(server.name) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(hasText("FTP only") and hasAnyAncestor(isDialog())).assertDoesNotExist()
        compose.onNode(hasContentDescription("Files") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertNull(model.state.value.overlay)
    }

    @Test fun terminalCanOpenAndDismissTheSshAccountEditor() {
        awaitListing()
        val model = viewModel
        compose.runOnUiThread { model.showScreen(Screen.HOME) }
        compose.onNodeWithTag("home-screen").performScrollToNode(hasText("Terminal"))
        compose.onNodeWithText("Terminal").performClick()
        compose.onNodeWithText("Add an SSH server").performClick()

        compose.onNode(hasText("Server") and hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNode(hasContentDescription("Back") and hasAnyAncestor(isDialog())).performClick()

        compose.onNode(hasText("Local shell") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText("Add an SSH server").assertIsDisplayed()
    }

    @Test fun sshSettingsEditTheSameAccountUsedBySftp() {
        awaitListing()
        val model = viewModel
        compose.runOnUiThread { assertNull(model.servers.save(server, null)) }
        openSettingsPage("Network")
        compose.onNodeWithText("SSH").performScrollTo().performClick()
        compose.onNodeWithText("Add an SSH server").assertExists()
        compose.onNodeWithText("Network thumbnails").assertDoesNotExist()
        compose.onNodeWithText(server.name).performScrollTo().performClick()
        compose.onNode(hasText("Name") and hasSetTextAction()).performTextReplacement("Renamed SSH server")
        compose.onNodeWithText("Save").performClick()
        awaitCondition("SSH account edited", 10_000) {
            model.state.value.transferAccounts.single().name == "Renamed SSH server"
        }
        assertEquals(server.copy(name = "Renamed SSH server"), model.state.value.transferAccounts.single())

        compose.onNode(hasContentDescription("Back") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("SFTP").performScrollTo().performClick()
        compose.onNodeWithText("Renamed SSH server").assertExists()
        compose.onNodeWithText("Add an SFTP server").assertExists()
    }
}
