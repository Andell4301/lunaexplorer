package com.lunaexplorer.core

import com.github.junrar.Archive as RarArchive
import com.github.junrar.ArchiveOptions as RarOptions
import com.github.junrar.exception.CorruptHeaderException
import com.github.junrar.exception.CrcErrorException
import com.github.junrar.exception.InitDeciphererFailedException
import com.github.junrar.exception.MissingNextVolumeException
import com.github.junrar.exception.MissingPreviousVolumeException
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedDictionarySizeException
import com.github.junrar.exception.UnsupportedRarMethodException
import com.github.junrar.exception.UnsupportedRarVersionException
import com.github.junrar.exception.WrongPasswordException
import com.github.junrar.io.SeekableReadOnlyByteChannel
import com.github.junrar.rarfile.FileHeader as RarHeader
import com.github.junrar.volume.Volume
import com.github.junrar.volume.VolumeManager
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.IdentityHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import net.lingala.zip4j.exception.ZipException as Zip4jException
import net.lingala.zip4j.io.inputstream.ZipInputStream as Zip4jInputStream
import net.lingala.zip4j.model.FileHeader as Zip4jHeader
import org.apache.commons.compress.MemoryLimitException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.fitDictionariesToContent
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.tukaani.xz.CorruptedInputException

internal data class ArchiveMember(
    val path: String,
    val directory: Boolean,
    val size: Long?,
    val modified: Long?,
    /** Per-member flag for zip and rar; for 7z, the whole archive's. */
    val encrypted: Boolean = false,
)

/**
 * Forward slashes, with empty, "." and ".." segments dropped; `tar -cf x.tar .` stores
 * "./docs/readme.txt". Extraction resolves member paths the same way.
 */
internal fun normalizedMemberPath(name: String): String = name.replace('\\', '/')
    .split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")

internal class MemberIndex {
    private val members = LinkedHashMap<String, ArchiveMember>()

    /** Returns false for an empty path. A duplicate path replaces the earlier member. */
    fun add(member: ArchiveMember): Boolean {
        if (member.path.isEmpty()) return false
        members[member.path] = member
        var parent = member.path.substringBeforeLast('/', "")
        while (parent.isNotEmpty() && members[parent] == null) {
            members[parent] = ArchiveMember(parent, directory = true, size = null, modified = null)
            parent = parent.substringBeforeLast('/', "")
        }
        return true
    }

    fun toList(): List<ArchiveMember> = members.values.sortedBy { it.path }
}

internal class ReaderBusy : Exception()

/**
 * An archive index kept open with its source handle. Member streams are counted so [retire]
 * closes the source only after the last one has finished.
 */
internal abstract class KeptReader {
    private var reading = 0
    private var retired = false

    /** Whether the library serves one member stream at a time. */
    protected open val exclusive: Boolean get() = false

    val usable: Boolean @Synchronized get() = !retired

    /**
     * Returns null if [path] is absent. Throws [ReaderBusy] when an exclusive reader already has a
     * stream open. [caller] lets a reader that must decode earlier members stop when that job is
     * cancelled.
     */
    @Synchronized fun open(path: String, password: String?, caller: Job? = null): InputStream? {
        if (retired) throw IOException("This archive was closed")
        if (exclusive && reading > 0) throw ReaderBusy()
        reading++
        var handed = false
        try {
            val stream = stream(path, password, caller, ::release) ?: return null
            handed = true
            return stream
        } finally {
            if (!handed) reading--
        }
    }

    /** The returned stream must call [release] exactly once, when it is done with the source. */
    protected abstract fun stream(path: String, password: String?, caller: Job?, release: () -> Unit): InputStream?

    protected abstract fun closeSource()

    @Synchronized private fun release() {
        reading--
        if (retired && reading == 0) runCatching { closeSource() }
    }

    @Synchronized fun retire() {
        retired = true
        if (reading == 0) runCatching { closeSource() }
    }
}

