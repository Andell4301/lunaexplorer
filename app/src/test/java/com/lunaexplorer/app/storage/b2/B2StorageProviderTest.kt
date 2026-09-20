package com.lunaexplorer.app.storage.b2

import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.KeepVersions
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class B2StorageProviderTest {
    @get:Rule val temporary = TemporaryFolder()

    private val backend = FakeB2Backend("media")
    private val connector = FakeB2Connector(backend)
    private var account = B2Account(id = "cloud", name = "Cloud", keyId = "key-id", bucket = "media",
        options = B2Options(partMegabytes = 0))
    private var unlocked = true
    private val provider by lazy {
        B2StorageProvider({ listOf(account) }, { if (unlocked) it.copy(applicationKey = "secret") else null },
            connector, B2ListingCache(temporary.newFolder("listings")), temporary.newFolder("uploads"))
    }
    private val root = NodeRef("b2", "cloud:media:")

    private fun at(path: String) = NodeRef("b2", "cloud:media:$path")

    private suspend fun names(folder: NodeRef): List<String> = provider.list(folder, complete = true).toList().flatten().map { it.name }

    private suspend fun refused(reason: StorageError, action: suspend () -> Unit) {
        try { action(); fail("Expected $reason") } catch (error: StorageException) { assertEquals(reason, error.reason) }
    }

    @Test fun `a folder is whatever shares a prefix, and its marker is not a child`() = runBlocking {
        backend.put("media", "photos/2024/a.jpg", "a")
        backend.put("media", "photos/.bzEmpty", "")
        backend.put("media", "notes.txt", "n")

        assertEquals(listOf("photos", "notes.txt"), names(root))
        assertEquals(listOf("2024"), names(at("photos")))
        assertTrue(provider.stat(at("photos/2024")).directory)
        refused(StorageError.NOT_FOUND) { provider.stat(at("photos/2023")) }
    }

    @Test fun `the marker object other tools write for a folder is not a nameless file inside it`() = runBlocking {
        backend.put("media", "photos/", "")
        backend.put("media", "photos/a.jpg", "a")

        assertEquals(listOf("a.jpg"), names(at("photos")))
        refused(StorageError.NOT_FOUND) { provider.stat(NodeRef("b2", "cloud:media:photos/")) }
    }

    @Test fun `a folder cannot be published under another name`() = runBlocking {
        val staged = provider.create(root, ".stage.partial", directory = true)

        refused(StorageError.UNSUPPORTED) { provider.commit(staged.ref, root, "album", replace = null) }
    }

    @Test fun `a folder that goes with its last file leaves every saved listing above it`() = runBlocking {
        backend.put("media", "a/b/only.txt", "x")
        provider.list(root).toList(); provider.list(at("a")).toList()

        provider.delete(at("a/b/only.txt"))

        assertEquals(emptyList<String>(), provider.list(root).toList().flatten().map { it.name })
    }

    @Test fun `a name that is both an object and a prefix is listed once, as the file`() = runBlocking {
        backend.put("media", "report", "the file")
        backend.put("media", "report/appendix.txt", "beneath it")

        val listed = provider.list(root, complete = true).toList().flatten()
        assertEquals(listOf("report"), listed.map { it.name })
        assertFalse(listed.single().directory)
    }

    @Test fun `delete removes every version unless the run asks to keep them`() = runBlocking {
        backend.put("media", "a.txt", "old"); backend.put("media", "a.txt", "new")
        backend.put("media", "b.txt", "old"); backend.put("media", "b.txt", "new")

        provider.delete(at("a.txt"))
        withContext(KeepVersions()) { provider.delete(at("b.txt")) }

        assertTrue("A plain delete leaves nothing stored", backend.versionsOf("media", "a.txt").isEmpty())
        assertEquals("A kept delete adds a hide marker above both versions", listOf(true, false, false),
            backend.versionsOf("media", "b.txt").map { it.hidden })
        assertEquals("Either way the name is gone from the folder", emptyList<String>(), names(root))
    }

    @Test fun `a key that may not delete hides instead, and is not offered rename`() = runBlocking {
        backend.auth = B2Auth("account", capabilities = setOf("listFiles", "readFiles", "writeFiles"))
        backend.put("media", "a.txt", "kept")

        val entry = provider.stat(at("a.txt"))
        assertTrue(Capability.DELETE in entry.capabilities)
        assertFalse("Rename needs the original removed", Capability.RENAME in entry.capabilities)
        provider.delete(at("a.txt"))

        assertEquals(listOf(true, false), backend.versionsOf("media", "a.txt").map { it.hidden })
    }

    @Test fun `a folder that vanished with its last object deletes without complaint`() = runBlocking {
        backend.put("media", "loose/only.txt", "x")
        provider.delete(at("loose/only.txt"))

        provider.delete(at("loose"))
        assertEquals(emptyList<String>(), names(root))
    }

    @Test fun `renaming a folder moves everything beneath it and leaves no version behind`() = runBlocking {
        backend.put("media", "trip/day1/a.jpg", "a")
        backend.put("media", "trip/b.jpg", "b")
        backend.put("media", "tripod.txt", "not inside trip")

        val renamed = provider.rename(at("trip"), "holiday")

        assertTrue(renamed.directory)
        assertEquals("a", backend.text("media", "holiday/day1/a.jpg"))
        assertEquals("b", backend.text("media", "holiday/b.jpg"))
        assertTrue(backend.versionsOf("media", "trip/b.jpg").isEmpty())
        assertEquals("A name that only shares the prefix's letters is not part of the folder",
            "not inside trip", backend.text("media", "tripod.txt"))
    }

    @Test fun `a folder move that fails midway names what was copied and removes nothing`() = runBlocking {
        backend.put("media", "trip/a.jpg", "a")
        backend.put("media", "trip/b.jpg", "b")
        var calls = 0
        val real = backend.bucket("media")
        val flaky = object : B2Bucket by real {
            override fun copy(from: B2Item, toKey: String): B2Item {
                if (++calls == 2) throw B2Failure(StorageError.IO, "connection reset")
                return real.copy(from, toKey)
            }
        }
        val flakyProvider: B2StorageProvider? = B2StorageProvider({ listOf(account) }, { it.copy(applicationKey = "s") },
            B2Connector { object : B2Session by connector.connect(it) { override fun bucket(name: String) = flaky } },
            B2ListingCache(temporary.newFolder("l2")), temporary.newFolder("u2"))
        flakyProvider!!.list(root).toList()
        val failure = try { flakyProvider.rename(at("trip"), "holiday"); null } catch (error: StorageException) { error }

        assertTrue("The message must say how far it got: ${failure?.message}", failure?.message.orEmpty().contains("1 of 2"))
        assertTrue("What did land is listed, not hidden behind the saved listing",
            "holiday" in flakyProvider!!.list(root).toList().flatten().map { it.name })
        assertEquals("a", backend.text("media", "trip/a.jpg"))
        assertEquals("b", backend.text("media", "trip/b.jpg"))
    }

    @Test fun `writing a created file leaves exactly one version`() = runBlocking {
        val created = provider.create(root, "draft.txt", directory = false)
        provider.openWrite(created.ref).use { it.write("content".toByteArray()) }

        assertEquals("content", backend.text("media", "draft.txt"))
        assertEquals("The empty placeholder from create must not linger", 1, backend.versionsOf("media", "draft.txt").size)
    }

    @Test fun `replacing through commit keeps what it covered as an older version`() = runBlocking {
        backend.put("media", "final.txt", "original")
        val seen = provider.stat(at("final.txt"))
        val staged = provider.create(root, ".stage.partial", directory = false)
        provider.openWrite(staged.ref).use { it.write("new".toByteArray()) }

        provider.commit(staged.ref, root, "final.txt", replace = seen)

        assertEquals("new", backend.text("media", "final.txt"))
        assertEquals(2, backend.versionsOf("media", "final.txt").size)
        assertTrue("The staged name is Luna's own and leaves nothing", backend.versionsOf("media", ".stage.partial").isEmpty())
    }

    @Test fun `writing over a file keeps its old content beneath the new`() = runBlocking {
        backend.put("media", "a.txt", "old")

        provider.openWrite(at("a.txt")).use { it.write("new".toByteArray()) }

        assertEquals("new", backend.text("media", "a.txt"))
        assertEquals(2, backend.versionsOf("media", "a.txt").size)
    }

    @Test fun `a rename that keeps versions hides the old name instead of emptying it`() = runBlocking {
        backend.put("media", "a.txt", "old"); backend.put("media", "a.txt", "new")

        withContext(KeepVersions()) { provider.rename(at("a.txt"), "b.txt") }

        assertEquals("new", backend.text("media", "b.txt"))
        assertEquals(listOf(true, false, false), backend.versionsOf("media", "a.txt").map { it.hidden })
        assertEquals(listOf("b.txt"), names(root))
    }

    @Test fun `a stream longer than one part goes up as a large file, in order`() = runBlocking {
        backend.auth = B2Auth("account", minimumPartSize = 4)
        val created = provider.create(root, "big.bin", directory = false)

        provider.openWrite(created.ref).use { out -> "0123456789".toByteArray().forEach { out.write(it.toInt()) } }

        assertEquals(listOf(1 to 4, 2 to 4, 3 to 2), backend.uploadedParts)
        assertEquals("0123456789", backend.text("media", "big.bin"))
    }

    @Test fun `a stream of exactly one part stays a plain upload`() = runBlocking {
        backend.auth = B2Auth("account", minimumPartSize = 4)
        val created = provider.create(root, "four.bin", directory = false)

        provider.openWrite(created.ref).use { it.write("abcd".toByteArray()) }

        assertTrue(backend.uploadedParts.isEmpty())
        assertEquals("abcd", backend.text("media", "four.bin"))
    }

    @Test fun `a large upload that fails is cancelled so its parts stop being stored`() = runBlocking {
        backend.auth = B2Auth("account", minimumPartSize = 4)
        backend.failPartsFrom = 2
        val created = provider.create(root, "big.bin", directory = false)

        val failure = runCatching { provider.openWrite(created.ref).use { it.write("0123456789".toByteArray()) } }.exceptionOrNull()

        assertTrue("The failure must reach the writer: $failure", failure is StorageException)
        assertEquals(1, backend.cancelledLargeFiles.size)
        assertEquals("The name keeps what it held before", "", backend.text("media", "big.bin"))
    }

    @Test fun `a key confined to a prefix sees only the way down to it`() = runBlocking {
        backend.auth = B2Auth("account", namePrefix = "shared/team/")
        backend.put("media", "shared/team/plan.txt", "p")
        backend.put("media", "shared/other/secret.txt", "s")
        backend.put("media", "private.txt", "x")

        assertEquals(listOf("shared"), names(root))
        assertEquals(listOf("team"), names(at("shared")))
        assertEquals(listOf("plan.txt"), names(at("shared/team")))
        assertTrue("B2 refuses a question about a name above the prefix, so none may be asked",
            provider.child(root, "shared")!!.directory)
        assertNull(provider.child(root, "private.txt"))
    }

    @Test fun `a locked vault is an authentication failure, and roots still list`() = runBlocking {
        unlocked = false
        assertEquals(listOf("Cloud"), provider.roots().map { it.title })
        assertTrue("The account root needs no network", provider.stat(root).directory)
        refused(StorageError.AUTH) { provider.list(root).toList() }
        assertTrue(connector.keysSeen.isEmpty())
    }

    @Test fun `a spent authorization is dropped, and the next request authorizes again`() = runBlocking {
        backend.put("media", "a.txt", "x")
        names(root)
        backend.failNext = B2Failure(StorageError.AUTH, "expired_auth_token")

        refused(StorageError.AUTH) { provider.stat(at("a.txt")) }
        assertEquals("a.txt", provider.stat(at("a.txt")).name)
        assertEquals(2, connector.keysSeen.size)
    }

    @Test fun `an account-wide account lists its buckets, and refuses to make one`() = runBlocking {
        account = account.copy(bucket = "")
        val top = NodeRef("b2", "cloud::")

        assertEquals(listOf("media"), provider.list(top).toList().flatten().map(Entry::name))
        refused(StorageError.UNSUPPORTED) { provider.create(top, "new-bucket", directory = true) }
        assertNull(provider.child(top, "absent"))
    }

    @Test fun `a download that breaks fails the way a stream does`() = runBlocking {
        backend.put("media", "a.bin", "0123456789")
        val real = backend.bucket("media")
        val breaking = object : B2Bucket by real {
            override fun open(item: B2Item, from: Long): InputStream = object : InputStream() {
                override fun read(): Int = throw B2Failure(StorageError.IO, "The connection to B2 was lost")
            }
        }
        val provider = B2StorageProvider({ listOf(account) }, { it.copy(applicationKey = "s") },
            B2Connector { object : B2Session by connector.connect(it) { override fun bucket(name: String) = breaking } },
            B2ListingCache(temporary.newFolder("l3")), temporary.newFolder("u3"))

        val failure = try { provider.openRead(at("a.bin")).use { it.readBytes() }; null } catch (error: Exception) { error }

        assertTrue("Readers catch IOException, so that is what must arrive: $failure", failure is IOException)
    }

    @Test fun `a ranged read comes from the version that was opened, even after an overwrite`() = runBlocking {
        backend.put("media", "clip.bin", "0123456789")
        val channel = provider.openChannel(at("clip.bin"))
        backend.put("media", "clip.bin", "XXXXXXXXXX")

        val buffer = java.nio.ByteBuffer.allocate(4)
        channel.use { it.position(3); it.read(buffer) }

        assertEquals("3456", String(buffer.array()))
    }
}
