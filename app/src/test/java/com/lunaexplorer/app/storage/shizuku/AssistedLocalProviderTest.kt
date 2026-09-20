package com.lunaexplorer.app.storage.shizuku

import com.lunaexplorer.app.storage.LocalRoot
import com.lunaexplorer.app.storage.LocalStorageProvider
import com.lunaexplorer.app.storage.PathProbe
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.RootKind
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import android.os.DeadObjectException
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
class AssistedLocalProviderTest {
    @get:Rule val temporary = TemporaryFolder()

    /** Counts what reaches the helper, which is how a test tells the two routes apart: both can read the temp folder. */
    private class Counting(private val inner: IFileHelper) : IFileHelper by inner {
        val asked = ArrayList<String>()
        override fun stat(key: String): String { asked += "stat $key"; return inner.stat(key) }
        override fun list(key: String, complete: Boolean): Long { asked += "list $key"; return inner.list(key, complete) }
        override fun relocate(key: String, parent: String, name: String): String? { asked += "relocate $key"; return inner.relocate(key, parent, name) }
        override fun openRead(key: String) = inner.openRead(key).also { asked += "read $key" }
    }

    private val roots by lazy { listOf(LocalRoot("primary", "Internal storage", temporary.root, RootKind.INTERNAL, followLinks = true)) }
    private val direct by lazy { LocalStorageProvider(roots, probe = PathProbe.OF_FILESYSTEM) }
    private val counting by lazy { Counting(FileHelperService()) }
    private var connected = true
    private val provider by lazy {
        val files = HelperFiles("local", counting).also { runBlocking { it.setRoots(roots) } }
        AssistedLocalProvider(direct, "com.lunaexplorer.app") { files.takeIf { connected } }
    }

    private fun at(relative: String) = NodeRef("local", "primary $relative")

    @Before fun files() {
        File(temporary.root, "Android/data/com.game/files").mkdirs()
        File(temporary.root, "Android/data/com.game/files/save.dat").writeText("progress")
        File(temporary.root, "Android/obb").mkdirs()
        File(temporary.root, "Android/media").mkdirs()
        File(temporary.root, "Download").mkdirs()
        File(temporary.root, "Download/notes.txt").writeText("notes")
    }

    @Test fun `only what Android closes to apps goes to the helper`() = runBlocking {
        provider.list(at("Download")).toList()
        provider.stat(at("Download/notes.txt"))
        provider.list(at("Android/media")).toList()
        assertEquals(emptyList<String>(), counting.asked)

        val listed = provider.list(at("Android/data/com.game/files")).toList().flatten()
        provider.openRead(listed.single().ref).use { assertEquals("progress", it.readBytes().decodeToString()) }

        assertEquals(listOf("list primary Android/data/com.game/files", "read primary Android/data/com.game/files/save.dat"), counting.asked)
    }

    @Test fun `in a volume's Android folder, data and obb are described by the helper that will open them`() = runBlocking {
        val names = provider.list(at("Android")).toList().flatten().map { it.name }.toSet()

        assertEquals(setOf("data", "obb", "media"), names)
        assertEquals(setOf("stat primary Android/data", "stat primary Android/obb"), counting.asked.toSet())
    }

    @Test fun `Luna's own folder in there, and a folder that only looks the part, stay with Android`() = runBlocking {
        File(temporary.root, "Android/data/com.lunaexplorer.app/files").mkdirs()
        File(temporary.root, "Backups/Android/data/com.game").mkdirs()

        provider.list(at("Android/data/com.lunaexplorer.app/files")).toList()
        provider.list(at("Backups/Android/data/com.game")).toList()

        assertEquals(emptyList<String>(), counting.asked)
        assertFalse(provider.closes(at("Backups/Android/data/com.game")))
        assertFalse(provider.closes(at("Android/data/com.lunaexplorer.app/files")))
        assertTrue(provider.closes(at("Android/data/com.game")))
    }

    @Test fun `a listing abandoned while the helper was still opening it is let go of`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val released = ArrayList<Long>()
        var opened = 0L
        val real = FileHelperService()
        val slow = object : IFileHelper by real {
            override fun list(key: String, complete: Boolean): Long {
                entered.countDown(); release.await()
                return real.list(key, complete).also { opened = it }
            }
            override fun done(listing: Long) { synchronized(released) { released += listing }; real.done(listing) }
        }
        val files = HelperFiles("local", slow).also { it.setRoots(roots) }
        val provider = AssistedLocalProvider(direct, "com.lunaexplorer.app") { files }

