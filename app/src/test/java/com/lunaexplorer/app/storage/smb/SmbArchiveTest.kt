package com.lunaexplorer.app.storage.smb

import com.lunaexplorer.core.ArchiveProvider
import com.lunaexplorer.core.ArchiveReading
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.ProviderRegistry
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import com.lunaexplorer.core.StorageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SmbArchiveTest {
    private val account = SmbAccount(id = "nas", name = "NAS", host = "nas.local", guest = true)
    private val connector = FakeSmbConnector()
    private val smb = SmbStorageProvider({ listOf(account) }, { it }, connector)
    private lateinit var registry: ProviderRegistry
    private val inside = ArchiveProvider({ registry })
    private val archive = NodeRef("smb", "nas:media:big.zip")
    private val payload = ByteArray(64 * 1024).also { Random(7).nextBytes(it) }
    private val share get() = connector.share

    init { registry = ProviderRegistry(listOf(smb, inside)) }

    private fun zip(count: Int, prefix: String = "file", content: ByteArray = payload): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            repeat(count) { i ->
                zip.putNextEntry(ZipEntry("folder${i % 10}/$prefix$i.bin"))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun `an archive on a share is listed from its directory, without fetching what is in it`() = runBlocking {
        val bytes = zip(count = 200)
        share.put("big.zip", bytes)

        val root = inside.open(archive, "big.zip")
        val folders = inside.list(root).toList().flatten().map { it.name }.sorted()

        assertEquals((0 until 10).map { "folder$it" }.sorted(), folders)
        assertTrue("The directory, not the members: ${share.bytesServed} of ${bytes.size} bytes",
            share.bytesServed < bytes.size / 20)
        assertEquals("On one handle", 1, share.readersOpened)
    }

    @Test fun `a member is read from where the directory says, without the directory being read again`() = runBlocking {
        share.put("big.zip", zip(count = 200))
        val root = inside.open(archive, "big.zip")
        val folder = inside.list(root).toList().flatten().single { it.name == "folder3" }
        val member = inside.list(folder.ref).toList().flatten().first()
        val before = share.bytesServed

        val content = inside.openRead(member.ref).use { it.readBytes() }

        assertArrayEquals(payload, content)
        assertEquals("On the handle the directory was read on", 1, share.readersOpened)
        assertTrue("About one member's worth: ${share.bytesServed - before}", share.bytesServed - before < 4 * 64 * 1024)
    }

    @Test fun `giving up on an archive stops the read there, and nothing is walked from the start instead`() = runBlocking {
        // 5000 members make the central directory span several read blocks.
        val bytes = zip(count = 5000, content = byteArrayOf(1))
        share.put("big.zip", bytes)
        lateinit var job: Job
        job = CoroutineScope(Dispatchers.IO).launch(start = CoroutineStart.LAZY) {
            inside.open(archive, "big.zip") { job.cancel() }
        }
        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertTrue("Stopped within a block or two: ${share.bytesServed} of ${bytes.size}", share.bytesServed <= 2 * 64 * 1024)
        assertEquals("No second handle to walk the file from its start", 1, share.readersOpened)
        assertEquals("And the one it had is closed", 0, share.readersOpen)
    }

    @Test fun `a provider that cannot seek is walked from the start, saying how far through it is`() = runBlocking {
        val bytes = zip(count = 20)
        share.put("big.zip", bytes)
        val streaming = object : StorageProvider by smb {
            override suspend fun openChannel(ref: NodeRef) = null
        }
        registry = ProviderRegistry(listOf(streaming, inside))
        val heard = mutableListOf<ArchiveReading>()

        val root = inside.open(archive, "big.zip") { synchronized(heard) { heard += it } }

        assertEquals(10, inside.list(root).toList().flatten().size)
        assertTrue(heard.isNotEmpty())
        assertTrue("Every report says it is a walk, against the whole size",
            heard.all { it.fromStart && it.total == bytes.size.toLong() })
        assertTrue("And the last says how far it got: ${heard.last().bytesRead}", heard.last().bytesRead > bytes.size / 2)
    }

    @Test fun `members stored with a leading slash or backslashes open under the names they are listed by`() = runBlocking {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("/docs/readme.txt")); zip.write("slash".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("dir\\sub\\notes.txt")); zip.write("back".toByteArray()); zip.closeEntry()
        }
        share.put("big.zip", out.toByteArray())
        val root = inside.open(archive, "big.zip")
        suspend fun child(parent: NodeRef, name: String) = inside.list(parent).toList().flatten().single { it.name == name }

        val readme = child(child(root, "docs").ref, "readme.txt")
        val notes = child(child(child(root, "dir").ref, "sub").ref, "notes.txt")

        assertEquals("slash", inside.openRead(readme.ref).use { it.readBytes().decodeToString() })
        assertEquals("back", inside.openRead(notes.ref).use { it.readBytes().decodeToString() })
        assertEquals("From the directory already read", 1, share.readersOpened)
    }

    @Test fun `a name kept in an old code page is shown and opened by the Unicode spelling beside it`() = runBlocking {
        val out = ByteArrayOutputStream()
        ZipArchiveOutputStream(out).use { zip ->
            zip.setEncoding("Cp437")
            zip.setUseLanguageEncodingFlag(false)
            zip.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.ALWAYS)
            zip.putArchiveEntry(ZipArchiveEntry("Привет.txt")); zip.write("hello".toByteArray()); zip.closeArchiveEntry()
        }
        share.put("big.zip", out.toByteArray())

        val root = inside.open(archive, "big.zip")
        val member = inside.list(root).toList().flatten().single()

        assertEquals("Привет.txt", member.name)
        assertEquals("hello", inside.openRead(member.ref).use { it.readBytes().decodeToString() })
    }

    @Test fun `a read that fails while the directory is read is reported, not answered by reading the whole archive`() = runBlocking {
        share.put("big.zip", zip(count = 20))
        share.failNextRead = SmbFailure(StorageError.IO, "The server reported an error")

        try { inside.open(archive, "big.zip"); fail("Expected the failure to be reported") }
        catch (error: StorageException) { assertEquals(StorageError.IO, error.reason) }

        assertEquals("No second handle to walk the file from its start", 1, share.readersOpened)
    }

    @Test fun `an archive stuck on one share holds up no other`() = runBlocking {
        share.put("big.zip", zip(count = 3))
        connector.server.add("backup").put("other.zip", zip(count = 3))
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        share.readEntered = entered
        share.readGate = gate
        val stuck = CoroutineScope(Dispatchers.IO).launch { runCatching { inside.open(archive, "big.zip") } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val other = withTimeout(5_000) { inside.open(NodeRef("smb", "nas:backup:other.zip"), "other.zip") }
            assertEquals(3, inside.list(other).toList().flatten().size)
        } finally {
            gate.countDown()
            stuck.join()
        }
    }

    @Test fun `an archive opened again is read again only when it has changed`() = runBlocking {
        share.put("big.zip", zip(count = 3, prefix = "old"))
        inside.open(archive, "big.zip", version = "one")
        inside.open(archive, "big.zip", version = "one")
        assertEquals("The same archive is not read twice", 1, share.readersOpened)

        share.put("big.zip", zip(count = 3, prefix = "new"))
        val root = inside.open(archive, "big.zip", version = "two")
        val folder = inside.list(root).toList().flatten().single { it.name == "folder0" }

        assertEquals(listOf("new0.bin"), inside.list(folder.ref).toList().flatten().map { it.name })
    }
}
