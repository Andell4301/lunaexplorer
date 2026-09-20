package com.lunaexplorer.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.toList
import net.lingala.zip4j.io.outputstream.ZipOutputStream as Zip4jOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel

internal class ByteSource(
    val name: String,
    private val bytes: ByteArray,
    private val seekable: Boolean = true,
    network: Boolean = false,
    override val id: String = "source",
) : StorageProvider by MemoryStorageProvider(id), BudgetedReads {
    val ref = NodeRef(id, name)
    override val features: Set<Feature> = buildSet {
        if (seekable) add(Feature.RANGE_READ)
        if (network) add(Feature.NETWORK)
    }
    var channelOpens = 0
    var streamOpens = 0
    var openHandles = 0
    var bytesServed = 0L

    override suspend fun stat(ref: NodeRef) = Entry(ref, name, false, size = bytes.size.toLong(), capabilities = setOf(Capability.READ))

    override suspend fun openChannel(ref: NodeRef): SeekableByteChannel? = if (seekable) { channelOpens++; channel(null) } else null

    override suspend fun openChannel(ref: NodeRef, budget: ReadBudget): SeekableByteChannel? =
        if (seekable) { channelOpens++; channel(budget) } else null

    override suspend fun openRead(ref: NodeRef): InputStream { streamOpens++; return stream(null) }

    override suspend fun openRead(ref: NodeRef, budget: ReadBudget): InputStream { streamOpens++; return stream(budget) }

    private fun stream(budget: ReadBudget?): InputStream {
        val channel = channel(budget)
        return object : InputStream() {
            override fun read(): Int {
                val single = ByteArray(1)
                return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
            }
            override fun read(b: ByteArray, off: Int, len: Int) = channel.read(ByteBuffer.wrap(b, off, len))
            override fun close() = channel.close()
        }
    }

    private fun channel(budget: ReadBudget?): SeekableByteChannel {
        openHandles++
        return object : SeekableByteChannel {
            private var offset = 0
            private var open = true
            override fun read(dst: ByteBuffer): Int {
                if (!dst.hasRemaining()) return 0
                if (offset >= bytes.size) return -1
                fun fetch(requested: Int): Int {
                    val count = minOf(requested, bytes.size - offset)
                    dst.put(bytes, offset, count)
                    offset += count
                    bytesServed += count
                    return count
                }
                return budget?.read(dst.remaining(), ::fetch) ?: fetch(dst.remaining())
            }
            override fun position() = offset.toLong()
            override fun position(newPosition: Long): SeekableByteChannel = apply { offset = newPosition.toInt() }
            override fun size() = bytes.size.toLong()
            override fun isOpen() = open
            override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException()
            override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException()
            override fun close() {
                if (open) openHandles--
                open = false
            }
        }
    }
}

internal fun archiveProvider(vararg sources: StorageProvider): ArchiveProvider {
    lateinit var registry: ProviderRegistry
    val archive = ArchiveProvider({ registry })
    registry = ProviderRegistry(sources.toList() + archive)
    return archive
}

internal suspend fun ArchiveProvider.names(parent: NodeRef): List<String> = list(parent).toList().flatten().map { it.name }.sorted()

internal suspend fun ArchiveProvider.entryNamed(parent: NodeRef, name: String): Entry = list(parent).toList().flatten().single { it.name == name }

internal suspend fun ArchiveProvider.text(ref: NodeRef): String = openRead(ref).use { it.readBytes().decodeToString() }

internal fun memberRef(root: NodeRef, path: String) = NodeRef(root.provider, root.key + path)

internal fun storageFailure(block: () -> Unit): StorageException {
    val failure = runCatching(block).exceptionOrNull()
    return failure as? StorageException ?: throw AssertionError("Expected a StorageException, got $failure", failure)
}

/** Members in insertion order; a null body is a directory. */
internal typealias Members = Map<String, ByteArray?>

internal fun tarBytes(format: ArchiveFormat, members: Members): ByteArray {
    val raw = ByteArrayOutputStream()
    val sink = when (format) {
        ArchiveFormat.TAR_GZ -> GzipCompressorOutputStream(raw)
        ArchiveFormat.TAR_XZ -> XZCompressorOutputStream(raw)
        ArchiveFormat.TAR_BZ2 -> BZip2CompressorOutputStream(raw)
        else -> raw
    }
    TarArchiveOutputStream(sink).use { tar ->
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
        for ((name, body) in members) {
            val entry = TarArchiveEntry(if (body == null) "$name/" else name)
            entry.size = body?.size?.toLong() ?: 0L
            entry.setModTime(1_700_000_000_000L)
            tar.putArchiveEntry(entry)
            if (body != null) tar.write(body)
            tar.closeArchiveEntry()
        }
        tar.finish()
    }
    sink.close()
    return raw.toByteArray()
}

/** A 7z written by commons-compress: one LZMA2 folder per member, never solid. */
internal fun sevenZBytes(members: Members): ByteArray {
    val channel = SeekableInMemoryByteChannel()
    SevenZOutputFile(channel).use { out ->
        for ((name, body) in members) {
            val entry = SevenZArchiveEntry().apply {
                this.name = name
                isDirectory = body == null
                lastModifiedDate = Date(1_700_000_000_000L)
            }
            out.putArchiveEntry(entry)
            if (body != null) out.write(body)
            out.closeArchiveEntry()
        }
        out.finish()
    }
    return channel.array().copyOf(channel.size().toInt())
}

internal fun zip4jBytes(
    members: Members,
    encrypted: Set<String>,
    password: String,
    encryption: ZipEncryption,
    stored: Boolean,
): ByteArray {
    val raw = ByteArrayOutputStream()
    val zip = if (encrypted.isEmpty()) Zip4jOutputStream(raw) else Zip4jOutputStream(raw, password.toCharArray())
    for ((name, body) in members) {
        val parameters = ZipParameters().apply {
            fileNameInZip = if (body == null) "$name/" else name
            compressionMethod = if (stored) CompressionMethod.STORE else CompressionMethod.DEFLATE
            if (stored && body != null) entrySize = body.size.toLong()
            if (name in encrypted && body != null) {
                isEncryptFiles = true
                encryptionMethod = if (encryption == ZipEncryption.AES_256) EncryptionMethod.AES else EncryptionMethod.ZIP_STANDARD
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }
        zip.putNextEntry(parameters)
        // zip4j encrypts stored members in the caller's array; keep the fixture's bytes intact.
        if (body != null) zip.write(body.copyOf())
        zip.closeEntry()
    }
    zip.close()
    return raw.toByteArray()
}

internal fun jdkZipBytes(members: Members): ByteArray = ByteArrayOutputStream().also { raw ->
    ZipOutputStream(raw).use { zip ->
        for ((name, body) in members) {
            zip.putNextEntry(ZipEntry(if (body == null) "$name/" else name))
            if (body != null) zip.write(body)
            zip.closeEntry()
        }
    }
}.toByteArray()

internal fun bytesOf(text: String): ByteArray = text.toByteArray()
