package com.lunaexplorer.app.storage.transfer

import com.lunaexplorer.app.storage.RemoteReader
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import kotlinx.coroutines.CancellationException
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.ClosedChannelException
import java.security.MessageDigest
import java.security.Provider
import java.security.PublicKey
import java.security.Security
import java.util.Base64
import java.util.EnumSet
import java.util.concurrent.TimeoutException

class SshjConnector internal constructor(private val newClient: () -> SSHClient) : TransferConnector {
    constructor() : this({
        configureSshCrypto()
        SSHClient()
    })

    override fun connect(account: TransferAccount, credentials: TransferCredentials): TransferClient = sshFailure {
        val ssh = newClient()
        val verifier = SshHostVerifier(account.hostKeyFingerprint)
        try {
            ssh.connectTimeout = account.timeoutSeconds * 1_000
            ssh.timeout = account.timeoutSeconds * 1_000
            ssh.addHostKeyVerifier(verifier)
            try {
                ssh.connect(account.host, account.port)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                verifier.required?.let { throw it }
                throw error
            }
            if (account.authentication == TransferAuthentication.PRIVATE_KEY) {
                val key = try {
                    ssh.loadKeys(decryptPkcs8(credentials), null, object : PasswordFinder {
                        override fun reqPassword(resource: Resource<*>): CharArray = credentials.passphrase.toCharArray()
                        override fun shouldRetry(resource: Resource<*>): Boolean = false
                    }).also { it.private }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    throw StorageException(StorageError.AUTH, "The private key could not be opened")
                }
                ssh.authPublickey(account.username, key)
            } else {
                ssh.authPassword(account.username, credentials.password.toCharArray())
            }
            val sftp = ssh.newSFTPClient()
            try {
                sftp.sftpEngine.timeoutMs = account.timeoutSeconds * 1_000
                val root = sftp.canonicalize(account.rootPath)
                if (sftp.lstat(root).type != FileMode.Type.DIRECTORY) {
                    throw StorageException(StorageError.UNSUPPORTED, "The root is not a folder")
                }
                ConnectedSftp(ssh, sftp, root)
            } catch (error: Exception) {
                closeSshResource(sftp)
                throw error
            }
        } catch (error: Exception) {
            closeSshResource(ssh)
            throw error
        }
    }

    private fun decryptPkcs8(credentials: TransferCredentials): String {
        if (!credentials.privateKey.contains("-----BEGIN ENCRYPTED PRIVATE KEY-----")) return credentials.privateKey
        val password = credentials.passphrase.toCharArray()
        try {
            val encrypted = PEMParser(StringReader(credentials.privateKey)).use { it.readObject() } as PKCS8EncryptedPrivateKeyInfo
            // SSHJ's PKCS8 reader uses AlgorithmParameters.toString() as a cipher name, which varies by provider.
            val decryptor = JceOpenSSLPKCS8DecryptorProviderBuilder().setProvider("LunaSSH").build(password)
            val bytes = encrypted.decryptPrivateKeyInfo(decryptor).encoded
            return "-----BEGIN PRIVATE KEY-----\n${Base64.getEncoder().encodeToString(bytes)}\n-----END PRIVATE KEY-----\n"
        } finally {
            password.fill('\u0000')
        }
    }
}

internal class SshHostVerifier(private val expected: String) : HostKeyVerifier {
    @Volatile var required: HostKeyRequired? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val fingerprint = fingerprint(key)
        if (expected == fingerprint) return true
        required = HostKeyRequired(fingerprint, KeyType.fromKey(key).toString(), expected.isNotEmpty())
        return false
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()

    companion object {
        fun fingerprint(key: PublicKey): String {
            val wire = Buffer.PlainBuffer().putPublicKey(key).compactData
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(wire))
        }
    }
}