internal class Counted(private val stream: InputStream, private val release: () -> Unit) : InputStream() {
    private var closed = false
    override fun read(): Int = stream.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
    override fun available(): Int = stream.available()
    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        runCatching { stream.close() }
        release()
    }
}

/** Serializes reads of streams that share one seekable channel, since each read seeks first. */
internal class Locked(private val stream: InputStream, private val lock: Any) : InputStream() {
    override fun read(): Int = synchronized(lock) { stream.read() }
    override fun read(b: ByteArray, off: Int, len: Int): Int = synchronized(lock) { stream.read(b, off, len) }
    override fun available(): Int = stream.available()
    override fun close() = stream.close()
}

internal class Mapped(private val stream: InputStream, private val map: (Throwable) -> Throwable) : InputStream() {
    override fun read(): Int = try { stream.read() } catch (failure: Throwable) { throw map(failure) }
    override fun read(b: ByteArray, off: Int, len: Int): Int =
        try { stream.read(b, off, len) } catch (failure: Throwable) { throw map(failure) }
    override fun available(): Int = stream.available()
    override fun close() = stream.close()
}

internal class ChannelSlice(
    private val channel: SeekableByteChannel,
    private var position: Long,
    private val end: Long,
) : InputStream() {
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == 1) one[0].toInt() and 0xff else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val want = minOf(len.toLong(), end - position).toInt()
        if (want <= 0) return -1
        val got = synchronized(channel) {
            channel.position(position)
            channel.read(ByteBuffer.wrap(b, off, want))
        }
        if (got > 0) position += got
        return got
    }

    override fun close() {}
}

/** Ignores close(); the owner closes the underlying channel. */
internal class NonClosingChannel(private val inner: SeekableByteChannel) : SeekableByteChannel by inner {
    override fun close() {}
}

internal interface CancellableSource {
    /** Stops reads once [job] is cancelled; null detaches the previous one. */
    fun readFor(job: Job?)
}

/** Fails reads once [owner], or the job attached through [readFor], is cancelled. */
internal class JobChannel(
    private val inner: SeekableByteChannel,
    private val owner: Job? = null,
) : SeekableByteChannel, CancellableSource {
    @Volatile private var reader: Job? = null

    override fun readFor(job: Job?) { reader = job }

    override fun read(dst: ByteBuffer): Int {
        // Cancelled, not merely finished: a stream is read after the coroutine that opened it has returned.
        if (owner?.isCancelled == true || reader?.isCancelled == true) {
            throw InterruptedIOException("Reading the archive was given up")
        }
        return inner.read(dst)
    }

    override fun write(src: ByteBuffer): Int = inner.write(src)
    override fun position(): Long = inner.position()
    override fun position(newPosition: Long): SeekableByteChannel = apply { inner.position(newPosition) }
    override fun size(): Long = inner.size()
    override fun truncate(size: Long): SeekableByteChannel = apply { inner.truncate(size) }
    override fun isOpen(): Boolean = inner.isOpen
    override fun close() = inner.close()
}

/**
 * The cancellation, read-limit or [StorageException] in [failure]'s cause chain, if any. These must
 * reach the caller unchanged even when a library wrapped them.
 */
internal fun interruption(failure: Throwable): Throwable? {
    var cursor: Throwable? = failure
    var depth = 0
    while (cursor != null && depth++ < 8) {
        if (cursor is InterruptedIOException || cursor is CancellationException || cursor is StorageException) return cursor
        cursor = cursor.cause
    }
    return null
}

/** Set for both ZipCrypto and WinZip AES. */
internal fun ZipArchiveEntry.isEncryptedMember(): Boolean = generalPurposeBit.usesEncryption()

/**
 * Decrypts one zip member with zip4j. With [entry], [source] must start at the member's local file
 * header; the entry supplies sizes and CRC so no data descriptor is needed. Without it, [source] is
 * walked from its start until [expected] is found, decoding every member before it. Closing the
 * result closes [source].
 */
