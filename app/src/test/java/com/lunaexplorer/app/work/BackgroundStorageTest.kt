package com.lunaexplorer.app.work

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.lunaexplorer.app.AppGraph
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.Preferences
import com.lunaexplorer.app.storage.MemoryVaultKeys
import com.lunaexplorer.app.storage.PathProbe
import com.lunaexplorer.app.storage.Secrets
import com.lunaexplorer.app.storage.b2.B2Account
import com.lunaexplorer.app.storage.b2.FakeB2Backend
import com.lunaexplorer.app.storage.b2.FakeB2Connector
import com.lunaexplorer.app.storage.shizuku.FakeShizuku
import com.lunaexplorer.app.storage.smb.SmbAccount
import com.lunaexplorer.app.storage.transfer.TransferAccount
import com.lunaexplorer.app.storage.transfer.TransferCredentials
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackgroundStorageTest {
    private lateinit var graph: AppGraph
    private val keys = MemoryVaultKeys()
    private val connector = FakeB2Connector(FakeB2Backend("media").apply { put("media", "remote.txt", "content") })
    private val smb = SmbAccount(id = "lan", name = "LAN", host = "files.test", share = "files")
    private val b2 = B2Account(id = "cloud", name = "Cloud", keyId = "key-id", bucket = "media")
    private val transfer = TransferAccount(id = "ssh", name = "SSH", host = "ssh.test", username = "user")
    private val session = BrowserState(smbAccounts = listOf(smb), b2Accounts = listOf(b2),
        transferAccounts = listOf(transfer), preferences = Preferences(showAppData = true, showDeviceRoot = false))
    private val secrets = Secrets(smbPasswords = mapOf(smb.id to "smb-secret"), b2Keys = mapOf(b2.id to "b2-secret"),
        transferCredentials = mapOf(transfer.id to TransferCredentials(password = "ssh-secret")))

    @Before fun createGraph() {
        graph = AppGraph(ApplicationProvider.getApplicationContext(), probe = PathProbe.OF_FILESYSTEM,
            b2Connector = connector, vaultKeys = keys, shizukuGateway = FakeShizuku())
    }

    @After fun closeGraph() {
        graph.b2.disconnect(b2.id)
        graph.procedures.close()
        graph.database.close()
        graph.debugLog.close()
    }

    @Test fun `a background run restores storage accounts roots and unlocked credentials without a browser`() = runBlocking {
        graph.database.saveSession(session)
        graph.vault.save(secrets, locked = false)
        graph.vault.close()

        graph.prepareForWork()

        assertEquals(session.smbAccounts, graph.smbAccounts)
        assertEquals(session.b2Accounts, graph.b2Accounts)
        assertEquals(session.transferAccounts, graph.transferAccounts)
        assertTrue(graph.appDataVisible)
        assertTrue(graph.local.definitions().any { it.id == "appdata" })
        assertFalse(graph.local.definitions().any { it.id == "device" })
        val root = graph.b2.roots().single().ref
        assertEquals(listOf("remote.txt"), graph.b2.list(root, complete = true).toList().flatten().map { it.name })
        assertEquals(listOf("b2-secret"), connector.keysSeen)
        assertEquals(secrets, graph.vault.secrets.value)
    }

    @Test fun `background startup restores locked accounts without unlocking their vault`() = runBlocking {
        graph.database.saveSession(session.copy(vaultLocked = true))
        graph.vault.save(secrets, locked = true)
        graph.vault.close()
        keys.personPresent = false

        graph.prepareForWork()

        assertEquals(listOf(b2), graph.b2Accounts)
        assertNull(graph.vault.secrets.value)
        val root = graph.b2.roots().single().ref
        try {
            graph.b2.list(root, complete = true).toList()
            fail("Locked network storage must refuse a background read")
        } catch (error: StorageException) {
            assertEquals(StorageError.AUTH, error.reason)
        }
        assertTrue(connector.keysSeen.isEmpty())
    }

    @Test fun `later background runs retain current accounts root preferences and vault state`() = runBlocking {
        graph.database.saveSession(session)
        graph.vault.save(secrets, locked = false)
        graph.vault.close()
        graph.prepareForWork()

        graph.smbAccounts = emptyList()
        graph.b2Accounts = listOf(b2.copy(bucket = "another"))
        graph.transferAccounts = listOf(transfer.copy(host = "changed.test"))
        graph.appDataVisible = false
        graph.refreshRoots(includeDeviceRoot = true)
        graph.vault.close()
        graph.prepareForWork()

        assertTrue(graph.smbAccounts.isEmpty())
        assertEquals(listOf(b2.copy(bucket = "another")), graph.b2Accounts)
        assertEquals(listOf(transfer.copy(host = "changed.test")), graph.transferAccounts)
        assertFalse(graph.appDataVisible)
        assertFalse(graph.local.definitions().any { it.id == "appdata" })
        assertTrue(graph.local.definitions().any { it.id == "device" })
        assertNull(graph.vault.secrets.value)
    }
}