        val reading = launch(Dispatchers.Default) { provider.list(at("Android/data/com.game/files")).toList() }
        entered.await()
        reading.cancel()
        release.countDown()
        reading.join()

        assertEquals("The answer a cancellation throws away is the only handle on what the helper opened", listOf(opened), released)
    }

    @Test fun `with no helper connected everything is asked of Android directly`() = runBlocking {
        connected = false

        provider.list(at("Android/data/com.game/files")).toList()
        provider.list(at("Android")).toList()

        assertEquals(emptyList<String>(), counting.asked)
    }

    @Test fun `a closed file has a path to show but none for a caller to open`() = runBlocking {
        val save = at("Android/data/com.game/files/save.dat")

        assertNull("Given a path, a thumbnail or viewer would try to open it and fail", provider.pathOf(save))
        assertEquals(File(temporary.root, "Android/data/com.game/files/save.dat").canonicalPath, provider.shownPathOf(save))
        assertEquals(provider.shownPathOf(at("Download/notes.txt")), provider.pathOf(at("Download/notes.txt")))
    }

    @Test fun `a move across the boundary is still a rename, made by the helper`() = runBlocking {
        val moved = provider.relocate(at("Download/notes.txt"), at("Android/data/com.game/files"), "notes.txt")

        assertEquals(at("Android/data/com.game/files/notes.txt"), moved?.ref)
        assertTrue(File(temporary.root, "Android/data/com.game/files/notes.txt").isFile)
        assertEquals(listOf("relocate primary Download/notes.txt"), counting.asked)
    }

    @Test fun `a move onto a name already taken is refused, and replaces nothing`() = runBlocking {
        File(temporary.root, "Android/data/com.game/files/notes.txt").writeText("already here")

        val failure = try {
            provider.relocate(at("Download/notes.txt"), at("Android/data/com.game/files"), "notes.txt"); null
        } catch (error: StorageException) { error }

        assertEquals(StorageError.CONFLICT, failure?.reason)
        assertEquals("already here", File(temporary.root, "Android/data/com.game/files/notes.txt").readText())
        assertEquals("notes", File(temporary.root, "Download/notes.txt").readText())
    }

    @Test fun `what the helper refuses arrives as the same kind of failure`() = runBlocking {
        val failure = try {
            provider.create(at("Android/data/com.game/files"), "save.dat", directory = false); null
        } catch (error: StorageException) { error }

        assertEquals(StorageError.CONFLICT, failure?.reason)
    }

    /** A device whose policy stops a descriptor crossing reports it as a failed transaction from a helper that still answers. */
    private class NoDescriptors(private val inner: IFileHelper) : IFileHelper by inner {
        var refused = 0
        override fun openRead(key: String) = throw DeadObjectException("Transaction failed on small parcel").also { refused++ }
        override fun openWrite(key: String) = throw DeadObjectException("Transaction failed on small parcel").also { refused++ }
    }

    @Test fun `where a descriptor cannot cross, the same bytes go in blocks`() = runBlocking {
        val blocked = NoDescriptors(FileHelperService())
        val files = HelperFiles("local", blocked).also { it.setRoots(roots) }
        val provider = AssistedLocalProvider(direct, "com.lunaexplorer.app") { files }
        val big = ByteArray(300_000) { (it % 251).toByte() }
        val folder = at("Android/data/com.game/files")

        val made = provider.create(folder, "big.bin", directory = false)
        provider.openWrite(made.ref).use { it.write(big) }
        val read = provider.openRead(made.ref).use { it.readBytes() }
        val tail = provider.openChannel(made.ref).use { channel ->
            channel.position(299_990)
            ByteBuffer.allocate(10).also { while (it.hasRemaining() && channel.read(it) >= 0) { } }.array()
        }

        assertArrayEquals(big, read)
        assertArrayEquals(big.copyOfRange(299_990, 300_000), tail)
        assertArrayEquals(big, File(temporary.root, "Android/data/com.game/files/big.bin").readBytes())
        assertEquals("It is tried once, not on every file", 1, blocked.refused)
    }

    @Test fun `a folder larger than one reply arrives whole`() = runBlocking {
        val many = File(temporary.root, "Android/data/com.game/cache").apply { mkdirs() }
        repeat(700) { File(many, "chunk-$it.bin").createNewFile() }

        val batches = provider.list(at("Android/data/com.game/cache"), complete = true).toList()

        assertTrue("It came in more than one batch", batches.size > 1)
        assertEquals(700, batches.flatten().map { it.name }.toSet().size)
    }
}