private class ConnectedSftp(
    private val ssh: SSHClient,
    private val sftp: SFTPClient,
    private val root: String,
) : TransferClient {
    override fun alive(): Boolean = ssh.isConnected && ssh.isAuthenticated && sftp.sftpEngine.subsystem.isOpen

    override fun list(path: String): List<TransferItem> = sshFailure {
        val wire = checked(path)
        if (wire.attributes?.type != FileMode.Type.DIRECTORY) {
            throw StorageException(StorageError.UNSUPPORTED, "The item is not a folder")
        }
        sftp.ls(wire.path).filter { it.name != "." && it.name != ".." }.map { item ->
            item.attributes.item(item.name)
        }
    }

    override fun stat(path: String): TransferItem? = sshFailure {
        try {
            checked(path, allowLeafLink = true).attributes!!.item(path.substringAfterLast('/'))
        } catch (error: SFTPException) {
            if (error.statusCode == StatusCode.NO_SUCH_FILE || error.statusCode == StatusCode.NO_SUCH_PATH) null else throw error
        }
    }

    override fun mkdir(path: String) = sshFailure { sftp.mkdir(checked(path, missingLeaf = true).path) }

    override fun createFile(path: String) = sshFailure {
        sftp.open(checked(path, missingLeaf = true).path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)).use { }
    }

    override fun rename(from: String, to: String) = sshFailure {
        val source = checked(from, allowLeafLink = true)
        val target = checked(to, missingLeaf = true, allowLeafLink = true)
        // Empty flags use SFTP RENAME, whose destination must not exist.
        sftp.rename(source.path, target.path, EnumSet.noneOf(RenameFlags::class.java))
    }

    override fun deleteFile(path: String) = sshFailure { sftp.rm(checked(path, allowLeafLink = true).path) }

    override fun deleteFolder(path: String) = sshFailure { sftp.rmdir(checked(path).path) }

    override fun openReader(path: String): RemoteReader = sshFailure {
        val wire = checked(path)
        requireRegular(wire.attributes)
        val file = sftp.open(wire.path, EnumSet.of(OpenMode.READ))
        object : RemoteReader {
            override fun read(offset: Long, into: ByteArray, at: Int, length: Int): Int = sshFailure {
                require(offset >= 0 && at >= 0 && length >= 0 && at <= into.size - length)
                if (length == 0) 0 else file.read(offset, into, at, minOf(length, PACKET_BYTES))
            }
            override fun close() = sshFailure { file.close() }
        }
    }

    override fun openStream(path: String): InputStream = sshFailure {
        val wire = checked(path)
        val attributes = requireRegular(wire.attributes)
        val size = attributes.size.takeIf { attributes.has(FileAttributes.Flag.SIZE) }
        val file = sftp.open(wire.path, EnumSet.of(OpenMode.READ))
        val stream = BufferedInputStream(file.ReadAheadRemoteFileInputStream(PIPELINE_REQUESTS - 1, 0L, size ?: -1L), PACKET_BYTES)
        object : InputStream() {
            private var position = 0L
            private var ended = false
            private var closed = false
            private val single = ByteArray(1)

            override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = sshFailure {
                if (offset < 0 || length < 0 || length > bytes.size - offset) throw IndexOutOfBoundsException()
                if (closed) throw ClosedChannelException()
                if (length == 0) return@sshFailure 0
                if (ended || (size != null && position >= size)) return@sshFailure -1
                val wanted = minOf(length.toLong(), PACKET_BYTES.toLong(), size?.minus(position) ?: Long.MAX_VALUE).toInt()
                stream.read(bytes, offset, wanted).also {
                    if (it > 0) position += it
                    if (it < 0) ended = true
                }
            }

            override fun close() = sshFailure {
                if (!closed) {
                    closed = true
                    stream.close()
                    file.close()
                }
            }
        }
    }

    override fun write(path: String): OutputStream = sshFailure {
        val wire = checked(path)
        requireRegular(wire.attributes)
        val file = sftp.open(wire.path, EnumSet.of(OpenMode.WRITE))
        try {
            val attributes = file.fetchAttributes()
            requireRegular(attributes)
            if (attributes.size != 0L) throw StorageException(StorageError.CONFLICT, "The file is not empty")
        } catch (error: Exception) {
            closeSshResource(file)
            throw error
        }
        val stream = file.RemoteFileOutputStream(0L, PIPELINE_REQUESTS - 1)
        val packetBytes = (sftp.sftpEngine.subsystem.remoteMaxPacketSize - file.outgoingPacketOverhead).coerceIn(1, PACKET_BYTES)
        object : OutputStream() {
            private var closed = false
            private val single = ByteArray(1)
            override fun write(value: Int) {
                single[0] = value.toByte()
                write(single, 0, 1)
            }
            override fun write(bytes: ByteArray, at: Int, length: Int) = sshFailure {
                require(at >= 0 && length >= 0 && at <= bytes.size - length)
                if (closed) throw ClosedChannelException()
                var written = 0
                while (written < length) {
                    val count = minOf(length - written, packetBytes)
                    stream.write(bytes, at + written, count)
                    written += count
                }
            }
            override fun flush() = sshFailure {
                if (closed) throw ClosedChannelException()
                stream.flush()
            }
            override fun close() = sshFailure {
                if (!closed) {
                    closed = true
                    try { stream.close() } catch (error: Throwable) {
                        closeSshResource(file)
                        throw error
                    }
                    file.close()
                }
            }
        }
    }

    override fun setModified(path: String, millis: Long): Boolean = sshFailure {
        val wire = checked(path)
        val attributes = wire.attributes!!
        sftp.setattr(wire.path, FileAttributes.Builder().withAtimeMtime(attributes.atime, millis / 1_000).build())
        true
    }

    override fun close() {
        try { closeSshResource(sftp) } finally { closeSshResource(ssh) }
    }

    private class CheckedPath(val path: String, val attributes: FileAttributes?)

    private fun checked(path: String, missingLeaf: Boolean = false, allowLeafLink: Boolean = false): CheckedPath {
        val rootAttributes = if (sftp.canonicalize(root) == root) sftp.lstat(root) else null
        if (rootAttributes?.type != FileMode.Type.DIRECTORY) {
            throw StorageException(StorageError.UNSUPPORTED, "The root folder changed")
        }
        if (path.isEmpty()) return CheckedPath(root, rootAttributes)
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || '\u0000' in it }) {
            throw StorageException(StorageError.INVALID_NAME, "Invalid path")
        }
        var current = root
        var attributes = rootAttributes
        for ((index, name) in segments.withIndex()) {
            current = if (current.endsWith('/')) current + name else "$current/$name"
            val leaf = index == segments.lastIndex
            attributes = try {
                sftp.lstat(current)
            } catch (error: SFTPException) {
                if (missingLeaf && leaf && (error.statusCode == StatusCode.NO_SUCH_FILE || error.statusCode == StatusCode.NO_SUCH_PATH)) return CheckedPath(current, null)
                throw error
            }
            if (attributes.type == FileMode.Type.SYMLINK && !(leaf && allowLeafLink)) {
                throw StorageException(StorageError.UNSUPPORTED, "Symbolic links cannot be opened")
            }
            if (!leaf && attributes.type != FileMode.Type.DIRECTORY) {
                throw StorageException(StorageError.UNSUPPORTED, "The item is not a folder")
            }
        }
        return CheckedPath(current, attributes)
    }

    private fun requireRegular(attributes: FileAttributes?): FileAttributes {
        if (attributes?.type != FileMode.Type.REGULAR) throw StorageException(StorageError.UNSUPPORTED, "The item is not a file")
        return attributes
    }

    private fun FileAttributes.item(name: String) = TransferItem(
        name = name,
        directory = type == FileMode.Type.DIRECTORY,
        size = if (has(FileAttributes.Flag.SIZE)) size else null,
        modified = if (has(FileAttributes.Flag.ACMODTIME)) mtime * 1_000 else null,
        link = type != FileMode.Type.REGULAR && type != FileMode.Type.DIRECTORY,
    )

    private companion object {
        const val PACKET_BYTES = 32 * 1_024
        const val PIPELINE_REQUESTS = 16
    }
}

