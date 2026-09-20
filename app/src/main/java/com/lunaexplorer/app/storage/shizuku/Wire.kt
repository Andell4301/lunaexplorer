package com.lunaexplorer.app.storage.shizuku

import com.lunaexplorer.app.storage.LocalRoot
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.RootKind
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** What crosses between Luna and its helper process. Both ends are this same build, so the format need not be stable. */
internal object Wire {
    /** Raised with any change to IFileHelper or to what its strings hold. */
    const val PROTOCOL = 1

    /** Bytes in one call of the descriptor-free transport. Every call in flight in a process shares one 1 MB binder buffer. */
    const val BLOCK = 64 * 1024

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Serializable
    data class Item(
        val key: String, val name: String, val directory: Boolean, val size: Long? = null, val modified: Long? = null,
        val mimeType: String, val capabilities: List<Capability>, val hidden: Boolean, val link: Boolean = false,
        val version: String? = null,
    )

    @Serializable
    data class Root(val id: String, val title: String, val path: String, val kind: RootKind, val followLinks: Boolean, val hidden: Boolean)

    /** [retained] are what a replacing commit left behind, as key and message. */
    @Serializable
    data class Committed(val item: Item, val retained: List<Pair<String, String>> = emptyList())

    fun item(entry: Entry) = Item(entry.ref.key, entry.name, entry.directory, entry.size, entry.modified, entry.mimeType,
        entry.capabilities.toList(), entry.hidden, entry.link, entry.version)

    fun entry(provider: String, item: Item) = Entry(NodeRef(provider, item.key), item.name, item.directory, item.size, item.modified,
        item.mimeType, item.capabilities.toSet(), item.hidden, item.link, item.version)

    fun root(root: LocalRoot) = Root(root.id, root.title, root.path.path, root.kind, root.followLinks, root.hidden)

    fun root(root: Root) = LocalRoot(root.id, root.title, File(root.path), root.kind, root.followLinks, root.hidden)

    fun failure(error: StorageException) = IllegalStateException("${error.reason.name}\n${error.message.orEmpty()}")

    /** Null when [error] is not one of the helper's own, such as a fault inside binder. */
    fun failure(error: IllegalStateException): StorageException? {
        val text = error.message ?: return null
        val reason = StorageError.entries.firstOrNull { it.name == text.substringBefore('\n') } ?: return null
        return StorageException(reason, text.substringAfter('\n', ""))
    }
}
