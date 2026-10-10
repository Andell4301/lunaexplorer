package com.lunaexplorer.core

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import com.github.junrar.rarfile.HostSystem
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile as SeekableZip
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
    /** Where stream-only ZIP, 7z and RAR sources are staged; null uses the JVM's temporary directory. */
    private val stagingDirectory: File? = null,
) {
    fun create(
        sources: List<NodeRef>,
        parent: NodeRef,
        name: String,
        options: ArchiveOptions,
        verify: Boolean = false,
        onSource: (suspend (Entry, ByteArray?) -> Unit)? = null,
        onVerification: ((suspend () -> Unit) -> Unit)? = null,
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
            if (verify) {
                val paths = plan.map { normalizedMemberPath(it.path) }
                val files = plan.indices.filter { !plan[it].entry.directory }.mapTo(hashSetOf()) { paths[it] }
                val overlaps = paths.any { path ->
                    generateSequence(path.substringBeforeLast('/', "")) { parent ->
                        parent.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
                    }.any { it in files }
                }
                if (paths.any { it.isEmpty() } || paths.toSet().size != paths.size ||
                    overlaps) {
                    throw StorageException(StorageError.CONFLICT, "Archive member names collide; source retained")
                }
            }
            val expected = if (plan.any { !it.entry.directory && it.entry.size == null }) null
                else plan.filter { !it.entry.directory }.sumOf { it.entry.size ?: 0L }
            emit(ArchiveProgress(entriesTotal = plan.size, bytesTotal = expected, target = staged.ref))

            var done = 0
            var bytes = 0L
            if (verify && options.format in setOf(ArchiveFormat.GZIP, ArchiveFormat.XZ) &&
                (plan.size != 1 || plan.single().entry.directory)) {
                throw StorageException(StorageError.UNSUPPORTED, "A compressed file cannot retain source folders")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val onEntry: suspend (String, Long) -> Unit = { path, written ->
                done++
                bytes += written
                emit(ArchiveProgress(path, done, plan.size, bytes, bytesTotal = expected))
            }
            target.openWrite(staged.ref).use { raw ->
                val sink = BufferedOutputStream(if (verify) DigestOutputStream(raw, digest) else raw, 128 * 1024)
                when (options.format) {
                    ArchiveFormat.ZIP -> writeZip(plan, sink, options, onEntry, onSource)
                    ArchiveFormat.TAR -> writeTar(plan, sink, onEntry, onSource)
                    ArchiveFormat.TAR_GZ -> GZIPOutputStream(sink).use { writeTar(plan, it, onEntry, onSource) }
                    ArchiveFormat.TAR_XZ -> XZOutputStream(sink, xzOptions(options.level)).use { writeTar(plan, it, onEntry, onSource) }
                    ArchiveFormat.GZIP, ArchiveFormat.XZ -> {
                        val single = plan.singleOrNull { !it.entry.directory }
                            ?: throw StorageException(StorageError.UNSUPPORTED,
                                "${options.format.label} holds a single file; choose Tar + ${options.format.label.lowercase()} for more than one")
                        val wrapped = if (options.format == ArchiveFormat.XZ) {
                            XZOutputStream(sink, xzOptions(options.level))
                        } else {
                            GZIPOutputStream(sink)
                        }
                        val sourceDigest = onSource?.let { MessageDigest.getInstance("SHA-256") }
                        val written = wrapped.use {
                            copy(registry.provider(single.entry.ref), single.entry.ref,
                                if (sourceDigest != null) DigestOutputStream(it, sourceDigest) else it)
                        }
                        onSource?.invoke(single.entry, sourceDigest?.digest())
                        onEntry(single.entry.name, written)
                    }
                    ArchiveFormat.TAR_BZ2, ArchiveFormat.BZIP2, ArchiveFormat.SEVEN_Z, ArchiveFormat.RAR ->
                        throw StorageException(StorageError.UNSUPPORTED, "${options.format.label} archives can be opened but not created")
                }
                sink.flush()
            }
            val expectedArchiveDigest = if (verify) digest.digest() else null
            if (expectedArchiveDigest != null) verifyBytes(target, staged.ref, expectedArchiveDigest)
            published = target.commit(staged.ref, parent, name)
            if (expectedArchiveDigest != null) {
                val ref = published.ref
                onVerification?.invoke { verifyBytes(target, ref, expectedArchiveDigest) }
            }
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
        verify: Boolean = false,
        onVerification: ((suspend () -> Unit) -> Unit)? = null,
        sourceDigest: ByteArray? = null,
        keepBoth: Boolean = false,
    ): Flow<ArchiveProgress> = flow {
        val source = registry.provider(archive)
        val item = source.stat(archive)
        val verifiedSource = sourceDigest?.let { stageVerifiedSource(source, archive, it) }
        try {
            val target = registry.provider(parent)
            val destination = if (into == null) parent else {
                validateName(into)
                val name = if (keepBoth) extractionName(target, parent, into, directory = true) else into
                target.create(parent, name, directory = true).ref
            }
            emit(ArchiveProgress(target = destination))
            val format = formatOf(item.name)
            var done = 0
            var bytes = 0L
            val verification = if (verify) ExtractionVerification() else null
            val names = ExtractionNames(archive, keepBoth)
            val onMember: suspend (String, Boolean, InputStream) -> Unit = { name, directory, stream ->
                bytes += unpack(target, destination, name, directory, stream, verification, names)
                done++
                emit(ArchiveProgress(name, done, 0, bytes))
            }
            when (format) {
                ArchiveFormat.ZIP -> (verifiedSource?.let { FileChannel.open(it.toPath(), StandardOpenOption.READ) }
                    ?: indexedSource(source, archive, format)).use { channel ->
                    extractZip(channel, password, verify, onMember)
                }
                ArchiveFormat.SEVEN_Z -> (verifiedSource?.let { FileChannel.open(it.toPath(), StandardOpenOption.READ) }
                    ?: indexedSource(source, archive, format)).use { channel ->
                    extractSevenZ(channel, item.name, password, verify, onMember)
                }
                ArchiveFormat.RAR -> (verifiedSource?.let { FileChannel.open(it.toPath(), StandardOpenOption.READ) }
                    ?: indexedSource(source, archive, format)).use { channel ->
                    extractRar(channel, password, target, destination, verification, names) { name, written ->
                        bytes += written
                        done++
                        emit(ArchiveProgress(name, done, 0, bytes))
                    }
                }
                else -> (verifiedSource?.inputStream() ?: source.openRead(archive)).use { raw ->
                    val buffered = raw.buffered(128 * 1024)
                    when (format) {
                        ArchiveFormat.TAR -> readTar(buffered, verify, onMember)
                        ArchiveFormat.TAR_GZ -> GZIPInputStream(buffered).use { readTar(it, verify, onMember) }
                        ArchiveFormat.TAR_XZ -> XZInputStream(buffered).use { readTar(it, verify, onMember) }
                        ArchiveFormat.TAR_BZ2 -> BZip2CompressorInputStream(buffered, true).use { readTar(it, verify, onMember) }
                        ArchiveFormat.GZIP, ArchiveFormat.XZ, ArchiveFormat.BZIP2 -> {
                            val stream = when (format) {
                                ArchiveFormat.XZ -> XZInputStream(buffered)
                                ArchiveFormat.BZIP2 -> BZip2CompressorInputStream(buffered, true)
                                else -> GZIPInputStream(buffered)
                            }
                            stream.use {
                                val name = item.name.removeSuffix(".gz").removeSuffix(".xz").removeSuffix(".bz2").ifBlank { "extracted" }
                                bytes += unpack(target, destination, name, false, it, verification, names)
                                done++
                                emit(ArchiveProgress(name, done, 1, bytes))
                            }
                        }
                        ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z, ArchiveFormat.RAR -> error("handled above")
                    }
                }
            }
            if (verification != null) {
                val verifyOutput: suspend () -> Unit = {
                    verification.files.forEach { (ref, digest) -> verifyBytes(target, ref, digest) }
                    verification.directories.forEach { ref ->
                        if (!target.stat(ref).directory) throw StorageException(StorageError.IO, "Extracted folder changed; source retained")
                    }
                }
                verifyOutput()
                onVerification?.invoke(verifyOutput)
            }
            emit(ArchiveProgress(item.name, done, done, bytes, complete = true, produced = destination))
        } finally {
            verifiedSource?.delete()
        }
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
        provider.list(entry.ref, complete = true).collect { children.addAll(it) }
        for (child in children) plan(provider, child, path, into, depth + 1)
    }

    private suspend fun writeZip(
        plan: List<Planned>,
        sink: OutputStream,
        options: ArchiveOptions,
        onEntry: suspend (String, Long) -> Unit,
        onSource: (suspend (Entry, ByteArray?) -> Unit)?,
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
            val sourceDigest = onSource?.let { MessageDigest.getInstance("SHA-256") }
            val written = if (directory) 0L else copy(provider, planned.entry.ref,
                if (sourceDigest != null) DigestOutputStream(zip, sourceDigest) else zip)
            zip.closeEntry()
            onSource?.invoke(planned.entry, if (directory) null else sourceDigest?.digest())
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
        onSource: (suspend (Entry, ByteArray?) -> Unit)?,
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
                onSource?.invoke(planned.entry, null)
                onEntry(planned.path, 0)
                continue
            }
            // TAR headers require the content size before the entry is written.
            val bytes = readFully(provider, planned.entry.ref)
            record.size = bytes.size.toLong()
            tar.putArchiveEntry(record)
            tar.write(bytes)
            tar.closeArchiveEntry()
            onSource?.invoke(planned.entry, MessageDigest.getInstance("SHA-256").digest(bytes))
            onEntry(planned.path, bytes.size.toLong())
        }
        tar.finish()
        tar.flush()
    }

    private suspend fun readTar(
        source: InputStream,
        verify: Boolean,
        onEntry: suspend (String, Boolean, InputStream) -> Unit,
    ) {
        TarArchiveInputStream(source).use { tar ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val record = tar.nextEntry ?: return
                if (verify && !record.isFile && !record.isDirectory) {
                    throw StorageException(StorageError.UNSUPPORTED, "Archive contains an unsupported member: ${record.name}")
                }
                val name = record.name.trimEnd('/')
                if (name.isEmpty()) {
                    if (verify) throw StorageException(StorageError.UNSUPPORTED, "Archive contains an unnamed member")
                    continue
                }
                onEntry(name, record.isDirectory, tar)
            }
        }
    }

    private suspend fun stageVerifiedSource(provider: StorageProvider, ref: NodeRef, digest: ByteArray): File {
        val staged = File.createTempFile("luna-extract", ".verified", stagingDirectory)
        try {
            staged.outputStream().use { output -> copy(provider, ref, output) }
            staged.inputStream().use { input -> verifyBytes(input, digest) }
            return staged
        } catch (failure: Throwable) {
            staged.delete()
            throw failure
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

    private suspend fun extractZip(
        channel: SeekableByteChannel,
        password: String,
        verify: Boolean,
        onMember: suspend (String, Boolean, InputStream) -> Unit,
    ) {
        val job = currentCoroutineContext().job
        val watched = JobChannel(NonClosingChannel(channel), job)
        try {
            SeekableZip.builder().setSeekableByteChannel(watched).setCharset("CP437")
                .setIgnoreLocalFileHeader(true).get().use { zip ->
                val entries = zip.entriesInPhysicalOrder
                val end = channel.size()
                while (entries.hasMoreElements()) {
                    job.ensureActive()
                    val entry = entries.nextElement()
                    if (verify && entry.isUnixSymlink) {
                        throw StorageException(StorageError.UNSUPPORTED, "Archive contains a symbolic link: ${entry.name}")
                    }
                    val stream = ChannelSlice(watched, entry.localHeaderOffset, end).buffered(128 * 1024)
                    // Stored members with data descriptors may have no sizes in their local headers.
                    decryptZipMember(stream, entry, null, password).use {
                        onMember(entry.name, entry.isDirectory, it)
                    }
                }
            }
        } catch (failure: Exception) {
            job.ensureActive()
            throw interruption(failure) ?: failure
        }
    }

    /** Reads 7z members in archive order, which keeps solid blocks sequential. */
    private suspend fun extractSevenZ(
        channel: SeekableByteChannel,
        name: String,
        password: String,
        verify: Boolean,
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
                // 7z stores Unix mode bits in the upper half of its Windows attributes.
                val special = entry.hasWindowsAttributes &&
                    (unsupportedFileMode(entry.windowsAttributes ushr 16) || entry.windowsAttributes and 0x400 != 0)
                if (verify && (entry.isAntiItem || entry.name.isNullOrEmpty() || special)) {
                    throw StorageException(StorageError.UNSUPPORTED, "Archive contains an unsupported member")
                }
                if (entry.isAntiItem) continue
                val memberName = normalizedMemberPath(entry.name ?: continue)
                if (memberName.isEmpty()) {
                    if (verify) throw StorageException(StorageError.UNSUPPORTED, "Archive contains an unnamed member")
                    continue
                }
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
        verification: ExtractionVerification?,
        names: ExtractionNames,
        onMember: suspend (String, Long) -> Unit,
    ) {
        val passwordGiven = password.isNotEmpty()
        val job = currentCoroutineContext().job
        openRar(channel, channel.size(), password.takeIf { it.isNotEmpty() }).use { rar ->
            for (header in rar.fileHeaders) {
                job.ensureActive()
                if (verification != null && (header.redirection != null ||
                    (header.hostOS == HostSystem.unix && unsupportedFileMode(header.fileAttr)))) {
                    throw StorageException(StorageError.UNSUPPORTED, "Archive contains an unsupported member")
                }
                val name = normalizedMemberPath(header.fileName.orEmpty())
                if (name.isEmpty()) {
                    if (verification != null) throw StorageException(StorageError.UNSUPPORTED, "Archive contains an unnamed member")
                    continue
                }
                val written = unpackWith(target, destination, name, header.isDirectory, verification, names) { output ->
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
        verification: ExtractionVerification?,
        names: ExtractionNames,
    ): Long = unpackWith(target, destination, rawName, directory, verification, names) { output ->
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
        verification: ExtractionVerification?,
        names: ExtractionNames,
        write: suspend (OutputStream) -> Unit,
    ): Long {
        // "." and ".." segments are dropped so a member cannot escape the destination; existing
        // links are refused in extractionChild.
        val segments = rawName.split('/', '\\').filter { it.isNotEmpty() && it != "." && it != ".." }
        if (segments.isEmpty()) {
            if (verification != null) throw StorageException(StorageError.UNSUPPORTED, "Archive contains an unnamed member")
            return 0
        }
        var parent = destination
        var path = ""
        for (segment in segments.dropLast(1)) {
            path = if (path.isEmpty()) segment else "$path/$segment"
            parent = folder(target, parent, segment, path, names)
        }
        val name = segments.last()
        val memberPath = segments.joinToString("/")
        if (directory) {
            val ref = folder(target, parent, name, memberPath, names)
            verification?.directories?.add(ref)
            return 0
        }
        validateName(name)
        if (!names.keepBoth && memberPath in names.filePaths) {
            throw StorageException(StorageError.CONFLICT, "Duplicate archive member: $rawName")
        }
        val existing = extractionChild(target, parent, name, requireExactName = !names.keepBoth)
        if (!names.keepBoth && existing?.ref == names.archive) {
            throw StorageException(StorageError.CONFLICT, "Extraction would replace the source archive")
        }
        if (!names.keepBoth && existing != null) {
            if (existing.directory || existing.name != name || existing.ref in names.files) {
                throw StorageException(StorageError.CONFLICT, "Name collision: $rawName and ${existing.name}")
            }
            target.delete(existing.ref)
        }
        val outputName = if (names.keepBoth && existing != null) extractionName(target, parent, name, directory = false) else name
        val created = target.create(parent, outputName, directory = false)
        names.files += created.ref
        names.filePaths += memberPath
        var written = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        target.openWrite(created.ref).use { output ->
            val checked = if (verification != null) DigestOutputStream(output, digest) else output
            val counting = object : OutputStream() {
                override fun write(b: Int) { checked.write(b); written++ }
                override fun write(b: ByteArray, off: Int, len: Int) { checked.write(b, off, len); written += len }
                override fun flush() = checked.flush()
            }
            write(counting)
        }
        verification?.files?.add(created.ref to digest.digest())
        return written
    }

    private class ExtractionVerification {
        val files = mutableListOf<Pair<NodeRef, ByteArray>>()
        val directories = mutableSetOf<NodeRef>()
    }

    private class ExtractionNames(val archive: NodeRef, val keepBoth: Boolean) {
        val folders = mutableMapOf<String, NodeRef>()
        val files = mutableSetOf<NodeRef>()
        val filePaths = mutableSetOf<String>()
    }

    private fun unsupportedFileMode(mode: Int): Boolean = (mode and 0xf000) !in setOf(0, 0x4000, 0x8000)

    private suspend fun verifyBytes(provider: StorageProvider, ref: NodeRef, expected: ByteArray) {
        provider.openRead(ref).use { input -> verifyBytes(input, expected) }
    }

    private suspend fun verifyBytes(input: InputStream, expected: ByteArray) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        if (!digest.digest().contentEquals(expected)) {
            throw StorageException(StorageError.IO, "Archive verification failed; source retained")
        }
    }

    private suspend fun folder(target: StorageProvider, parent: NodeRef, name: String, path: String, names: ExtractionNames): NodeRef {
        names.folders[path]?.let { ref ->
            val entry = extractionEntry(target, ref)
            if (!entry.directory) throw StorageException(StorageError.CONFLICT, "Extracted folder changed: $path")
            return ref
        }
        validateName(name)
        val existing = extractionChild(target, parent, name, requireExactName = !names.keepBoth)
        val ref = if (existing != null && !names.keepBoth) {
            if (!existing.directory || existing.name != name) {
                throw StorageException(StorageError.CONFLICT, "Name collision: $path and ${existing.name}")
            }
            existing.ref
        } else {
            val outputName = if (existing != null) extractionName(target, parent, name, directory = true) else name
            target.create(parent, outputName, directory = true).ref
        }
        names.folders[path] = ref
        return ref
    }

    private suspend fun extractionChild(provider: StorageProvider, parent: NodeRef, name: String, requireExactName: Boolean): Entry? {
        val found = provider.child(parent, name) ?: return null
        // Direct lookups can echo the requested spelling on case-insensitive storage.
        val stored = if (requireExactName) provider.list(parent, complete = true)
            .map { batch -> batch.firstOrNull { it.name == name } }.firstOrNull { it != null }
            ?: throw StorageException(StorageError.CONFLICT, "Name collision: $name") else found
        return extractionEntry(provider, stored.ref)
    }

    private suspend fun extractionEntry(provider: StorageProvider, ref: NodeRef): Entry =
        provider.stat(ref).also {
            if (it.link) throw StorageException(StorageError.UNSUPPORTED,
                "Cannot extract to a symbolic link: ${it.name}")
        }

    private suspend fun extractionName(provider: StorageProvider, parent: NodeRef, name: String, directory: Boolean): String {
        val dot = if (directory) -1 else name.lastIndexOf('.').takeIf { it > 0 } ?: -1
        val stem = if (dot > 0) name.substring(0, dot) else name
        val suffix = if (dot > 0) name.substring(dot) else ""
        var candidate = name
        var number = 1
        while (provider.child(parent, candidate) != null) {
            currentCoroutineContext().ensureActive()
            candidate = "$stem (${number++})$suffix"
        }
        return candidate
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