internal fun decryptZipMember(source: InputStream, entry: ZipArchiveEntry?, expected: String?, password: String): InputStream {
    val zip = Zip4jInputStream(source, password.toCharArray())
    try {
        if (entry != null) {
            val header = Zip4jHeader().apply {
                crc = entry.crc
                compressedSize = entry.compressedSize
                uncompressedSize = entry.size
                isDirectory = entry.isDirectory
            }
            zip.getNextEntry(header, false) ?: throw IOException("The member's local header could not be read")
        } else {
            while (true) {
                val local = zip.getNextEntry()
                    ?: throw StorageException(StorageError.NOT_FOUND, "That item is not in this archive")
                if (normalizedMemberPath(local.fileName) == expected) break
            }
        }
    } catch (failure: Throwable) {
        runCatching { zip.close() }
        runCatching { source.close() }
        throw zipFailure(failure)
    }
    val member = object : InputStream() {
        override fun read(): Int = zip.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = zip.read(b, off, len)
        override fun close() {
            runCatching { zip.close() }
            source.close()
        }
    }
    return Mapped(member, ::zipFailure)
}

internal fun zipFailure(failure: Throwable): Throwable {
    interruption(failure)?.let { return it }
    if (failure !is Zip4jException) return failure
    return when (failure.type) {
        Zip4jException.Type.WRONG_PASSWORD -> StorageException(StorageError.AUTH, "Wrong password", failure)
        Zip4jException.Type.UNSUPPORTED_ENCRYPTION, Zip4jException.Type.UNKNOWN_COMPRESSION_METHOD ->
            StorageException(StorageError.UNSUPPORTED, failure.message ?: "This item cannot be read", failure)
        else -> failure
    }
}

internal fun tarStream(input: InputStream, format: ArchiveFormat): InputStream = when (format) {
    ArchiveFormat.TAR_GZ -> GzipCompressorInputStream.builder().setInputStream(input).setDecompressConcatenated(true).get()
    ArchiveFormat.TAR_XZ -> XZCompressorInputStream.builder().setInputStream(input).setDecompressConcatenated(true).get()
    ArchiveFormat.TAR_BZ2 -> BZip2CompressorInputStream(input, true)
    else -> input
}

/** Null for links, FIFOs and device nodes, which carry no data. */
private fun TarArchiveEntry.member(): ArchiveMember? {
    val path = normalizedMemberPath(name)
    if (path.isEmpty() || isSymbolicLink || isLink || isFIFO || isCharacterDevice || isBlockDevice) return null
    return ArchiveMember(path, isDirectory, if (isDirectory) null else realSize,
        modified = lastModifiedDate?.time?.takeIf { it > 0 })
}

internal fun indexTar(tar: TarFile): Pair<List<ArchiveMember>, Map<String, TarArchiveEntry>> {
    val index = MemberIndex()
    val entries = HashMap<String, TarArchiveEntry>()
    for (entry in tar.entries) {
        val member = entry.member() ?: continue
        if (index.add(member)) entries[member.path] = entry
    }
    return index.toList() to entries
}

internal fun walkTar(input: InputStream, format: ArchiveFormat, check: () -> Unit): List<ArchiveMember> {
    val index = MemberIndex()
    TarArchiveInputStream(tarStream(input, format)).use { tar ->
        while (true) {
            check()
            val record = tar.nextEntry ?: break
            record.member()?.let { index.add(it) }
        }
    }
    return index.toList()
}

/** Opens [path] by walking [input] from its start. Closing the result closes [input]. */
internal fun tarMemberStream(input: InputStream, format: ArchiveFormat, path: String, check: () -> Unit): InputStream {
    val tar = TarArchiveInputStream(tarStream(input, format))
    try {
        while (true) {
            check()
            val record = tar.nextEntry
                ?: throw StorageException(StorageError.NOT_FOUND, "That item is not in this archive")
            if (normalizedMemberPath(record.name) != path) continue
            return object : InputStream() {
                override fun read(): Int = tar.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = tar.read(b, off, len)
                override fun close() = tar.close()
            }
        }
    } catch (failure: Throwable) {
        runCatching { tar.close() }
        throw failure
    }
}

