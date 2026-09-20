package com.lunaexplorer.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.tar.TarFile
import org.apache.commons.compress.archivers.zip.UnicodePathExtraField
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile as SeekableZip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [total] is the source size when known. [fromStart] marks a sequential scan or a local staging
 * copy; otherwise the index is being read by seeking.
 */
data class ArchiveReading(val bytesRead: Long, val total: Long?, val fromStart: Boolean)

class ArchiveProvider(
    private val registry: () -> ProviderRegistry,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Where stream-only 7z and RAR sources are staged; null uses the JVM's temporary directory. */
    private val stagingDirectory: File? = null,
) : StorageProvider, BudgetedReads, DeferredWrites {
    override val id = "archive"

    private class Opened(
        val source: NodeRef,
        val label: String,
        val version: String?,
        val format: ArchiveFormat,
        val members: List<ArchiveMember>,
        /** Whole-archive protection: 7z content or RAR headers. */
        val encrypted: Boolean,
        @Volatile var reader: KeptReader?,
        /** Local copy of a stream-only source, deleted when the archive leaves the cache. */
        val staged: File?,
        @Volatile var password: String?,
        /** Unapplied writes, keyed by run so one run's failure cannot drop another's. */
        val pending: MutableMap<Any, Pending> = LinkedHashMap(),
        // Computed at open because member capabilities cannot suspend to stat the parent.
        val editable: Boolean = false,
    ) {
        fun discard() {
            reader?.retire()
            staged?.delete()
            synchronized(pending) { pending.values.forEach { it.clear() }; pending.clear() }
        }

        fun holding(run: Any): Pending = synchronized(pending) { pending.getOrPut(run) { Pending() } }

        // Capability checks cannot suspend to identify the caller's run, so inspect all pending writes.
        fun staging(path: String): Boolean =
            synchronized(pending) { pending.values.any { it.added.containsKey(path) } }

        fun held(run: Any): Pending? = synchronized(pending) { pending[run] }

        fun taken(run: Any): Pending? = synchronized(pending) { pending.remove(run) }

        fun withEditability(can: Boolean): Opened =
            Opened(source, label, version, format, members, encrypted, reader, staged, password, pending, can)

        fun effective(run: Any): List<ArchiveMember> {
            val holding = held(run) ?: return members
            if (holding.isEmpty()) return members
            val surviving = members.mapNotNull { member ->
                if (holding.gone(member.path)) null
                else holding.renaming(member.path)?.let { member.copy(path = it) } ?: member
            }
            return surviving + holding.added.values.map { add ->
                ArchiveMember(add.path, add.directory, add.scratch?.length(), add.modified)
            }
        }
    }

    private class Pending {
        val removed = LinkedHashSet<String>()
        val renamed = LinkedHashMap<String, String>()
        val added = LinkedHashMap<String, PendingAdd>()

        fun isEmpty(): Boolean = removed.isEmpty() && renamed.isEmpty() && added.isEmpty()

        fun gone(path: String): Boolean =
            path in removed || removed.any { path.startsWith("$it/") }

        /** [path] after a rename of it or of a folder above it; null if neither was renamed. */
        fun renaming(path: String): String? {
            renamed[path]?.let { return it }
            val folder = renamed.keys.firstOrNull { path.startsWith("$it/") } ?: return null
            return renamed.getValue(folder) + path.removePrefix(folder)
        }

        /** The stored path of a member renamed to [path] this run; null if it was not renamed. */
        fun originalOf(path: String): String? {
            renamed.entries.firstOrNull { it.value == path }?.let { return it.key }
            val moved = renamed.entries.firstOrNull { path.startsWith("${it.value}/") } ?: return null
            return moved.key + path.removePrefix(moved.value)
        }

        fun clear() {
            added.values.forEach { it.scratch?.delete() }
            removed.clear(); renamed.clear(); added.clear()
        }
    }

    private class PendingAdd(
        var path: String,
        val directory: Boolean,
        /** Holds the content until the rewrite; null for a folder. */
        val scratch: File?,
        val modified: Long = System.currentTimeMillis(),
    )

    private object Immediate

    private class Indexed(val members: List<ArchiveMember>, val encrypted: Boolean, val reader: KeptReader)

    /** Per-archive locks prevent a slow source from blocking unrelated archives. */
    private val locks = HashMap<String, Mutex>()
    private val opened = LinkedHashMap<String, Opened>()

    // Progress runs on the reader thread; cancellation must propagate without starting a fallback scan.
    suspend fun open(
        archive: NodeRef,
        label: String,
        version: String? = null,
        password: String? = null,
        progress: (ArchiveReading) -> Unit = {},
    ): NodeRef = withContext(dispatcher) {
        val handle = handleFor(archive)
        val root = NodeRef(id, "$handle|")
        current(handle, version)?.let { cached ->
            if (password != null && cached.password != password) unlockOpened(cached, "", password)
            return@withContext root
        }
        val lock = synchronized(locks) { locks.getOrPut(handle) { Mutex() } }
        lock.withLock {
            val cached = current(handle, version)
            if (cached == null) {
                val fresh = readMembers(archive, label, version, progress, password)
                val retired = synchronized(opened) {
                    val gone = listOfNotNull(opened.remove(handle)).toMutableList()
                    opened[handle] = fresh
                    while (opened.size > 4) opened.remove(opened.keys.first())?.let { gone += it }
                    gone
                }
                retired.forEach { it.discard() }
            } else if (password != null && cached.password != password) {
                unlockOpened(cached, "", password)
            }
        }
        root
    }

    private fun current(handle: String, version: String?): Opened? =
        synchronized(opened) { opened[handle] }?.takeIf { it.version == version && it.reader?.usable != false }

    fun sourceOf(ref: NodeRef): NodeRef? {
        val handle = decode(ref).first
        return synchronized(opened) { opened[handle] }?.source
    }

    // A folder needs a password when any descendant or the whole archive is encrypted.
    fun isEncrypted(ref: NodeRef): Boolean {
        val (handle, path) = runCatching { decode(ref) }.getOrElse { return false }
        val archive = synchronized(opened) { opened[handle] } ?: return false
        if (archive.encrypted) return true
        val member = archive.members.firstOrNull { it.path == path }
        if (path.isNotEmpty() && member?.directory == false) return member.encrypted
        val prefix = if (path.isEmpty()) "" else "$path/"
        return archive.members.any { it.encrypted && it.path.startsWith(prefix) }
    }

    fun hasPassword(ref: NodeRef): Boolean {
        val handle = runCatching { decode(ref).first }.getOrElse { return false }
        return synchronized(opened) { opened[handle] }?.password != null
    }

    fun needsPassword(ref: NodeRef): Boolean = isEncrypted(ref) && !hasPassword(ref)

    // Read an encrypted member completely before retaining the password so its checksum can reject a wrong one.
    suspend fun unlock(ref: NodeRef, password: String) = withContext(dispatcher) {
        val (handle, path) = decode(ref)
        unlockOpened(archiveOf(handle), path, password)
    }

    private suspend fun unlockOpened(archive: Opened, path: String, password: String) {
        val caller = currentCoroutineContext().job
        val prefix = if (path.isEmpty()) "" else "$path/"
        val sample = archive.members.firstOrNull { it.path == path && !it.directory }
            ?: archive.members
                .filter { !it.directory && (it.encrypted || archive.encrypted) && it.path.startsWith(prefix) }
                .minByOrNull { it.size ?: Long.MAX_VALUE }
        when (archive.format) {
            ArchiveFormat.SEVEN_Z, ArchiveFormat.RAR -> {
                // 7z and RAR readers take the password at open, so a verified one replaces the index.
                val fresh = reopenIndexed(archive, password)
                try {
                    sample?.let { member ->
                        verify(fresh.reader.open(member.path, password, caller)
                            ?: throw StorageException(StorageError.NOT_FOUND, "That item is not in this archive"), caller)
                    }
                } catch (failure: Throwable) {
                    fresh.reader.retire()
                    throw failure
                }
                val previous = archive.reader
                archive.reader = fresh.reader
                archive.password = password
                previous?.retire()
            }
            else -> {
                sample?.let { verify(openStream(archive, it, caller, null, password), caller) }
                archive.password = password
            }
        }
    }

    /** Reads [stream] to its end so a wrong password is caught by the decoder or checksum. */
    private fun verify(stream: InputStream, caller: Job) {
        stream.use {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (!caller.isActive) throw CancellationException("Unlocking was given up")
                if (it.read(buffer) < 0) break
            }
        }
    }

    private fun handleFor(archive: NodeRef) = "${archive.provider}${archive.key}"

    private fun decode(ref: NodeRef): Pair<String, String> {
        if (ref.provider != id) throw StorageException(StorageError.DISCONNECTED, "Wrong storage provider")
        val separator = ref.key.lastIndexOf('|')
        if (separator < 0) throw StorageException(StorageError.NOT_FOUND, "Invalid archive reference")
        return ref.key.substring(0, separator) to ref.key.substring(separator + 1)
    }

    private fun refFor(handle: String, path: String) = NodeRef(id, "$handle|$path")

    private fun archiveOf(handle: String): Opened = synchronized(opened) { opened[handle] }
        ?: throw StorageException(StorageError.DISCONNECTED, "This archive is no longer open")

    // The Unicode Path extra field is valid only when its CRC matches the raw non-UTF-8 name.
    private fun pathOf(entry: ZipArchiveEntry): String {
        val unicode = if (entry.generalPurposeBit.usesUTF8ForNames()) null else entry.rawName?.let { raw ->
            (entry.getExtraField(UnicodePathExtraField.UPATH_ID) as? UnicodePathExtraField)
                ?.takeIf { field -> CRC32().apply { update(raw) }.value == field.nameCRC32 }
                ?.unicodeName?.let { String(it, Charsets.UTF_8) }
        }
        return normalizedMemberPath(unicode ?: entry.name)
    }

    private fun memberOf(path: String, entry: ZipEntry, encrypted: Boolean) = ArchiveMember(
        path = path,
        directory = entry.isDirectory,
        size = entry.size.takeIf { it >= 0 },
        modified = entry.time.takeIf { it > 0 },
        encrypted = encrypted,
    )

    /** Unknown names are tried as zip. */
    private suspend fun readMembers(
        archive: NodeRef,
        label: String,
        version: String?,
        progress: (ArchiveReading) -> Unit,
        password: String?,
    ): Opened {
        val provider = registry().provider(archive)
        val read = when (val format = archiveFormatOf(label) ?: ArchiveFormat.ZIP) {
            ArchiveFormat.ZIP -> readZip(provider, archive, label, version, progress, password)
            ArchiveFormat.TAR -> readTar(provider, archive, label, version, progress, password)
            ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_BZ2 ->
                readTarStream(provider, archive, label, version, format, progress, password)
            ArchiveFormat.SEVEN_Z, ArchiveFormat.RAR ->
                readIndexed(provider, archive, label, version, format, progress, password)
            ArchiveFormat.GZIP, ArchiveFormat.XZ, ArchiveFormat.BZIP2 ->
                throw StorageException(StorageError.UNSUPPORTED, "This is a single compressed file. Extract it instead.")
        }
        return try {
            val editable = canEdit(provider, archive, label, read)
            currentCoroutineContext().ensureActive()
            read.withEditability(editable)
        } catch (failure: Throwable) {
            read.discard()
            throw failure
        }
    }

    private suspend fun sourceSize(provider: StorageProvider, archive: NodeRef): Long? = try {
        provider.stat(archive).size
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        null
    }

    private suspend fun canEdit(
        provider: StorageProvider,
        archive: NodeRef,
        label: String,
        read: Opened,
    ): Boolean {
        if (archiveFormatOf(label) != ArchiveFormat.ZIP) return false
        // A staged copy is a local cache; editing it would not change the source.
        if (read.staged != null) return false
        // A nested archive would need every enclosing archive rewritten.
        if (provider.id == id) return false
        return try {
            val parent = provider.parentOf(archive) ?: return false
            val folder = provider.stat(parent)
            Capability.ATOMIC_REPLACE in folder.capabilities || Capability.REPLACE in folder.capabilities
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            false
        }
    }

    /**
     * Reads the central directory through a seekable channel, skipping per-member local headers,
     * and keeps the channel open for member reads.
     *
     * Stream-only sources and files without a usable ZIP directory fall back to [ZipInputStream],
     * which inflates preceding members to locate each header. That walk cannot pass an encrypted
     * member, so such archives are staged to a local copy whose directory is then read.
     * Cancellation and channel failures propagate without fallback.
     */
    private suspend fun readZip(
        provider: StorageProvider,
        archive: NodeRef,
        label: String,
        version: String?,
        progress: (ArchiveReading) -> Unit,
        password: String?,
    ): Opened {
        val job = currentCoroutineContext().job
        val report = Reporter(progress)
        val channel = holding({ it?.close() }) { provider.openChannel(archive) }
        if (channel != null) {
            val total = runCatching { channel.size() }.getOrNull()
            val watched = WatchedChannel(channel, job) { report(ArchiveReading(it, total, fromStart = false)) }
            val zip = runCatching {
                SeekableZip.builder().setSeekableByteChannel(watched).setIgnoreLocalFileHeader(true).get()
            }.getOrNull()
            if (zip == null) {
                runCatching { channel.close() }
                // Cancellation must not start a sequential fallback.
                currentCoroutineContext().ensureActive()
                // A channel failure does not mean the ZIP directory is invalid; do not fall back.
                watched.failure?.let { throw sourceFailure(it) }
            } else {
                return indexZip(zip, watched, archive, label, version, password, staged = null)
                    .also { watched.stopWatching() }
            }
        }
        // No channel, or no ZIP directory at the end of the file: walk forward.
        val total = sourceSize(provider, archive)
        val index = MemberIndex()
        val raw = holding({ it.close() }) { provider.openRead(archive) }
        var refusedByWalk = false
        try {
            raw.use {
                val watched = WatchedStream(raw, job) { report(ArchiveReading(it, total, fromStart = true)) }
                ZipInputStream(watched.buffered(128 * 1024)).use { zip ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = try {
                            zip.nextEntry
                        } catch (refused: ZipException) {
                            // Encrypted members, and stored members with data descriptors, need the
                            // central directory; a local copy provides it.
                            refusedByWalk = true
                            null
                        } ?: break
                        index.add(memberOf(normalizedMemberPath(entry.name), entry, encrypted = false))
                        zip.closeEntry()
                    }
                }
                report(ArchiveReading(watched.bytes, total, fromStart = true), closing = true)
            }
        } catch (stopped: InterruptedIOException) {
            currentCoroutineContext().ensureActive()
            throw stopped
        }
        if (!refusedByWalk) {
            return Opened(archive, label, version, ArchiveFormat.ZIP, index.toList(), encrypted = false,
                reader = null, staged = null, password = password)
        }
        val staged = stage(provider, archive, ".zip", job, report, total)
        val local = FileChannel.open(staged.toPath(), StandardOpenOption.READ)
        val zip = try {
            SeekableZip.builder().setSeekableByteChannel(local).setIgnoreLocalFileHeader(true).get()
        } catch (failure: Throwable) {
            runCatching { local.close() }
            staged.delete()
            throw failure
        }
        return indexZip(zip, local, archive, label, version, password, staged)
    }

    private suspend fun indexZip(
        zip: SeekableZip,
        channel: SeekableByteChannel,
        archive: NodeRef,
        label: String,
        version: String?,
        password: String?,
        staged: File?,
    ): Opened {
        try {
            currentCoroutineContext().ensureActive()
            val index = MemberIndex()
            val entries = HashMap<String, ZipArchiveEntry>()
            val all = zip.entries
            while (all.hasMoreElements()) {
                currentCoroutineContext().ensureActive()
                val entry = all.nextElement()
                val path = pathOf(entry)
                // Duplicate names resolve to the last member for both listing and opening.
                if (index.add(memberOf(path, entry, entry.isEncryptedMember()))) entries[path] = entry
            }
            return Opened(archive, label, version, ArchiveFormat.ZIP, index.toList(), encrypted = false,
                reader = KeptZip(zip, channel, entries), staged = staged, password = password)
        } catch (failure: Throwable) {
            runCatching { zip.close() }
            staged?.delete()
            throw failure
        }
    }

    private suspend fun readTar(
        provider: StorageProvider,
        archive: NodeRef,
        label: String,
        version: String?,
        progress: (ArchiveReading) -> Unit,
        password: String?,
    ): Opened {
        val job = currentCoroutineContext().job
        val report = Reporter(progress)
        val channel = holding({ it?.close() }) { provider.openChannel(archive) }
            ?: return readTarStream(provider, archive, label, version, ArchiveFormat.TAR, progress, password)
        val total = runCatching { channel.size() }.getOrNull()
        val watched = WatchedChannel(channel, job) { report(ArchiveReading(it, total, fromStart = false)) }
        val tar = try {
            TarFile(watched)
        } catch (failure: Throwable) {
            runCatching { channel.close() }
            currentCoroutineContext().ensureActive()
            throw watched.failure?.let { sourceFailure(it) } ?: failure
        }
        try {
            currentCoroutineContext().ensureActive()
            val (members, entries) = indexTar(tar)
            watched.stopWatching()
            return Opened(archive, label, version, ArchiveFormat.TAR, members, encrypted = false,
                reader = KeptTar(tar, watched, entries), staged = null, password = password)
        } catch (failure: Throwable) {
            runCatching { tar.close() }
            throw failure
        }
    }

    private suspend fun readTarStream(
        provider: StorageProvider,
        archive: NodeRef,
        label: String,
        version: String?,
        format: ArchiveFormat,
        progress: (ArchiveReading) -> Unit,
        password: String?,
    ): Opened {
        val job = currentCoroutineContext().job
        val report = Reporter(progress)
        val total = sourceSize(provider, archive)
        val raw = holding({ it.close() }) { provider.openRead(archive) }
        val members = try {
            raw.use {
                val watched = WatchedStream(raw, job) { report(ArchiveReading(it, total, fromStart = true)) }
                walkTar(watched.buffered(128 * 1024), format) { job.ensureActive() }.also {
                    report(ArchiveReading(watched.bytes, total, fromStart = true), closing = true)
                }
            }
        } catch (stopped: InterruptedIOException) {
            currentCoroutineContext().ensureActive()
            throw stopped
        }
        return Opened(archive, label, version, format, members, encrypted = false,
            reader = null, staged = null, password = password)
    }

    private suspend fun readIndexed(
        provider: StorageProvider,
        archive: NodeRef,
        label: String,
        version: String?,
        format: ArchiveFormat,
        progress: (ArchiveReading) -> Unit,
        password: String?,
    ): Opened {
        val job = currentCoroutineContext().job
        val report = Reporter(progress)
        var staged: File? = null
        val channel = holding({ it?.close() }) { provider.openChannel(archive) } ?: run {
            val total = sourceSize(provider, archive)
            val copy = stage(provider, archive, ".${format.extension}", job, report, total)
            staged = copy
            FileChannel.open(copy.toPath(), StandardOpenOption.READ)
        }
        try {
            val total = runCatching { channel.size() }.getOrNull()
            val watched = WatchedChannel(channel, job) { report(ArchiveReading(it, total, fromStart = false)) }
            val indexed = try {
                if (format == ArchiveFormat.SEVEN_Z) indexSevenZ(watched, label, password)
                else indexRar(watched, total ?: channel.size(), password)
            } catch (failure: Throwable) {
                runCatching { channel.close() }
                currentCoroutineContext().ensureActive()
                throw watched.failure?.let { sourceFailure(it) } ?: failure
            }
            watched.stopWatching()
            return Opened(archive, label, version, format, indexed.members, indexed.encrypted,
                indexed.reader, staged, password)
        } catch (failure: Throwable) {
            staged?.delete()
            throw failure
        }
    }

    private fun indexSevenZ(channel: SeekableByteChannel, label: String, password: String?): Indexed {
        // A failed read reopens the archive for fresh decoder state; only the reader closes the channel.
        val shared = NonClosingChannel(channel)
        val file = openSevenZ(shared, label, password)
        try {
            val (members, entries) = sevenZMembers(file)
            var probeFailed = false
            val encrypted = sevenZEncrypted(file, password) { probeFailed = true }
            val flagged = if (encrypted) members.map { if (it.directory) it else it.copy(encrypted = true) } else members
            return Indexed(flagged, encrypted, KeptSevenZ(file, entries, encrypted,
                passwordGiven = password != null, source = channel as? CancellableSource,
                reopen = { openSevenZ(shared, label, password) }, closeChannel = { channel.close() },
                stale = probeFailed))
        } catch (failure: Throwable) {
            runCatching { file.close() }
            throw failure
        }
    }

    private fun indexRar(channel: SeekableByteChannel, length: Long, password: String?): Indexed {
        // A solid read reopens the archive for fresh decoder state; only the reader closes the channel.
        val shared = NonClosingChannel(channel)
        val rar = openRar(shared, length, password)
        try {
            val (members, headers) = rarMembers(rar)
            val encrypted = runCatching { rar.isEncrypted }.getOrDefault(false)
            val reader = KeptRar(rar, headers, passwordGiven = password != null,
                reopen = { openRar(shared, length, password) },
                closeChannel = { channel.close() })
            return Indexed(members, encrypted, reader)
        } catch (failure: Throwable) {
            runCatching { rar.close() }
            throw failure
        }
    }

    private suspend fun reopenIndexed(archive: Opened, password: String): Indexed {
        val channel = archive.staged?.let { FileChannel.open(it.toPath(), StandardOpenOption.READ) }
            ?: holding({ it?.close() }) { registry().provider(archive.source).openChannel(archive.source) }
            ?: throw StorageException(StorageError.UNSUPPORTED, "This storage cannot seek in the archive")
        // JobChannel lets a member open be cancelled by its caller.
        val watched = JobChannel(channel)
        try {
            return if (archive.format == ArchiveFormat.SEVEN_Z) indexSevenZ(watched, archive.label, password)
            else indexRar(watched, watched.size(), password)
        } catch (failure: Throwable) {
            runCatching { channel.close() }
            throw failure
        }
    }

    private suspend fun stage(
        provider: StorageProvider,
        archive: NodeRef,
        suffix: String,
        job: Job,
        report: Reporter,
        total: Long?,
    ): File {
        val raw = holding({ it.close() }) { provider.openRead(archive) }
        val file = File.createTempFile("luna-archive", suffix, stagingDirectory)
        try {
            raw.use { input ->
                val watched = WatchedStream(input, job) { report(ArchiveReading(it, total, fromStart = true)) }
                file.outputStream().use { output -> watched.copyTo(output, 128 * 1024) }
                report(ArchiveReading(watched.bytes, total, fromStart = true), closing = true)
            }
        } catch (failure: Throwable) {
            file.delete()
            if (failure is InterruptedIOException) currentCoroutineContext().ensureActive()
            throw failure
        }
        return file
    }

    private fun sourceFailure(failure: Throwable): Throwable =
        failure as? StorageException ?: StorageException(StorageError.IO, failure.message ?: "The archive could not be read", failure)

    private fun entryFor(handle: String, member: ArchiveMember): Entry {
        val archive = synchronized(opened) { opened[handle] }
        return Entry(
            ref = refFor(handle, member.path),
            name = member.path.substringAfterLast('/'),
            directory = member.directory,
            size = if (member.directory) null else member.size,
            modified = member.modified,
            mimeType = if (member.directory) "vnd.android.document/directory" else guessType(member.path),
            capabilities = buildSet {
                if (member.directory) add(Capability.LIST) else add(Capability.READ)
                add(Capability.HIDDEN)
                if (archive?.editable == true) {
                    add(Capability.RENAME)
                    add(Capability.DELETE)
                    if (member.directory) {
                        add(Capability.CREATE)
                        // A replacement is part of the one rewrite, which is published atomically.
                        add(Capability.ATOMIC_REPLACE)
                    }
                }
                // Only a member created this run and not yet packed accepts bytes.
                if (archive?.staging(member.path) == true) add(Capability.WRITE)
            },
        )
    }

    private fun guessType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "txt", "md", "log", "json", "xml", "csv" -> "text/plain"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        "apk" -> "application/vnd.android.package-archive"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    override suspend fun stat(ref: NodeRef): Entry = withContext(dispatcher) {
        val (handle, path) = decode(ref)
        val archive = archiveOf(handle)
        if (path.isEmpty()) {
            return@withContext Entry(ref, archive.label, directory = true,
                // No RENAME or DELETE on the root: that is the archive file, which its folder owns.
                capabilities = buildSet {
                    add(Capability.LIST); add(Capability.HIDDEN)
                    if (archive.editable) { add(Capability.CREATE); add(Capability.ATOMIC_REPLACE) }
                })
        }
        val member = archive.effective(writer()).firstOrNull { it.path == path }
            ?: throw StorageException(StorageError.NOT_FOUND, "That item is not in this archive")
        entryFor(handle, member)
    }

    override suspend fun parentOf(ref: NodeRef): NodeRef? {
        val (handle, path) = decode(ref)
        return if (path.isEmpty()) null else refFor(handle, path.substringBeforeLast('/', ""))
    }

    override fun list(parent: NodeRef, complete: Boolean): Flow<List<Entry>> = flow {
        val (handle, path) = decode(parent)
        val archive = archiveOf(handle)
        val prefix = if (path.isEmpty()) "" else "$path/"
        val children = archive.effective(writer()).filter { member ->
            member.path.startsWith(prefix) &&
                member.path.length > prefix.length &&
                !member.path.substring(prefix.length).contains('/')
        }
        for (batch in children.chunked(128)) {
            currentCoroutineContext().ensureActive()
            emit(batch.map { entryFor(handle, it) })
        }
    }.flowOn(dispatcher)

    /**
     * An encrypted member without a stored password fails with [StorageError.AUTH]. The open is
     * not cancellable, so the handle cannot leak; if the caller was cancelled meanwhile, the
     * stream is closed before the cancellation propagates.
     */
    override suspend fun openRead(ref: NodeRef): InputStream = openMember(ref, null)

    override suspend fun openRead(ref: NodeRef, budget: ReadBudget): InputStream = openMember(ref, budget)

    private suspend fun openMember(ref: NodeRef, budget: ReadBudget?): InputStream {
        val caller = currentCoroutineContext().job
        caller.ensureActive()
        budget?.checkCancelled()
        val stream = withContext(dispatcher + NonCancellable) { member(ref, caller, budget) }
        if (!caller.isActive) {
            withContext(dispatcher + NonCancellable) { runCatching { stream.close() } }
            throw CancellationException("Reading was given up")
        }
        return stream
    }

    private suspend fun member(ref: NodeRef, caller: Job, budget: ReadBudget?): InputStream {
        val (handle, path) = decode(ref)
        val archive = archiveOf(handle)
        val holding = archive.held(writer())
        // Created this run and not yet packed: read the scratch file.
        holding?.added?.get(path)?.let { staged ->
            return staged.scratch?.inputStream() ?: ByteArrayInputStream(ByteArray(0))
        }
        // Renamed this run: the archive still stores it under the old name.
        val stored = holding?.originalOf(path) ?: path
        if (holding?.gone(stored) == true) {
            throw StorageException(StorageError.NOT_FOUND, "That item is not in this archive")
        }
        val member = archive.members.firstOrNull { it.path == stored }
            ?: throw StorageException(StorageError.NOT_FOUND, "That item is not in this archive")
        return openStream(archive, member, caller, budget, archive.password)
    }

    private suspend fun openStream(
        archive: Opened,
        member: ArchiveMember,
        caller: Job,
        budget: ReadBudget?,
        password: String?,
    ): InputStream {
        if (member.directory) return object : InputStream() { override fun read() = -1 }
        if ((member.encrypted || archive.encrypted) && password == null) {
            throw StorageException(StorageError.AUTH, "This item needs a password")
        }
        // The kept reader's channel was opened without a budget. A budgeted read opens its own
        // source handle so archive headers and compressed bytes are charged to it.
        archive.reader?.takeIf { budget == null }?.let { kept ->
            val stream = try {
                kept.open(member.path, password, caller) ?: MISSING
            } catch (_: ReaderBusy) {
                // Exclusive reader in use; open a separate handle below.
                null
            } catch (classified: StorageException) {
                throw classified
            } catch (stopped: InterruptedIOException) {
                // Cancellation must not retire the index or start a fallback read.
                throw stopped
            } catch (unsupported: ZipException) {
                // Unsupported encryption or compression cannot be recovered by reopening the source.
                throw StorageException(StorageError.UNSUPPORTED, unsupported.message ?: "This item cannot be read", unsupported)
            } catch (_: IOException) {
                // Retire the failed channel; reopen the source below.
                kept.retire()
                null
            }
            if (stream === MISSING) throw StorageException(StorageError.NOT_FOUND, "That item is not in this archive")
            if (stream != null) return stream
        }
        return freshMember(archive, member, caller, budget, password)
    }

    /** Opens a member over its own source handle: a budgeted read, or a retired or busy index. */
    private suspend fun freshMember(
        archive: Opened,
        member: ArchiveMember,
        caller: Job,
        budget: ReadBudget?,
        password: String?,
    ): InputStream {
        val provider = registry().provider(archive.source)
        val path = member.path
        val check = { if (!caller.isActive) throw CancellationException("Reading was given up") }
        fun limited(): BudgetedReads = provider as? BudgetedReads
            ?: throw StorageException(StorageError.UNSUPPORTED, "This storage cannot limit preview downloads")
        suspend fun channel(): SeekableByteChannel? =
            if (budget == null) provider.openChannel(archive.source) else limited().openChannel(archive.source, budget)
        suspend fun stream(): InputStream =
            if (budget == null) provider.openRead(archive.source) else limited().openRead(archive.source, budget)

        return when (archive.format) {
            ArchiveFormat.ZIP -> {
                // A staged copy is local, so reading it is not charged to the budget.
                val local = archive.staged?.let { FileChannel.open(it.toPath(), StandardOpenOption.READ) }
                (local ?: channel())?.let { zipMember(it, path, password, budget) }?.let { return it }
                val raw = stream()
                if (member.encrypted) decryptZipMember(raw.buffered(128 * 1024), null, path, password!!)
                else walkZip(raw, path, check)
            }
            ArchiveFormat.TAR -> {
                channel()?.let { tarMember(it, path, budget) }?.let { return it }
                tarMemberStream(stream().buffered(128 * 1024), ArchiveFormat.TAR, path, check)
            }
            ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_BZ2 ->
                tarMemberStream(stream().buffered(128 * 1024), archive.format, path, check)
            ArchiveFormat.SEVEN_Z, ArchiveFormat.RAR -> {
                val channel = archive.staged?.let { FileChannel.open(it.toPath(), StandardOpenOption.READ) }
                    ?: channel()
                    ?: throw StorageException(StorageError.UNSUPPORTED, "This storage cannot seek in the archive")
                // Opening a member of a solid block decodes everything before it; stop when the caller does.
                indexedMember(JobChannel(channel, caller), archive, member, password, budget)
            }
            ArchiveFormat.GZIP, ArchiveFormat.XZ, ArchiveFormat.BZIP2 ->
                throw StorageException(StorageError.UNSUPPORTED, "This is a single compressed file. Extract it instead.")
        }
    }

    /**
     * Seeks to the member's indexed offset without inflating preceding members. Returns null when
     * the channel does not hold a readable zip directory, so the caller can walk the stream instead.
     */
    private fun zipMember(channel: SeekableByteChannel, path: String, password: String?, budget: ReadBudget?): InputStream? =
        runCatching {
            val zip = SeekableZip.builder().setSeekableByteChannel(channel).setIgnoreLocalFileHeader(true).get()
            try {
                val entry = zip.entries.asSequence().lastOrNull { pathOf(it) == path }
                when {
                    entry == null -> { zip.close(); null }
                    entry.isEncryptedMember() -> {
                        val key = password ?: throw StorageException(StorageError.AUTH, "This item needs a password")
                        val slice = ChannelSlice(channel, entry.localHeaderOffset, channel.size())
                        // Closing the member stream closes the archive, and with it the channel.
                        closing(decryptZipMember(slice, entry, null, key)) { zip.close() }
                    }
                    else -> closing(zip.getInputStream(entry)) { zip.close() }
                }
            } catch (failure: Throwable) {
                runCatching { zip.close() }
                throw failure
            }
        }.onFailure {
            runCatching { channel.close() }
            budget?.checkCancelled()
            if (it is InterruptedIOException || it is CancellationException || it is StorageException) throw it
            if (budget?.remaining == 0L) throw ReadBudgetExceeded(budget.max)
        }.getOrNull()

    /** Walks a zip stream to [path]; the caller owns the stream and closing it closes the source. */
    private fun walkZip(raw: InputStream, path: String, check: () -> Unit): InputStream {
        val zip = ZipInputStream(raw.buffered(128 * 1024))
        try {
            while (true) {
                check()
                val entry = zip.nextEntry ?: break
                if (normalizedMemberPath(entry.name) == path) return zip
                zip.closeEntry()
            }
            throw StorageException(StorageError.NOT_FOUND, "That item is not in this archive")
        } catch (failure: Throwable) {
            runCatching { zip.close() }
            throw failure
        }
    }

    private fun tarMember(channel: SeekableByteChannel, path: String, budget: ReadBudget?): InputStream {
        try {
            val tar = TarFile(channel)
            try {
                val entry = tar.entries.lastOrNull { normalizedMemberPath(it.name) == path }
                    ?: throw StorageException(StorageError.NOT_FOUND, "That item is not in this archive")
                return closing(tar.getInputStream(entry)) { tar.close() }
            } catch (failure: Throwable) {
                runCatching { tar.close() }
                throw failure
            }
        } catch (failure: Throwable) {
            runCatching { channel.close() }
            budget?.checkCancelled()
            if (budget?.remaining == 0L && failure !is StorageException) throw ReadBudgetExceeded(budget.max)
            throw failure
        }
    }

    private fun indexedMember(
        channel: SeekableByteChannel,
        archive: Opened,
        member: ArchiveMember,
        password: String?,
        budget: ReadBudget?,
    ): InputStream {
        try {
            if (archive.format == ArchiveFormat.SEVEN_Z) {
                val file = openSevenZ(channel, archive.label, password)
                try {
                    val entry = file.entries.firstOrNull { !it.isAntiItem && it.name?.let(::normalizedMemberPath) == member.path }
                        ?: throw StorageException(StorageError.NOT_FOUND, "That item is not in this archive")
                    return sevenZMemberStream(file, entry, member.encrypted || archive.encrypted, password != null) {
                        runCatching { file.close() }
                    }
                } catch (failure: Throwable) {
                    runCatching { file.close() }
                    throw failure
                }
            }
            val rar = openRar(channel, channel.size(), password)
            try {
                val header = rar.fileHeaders.lastOrNull { normalizedMemberPath(it.fileName) == member.path }
                    ?: throw StorageException(StorageError.NOT_FOUND, "That item is not in this archive")
                return rarMemberStream(rar, header, header.isEncrypted, password != null) { runCatching { rar.close() } }
            } catch (failure: Throwable) {
                runCatching { rar.close() }
                throw failure
            }
        } catch (failure: Throwable) {
            runCatching { channel.close() }
            budget?.checkCancelled()
            if (budget?.remaining == 0L && failure !is StorageException) throw ReadBudgetExceeded(budget.max)
            throw failure
        }
    }

    private fun closing(stream: InputStream, andThen: () -> Unit): InputStream = object : InputStream() {
        override fun read() = stream.read()
        override fun read(b: ByteArray, off: Int, len: Int) = stream.read(b, off, len)
        override fun available() = stream.available()
        override fun close() {
            runCatching { stream.close() }
            andThen()
        }
    }

    override suspend fun isDescendant(candidate: NodeRef, ancestor: NodeRef): Boolean {
        if (candidate.provider != id || ancestor.provider != id) return false
        val (candidateHandle, candidatePath) = decode(candidate)
        val (ancestorHandle, ancestorPath) = decode(ancestor)
        if (candidateHandle != ancestorHandle) return false
        return ancestorPath.isEmpty() || candidatePath == ancestorPath ||
            candidatePath.startsWith("$ancestorPath/")
    }

    // Mutations are recorded in Pending and applied by one rewrite per archive in flush(). Reads
    // within the run see them as already applied.
    override suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String): Entry {
        val (handle, folder) = decode(parent)
        val archive = editableArchive(handle, "added to")
        validateName(name)
        requireMemberName(name)
        val path = if (folder.isEmpty()) name else "${folder.trimEnd('/')}/$name"
        if (archive.effective(writer()).any { it.path == path || it.path == "$path/" }) {
            throw StorageException(StorageError.CONFLICT, "$name is already in this archive")
        }
        val scratch = if (directory) null else File(scratchRoot(), "luna-member-${UUID.randomUUID()}").also {
            it.parentFile?.mkdirs(); it.createNewFile()
        }
        val run = writer()
        archive.holding(run).added[path] = PendingAdd(path, directory, scratch)
        val made = stat(refFor(handle, path))
        // A folder made outside a run gets no commit(), so apply it here or it never would be.
        if (directory) settle(handle, run)
        return made
    }

    override suspend fun openWrite(ref: NodeRef): OutputStream {
        val (handle, path) = decode(ref)
        val archive = editableArchive(handle, "written to")
        val staged = archive.held(writer())?.added?.get(path)
            ?: throw StorageException(StorageError.UNSUPPORTED,
                "Only a newly created item inside an archive can be written to")
        val scratch = staged.scratch
            ?: throw StorageException(StorageError.UNSUPPORTED, "A folder holds no bytes")
        return scratch.outputStream()
    }

    override suspend fun commit(
        staged: NodeRef,
        parent: NodeRef,
        name: String,
        replace: Entry?,
        onRetained: RetainedObjects?,
    ): Entry {
        val (handle, stagedPath) = decode(staged)
        val archive = editableArchive(handle, "added to")
        val run = writer()
        val pending = archive.holding(run)
        val holding = pending.added.remove(stagedPath)
            ?: throw StorageException(StorageError.NOT_FOUND, "That staged item is no longer here")
        validateName(name)
        requireMemberName(name)
        val (_, folder) = decode(parent)
        val target = if (folder.isEmpty()) name else "${folder.trimEnd('/')}/$name"
        replace?.let { existing ->
            val (_, replacedPath) = decode(existing.ref)
            if (pending.added.remove(replacedPath) == null) pending.removed += replacedPath
        }
        // Members written under a staged folder move with it to the target path.
        val moved = pending.added.keys.filter { it.startsWith("$stagedPath/") }.mapNotNull { child ->
            pending.added.remove(child)?.also { it.path = target + child.removePrefix(stagedPath) }
        }
        holding.path = target
        pending.added[target] = holding
        moved.forEach { pending.added[it.path] = it }
        val committed = stat(refFor(handle, target))
        settle(handle, run)
        return committed
    }

    override suspend fun rename(ref: NodeRef, name: String): Entry {
        val (_, path) = decode(ref)
        validateName(name)
        requireMemberName(name)
        val folder = path.trimEnd('/').substringBeforeLast('/', "")
        return move(ref, if (folder.isEmpty()) name else "$folder/$name")
    }

    override suspend fun relocate(ref: NodeRef, parent: NodeRef, name: String): Entry? {
        val (handle, _) = decode(ref)
        val (parentHandle, folder) = decode(parent)
        // A move between two archives is a copy; null makes the engine fall back to one.
        if (parentHandle != handle) return null
        validateName(name)
        requireMemberName(name)
        return move(ref, if (folder.isEmpty()) name else "${folder.trimEnd('/')}/$name")
    }

    private suspend fun move(ref: NodeRef, target: String): Entry {
        val (handle, path) = decode(ref)
        val archive = editableArchive(handle, "renamed")
        if (target == path) throw StorageException(StorageError.UNSUPPORTED, "That is already its name")
        if (archive.effective(writer()).any { it.path == target }) {
            throw StorageException(StorageError.CONFLICT, "${target.substringAfterLast('/')} is already in this archive")
        }
        val run = writer()
        val pending = archive.holding(run)
        val staged = pending.added.remove(path)
        if (staged != null) {
            // Not packed yet: retarget the pending addition.
            staged.path = target
            pending.added[target] = staged
        } else {
            val stored = pending.originalOf(path) ?: path
            pending.renamed[stored] = target
        }
        val renamed = stat(refFor(handle, target))
        settle(handle, run)
        return renamed
    }

    override suspend fun delete(ref: NodeRef) {
        val (handle, path) = decode(ref)
        val archive = editableArchive(handle, "deleted")
        val run = writer()
        val pending = archive.holding(run)
        val staged = pending.added.remove(path)
        if (staged != null) { staged.scratch?.delete(); return }
        val stored = pending.originalOf(path) ?: path
        pending.renamed.remove(stored)
        pending.removed += stored
        settle(handle, run)
    }

    private suspend fun writer(): Any = currentCoroutineContext()[StorageRun] ?: Immediate

    /** Applies a write made outside a run; a run's writes wait for [flush]. */
    private suspend fun settle(handle: String, run: Any) {
        if (run !== Immediate) return
        flushOne(handle, run)
    }

    // Publish archives gaining members before removing those members from source archives.
    override suspend fun flush(run: StorageRun) {
        val handles = synchronized(opened) { opened.keys.toList() }
        val (gaining, others) = handles.partition { handle ->
            synchronized(opened) { opened[handle] }?.held(run)?.added?.isNotEmpty() == true
        }
        (gaining + others).forEach { flushOne(it, run) }
    }

    private suspend fun flushOne(handle: String, run: Any) {
        val archive = synchronized(opened) { opened[handle] } ?: return
        val pending = archive.taken(run) ?: return
        if (pending.isEmpty()) return
        val edits = ArchiveEdits(
            removed = pending.removed.toSet(),
            renamed = pending.renamed.toMap(),
            added = pending.added.values.map {
                ArchiveAddition(path = it.path, directory = it.directory, file = it.scratch)
            },
        )
        try {
            applyEdits(handle, archive, edits)
        } finally {
            pending.clear()
        }
    }

    override suspend fun discardPending(run: StorageRun) {
        val archives = synchronized(opened) { opened.values.toList() }
        archives.forEach { it.taken(run)?.clear() }
    }

    /** Member refs are "<handle>|<path>", split at the last '|', so a name must not contain one. */
    private fun requireMemberName(name: String) {
        if (name.contains('|')) {
            throw StorageException(StorageError.UNSUPPORTED, "A name inside an archive cannot contain |")
        }
    }

    private fun scratchRoot(): File = stagingDirectory ?: File(System.getProperty("java.io.tmpdir"))

    private fun editableArchive(handle: String, verb: String): Opened {
        val archive = archiveOf(handle)
        if (!archive.editable) {
            throw StorageException(StorageError.UNSUPPORTED, when {
                archive.format != ArchiveFormat.ZIP ->
                    "${archive.format.label} archives can be opened but not $verb. Extract it, change it, and pack it again."
                archive.staged != null ->
                    "${archive.label} is being read from a copy, so it cannot be changed where it is stored"
                else -> "${archive.label} cannot be changed where it is stored"
            })
        }
        return archive
    }

    // Replace the cached index after rewriting; its source handle and member offsets are stale.
    private suspend fun applyEdits(handle: String, archive: Opened, edits: ArchiveEdits) {
        if (edits.empty) return
        val rewriter = ArchiveRewriter(registry(), dispatcher, stagingDirectory)
            .unlockedWith(archive.password)
        var produced: NodeRef? = null
        // A failed rewrite changes nothing on disk, so the failure propagates and the index stays.
        rewriter.rewrite(archive.source, edits).collect { step ->
            step.produced?.let { produced = it }
        }
        // Re-read under the same handle: member refs are "<handle>|<path>", so a new handle would
        // invalidate every ref the browser holds. Read first and swap after, so a failed re-read
        // leaves the old index bound to the handle.
        val source = produced ?: archive.source
        val reread = readMembers(source, archive.label, version = null, progress = {}, password = archive.password)
        val retired = synchronized(opened) { opened.put(handle, reread) }
        retired?.let { old ->
            // Another run may still hold writes for this archive; carry them over to the new index.
            synchronized(old.pending) {
                synchronized(reread.pending) { reread.pending.putAll(old.pending) }
                old.pending.clear()
            }
            old.discard()
        }
    }

    // Open without cancellation, then close the acquired handle if the caller was cancelled meanwhile.
    private suspend fun <T> holding(close: (T) -> Unit, open: suspend () -> T): T {
        val caller = currentCoroutineContext().job
        caller.ensureActive()
        val result = withContext(NonCancellable) { open() }
        if (!caller.isActive) {
            runCatching { close(result) }
            throw CancellationException("Given up")
        }
        return result
    }

    /**
     * [entries] is keyed by normalized path. Encrypted members are decrypted from their local
     * header through the same channel.
     */
    private class KeptZip(
        private val zip: SeekableZip,
        private val channel: SeekableByteChannel,
        private val entries: Map<String, ZipArchiveEntry>,
    ) : KeptReader() {
        override fun stream(path: String, password: String?, caller: Job?, release: () -> Unit): InputStream? {
            val entry = entries[path] ?: return null
            if (entry.isEncryptedMember()) {
                val key = password ?: throw StorageException(StorageError.AUTH, "This item needs a password")
                val end = synchronized(channel) { channel.size() }
                return Counted(decryptZipMember(ChannelSlice(channel, entry.localHeaderOffset, end), entry, null, key), release)
            }
            // Local-header reads share the channel with active members and need the same lock.
            val stream: InputStream = synchronized(channel) { zip.getInputStream(entry) } ?: return null
            return Counted(stream, release)
        }

        override fun closeSource() = zip.close()
    }

    /**
     * Counts index-read bytes and checks cancellation at each read. Records channel failures so a
     * parser cannot misreport them as malformed input. Watching stops after indexing; the channel
     * stays open for member reads.
     */
    private class WatchedChannel(
        private val inner: SeekableByteChannel,
        job: Job,
        report: (Long) -> Unit,
    ) : SeekableByteChannel, CancellableSource {
        @Volatile private var job: Job? = job
        @Volatile private var report: ((Long) -> Unit)? = report
        @Volatile private var reader: Job? = null
        private var bytes = 0L

        override fun readFor(job: Job?) { reader = job }

        @Volatile var failure: Throwable? = null
            private set

        fun stopWatching() { job = null; report = null }

        private inline fun <T> recording(block: () -> T): T = try {
            block()
        } catch (thrown: Throwable) {
            failure = thrown
            throw thrown
        }

        override fun read(dst: ByteBuffer): Int {
            if (job?.isActive == false) throw InterruptedIOException("Opening the archive was given up")
            if (reader?.isActive == false) throw InterruptedIOException("Reading was given up")
            val got = recording { inner.read(dst) }
            report?.let { if (got > 0) { bytes += got; it(bytes) } }
            return got
        }

        override fun write(src: ByteBuffer): Int = inner.write(src)
        override fun position(): Long = recording { inner.position() }
        override fun position(newPosition: Long): SeekableByteChannel = apply { recording { inner.position(newPosition) } }
        override fun size(): Long = recording { inner.size() }
        override fun truncate(size: Long): SeekableByteChannel = apply { inner.truncate(size) }
        override fun isOpen(): Boolean = inner.isOpen
        override fun close() = inner.close()
    }

    private class WatchedStream(
        private val inner: InputStream,
        private val job: Job,
        private val report: (Long) -> Unit,
    ) : InputStream() {
        var bytes = 0L
            private set

        private fun check() { if (!job.isActive) throw InterruptedIOException("Opening the archive was given up") }

        override fun read(): Int {
            check()
            return inner.read().also { if (it >= 0) { bytes++; report(bytes) } }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            check()
            return inner.read(b, off, len).also { if (it > 0) { bytes += it; report(bytes) } }
        }

        override fun available() = inner.available()
        override fun close() = inner.close()
    }

    private class Reporter(private val sink: (ArchiveReading) -> Unit) {
        private var sent = false
        private var sentAt = 0L

        operator fun invoke(reading: ArchiveReading, closing: Boolean = false) {
            val now = System.nanoTime()
            if (sent && !closing && now - sentAt < 100_000_000L) return
            sent = true
            sentAt = now
            sink(reading)
        }
    }

    private companion object {
        val MISSING: InputStream = object : InputStream() { override fun read() = -1 }
    }
}
