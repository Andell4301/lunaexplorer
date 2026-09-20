package com.lunaexplorer.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedZipBrowsingTest {
    private val secret = ByteArray(40_000) { (it * 7 % 251).toByte() }
    private val members: Members = linkedMapOf("plain.txt" to bytesOf("in the clear"), "secret/data.bin" to secret)
    private val password = "correct horse"

    private val cases = listOf(
        ZipEncryption.AES_256 to false,
        ZipEncryption.AES_256 to true,
        ZipEncryption.ZIP_CRYPTO to false,
        ZipEncryption.ZIP_CRYPTO to true,
    )

    private fun fixture(encryption: ZipEncryption, stored: Boolean) =
        zip4jBytes(members, encrypted = setOf("secret/data.bin"), password = password, encryption = encryption, stored = stored)

    /** [staged]: the source was copied locally, so budgeted reads cost no allowance. */
    private suspend fun exercise(label: String, archive: ArchiveProvider, root: NodeRef, staged: Boolean = false) {
        val plain = memberRef(root, "plain.txt")
        val data = memberRef(root, "secret/data.bin")
        assertEquals(label, listOf("plain.txt", "secret"), archive.names(root))
        assertFalse(label, archive.isEncrypted(plain))
        assertTrue(label, archive.isEncrypted(data))
        assertTrue("$label: the root holds something encrypted", archive.isEncrypted(root))
        assertTrue(label, archive.needsPassword(data))
        assertFalse(label, archive.hasPassword(data))
        assertEquals(label, "in the clear", archive.text(plain))

        val locked = storageFailure { runBlocking { archive.openRead(data) } }
        assertEquals(label, StorageError.AUTH, locked.reason)

        val wrong = storageFailure { runBlocking { archive.unlock(data, "wrong") } }
        assertEquals(label, StorageError.AUTH, wrong.reason)
        assertTrue(label, archive.needsPassword(data))

        archive.unlock(data, password)

        assertTrue(label, archive.hasPassword(data))
        assertFalse(label, archive.needsPassword(data))
        assertArrayEquals(label, secret, archive.openRead(data).use { it.readBytes() })
        val budget = ReadBudget(1_000_000)
        assertArrayEquals(label, secret, archive.openRead(data, budget).use { it.readBytes() })
        if (staged) assertEquals("$label: reading the local copy costs no allowance", 0L, budget.bytesRead)
        else assertTrue(label, budget.bytesRead > 0)
    }

    @Test fun `encrypted members are decrypted from the directory over a channel`() = runBlocking {
        for ((encryption, stored) in cases) {
            val label = "$encryption stored=$stored"
            val source = ByteSource("locked.zip", fixture(encryption, stored))
            val archive = archiveProvider(source)

            val root = archive.open(source.ref, "locked.zip")

            exercise(label, archive, root)
            assertEquals("$label: the index channel serves plain and encrypted members", 2, source.channelOpens)
            assertEquals("$label: only the kept index stays open", 1, source.openHandles)
        }
    }

    @Test fun `encrypted members are reached through a stream-only source`() = runBlocking {
        for ((encryption, stored) in cases) {
            val label = "$encryption stored=$stored"
            val source = ByteSource("locked.zip", fixture(encryption, stored), seekable = false)
            val archive = archiveProvider(source)

            val root = archive.open(source.ref, "locked.zip")

            exercise(label, archive, root, staged = true)
            assertEquals("$label: every stream was closed", 0, source.openHandles)
        }
    }

    @Test fun `a plain member after an encrypted one is read through the staged copy`() = runBlocking {
        // A forward walk cannot pass an encrypted local header, so the archive is staged locally and
        // every later read must use that copy.
        val ordered: Members = linkedMapOf("a-secret.bin" to secret, "b-plain.txt" to bytesOf("in the clear"))
        val source = ByteSource("locked.zip", zip4jBytes(ordered, encrypted = setOf("a-secret.bin"),
            password = password, encryption = ZipEncryption.AES_256, stored = false), seekable = false)
        val archive = archiveProvider(source)
        val root = archive.open(source.ref, "locked.zip")
        val plain = memberRef(root, "b-plain.txt")

        assertEquals(listOf("a-secret.bin", "b-plain.txt"), archive.names(root))
        assertEquals("in the clear", archive.text(plain))

        val budget = ReadBudget(1_000_000)
        assertEquals("in the clear", archive.openRead(plain, budget).use { it.readBytes().decodeToString() })
        assertEquals("The staged copy is local, so it costs no allowance", 0L, budget.bytesRead)
        assertEquals(0, source.openHandles)
    }

    @Test fun `a password given when opening is kept for reads`() = runBlocking {
        val source = ByteSource("locked.zip", fixture(ZipEncryption.AES_256, stored = false))
        val archive = archiveProvider(source)

        val root = archive.open(source.ref, "locked.zip", password = password)

        val data = memberRef(root, "secret/data.bin")
        assertTrue(archive.hasPassword(data))
        assertFalse(archive.needsPassword(data))
        assertArrayEquals(secret, archive.openRead(data).use { it.readBytes() })
    }

    @Test fun `unlocking at the root verifies against an encrypted member`() = runBlocking {
        val source = ByteSource("locked.zip", fixture(ZipEncryption.ZIP_CRYPTO, stored = true))
        val archive = archiveProvider(source)
        val root = archive.open(source.ref, "locked.zip")

        assertEquals(StorageError.AUTH, storageFailure { runBlocking { archive.unlock(root, "wrong") } }.reason)
        assertFalse(archive.hasPassword(root))

        archive.unlock(root, password)

        assertTrue(archive.hasPassword(root))
        assertArrayEquals(secret, archive.openRead(memberRef(root, "secret/data.bin")).use { it.readBytes() })
    }

    @Test fun `a zip with encrypted members nested inside another zip still works`() = runBlocking {
        val outer = jdkZipBytes(linkedMapOf("inner.zip" to fixture(ZipEncryption.AES_256, stored = false)))
        val source = ByteSource("outer.zip", outer)
        val archive = archiveProvider(source)
        val outerRoot = archive.open(source.ref, "outer.zip")

        val inner = archive.open(archive.entryNamed(outerRoot, "inner.zip").ref, "inner.zip")

        exercise("nested", archive, inner, staged = true)
    }
}