internal fun <T> sshFailure(block: () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: HostKeyRequired) {
    throw error
} catch (error: StorageException) {
    throw error
} catch (error: Exception) {
    val reason = when {
        generateSequence(error as Throwable) { it.cause }.take(16).any { it is SocketTimeoutException || it is TimeoutException } -> StorageError.TIMEOUT
        error is UnknownHostException || error is ConnectException -> StorageError.OFFLINE
        error is UserAuthException -> StorageError.AUTH
        error is SFTPException -> when (error.statusCode) {
            StatusCode.NO_SUCH_FILE, StatusCode.NO_SUCH_PATH -> StorageError.NOT_FOUND
            StatusCode.PERMISSION_DENIED, StatusCode.WRITE_PROTECT, StatusCode.CANNOT_DELETE -> StorageError.PERMISSION
            StatusCode.FILE_ALREADY_EXISTS, StatusCode.DIR_NOT_EMPTY, StatusCode.LOCK_CONFLICT -> StorageError.CONFLICT
            StatusCode.NO_SPACE_ON_FILESYSTEM, StatusCode.QUOTA_EXCEEDED -> StorageError.NO_SPACE
            StatusCode.NO_CONNECTION, StatusCode.CONNECITON_LOST -> StorageError.DISCONNECTED
            StatusCode.OP_UNSUPPORTED, StatusCode.NOT_A_DIRECTORY, StatusCode.FILE_IS_A_DIRECTORY -> StorageError.UNSUPPORTED
            StatusCode.INVALID_FILENAME -> StorageError.INVALID_NAME
            else -> StorageError.IO
        }
        error is TransportException || error is IOException -> StorageError.DISCONNECTED
        else -> StorageError.IO
    }
    throw StorageException(reason, when (reason) {
        StorageError.AUTH -> "The server rejected the credentials"
        StorageError.TIMEOUT -> "The server did not answer in time"
        StorageError.OFFLINE -> "The server could not be reached"
        StorageError.DISCONNECTED -> "The connection to the server was lost"
        StorageError.PERMISSION -> "The server denied access"
        StorageError.NOT_FOUND -> "The item no longer exists"
        StorageError.CONFLICT -> "The destination already exists or is in use"
        StorageError.NO_SPACE -> "The server has no free space"
        StorageError.UNSUPPORTED -> "The server does not support this operation"
        StorageError.INVALID_NAME -> "Invalid name"
        else -> "The SFTP operation failed"
    })
}

private fun closeSshResource(resource: Closeable) {
    try { resource.close() } catch (error: Exception) { if (error is CancellationException) throw error }
}

@Synchronized internal fun configureSshCrypto() {
    // Android's built-in BC omits algorithms required by SSHJ.
    val name = "LunaSSH"
    if (Security.getProvider(name) == null) {
        Security.addProvider(object : Provider(name, 1.0, "SSH cryptography") {
            init { putAll(BouncyCastleProvider()) }
        })
    }
    SecurityUtils.setSecurityProvider(name)
}
