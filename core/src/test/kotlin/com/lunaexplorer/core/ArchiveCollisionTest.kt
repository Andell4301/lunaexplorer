package com.lunaexplorer.core

import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.*
import org.junit.Test

class ArchiveCollisionTest {
    @Test fun `case colliding archive members never replace an earlier extracted file`() = runBlocking {
        val storage = MemoryStorageProvider("disk").apply { caseInsensitive = true }
        val archive = storage.create(storage.root, "app-release.zip", false).ref
        storage.openWrite(archive).use { it.write(jdkZipBytes(linkedMapOf(
            "res/3H.xml" to bytesOf("upper"), "res/3h.xml" to bytesOf("lower")))) }
        val destination = storage.folder(storage.root, "extracted")
        val engine = ArchiveEngine(ProviderRegistry(listOf(storage)))

        val failure = runCatching { engine.extract(archive, destination, null).last() }.exceptionOrNull()

        assertTrue(failure is StorageException)
        assertEquals(StorageError.CONFLICT, (failure as StorageException).reason)
        val res = requireNotNull(storage.childRef(destination, "res"))
        assertEquals("upper", storage.text(requireNotNull(storage.childRef(res, "3H.xml"))))
        assertTrue(storage.exists(archive))
    }

    @Test fun `keep both uses destination lookup for case collisions and occupied suffixes`() = runBlocking {
        val storage = MemoryStorageProvider("disk").apply { caseInsensitive = true }
        val provider = object : StorageProvider by storage {
            override fun namesEqual(first: String, second: String) = first == second
            override suspend fun child(parent: NodeRef, name: String) = storage.childRef(parent, name)?.let { storage.stat(it) }
        }
        val archive = zip(storage, linkedMapOf("3H.xml" to "upper", "3h.xml" to "lower"))
        val destination = storage.folder(storage.root, "extracted")
        val occupied = storage.file(destination, "3H (1).xml", "existing")

        ArchiveEngine(ProviderRegistry(listOf(provider))).extract(archive, destination, null, keepBoth = true).last()

        assertEquals("upper", text(storage, destination, "3H.xml"))
        assertEquals("lower", text(storage, destination, "3h (2).xml"))
        assertEquals("existing", storage.text(occupied))
        assertTrue(storage.deleted.isEmpty())
    }

    @Test fun `keep both preserves case distinct names on a case sensitive destination`() = runBlocking {
        val storage = MemoryStorageProvider("disk")
        val archive = zip(storage, linkedMapOf("3H.xml" to "upper", "3h.xml" to "lower"))
        val destination = storage.folder(storage.root, "extracted")

        ArchiveEngine(ProviderRegistry(listOf(storage))).extract(archive, destination, null, keepBoth = true).last()

        assertEquals("upper", text(storage, destination, "3H.xml"))
        assertEquals("lower", text(storage, destination, "3h.xml"))
        assertNull(storage.childRef(destination, "3h (1).xml"))
    }

    @Test fun `keep both consistently maps renamed folders including later directory entries`() = runBlocking {
        val storage = MemoryStorageProvider("disk").apply { caseInsensitive = true }
        val archive = zip(storage, linkedMapOf(
            "Res/one.txt" to "one", "res/two.txt" to "two", "res/" to "", "res/three.txt" to "three"))
        val destination = storage.folder(storage.root, "extracted")
        val occupied = storage.file(destination, "res (1)", "existing")

        ArchiveEngine(ProviderRegistry(listOf(storage))).extract(archive, destination, null, keepBoth = true).last()

        val first = requireNotNull(storage.childRef(destination, "Res"))
        val second = requireNotNull(storage.childRef(destination, "res (2)"))
        assertEquals("one", text(storage, first, "one.txt"))
        assertEquals("two", text(storage, second, "two.txt"))
        assertEquals("three", text(storage, second, "three.txt"))
        assertEquals("existing", storage.text(occupied))
        assertNull(storage.childRef(destination, "res (3)"))
    }

    @Test fun `keep both preserves existing files and folders during loose extraction`() = runBlocking {
        val storage = MemoryStorageProvider("disk")
        val archive = zip(storage, linkedMapOf("same.txt" to "new", "folder/item" to "item"))
        val destination = storage.folder(storage.root, "extracted")
        val file = storage.file(destination, "same.txt", "existing")
        val folder = storage.folder(destination, "folder")
        val nested = storage.file(folder, "item", "existing nested")

        ArchiveEngine(ProviderRegistry(listOf(storage))).extract(archive, destination, null, keepBoth = true).last()

        assertEquals("existing", storage.text(file))
        assertEquals("existing nested", storage.text(nested))
        assertEquals("new", text(storage, destination, "same (1).txt"))
        assertEquals("item", text(storage, requireNotNull(storage.childRef(destination, "folder (1)")), "item"))
    }

