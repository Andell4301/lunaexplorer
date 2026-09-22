package com.lunaexplorer.app.storage.transfer

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class TransferProtocol(val providerId: String) { FTP("ftp"), SFTP("sftp") }

@Serializable
enum class FtpSecurity(val port: Int) { PLAIN(21), EXPLICIT_TLS(21), IMPLICIT_TLS(990) }

@Serializable
enum class TransferAuthentication { PASSWORD, PRIVATE_KEY }

@Serializable
data class TransferAccount(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val protocol: TransferProtocol = TransferProtocol.SFTP,
    val host: String = "",
    val port: Int = if (protocol == TransferProtocol.SFTP) 22 else 21,
    val rootPath: String = "/",
    val username: String = "",
    val anonymous: Boolean = false,
    val security: FtpSecurity = FtpSecurity.EXPLICIT_TLS,
    val authentication: TransferAuthentication = TransferAuthentication.PASSWORD,
    val timeoutSeconds: Int = 20,
    val hostKeyFingerprint: String = "",
)

@Serializable
data class TransferCredentials(
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
)

class HostKeyRequired(val fingerprint: String, val algorithm: String, val changed: Boolean) :
    Exception(if (changed) "Host key changed" else "Host key not trusted")

internal fun TransferAccount.validationError(): String? = when {
    id.isEmpty() || ':' in id -> "Invalid server ID"
    host.isEmpty() -> "Server is required"
    port !in 1..65_535 -> "Port must be from 1 to 65535"
    timeoutSeconds !in 1..600 -> "Timeout must be from 1 to 600 seconds"
    rootPath.isEmpty() || '\u0000' in rootPath -> "Invalid root folder"
    protocol == TransferProtocol.FTP && ('\r' in rootPath || '\n' in rootPath) -> "FTP cannot address this root folder"
    else -> null
}
