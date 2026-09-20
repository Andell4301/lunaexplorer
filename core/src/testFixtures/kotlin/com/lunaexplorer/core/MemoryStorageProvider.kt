package com.lunaexplorer.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class MemoryStorageProvider(override val id: String) : StorageProvider {
    private class Node(val ref: NodeRef, var parent: NodeRef?, var name: String, val directory: Boolean,
        var data: ByteArray = byteArrayOf(), var modified: Long = 1)
    private val nodes = linkedMapOf<NodeRef, Node>()
    private var sequence = 0
    val root = insert(null, "storage", true)
    var atomicReplace = true
    var recoverableReplace = true
    /** False models a provider without rename support, such as Android MTP. */
    var canRename = true
    var canRelocate = false
    val relocated = mutableListOf<NodeRef>()
    var retainReplacedTarget = false
    var caseInsensitive = false
    var unknownMetadata = false
    var freeBytes: Long? = null
    var writeFailure: StorageError? = null
    var rawWriteFailure: IOException? = null
    var commitFailure: StorageError? = null
    var corruptStageReads = false
    var failStageCleanup = false
    var afterCommit: (() -> Unit)? = null
    var beforeDelete: ((NodeRef) -> Unit)? = null
    val deniedCapabilities = mutableMapOf<NodeRef, Set<Capability>>()
    val deleteFailures = mutableSetOf<NodeRef>()
    val listCap = mutableMapOf<NodeRef, Int>()
    var listings = 0
    val deleted = mutableListOf<NodeRef>()

    fun folder(parent: NodeRef, name: String): NodeRef = insert(parent, name, true)
    fun file(parent: NodeRef, name: String, text: String): NodeRef = insert(parent, name, false).also { setText(it, text) }
    fun setText(ref: NodeRef, text: String) { node(ref).data = text.toByteArray(); node(ref).modified++ }
    fun text(ref: NodeRef) = node(ref).data.decodeToString()
    fun exists(ref: NodeRef) = ref in nodes
    override fun namesEqual(first: String, second: String) = first.equals(second, ignoreCase = caseInsensitive)
    fun childRef(parent: NodeRef, name: String) = nodes.values.firstOrNull { it.parent == parent && namesEqual(it.name, name) }?.ref
    fun hasStages() = nodes.values.any { it.name.startsWith(".luna-") }
    fun under(ref: NodeRef, ancestor: NodeRef): Boolean {
        var cursor: NodeRef? = ref
        while (cursor != null) {
            if (cursor == ancestor) return true
            cursor = nodes[cursor]?.parent
        }
        return false
    }
    private fun inStage(ref: NodeRef): Boolean {
        var cursor: NodeRef? = ref
        while (cursor != null) {
            val entry = node(cursor)
            if (entry.name.startsWith(".luna-")) return true
            cursor = entry.parent
        }
        return false
    }
    private fun insert(parent: NodeRef?, name: String, directory: Boolean): NodeRef {
        if (parent != null && childRef(parent, name) != null) throw StorageException(StorageError.CONFLICT, "Name exists")
        val ref = NodeRef(id, "opaque-${++sequence}")
        nodes[ref] = Node(ref, parent, name, directory)
        return ref
    }
    private fun node(ref: NodeRef) = nodes[ref] ?: throw StorageException(StorageError.NOT_FOUND, "Item disappeared")
    private fun entry(ref: NodeRef): Entry {
        val node = node(ref)
        val supported = Capability.entries.toMutableSet()
        if (!atomicReplace) supported -= Capability.ATOMIC_REPLACE
        if (!recoverableReplace) supported -= Capability.REPLACE
        if (!canRename) supported -= Capability.RENAME
        supported.removeAll(deniedCapabilities[ref].orEmpty())
        return Entry(ref, node.name, node.directory, if (node.directory || unknownMetadata) null else node.data.size.toLong(),
            if (unknownMetadata) null else node.modified,
            capabilities = supported,
            version = node.modified.toString())
    }
    override suspend fun stat(ref: NodeRef) = entry(ref)
    override val features: Set<Feature> = setOf(Feature.STABLE_KEYS)
    override suspend fun roots(): List<StorageRoot> = listOf(StorageRoot(root, "storage"))
    override suspend fun parentOf(ref: NodeRef): NodeRef? = node(ref).parent
    override fun list(parent: NodeRef, complete: Boolean): Flow<List<Entry>> = flow {
        if (!node(parent).directory) throw StorageException(StorageError.UNSUPPORTED, "Not a folder")
        listings++
        var refs = nodes.values.filter { it.parent == parent }.map { it.ref }
        // A truncated listing: returns a prefix without reporting the omission.
        listCap[parent]?.let { cap -> refs = refs.take(cap) }
        for (batch in refs.chunked(2)) emit(batch.map { entry(it) })
    }
    override suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String): Entry {
        validateName(name)
        return entry(insert(parent, name, directory))
    }
    override suspend fun rename(ref: NodeRef, name: String): Entry {
        validateName(name)
        val item = node(ref)
        if (item.parent?.let { childRef(it, name) } != null) throw StorageException(StorageError.CONFLICT, "Name exists")
        item.name = name
        return entry(ref)
    }
    override suspend fun delete(ref: NodeRef) {
        // Real providers switch to an IO dispatcher here and so throw once cancelled. Without this
        // check, cleanup that forgot NonCancellable would pass against this fake.
        currentCoroutineContext().ensureActive()
        beforeDelete?.invoke(ref)
        if (ref in deleteFailures || (failStageCleanup && inStage(ref))) throw StorageException(StorageError.PERMISSION, "Delete access revoked")
        if (nodes.values.any { it.parent == ref }) throw StorageException(StorageError.CONFLICT, "Directory is not empty")
        node(ref)
        nodes.remove(ref)
        deleted += ref
    }
    override suspend fun openRead(ref: NodeRef): InputStream {
        val data = node(ref).data.copyOf()
        if (corruptStageReads && inStage(ref) && data.isNotEmpty()) data[0] = (data[0].toInt() xor 1).toByte()
        return ByteArrayInputStream(data)
    }
    override suspend fun openWrite(ref: NodeRef): OutputStream = object : ByteArrayOutputStream() {
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            rawWriteFailure?.let { throw it }
            writeFailure?.let {
                if (length > 0) super.write(bytes, offset, 1)
                throw StorageException(it, "Storage failed during write")
            }
            super.write(bytes, offset, length)
        }
        override fun close() { node(ref).data = toByteArray(); super.close() }
    }
    override suspend fun relocate(ref: NodeRef, parent: NodeRef, name: String): Entry? {
        if (!canRelocate) return null
        if (childRef(parent, name) != null) throw StorageException(StorageError.CONFLICT, "Name exists")
        relocated += ref
        node(ref).parent = parent
        node(ref).name = name
        return entry(ref)
    }
    override suspend fun availableBytes(parent: NodeRef) = freeBytes
    override suspend fun isDescendant(candidate: NodeRef, ancestor: NodeRef) = under(candidate, ancestor)
    override suspend fun commit(staged: NodeRef, parent: NodeRef, name: String, replace: Entry?, onRetained: RetainedObjects?): Entry {
        commitFailure?.let { throw StorageException(it, "Commit failed") }
        val existing = childRef(parent, name)
        if (existing != replace?.ref) throw StorageException(StorageError.CONFLICT, "Destination changed")
        if (replace != null) {
            if (entry(replace.ref).version != replace.version) throw StorageException(StorageError.CONFLICT, "The item being replaced changed")
            if (!atomicReplace && !recoverableReplace) throw StorageException(StorageError.UNSUPPORTED, "No safe replace")
            if (node(replace.ref).directory) throw StorageException(StorageError.UNSUPPORTED, "Cannot replace directory")
            if (retainReplacedTarget) {
                // Publication succeeds but the backup cannot be deleted.
                node(replace.ref).name = ".luna-replaced-$name"
                onRetained?.invoke(replace.ref, "Retained the previous $name")
            } else {
                nodes.remove(replace.ref)
            }
        }
        node(staged).parent = parent
        node(staged).name = name
        val result = entry(staged)
        afterCommit?.invoke()
        return result
    }
}
