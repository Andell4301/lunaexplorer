package com.lunaexplorer.core

import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ArchiveAliasLookupTest {
    @Test fun `a lookup that echoes requested spelling cannot replace a case colliding member`() = runBlocking {
        val source = archive()
        val target = AliasLookupStorage()
        val engine = ArchiveEngine(ProviderRegistry(listOf(source, target)))

        val failure = runCatching { engine.extract(source.ref, target.root, null).last() }.exceptionOrNull()

        assertTrue(failure is StorageException)
        assertEquals(StorageError.CONFLICT, (failure as StorageException).reason)
        assertEquals("upper", target.text("res/3H.xml"))
        assertTrue(target.deleted.isEmpty())
        val folder = requireNotNull(target.child(target.root, "res"))
        assertEquals(listOf("3H.xml"), target.list(folder.ref).toList().flatten().map { it.name })
    }

    @Test fun `keep both preserves members when lookups echo requested spelling`() = runBlocking {
        val source = archive()
        val target = AliasLookupStorage()
        val engine = ArchiveEngine(ProviderRegistry(listOf(source, target)))

        engine.extract(source.ref, target.root, null, verify = true, keepBoth = true).last()

        assertEquals("upper", target.text("res/3H.xml"))
        assertEquals("lower", target.text("res/3h (1).xml"))
        assertTrue(target.deleted.isEmpty())
        val folder = requireNotNull(target.child(target.root, "res"))
        assertEquals(setOf("3H.xml", "3h (1).xml"), target.list(folder.ref).toList().flatten().map { it.name }.toSet())
    }

    private fun archive() = ByteSource("app-release.zip", jdkZipBytes(linkedMapOf(
        "res/3H.xml" to bytesOf("upper"), "res/3h.xml" to bytesOf("lower"))))

    private class AliasLookupStorage(
        private val storage: MemoryStorageProvider = MemoryStorageProvider("disk").apply { caseInsensitive = true },
    ) : StorageProvider by storage {
        val root = NodeRef(id, "")
        val deleted get() = storage.deleted

        override fun namesEqual(first: String, second: String) = first == second

        override suspend fun stat(ref: NodeRef) = storage.stat(resolve(ref)).copy(ref = ref, name = ref.key.substringAfterLast('/'))

        override fun list(parent: NodeRef, complete: Boolean) = storage.list(resolve(parent), complete).map { batch ->
            batch.map { it.copy(ref = childRef(parent, it.name)) }
        }

        override suspend fun child(parent: NodeRef, name: String): Entry? =
            storage.childRef(resolve(parent), name)?.let {
                storage.stat(it).copy(ref = childRef(parent, name), name = name)
            }

        override suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String) =
            storage.create(resolve(parent), name, directory, mimeType).copy(ref = childRef(parent, name))

        override suspend fun delete(ref: NodeRef) = storage.delete(resolve(ref))
        override suspend fun openRead(ref: NodeRef) = storage.openRead(resolve(ref))
        override suspend fun openWrite(ref: NodeRef) = storage.openWrite(resolve(ref))

        fun text(path: String) = storage.text(resolve(NodeRef(id, path)))

        private fun childRef(parent: NodeRef, name: String) =
            NodeRef(id, if (parent.key.isEmpty()) name else "${parent.key}/$name")

        private fun resolve(ref: NodeRef): NodeRef = if (ref.key.isEmpty()) storage.root else {
            ref.key.split('/').fold(storage.root) { parent, name ->
                storage.childRef(parent, name) ?: throw StorageException(StorageError.NOT_FOUND, "Not found")
            }
        }
    }
}
