package com.lunaexplorer.core

import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.Random
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SevenZBrowsingTest {
    private val notes = ByteArray(150_000).also { Random(7).nextBytes(it) }
    private val members: Members = linkedMapOf(
        "docs" to null,
        "docs/readme.txt" to bytesOf("top level text"),
        "docs/nested/notes.bin" to notes,
        "empty.txt" to ByteArray(0),
    )

    /** docs/nested/notes.txt in the 7-Zip CLI fixtures. */
    private val cliNotes = "nested notes, a little longer so the codec has something to do " + (1..40).joinToString(" ") + "\n"

    private suspend fun check(archive: ArchiveProvider, root: NodeRef) {
        assertEquals(listOf("docs", "empty.txt"), archive.names(root))
        assertEquals(listOf("nested", "readme.txt"), archive.names(memberRef(root, "docs")))
        val note = archive.entryNamed(memberRef(root, "docs/nested"), "notes.bin")
        assertEquals(notes.size.toLong(), note.size)
        assertEquals(1_700_000_000_000L, note.modified)
        assertArrayEquals(notes, archive.openRead(note.ref).use { it.readBytes() })
        assertEquals("top level text", archive.text(memberRef(root, "docs/readme.txt")))
        assertEquals("", archive.text(memberRef(root, "empty.txt")))
    }

    @Test fun `a 7z lists nested folders and reads members over one channel`() = runBlocking {
        val source = ByteSource("bundle.7z", sevenZBytes(members))
        val archive = archiveProvider(source)

        val root = archive.open(source.ref, "bundle.7z")

        check(archive, root)
        assertEquals("The index channel serves the members too", 1, source.channelOpens)
        assertEquals(0, source.streamOpens)
    }

    @Test fun `a 7z from a stream-only source is staged once and then read locally`() = runBlocking {
        val source = ByteSource("bundle.7z", sevenZBytes(members), seekable = false)
        val archive = archiveProvider(source)

        val root = archive.open(source.ref, "bundle.7z")

        check(archive, root)
        assertEquals("Copied once, never re-downloaded per member", 1, source.streamOpens)
        assertEquals(0, source.openHandles)
    }

    @Test fun `a 7z with encrypted content lists without a password and unlocks with the right one`() = runBlocking {
        val source = ByteSource("enc.7z", ArchiveFixtureData.ENC_7Z)
        val archive = archiveProvider(source)
        val root = archive.open(source.ref, "enc.7z")
        val readme = memberRef(root, "readme.txt")

        assertEquals(listOf("docs", "readme.txt"), archive.names(root))
        assertTrue(archive.isEncrypted(root))
        assertTrue(archive.isEncrypted(readme))
        assertTrue(archive.needsPassword(readme))
        assertFalse(archive.hasPassword(readme))
        assertEquals("This item needs a password", storageFailure { runBlocking { archive.openRead(readme) } }.also {
            assertEquals(StorageError.AUTH, it.reason)
        }.message)

        val wrong = storageFailure { runBlocking { archive.unlock(readme, "wrong") } }
        assertEquals(StorageError.AUTH, wrong.reason)
        assertTrue("A rejected password is not kept", archive.needsPassword(readme))

        archive.unlock(root, "Secret1")

        assertTrue(archive.hasPassword(readme))
        assertFalse(archive.needsPassword(readme))
        assertEquals("top level text\n", archive.text(readme))
        assertEquals(cliNotes, archive.text(memberRef(root, "docs/nested/notes.txt")))
        assertEquals("second doc\n", archive.text(memberRef(root, "docs/second.txt")))
    }

    @Test fun `a wrong password given when opening encrypted content is rejected`() = runBlocking {
        val source = ByteSource("enc.7z", ArchiveFixtureData.ENC_7Z)
        val archive = archiveProvider(source)

        val failure = storageFailure { runBlocking { archive.open(source.ref, "enc.7z", password = "wrong") } }

        assertEquals(StorageError.AUTH, failure.reason)
    }

    @Test fun `a 7z with encrypted headers opens only with its password`() = runBlocking {
        val source = ByteSource("hdr.7z", ArchiveFixtureData.HDR_7Z)
        val archive = archiveProvider(source)

        val locked = storageFailure { runBlocking { archive.open(source.ref, "hdr.7z") } }
        assertEquals(StorageError.AUTH, locked.reason)
        val wrong = storageFailure { runBlocking { archive.open(source.ref, "hdr.7z", password = "wrong") } }
        assertEquals(StorageError.AUTH, wrong.reason)

        val root = archive.open(source.ref, "hdr.7z", password = "Secret1")

        assertEquals(listOf("docs", "readme.txt"), archive.names(root))
        assertTrue(archive.isEncrypted(root))
        assertFalse(archive.needsPassword(root))
        assertEquals("top level text\n", archive.text(memberRef(root, "readme.txt")))
        assertEquals(cliNotes, archive.text(memberRef(root, "docs/nested/notes.txt")))
        assertEquals("Nothing left open after a failed opening", 1, source.openHandles)
    }

    private fun written(method: SevenZMethod, body: ByteArray): ByteArray = SeekableInMemoryByteChannel().let { channel ->
        SevenZOutputFile(channel).use { out ->
            out.setContentCompression(method)
            out.putArchiveEntry(SevenZArchiveEntry().apply { name = "main.rs" })
            out.write(body)
            out.closeArchiveEntry()
            out.finish()
        }
        channel.array().copyOf(channel.size().toInt())
    }

    @Test fun `a small block is read however large a dictionary its header declares`() {
        // commons-compress writes an 8 MiB dictionary into the header, eight times this limit.
        for (method in listOf(SevenZMethod.LZMA2, SevenZMethod.LZMA)) {
            openSevenZ(SeekableInMemoryByteChannel(written(method, bytesOf("fn main() {}"))), "code.7z", null, memoryLimitKiB = 1024).use { file ->
                val entry = file.entries.first()
                assertEquals("$method", "fn main() {}", file.getInputStream(entry).use { it.readBytes().decodeToString() })
            }
        }
    }

    @Test fun `a block that really is larger than the memory allowed is still refused`() {
        val large = ByteArray(3 * 1024 * 1024).also { Random(3).nextBytes(it) }
        openSevenZ(SeekableInMemoryByteChannel(written(SevenZMethod.LZMA2, large)), "big.7z", null, memoryLimitKiB = 1024).use { file ->
            val failure = runCatching { sevenZMemberStream(file, file.entries.first(), false, false) {} }.exceptionOrNull()
            assertEquals(StorageError.UNSUPPORTED, (failure as? StorageException)?.reason)
        }
    }

    @Test fun `a member open that was cut short does not break the opens after it`() {
        // LZMA, not LZMA2: its decoder reads from the source while the block is being opened.
        val bytes = written(SevenZMethod.LZMA, bytesOf("fn main() {}"))
        var cut = false
        val inner = SeekableInMemoryByteChannel(bytes)
        val channel = object : SeekableByteChannel by inner {
            override fun read(dst: ByteBuffer): Int {
                if (cut) throw InterruptedIOException("Reading the archive was given up")
                return inner.read(dst)
            }
        }
        val shared = NonClosingChannel(channel)
        val file = openSevenZ(shared, "code.7z", null)
        val reader = KeptSevenZ(file, sevenZMembers(file).second, encrypted = false, passwordGiven = false,
            reopen = { openSevenZ(shared, "code.7z", null) }, closeChannel = { channel.close() })

        cut = true
        assertTrue(runCatching { reader.open("main.rs", null) }.exceptionOrNull() is InterruptedIOException)
        cut = false

        assertEquals("fn main() {}", reader.open("main.rs", null)!!.use { it.readBytes().decodeToString() })
        assertTrue("The reopened archive still reads over the one source handle", inner.isOpen)

        // The encryption probe at index time can fail the same way, before the reader exists.
        val probed = openSevenZ(shared, "code.7z", null)
        cut = true
        assertTrue(runCatching { probed.getInputStream(probed.entries.first()) }.exceptionOrNull() is InterruptedIOException)
        cut = false
        val afterProbe = KeptSevenZ(probed, sevenZMembers(probed).second, encrypted = false, passwordGiven = false,
            reopen = { openSevenZ(shared, "code.7z", null) }, closeChannel = {}, stale = true)
        assertEquals("fn main() {}", afterProbe.open("main.rs", null)!!.use { it.readBytes().decodeToString() })
    }

    @Test fun `members of a solid block are read in any order`() = runBlocking {
        val source = ByteSource("solid.7z", ArchiveFixtureData.SOLID_7Z)
        val archive = archiveProvider(source)
        val root = archive.open(source.ref, "solid.7z")

        assertEquals(cliNotes, archive.text(memberRef(root, "docs/nested/notes.txt")))
        assertEquals("top level text\n", archive.text(memberRef(root, "readme.txt")))
        assertEquals("second doc\n", archive.text(memberRef(root, "docs/second.txt")))
        assertEquals(cliNotes, archive.text(memberRef(root, "docs/nested/notes.txt")))
        assertEquals(1, source.channelOpens)
    }

    @Test fun `the encryption sample is the first member with data in archive order`() {
        SevenZFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(ArchiveFixtureData.SOLID_7Z)).get().use { file ->
            val inArchiveOrder = file.entries.filter { !it.isAntiItem && !it.isDirectory && it.hasStream() && it.size > 0 }
            // The index keeps its entries in a hash map, whose order is not the archive's.
            val fromTheIndexMap = sevenZMembers(file).second.values
                .first { !it.isDirectory && it.hasStream() && it.size > 0 }

            assertEquals("docs/nested/notes.txt", inArchiveOrder.first().name)
            assertNotEquals("The fixture must tell the two orders apart",
                inArchiveOrder.first().name, fromTheIndexMap.name)
            assertEquals("Sampling a later member decodes the whole block before it",
                inArchiveOrder.first().name, sevenZSample(file)?.name)
        }
    }

    @Test fun `a second member opened while one is streaming gets its own reader`() = runBlocking {
        val source = ByteSource("bundle.7z", sevenZBytes(members))
        val archive = archiveProvider(source)
        val root = archive.open(source.ref, "bundle.7z")

        val first = archive.openRead(memberRef(root, "docs/nested/notes.bin"))
        val head = first.read()
        assertEquals("top level text", archive.text(memberRef(root, "docs/readme.txt")))
        assertEquals("The library serves one member at a time, so the second read opened a handle", 2, source.channelOpens)

        val rest = first.use { it.readBytes() }
        assertArrayEquals(notes, byteArrayOf(head.toByte()) + rest)
        assertEquals("Only the kept index remains open", 1, source.openHandles)
    }

    @Test fun `a 7z inside a zip opens through the archive provider`() = runBlocking {
        val outer = jdkZipBytes(linkedMapOf("inner.7z" to sevenZBytes(members)))
        val source = ByteSource("outer.zip", outer)
        val archive = archiveProvider(source)
        val outerRoot = archive.open(source.ref, "outer.zip")

        val inner = archive.open(archive.entryNamed(outerRoot, "inner.7z").ref, "inner.7z")

        check(archive, inner)
    }

    @Test fun `a budgeted read opens its own handle and stops at the allowance`() = runBlocking {
        val source = ByteSource("bundle.7z", sevenZBytes(members))
        val archive = archiveProvider(source)
        val root = archive.open(source.ref, "bundle.7z")
        val note = memberRef(root, "docs/nested/notes.bin")
        val servedBefore = source.bytesServed

        val small = ReadBudget(2_000)
        val failure = runCatching { archive.openRead(note, small).use { it.readBytes() } }.exceptionOrNull()
        assertTrue("Expected the read limit, got $failure", failure is InterruptedIOException)
        assertTrue(small.bytesRead in 1..small.max)
        assertEquals("The limited read paid for everything it fetched", small.bytesRead, source.bytesServed - servedBefore)
        assertEquals(2, source.channelOpens)
        assertEquals("The exhausted handle is closed; the kept index stays", 1, source.openHandles)

        val generous = ReadBudget(1_000_000)
        assertArrayEquals(notes, archive.openRead(note, generous).use { it.readBytes() })
        assertTrue(generous.bytesRead > 0)
        assertEquals(1, source.openHandles)
    }
}
