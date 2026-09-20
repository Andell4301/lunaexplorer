package com.lunaexplorer.app.storage.smb

import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.RootKind
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class SmbStorageProviderTest {
    private val account = SmbAccount(id = "nas", name = "NAS", host = "nas.local", username = "me")
    private val connector = FakeSmbConnector()
    private var vaultOpen = true
    private val provider = SmbStorageProvider(
        accounts = { listOf(account) },
        withPassword = { if (vaultOpen) it.copy(password = "secret") else null },
        connector = connector,
    )
    // Keys are account:share:path; an empty share is the server itself.
    private val root = NodeRef("smb", "nas::")
    private val media = NodeRef("smb", "nas:media:")

    private suspend fun refused(reason: StorageError, action: suspend () -> Unit) {
        try { action(); fail("Expected $reason") }
        catch (error: StorageException) { assertEquals(reason, error.reason) }
    }

    private suspend fun failsToRead(action: suspend () -> Unit) {
        try { action(); fail("Expected the read to fail") } catch (_: IOException) {}
    }

    @Test fun `listing the roots connects to nothing`() = runBlocking {
        val roots = provider.roots()

        assertEquals(listOf("NAS"), roots.map { it.title })
        assertEquals(RootKind.NETWORK, roots.single().kind)
        assertEquals("smb://nas.local", roots.single().description)
        assertEquals("A root is a promise, not a connection", 0, connector.connections)
    }

    @Test fun `the first request connects, and the ones after reuse it`() = runBlocking {
        provider.stat(media)
        provider.list(media).toList()
        provider.stat(media)

        assertEquals(1, connector.connections)
        assertEquals("With the password put on for the wire", "secret", connector.lastAccount?.password)
    }

    @Test fun `a connection that dropped is made again on the next request`() = runBlocking {
        provider.stat(media)
        connector.server.connected = false

        provider.stat(media)

        assertEquals("Found dead before the request, so replaced", 2, connector.connections)
    }

    @Test fun `a share that cannot be reached says so and leaves the roots alone`() = runBlocking {
        connector.refuse = SmbFailure(StorageError.OFFLINE, "The server could not be found by that name")

        try { provider.stat(media); fail("Expected a failure") }
        catch (error: StorageException) { assertEquals(StorageError.OFFLINE, error.reason) }

        assertEquals("The root is still offered; it is the share that is away, not the account",
            1, provider.roots().size)
    }

    @Test fun `a locked vault is reported as the thing to do, not as a wrong password`() = runBlocking {
        vaultOpen = false

        try { provider.list(media).toList(); fail("Expected a failure") }
        catch (error: StorageException) {
            assertEquals(StorageError.AUTH, error.reason)
            assertTrue(error.message, error.message?.contains("Unlock") == true)
        }
        assertEquals("Nothing was tried without the password", 0, connector.connections)
    }

    @Test fun `a guest account never asks the vault`() = runBlocking {
        vaultOpen = false
        val guest = SmbAccount(id = "g", name = "Guest", host = "nas.local", guest = true)
        val open = SmbStorageProvider({ listOf(guest) }, { if (vaultOpen) it else null }, connector)

        open.list(NodeRef("smb", "g::")).toList()

        assertEquals(1, connector.connections)
    }

    @Test fun `a moved file stays within its share by renaming, and does not leave it`() = runBlocking {
        val folder = provider.create(media, "in", directory = true)
        val file = provider.create(media, "a.txt", directory = false)
        provider.openWrite(file.ref).use { it.write("x".toByteArray()) }

        val moved = provider.relocate(file.ref, folder.ref, "b.txt")

        assertEquals(NodeRef("smb", "nas:media:in/b.txt"), moved?.ref)
        assertNull("The old name is gone", provider.child(media, "a.txt"))
        assertEquals("x", provider.openRead(moved!!.ref).use { it.readBytes().decodeToString() })
    }

    @Test fun `the top of a server stats without connecting, and lists by connecting`() = runBlocking {
        val top = provider.stat(root)

        assertTrue(top.directory)
        assertEquals("NAS", top.name)
        assertEquals(setOf(Capability.LIST), top.capabilities)
        assertEquals("The top of a server is a fact of the settings", 0, connector.connections)

        provider.list(root).toList()

        assertEquals(1, connector.connections)
        assertEquals(1, connector.server.listings)
    }

    @Test fun `the top of a server is its folder shares, with the administrative ones hidden`() = runBlocking {
        connector.server.add("backup\$")
        connector.server.add("admin", special = true)
        connector.server.add("printer", disk = false)
        connector.server.add("IPC\$", disk = false, special = true)

        val listed = provider.list(root).toList().flatten()

        assertEquals(setOf("media", "backup\$", "admin"), listed.map { it.name }.toSet())
        assertEquals(setOf("backup\$", "admin"), listed.filter { it.hidden }.map { it.name }.toSet())
        val first = listed.single { it.name == "media" }
        assertTrue(first.directory)
        assertEquals("A share lists, and nothing else is done to it from here", setOf(Capability.LIST), first.capabilities)
        assertEquals(NodeRef("smb", "nas:backup\$:"), listed.single { it.name == "backup\$" }.ref)
        assertEquals("Naming them opened none of them", 0, connector.server.opened)
    }

    @Test fun `a share opened from the top uses the session already signed in`() = runBlocking {
        provider.list(root).toList()
        provider.list(media).toList()
        provider.stat(media)

        assertEquals("media", provider.stat(media).name)
        assertEquals(1, connector.connections)
        assertEquals("One tree, kept", 1, connector.server.opened)
    }

    @Test fun `a tree that dropped is opened again without signing in again`() = runBlocking {
        provider.stat(media)
        connector.share.connected = false

        provider.stat(media)

        assertEquals(1, connector.connections)
        assertEquals("Found dead before the request, so a new tree on the same session", 2, connector.server.opened)
    }

    @Test fun `a tree the server dropped is forgotten on that failure and made again on the next request`() = runBlocking {
        provider.stat(media)
        connector.share.failNext = SmbFailure(StorageError.DISCONNECTED, "The connection to the server was lost")

        refused(StorageError.DISCONNECTED) { provider.stat(media) }
        assertEquals("The request that found the tree dead is not retried", 1, connector.server.opened)

        assertEquals("media", provider.stat(media).name)
        assertEquals(1, connector.connections)
        assertEquals("A new tree on the same session, made for the next request", 2, connector.server.opened)
    }

    @Test fun `a session that hands back a closed tree is replaced, and the request still goes through`() = runBlocking {
        connector.server.keepsClosedTrees = true
        provider.stat(media)
        connector.share.failNext = SmbFailure(StorageError.DISCONNECTED, "The connection to the server was lost")
        refused(StorageError.DISCONNECTED) { provider.stat(media) }

        assertEquals("media", provider.stat(media).name)
        assertEquals("A new session, since the old one could only hand back the closed tree", 2, connector.connections)
    }

    @Test fun `a file is read on one handle, and small reads come from blocks rather than a request each`() = runBlocking {
        val bytes = ByteArray(1 shl 20) { (it % 251).toByte() }
        connector.share.put("big.bin", bytes)
        val channel = provider.openChannel(NodeRef("smb", "nas:media:big.bin"))!!
        val buffer = ByteBuffer.allocate(4096)
        val out = ByteArrayOutputStream()
        while (true) {
            buffer.clear()
            val n = channel.read(buffer)
            if (n < 0) break
            out.write(buffer.array(), 0, n)
        }
        channel.close()

        assertArrayEquals(bytes, out.toByteArray())
        assertEquals("One handle for the whole file", 1, connector.share.readersOpened)
        assertEquals("Closed with the channel", 0, connector.share.readersOpen)
        assertTrue("A megabyte in 4 KB reads is a few requests, not 256: ${connector.share.reads}", connector.share.reads <= 20)
    }

    @Test fun `a read that finds its tree gone is asked again on a new tree`() = runBlocking {
        connector.share.put("clip.bin", ByteArray(200_000) { it.toByte() })
        val channel = provider.openChannel(NodeRef("smb", "nas:media:clip.bin"))!!
        channel.read(ByteBuffer.allocate(1000))
        channel.position(150_000)
        connector.share.failNext = SmbFailure(StorageError.DISCONNECTED, "The connection to the server was lost")

        val buffer = ByteBuffer.allocate(1000)
        assertEquals(1000, channel.read(buffer))
        assertEquals(150_000.toByte(), buffer.array()[0])
        assertEquals("A new tree for the read", 2, connector.server.opened)
        assertEquals("On the same session", 1, connector.connections)
        assertEquals("The file was opened again on it", 2, connector.share.readersOpened)
        assertEquals("And the handle on the old tree closed", 1, connector.share.readersOpen)
        channel.close()
    }

    @Test fun `a read that cannot be opened again leaves the tree everything else is using alone`() = runBlocking {
        connector.share.put("clip.bin", ByteArray(200_000) { it.toByte() })
        val channel = provider.openChannel(NodeRef("smb", "nas:media:clip.bin"))!!
        channel.read(ByteBuffer.allocate(1000))
        connector.share.rename("clip.bin", "moved.bin", replace = false)
        connector.share.failNext = SmbFailure(StorageError.DISCONNECTED, "The connection to the server was lost")

        channel.position(150_000)
        failsToRead { channel.read(ByteBuffer.allocate(1000)) }
        failsToRead { channel.read(ByteBuffer.allocate(1000)) }

        assertEquals("moved.bin", provider.stat(NodeRef("smb", "nas:media:moved.bin")).name)
        assertEquals("One new tree, kept, rather than one made and closed for every failed read", 2, connector.server.opened)
        channel.close()
    }

    @Test fun `a reader does not sign in again under settings the account no longer has`() = runBlocking {
        var current = account
        val changing = SmbStorageProvider({ listOf(current) }, { it.copy(password = "secret") }, connector)
        connector.share.put("clip.bin", ByteArray(200_000) { it.toByte() })
        val channel = changing.openChannel(NodeRef("smb", "nas:media:clip.bin"))!!
        channel.read(ByteBuffer.allocate(1000))

        current = account.copy(options = account.options.copy(requireSigning = true))
        changing.disconnect(account.id)
        changing.stat(media)
        val signedIn = connector.connections

        channel.position(150_000)
        failsToRead { channel.read(ByteBuffer.allocate(1000)) }
        changing.stat(media)

        assertEquals("Neither the reader nor the browser signed in again", signedIn, connector.connections)
        assertTrue("And the session is the one with the new settings", connector.lastAccount!!.options.requireSigning)
        channel.close()
    }

    @Test fun `a file whose opening was given up on is not left open on the server`() = runBlocking {
        connector.share.put("clip.bin", ByteArray(10))
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        connector.share.openEntered = entered
        connector.share.openGate = gate
        val job = CoroutineScope(Dispatchers.Default).launch { provider.openChannel(NodeRef("smb", "nas:media:clip.bin")) }

        assertTrue(entered.await(5, TimeUnit.SECONDS))
        job.cancel()
        gate.countDown()
        job.join()

        assertEquals(1, connector.share.readersOpened)
        assertEquals("Closed, since nobody was left to close it", 0, connector.share.readersOpen)
    }

    @Test fun `a server that will not name its shares fails at the top and nowhere else`() = runBlocking {
        connector.server.refuseListing = SmbFailure(StorageError.PERMISSION, "The server refused: access denied")

        refused(StorageError.PERMISSION) { provider.list(root).toList() }

        assertTrue("A share asked for by name still opens", provider.stat(media).directory)
        assertEquals("On the session that was already signed in", 1, connector.connections)
        val narrowed = SmbAccount(id = "one", name = "Media", host = "nas.local", share = "media", username = "me")
        val scoped = SmbStorageProvider({ listOf(narrowed) }, { it.copy(password = "secret") }, connector)
        scoped.list(NodeRef("smb", "one:media:")).toList()
        assertEquals("A share account never asks for the listing", 1, connector.server.listings)
    }

    @Test fun `a share account is its share, and nothing above or beside it`() = runBlocking {
        connector.server.add("backup")
        val narrowed = SmbAccount(id = "one", name = "Media", host = "nas.local", share = "media", username = "me")
        val scoped = SmbStorageProvider({ listOf(narrowed) }, { it.copy(password = "secret") }, connector)
        val shareRoot = NodeRef("smb", "one:media:")

        val roots = scoped.roots()

        assertEquals(shareRoot, roots.single().ref)
        assertEquals("smb://nas.local/media", roots.single().description)
        assertEquals("Media", scoped.stat(shareRoot).name)
        assertNull("The share is the root; there is no server above it", scoped.parentOf(shareRoot))
        for (elsewhere in listOf("one::", "one:backup:", "one:backup:x", "one:")) {
            refused(StorageError.NOT_FOUND) { scoped.stat(NodeRef("smb", elsewhere)) }
        }
        assertEquals("And none of that touched the other share", 1, connector.server.opened)
    }

    @Test fun `parentOf climbs from a file through its share to the server, and stops there`() = runBlocking {
        val films = provider.create(media, "films", directory = true)
        val film = provider.create(films.ref, "one.mkv", directory = false)

        assertEquals(films.ref, provider.parentOf(film.ref))
        assertEquals(media, provider.parentOf(films.ref))
        assertEquals(root, provider.parentOf(media))
        assertNull(provider.parentOf(root))
    }

    @Test fun `two shares on one server are under the server and not under each other`() = runBlocking {
        connector.server.add("backup")
        val backup = NodeRef("smb", "nas:backup:")
        val inMedia = provider.create(media, "x", directory = true).ref
        val inBackup = provider.create(backup, "x", directory = true).ref

        assertTrue(provider.isDescendant(inMedia, root))
        assertTrue(provider.isDescendant(backup, root))
        assertFalse(provider.isDescendant(inMedia, backup))
        assertFalse(provider.isDescendant(inBackup, media))
        assertFalse("The same path in another share is another place", provider.isDescendant(inBackup, inMedia))
        assertNull("A move across shares is a copy, however cheap a rename looks", provider.relocate(inMedia, backup, "y"))
    }

    @Test fun `nothing is made, renamed or deleted at the top of a server`() = runBlocking {
        refused(StorageError.UNSUPPORTED) { provider.create(root, "new", directory = true) }
        refused(StorageError.UNSUPPORTED) { provider.rename(media, "films") }
        refused(StorageError.UNSUPPORTED) { provider.delete(media) }
        refused(StorageError.UNSUPPORTED) { provider.rename(root, "other") }
        refused(StorageError.UNSUPPORTED) { provider.openRead(root) }
        assertNull(provider.availableBytes(root))
        assertEquals("None of it needed the server", 0, connector.connections)
    }
}