/** Member reads seek on the shared [channel], so they are serialized on it. */
internal class KeptTar(
    private val tar: TarFile,
    private val channel: SeekableByteChannel,
    private val entries: Map<String, TarArchiveEntry>,
) : KeptReader() {
    override fun stream(path: String, password: String?, caller: Job?, release: () -> Unit): InputStream? {
        val entry = entries[path] ?: return null
        val stream = synchronized(channel) { tar.getInputStream(entry) }
        return Counted(Locked(stream, channel), release)
    }

    override fun closeSource() = tar.close()
}

/**
 * Memory limit for 7z decoders: half the max heap, and never less than a 64 MiB dictionary needs.
 * That is 7-Zip's Ultra preset, and its decoder asks for the dictionary plus about 100 KiB.
 */
private fun sevenZMemoryLimitKiB(): Int =
    (Runtime.getRuntime().maxMemory() / 2 / 1024).coerceIn(72L * 1024, Int.MAX_VALUE.toLong()).toInt()

/** Header-encrypted archives need [password] here. */
internal fun openSevenZ(
    channel: SeekableByteChannel, name: String, password: String?, memoryLimitKiB: Int = sevenZMemoryLimitKiB(),
): SevenZFile = try {
    // SevenZFile reads the signature from wherever the channel is, and a reopened channel is mid-archive.
    channel.position(0)
    SevenZFile.builder()
        .setSeekableByteChannel(channel)
        .setDefaultName(name)
        .setUseDefaultNameForUnnamedEntries(true)
        .setMaxMemoryLimitKiB(memoryLimitKiB)
        .apply { if (password != null) setPassword(password) }
        .get()
        .also(::fitDictionariesToContent)
} catch (failure: Throwable) {
    throw sevenZFailure(failure, encrypted = true, passwordGiven = password != null)
}

/**
 * Maps commons-compress 7z failures. A wrong password has no dedicated signal: AES decrypts to
 * garbage that the decoder or checksum rejects, so those failures count as a wrong password only
 * when the data is known to be encrypted and a password was given.
 */
internal fun sevenZFailure(failure: Throwable, encrypted: Boolean, passwordGiven: Boolean): Throwable {
    interruption(failure)?.let { return it }
    val message = failure.message.orEmpty()
    return when {
        failure is PasswordRequiredException ->
            StorageException(StorageError.AUTH, "This archive needs a password", failure)
        encrypted && passwordGiven && (failure is CorruptedInputException ||
            message.startsWith("Checksum verification failed") || message.contains("premature end") ||
            message.contains("no Header") || message.contains("not in the BZip2 format")) ->
            StorageException(StorageError.AUTH, "Wrong password", failure)
        failure is MemoryLimitException || failure is OutOfMemoryError ->
            StorageException(StorageError.UNSUPPORTED, "This 7z archive needs more memory than is available", failure)
        message.startsWith("Unsupported compression method") || message.startsWith("Multi input/output stream coders") ->
            StorageException(StorageError.UNSUPPORTED, message, failure)
        // The library's own state errors carry messages that mean nothing to a user, such as "origin".
        failure is RuntimeException -> StorageException(StorageError.IO, "This 7z archive could not be read", failure)
        else -> failure
    }
}

