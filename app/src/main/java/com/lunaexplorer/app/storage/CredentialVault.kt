package com.lunaexplorer.app.storage

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.lunaexplorer.app.storage.transfer.TransferCredentials
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class Secrets(
    val smbPasswords: Map<String, String> = emptyMap(),
    val b2Keys: Map<String, String> = emptyMap(),
    val transferCredentials: Map<String, TransferCredentials> = emptyMap(),
)

class VaultLocked : Exception("The credential vault is locked")

class VaultUnreadable(message: String, cause: Throwable? = null) : Exception(message, cause)

interface VaultKeys {
    /** [iv] is null when encrypting: the cipher generates one, read it from the returned cipher. */
    fun cipher(mode: Int, locked: Boolean, iv: ByteArray?): Cipher
    fun canLock(): Boolean
    fun needsPerson(error: Throwable): Boolean
    fun keyIsGone(error: Throwable): Boolean
    fun discardLockedKey()
}

// The locked flag selects the authentication-bound key.
class CredentialVault(private val file: File, private val keys: VaultKeys) {
    private val _secrets = MutableStateFlow<Secrets?>(null)
    val secrets: StateFlow<Secrets?> = _secrets
    val open: Boolean get() = _secrets.value != null

    fun canLock(): Boolean = keys.canLock()

    /** The authentication-bound key answers only for a while after the user authenticates, whether or not the vault is open. */
    fun keyAnswers(locked: Boolean): Boolean = try { keys.cipher(Cipher.ENCRYPT_MODE, locked, null); true } catch (_: Exception) { false }

    /** Throws [VaultLocked] if authentication is needed, [VaultUnreadable] if decryption fails. */
    fun open(locked: Boolean): Secrets {
        _secrets.value?.let { return it }
        val read = if (!file.exists()) Secrets() else decode(file.readBytes(), locked)
        _secrets.value = read
        return read
    }

    fun close() { _secrets.value = null }

    fun save(secrets: Secrets, locked: Boolean) {
        file.parentFile?.mkdirs()
        val bytes = encode(secrets, locked)
        val staging = File(file.parentFile, file.name + ".writing")
        staging.writeBytes(bytes)
        if (!staging.renameTo(file)) { file.writeBytes(bytes); staging.delete() }
        _secrets.value = secrets
    }

    fun relock(locked: Boolean) {
        val current = _secrets.value ?: throw VaultLocked()
        save(current, locked)
    }

    fun discard() {
        file.delete()
        keys.discardLockedKey()
        _secrets.value = null
    }

    private fun encode(secrets: Secrets, locked: Boolean): ByteArray {
        val cipher = ready(Cipher.ENCRYPT_MODE, locked, null)
        val body = cipher.doFinal(json.encodeToString(Secrets.serializer(), secrets).toByteArray())
        val iv = cipher.iv
        if (iv.size != IV_BYTES) throw VaultUnreadable("The cipher chose an IV of ${iv.size} bytes, not $IV_BYTES")
        return byteArrayOf(VERSION) + iv + body
    }

    private fun decode(bytes: ByteArray, locked: Boolean): Secrets {
        if (bytes.size < 1 + IV_BYTES + 1 || bytes[0] != VERSION) throw VaultUnreadable("The vault file is not one this build wrote")
        val iv = bytes.copyOfRange(1, 1 + IV_BYTES)
        val body = bytes.copyOfRange(1 + IV_BYTES, bytes.size)
        val cipher = ready(Cipher.DECRYPT_MODE, locked, iv)
        return try {
            json.decodeFromString(Secrets.serializer(), cipher.doFinal(body).decodeToString())
        } catch (error: Exception) {
            throw VaultUnreadable("The vault file could not be read: ${error.message}", error)
        }
    }

    private fun ready(mode: Int, locked: Boolean, iv: ByteArray?): Cipher = try {
        keys.cipher(mode, locked, iv)
    } catch (error: Exception) {
        if (keys.needsPerson(error)) throw VaultLocked()
        if (keys.keyIsGone(error)) throw VaultUnreadable("The key to the vault is gone: the device's biometrics changed", error)
        throw VaultUnreadable(error.message ?: "The vault could not be opened", error)
    }

    private companion object {
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }
}

/** The authentication-bound key works for AUTH_VALID_SECONDS after device authentication; the caller prompts. */
class KeystoreVaultKeys(private val context: Context) : VaultKeys {
    override fun cipher(mode: Int, locked: Boolean, iv: ByteArray?): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            if (iv == null) init(mode, key(locked)) else init(mode, key(locked), GCMParameterSpec(TAG_BITS, iv))
        }

    private fun key(locked: Boolean): SecretKey {
        val alias = if (locked) LOCKED_ALIAS else OPEN_ALIAS
        (keystore().getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (locked) {
                    setUserAuthenticationRequired(true)
                    if (Build.VERSION.SDK_INT >= 30) {
                        setUserAuthenticationParameters(AUTH_VALID_SECONDS,
                            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    } else {
                        @Suppress("DEPRECATION")
                        setUserAuthenticationValidityDurationSeconds(AUTH_VALID_SECONDS)
                    }
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }.generateKey()
    }

    override fun canLock(): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    override fun needsPerson(error: Throwable): Boolean = error is UserNotAuthenticatedException

    override fun keyIsGone(error: Throwable): Boolean = error is KeyPermanentlyInvalidatedException

    override fun discardLockedKey() {
        runCatching { keystore().deleteEntry(LOCKED_ALIAS) }
    }

    private fun keystore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val KEYSTORE = "AndroidKeyStore"
        const val OPEN_ALIAS = "luna.vault"
        const val LOCKED_ALIAS = "luna.vault.locked"
        const val AUTH_VALID_SECONDS = 60
    }
}
