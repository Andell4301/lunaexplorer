package com.lunaexplorer.core

import java.io.IOException
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedZipExtractionTest {
    @get:Rule val temporary = TemporaryFolder()

    private val password = "correct horse"
    private val firstImage = "photos/first.jpg"
    private val members: Members = linkedMapOf(
        "photos" to null,
        firstImage to ByteArray(17_000) { (it * 7 % 251).toByte() },
        "photos/nested" to null,
        "photos/nested/second.jpg" to ByteArray(197_000) { (it * 13 % 253).toByte() },
        "photos/nested/third.png" to ByteArray(29_000) { (it * 11 % 247).toByte() },
    )

    @Test fun `stored zipcrypto members with descriptor sizes extract from a seekable source`() =
        extract(ZipEncryption.ZIP_CRYPTO, seekable = true)

    @Test fun `stored zipcrypto members with descriptor sizes extract from a stream-only source`() =
        extract(ZipEncryption.ZIP_CRYPTO, seekable = false)

    @Test fun `stored aes members with descriptor sizes extract from a seekable source`() =
        extract(ZipEncryption.AES_256, seekable = true)

    @Test fun `stored aes members with descriptor sizes extract from a stream-only source`() =
        extract(ZipEncryption.AES_256, seekable = false)

    @Test fun `deflated encrypted members with descriptor sizes extract from a seekable source`() {
        for (encryption in listOf(ZipEncryption.ZIP_CRYPTO, ZipEncryption.AES_256)) {
            extract(encryption, seekable = true, stored = false)
        }
    }

    @Test fun `deflated encrypted members with descriptor sizes extract from a stream-only source`() {
        for (encryption in listOf(ZipEncryption.ZIP_CRYPTO, ZipEncryption.AES_256)) {
            extract(encryption, seekable = false, stored = false)
        }
    }

    @Test fun `a wrong password fails extraction and releases the source and staged copy`() = runBlocking {
        for (encryption in listOf(ZipEncryption.ZIP_CRYPTO, ZipEncryption.AES_256)) {
            for (seekable in listOf(true, false)) {
                val source = ByteSource("photos.zip", descriptorSizedZip(encryption), seekable = seekable)
                val destination = MemoryStorageProvider("destination")
                val staging = temporary.newFolder()
                val engine = ArchiveEngine(ProviderRegistry(listOf(source, destination)), stagingDirectory = staging)

                val failure = storageFailure {
                    runBlocking { engine.extract(source.ref, destination.root, null, "wrong password").last() }
                }

                assertEquals(StorageError.AUTH, failure.reason)
                assertEquals(0, source.openHandles)
                assertEquals(0, staging.listFiles()!!.size)
            }
        }
    }

    @Test fun `damaged encrypted data fails extraction and releases the source and staged copy`() {
        for (encryption in listOf(ZipEncryption.ZIP_CRYPTO, ZipEncryption.AES_256)) {
            for (seekable in listOf(true, false)) {
                val source = ByteSource("photos.zip", descriptorSizedZip(encryption, corrupt = true), seekable = seekable)
                val destination = MemoryStorageProvider("destination")
                val staging = temporary.newFolder()
                val engine = ArchiveEngine(ProviderRegistry(listOf(source, destination)), stagingDirectory = staging)

                assertThrows(IOException::class.java) {
                    runBlocking { engine.extract(source.ref, destination.root, null, password).last() }
                }

                assertEquals(0, source.openHandles)
                assertEquals(0, staging.listFiles()!!.size)
            }
        }
    }

    private fun extract(encryption: ZipEncryption, seekable: Boolean, stored: Boolean = true) = runBlocking {
        val source = ByteSource("photos.zip", descriptorSizedZip(encryption, stored), seekable = seekable)
        val destination = MemoryStorageProvider("destination")
        val staging = temporary.newFolder()
        val engine = ArchiveEngine(ProviderRegistry(listOf(source, destination)), stagingDirectory = staging)

        val result = engine.extract(source.ref, destination.root, null, password).last()

        assertTrue(result.complete)
        for ((path, body) in members) {
            val ref = path.split('/').fold(destination.root) { parent, name ->
                destination.childRef(parent, name) ?: throw AssertionError("Missing $path")
            }
            assertEquals(path, body == null, destination.stat(ref).directory)
            if (body != null) assertArrayEquals(path, body, destination.openRead(ref).use { it.readBytes() })
        }
        assertEquals(0, source.openHandles)
        assertEquals(0, staging.listFiles()!!.size)
    }

    private fun descriptorSizedZip(encryption: ZipEncryption, stored: Boolean = true, corrupt: Boolean = false): ByteArray {
        val bytes = zip4jBytes(members, members.filterValues { it != null }.keys, password, encryption, stored)
        ZipFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(bytes)).get().use { zip ->
            for (entry in zip.entries.asSequence()) {
                if (entry.isDirectory || entry.name == firstImage) continue
                val offset = entry.localHeaderOffset.toInt()
                require(bytes[offset + 6].toInt() and 8 != 0)
                // With bit 3 set, the descriptor and central directory supply these values.
                bytes.fill(0, offset + 14, offset + 26)
                if (corrupt && entry.name == "photos/nested/second.jpg") {
                    val middle = (entry.dataOffset + entry.compressedSize / 2).toInt()
                    bytes[middle] = (bytes[middle].toInt() xor 1).toByte()
                }
            }
        }
        return bytes
    }
}