internal fun sevenZMembers(file: SevenZFile): Pair<List<ArchiveMember>, Map<String, SevenZArchiveEntry>> {
    val index = MemberIndex()
    val entries = HashMap<String, SevenZArchiveEntry>()
    for (entry in file.entries) {
        if (entry.isAntiItem) continue
        val path = normalizedMemberPath(entry.name ?: continue)
        val member = ArchiveMember(path, entry.isDirectory, if (entry.isDirectory) null else entry.size,
            modified = if (entry.hasLastModifiedDate) entry.lastModifiedDate.time.takeIf { it > 0 } else null)
        if (index.add(member)) entries[path] = entry
    }
    return index.toList() to entries
}

/**
 * The first member with data in archive order, used to detect encryption. Opening a later member
 * of a solid block would decompress everything before it.
 */
internal fun sevenZSample(file: SevenZFile): SevenZArchiveEntry? =
    file.entries.firstOrNull { !it.isAntiItem && !it.isDirectory && it.hasStream() && it.size > 0 }

/**
 * Whether the content is encrypted, judged from [sevenZSample]: building its decoder reveals the
 * AES coder without reading data. With a password, one byte is decoded so a wrong one fails here.
 * A sample the library cannot decode counts as not encrypted; its own read reports the failure.
 */
/** [unusable] runs when the sample could not be opened, which leaves [file] unfit for another open. */
internal fun sevenZEncrypted(file: SevenZFile, password: String?, unusable: () -> Unit = {}): Boolean {
    val sample = sevenZSample(file) ?: return false
    val stream = try {
        file.getInputStream(sample)
    } catch (failure: Throwable) {
        unusable()
        if (failure is PasswordRequiredException) return true
        val mapped = sevenZFailure(failure, encrypted = true, passwordGiven = password != null)
        if ((mapped as? StorageException)?.reason == StorageError.UNSUPPORTED) return false
        throw mapped
    }
    val encrypted = sample.contentMethods?.any { it.method == SevenZMethod.AES256SHA256 } == true
    if (!encrypted) return false
    if (password == null) return true
    try {
        stream.read()
    } catch (failure: Throwable) {
        throw sevenZFailure(failure, encrypted = true, passwordGiven = true)
    }
    return true
}

/**
 * Random-access stream for [entry]; in a solid block this first decodes the members before it.
 * [failed] runs when a read of the returned stream throws.
 */
internal fun sevenZMemberStream(
    file: SevenZFile,
    entry: SevenZArchiveEntry,
    encrypted: Boolean,
    passwordGiven: Boolean,
    failed: () -> Unit = {},
    release: () -> Unit,
): InputStream {
    val stream = try {
        file.getInputStream(entry)
    } catch (failure: Throwable) {
        throw sevenZFailure(failure, encrypted, passwordGiven)
    }
    return Counted(Mapped(stream) { failed(); sevenZFailure(it, encrypted, passwordGiven) }, release)
}

/**
 * SevenZFile cannot be used again after an open or a read of a member failed, a cancelled one
 * included: it records the block it is in before that block's decoder exists, so the next open in
 * the same block reads from a null stream, and a read cut short leaves the block's decoder at an
 * unknown offset. Any failure therefore reopens the archive over the same channel first.
 */
internal class KeptSevenZ(
    private var file: SevenZFile,
    entries: Map<String, SevenZArchiveEntry>,
    private val encrypted: Boolean,
    private val passwordGiven: Boolean,
    private val source: CancellableSource? = null,
    /** Opens another view of the same source; the shared channel outlives each view. */
    private val reopen: () -> SevenZFile,
    private val closeChannel: () -> Unit,
    /** Whether an open on [file] has already failed, as the index-time encryption probe's can. */
    @Volatile private var stale: Boolean = false,
) : KeptReader() {
    override val exclusive: Boolean get() = true

    /** Member positions in the archive's entry list; a reopened archive repeats the same order. */
    private val positions: Map<String, Int> = IdentityHashMap<SevenZArchiveEntry, Int>().let { byEntry ->
        file.entries.forEachIndexed { position, entry -> byEntry[entry] = position }
        entries.mapNotNull { (path, entry) -> byEntry[entry]?.let { path to it } }.toMap()
    }

    override fun stream(path: String, password: String?, caller: Job?, release: () -> Unit): InputStream? {
        val position = positions[path] ?: return null
        // Building the stream decodes every earlier member of a solid block; tie those reads to the caller.
        source?.readFor(caller)
        try {
            if (stale) restart()
            val entry = file.entries.elementAtOrNull(position) ?: return null
            stale = true
            val stream = sevenZMemberStream(file, entry, encrypted, passwordGiven, failed = { stale = true }, release = release)
            stale = false
            return stream
        } finally {
            source?.readFor(null)
        }
    }

    private fun restart() {
        val fresh = reopen()
        runCatching { file.close() }
        file = fresh
        stale = false
    }

    override fun closeSource() {
        runCatching { file.close() }
        closeChannel()
    }
}

