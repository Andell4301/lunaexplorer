package com.lunaexplorer.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/** Opaque, provider-owned identity. Callers must never interpret [key] as a path. */
@Serializable
data class NodeRef(val provider: String, val key: String)

/**
 * [ATOMIC_REPLACE] and [REPLACE] are declared on the parent folder and describe how it can publish
 * a staged file over an existing child. Atomic replacement swaps in one filesystem step. Recoverable
 * replacement keeps the previous target under a backup name until the new content is published, and
 * restores it if publication fails.
 */
enum class Capability { READ, LIST, CREATE, WRITE, RENAME, DELETE, ATOMIC_REPLACE, REPLACE, HIDDEN }

data class Entry(
    val ref: NodeRef,
    val name: String,
    val directory: Boolean,
    val size: Long? = null,
    val modified: Long? = null,
    val mimeType: String = "application/octet-stream",
    val capabilities: Set<Capability> = emptySet(),
    val hidden: Boolean = name.startsWith("."),
    /** A followed link. Recursive operations must treat it as a leaf. */
    val link: Boolean = false,
    /**
     * Provider-specific change token (filesystem key, etag, server file id), or null. Kept out of
     * [NodeRef] so ref equality depends only on the address.
     */
    val version: String? = null,
)

enum class RootKind { INTERNAL, SD_CARD, USB, SYSTEM, APP, FOLDER, NETWORK }

data class StorageRoot(
    val ref: NodeRef,
    val title: String,
    val description: String = "",
    val kind: RootKind = RootKind.FOLDER,
    val totalBytes: Long? = null,
    val freeBytes: Long? = null,
    val readOnly: Boolean = false,
    /** The user can remove this root, e.g. by releasing a folder grant or removing an account. */
    val removable: Boolean = false,
    /** Addressable by path but left out of storage lists, e.g. the app-data directory. */
    val hidden: Boolean = false,
)

/**
 * [PERMISSION] concerns device access; [AUTH] concerns server authentication. [DISCONNECTED] covers
 * an unavailable volume or provider process; [OFFLINE], [TIMEOUT] and [RATE_LIMITED] are network
 * failures.
 */
enum class StorageError {
    PERMISSION, NOT_FOUND, DISCONNECTED, NO_SPACE, CONFLICT, UNSUPPORTED, INVALID_NAME, IO,
    AUTH, OFFLINE, TIMEOUT, RATE_LIMITED,
}

/** Provider-wide capabilities. Per-entry operations are described by [Capability]. */
enum class Feature {
    /** References to the same address compare equally across lookups and listings. */
    STABLE_KEYS,
    /** [StorageProvider.setModified] is honoured. */
    SET_TIMES,
    /** [StorageProvider.openChannel] returns a channel; bytes can be read from any offset. */
    RANGE_READ,
    /** Reads require network access and may need bandwidth limits. */
    NETWORK,
    /** Old versions of a name are kept, and an overwrite adds one. Removing a name removes them all unless the removal carries [KeepVersions]. */
    VERSIONED,
    /**
     * Folders are name prefixes: one exists only while something lies under it, and renaming or relocating
     * one copies everything beneath it, so a failure can leave that partly done. Each file still appears whole.
     */
    PREFIX_FOLDERS,
}

/** Implemented by providers whose references map to local filesystem paths. */
interface PathAddressable {
    /** A path the caller may open itself. Null when there is none, or when only the provider can open it. */
    fun pathOf(ref: NodeRef): String?
    /** The path to show for [ref], which this process may not be able to open. */
    fun shownPathOf(ref: NodeRef): String? = pathOf(ref)
    /** Reference for a path within this provider's roots, or null if no root contains it. */
    fun refFor(path: String): NodeRef?
}

/** Reads whose network payload, including provider read-ahead, is charged to a [ReadBudget]. */
interface BudgetedReads {
    suspend fun openRead(ref: NodeRef, budget: ReadBudget): InputStream
    suspend fun openChannel(ref: NodeRef, budget: ReadBudget): SeekableByteChannel? = null
}

/** A provider that can give everything under a folder for less than a walk of its folders would cost. */
interface DeepListing {
    /**
     * Every entry under [root], at any depth, in batches. With [hidden] false, a hidden entry and
     * everything beneath a hidden folder are left out, as a walk that skips them would.
     */
    fun listDeep(root: NodeRef, hidden: Boolean): Flow<List<Entry>>
}

/** A provider that answers listings from a saved copy until told to forget it. */
interface CachedListings {
    /** Drops the saved listing of [folder], with those of everything under it when [below]; every saved listing when null. */
    suspend fun forget(folder: NodeRef?, below: Boolean = false)
    suspend fun cachedBytes(): Long
}

