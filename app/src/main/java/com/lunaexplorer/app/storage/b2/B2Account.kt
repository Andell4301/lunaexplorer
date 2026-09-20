package com.lunaexplorer.app.storage.b2

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

/** [applicationKey] is never serialized; the credential vault supplies it only when connecting. */
@Serializable
data class B2Account(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val keyId: String,
    /** Blank for every bucket the key can reach. */
    val bucket: String = "",
    val options: B2Options = B2Options(),
    @Transient val applicationKey: String = "",
) {
    val wholeAccount: Boolean get() = bucket.isEmpty()

    val address: String get() = "b2://" + bucket.ifEmpty { keyId }

    // The generated one would print the application key.
    override fun toString(): String = "B2Account($id, $name)"
}

@Serializable
data class B2Options(
    /** Connect and read timeout for one request. */
    val timeoutSeconds: Int = 30,
    /** Part size for a large upload, held to the floor B2 reports for the account. */
    val partMegabytes: Int = 100,
)
