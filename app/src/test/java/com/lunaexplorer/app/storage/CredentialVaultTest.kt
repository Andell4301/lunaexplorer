package com.lunaexplorer.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CredentialVaultTest {
    @get:Rule val folder = TemporaryFolder()

    private val keys = MemoryVaultKeys()
    private fun vault() = CredentialVault(File(folder.root, "vault/credentials"), keys)

    @Test fun `what is saved is read back, and only through the vault`() {
        val written = vault()
        written.save(Secrets(smbPasswords = mapOf("nas" to "hunter2")), locked = false)

        val read = vault().open(locked = false)

        assertEquals("hunter2", read.smbPasswords["nas"])
        val raw = File(folder.root, "vault/credentials").readBytes().decodeToString()
        assertFalse("The file must not carry the password in the clear", raw.contains("hunter2"))
    }

    @Test fun `the cipher chooses the IV on encryption, because the keystore allows nothing else`() {
        vault().save(Secrets(smbPasswords = mapOf("nas" to "hunter2")), locked = false)

        assertFalse("No IV may be offered to the cipher when encrypting", keys.offeredIvOnEncrypt)
        assertEquals("And the one it chose is what decrypts the file",
            "hunter2", vault().open(locked = false).smbPasswords["nas"])
    }

    @Test fun `a missing vault opens without credentials`() {
        assertEquals(Secrets(), vault().open(locked = false))
    }

    @Test fun `a truncated vault stays closed and is preserved`() {
        val file = File(folder.root, "vault/credentials").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf()) }
        val vault = vault()

        try { vault.open(locked = false); fail("Expected a truncated vault to be refused") }
        catch (expected: VaultUnreadable) { }

        assertFalse(vault.open)
        assertTrue(file.exists())
        assertEquals(0L, file.length())
    }

    @Test fun `a locked vault stays shut until the person has been asked`() {
        vault().save(Secrets(smbPasswords = mapOf("nas" to "hunter2")), locked = true)
        keys.personPresent = false
        val shut = vault()

        try { shut.open(locked = true); fail("Expected the vault to stay shut") }
        catch (expected: VaultLocked) {}
        assertFalse(shut.open)
        assertNull(shut.secrets.value)

        keys.personPresent = true
        assertEquals("hunter2", shut.open(locked = true).smbPasswords["nas"])
        assertTrue(shut.open)
    }

    @Test fun `turning the lock on is a change of key, so the old key stops working`() {
        val v = vault()
        v.save(Secrets(smbPasswords = mapOf("nas" to "hunter2")), locked = false)

        v.relock(locked = true)

        try { vault().open(locked = false); fail("The unlocked key must not open a locked vault") }
        catch (expected: VaultUnreadable) {}
        assertEquals("hunter2", vault().open(locked = true).smbPasswords["nas"])
    }

    @Test fun `closing forgets what was read, and opening reads it again`() {
        val v = vault()
        v.save(Secrets(smbPasswords = mapOf("nas" to "hunter2")), locked = false)
        v.close()

        assertNull(v.secrets.value)
        assertEquals("hunter2", v.open(locked = false).smbPasswords["nas"])
    }

    @Test fun `a file this build did not write is refused rather than misread`() {
        val file = File(folder.root, "vault/credentials").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(9, 1, 2, 3)) }

        try { vault().open(locked = false); fail("Expected refusal") }
        catch (expected: VaultUnreadable) { assertTrue(file.exists()) }
    }
}
