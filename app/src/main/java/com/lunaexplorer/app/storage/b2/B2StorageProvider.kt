package com.lunaexplorer.app.storage.b2

import com.lunaexplorer.app.debug.DebugLog
import com.lunaexplorer.app.storage.RemoteChannel
import com.lunaexplorer.app.storage.RemoteReading
import com.lunaexplorer.core.BudgetedReads
import com.lunaexplorer.core.CachedListings
import com.lunaexplorer.core.DeepListing
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.Feature
import com.lunaexplorer.core.KeepVersions
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.ReadBudget
import com.lunaexplorer.core.RetainedObjects
import com.lunaexplorer.core.RootKind
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import com.lunaexplorer.core.StorageProvider
import com.lunaexplorer.core.StorageRoot
import com.lunaexplorer.core.validateName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.ConcurrentHashMap

// Empty folders use zero-byte markers because B2 stores only objects and name prefixes.
class B2StorageProvider(
    private val accounts: () -> List<B2Account>,
    /** Null while the credential vault is locked. */
    private val withKey: (B2Account) -> B2Account?,
    private val connector: B2Connector,
    private val cache: B2ListingCache,
    /** Holds bytes on their way up; see [B2UploadStream]. */
    private val staging: File,
    /** Objects per listing request. B2 allows 10000 but bills each 1000 as one class C call. */
    private val pageSize: Int = 1000,
) : StorageProvider, BudgetedReads, CachedListings, DeepListing {
    override val id = "b2"
    override val features = setOf(Feature.STABLE_KEYS, Feature.RANGE_READ, Feature.NETWORK, Feature.VERSIONED, Feature.PREFIX_FOLDERS)

    private val sessions = B2Sessions(connector)
    private val sweepPage = (pageSize * 10).coerceAtMost(LARGEST_PAGE)
    /** Ref key to the id of the empty version [create] uploaded, which the first write then removes. */
    private val placeholders = ConcurrentHashMap<String, String>()

    fun disconnect(accountId: String) = sessions.drop(accountId)

    suspend fun probe(account: B2Account): B2Probe = withContext(Dispatchers.IO) {
        mapped {
            connector.connect(account).use { session ->
                if (account.wholeAccount) B2Probe(session.buckets().size)
                else { session.bucket(account.bucket); B2Probe(buckets = null) }
            }
        }
    }

    override suspend fun forget(folder: NodeRef?, below: Boolean) = withContext(Dispatchers.IO) {
        when {
            folder == null -> cache.clear()
            folder.provider != id -> Unit
            below -> { cache.forget(folder.key); cache.forgetStartingWith(beneath(place(folder))) }
            else -> cache.forget(folder.key)
        }
    }

    /** What the key of everything under [place] starts with. A bucket's own key already ends in its separator. */
    private fun beneath(place: Place): String = when {
        place.atAccount -> "${place.account.id}:"
        place.path.isEmpty() -> refOf(place).key
        else -> refOf(place).key + "/"
    }

    override suspend fun cachedBytes(): Long = withContext(Dispatchers.IO) { cache.bytes() }

    suspend fun forgetAccount(accountId: String) = withContext(Dispatchers.IO) { cache.forgetStartingWith("$accountId:") }

    override suspend fun roots(): List<StorageRoot> = accounts().map { account ->
        StorageRoot(rootOf(account), account.name, description = account.address, kind = RootKind.NETWORK)
    }

    override suspend fun forgetRoot(root: StorageRoot) {
        fail(StorageError.UNSUPPORTED, "Remove the account from Settings, under Network")
    }

    // Require authorization before exposing cached listings so a locked account cannot open halfway.
    private fun unlocked(account: B2Account) {
        if (!sessions.holds(account.id) && withKey(account) == null)
            fail(StorageError.AUTH, "Unlock the credential vault to connect to ${account.name}")
    }

    override suspend fun stat(ref: NodeRef): Entry {
        val place = place(ref)
        if (place.path.isEmpty()) return containerEntry(place)
        // A folder whose listing is saved opens without a request, as its listing does.
        if (withContext(Dispatchers.IO) { cache.has(ref.key) }) return folderEntry(place, sessions.known(place.account.id))
        return found(place) ?: fail(StorageError.NOT_FOUND, "Not found on ${place.account.name}")
    }

    /** A file of exactly this name, else a folder if anything lies under it. */
    private suspend fun found(place: Place): Entry? = reading(place, "stat") { bucket, auth ->
        // B2 refuses a question about any name outside the prefix a key is confined to.
        if (place.path.startsWith(auth.namePrefix)) bucket.stat(place.path)?.let { return@reading fileEntry(place, it.listed(), auth) }
        when (val scope = scope(place.path + "/", auth.namePrefix)) {
            is Scope.Only -> folderEntry(place, auth)
            is Scope.Listing -> bucket.list(scope.prefix, "/", null, 1)
                .takeIf { it.items.isNotEmpty() || it.folders.isNotEmpty() }?.let { folderEntry(place, auth) }
            Scope.Nothing -> null
        }
    }

    override fun list(parent: NodeRef, complete: Boolean): Flow<List<Entry>> = flow {
        val place = place(parent)
        unlocked(place.account)
        if (!complete) withContext(Dispatchers.IO) { cache.read(parent.key) }?.let { saved ->
            val auth = sessions.known(place.account.id)
            saved.map { entryOf(place.child(it.name), it, auth) }.chunked(BATCH).forEach { emit(it) }
            return@flow
        }
        val listed = ArrayList<B2Listed>()
        val begun = cache.now()
        if (place.atAccount) {
            val buckets = session(place, "list buckets") { it.buckets() }.map { B2Listed(it.name) }
            listed += buckets
            emit(buckets.map { containerEntry(place.child(it.name)) })
        } else {
            val auth = reading(place, "authorize") { _, auth -> auth }
            when (val scope = scope(place.folderPrefix, auth.namePrefix)) {
                Scope.Nothing -> Unit
                is Scope.Only -> B2Listed(scope.name).let { listed += it; emit(listOf(entryOf(place.child(it.name), it, auth))) }
                is Scope.Listing -> {
                    var after: String? = null
                    do {
                        val page = reading(place, "list") { bucket, _ ->
                            bucket.list(scope.prefix, "/", after, pageSize)
                        }
                        // An object named exactly as the prefix (the folder marker other tools write) has no name of its own here.
                        val files = page.items.filter { it.key != place.folderPrefix + MARKER && it.key != place.folderPrefix }
                            .map { it.listed(it.key.removePrefix(place.folderPrefix)) }
                        // B2 allows an object and a prefix of one name, but both would be the same ref here; the file wins.
                        val taken = files.mapTo(HashSet()) { it.name }
                        val folders = page.folders.map { B2Listed(it.removePrefix(place.folderPrefix).trimEnd('/')) }
                            .filter { it.name.isNotEmpty() && it.name !in taken }
                        val children = folders + files
                        listed += children
                        if (children.isNotEmpty()) emit(children.map { entryOf(place.child(it.name), it, auth) })
                        after = page.next
                    } while (after != null)
                }
            }
        }
        // Reached only by a listing that ran to its end. One that a change overtook is not saved.
        withContext(Dispatchers.IO) { cache.write(parent.key, listed, begun) }
    }

    // Sweep uncached subtrees together because B2 charges per request, including single-folder listings.
    override fun listDeep(root: NodeRef, hidden: Boolean): Flow<List<Entry>> = flow {
        val pending = ArrayDeque<NodeRef>().apply { add(root) }
        while (pending.isNotEmpty()) {
            val folder = pending.removeFirst()
            val place = place(folder)
            // An account's own listing is its buckets, which is one request however it is asked.
            if (place.atAccount || withContext(Dispatchers.IO) { cache.has(folder.key) }) {
                list(folder).collect { batch ->
                    val shown = batch.filter { hidden || !it.hidden }
                    if (shown.isNotEmpty()) emit(shown)
                    shown.forEach { if (it.directory) pending.add(it.ref) }
                }
            } else {
                sweep(place, hidden) { pending.add(it) }
            }
        }
    }

    // Ordered names complete each folder when the next name falls outside it; only complete folders are cached.
    private suspend fun FlowCollector<List<Entry>>.sweep(place: Place, hidden: Boolean, walkOn: (NodeRef) -> Unit) {
        val auth = reading(place, "authorize") { _, auth -> auth }
        val prefix = when (val scope = scope(place.folderPrefix, auth.namePrefix)) {
            Scope.Nothing -> return
            // Above what the key is confined to there is only the way down to it, which is walked.
            is Scope.Only -> {
                val child = folderEntry(place.child(scope.name), auth)
                if (hidden || !child.hidden) { emit(listOf(child)); walkOn(child.ref) }
                return
            }
            is Scope.Listing -> scope.prefix
        }

        class Open(val at: Place, val shown: Boolean, val shadowed: Boolean) {
            val children = ArrayList<B2Listed>()
            val names = HashSet<String>()
        }
        val begun = cache.now()
        val open = ArrayList<Open>().apply { add(Open(place, shown = true, shadowed = false)) }
        val closed = HashSet<String>()
        // False once a name arrives out of order: nothing can be called complete after that.
        var ordered = true
        var batch = ArrayList<Entry>(BATCH)

        suspend fun close(folder: Open) {
            if (folder.shadowed) return
            val key = refOf(folder.at).key
            closed += key
            if (ordered) withContext(Dispatchers.IO) { cache.write(key, folder.children, begun) }
        }

        var after: String? = null
        do {
            val page = reading(place, "list") { bucket, _ -> bucket.list(prefix, null, after, sweepPage) }
            for (item in page.items) {
                val parts = item.key.removePrefix(place.folderPrefix).split('/')
                val folders = parts.dropLast(1)
                var depth = 0
                while (depth < folders.size && depth + 1 < open.size && open[depth + 1].at.name == folders[depth]) depth++
                while (open.size > depth + 1) close(open.removeAt(open.lastIndex))
                for (name in folders.drop(depth)) {
                    val parent = open.last()
                    val at = parent.at.child(name)
                    if (refOf(at).key in closed) {
                        ordered = false
                        withContext(Dispatchers.IO) { cache.forget(refOf(at).key) }
                    }
                    // B2 allows an object and a prefix of one name, but both would be the same ref here; the file wins.
                    val shadowed = parent.shadowed || name in parent.names
                    val shown = parent.shown && (hidden || !name.startsWith("."))
                    if (!shadowed) {
                        parent.children += B2Listed(name); parent.names += name
                        if (shown) batch += folderEntry(at, auth)
                    }
                    open += Open(at, shown, shadowed)
                }
                val name = parts.last()
                val parent = open.last()
                // A marker, or the object other tools write under the folder's own name, says only that the folder is there.
                if (name.isEmpty() || name == MARKER || parent.shadowed || !parent.names.add(name)) continue
                val listed = item.listed(name)
                parent.children += listed
                if (parent.shown && (hidden || !name.startsWith("."))) batch += fileEntry(parent.at.child(name), listed, auth)
                if (batch.size >= BATCH) { emit(batch); batch = ArrayList(BATCH) }
            }
            if (batch.isNotEmpty()) { emit(batch); batch = ArrayList(BATCH) }
            after = page.next
        } while (after != null)
        while (open.isNotEmpty()) close(open.removeAt(open.lastIndex))
    }

    override suspend fun child(parent: NodeRef, name: String): Entry? {
        val place = place(parent)
        if (place.atAccount) {
            return session(place, "look up bucket") { it.buckets() }.firstOrNull { it.name == name }
                ?.let { containerEntry(place.child(it.name)) }
        }
        return found(place.child(name))
    }

    override suspend fun parentOf(ref: NodeRef): NodeRef? {
        val place = place(ref)
        if (place.atAccount) return null
        if (place.path.isEmpty()) return if (place.account.wholeAccount) rootOf(place.account) else null
        return refOf(place.account.id, place.bucket, place.parentPath)
    }

    override suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String): Entry {
        validateName(name)
        val place = place(parent)
        if (place.atAccount) fail(StorageError.UNSUPPORTED, "Buckets are made on the Backblaze site, not from here")
        val at = place.child(name)
        // B2 has no create-if-absent: an upload onto a taken name succeeds as a new version. This check can be raced.
        if (found(at) != null) fail(StorageError.CONFLICT, "$name already exists")
        return try {
            writing(place, "create") { bucket, auth ->
                val empty = File.createTempFile("empty", ".part", staging.apply { mkdirs() })
                try {
                    if (directory) { bucket.upload(at.path + "/" + MARKER, empty, null, "application/octet-stream"); folderEntry(at, auth) }
                    else {
                        val placeholder = bucket.upload(at.path, empty, null, typeFor(name, mimeType))
                        placeholders[refOf(at).key] = placeholder.fileId
                        fileEntry(at, placeholder.listed(), auth)
                    }
                } finally { empty.delete() }
            }
        } finally {
            // Also after a failure: the upload may have landed all the same.
            changed(at)
        }
    }

    override suspend fun rename(ref: NodeRef, name: String): Entry {
        validateName(name)
        val place = place(ref)
        if (place.path.isEmpty()) fail(StorageError.UNSUPPORTED, if (place.atAccount) "An account is renamed in Settings" else "A bucket cannot be renamed")
        return moved(place, place.sibling(name), "rename")
    }

    override suspend fun relocate(ref: NodeRef, parent: NodeRef, name: String): Entry? {
        val from = place(ref)
        val into = place(parent)
        // A server-side copy stays inside one bucket; null makes the caller copy through the device instead.
        if (from.account.id != into.account.id || from.bucket != into.bucket || from.path.isEmpty() || into.atAccount) return null
        val to = into.child(name)
        if (to.path == from.path || to.path.startsWith(from.path + "/")) fail(StorageError.UNSUPPORTED, "A folder cannot be moved into itself")
        return moved(from, to, "move")
    }

    /** B2 cannot rename: every object is copied on the server and the original then removed. */
    private suspend fun moved(from: Place, to: Place, what: String): Entry {
        if (found(to) != null) fail(StorageError.CONFLICT, "${to.name} already exists")
        val keepVersions = currentCoroutineContext()[KeepVersions] != null
        try {
            return writing(from, what) { bucket, auth ->
                val file = bucket.stat(from.path)
                if (file != null) {
                    val copied = bucket.copy(file, to.path)
                    try { remove(bucket, auth, from.path, keepVersions) } catch (failure: B2Failure) {
                        throw B2Failure(failure.reason, "${to.name} is complete, but ${from.name} could not be removed: ${failure.message}", failure)
                    }
                    return@writing fileEntry(to, copied.listed(), auth)
                }
                val sources = everythingUnder(bucket, from.folderPrefix)
                if (sources.isEmpty()) fail(StorageError.NOT_FOUND, "Not found on ${from.account.name}")
                sources.forEachIndexed { done, source ->
                    try {
                        bucket.copy(source, to.folderPrefix + source.key.removePrefix(from.folderPrefix))
                    } catch (failure: B2Failure) {
                        // The copies already made are left in place and named, not deleted.
                        throw B2Failure(failure.reason, "Copied $done of ${sources.size} items to ${to.name} before failing: " +
                            "${failure.message}. Nothing was removed from ${from.name}.", failure)
                    }
                }
                sources.forEachIndexed { done, source ->
                    try { remove(bucket, auth, source.key, keepVersions) } catch (failure: B2Failure) {
                        throw B2Failure(failure.reason, "${to.name} is complete, but only $done of ${sources.size} items " +
                            "were removed from ${from.name}: ${failure.message}", failure)
                    }
                }
                folderEntry(to, auth)
            }
        } finally {
            // Also after a failure, which can leave either side changed.
            vanished(from); changed(to)
            withContext(Dispatchers.IO) { cache.forgetStartingWith(beneath(from)); cache.forgetStartingWith(beneath(to)) }
        }
    }

    private fun everythingUnder(bucket: B2Bucket, prefix: String): List<B2Item> = buildList {
        var after: String? = null
        do {
            val page = bucket.list(prefix, null, after, 1000)
            addAll(page.items)
            after = page.next
        } while (after != null)
    }

    private fun purge(bucket: B2Bucket, key: String) = bucket.versions(key).forEach(bucket::delete)

    /** A key that may write but not delete can only hide. */
    private fun remove(bucket: B2Bucket, auth: B2Auth, key: String, keepVersions: Boolean) =
        if (keepVersions || !auth.can(DELETE_FILES)) bucket.hide(key) else purge(bucket, key)

    override suspend fun delete(ref: NodeRef) {
        val place = place(ref)
        if (place.path.isEmpty()) fail(StorageError.UNSUPPORTED, if (place.atAccount) "An account is removed in Settings" else "A bucket cannot be deleted from here")
        val keepVersions = currentCoroutineContext()[KeepVersions] != null
        try {
            writing(place, "delete") { bucket, auth ->
                if (bucket.stat(place.path) != null) {
                    remove(bucket, auth, place.path, keepVersions)
                    return@writing
                }
                val marker = place.folderPrefix + MARKER
                if (bucket.list(place.folderPrefix, null, null, 2).items.any { it.key != marker }) {
                    fail(StorageError.CONFLICT, "The folder is not empty")
                }
                // Without a marker the folder went when its last object did, which is what was asked for.
                if (auth.can(DELETE_FILES)) purge(bucket, marker) else if (bucket.stat(marker) != null) bucket.hide(marker)
            }
        } finally {
            vanished(place)
            withContext(Dispatchers.IO) { cache.forgetStartingWith(beneath(place)) }
        }
    }

    override suspend fun openRead(ref: NodeRef): InputStream = opened(ref, null) { bucket, item, _ -> Download(bucket.open(item), null) }

    override suspend fun openRead(ref: NodeRef, budget: ReadBudget): InputStream =
        opened(ref, budget) { bucket, item, _ -> Download(bucket.open(item), budget) }

    override suspend fun openChannel(ref: NodeRef): SeekableByteChannel = channel(ref, null)

    override suspend fun openChannel(ref: NodeRef, budget: ReadBudget): SeekableByteChannel = channel(ref, budget)

    private suspend fun channel(ref: NodeRef, budget: ReadBudget?): SeekableByteChannel = opened(ref, budget) { bucket, item, place ->
        var session = sessions.session(place.account, withKey)
        val label = where(place)
        // The reader is pinned to the version's id, so a concurrent upload cannot swap bytes under it.
        RemoteChannel(RemoteReading(bucket.openReader(item), label, budget, TAG, StorageError.AUTH) {
            sessions.forget(place.account, session)
            val account = accounts().firstOrNull { it.id == place.account.id }
                ?: throw B2Failure(StorageError.DISCONNECTED, "${place.account.name} was removed since the file was opened")
            session = sessions.session(account, withKey)
            sessions.bucket(account, place.bucket, withKey).openReader(item)
        }, item.size, CHANNEL_BLOCK)
    }

    private suspend fun <T : Closeable> opened(ref: NodeRef, budget: ReadBudget?, open: (B2Bucket, B2Item, Place) -> T): T = holding {
        budget?.checkCancelled()
        val place = place(ref)
        if (place.path.isEmpty()) fail(StorageError.UNSUPPORTED, "${place.name} is a folder")
        reading(place, "open") { bucket, _ ->
            val item = bucket.stat(place.path) ?: fail(StorageError.NOT_FOUND, "Not found on ${place.account.name}")
            open(bucket, item, place)
        }
    }

    override suspend fun openWrite(ref: NodeRef): OutputStream {
        val place = place(ref)
        if (place.path.isEmpty()) fail(StorageError.UNSUPPORTED, "${place.name} is a folder")
        val auth = reading(place, "authorize") { _, auth -> auth }
        val options = place.account.options
        val partSize = (options.partMegabytes.toLong() * 1_000_000).coerceIn(auth.minimumPartSize, MAX_SINGLE_UPLOAD)
        return B2UploadStream(staging, partSize, place.path, typeFor(place.name, "application/octet-stream"),
            bucket = { unwrapped { sessions.bucket(place.account, place.bucket, withKey) } },
            onPublished = {
                // Only the placeholder goes. Content this write covers stays beneath it as an older version.
                placeholders.remove(ref.key)?.let { empty ->
                    runCatching { sessions.bucket(place.account, place.bucket, withKey).delete(B2Version(place.path, empty)) }
                }
                cache.forget(refOf(place.account.id, place.bucket, place.parentPath).key)
            })
    }

    override suspend fun isDescendant(candidate: NodeRef, ancestor: NodeRef): Boolean {
        val below = runCatching { place(candidate) }.getOrNull() ?: return false
        val above = runCatching { place(ancestor) }.getOrNull() ?: return false
        if (below.account.id != above.account.id) return false
        if (above.atAccount) return true
        if (below.bucket != above.bucket) return false
        return above.path.isEmpty() || below.path == above.path || below.path.startsWith(above.path + "/")
    }

    override suspend fun commit(staged: NodeRef, parent: NodeRef, name: String, replace: Entry?, onRetained: RetainedObjects?): Entry {
        val from = place(staged)
        val into = place(parent)
        if (from.account.id != into.account.id || from.bucket != into.bucket || from.parentPath != into.path || from.path.isEmpty()) {
            fail(StorageError.UNSUPPORTED, "Staged files must belong to the destination folder")
        }
        val to = into.child(name)
        val current = found(to)
        if (replace == null) {
            if (current != null) fail(StorageError.CONFLICT, "$name already exists")
        } else {
            // The target may have changed during the copy; recheck it before covering it.
            if (current == null) fail(StorageError.CONFLICT, "$name is no longer there; refresh and try again")
            if (current.directory) fail(StorageError.UNSUPPORTED, "Folders are merged rather than replaced")
            if (current.version != replace.version) fail(StorageError.CONFLICT, "$name changed since it was seen; refresh and try again")
        }
        val retained = ArrayList<String>()
        val published = try {
            writing(into, "publish") { bucket, auth ->
                val source = bucket.stat(from.path)
                    ?: fail(StorageError.UNSUPPORTED, "Only a file can be published under another name; a folder is written where it belongs")
                // The copy becomes the name's current version in one step; whatever it covers stays stored beneath it.
                val copied = bucket.copy(source, to.path)
                try { remove(bucket, auth, from.path, keepVersions = false) } catch (failure: B2Failure) {
                    retained += "The staged copy ${from.name} could not be removed: ${failure.message}"
                }
                fileEntry(to, copied.listed(), auth)
            }
        } finally {
            changed(to)
        }
        retained.forEach { onRetained?.invoke(published.ref, it) }
        return published
    }

    private class Place(val account: B2Account, val bucket: String, val path: String) {
        val atAccount: Boolean get() = bucket.isEmpty()
        val parentPath: String get() = path.substringBeforeLast('/', "")
        val folderPrefix: String get() = if (path.isEmpty()) "" else "$path/"
        val name: String get() = when {
            path.isNotEmpty() -> path.substringAfterLast('/')
            atAccount || !account.wholeAccount -> account.name
            else -> bucket
        }
        fun child(name: String) = if (atAccount) Place(account, name, "") else Place(account, bucket, if (path.isEmpty()) name else "$path/$name")
        fun sibling(name: String) = Place(account, bucket, if (parentPath.isEmpty()) name else "$parentPath/$name")
    }

    /** What a folder's listing may ask B2 for when the key is confined to names starting with a prefix. */
    private sealed interface Scope {
        data class Listing(val prefix: String) : Scope
        /** The folder lies above the confined prefix: its one child is the next step towards it. */
        data class Only(val name: String) : Scope
        data object Nothing : Scope
    }

    private fun scope(folderPrefix: String, confined: String): Scope = when {
        folderPrefix.startsWith(confined) -> Scope.Listing(folderPrefix)
        !confined.startsWith(folderPrefix) -> Scope.Nothing
        else -> confined.removePrefix(folderPrefix).let { rest ->
            if ('/' in rest) Scope.Only(rest.substringBefore('/')) else Scope.Listing(confined)
        }
    }

    private fun refOf(accountId: String, bucket: String, path: String) = NodeRef(id, "$accountId:$bucket:$path")

    private fun refOf(place: Place) = refOf(place.account.id, place.bucket, place.path)

    private fun rootOf(account: B2Account) = refOf(account.id, account.bucket, "")

    // Keys use account:bucket:path; only the path can contain a colon.
    private fun place(ref: NodeRef): Place {
        if (ref.provider != id) fail(StorageError.NOT_FOUND, "Not a B2 location")
        val accountId = ref.key.substringBefore(':')
        val rest = ref.key.substringAfter(':', "")
        if (':' !in rest) fail(StorageError.NOT_FOUND, "Not a B2 location")
        val bucket = rest.substringBefore(':')
        val path = rest.substringAfter(':')
        // A path ending in a slash would name its own folder a second time.
        if (path.startsWith('/') || path.endsWith('/') || (bucket.isEmpty() && path.isNotEmpty())) fail(StorageError.NOT_FOUND, "Not a B2 location")
        val account = accounts().firstOrNull { it.id == accountId }
            ?: fail(StorageError.NOT_FOUND, "That account is no longer set up")
        if (!account.wholeAccount && bucket != account.bucket) fail(StorageError.NOT_FOUND, "That is not on ${account.name}")
        return Place(account, bucket, path)
    }

    /** An account or a bucket: known from settings, so never looked up. */
    private fun containerEntry(place: Place) = Entry(
        ref = refOf(place), name = place.name, directory = true,
        capabilities = if (place.atAccount) setOf(Capability.LIST) else folderCapabilities(sessions.known(place.account.id), root = true),
    )

    private fun entryOf(place: Place, listed: B2Listed, auth: B2Auth?): Entry =
        if (place.path.isEmpty()) containerEntry(place)
        else if (listed.directory) folderEntry(place, auth) else fileEntry(place, listed, auth)

    private fun folderEntry(place: Place, auth: B2Auth?) = Entry(
        ref = refOf(place), name = place.name, directory = true,
        capabilities = folderCapabilities(auth, root = false) + hiddenIf(place.name),
        hidden = place.name.startsWith("."),
    )

    private fun fileEntry(place: Place, listed: B2Listed, auth: B2Auth?) = Entry(
        ref = refOf(place), name = place.name, directory = false,
        size = listed.size, modified = listed.modified.takeIf { it > 0 },
        mimeType = listed.contentType.takeUnless { it == "application/octet-stream" } ?: typeFor(place.name, listed.contentType),
        capabilities = buildSet {
            if (auth.allows(READ_FILES)) add(Capability.READ)
            if (auth.allows(WRITE_FILES)) add(Capability.WRITE)
            if (auth.allows(WRITE_FILES) && auth.allows(DELETE_FILES)) add(Capability.RENAME)
            if (auth.allows(WRITE_FILES) || auth.allows(DELETE_FILES)) add(Capability.DELETE)
        } + hiddenIf(place.name),
        hidden = place.name.startsWith("."),
        version = listed.fileId,
    )

    private fun folderCapabilities(auth: B2Auth?, root: Boolean): Set<Capability> = buildSet {
        add(Capability.LIST)
        if (auth.allows(WRITE_FILES)) { add(Capability.CREATE); add(Capability.ATOMIC_REPLACE) }
        if (!root && auth.allows(WRITE_FILES) && auth.allows(DELETE_FILES)) { add(Capability.RENAME); add(Capability.DELETE) }
    }

    /** With no authorization yet, a saved listing offers everything; [stat] decides before anything is changed. */
    private fun B2Auth?.allows(capability: String) = this == null || can(capability)

    private fun hiddenIf(name: String) = if (name.startsWith(".")) setOf(Capability.HIDDEN) else emptySet()

    private fun B2Item.listed(name: String = key.substringAfterLast('/')) = B2Listed(name, fileId, size, modified, contentType)

    /** "b2/x-auto" lets B2 choose from the extension when Luna cannot. */
    private fun typeFor(name: String, given: String): String =
        given.takeUnless { it == "application/octet-stream" } ?: URLConnection.guessContentTypeFromName(name) ?: given.takeUnless { it.isEmpty() } ?: "b2/x-auto"

    private suspend fun changed(place: Place) = withContext(Dispatchers.IO) {
        cache.forget(refOf(place.account.id, place.bucket, place.parentPath).key)
    }

    /** A folder with no marker goes with its last object, and then so may the one above it: every listing on the way up is forgotten. */
    private suspend fun vanished(place: Place) = withContext(Dispatchers.IO) {
        cache.forget(refOf(place).key)
        var path = place.path
        while (path.isNotEmpty()) {
            path = path.substringBeforeLast('/', "")
            cache.forget(refOf(place.account.id, place.bucket, path).key)
        }
    }

    private suspend fun <T> session(place: Place, what: String, block: (B2Session) -> T): T = withContext(Dispatchers.IO) {
        timed(what, where(place)) { mapped { block(sessions.session(place.account, withKey)) } }
    }

    private suspend fun <T> reading(place: Place, what: String, block: (B2Bucket, B2Auth) -> T): T = io(place, what, block)

    /** A change is never cut short by cancellation once sent, so its outcome is always known. */
    private suspend fun <T> writing(place: Place, what: String, block: (B2Bucket, B2Auth) -> T): T {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) { io(place, what, block) }
    }

    private suspend fun <T> io(place: Place, what: String, block: (B2Bucket, B2Auth) -> T): T = withContext(Dispatchers.IO) {
        if (place.atAccount) fail(StorageError.UNSUPPORTED, "The account itself holds only buckets; open one")
        timed(what, where(place)) {
            mapped {
                val session = sessions.session(place.account, withKey)
                try {
                    block(sessions.bucket(place.account, place.bucket, withKey), session.summary)
                } catch (spent: B2Failure) {
                    if (spent.reason == StorageError.AUTH) sessions.forget(place.account, session)
                    throw spent
                }
            }
        }
    }

    private inline fun <T> timed(what: String, where: String, block: () -> T): T {
        val started = System.nanoTime()
        DebugLog.d(TAG) { "$what $where…" }
        return try {
            block().also { DebugLog.d(TAG) { "$what $where · ${DebugLog.millisSince(started)} ms" } }
        } catch (failure: StorageException) {
            val serious = failure.reason in SERIOUS
            DebugLog.emit(if (serious) DebugLog.Level.WARN else DebugLog.Level.DEBUG, TAG, if (serious) failure.cause else null) {
                "$what $where failed after ${DebugLog.millisSince(started)} ms: ${failure.reason} · ${failure.message}"
            }
            throw failure
        }
    }

    private fun where(place: Place): String =
        listOf(place.account.name, place.bucket, place.path).filter { it.isNotEmpty() }.joinToString("/")

    // Cancellation during open must not discard the only reference to the connection.
    private suspend fun <T : Closeable> holding(open: suspend () -> T): T {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val opened = withContext(NonCancellable) { open() }
        if (!caller.isActive) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { opened.close() } }
            caller.ensureActive()
        }
        return opened
    }

    private inline fun <T> mapped(block: () -> T): T = try {
        block()
    } catch (error: B2Failure) {
        throw StorageException(error.reason, error.message ?: "B2 reported an error", error)
    }

    private inline fun <T> unwrapped(block: () -> T): T = try {
        block()
    } catch (error: StorageException) {
        throw B2Failure(error.reason, error.message ?: "B2 reported an error", error)
    }

    private fun fail(reason: StorageError, message: String): Nothing = throw StorageException(reason, message)

    private companion object {
        const val TAG = "B2"
        const val MARKER = ".bzEmpty"
        const val BATCH = 256
        /** The most B2 gives in one request. It bills each thousand as a call, but a sweep is quicker in fewer round trips. */
        const val LARGEST_PAGE = 10_000
        /** Each block is one request and one billed download, so larger than a LAN share's. */
        const val CHANNEL_BLOCK = 256 * 1024
        const val MAX_SINGLE_UPLOAD = 5_000_000_000L
        const val READ_FILES = "readFiles"
        const val WRITE_FILES = "writeFiles"
        const val DELETE_FILES = "deleteFiles"
        val SERIOUS = setOf(StorageError.DISCONNECTED, StorageError.TIMEOUT, StorageError.OFFLINE, StorageError.IO, StorageError.AUTH)
    }
}

/** [buckets] is the bucket count, or null for an account scoped to one bucket. */
data class B2Probe(val buckets: Int?)

/** One download, read in order. Charges a preview's [ReadBudget], and fails the way a stream is expected to. */
private class Download(private val inner: InputStream, private val budget: ReadBudget?) : InputStream() {
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = try {
        when {
            len == 0 -> 0
            budget == null -> inner.read(b, off, len)
            else -> budget.read(len) { allowed -> inner.read(b, off, allowed) }
        }
    } catch (failed: B2Failure) {
        throw IOException(failed.message, failed)
    }

    override fun close() = inner.close()
}
