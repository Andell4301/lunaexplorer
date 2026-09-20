package com.lunaexplorer.app.storage

import java.security.InvalidAlgorithmParameterException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** [personPresent] stands for the window after authentication in which the keystore lets the locked key be used. */
class MemoryVaultKeys : VaultKeys {
    val open: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    var locked: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    var personPresent = true
    var screenLock = true
    var offeredIvOnEncrypt = false

    override fun cipher(mode: Int, locked: Boolean, iv: ByteArray?): Cipher {
        val key = if (!locked) open else if (personPresent) this.locked else throw NeedsPerson()
        // Android Keystore refuses a caller-provided IV on encryption.
        if (mode == Cipher.ENCRYPT_MODE && iv != null) {
            offeredIvOnEncrypt = true
            throw InvalidAlgorithmParameterException("Caller-provided IV not permitted")
        }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            if (iv == null) init(mode, key) else init(mode, key, GCMParameterSpec(128, iv))
        }
    }

    override fun canLock() = screenLock
    override fun needsPerson(error: Throwable) = error is NeedsPerson
    override fun keyIsGone(error: Throwable) = false
    override fun discardLockedKey() { locked = SecretKeySpec(ByteArray(32), "AES") }

    class NeedsPerson : RuntimeException()
}
