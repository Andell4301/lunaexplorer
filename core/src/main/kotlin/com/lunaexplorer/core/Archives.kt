package com.lunaexplorer.core

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import net.lingala.zip4j.io.inputstream.ZipInputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

val BROWSABLE_ARCHIVE_EXTENSIONS = setOf("zip", "jar", "apk", "apks", "xapk", "apkm", "epub")

@Serializable
enum class ArchiveFormat(
    val label: String,
    val extension: String,
    val compressible: Boolean,
    val creatable: Boolean = true,
) {
    ZIP("Zip", "zip", true),
    TAR("Tar", "tar", false),
    TAR_GZ("Tar + gzip", "tar.gz", true),
    TAR_XZ("Tar + xz", "tar.xz", true),
    GZIP("Gzip", "gz", true),
    XZ("Xz", "xz", true),
    TAR_BZ2("Tar + bzip2", "tar.bz2", true, creatable = false),
    BZIP2("Bzip2", "bz2", true, creatable = false),
    SEVEN_Z("7z", "7z", true, creatable = false),
    RAR("Rar", "rar", true, creatable = false),
}

fun archiveFormatOf(name: String): ArchiveFormat? {
    val lower = name.lowercase()
    return when {
        lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> ArchiveFormat.TAR_GZ
        lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> ArchiveFormat.TAR_XZ
        lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tbz") -> ArchiveFormat.TAR_BZ2
        lower.endsWith(".tar") -> ArchiveFormat.TAR
        lower.endsWith(".gz") -> ArchiveFormat.GZIP
        lower.endsWith(".xz") -> ArchiveFormat.XZ
        lower.endsWith(".bz2") -> ArchiveFormat.BZIP2
        lower.endsWith(".7z") -> ArchiveFormat.SEVEN_Z
        lower.endsWith(".rar") -> ArchiveFormat.RAR
        lower.substringAfterLast('.', "") in BROWSABLE_ARCHIVE_EXTENSIONS -> ArchiveFormat.ZIP
        else -> null
    }
}

private val STAGING_PREFIXES = listOf("luna-archive", "luna-extract", "luna-rewrite", "luna-member")

fun sweepArchiveStaging(directory: File) {
    directory.listFiles { file -> STAGING_PREFIXES.any { file.name.startsWith(it) } }?.forEach { it.delete() }
}

enum class CompressionPreset(val label: String, val level: Int) {
    STORE("Store", 0),
    FAST("Fast", 1),
    NORMAL("Normal", 6),
    BEST("Best", 9),
}

data class ArchiveOptions(
    val format: ArchiveFormat = ArchiveFormat.ZIP,
    /** 0 stores, 1 is fastest, 9 is smallest. */
    val level: Int = CompressionPreset.NORMAL.level,
    val comment: String = "",
    val encryption: ZipEncryption = ZipEncryption.NONE,
    val password: String = "",
) {
    val stored: Boolean get() = level <= 0
}

// Queue payloads record only whether a password is needed; the password stays in memory.
@Serializable
data class ArchiveSpec(
    val format: ArchiveFormat = ArchiveFormat.ZIP,
    val level: Int = CompressionPreset.NORMAL.level,
    val comment: String = "",
    val encryption: ZipEncryption = ZipEncryption.NONE,
    val needsPassword: Boolean = false,
)

fun ArchiveOptions.spec(): ArchiveSpec =
    ArchiveSpec(format, level, comment, encryption, needsPassword = password.isNotEmpty())

fun ArchiveSpec.options(password: String): ArchiveOptions =
    ArchiveOptions(format, level, comment, encryption, password)

data class ArchiveProgress(
    val currentName: String = "",
    val entriesDone: Int = 0,
    val entriesTotal: Int = 0,
    val bytesDone: Long = 0,
    val complete: Boolean = false,
    val produced: NodeRef? = null,
    /** Total source bytes; null when any source size is unknown, and always null when extracting. */
    val bytesTotal: Long? = null,
    // Emitted before any member: the staging file when creating, the destination folder when extracting.
    val target: NodeRef? = null,
)