    @Test fun `keep both chooses a new containing folder when the requested folder exists`() = runBlocking {
        val storage = MemoryStorageProvider("disk")
        val archive = zip(storage, linkedMapOf("item" to "contents"))
        val existing = storage.folder(storage.root, "extracted")
        val file = storage.file(existing, "item", "existing")

        val result = ArchiveEngine(ProviderRegistry(listOf(storage)))
            .extract(archive, storage.root, "extracted", keepBoth = true).last()

        assertEquals(storage.childRef(storage.root, "extracted (1)"), result.produced)
        assertEquals("contents", text(storage, requireNotNull(result.produced), "item"))
        assertEquals("existing", storage.text(file))
    }

    @Test fun `keep both does not retry a create whose outcome is unknown`() = runBlocking {
        val storage = MemoryStorageProvider("disk")
        val archive = zip(storage, linkedMapOf("item" to "contents"))
        val destination = storage.folder(storage.root, "extracted")
        val existing = storage.file(destination, "item", "existing")
        var attempts = 0
        val provider = object : StorageProvider by storage {
            override suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String): Entry {
                attempts++
                storage.create(parent, name, directory, mimeType)
                throw StorageException(StorageError.IO, "Connection lost after create")
            }
        }

        val failure = runCatching {
            ArchiveEngine(ProviderRegistry(listOf(provider))).extract(archive, destination, null, keepBoth = true).last()
        }.exceptionOrNull()

        assertTrue(failure is StorageException)
        assertEquals(StorageError.IO, (failure as StorageException).reason)
        assertEquals(1, attempts)
        assertEquals("existing", storage.text(existing))
        assertNotNull(storage.childRef(destination, "item (1)"))
        assertNull(storage.childRef(destination, "item (2)"))
        assertTrue(storage.exists(archive))
    }

    @Test fun `keep both allows verified extraction and removal of an archive with case collisions`() = runBlocking {
        val storage = MemoryStorageProvider("disk").apply { caseInsensitive = true }
        val archive = zip(storage, linkedMapOf("res/3H.xml" to "upper", "res/3h.xml" to "lower"))
        val destination = storage.folder(storage.root, "extracted")

        val result = OperationEngine(ProviderRegistry(listOf(storage))).run(OperationRequest(
            type = OperationType.EXTRACT_ARCHIVE, sources = listOf(archive), destination = destination,
            archiveKeepBoth = true, deleteAfterArchive = true))

        assertTrue(result.successful)
        val res = requireNotNull(storage.childRef(destination, "res"))
        assertEquals("upper", text(storage, res, "3H.xml"))
        assertEquals("lower", text(storage, res, "3h (1).xml"))
        assertFalse(storage.exists(archive))
    }

    @Test fun `keep both preserves exact duplicate tar members`() = runBlocking {
        val storage = MemoryStorageProvider("disk")
        val archive = storage.create(storage.root, "duplicate.tar", false).ref
        storage.openWrite(archive).use { output ->
            TarArchiveOutputStream(output).use { tar ->
                for (text in listOf("one", "two")) {
                    tar.putArchiveEntry(TarArchiveEntry("same.txt").apply { size = 3 })
                    tar.write(text.toByteArray())
                    tar.closeArchiveEntry()
                }
            }
        }
        val destination = storage.folder(storage.root, "extracted")

        ArchiveEngine(ProviderRegistry(listOf(storage))).extract(archive, destination, null,
            verify = true, keepBoth = true).last()

        assertEquals("one", text(storage, destination, "same.txt"))
        assertEquals("two", text(storage, destination, "same (1).txt"))
    }

    private suspend fun zip(storage: MemoryStorageProvider, members: Map<String, String>): NodeRef {
        val archive = storage.create(storage.root, "app-release.zip", false).ref
        storage.openWrite(archive).use { it.write(jdkZipBytes(members.mapValues { (_, value) -> bytesOf(value) })) }
        return archive
    }

    private fun text(storage: MemoryStorageProvider, parent: NodeRef, name: String) =
        storage.text(requireNotNull(storage.childRef(parent, name)))
}
