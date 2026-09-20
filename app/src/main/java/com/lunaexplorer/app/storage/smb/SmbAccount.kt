package com.lunaexplorer.app.storage.smb

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

/** [password] is never serialized; the credential vault supplies it only when connecting. */
@Serializable
data class SmbAccount(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 445,
    /** Blank for the whole server. */
    val share: String = "",
    val domain: String = "",
    val username: String = "",
    val guest: Boolean = false,
    val options: SmbOptions = SmbOptions(),
    @Transient val password: String = "",
) {
    val wholeServer: Boolean get() = share.isEmpty()

    val address: String get() = "smb://$host${if (port != 445) ":$port" else ""}${if (wholeServer) "" else "/$share"}"
}

@Serializable
data class SmbOptions(
    val minDialect: SmbDialect = SmbDialect.SMB_2_0_2,
    val maxDialect: SmbDialect = SmbDialect.SMB_3_1_1,
    /** Ask for SMB3 encryption; the session still opens without it. */
    val encrypt: Boolean = true,
    /** Fail the connection if the session is not encrypted. */
    val requireEncryption: Boolean = false,
    val requireSigning: Boolean = false,
    /** Follow DFS referrals to other servers. */
    val dfs: Boolean = false,
    /** Connection and per-request timeout. */
    val timeoutSeconds: Int = 20,
) {
    val dialects: List<SmbDialect> get() = SmbDialect.entries.filter { it in minDialect..maxDialect }
}

/** Oldest first; [SmbOptions.dialects] relies on the order. SMB1 is not supported. */
@Serializable
enum class SmbDialect(val label: String) {
    SMB_2_0_2("2.0.2"), SMB_2_1("2.1"), SMB_3_0("3.0"), SMB_3_0_2("3.0.2"), SMB_3_1_1("3.1.1");
}
