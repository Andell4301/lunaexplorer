package com.lunaexplorer.app.storage

import android.system.Os
import com.lunaexplorer.core.*
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class LocalStorageProvider(
    initialRoots: List<LocalRoot> = emptyList(),
    private val names: SystemNames? = null,
    private val probe: PathProbe = SystemProbe,
    private val openDirectory: (Path) -> DirectoryStream<Path> = Files::newDirectoryStream,
) : StorageProvider, PathAddressable {
    override val id = "local"
    override val features = setOf(Feature.STABLE_KEYS, Feature.SET_TIMES, Feature.RANGE_READ)

    @Volatile private var rootDefinitions: List<LocalRoot> = initialRoots
    // Canonical root paths, cached to avoid a syscall for each listed entry.
    private val baseCache = ConcurrentHashMap<String, Path>()

    fun updateRoots(roots: List<LocalRoot>) { rootDefinitions = roots; baseCache.clear() }

    fun bases(): List<Pair<LocalRoot, String>> = rootDefinitions.mapNotNull { root -> runCatching { root to basePath(root).toString() }.getOrNull() }

    fun definitions(): List<LocalRoot> = rootDefinitions

    override suspend fun roots(): List<StorageRoot> = withContext(Dispatchers.IO) {
        rootDefinitions.mapNotNull { root ->
            try {
                if (root.kind == RootKind.APP && !root.path.exists()) root.path.mkdirs()
                val base = basePath(root)
                if (!Files.isDirectory(base)) return@mapNotNull null
                val total = runCatching { root.path.totalSpace }.getOrNull()?.takeIf { it > 0 }
                StorageRoot(
                    ref = reference(root, base),
                    title = root.title,
                    kind = root.kind,
                    totalBytes = total,
                    freeBytes = if (total == null) null else runCatching { root.path.usableSpace }.getOrNull(),
                    readOnly = !Files.isWritable(base),
                    hidden = root.hidden,
                )
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { null }
        }
    }

    suspend fun root(id: String): NodeRef = roots().firstOrNull { decode(it.ref).root == id }?.ref
        ?: fail(StorageError.NOT_FOUND, "That storage location is unavailable")

    fun absolutePath(ref: NodeRef): String? = runCatching { resolve(ref).toString() }.getOrNull()

    /** Where [ref] points, worked out from its name alone: the item may be missing, or closed to this process. */
    fun locationOf(ref: NodeRef): String? = runCatching {
        val location = decode(ref)
        val base = basePath(rootDefinition(location.root))
        base.resolve(location.relative).normalize().takeIf { it.startsWith(base) }?.toString()
    }.getOrNull()

    /** Does not stat the path, so it is cheap enough for bulk queries; existence is not checked. */
    fun referenceTo(absolutePath: String): NodeRef? {
        val path = runCatching { File(absolutePath).toPath().toAbsolutePath().normalize() }
            .getOrNull() ?: return null
        val root = rootDefinitions
            .filter { root -> runCatching { path.startsWith(basePath(root)) }.getOrDefault(false) }
            // Roots can overlap; the deepest one gives the shortest reference.
            .maxByOrNull { runCatching { basePath(it).nameCount }.getOrDefault(0) }
            ?: return null
        val relative = runCatching { basePath(root).relativize(path).toString() }.getOrNull() ?: return null
        return NodeRef(id, key(root.id, relative))
    }

    override fun pathOf(ref: NodeRef): String? = absolutePath(ref)
    override fun shownPathOf(ref: NodeRef): String? = locationOf(ref)
    override fun refFor(path: String): NodeRef? = referenceTo(path)

    override suspend fun stat(ref: NodeRef): Entry = io { entry(rootDefinition(decode(ref).root), resolve(ref)) }

    override suspend fun parentOf(ref: NodeRef): NodeRef? {
        val location = decode(ref)
        return if (location.relative.isEmpty()) null
        else NodeRef(id, key(location.root, location.relative.substringBeforeLast('/', "")))
    }

    override fun list(parent: NodeRef, complete: Boolean): Flow<List<Entry>> = flow {
        mapped {
            val location = decode(parent)
            val root = rootDefinition(location.root)
            val path = resolve(parent)
            if (!Files.isDirectory(path)) fail(StorageError.IO, "This item is not a folder")
            val writable = Files.isWritable(path)
            val parentRelative = basePath(root).relativize(path.toAbsolutePath().normalize()).toString()
            var batch = ArrayList<Entry>(128)
            var produced = 0
            suspend fun add(child: Path) {
                currentCoroutineContext().ensureActive()
                batch.add(entryOrPlaceholder(root, child, writable, parentRelative))
                produced++
                if (batch.size == 128) { emit(batch); batch = ArrayList(128) }
            }

            var failure: Exception? = null
            try {
                openDirectory(path).use { stream -> for (child in stream) add(child) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Rows read before a DirectoryIteratorException stand unless the caller needs a complete listing.
                if (produced == 0 || complete) failure = error
            }

            // File.list() can succeed where the directory stream failed; it needs the same permissions.
            if (failure != null && produced == 0) {
                path.toFile().list()?.let { names ->
                    for (name in names.sorted()) add(path.resolve(name))
                    failure = null
                }
            }

            // With search (x) but not read permission readdir is denied, yet known child names still resolve.
            if (failure != null && produced == 0) {
                for (name in probeCandidates(path)) {
                    if (probe.exists(path.resolve(name).toString())) add(path.resolve(name))
                }
                // A probed listing is partial, so it cannot satisfy a complete one.
                if (produced > 0 && !complete) failure = null
            }

            failure?.let { throw it }
            if (batch.isNotEmpty()) emit(batch)
        }
    }.flowOn(Dispatchers.IO)

    private fun probeCandidates(path: Path): List<String> {
        val here = path.toString().trimEnd('/').ifEmpty { "/" }
        val fixed = SystemNames.FIXED_CANDIDATES[here].orEmpty()
        val derived = names?.namesUnder(here).orEmpty()
        val mounted = probe.mountPointsUnder(here)
        return (fixed + derived + mounted).distinct().sorted()
    }

    override suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String): Entry = io {
        validateName(name)
        val root = rootDefinition(decode(parent).root)
        val path = resolve(parent).resolve(name)
        if (directory) Files.createDirectory(path) else Files.createFile(path)
        entry(root, path)
    }

    override suspend fun rename(ref: NodeRef, name: String): Entry = io {
        validateName(name)
        val location = decode(ref)
        val root = rootDefinition(location.root)
        val path = resolve(ref)
        if (location.relative.isEmpty()) fail(StorageError.UNSUPPORTED, "A storage root cannot be renamed")
        if (path.fileName.toString() == name) return@io entry(root, path)
        val target = path.resolveSibling(name)
        // No REPLACE_EXISTING: a rename must not overwrite.
        Files.move(path, target)
        entry(root, target)
    }

    override suspend fun setModified(ref: NodeRef, epochMillis: Long): Long = io {
        val path = resolve(ref)
        // A symlink's own times cannot be set: there is no public lutimes, and opening with
        // O_NOFOLLOW to set them fails with ELOOP.
        if (Files.isSymbolicLink(path)) fail(StorageError.UNSUPPORTED, "A link's own modification time cannot be changed")
        // Throws with the errno; File.setLastModified only returns false.
        Files.setLastModifiedTime(path, FileTime.fromMillis(epochMillis))
        // Read back the rounded value: vfat stores two-second steps, exFAT ten milliseconds.
        Files.getLastModifiedTime(path).toMillis()
    }

    override suspend fun delete(ref: NodeRef) = io {
        if (decode(ref).relative.isEmpty()) fail(StorageError.UNSUPPORTED, "A storage root cannot be deleted")
        val path = resolve(ref)
        try {
            Files.delete(path) // DirectoryNotEmptyException is intentional: no implicit recursion.
        } catch (notEmpty: DirectoryNotEmptyException) {
            val remaining = runCatching { path.toFile().list()?.toList() }.getOrNull().orEmpty()
            val named = remaining.take(3).joinToString(", ")
            fail(StorageError.CONFLICT, when {
                remaining.isEmpty() -> "This folder reports that it still has contents"
                remaining.size <= 3 -> "This folder still contains $named"
                else -> "This folder still contains ${remaining.size} items, including $named"
            }, notEmpty)
        }
    }

    override suspend fun openChannel(ref: NodeRef): SeekableByteChannel = io {
        Files.newByteChannel(resolve(ref), StandardOpenOption.READ)
    }

    override suspend fun openRead(ref: NodeRef): InputStream = io {
        val root = rootDefinition(decode(ref).root)
        if (root.followLinks) Files.newInputStream(resolve(ref), StandardOpenOption.READ)
        else Files.newInputStream(resolve(ref), StandardOpenOption.READ, NOFOLLOW_LINKS)
    }

    override suspend fun openWrite(ref: NodeRef): OutputStream = io {
        val root = rootDefinition(decode(ref).root)
        val path = resolve(ref)
        // Engine contract: only a newly created staging object is passed here.
        if (Files.size(path) != 0L) fail(StorageError.CONFLICT, "Refusing to truncate a nonempty file")
        val channel = if (root.followLinks) FileChannel.open(path, StandardOpenOption.WRITE)
        else FileChannel.open(path, StandardOpenOption.WRITE, NOFOLLOW_LINKS)
        if (channel.size() != 0L) {
            channel.close()
            fail(StorageError.CONFLICT, "Refusing to write a file that changed after creation")
        }
        val stream = Channels.newOutputStream(channel)
        object : OutputStream() {
            override fun write(b: Int) = stream.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = stream.write(b, off, len)
            override fun flush() = stream.flush()
            override fun close() {
                try { stream.flush(); channel.force(true) } finally { stream.close() }
            }
        }
    }

    override suspend fun relocate(ref: NodeRef, parent: NodeRef, name: String): Entry? = io {
        validateName(name)
        val root = rootDefinition(decode(parent).root)
        val source = resolve(ref)
        val destination = resolve(parent)
        if (!Files.isDirectory(destination)) return@io null
        // Only rename within one filesystem; the caller stages a copy otherwise. Compare st_dev
        // because Files.getFileStore does not work on Android.
        val sameVolume = deviceOf(source) != null && deviceOf(source) == deviceOf(destination)
        if (!sameVolume) return@io null
        val target = destination.resolve(name)
        // An atomic move replaces what it lands on, and collisions are the caller's to settle.
        if (Files.exists(target, NOFOLLOW_LINKS)) throw java.nio.file.FileAlreadyExistsException(target.toString())
        try {
            // Atomic, or not at all: a plain move answers a refused rename (EXDEV between bind mounts of one
            // filesystem, which st_dev cannot tell apart) by copying and deleting, with nothing verified.
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.FileSystemException) {
            return@io null
        }
        entry(root, target)
    }

    override suspend fun availableBytes(parent: NodeRef): Long? = io {
        // Use statvfs because Android throws SecurityException from Files.getFileStore.
        val path = resolve(parent)
        runCatching { Os.statvfs(path.toString()).let { it.f_bavail * it.f_frsize } }.getOrNull()
            ?: runCatching { path.toFile().usableSpace }.getOrNull()?.takeIf { it > 0 }
    }

    private fun deviceOf(path: Path): Long? = runCatching { Os.stat(path.toString()).st_dev }.getOrNull()

    override suspend fun isDescendant(candidate: NodeRef, ancestor: NodeRef): Boolean = io {
        if (candidate.provider != id || ancestor.provider != id) return@io false
        // Compare real paths so overlapping roots and symlink aliases agree on ancestry.
        real(resolve(candidate)).startsWith(real(resolve(ancestor)))
    }

    private fun real(path: Path): Path = runCatching { path.toRealPath() }.getOrDefault(path)

    override suspend fun commit(staged: NodeRef, parent: NodeRef, name: String, replace: Entry?, onRetained: RetainedObjects?): Entry = io {
        validateName(name)
        val root = rootDefinition(decode(parent).root)
        val source = resolve(staged)
        val destinationParent = resolve(parent)
        if (source.parent != destinationParent) fail(StorageError.UNSUPPORTED, "Staged files must belong to the destination folder")
        val target = destinationParent.resolve(name)
        if (replace == null) {
            Files.move(source, target)
        } else {
            val expected = resolve(replace.ref)
            if (expected != target || Files.isDirectory(expected)) {
                fail(StorageError.CONFLICT, "The item being replaced changed; refresh and try again")
            }
            val seen = replace.version ?: fail(StorageError.UNSUPPORTED, "This filesystem cannot identify the item being replaced; choose Keep both")
            if (versionOf(attributes(root, expected)) != seen) {
                fail(StorageError.CONFLICT, "The item being replaced changed; refresh and try again")
            }
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (error: AtomicMoveNotSupportedException) {
                recoverableReplace(source, target, destinationParent, name, error)?.let { leftover ->
                    onRetained?.invoke(reference(root, leftover), "The previous '$name' could not be removed " +
                        "after overwriting it; a full copy remains as ${leftover.fileName}")
                }
            }
        }
        entry(root, target)
    }

    // If replacement fails, restore the old target; return a backup that could not be deleted.
    private fun recoverableReplace(source: Path, target: Path, parent: Path, name: String, cause: Throwable): Path? {
        val backup = parent.resolve(backupName(name))
        try {
            Files.move(target, backup)
        } catch (error: Exception) {
            throw StorageException(StorageError.UNSUPPORTED,
                "This filesystem cannot overwrite safely; choose Keep both", error.also { it.addSuppressed(cause) })
        }
        try {
            Files.move(source, target)
        } catch (error: Exception) {
            val restored = runCatching { Files.move(backup, target) }.isSuccess
            throw StorageException(StorageError.IO, "Overwriting '$name' failed and the previous file was " +
                if (restored) "restored" else "retained as ${backup.fileName}", error)
        }
        return if (runCatching { Files.delete(backup) }.isFailure) backup else null
    }

    private fun backupName(name: String): String {
        val prefix = ".luna-replaced-${UUID.randomUUID().toString().replace("-", "").take(12)}-"
        val limit = 255 - prefix.toByteArray(Charsets.UTF_8).size
        var index = 0
        var used = 0
        while (index < name.length) {
            val point = name.codePointAt(index)
            val size = String(Character.toChars(point)).toByteArray(Charsets.UTF_8).size
            if (used + size > limit) break
            used += size
            index += Character.charCount(point)
        }
        return prefix + name.substring(0, index)
    }

    private data class Location(val root: String, val relative: String)

    /** Key format: `rootId SP relative`. Only the relative path may contain spaces. */
    private fun key(root: String, relative: String) =
        buildString(root.length + relative.length + 1) { append(root); append(SEPARATOR); append(relative) }

    private fun decode(ref: NodeRef): Location {
        if (ref.provider != id) fail(StorageError.DISCONNECTED, "Wrong storage provider")
        val split = ref.key.indexOf(SEPARATOR)
        if (split < 0) fail(StorageError.NOT_FOUND, "Invalid storage reference")
        return Location(ref.key.substring(0, split), ref.key.substring(split + 1))
    }

    private fun rootDefinition(key: String): LocalRoot = rootDefinitions.firstOrNull { it.id == key }
        ?: fail(StorageError.DISCONNECTED, "This storage location is unavailable")

    /** Link-following roots are canonicalized so that `/sdcard` and its real location share one base. */
    private fun basePath(root: LocalRoot): Path = baseCache.getOrPut("${root.id}@${root.path.absolutePath}") {
        val path = root.path.toPath().toAbsolutePath().normalize()
        if (root.followLinks) runCatching { path.toRealPath() }.getOrDefault(path) else path
    }

    private fun attributes(root: LocalRoot, path: Path): BasicFileAttributes =
        if (root.followLinks) Files.readAttributes(path, BasicFileAttributes::class.java)
        else Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)

    private fun resolve(ref: NodeRef): Path {
        val location = decode(ref)
        val root = rootDefinition(location.root)
        val base = basePath(root)
        val path = base.resolve(location.relative).normalize()
        if (!path.startsWith(base)) fail(StorageError.PERMISSION, "Path is outside this storage location")
        if (!root.followLinks) {
            // Check only below the base: the base itself can sit behind a platform symlink such as /data/user/0.
            var current: Path? = path
            while (current != null && current != base) {
                if (Files.isSymbolicLink(current)) fail(StorageError.PERMISSION, "Symbolic links are not supported")
                current = current.parent
            }
        }
        val attrs = attributes(root, path)
        if (!attrs.isDirectory && !attrs.isRegularFile) fail(StorageError.UNSUPPORTED, "This special file is not supported")
        return path
    }

    private fun reference(root: LocalRoot, path: Path): NodeRef =
        NodeRef(id, key(root.id, basePath(root).relativize(path.toAbsolutePath().normalize()).toString()))

    private fun childReference(root: LocalRoot, parentRelative: String, name: String) =
        NodeRef(id, key(root.id, if (parentRelative.isEmpty()) name else "$parentRelative/$name"))

    private fun entryOrPlaceholder(
        root: LocalRoot,
        path: Path,
        parentWritable: Boolean,
        parentRelative: String,
    ): Entry = try {
        entry(root, path, parentWritable, parentRelative)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // stat can fail on an SELinux getattr denial while the directory is still traversable, so
        // probe before classifying the entry as a file.
        val name = path.fileName?.toString().orEmpty()
        val relative = if (parentRelative.isEmpty()) name else "$parentRelative/$name"
        val enterable = probe.traversable(path.toString())
        Entry(NodeRef(id, key(root.id, relative)), name.ifEmpty { relative },
            directory = enterable, size = null, modified = null,
            mimeType = if (enterable) "inode/directory" else "application/x-unreadable",
            capabilities = if (enterable) setOf(Capability.LIST) else emptySet())
    }

    private fun entry(
        root: LocalRoot,
        path: Path,
        parentWritable: Boolean? = null,
        parentRelative: String? = null,
    ): Entry {
        val linkAttributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val followed = root.followLinks && linkAttributes.isSymbolicLink
        val attrs = if (followed) {
            runCatching { Files.readAttributes(path, BasicFileAttributes::class.java) }.getOrDefault(linkAttributes)
        } else linkAttributes
        val childName = path.fileName?.toString().orEmpty()
        val ref = if (parentRelative != null) childReference(root, parentRelative, childName) else reference(root, path)
        val version = versionOf(attrs)
        if (attrs.isSymbolicLink || (!attrs.isDirectory && !attrs.isRegularFile)) {
            return Entry(ref, childName, directory = false,
                size = attrs.size(), modified = attrs.lastModifiedTime().toMillis(),
                mimeType = if (attrs.isSymbolicLink) "inode/symlink" else "application/x-special-file",
                capabilities = emptySet(), version = version)
        }
        val isRoot = parentRelative == null && path.toAbsolutePath().normalize() == basePath(root)
        val caps = mutableSetOf(Capability.HIDDEN)
        if (Files.isReadable(path)) caps += if (attrs.isDirectory) Capability.LIST else Capability.READ
        // Listings (parentRelative != null) skip the per-child write probe; stat() reports the full set.
        if (parentRelative == null && Files.isWritable(path)) {
            if (attrs.isDirectory) {
                caps += Capability.CREATE
                if (attrs.fileKey() != null) caps.addAll(listOf(Capability.REPLACE, Capability.ATOMIC_REPLACE))
            } else {
                caps += Capability.WRITE
            }
        }
        val containerWritable = parentWritable ?: (path.parent != null && Files.isWritable(path.parent))
        if (!isRoot && containerWritable) caps.addAll(listOf(Capability.DELETE, Capability.RENAME))
        if (followed) {
            // A followed link can be read and copied but not deleted: a recursive delete would empty its target.
            caps -= Capability.DELETE
        }
        return Entry(ref, if (isRoot) root.title else childName, attrs.isDirectory,
            if (attrs.isDirectory) null else attrs.size(), attrs.lastModifiedTime().toMillis(),
            if (attrs.isDirectory) "vnd.android.document/directory" else mimeFor(path.fileName.toString()), caps,
            link = followed, version = version)
    }

    // Inodes can be reused after deletion and remain unchanged by in-place edits.
    private fun versionOf(attrs: BasicFileAttributes): String? = attrs.fileKey()?.let { key ->
        "$key:${attrs.size()}:${attrs.lastModifiedTime()}:${attrs.creationTime()}"
    }

    private fun mimeFor(name: String): String = URLConnection.guessContentTypeFromName(name)
        ?: EXTENSION_TYPES[name.substringAfterLast('.', "").lowercase()]
        ?: "application/octet-stream"

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { mapped(block) }
    private suspend fun <T> mapped(block: suspend () -> T): T = try { block() } catch (error: Exception) {
        if (error is CancellationException || error is StorageException) throw error
        val reason = when (error) {
            is java.nio.file.AccessDeniedException, is SecurityException -> StorageError.PERMISSION
            is java.nio.file.NoSuchFileException, is FileNotFoundException -> StorageError.NOT_FOUND
            is java.nio.file.FileAlreadyExistsException, is DirectoryNotEmptyException -> StorageError.CONFLICT
            is java.nio.file.FileSystemException -> if (error.reason?.contains("space", true) == true) StorageError.NO_SPACE else StorageError.IO
            else -> StorageError.IO
        }
        throw StorageException(reason, error.message ?: "The storage operation failed", error)
    }
    private fun fail(reason: StorageError, message: String, cause: Throwable? = null): Nothing = throw StorageException(reason, message, cause)

    private companion object {
        const val SEPARATOR = ' '
        val EXTENSION_TYPES = mapOf(
            "apk" to "application/vnd.android.package-archive", "webp" to "image/webp", "heic" to "image/heic",
            "heif" to "image/heif", "mkv" to "video/x-matroska", "webm" to "video/webm", "opus" to "audio/opus",
            "flac" to "audio/flac", "m4a" to "audio/mp4", "md" to "text/markdown", "json" to "application/json",
            "7z" to "application/x-7z-compressed", "rar" to "application/vnd.rar", "epub" to "application/epub+zip",
        )
    }
}
