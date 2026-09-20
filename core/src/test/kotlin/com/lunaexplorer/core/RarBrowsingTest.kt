package com.lunaexplorer.core

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RarBrowsingTest {
    private fun opened(bytes: ByteArray, seekable: Boolean = true, password: String? = null): Triple<ByteSource, ArchiveProvider, NodeRef> {
        val source = ByteSource("fixture.rar", bytes, seekable = seekable)
        val archive = archiveProvider(source)
        val root = runBlocking { archive.open(source.ref, "fixture.rar", password = password) }
        return Triple(source, archive, root)
    }

    @Test fun `a rar4 archive lists and reads its members`() = runBlocking {
        val (source, archive, root) = opened(ArchiveFixtureData.RAR4)

        assertEquals(listOf("FILE1.TXT", "FILE2.TXT"), archive.names(root))
        val first = archive.entryNamed(root, "FILE1.TXT")
        assertEquals(7L, first.size)
        assertNotNull(first.modified)
        assertEquals("file1\r\n", archive.text(first.ref))
        assertEquals("file2\r\n", archive.text(memberRef(root, "FILE2.TXT")))
        assertEquals("The index channel serves the members", 1, source.channelOpens)
    }

    @Test fun `backslash paths become folders`() = runBlocking {
        val (_, archive, root) = opened(ArchiveFixtureData.RAR4_DIRECTORY)

        assertEquals(listOf("foo"), archive.names(root))
        val foo = archive.entryNamed(root, "foo")
        assertTrue(foo.directory)
        assertEquals(listOf("bar.txt"), archive.names(foo.ref))
        assertEquals("baz\n", archive.text(memberRef(root, "foo/bar.txt")))
    }

    @Test fun `unicode names survive`() = runBlocking {
        val (_, archive, root) = opened(ArchiveFixtureData.RAR4_UNICODE)

        assertEquals(listOf("ウニコド.txt", "新建文本文档.txt").sorted(), archive.names(root))
        assertEquals("aaaaaaaaaa", archive.text(memberRef(root, "新建文本文档.txt")))
        assertEquals("このファイルにはUnicodeテキストが含まれています", archive.text(memberRef(root, "ウニコド.txt")))
    }

    @Test fun `solid archives read members in any order`() = runBlocking {
        for (fixture in listOf(ArchiveFixtureData.RAR4_SOLID, ArchiveFixtureData.RAR5_SOLID)) {
            val (source, archive, root) = opened(fixture)
            assertEquals((1..9).map { "file$it.txt" }, archive.names(root))
            // A repeated or earlier member makes the reader reopen the archive over the same channel.
            for (index in listOf(9, 9, 2, 9, 9, 1, 1, 5)) {
                assertEquals("file$index\n", archive.text(memberRef(root, "file$index.txt")))
            }
            assertEquals(1, source.channelOpens)
        }
    }

    @Test fun `a solid member abandoned part-way leaves the reads after it correct`() = runBlocking {
        for (fixture in listOf(ArchiveFixtureData.RAR4_SOLID, ArchiveFixtureData.RAR5_SOLID)) {
            val (source, archive, root) = opened(fixture)

            archive.openRead(memberRef(root, "file7.txt")).use { assertTrue(it.read() >= 0) }

            assertEquals("file7\n", archive.text(memberRef(root, "file7.txt")))
            assertEquals("file3\n", archive.text(memberRef(root, "file3.txt")))
            assertEquals("file9\n", archive.text(memberRef(root, "file9.txt")))
            assertEquals(1, source.channelOpens)
        }
    }

    @Test fun `a member opened in a coroutine that has since finished still reads`() {
        val (_, archive, root) = opened(ArchiveFixtureData.RAR5_SOLID)
        // A budgeted read always gets a source handle of its own, tied to the job that opened it.
        val member = runBlocking { archive.openRead(memberRef(root, "file3.txt"), ReadBudget(1L shl 20)) }
        assertEquals("file3\n", member.use { it.readBytes().decodeToString() })
    }

    @Test fun `a dictionary beyond the configured bound is refused rather than allocated`() = runBlocking {
        val channel = SeekableInMemoryByteChannel(ArchiveFixtureData.RAR5_SOLID)
        val rar = openRar(channel, channel.size(), password = null, maxDictionary = 1024)
        val failure = try {
            storageFailure {
                rarMemberStream(rar, rar.fileHeaders.first(), encrypted = false, passwordGiven = false) {}
                    .use { it.readBytes() }
            }
        } finally {
            runCatching { rar.close() }
        }

        assertEquals(StorageError.UNSUPPORTED, failure.reason)
        assertTrue("${failure.message}", failure.message.orEmpty().contains("dictionary"))

        // The default bound still opens ordinary archives.
        val (_, opened, root) = opened(ArchiveFixtureData.RAR5_SOLID)
        assertEquals("file1\n", opened.text(memberRef(root, "file1.txt")))
    }

    @Test fun `rar5 archives are read`() = runBlocking {
        val (_, archive, root) = opened(ArchiveFixtureData.RAR5)

        assertEquals(listOf("FILE1.TXT", "FILE2.TXT"), archive.names(root))
        assertEquals("file2\r\n", archive.text(memberRef(root, "FILE2.TXT")))
    }

    @Test fun `encrypted headers need the password to open`() = runBlocking {
        for (fixture in listOf(ArchiveFixtureData.RAR4_ENCRYPTED_HEADERS, ArchiveFixtureData.RAR5_ENCRYPTED_HEADERS)) {
            val source = ByteSource("locked.rar", fixture)
            val archive = archiveProvider(source)

            val locked = storageFailure { runBlocking { archive.open(source.ref, "locked.rar") } }
            assertEquals(StorageError.AUTH, locked.reason)
            val wrong = storageFailure { runBlocking { archive.open(source.ref, "locked.rar", password = "wrong") } }
            assertEquals(StorageError.AUTH, wrong.reason)
            assertEquals("Failed openings close their handles", 0, source.openHandles)

            val root = archive.open(source.ref, "locked.rar", password = "junrar")

            assertEquals(listOf("file1.txt"), archive.names(root))
            assertTrue(archive.isEncrypted(root))
            assertTrue(archive.hasPassword(root))
            assertFalse(archive.needsPassword(memberRef(root, "file1.txt")))
            assertEquals("file1\n", archive.text(memberRef(root, "file1.txt")))
        }
    }

    @Test fun `encrypted members list without a password and unlock with the right one`() = runBlocking {
        for (fixture in listOf(ArchiveFixtureData.RAR4_ENCRYPTED_FILES, ArchiveFixtureData.RAR5_ENCRYPTED_FILES)) {
            val (_, archive, root) = opened(fixture)
            val member = memberRef(root, "file1.txt")

            assertEquals(listOf("file1.txt"), archive.names(root))
            assertTrue(archive.isEncrypted(member))
            assertTrue(archive.isEncrypted(root))
            assertTrue(archive.needsPassword(member))
            val locked = storageFailure { runBlocking { archive.openRead(member) } }
            assertEquals(StorageError.AUTH, locked.reason)

            val wrong = storageFailure { runBlocking { archive.unlock(member, "wrong") } }
            assertEquals(StorageError.AUTH, wrong.reason)
            assertTrue(archive.needsPassword(member))

            archive.unlock(member, "junrar")

            assertFalse(archive.needsPassword(member))
            assertEquals("file1\n", archive.text(member))
            // A stale decoder on the second read would report a wrong password.
            assertEquals("file1\n", archive.text(member))
        }
    }

    @Test fun `a stream-only source is staged once`() = runBlocking {
        val (source, archive, root) = opened(ArchiveFixtureData.RAR4_DIRECTORY, seekable = false)

        assertEquals("baz\n", archive.text(memberRef(root, "foo/bar.txt")))
        assertEquals("baz\n", archive.text(memberRef(root, "foo/bar.txt")))
        assertEquals(1, source.streamOpens)
        assertEquals(0, source.channelOpens)
    }

    @Test fun `closing a member early stops its worker and leaves the archive usable`() = runBlocking {
        val (source, archive, root) = opened(ArchiveFixtureData.RAR4_SOLID)

        val stream = archive.openRead(memberRef(root, "file5.txt"))
        assertTrue(stream.read() >= 0)
        stream.close()

        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline && workers().isNotEmpty()) Thread.sleep(20)
        assertTrue("Worker threads still alive: ${workers()}", workers().isEmpty())
        assertEquals("file6\n", archive.text(memberRef(root, "file6.txt")))
        assertEquals("The kept index served both reads", 1, source.channelOpens)
    }

    @Test fun `a rar inside a zip opens through the archive provider`() = runBlocking {
        val outer = jdkZipBytes(linkedMapOf("inner.rar" to ArchiveFixtureData.RAR4))
        val source = ByteSource("outer.zip", outer)
        val archive = archiveProvider(source)
        val outerRoot = archive.open(source.ref, "outer.zip")

        val inner = archive.open(archive.entryNamed(outerRoot, "inner.rar").ref, "inner.rar")

        assertEquals(listOf("FILE1.TXT", "FILE2.TXT"), archive.names(inner))
        assertEquals("file1\r\n", archive.text(memberRef(inner, "FILE1.TXT")))
    }

    private fun workers() = Thread.getAllStackTraces().keys.filter { it.name == "luna-rar-member" && it.isAlive }.map { it.name }
}