private class ChannelVolume(
    private val archive: RarArchive,
    private val channel: SeekableByteChannel,
    private val length: Long,
) : Volume {
    override fun getChannel(): SeekableReadOnlyByteChannel = object : SeekableReadOnlyByteChannel {
        override fun getPosition(): Long = channel.position()
        override fun setPosition(pos: Long) { channel.position(pos) }
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xff else -1
        }
        override fun read(buffer: ByteArray, off: Int, count: Int): Int =
            if (count == 0) 0 else channel.read(ByteBuffer.wrap(buffer, off, count))
        override fun readFully(buffer: ByteArray, count: Int): Int {
            var filled = 0
            while (filled < count) {
                val got = read(buffer, filled, count - filled)
                if (got <= 0) break
                filled += got
            }
            return filled
        }
        override fun close() = channel.close()
    }

    override fun getLength(): Long = length
    override fun getArchive(): RarArchive = archive
}

/** Multi-volume sets are not followed: a continuation is reported as missing. */
private class SingleVolume(private val channel: SeekableByteChannel, private val length: Long) : VolumeManager {
    override fun nextVolume(archive: RarArchive, lastVolume: Volume?): Volume? =
        if (lastVolume == null) ChannelVolume(archive, channel, length) else null
}

/**
 * junrar refuses, before allocating, an archive whose declared dictionary exceeds this: a quarter
 * of the max heap, within 64..512 MiB. RAR5 defaults to 32 MB; large archives commonly use 64-128 MB.
 */
private fun rarDictionaryLimit(): Long =
    (Runtime.getRuntime().maxMemory() / 4).coerceIn(64L * 1024 * 1024, 512L * 1024 * 1024)

/**
 * Encrypted headers need [password] here: RAR5 refuses to open without it, while RAR3 opens and
 * lists nothing; both fail with [StorageError.AUTH]. [maxDictionary] bounds the window junrar
 * allocates for a member.
 */
internal fun openRar(
    channel: SeekableByteChannel,
    length: Long,
    password: String?,
    maxDictionary: Long = rarDictionaryLimit(),
): RarArchive {
    val options = RarOptions.builder().apply {
        maxDictionarySize(maxDictionary)
        if (password != null) password(password)
    }.build()
    val archive = try {
        RarArchive(SingleVolume(channel, length), options)
    } catch (failure: Throwable) {
        throw rarFailure(failure, encrypted = true, passwordGiven = password != null)
    }
    val lockedHeaders = runCatching { archive.isEncrypted }.getOrDefault(false)
    if (lockedHeaders && archive.fileHeaders.isEmpty()) {
        runCatching { archive.close() }
        throw StorageException(StorageError.AUTH, if (password == null) "This archive needs a password" else "Wrong password")
    }
    return archive
}

/**
 * Maps junrar failures. RAR3 data has no password check, so on an encrypted member a wrong
 * password shows up as a CRC failure, a corrupt header or an out-of-range dictionary size.
 */