class StorageException(
    val reason: StorageError,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Called by [StorageProvider.commit] with a backup it could not delete and a message for the journal. */
typealias RetainedObjects = suspend (NodeRef, String) -> Unit

/**
 * Returned streams block and must be used on an IO dispatcher. Listings emit bounded batches, not
 * cumulative snapshots. Unknown size or time stays null. Mutations must fail explicitly; create and
 * rename must not replace an existing item.
 */
interface StorageProvider {
    val id: String
    val features: Set<Feature> get() = emptySet()
    /** Whether two names collide in this provider, e.g. case-insensitively. */
    fun namesEqual(first: String, second: String): Boolean = first == second
    suspend fun roots(): List<StorageRoot> = emptyList()
    /** Removes a [StorageRoot.removable] root by releasing its grant or removing its account. */
    suspend fun forgetRoot(root: StorageRoot) {
        throw StorageException(StorageError.UNSUPPORTED, "This location cannot be removed")
    }
    /** Returns the stored value, which the filesystem may have rounded. Requires [Feature.SET_TIMES]. */
    suspend fun setModified(ref: NodeRef, epochMillis: Long): Long {
        throw StorageException(StorageError.UNSUPPORTED, "This storage provider does not allow the modification time to be changed")
    }
    suspend fun stat(ref: NodeRef): Entry
    /**
     * Children in bounded batches. Listed entries may report reduced capabilities to avoid extra
     * probes; [stat] is authoritative before a mutation.
     *
     * A default listing may be partial. With [complete] (deletion, name allocation) an incomplete
     * listing must fail instead.
     */
    fun list(parent: NodeRef, complete: Boolean = false): Flow<List<Entry>>
    /**
     * The child of [parent] named [name], or null. The default scans a complete listing; override
     * with a direct lookup where the backend has one.
     */
    suspend fun child(parent: NodeRef, name: String): Entry? =
        list(parent, complete = true).map { batch -> batch.firstOrNull { namesEqual(it.name, name) } }.firstOrNull { it != null }
    /** The folder holding [ref], or null at a root or where the backend cannot say. */
    suspend fun parentOf(ref: NodeRef): NodeRef? = null
    /** Non-fatal status messages, such as a pending folder or a partial listing. */
    val notices: Flow<String> get() = emptyFlow()
    suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String = "application/octet-stream"): Entry
    suspend fun rename(ref: NodeRef, name: String): Entry
    /** Deletes exactly this item. Directories must be empty; never recursive. */
    suspend fun delete(ref: NodeRef)
    suspend fun openRead(ref: NodeRef): InputStream
    /** Random access with a known length, or null if only streaming is supported. */
    suspend fun openChannel(ref: NodeRef): SeekableByteChannel? = null
    /** For newly created staging or in-place items only. Must not truncate an existing user file. */
    suspend fun openWrite(ref: NodeRef): OutputStream
    /**
     * Moves an item into [parent] under [name] in one provider operation, without copying bytes.
     * Must not replace an existing target. Returns null if unsupported; the caller then falls back
     * to a verified copy followed by source deletion.
     */
    suspend fun relocate(ref: NodeRef, parent: NodeRef, name: String): Entry? = null

    suspend fun availableBytes(parent: NodeRef): Long? = null
    /** True for self or a descendant. Must fail closed if ancestry cannot be established. */
    suspend fun isDescendant(candidate: NodeRef, ancestor: NodeRef): Boolean
    /**
     * Publishes a completed staging file. Replacement requires ATOMIC_REPLACE or REPLACE on
     * [parent] and must verify that the target still matches [replace]'s [Entry.version].
     *
     * Atomic providers swap the target in one step. Recoverable providers keep the old target as a
     * backup and restore it if publication fails; a backup that cannot be deleted afterwards is
     * reported through [onRetained].
     */
    suspend fun commit(
        staged: NodeRef,
        parent: NodeRef,
        name: String,
        replace: Entry? = null,
        onRetained: RetainedObjects? = null,
    ): Entry
}

/**
 * A provider that holds a run's writes until [flush], because any change rewrites the whole
 * container (an archive). Pending writes must already be visible through [StorageProvider.list] and
 * [StorageProvider.stat], so conflict checks, merges and verification see them.
 */
interface DeferredWrites {
    suspend fun flush(run: StorageRun)
    suspend fun discardPending(run: StorageRun)
}

/**
 * Coroutine context element identifying one run. A [DeferredWrites] provider reads it to keep each
 * run's pending writes separate. A write made outside any run is applied immediately.
 */
class StorageRun : AbstractCoroutineContextElement(StorageRun) {
    companion object Key : CoroutineContext.Key<StorageRun>
}

/**
 * Coroutine context element asking a [Feature.VERSIONED] provider to hide what it removes, so the
 * stored versions survive. Without it a delete, or the source of a move, loses every version. The
 * engine sets it only around what the user asked to have removed, never around its own staging data.
 */
class KeepVersions : AbstractCoroutineContextElement(KeepVersions) {
    companion object Key : CoroutineContext.Key<KeepVersions>
}

class ProviderRegistry(providers: List<StorageProvider>) {
    private val registered = CopyOnWriteArrayList(providers)
    val all: List<StorageProvider> get() = registered.toList()
    fun provider(ref: NodeRef): StorageProvider = registered.firstOrNull { it.id == ref.provider }
        ?: throw StorageException(StorageError.DISCONNECTED, "Storage provider '${ref.provider}' is unavailable")

    fun register(provider: StorageProvider) {
        registered.removeAll { it.id == provider.id }
        registered.add(provider)
    }
    /**
     * Collects roots concurrently, with a separate deadline for each provider. Results keep
     * registration order; a slow or failed provider contributes nothing.
     */
    suspend fun roots(budgetMillis: Long = 3_000): List<StorageRoot> = supervisorScope {
        all.map { provider ->
            async {
                withTimeoutOrNull(budgetMillis) {
                    try {
                        provider.roots()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                        emptyList()
                    }
                } ?: emptyList()
            }
        }.awaitAll().flatten()
    }
}

fun validateName(name: String) {
    if (name.isBlank() || name == "." || name == ".." || name.length > 255 ||
        name.any { it == '/' || it == '\\' || it == '\u0000' || it.code < 32 }) {
        throw StorageException(StorageError.INVALID_NAME, "Use a name of 1-255 characters without slashes or control characters")
    }
}
