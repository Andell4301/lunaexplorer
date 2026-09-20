package com.lunaexplorer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class RecycleBinTest {
    @Test fun `an unavailable root does not hide a usable root for the bin`() = runBlocking {
        val storage = MemoryStorageProvider("test")
        val unavailable = NodeRef(storage.id, "stale grant")
        val provider = object : StorageProvider by storage {
            override suspend fun roots() = listOf(
                StorageRoot(unavailable, "stale"), StorageRoot(storage.root, "available"),
            )
            override suspend fun isDescendant(candidate: NodeRef, ancestor: NodeRef): Boolean {
                if (ancestor == unavailable) throw StorageException(StorageError.DISCONNECTED, "Unavailable")
                return storage.isDescendant(candidate, ancestor)
            }
        }

        val bin = RecycleBin(ProviderRegistry(listOf(provider))).folderFor(storage.root)

        assertEquals(storage.root, bin?.let { storage.parentOf(it.ref) })
    }

    @Test fun `overlapping roots use provider ancestry rather than opaque key lengths`() = runBlocking {
        val storage = MemoryStorageProvider("test")
        val nested = storage.folder(storage.root, "granted")
        val file = storage.file(nested, "note.txt", "content")
        val provider = object : StorageProvider by storage {
            override suspend fun roots() = listOf(
                StorageRoot(storage.root, "volume"), StorageRoot(nested, "grant"),
            )
        }

        val bin = RecycleBin(ProviderRegistry(listOf(provider))).folderFor(file)!!

        assertEquals(nested, storage.parentOf(bin.ref))
        assertNull(storage.childRef(storage.root, RecycleBin.FOLDER))
    }

    @Test fun `a failed bin lookup does not attempt to create one`() = runBlocking {
        val storage = MemoryStorageProvider("test")
        var created = false
        val provider = object : StorageProvider by storage {
            override suspend fun child(parent: NodeRef, name: String): Entry? =
                throw StorageException(StorageError.OFFLINE, "Offline")
            override suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String): Entry {
                created = true
                return storage.create(parent, name, directory, mimeType)
            }
        }

        assertNull(RecycleBin(ProviderRegistry(listOf(provider))).folderFor(storage.root))
        assertFalse(created)
    }

    @Test fun `cancellation during bin discovery or creation propagates`() = runBlocking {
        for (stage in listOf("roots", "ancestry", "stat", "child", "create")) {
            val storage = MemoryStorageProvider("test")
            val cancelled = CancellationException(stage)
            val provider = object : StorageProvider by storage {
                override suspend fun roots(): List<StorageRoot> {
                    if (stage == "roots") throw cancelled
                    return storage.roots()
                }
                override suspend fun isDescendant(candidate: NodeRef, ancestor: NodeRef): Boolean {
                    if (stage == "ancestry") throw cancelled
                    return storage.isDescendant(candidate, ancestor)
                }
                override suspend fun stat(ref: NodeRef): Entry {
                    if (stage == "stat") throw cancelled
                    return storage.stat(ref)
                }
                override suspend fun child(parent: NodeRef, name: String): Entry? {
                    if (stage == "child") throw cancelled
                    return storage.child(parent, name)
                }
                override suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String): Entry {
                    if (stage == "create") throw cancelled
                    return storage.create(parent, name, directory, mimeType)
                }
            }
            try {
                RecycleBin(ProviderRegistry(listOf(provider))).folderFor(storage.root)
                fail("Cancellation during $stage was swallowed")
            } catch (failure: CancellationException) {
                assertEquals(cancelled.message, failure.message)
            }
        }
    }
}