internal fun rarFailure(failure: Throwable, encrypted: Boolean, passwordGiven: Boolean): Throwable {
    interruption(failure)?.let { return it }
    return when (failure) {
        is WrongPasswordException -> StorageException(StorageError.AUTH,
            if (!passwordGiven || failure.message?.contains("Missing password") == true) "This archive needs a password"
            else "Wrong password", failure)
        is InitDeciphererFailedException -> StorageException(StorageError.AUTH,
            if (passwordGiven) "Wrong password" else "This archive needs a password", failure)
        is CrcErrorException ->
            if (encrypted && passwordGiven) StorageException(StorageError.AUTH, "Wrong password", failure)
            else StorageException(StorageError.IO, "The archive's data failed its checksum", failure)
        is CorruptHeaderException ->
            if (encrypted && passwordGiven) StorageException(StorageError.AUTH, "Wrong password", failure)
            else StorageException(StorageError.IO, failure.message ?: "The RAR archive is damaged", failure)
        is UnsupportedRarVersionException ->
            StorageException(StorageError.UNSUPPORTED, "This RAR version is not supported", failure)
        is UnsupportedRarMethodException ->
            StorageException(StorageError.UNSUPPORTED, failure.message ?: "This RAR compression method is not supported", failure)
        is UnsupportedDictionarySizeException ->
            if (encrypted && passwordGiven) StorageException(StorageError.AUTH, "Wrong password", failure)
            else StorageException(StorageError.UNSUPPORTED,
                failure.message ?: "This RAR archive needs more memory than is available", failure)
        is MissingNextVolumeException, is MissingPreviousVolumeException ->
            StorageException(StorageError.UNSUPPORTED, "Multi-volume RAR archives are not supported", failure)
        is RarException -> (failure.cause as? IOException)
            ?: StorageException(StorageError.IO, failure.message ?: "The RAR archive could not be read", failure)
        else -> failure
    }
}

internal fun rarMembers(archive: RarArchive): Pair<List<ArchiveMember>, Map<String, RarHeader>> {
    val index = MemberIndex()
    val headers = HashMap<String, RarHeader>()
    for (header in archive.fileHeaders) {
        val path = normalizedMemberPath(header.fileName ?: continue)
        val member = ArchiveMember(path, header.isDirectory, if (header.isDirectory) null else header.fullUnpackSize,
            modified = header.lastModifiedTime?.toMillis()?.takeIf { it > 0 },
            encrypted = header.isEncrypted)
        if (index.add(member)) headers[path] = header
    }
    return index.toList() to headers
}

/**
 * Streams one member through a [Pipe] that junrar fills on a worker thread; in a solid archive the
 * worker first decodes the members before it. [done] runs when the worker has finished, with the
 * failure that stopped it, if any; only then is [archive] free for another member.
 */
internal fun rarMemberStream(
    archive: RarArchive,
    header: RarHeader,
    encrypted: Boolean,
    passwordGiven: Boolean,
    done: (Throwable?) -> Unit,
): InputStream {
    val pipe = Pipe()
    val worker = Thread({
        var failure: Throwable? = null
        try {
            archive.extractFile(header, pipe.output)
        } catch (thrown: Throwable) {
            failure = rarFailure(thrown, encrypted, passwordGiven)
        } finally {
            // Release the reader before the consumer can see the end of the stream, or its next open
            // finds the reader still busy and opens a second source handle.
            try { done(failure) } finally { pipe.finish(failure) }
        }
    }, "luna-rar-member")
    worker.isDaemon = true
    worker.start()
    return pipe.input
}

/**
 * junrar extracts one member at a time per archive. In a solid archive it carries the decoder
 * window over from the last member served: it resets the window only when the requested member
 * sits strictly before the last one it finished, and it does not record a member whose extraction
 * threw. Serving the same member again, or any member after a failed or abandoned read, would
 * decode against the wrong window and fail the CRC (reported as a wrong password for an encrypted
 * member), so those cases reopen the archive over the same channel first.
 */
