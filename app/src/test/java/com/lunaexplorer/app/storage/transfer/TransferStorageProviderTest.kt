package com.lunaexplorer.app.storage.transfer

import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Feature
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.ReadBudget
import com.lunaexplorer.core.ReadBudgetExceeded
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TransferStorageProviderTest {
    private val account = TransferAccount(id = "test", name = "Server", host = "test.local")
    private val connector = FakeTransferConnector()
    private val provider = makeProvider()
    private val root = NodeRef("sftp", "test:")
    private fun at(path: String) = NodeRef("sftp", "test:$path")
    private fun makeProvider(protocol: TransferProtocol = TransferProtocol.SFTP) = TransferStorageProvider(
        protocol.providerId, { listOf(account.copy(protocol = protocol)) }, { TransferCredentials(password = "secret") }, connector,
    )
    private suspend fun refused(reason: StorageError, action: suspend () -> Unit) {
        try { action(); fail("Expected $reason") } catch (error: StorageException) { assertEquals(reason, error.reason) }
    }

    @Test fun `FTP can create rename and delete while reads stay sequential`() = runBlocking {
        val ftp = makeProvider(TransferProtocol.FTP)
        connector.put("file.txt", "contents")
        connector.folder("folder")
        val ftpRoot = ftp.roots().single()
        assertFalse(ftpRoot.readOnly)
        assertEquals(setOf("file.txt", "folder"), ftp.list(ftpRoot.ref, complete = true).toList().flatten().map { it.name }.toSet())
        assertTrue(Capability.CREATE in ftp.stat(ftpRoot.ref).capabilities)
        assertTrue(Capability.REPLACE in ftp.stat(ftpRoot.ref).capabilities)
        assertFalse(Capability.ATOMIC_REPLACE in ftp.stat(ftpRoot.ref).capabilities)
        val file = ftp.child(ftpRoot.ref, "file.txt")!!
        assertTrue(Capability.READ in file.capabilities)
        assertFalse(Capability.WRITE in file.capabilities)
        assertEquals("contents", ftp.openRead(file.ref).use { it.readBytes().decodeToString() })
        assertEquals(ftpRoot.ref, ftp.parentOf(file.ref))
        assertNull(ftp.openChannel(file.ref))
        val created = ftp.create(ftpRoot.ref, "new", false)
        assertTrue(Capability.WRITE in created.capabilities)
        ftp.openWrite(created.ref).use { it.write("added".toByteArray()) }
        val renamed = ftp.rename(created.ref, "renamed")
        assertEquals("added", ftp.openRead(renamed.ref).use { it.readBytes().decodeToString() })
        ftp.delete(renamed.ref)
        assertNull(ftp.child(ftpRoot.ref, "renamed"))
        refused(StorageError.UNSUPPORTED) { ftp.setModified(file.ref, 123) }
        assertEquals("contents", connector.text("file.txt"))
        assertEquals(connector.sessionsOpened.get(), connector.sessionsClosed.get())
    }

    @Test fun `names and configured account values are kept exactly`() = runBlocking {
        connector.put(" spaced :名.txt ", "data")
        val item = provider.list(root).toList().flatten().single()
        assertEquals(" spaced :名.txt ", item.name)
        assertEquals(item.ref, provider.child(root, item.name)!!.ref)
        assertEquals(item.ref, provider.stat(item.ref).ref)
        assertEquals("data", provider.openRead(item.ref).use { it.readBytes().decodeToString() })
        assertEquals(account, connector.lastAccount)
    }

    @Test fun `malformed and removed account references fail before connecting`() = runBlocking {
        val bad = listOf("test", "test:/absolute", "test:../outside", "test:folder/../outside", "test:folder//file", "test:folder/", "test:./file", "gone:")
        for (key in bad) {
            val ref = NodeRef("sftp", key)
            refused(StorageError.NOT_FOUND) { provider.stat(ref) }
            assertFalse(provider.isDescendant(ref, root))
        }
        refused(StorageError.NOT_FOUND) { provider.stat(NodeRef("ftp", "test:")) }
        refused(StorageError.INVALID_NAME) { provider.child(root, "../outside") }
        assertEquals(0, connector.sessionsOpened.get())
    }

    @Test fun `server names cannot inject references outside the root`() = runBlocking {
        connector.extraListed = TransferItem("../outside", directory = false)
        refused(StorageError.INVALID_NAME) { provider.list(root, complete = true).toList() }
        assertEquals(connector.sessionsOpened.get(), connector.sessionsClosed.get())
    }

    @Test fun `links remain leaves and cannot be followed by reads or descendants`() = runBlocking {
        connector.link("outside")
        val linked = provider.stat(at("outside"))
        assertTrue(linked.link)
        assertFalse(linked.directory)
        assertFalse(Capability.LIST in linked.capabilities)
        assertFalse(Capability.READ in linked.capabilities)
        refused(StorageError.UNSUPPORTED) { provider.list(linked.ref).toList() }
        refused(StorageError.UNSUPPORTED) { provider.openRead(linked.ref) }
        refused(StorageError.UNSUPPORTED) { provider.stat(at("outside/secret")) }
        provider.delete(linked.ref)
        assertNull(provider.child(root, "outside"))
    }

    @Test fun `exclusive creation preserves a name that races the existence check`() = runBlocking {
        connector.createRacer = true
        refused(StorageError.CONFLICT) { provider.create(root, "new.txt", false) }
        assertEquals("racer", connector.text("new.txt"))
        assertEquals(1, connector.creates)
    }

    @Test fun `publication keeps staged data when a target races the existence check`() = runBlocking {
        connector.put("stage", "complete")
        connector.renameRacer = true
        refused(StorageError.CONFLICT) { provider.commit(at("stage"), root, "target") }
        assertEquals("complete", connector.text("stage"))
        assertEquals("racer", connector.text("target"))
        assertEquals(1, connector.renameCalls)
    }

    @Test fun `a lost mutation reply is never retried or recovered by deletion`() = runBlocking {
        connector.put("stage", "complete")
        connector.renameFailsAfter = true
        refused(StorageError.DISCONNECTED) { provider.commit(at("stage"), root, "target") }
        assertEquals("complete", connector.text("target"))
        assertNull(connector.text("stage"))
        assertEquals(1, connector.renameCalls)
    }

    @Test fun `replacement records and retains the old file until the staged file is published`() = runBlocking {
        for (protocol in TransferProtocol.entries) {
            val remote = makeProvider(protocol)
            val destination = remote.roots().single().ref
            connector.put("stage", "new")
            connector.put("target", "original")
            val existing = remote.child(destination, "target")!!
            val staged = remote.child(destination, "stage")!!
            var backup: NodeRef? = null
            connector.beforeRename = { from, to ->
                assertTrue("Record the backup before changing any name", backup != null)
                if (from == "stage") {
                    assertNull(connector.text("target"))
                    assertEquals("original", connector.text(backup!!.key.substringAfter(':')))
                    assertEquals("target", to)
                }
            }
            connector.beforeDelete = {
                assertEquals("new", connector.text("target"))
                assertNull(connector.text("stage"))
            }

            val published = remote.commit(staged.ref, destination, "target", existing) { retained, _ -> backup = retained }

            assertEquals(existing.ref, published.ref)
            assertEquals("new", connector.text("target"))
            assertNull(connector.text("stage"))
            assertNull(connector.text(backup!!.key.substringAfter(':')))
            assertFalse(Capability.WRITE in existing.capabilities)
            assertTrue(Capability.REPLACE in remote.stat(destination).capabilities)
            assertFalse(Capability.ATOMIC_REPLACE in remote.stat(destination).capabilities)
            remote.disconnect(account.id)
        }
    }

    @Test fun `a target changed before replacement keeps both files and performs no rename`() = runBlocking {
        connector.put("stage", "new")
        connector.put("target", "original")
        val existing = provider.stat(at("target"))
        connector.put("target", "changed since listing")

        refused(StorageError.CONFLICT) { provider.commit(at("stage"), root, "target", existing) }

        assertEquals("changed since listing", connector.text("target"))
        assertEquals("new", connector.text("stage"))
        assertEquals(0, connector.renameCalls)
    }

    @Test fun `replacement refuses a different target folder or link`() = runBlocking {
        connector.put("stage", "new")
        connector.put("target", "original")
        val wrong = provider.stat(at("stage"))
        refused(StorageError.CONFLICT) { provider.commit(at("stage"), root, "target", wrong) }
        connector.folder("target")
        refused(StorageError.UNSUPPORTED) { provider.commit(at("stage"), root, "target", provider.stat(at("target"))) }
        connector.link("target")
        refused(StorageError.UNSUPPORTED) { provider.commit(at("stage"), root, "target", provider.stat(at("target"))) }
        assertEquals(0, connector.renameCalls)
        assertEquals("new", connector.text("stage"))
    }

    @Test fun `a lost backup rename reply leaves its recorded copy without another mutation`() = runBlocking {
        connector.put("stage", "new")
        connector.put("target", "original")
        val existing = provider.stat(at("target"))
        connector.renameFailsAfterCall = 1
        var backup: NodeRef? = null

        refused(StorageError.DISCONNECTED) {
            provider.commit(at("stage"), root, "target", existing) { retained, _ -> backup = retained }
        }

        assertEquals("original", connector.text(backup!!.key.substringAfter(':')))
        assertEquals("new", connector.text("stage"))
        assertNull(connector.text("target"))
        assertEquals(1, connector.renameCalls)
        assertTrue(connector.deletedPaths.isEmpty())
    }

    @Test fun `a lost publication reply preserves the backup and never replays or restores`() = runBlocking {
        connector.put("stage", "new")
        connector.put("target", "original")
        val existing = provider.stat(at("target"))
        connector.renameFailsAfterCall = 2
        var backup: NodeRef? = null

        refused(StorageError.DISCONNECTED) {
            provider.commit(at("stage"), root, "target", existing) { retained, _ -> backup = retained }
        }

        assertEquals("original", connector.text(backup!!.key.substringAfter(':')))
        assertEquals("new", connector.text("target"))
        assertNull(connector.text("stage"))
        assertEquals(2, connector.renameCalls)
        assertTrue(connector.deletedPaths.isEmpty())
    }

    @Test fun `a lost lookup after publication reports its backup without another mutation`() = runBlocking {
        connector.put("stage", "new")
        connector.put("target", "original")
        val existing = provider.stat(at("target"))
        var backup = ""
        connector.beforeRename = { from, to -> if (from == "target") backup = to }
        connector.beforeStat = { path ->
            if (path == "target" && connector.renameCalls == 2) {
                throw StorageException(StorageError.DISCONNECTED, "Lookup reply lost")
            }
        }

        try {
            provider.commit(at("stage"), root, "target", existing)
            fail("Expected failure")
        } catch (error: StorageException) {
            assertEquals(StorageError.DISCONNECTED, error.reason)
            assertTrue("Recovery must be possible without an operation callback", error.message.orEmpty().contains(backup))
        }

        assertEquals("original", connector.text(backup))
        assertEquals("new", connector.text("target"))
        assertEquals(2, connector.renameCalls)
        assertTrue(connector.deletedPaths.isEmpty())
    }

    @Test fun `a previously seen link cannot authorize replacing a new regular file`() = runBlocking {
        connector.put("stage", "new")
        connector.link("target")
        val existing = provider.stat(at("target"))
        connector.put("target", "")

        refused(StorageError.UNSUPPORTED) { provider.commit(at("stage"), root, "target", existing) }

        assertEquals("", connector.text("target"))
        assertEquals("new", connector.text("stage"))
        assertEquals(0, connector.renameCalls)
    }

    @Test fun `a rejected publication restores the previous file without deleting anything`() = runBlocking {
        connector.put("stage", "new")
        connector.put("target", "original")
        val existing = provider.stat(at("target"))
        connector.beforeRename = { from, _ ->
            if (from == "stage") throw StorageException(StorageError.PERMISSION, "Rejected")
        }

        refused(StorageError.PERMISSION) { provider.commit(at("stage"), root, "target", existing) }

        assertEquals("original", connector.text("target"))
        assertEquals("new", connector.text("stage"))
        assertEquals(3, connector.renameCalls)
        assertTrue(connector.deletedPaths.isEmpty())
    }

    @Test fun `failed backup cleanup reports the old copy without failing publication`() = runBlocking {
        connector.put("stage", "new")
        connector.put("target", "original")
        val existing = provider.stat(at("target"))
        connector.beforeDelete = { throw StorageException(StorageError.PERMISSION, "Cannot remove backup") }
        val retained = mutableListOf<NodeRef>()

        val published = provider.commit(at("stage"), root, "target", existing) { ref, _ -> retained += ref }

        assertEquals(existing.ref, published.ref)
        assertEquals("new", connector.text("target"))
        assertTrue(retained.isNotEmpty())
        assertEquals("original", connector.text(retained.last().key.substringAfter(':')))
        assertTrue(connector.deletedPaths.isEmpty())
    }

    @Test fun `failed backup cleanup without a callback identifies the retained copy`() = runBlocking {
        connector.put("stage", "new")
        connector.put("target", "original")
        val existing = provider.stat(at("target"))
        var backup = ""
        connector.beforeDelete = {
            backup = it
            throw StorageException(StorageError.PERMISSION, "Cannot remove backup")
        }

        try {
            provider.commit(at("stage"), root, "target", existing)
            fail("Expected failure")
        } catch (error: StorageException) {
            assertEquals(StorageError.PERMISSION, error.reason)
            assertTrue(error.message.orEmpty().contains(backup))
        }

        assertEquals("original", connector.text(backup))
        assertEquals("new", connector.text("target"))
        assertNull(connector.text("stage"))
        assertEquals(2, connector.renameCalls)
        assertTrue(connector.deletedPaths.isEmpty())
    }

    @Test fun `a concurrent publication keeps the new occupant and retains the previous backup`() = runBlocking {
        connector.put("stage", "new")
        connector.put("target", "original")
        val existing = provider.stat(at("target"))
        connector.beforeRename = { from, _ -> if (from == "stage") connector.put("target", "another client") }
        var backup: NodeRef? = null

        refused(StorageError.CONFLICT) {
            provider.commit(at("stage"), root, "target", existing) { retained, _ -> backup = retained }
        }

        assertEquals("another client", connector.text("target"))
        assertEquals("original", connector.text(backup!!.key.substringAfter(':')))
        assertEquals("new", connector.text("stage"))
        assertEquals(2, connector.renameCalls)
        assertTrue(connector.deletedPaths.isEmpty())
    }

    @Test fun `cancelling publication retains the recorded old copy without further mutation`() = runBlocking {
        connector.put("stage", "new")
        connector.put("target", "original")
        val existing = provider.stat(at("target"))
        connector.beforeRename = { from, _ -> if (from == "stage") throw CancellationException("Stopped") }
        var backup: NodeRef? = null

        try {
            provider.commit(at("stage"), root, "target", existing) { retained, _ -> backup = retained }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }

        assertEquals("original", connector.text(backup!!.key.substringAfter(':')))
        assertEquals("new", connector.text("stage"))
        assertNull(connector.text("target"))
        assertEquals(2, connector.renameCalls)
        assertTrue(connector.deletedPaths.isEmpty())
    }

    @Test fun `opening a nonempty file for writing cannot truncate it`() = runBlocking {
        connector.put("important", "original")
        refused(StorageError.CONFLICT) { provider.openWrite(at("important")) }
        assertEquals("original", connector.text("important"))
        assertEquals(0, connector.writeOpens)
    }

    @Test fun `streams return their connection after closing the handle only once`() = runBlocking {
        connector.put("file", "x")
        val stream = provider.openRead(at("file"))
        assertEquals(1, connector.sessionsOpened.get())
        assertEquals(0, connector.sessionsClosed.get())
        stream.close()
        stream.close()
        assertEquals(0, connector.sessionsClosed.get())
        assertEquals(1, connector.handlesClosed.get())
        provider.stat(at("file"))
        assertEquals(1, connector.sessionsOpened.get())
        provider.disconnect(account.id)
        assertEquals(1, connector.sessionsClosed.get())
    }

    @Test fun `folder browsing and successive transfers reuse an authenticated session`() = runBlocking {
        connector.put("source", "contents")
        provider.stat(root)
        provider.list(root).toList()
        provider.child(root, "source")
        val target = provider.create(root, "target", false)
        provider.openWrite(target.ref).use { it.write("contents".toByteArray()) }
        assertEquals("contents", provider.openRead(target.ref).use { it.readBytes().decodeToString() })
        provider.rename(target.ref, "renamed")
        provider.delete(at("renamed"))
        assertEquals(1, connector.sessionsOpened.get())
        provider.disconnect(account.id)
        assertEquals(1, connector.sessionsClosed.get())
    }

    @Test fun `active streams have separate sessions and leave browsing available`() = runBlocking {
        connector.put("source", "contents")
        val first = provider.openRead(at("source"))
        val second = provider.openRead(at("source"))
        provider.list(root).toList()
        assertEquals(3, connector.sessionsOpened.get())
        assertEquals("contents", first.readBytes().decodeToString())
        assertEquals("contents", second.readBytes().decodeToString())
        first.close()
        second.close()
        provider.stat(root)
        assertEquals(3, connector.sessionsOpened.get())
        provider.disconnect(account.id)
        assertEquals(3, connector.sessionsClosed.get())
    }

    @Test fun `a dropped idle connection is replaced before the next operation`() = runBlocking {
        provider.stat(root)
        connector.dropSessions()
        provider.list(root).toList()
        assertEquals(2, connector.sessionsOpened.get())
        assertEquals(1, connector.sessionsClosed.get())
        provider.disconnect(account.id)
    }

    @Test fun `failed writes and failed completion discard their sessions without retrying`() = runBlocking {
        for (onClose in listOf(false, true)) {
            val target = provider.create(root, "file$onClose", false)
            val stream = provider.openWrite(target.ref)
            connector.failWrite = !onClose
            connector.failWriteClose = onClose
            if (onClose) {
                stream.write("contents".toByteArray())
                assertThrows(IOException::class.java) { stream.close() }
            } else {
                assertThrows(IOException::class.java) { stream.write("contents".toByteArray()) }
                stream.close()
            }
            assertEquals(connector.sessionsOpened.get(), connector.sessionsClosed.get())
            assertEquals(connector.handlesOpened.get(), connector.handlesClosed.get())
            connector.failWrite = false
            connector.failWriteClose = false
        }
        assertEquals(2, connector.writeOpens)
    }

    @Test fun `idle session retention stays bounded after concurrent transfers`() = runBlocking {
        connector.put("source", "contents")
        val streams = List(8) { provider.openRead(at("source")) }
        streams.forEach { it.close() }
        assertTrue(connector.sessionsClosed.get() > 0)
        assertTrue(connector.sessionsClosed.get() < connector.sessionsOpened.get())
        provider.disconnect(account.id)
        assertEquals(connector.sessionsOpened.get(), connector.sessionsClosed.get())
    }

    @Test fun `changed account settings never borrow the previous server session`() = runBlocking {
        var configured = account
        val source = TransferStorageProvider("sftp", { listOf(configured) }, { TransferCredentials(password = "secret") }, connector)
        source.stat(root)
        configured = account.copy(host = "another.local")
        source.stat(root)
        assertEquals(2, connector.sessionsOpened.get())
        assertEquals(configured, connector.lastAccount)
        source.disconnect(account.id)
        assertEquals(2, connector.sessionsClosed.get())
    }

    @Test fun `disconnect rejects an authentication which was already in progress`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        connector.connectEntered = entered
        connector.connectGate = release
        val opening = async(Dispatchers.Default) {
            refused(StorageError.DISCONNECTED) { provider.stat(root) }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        provider.disconnect(account.id)
        release.countDown()
        opening.await()
        assertEquals(1, connector.sessionsOpened.get())
        assertEquals(1, connector.sessionsClosed.get())
    }

    @Test fun `cancellation during read and write acquisition closes each handle and session`() = runBlocking {
        for (writing in listOf(false, true)) {
            connector.put("file", "")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            connector.openEntered = entered
            connector.openGate = release
            val loading = async(Dispatchers.Default) {
                if (writing) provider.openWrite(at("file")) else provider.openRead(at("file"))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            loading.cancel()
            release.countDown()
            loading.join()
            assertEquals(connector.handlesOpened.get(), connector.handlesClosed.get())
            assertEquals(connector.sessionsOpened.get(), connector.sessionsClosed.get())
        }
    }

    @Test fun `SFTP seeks and FTP streams charge only requested payload without read ahead`() = runBlocking {
        connector.put("file", "0123456789")
        val budget = ReadBudget(5)
        provider.openChannel(at("file"), budget)!!.use { channel ->
            channel.position(7)
            val one = ByteBuffer.allocate(1)
            assertEquals(1, channel.read(one))
            assertEquals('7'.code.toByte(), one.array()[0])
            assertEquals(1L, connector.bytesRead)
            channel.position(2)
            assertEquals(2, channel.read(ByteBuffer.allocateDirect(2)))
        }
        val ftp = makeProvider(TransferProtocol.FTP)
        ftp.openRead(NodeRef("ftp", "test:file"), budget).use { stream ->
            assertEquals(2, stream.read(ByteArray(8)))
            assertEquals(5L, connector.bytesRead)
            assertThrows(ReadBudgetExceeded::class.java) { stream.read() }
        }
        assertEquals(5L, budget.bytesRead)
    }

    @Test fun `known SFTP length returns EOF at the budget while FTP verifies its transfer completion`() = runBlocking {
        connector.put("file", "123")
        for (protocol in TransferProtocol.entries) {
            val source = makeProvider(protocol)
            val budget = ReadBudget(if (protocol == TransferProtocol.SFTP) 3 else 4)
            source.openRead(NodeRef(protocol.providerId, "test:file"), budget).use { stream ->
                assertEquals("123", stream.readBytes().decodeToString())
                assertEquals(-1, stream.read())
                assertEquals(3L, budget.bytesRead)
            }
        }
    }

    @Test fun `cancelled budgets stop reads before further network traffic`() = runBlocking {
        connector.put("file", "123456")
        val cancelled = AtomicBoolean()
        val budget = ReadBudget(6) { if (cancelled.get()) throw CancellationException() }
        provider.openRead(at("file"), budget).use { stream ->
            assertEquals('1'.code, stream.read())
            cancelled.set(true)
            assertThrows(CancellationException::class.java) { stream.read() }
        }
        assertEquals(1L, connector.bytesRead)
        assertEquals(1L, budget.bytesRead)
    }

    @Test fun `a failed read retains its reservation and never reconnects`() = runBlocking {
        connector.put("file", "123456")
        val budget = ReadBudget(6)
        provider.openRead(at("file"), budget).use { stream ->
            connector.failRead = true
            assertThrows(IOException::class.java) { stream.read(ByteArray(4)) }
            assertEquals(4L, budget.bytesRead)
            assertEquals(1, connector.sessionsOpened.get())
        }
        assertEquals(1, connector.sessionsClosed.get())
        provider.stat(root)
        assertEquals(2, connector.sessionsOpened.get())
    }

    @Test fun `disconnect closes active resources without reconnecting`() = runBlocking {
        connector.put("file", "x")
        provider.openRead(at("file")).use { stream ->
            provider.disconnect(account.id)
            assertThrows(IOException::class.java) { stream.read() }
        }
        assertEquals(1, connector.sessionsOpened.get())
        assertEquals(1, connector.sessionsClosed.get())
    }

    @Test fun `provider roots do not connect or ask for credentials`() = runBlocking {
        val provider = TransferStorageProvider("sftp", { listOf(account) }, { error("Vault should remain closed") }, connector)
        assertEquals(root, provider.roots().single().ref)
        assertTrue(Feature.RANGE_READ in provider.features)
        assertEquals(0, connector.sessionsOpened.get())
    }
}
