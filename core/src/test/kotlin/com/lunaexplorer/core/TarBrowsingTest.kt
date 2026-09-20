package com.lunaexplorer.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TarBrowsingTest {
    private val notes = ByteArray(200_000) { (it % 97).toByte() }
    private val members: Members = linkedMapOf(
        "media" to null,
        "docs/readme.txt" to bytesOf("top level text"),
        "docs/nested/notes.bin" to notes,
    )
    private val compressed = listOf(ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_BZ2)

    private suspend fun check(archive: ArchiveProvider, root: NodeRef) {
        assertEquals(listOf("docs", "media"), archive.names(root))
        val docs = archive.entryNamed(root, "docs")
        assertTrue("Implied by its children", docs.directory)
        assertEquals(listOf("nested", "readme.txt"), archive.names(docs.ref))
        val note = archive.entryNamed(archive.entryNamed(docs.ref, "nested").ref, "notes.bin")
        assertEquals(notes.size.toLong(), note.size)
        assertEquals(1_700_000_000_000L, note.modified)
        assertArrayEquals(notes, archive.openRead(note.ref).use { it.readBytes() })
        assertEquals("top level text", archive.text(memberRef(root, "docs/readme.txt")))
        assertEquals(StorageError.NOT_FOUND,
            storageFailure { runBlocking { archive.openRead(memberRef(root, "docs/missing.txt")) } }.reason)
    }

    @Test fun `a plain tar over a channel indexes from its headers alone and serves members by seeking`() = runBlocking {
        val bytes = tarBytes(ArchiveFormat.TAR, members)
        val source = ByteSource("bundle.tar", bytes)
        val archive = archiveProvider(source)

        val root = archive.open(source.ref, "bundle.tar")

        assertTrue("Only headers: ${source.bytesServed} of ${bytes.size}", source.bytesServed < notes.size / 4)
        check(archive, root)
        assertEquals("One channel for the index and every member", 1, source.channelOpens)
        assertEquals("Never walked as a stream", 0, source.streamOpens)
    }

    @Test fun `a plain tar is walked from the start when the source cannot seek`() = runBlocking {
        val source = ByteSource("bundle.tar", tarBytes(ArchiveFormat.TAR, members), seekable = false)
        val archive = archiveProvider(source)

        val root = archive.open(source.ref, "bundle.tar")

        check(archive, root)
        assertEquals(0, source.channelOpens)
        assertTrue("One walk for the index, one per member read", source.streamOpens >= 3)
        assertEquals("Every walk closed its handle", 0, source.openHandles)
    }

    @Test fun `compressed tars are decompressed and walked whether or not the source can seek`() = runBlocking {
        for (format in compressed) for (seekable in listOf(true, false)) {
            val name = "bundle.${format.extension}"
            val source = ByteSource(name, tarBytes(format, members), seekable = seekable)
            val archive = archiveProvider(source)

            val root = archive.open(source.ref, name)

            check(archive, root)
            assertEquals("$name: a channel is no use for a compressed tar", 0, source.channelOpens)
            assertEquals("$name: handles are released", 0, source.openHandles)
        }
    }

    @Test fun `a tar written from the current directory lists its real top level`() = runBlocking {
        // `tar -cf x.tar .` prefixes every member with "./" and adds a "." entry.
        val dotted: Members = linkedMapOf(
            "." to null,
            "./media" to null,
            "./docs/readme.txt" to bytesOf("top level text"),
            "./docs/nested/notes.bin" to notes,
        )
        for (seekable in listOf(true, false)) {
            val source = ByteSource("dotted.tar", tarBytes(ArchiveFormat.TAR, dotted), seekable = seekable)
            val archive = archiveProvider(source)

            val root = archive.open(source.ref, "dotted.tar")

            assertEquals("seekable=$seekable", listOf("docs", "media"), archive.names(root))
            assertEquals("top level text", archive.text(memberRef(root, "docs/readme.txt")))
            assertArrayEquals(notes, archive.openRead(memberRef(root, "docs/nested/notes.bin")).use { it.readBytes() })
        }
    }

    @Test fun `a zip with dot-slash prefixes lists its real top level`() = runBlocking {
        val source = ByteSource("dotted.zip", jdkZipBytes(linkedMapOf(
            "./docs/readme.txt" to bytesOf("top level text"),
            "./media/clip.bin" to bytesOf("clip"),
        )))
        val archive = archiveProvider(source)

        val root = archive.open(source.ref, "dotted.zip")

        assertEquals(listOf("docs", "media"), archive.names(root))
        assertEquals("top level text", archive.text(memberRef(root, "docs/readme.txt")))
    }

    @Test fun `opening a compressed tar reports a scan from the start against the whole size`() = runBlocking {
        val bytes = tarBytes(ArchiveFormat.TAR_GZ, members)
        val source = ByteSource("bundle.tgz", bytes)
        val archive = archiveProvider(source)
        val heard = mutableListOf<ArchiveReading>()

        archive.open(source.ref, "bundle.tgz") { synchronized(heard) { heard += it } }

        assertTrue(heard.isNotEmpty())
        assertTrue(heard.all { it.fromStart && it.total == bytes.size.toLong() })
        assertEquals("The whole file had to be read", bytes.size.toLong(), heard.last().bytesRead)
    }

    @Test fun `a compressed tar inside an archive counts as being where the archive is`() = runBlocking {
        val inner = tarBytes(ArchiveFormat.TAR_GZ, members)
        val outer = jdkZipBytes(linkedMapOf("inner.tar.gz" to inner, "inner.tar" to tarBytes(ArchiveFormat.TAR, members)))
        for (network in listOf(false, true)) {
            val source = ByteSource("outer.zip", outer, network = network)
            val archive = archiveProvider(source)
            val engine = ArchiveEngine(ProviderRegistry(listOf(source, archive)))
            val root = archive.open(source.ref, "outer.zip")

            val compressed = archive.entryNamed(root, "inner.tar.gz")
            val plain = archive.entryNamed(root, "inner.tar")

            assertEquals("network=$network", !network, engine.canBrowse(compressed))
            assertTrue(engine.canBrowse(plain))
            val nested = archive.open(compressed.ref, "inner.tar.gz")
            check(archive, nested)
        }
    }
}