internal class KeptRar(
    private var archive: RarArchive,
    headers: Map<String, RarHeader>,
    private val passwordGiven: Boolean,
    /** Opens another view of the same source; the shared channel outlives each archive. */
    private val reopen: () -> RarArchive,
    private val closeChannel: () -> Unit,
) : KeptReader() {
    override val exclusive: Boolean get() = true

    /** Member positions in the header list; a reopened archive repeats the same order. */
    private val positions: Map<String, Int> =
        headers.mapValues { archive.fileHeaders.indexOf(it.value) }.filterValues { it >= 0 }

    /** Only a solid archive carries decoder state between members; junrar tests both flags. */
    private val solid: Boolean = runCatching {
        archive.mainHeader?.isSolid == true || archive.fileHeaders.any { it.isSolid }
    }.getOrDefault(true)

    /** Position of the member junrar decoded last; [stale] when that decode failed or is unfinished. */
    private var served = -1
    private var stale = false

    override fun stream(path: String, password: String?, caller: Job?, release: () -> Unit): InputStream? {
        val position = positions[path] ?: return null
        if (solid && (stale || position <= served)) restart()
        val header = archive.fileHeaders.getOrNull(position) ?: return null
        served = position
        // Stale until the worker reports a clean finish.
        stale = true
        return rarMemberStream(archive, header, header.isEncrypted, passwordGiven) { failure ->
            finished(failure)
            release()
        }
    }

    @Synchronized private fun finished(failure: Throwable?) { stale = failure != null }

    private fun restart() {
        val fresh = reopen()
        runCatching { archive.close() }
        archive = fresh
        served = -1
        stale = false
    }

    override fun closeSource() {
        runCatching { archive.close() }
        closeChannel()
    }
}

/**
 * Bounded in-memory pipe between a producing thread and one reader. Data written before a
 * failure is delivered before the failure is raised; closing the reader makes the next write fail.
 */
internal class Pipe(capacity: Int = 64 * 1024) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val buffer = ByteArray(capacity)
    private var head = 0
    private var count = 0
    private var finished = false
    private var failure: Throwable? = null
    private var readerClosed = false

    private fun await() {
        try {
            changed.await()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted while piping archive data")
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var remaining = len
            while (remaining > 0) {
                lock.withLock {
                    while (count == buffer.size && !readerClosed) await()
                    if (readerClosed) throw IOException("The member stream was closed")
                    val tail = (head + count) % buffer.size
                    val chunk = minOf(remaining, buffer.size - count, buffer.size - tail)
                    System.arraycopy(b, offset, buffer, tail, chunk)
                    count += chunk
                    offset += chunk
                    remaining -= chunk
                    changed.signalAll()
                }
            }
        }
    }

    fun finish(error: Throwable?) = lock.withLock {
        finished = true
        failure = error
        changed.signalAll()
    }

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xff else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            lock.withLock {
                if (readerClosed) throw IOException("Stream closed")
                while (count == 0 && !finished) await()
                if (count == 0) {
                    failure?.let { throw it as? IOException ?: IOException(it.message ?: "The member could not be read", it) }
                    return -1
                }
                val chunk = minOf(len, count, buffer.size - head)
                System.arraycopy(buffer, head, b, off, chunk)
                head = (head + chunk) % buffer.size
                count -= chunk
                changed.signalAll()
                return chunk
            }
        }

        override fun available(): Int = lock.withLock { count }

        override fun close() = lock.withLock {
            if (readerClosed) return@withLock
            readerClosed = true
            changed.signalAll()
            // The writer releases its archive before it finishes the pipe. Returning sooner lets the
            // consumer's next open find that archive still busy. A writer that is not at a write, such
            // as one still decoding the members before this one, does not answer: hence the bound.
            var left = CLOSE_WAIT_NANOS
            try {
                while (!finished && left > 0) left = changed.awaitNanos(left)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private companion object {
        const val CLOSE_WAIT_NANOS = 500_000_000L
    }
}