class ArchiveEngine(
    private val registry: ProviderRegistry,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Where stream-only 7z and RAR sources are staged; null uses the JVM's temporary directory. */
    private val stagingDirectory: File? = null,
) {
    fun create(
        sources: List<NodeRef>,
        parent: NodeRef,
        name: String,
        options: ArchiveOptions,
    ): Flow<ArchiveProgress> = flow {
        validateName(name)
        if (!options.format.creatable) {
            throw StorageException(StorageError.UNSUPPORTED, "${options.format.label} archives can be opened but not created")
        }
        val target = registry.provider(parent)
        val staged = target.create(parent, stagingName(), directory = false, mimeType = "application/octet-stream")
        var published: Entry? = null
        try {
            val plan = mutableListOf<Planned>()
            for (source in sources) {
                val provider = registry.provider(source)
                plan(provider, provider.stat(source), "", plan)
            }
            val expected = if (plan.any { !it.entry.directory && it.entry.size == null }) null
                else plan.filter { !it.entry.directory }.sumOf { it.entry.size ?: 0L }
            emit(ArchiveProgress(entriesTotal = plan.size, bytesTotal = expected, target = staged.ref))

            var done = 0
            var bytes = 0L
            val onEntry: suspend (String, Long) -> Unit = { path, written ->
                done++
                bytes += written
                emit(ArchiveProgress(path, done, plan.size, bytes, bytesTotal = expected))
            }
            target.openWrite(staged.ref).use { raw ->
                val sink = BufferedOutputStream(raw, 128 * 1024)
                when (options.format) {
                    ArchiveFormat.ZIP -> writeZip(plan, sink, options, onEntry)
                    ArchiveFormat.TAR -> writeTar(plan, sink, onEntry)
                    ArchiveFormat.TAR_GZ -> GZIPOutputStream(sink).use { writeTar(plan, it, onEntry) }
                    ArchiveFormat.TAR_XZ -> XZOutputStream(sink, xzOptions(options.level)).use { writeTar(plan, it, onEntry) }
                    ArchiveFormat.GZIP, ArchiveFormat.XZ -> {
                        val single = plan.singleOrNull { !it.entry.directory }
                            ?: throw StorageException(StorageError.UNSUPPORTED,
                                "${options.format.label} holds a single file; choose Tar + ${options.format.label.lowercase()} for more than one")
                        val wrapped = if (options.format == ArchiveFormat.XZ) {
                            XZOutputStream(sink, xzOptions(options.level))
                        } else {
                            GZIPOutputStream(sink)
                        }
                        val written = wrapped.use { copy(registry.provider(single.entry.ref), single.entry.ref, it) }
                        onEntry(single.entry.name, written)
                    }
                    ArchiveFormat.TAR_BZ2, ArchiveFormat.BZIP2, ArchiveFormat.SEVEN_Z, ArchiveFormat.RAR ->
                        throw StorageException(StorageError.UNSUPPORTED, "${options.format.label} archives can be opened but not created")
                }
                sink.flush()
            }
            published = target.commit(staged.ref, parent, name)
            emit(ArchiveProgress(name, done, plan.size, bytes, complete = true, produced = published.ref,
                bytesTotal = expected))
        } finally {
            // NonCancellable: a cancelled run would otherwise skip the suspending delete and leave
            // the staging file behind.
            if (published == null) withContext(NonCancellable) { runCatching { target.delete(staged.ref) } }
        }
    }.flowOn(dispatcher)

    fun extract(
        archive: NodeRef,
        parent: NodeRef,
        into: String?,
        password: String = "",
    ): Flow<ArchiveProgress> = flow {
        val source = registry.provider(archive)
        val item = source.stat(archive)
        val target = registry.provider(parent)
        val destination = if (into == null) parent else {
            validateName(into)
            target.create(parent, into, directory = true).ref
        }
        emit(ArchiveProgress(target = destination))
        val format = formatOf(item.name)
        var done = 0
        var bytes = 0L
        val onMember: suspend (String, Boolean, InputStream) -> Unit = { name, directory, stream ->
            bytes += unpack(target, destination, name, directory, stream)
            done++
            emit(ArchiveProgress(name, done, 0, bytes))
        }
        when (format) {
            ArchiveFormat.SEVEN_Z -> indexedSource(source, archive, format).use { channel ->
                extractSevenZ(channel, item.name, password, onMember)
            }
            ArchiveFormat.RAR -> indexedSource(source, archive, format).use { channel ->
                extractRar(channel, password, target, destination) { name, written ->
                    bytes += written
                    done++
                    emit(ArchiveProgress(name, done, 0, bytes))
                }
            }
            else -> source.openRead(archive).use { raw ->
                val buffered = raw.buffered(128 * 1024)
                when (format) {
                    ArchiveFormat.ZIP -> {
                        val zip = if (password.isEmpty()) ZipInputStream(buffered)
                        else ZipInputStream(buffered, password.toCharArray())
                        zip.use {
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val entry = try { it.nextEntry } catch (failure: Throwable) { throw zipFailure(failure) } ?: break
                                onMember(entry.fileName, entry.isDirectory, Mapped(it, ::zipFailure))
                            }
                        }
                    }
                    ArchiveFormat.TAR -> readTar(buffered, onMember)
                    ArchiveFormat.TAR_GZ -> GZIPInputStream(buffered).use { readTar(it, onMember) }
                    ArchiveFormat.TAR_XZ -> XZInputStream(buffered).use { readTar(it, onMember) }
                    ArchiveFormat.TAR_BZ2 -> BZip2CompressorInputStream(buffered, true).use { readTar(it, onMember) }
                    ArchiveFormat.GZIP, ArchiveFormat.XZ, ArchiveFormat.BZIP2 -> {
                        val stream = when (format) {
                            ArchiveFormat.XZ -> XZInputStream(buffered)
                            ArchiveFormat.BZIP2 -> BZip2CompressorInputStream(buffered, true)
                            else -> GZIPInputStream(buffered)
                        }
                        stream.use {
                            val name = item.name.removeSuffix(".gz").removeSuffix(".xz").removeSuffix(".bz2").ifBlank { "extracted" }
                            bytes += unpack(target, destination, name, false, it)
                            done++
                            emit(ArchiveProgress(name, done, 1, bytes))
                        }
                    }
                    ArchiveFormat.SEVEN_Z, ArchiveFormat.RAR -> error("handled above")
                }
            }
        }
        emit(ArchiveProgress(item.name, done, done, bytes, complete = true, produced = destination))
    }.flowOn(dispatcher)

    /** Unknown names are treated as zip. */
    fun formatOf(name: String): ArchiveFormat = archiveFormatOf(name) ?: ArchiveFormat.ZIP

    fun canExtract(entry: Entry): Boolean = !entry.directory && archiveFormatOf(entry.name) != null

    // Compressed tar listings decompress the whole file, so browsing them is limited to local sources.
    fun canBrowse(entry: Entry): Boolean {
        if (entry.directory) return false
        return when (archiveFormatOf(entry.name)) {
            ArchiveFormat.ZIP, ArchiveFormat.TAR, ArchiveFormat.SEVEN_Z, ArchiveFormat.RAR -> true
            ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_BZ2 -> overNetwork(entry.ref) == false
            ArchiveFormat.GZIP, ArchiveFormat.XZ, ArchiveFormat.BZIP2, null -> false
        }
    }

    /** Whether reads of [ref] cross the network; null when its provider cannot be resolved. */
    private fun overNetwork(ref: NodeRef): Boolean? {
        var current = ref
        repeat(8) {
            val provider = runCatching { registry.provider(current) }.getOrNull() ?: return null
            if (Feature.NETWORK in provider.features) return true
            // Members of an archive are read from the archive's own source.
            current = (provider as? ArchiveProvider)?.let { runCatching { it.sourceOf(current) }.getOrNull() } ?: return false
        }
        return null
    }

    private data class Planned(val entry: Entry, val path: String)

    private suspend fun plan(provider: StorageProvider, entry: Entry, prefix: String, into: MutableList<Planned>, depth: Int = 0) {
        currentCoroutineContext().ensureActive()
        if (depth > 128) throw StorageException(StorageError.UNSUPPORTED, "Folder tree is too deeply nested to archive")
        val path = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
        if (entry.link) return
        into += Planned(entry, path)
        if (!entry.directory) return
        val children = mutableListOf<Entry>()
        provider.list(entry.ref).collect { children.addAll(it) }
        for (child in children) plan(provider, child, path, into, depth + 1)
    }

    private suspend fun writeZip(
        plan: List<Planned>,
        sink: OutputStream,
        options: ArchiveOptions,
        onEntry: suspend (String, Long) -> Unit,
    ) {
        val encrypting = options.encryption != ZipEncryption.NONE && options.password.isNotEmpty()
        // zip4j closes its output stream; the sink belongs to the caller.
        val shielded = NonClosing(sink)
        val zip = if (encrypting) ZipOutputStream(shielded, options.password.toCharArray())
        else ZipOutputStream(shielded)
        if (options.comment.isNotBlank()) zip.setComment(options.comment)

        for (planned in plan) {
            currentCoroutineContext().ensureActive()
            val provider = registry.provider(planned.entry.ref)
            val directory = planned.entry.directory
            val parameters = ZipParameters().apply {
                fileNameInZip = if (directory) "${planned.path}/" else planned.path
                compressionMethod = if (options.stored) CompressionMethod.STORE else CompressionMethod.DEFLATE
                compressionLevel = zipLevel(options.level)
                planned.entry.modified?.let { lastModifiedFileTime = it }
                if (encrypting && !directory) {
                    isEncryptFiles = true
                    encryptionMethod = options.encryption.method
                    aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                }
                // Stored ZIP entries require the uncompressed size in their header.
                if (options.stored && !directory) entrySize = planned.entry.size ?: -1L
            }
            zip.putNextEntry(parameters)
            val written = if (directory) 0L else copy(provider, planned.entry.ref, zip)
            zip.closeEntry()
            onEntry(planned.path, written)
        }
        zip.close()
    }

    private fun zipLevel(level: Int): CompressionLevel {
        val levels = CompressionLevel.entries
        return levels.firstOrNull { it.level == level.coerceIn(0, 9) } ?: levels.first { it.level == 6 }
    }

    private suspend fun writeTar(
        plan: List<Planned>,
        sink: OutputStream,
        onEntry: suspend (String, Long) -> Unit,
    ) {
        val tar = TarArchiveOutputStream(NonClosing(sink))
        // POSIX (pax) records carry paths longer than the ustar name field.
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
        for (planned in plan) {
            currentCoroutineContext().ensureActive()
            val provider = registry.provider(planned.entry.ref)
            val directory = planned.entry.directory
            val record = TarArchiveEntry(if (directory) "${planned.path}/" else planned.path)
            planned.entry.modified?.let { record.setModTime(it) }
            if (directory) {
                tar.putArchiveEntry(record)
                tar.closeArchiveEntry()
                onEntry(planned.path, 0)
                continue
            }
            // TAR headers require the content size before the entry is written.
            val bytes = readFully(provider, planned.entry.ref)
            record.size = bytes.size.toLong()
            tar.putArchiveEntry(record)
            tar.write(bytes)
            tar.closeArchiveEntry()
            onEntry(planned.path, bytes.size.toLong())
        }
        tar.finish()
        tar.flush()
    }

    private suspend fun readTar(
        source: InputStream,
        onEntry: suspend (String, Boolean, InputStream) -> Unit,
    ) {
        TarArchiveInputStream(source).use { tar ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val record = tar.nextEntry ?: return
                val name = record.name.trimEnd('/')
                if (name.isEmpty()) continue
                onEntry(name, record.isDirectory, tar)
            }
        }
    }

    /** The provider's channel, or a temporary local copy that is deleted on close. */
    private suspend fun indexedSource(source: StorageProvider, archive: NodeRef, format: ArchiveFormat): SeekableByteChannel {
        source.openChannel(archive)?.let { return it }
        val staged = File.createTempFile("luna-extract", ".${format.extension}", stagingDirectory)
        try {
            source.openRead(archive).use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (failure: Throwable) {
            staged.delete()
            throw failure
        }
        val channel = FileChannel.open(staged.toPath(), StandardOpenOption.READ)
        return object : SeekableByteChannel by channel {
            override fun close() {
                channel.close()
                staged.delete()
            }
        }
    }

    /** Reads 7z members in archive order, which keeps solid blocks sequential. */
    private suspend fun extractSevenZ(
        channel: SeekableByteChannel,
        name: String,
        password: String,
        onMember: suspend (String, Boolean, InputStream) -> Unit,
    ) {
        val passwordGiven = password.isNotEmpty()
        openSevenZ(channel, name, password.takeIf { it.isNotEmpty() }).use { file ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = try {
                    file.nextEntry
                } catch (failure: Throwable) {
                    throw sevenZFailure(failure, encrypted = true, passwordGiven = passwordGiven)
                } ?: break
                if (entry.isAntiItem) continue
                val memberName = normalizedMemberPath(entry.name ?: continue)
                if (memberName.isEmpty()) continue
                val encrypted = entry.contentMethods?.any { it.method == SevenZMethod.AES256SHA256 } == true
                val stream = Mapped(object : InputStream() {
                    override fun read(): Int = file.read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int = file.read(b, off, len)
                }) { sevenZFailure(it, encrypted, passwordGiven) }
                onMember(memberName, entry.isDirectory, stream)
            }
        }
    }

    /** Extracts RAR members in archive order on the calling thread; solid blocks stay sequential. */
    private suspend fun extractRar(
        channel: SeekableByteChannel,
        password: String,
        target: StorageProvider,
        destination: NodeRef,
        onMember: suspend (String, Long) -> Unit,
    ) {
        val passwordGiven = password.isNotEmpty()
        val job = currentCoroutineContext().job
        openRar(channel, channel.size(), password.takeIf { it.isNotEmpty() }).use { rar ->
            for (header in rar.fileHeaders) {
                job.ensureActive()
                val name = normalizedMemberPath(header.fileName ?: continue)
                if (name.isEmpty()) continue
                val written = unpackWith(target, destination, name, header.isDirectory) { output ->
                    val checked = object : OutputStream() {
                        override fun write(b: Int) { job.ensureActive(); output.write(b) }
                        override fun write(b: ByteArray, off: Int, len: Int) { job.ensureActive(); output.write(b, off, len) }
                        override fun flush() = output.flush()
                    }
                    try {
                        rar.extractFile(header, checked)
                    } catch (failure: Throwable) {
                        job.ensureActive()
                        throw rarFailure(failure, header.isEncrypted, passwordGiven)
                    }
                }
                onMember(name, written)
            }
        }
    }

    private suspend fun unpack(
        target: StorageProvider,
        destination: NodeRef,
        rawName: String,
        directory: Boolean,
        stream: InputStream,
    ): Long = unpackWith(target, destination, rawName, directory) { output ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = stream.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
        }
    }

    private suspend fun unpackWith(
        target: StorageProvider,
        destination: NodeRef,
        rawName: String,
        directory: Boolean,
        write: suspend (OutputStream) -> Unit,
    ): Long {
        // "." and ".." segments are dropped so a member cannot escape the destination; existing
        // links are refused in extractionChild.
        val segments = rawName.split('/', '\\').filter { it.isNotEmpty() && it != "." && it != ".." }
        if (segments.isEmpty()) return 0
        var parent = destination
        for (segment in segments.dropLast(1)) parent = folder(target, parent, segment)
        val name = segments.last()
        if (directory) { folder(target, parent, name); return 0 }
        validateName(name)
        val existing = extractionChild(target, parent, name)
        if (existing != null) target.delete(existing.ref)
        val created = target.create(parent, name, directory = false)
        var written = 0L
        target.openWrite(created.ref).use { output ->
            val counting = object : OutputStream() {
                override fun write(b: Int) { output.write(b); written++ }
                override fun write(b: ByteArray, off: Int, len: Int) { output.write(b, off, len); written += len }
                override fun flush() = output.flush()
            }
            write(counting)
        }
        return written
    }

    private suspend fun folder(target: StorageProvider, parent: NodeRef, name: String): NodeRef {
        extractionChild(target, parent, name)?.let { if (it.directory) return it.ref }
        validateName(name)
        return target.create(parent, name, directory = true).ref
    }

    private suspend fun extractionChild(provider: StorageProvider, parent: NodeRef, name: String): Entry? {
        var found: Entry? = null
        provider.list(parent).collect { batch ->
            batch.forEach { if (found == null && provider.namesEqual(it.name, name)) found = it }
        }
        // Stat before reusing or replacing an entry: listings may have stale or partial metadata.
        return found?.let { provider.stat(it.ref) }?.also {
            if (it.link) throw StorageException(StorageError.UNSUPPORTED,
                "Cannot extract to a symbolic link: ${it.name}")
        }
    }

    private suspend fun copy(provider: StorageProvider, ref: NodeRef, sink: OutputStream): Long {
        var written = 0L
        provider.openRead(ref).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                sink.write(buffer, 0, read)
                written += read
            }
        }
        return written
    }

    private suspend fun readFully(provider: StorageProvider, ref: NodeRef): ByteArray {
        val output = ByteArrayOutputStream()
        copy(provider, ref, output)
        return output.toByteArray()
    }

    private fun xzOptions(level: Int) = LZMA2Options(level.coerceIn(0, 9))

    private fun stagingName(): String = ".luna-archive-${UUID.randomUUID()}.partial"
}

/** Flushes on close without closing the caller-owned stream. */
class NonClosing(private val sink: OutputStream) : OutputStream() {
    override fun write(b: Int) = sink.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = sink.write(b, off, len)
    override fun flush() = sink.flush()
    override fun close() = sink.flush()
}
