package com.lunaexplorer.core

import net.lingala.zip4j.model.enums.EncryptionMethod
import kotlinx.serialization.Serializable

@Serializable
enum class ZipEncryption(val label: String) {
    NONE("None"),
    ZIP_CRYPTO("ZipCrypto"),
    AES_256("AES-256");

    internal val method: EncryptionMethod
        get() = when (this) {
            NONE -> EncryptionMethod.NONE
            ZIP_CRYPTO -> EncryptionMethod.ZIP_STANDARD
            AES_256 -> EncryptionMethod.AES
        }
}
