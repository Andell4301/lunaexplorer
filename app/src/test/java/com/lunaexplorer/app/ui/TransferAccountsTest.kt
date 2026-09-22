package com.lunaexplorer.app.ui

import android.net.Uri
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.data.TransferCodec
import com.lunaexplorer.app.storage.transfer.FakeTransferConnector
import com.lunaexplorer.app.storage.transfer.TransferAccount
import com.lunaexplorer.app.storage.transfer.TransferClient
import com.lunaexplorer.app.storage.transfer.TransferConnector
import com.lunaexplorer.app.storage.transfer.TransferCredentials
import com.lunaexplorer.app.storage.transfer.TransferProtocol
import com.lunaexplorer.core.NodeRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class TransferAccountsTest {
    private val original = TransferAccount(id = "server", name = "Server", host = "old-host",
        protocol = TransferProtocol.SFTP, username = "user", hostKeyFingerprint = "SHA256:trusted")
    private var connector: TransferConnector? = null
    @get:Rule val harness = BrowserViewModelHarness().withSession { it.copy(transferAccounts = listOf(original)) }
        .withSftp(TransferConnector { account, credentials ->
            connector?.connect(account, credentials) ?: throw CancellationException("Cancelled connection")
        })

    @Test fun `a failed secret write keeps the account and credentials unchanged`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        val old = TransferCredentials(password = "old password", privateKey = "old key", passphrase = "old passphrase")
        assertNull(harness.viewModel.servers.save(original, old))
        assertTrue(harness.viewModel.vault.setLocked(true))
        harness.vaultKeys.personPresent = false

        val failure = harness.viewModel.servers.save(original.copy(host = "new-host"), TransferCredentials(password = "new"))

        assertNotNull(failure)
        assertEquals(listOf(original), harness.state.transferAccounts)
        assertEquals(old, harness.graph.vault.secrets.value!!.transferCredentials[original.id])
    }

    @Test fun `metadata edits retain credentials and names and paths exactly as typed`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        val credentials = TransferCredentials(password = " secret ")
        assertNull(harness.viewModel.servers.save(original, credentials))
        val changed = original.copy(name = " Server ", rootPath = "/ Folder /", username = " user ")

        assertNull(harness.viewModel.servers.save(changed, null))

        assertEquals(changed, harness.state.transferAccounts.single())
        assertEquals(credentials, harness.graph.vault.secrets.value!!.transferCredentials[original.id])
        assertTrue(harness.awaitUntil { harness.state.roots.any { it.ref.provider == "sftp" } })
    }

    @Test fun `removing an account removes its encrypted credentials and its root`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        assertNull(harness.viewModel.servers.save(original, TransferCredentials(privateKey = "key", passphrase = "passphrase")))

        assertNull(harness.viewModel.servers.remove(original.id))

        assertTrue(harness.state.transferAccounts.isEmpty())
        assertFalse(harness.graph.vault.secrets.value!!.transferCredentials.containsKey(original.id))
        assertTrue(harness.awaitUntil { harness.state.roots.none { it.ref.provider == "sftp" } })
    }

    @Test fun `an untrusted SFTP endpoint cannot be saved`() {
        assertTrue(harness.awaitUntil { harness.state.ready })

        assertNotNull(harness.viewModel.servers.save(original.copy(host = "unknown", hostKeyFingerprint = ""), null))

        assertEquals(listOf(original), harness.state.transferAccounts)
    }

    @Test fun `invalid server metadata cannot replace an account or its stored credentials`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        val credentials = TransferCredentials(password = "kept")
        assertNull(harness.viewModel.servers.save(original, credentials))

        assertNotNull(harness.viewModel.servers.save(original.copy(id = "broken:id"), TransferCredentials(password = "new")))
        assertNotNull(harness.viewModel.servers.save(original.copy(port = 0), TransferCredentials(password = "new")))

        assertEquals(listOf(original), harness.state.transferAccounts)
        assertEquals(mapOf(original.id to credentials), harness.graph.vault.secrets.value!!.transferCredentials)
    }

    @Test fun `connection cancellation is never converted into a connection failure`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        assertThrows(CancellationException::class.java) {
            runBlocking { harness.viewModel.servers.test(original, TransferCredentials()) }
        }
    }

    @Test fun `connections opened while imported credentials replace a session use the new credentials`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        assertTrue(harness.viewModel.vault.open())
        harness.graph.vault.save(harness.graph.vault.secrets.value!!.copy(
            transferCredentials = mapOf(original.id to TransferCredentials(password = "old")),
        ), locked = false)
        val server = FakeTransferConnector()
        val passwords = CopyOnWriteArrayList<String>()
        val whenClosed = AtomicReference<(() -> Unit)?>(null)
        connector = TransferConnector { account, credentials ->
            passwords += credentials.password
            val client = server.connect(account, credentials)
            object : TransferClient by client {
                override fun close() {
                    client.close()
                    whenClosed.getAndSet(null)?.invoke()
                }
            }
        }
        val provider = harness.graph.sftp
        val root = NodeRef("sftp", "${original.id}:")
        runBlocking { provider.stat(root) }
        val transfer = harness.viewModel.transfer
        val id = "network.transferCredentials"
        val document = TransferCodec.export(transfer.snapshot().copy(
            transferCredentials = mapOf(original.id to TransferCredentials(password = "new")),
        ), setOf(id), "test", 0)
        val file = File(harness.directory, "credentials.json").apply { writeText(TransferCodec.encode(document)) }
        var opened = false
        transfer.openForImport(Uri.fromFile(file)) { opened = it }
        assertTrue(harness.awaitUntil { opened && transfer.preview.value != null })
        whenClosed.set { runBlocking { provider.stat(root) }; Unit }

        var done = false
        transfer.import(setOf(id), emptyMap()) { done = true }
        assertTrue(harness.awaitUntil { done })
        runBlocking { provider.stat(root) }

        try {
            assertEquals(listOf("old", "new"), passwords)
            assertEquals(2, server.sessionsOpened.get())
        } finally { provider.disconnect(original.id) }
    }
}
