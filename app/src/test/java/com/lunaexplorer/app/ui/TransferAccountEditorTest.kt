package com.lunaexplorer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.storage.RemoteReader
import com.lunaexplorer.app.storage.transfer.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class, qualifiers = "w393dp-h852dp-xhdpi")
class TransferAccountEditorTest {
    private val fingerprint = "SHA256:test-server-key"
    private val held = TransferAccount(id = "held", name = "SFTP server", host = "server.test", username = "user",
        hostKeyFingerprint = fingerprint)
    @Volatile private var presentedKey = fingerprint
    @Volatile private var receivedCredentials: TransferCredentials? = null
    private val connector = TransferConnector { account, credentials ->
        if (account.hostKeyFingerprint != presentedKey) {
            throw HostKeyRequired(presentedKey, "ssh-ed25519", account.hostKeyFingerprint.isNotEmpty())
        }
        receivedCredentials = credentials
        ProbeClient()
    }
    private val harness = BrowserViewModelHarness().withSftp(connector)
        .withSession { it.copy(transferAccounts = listOf(held)) }
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(harness).around(compose)
    private var open by mutableStateOf(true)
    private var prompts = 0
    private var prove = true
    private val actions = LunaActions({}, { _, _ -> }, { _, _, _ -> }, {}, {}, {},
        unlockVault = { done -> prompts++; if (prove) harness.vaultKeys.personPresent = true; done(prove) })

    private fun edit(account: TransferAccount, isNew: Boolean) {
        assertTrue(harness.awaitUntil { harness.state.ready })
        compose.setContent {
            MaterialTheme { if (open) TransferAccountEditor(account, isNew, harness.viewModel, actions) { open = false } }
        }
    }

    private fun field(label: String) = compose.onNode(hasText(label) and hasSetTextAction())

    @Test fun `SFTP requires explicit host key acceptance before saving`() {
        edit(held.copy(id = "new", hostKeyFingerprint = ""), true)
        field("Password").performScrollTo().performTextReplacement(" secret ")
        compose.onNodeWithText("Save").assertIsNotEnabled()

        compose.onNodeWithText("Test connection").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Trust host key").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("hostKeyFingerprint").assertTextEquals(fingerprint)
        compose.onNodeWithText("Save").assertIsNotEnabled()
        assertNull(receivedCredentials)

        compose.onNodeWithText("Trust host key").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Connected").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save").performClick()

        assertTrue(harness.awaitUntil { !open })
        assertEquals(fingerprint, harness.state.transferAccounts.first { it.id == "new" }.hostKeyFingerprint)
        assertEquals(" secret ", receivedCredentials?.password)
        assertEquals(" secret ", harness.graph.vault.secrets.value?.transferCredentials?.get("new")?.password)
    }

    @Test fun `changing an accepted endpoint requires a new host key decision`() {
        edit(held, false)
        compose.onNodeWithText("Save").assertIsEnabled()

        field("Server").performTextReplacement("other.test")

        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Test connection").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Trust host key").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        assertEquals(held, harness.state.transferAccounts.single())
    }

    @Test fun `a changed host key is displayed and never silently replaces the saved key`() {
        presentedKey = "SHA256:replacement-key"
        edit(held, false)

        compose.onNodeWithText("Test connection").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Host key changed").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("hostKeyFingerprint").assertTextEquals(presentedKey)
        assertEquals(fingerprint, harness.state.transferAccounts.single().hostKeyFingerprint)
        assertNull(receivedCredentials)
    }

    @Test fun `FTP saves exact field contents after renewing expired vault authentication`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        assertTrue(harness.viewModel.vault.setLocked(true))
        harness.vaultKeys.personPresent = false
        edit(TransferAccount(id = "ftp", protocol = TransferProtocol.FTP, host = "files.test", username = "user"), true)
        field("Name").performTextReplacement(" Files ")
        field("Root folder").performTextReplacement("/ Folder /")
        field("User").performScrollTo().performTextReplacement(" user ")
        field("Password").performScrollTo().performTextReplacement(" password ")

        compose.onNodeWithText("Save").performClick()

        assertTrue(harness.awaitUntil { !open })
        assertEquals(1, prompts)
        val saved = harness.state.transferAccounts.first { it.id == "ftp" }
        assertEquals(" Files ", saved.name)
        assertEquals("/ Folder /", saved.rootPath)
        assertEquals(" user ", saved.username)
        assertEquals(" password ", harness.graph.vault.secrets.value?.transferCredentials?.get("ftp")?.password)
    }

    @Test fun `failed credential authentication remains visible in the editor`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        assertTrue(harness.viewModel.vault.setLocked(true))
        harness.vaultKeys.personPresent = false
        prove = false
        edit(TransferAccount(id = "ftp", protocol = TransferProtocol.FTP, host = "files.test", username = "user"), true)
        field("Password").performScrollTo().performTextReplacement("password")

        compose.onNodeWithText("Save").performClick()

        compose.onNodeWithTag("editorResult").assertIsDisplayed()
        assertTrue(open)
        assertFalse(harness.state.transferAccounts.any { it.id == "ftp" })
    }

    @Test fun `private key import preserves its bytes and bounds oversized input`() {
        val key = "-----BEGIN PRIVATE KEY-----\n key bytes \n-----END PRIVATE KEY-----\n"
        assertEquals(key, readTransferPrivateKey(ByteArrayInputStream(key.toByteArray())))
        assertThrows(IllegalArgumentException::class.java) {
            readTransferPrivateKey(ByteArrayInputStream(ByteArray(65_537) { 'a'.code.toByte() }))
        }
    }

    private class ProbeClient : TransferClient {
        override fun stat(path: String) = TransferItem("", directory = true)
        override fun list(path: String) = emptyList<TransferItem>()
        override fun close() = Unit
        override fun mkdir(path: String): Unit = error("Unexpected mutation")
        override fun createFile(path: String): Unit = error("Unexpected mutation")
        override fun rename(from: String, to: String): Unit = error("Unexpected mutation")
        override fun deleteFile(path: String): Unit = error("Unexpected mutation")
        override fun deleteFolder(path: String): Unit = error("Unexpected mutation")
        override fun openReader(path: String): RemoteReader = error("Unexpected read")
        override fun write(path: String): OutputStream = error("Unexpected mutation")
        override fun setModified(path: String, millis: Long): Boolean = error("Unexpected mutation")
    }
}
