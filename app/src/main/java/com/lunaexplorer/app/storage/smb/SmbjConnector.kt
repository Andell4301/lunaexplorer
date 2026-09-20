package com.lunaexplorer.app.storage.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.protocol.commons.socket.ProxySocketFactory
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import com.hierynomus.smbj.share.PipeShare
import com.lunaexplorer.app.debug.DebugLog
import com.lunaexplorer.core.StorageError
import com.rapid7.client.dcerpc.Interface as RpcInterface
import com.rapid7.client.dcerpc.RPCException
import com.rapid7.client.dcerpc.mserref.SystemErrorCode
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.helper.smbj.io.SMB2Exception
import com.rapid7.client.dcerpc.transport.SMBTransport
import com.rapid7.client.dcerpc.transport.exceptions.RPCFaultException
import com.rapid7.helper.smbj.share.NamedPipe
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** smbj exceptions are translated to [SmbFailure] here; no smbj type leaves this file. */
class SmbjConnector : SmbConnector {
    override fun connect(account: SmbAccount): SmbServer {
        val options = account.options
        val client = SMBClient(configuration(options))
        val started = System.nanoTime()
        DebugLog.i(TAG) {
            "Connecting to ${account.host}:${account.port} as ${who(account)} · SMB ${options.minDialect.label} to " +
                "${options.maxDialect.label} · encryption ${when { options.requireEncryption -> "required"; options.encrypt -> "preferred"; else -> "off" }}" +
                " · signing ${if (options.requireSigning) "required" else "as the server asks"} · DFS ${if (options.dfs) "on" else "off"}" +
                " · timeout ${options.timeoutSeconds} s"
        }
        return failing("connect to ${account.host}") {
            val connection = try {
                client.connect(account.host, account.port)
            } catch (error: Exception) { client.close(); throw error }
            try {
                val credentials = when {
                    account.guest -> AuthenticationContext.guest()
                    account.username.isEmpty() -> AuthenticationContext.anonymous()
                    else -> AuthenticationContext(account.username, account.password.toCharArray(), account.domain)
                }
                val session = connection.authenticate(credentials)
                val summary = SmbSessionSummary(
                    dialect = connection.negotiatedProtocol.dialect.label,
                    encrypted = session.sessionContext.isEncryptData,
                    signed = session.sessionContext.isSigningRequired ||
                        connection.connectionContext.isServerRequiresSigning,
                )
                // withEncryptData only requests encryption, so requireEncryption is enforced here.
                if (options.requireEncryption && !summary.encrypted) {
                    session.close(); connection.close()
                    throw SmbFailure(StorageError.AUTH, "${account.host} would not encrypt the session")
                }
                DebugLog.i(TAG) {
                    "Signed in to ${account.host} in ${DebugLog.millisSince(started)} ms · SMB ${summary.dialect} · " +
                        "${if (summary.encrypted) "encrypted" else "not encrypted"} · ${if (summary.signed) "signed" else "unsigned"}"
                }
                ConnectedServer(client, connection, session, summary)
            } catch (error: Exception) {
                runCatching { connection.close() }; runCatching { client.close() }
                throw error
            }
        }
    }

    internal fun configuration(options: SmbOptions): SmbConfig = SmbConfig.builder()
        .withDialects(options.dialects.map { it.wire })
        .withEncryptData(options.encrypt || options.requireEncryption)
        .withSigningRequired(options.requireSigning)
        .withDfsEnabled(options.dfs)
        .withTimeout(options.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        // No socket read timeout: smbj's reader blocks on the socket even when idle, so one would
        // close idle connections. Requests (withTimeout) and the TCP connect (ProxySocketFactory)
        // keep their own deadlines.
        .withSoTimeout(0, TimeUnit.MILLISECONDS)
        .withSocketFactory(ProxySocketFactory(TimeUnit.SECONDS.toMillis(options.timeoutSeconds.toLong()).toInt()))
        .build()

    private fun who(account: SmbAccount): String = when {
        account.guest -> "guest"
        account.username.isEmpty() -> "anonymous"
        account.domain.isNotEmpty() -> "${account.domain}\\${account.username}"
        else -> account.username
    }

    private val SmbDialect.wire: SMB2Dialect get() = when (this) {
        SmbDialect.SMB_2_0_2 -> SMB2Dialect.SMB_2_0_2
        SmbDialect.SMB_2_1 -> SMB2Dialect.SMB_2_1
        SmbDialect.SMB_3_0 -> SMB2Dialect.SMB_3_0
        SmbDialect.SMB_3_0_2 -> SMB2Dialect.SMB_3_0_2
        SmbDialect.SMB_3_1_1 -> SMB2Dialect.SMB_3_1_1
    }

    private val SMB2Dialect.label: String get() = when (this) {
        SMB2Dialect.SMB_2_0_2 -> "2.0.2"
        SMB2Dialect.SMB_2_1 -> "2.1"
        SMB2Dialect.SMB_3_0 -> "3.0"
        SMB2Dialect.SMB_3_0_2 -> "3.0.2"
        SMB2Dialect.SMB_3_1_1 -> "3.1.1"
        else -> name
    }

    private class ConnectedServer(
        private val client: SMBClient,
        private val connection: Connection,
        private val session: Session,
        override val summary: SmbSessionSummary,
    ) : SmbServer {
        // Share enumeration is DCE/RPC over the srvsvc pipe on IPC$.
        override fun shares(): List<SmbShareInfo> = failing("list shares") {
            val pipes = session.connectShare("IPC\$") as? PipeShare
                ?: throw SmbFailure(StorageError.UNSUPPORTED, "The server has no IPC\$ to ask over")
            // Opened by hand so it gets closed; the library's RPC transport factory leaves the pipe
            // open.
            val listed = try {
                NamedPipe(session, pipes, "srvsvc").use { pipe ->
                    val transport = SMBTransport(pipe)
                    transport.bind(RpcInterface.SRVSVC_V3_0, RpcInterface.NDR_32BIT_V2)
                    ServerService(transport).getShares1()
                }
            } catch (error: SMB2Exception) {
                // Samba commonly denies srvsvc to guests; report the NT status, not a transport
                // failure.
                throw SmbFailure(error.status.reason(), error.status.describe("list shares"), error)
            } catch (error: RPCException) {
                throw SmbFailure(error.reason(), "The server would not list its shares: ${error.message}", error)
            } catch (error: RPCFaultException) {
                throw SmbFailure(StorageError.UNSUPPORTED, "The server would not list its shares: ${error.message}", error)
            } catch (broken: NoClassDefFoundError) {
                // smbj-rpc throws java.rmi.UnmarshalException on a malformed reply. Android has no
                // java.rmi, so it surfaces as NoClassDefFoundError.
                throw SmbFailure(StorageError.IO, "The server's answer to the share listing could not be read", broken)
            }
            listed.map { share ->
                SmbShareInfo(
                    name = share.netName,
                    disk = share.type and STYPE_KIND == STYPE_DISKTREE,
                    special = share.type and STYPE_SPECIAL != 0,
                )
            }
        }

        override fun open(share: String): SmbShare = failing("open $share") {
            val tree = session.connectShare(share) as? DiskShare
                ?: throw SmbFailure(StorageError.UNSUPPORTED, "$share is not a folder share")
            // smbj's session can hand back a cached tree that is already closed; DISCONNECTED makes
            // SmbSessions reconnect.
            if (!tree.isConnected) throw SmbFailure(StorageError.DISCONNECTED, "The session can no longer open $share")
            ConnectedShare(connection, tree)
        }

        override fun alive(): Boolean = connection.isConnected

        override fun close() {
            runCatching { session.close() }
            runCatching { connection.close() }
            runCatching { client.close() }
        }
    }

    private class ConnectedShare(
        private val connection: Connection,
        private val share: DiskShare,
    ) : SmbShare {
        // smbj wants backslashes and no leading separator.
        private fun wire(path: String): String = path.trim('/').replace('/', '\\')

        // smbj reports closed trees as generic errors; map them to DISCONNECTED so they can reopen.
        private fun <T> onTree(what: String, block: () -> T): T = try {
            failing(what, block)
        } catch (failure: SmbFailure) {
            if (failure.reason != StorageError.IO || share.isConnected) throw failure
            throw SmbFailure(StorageError.DISCONNECTED, "The connection to the share was closed", failure)
        }

        override fun list(path: String): List<SmbItem> = onTree("list") {
            share.list(wire(path))
                .filter { it.fileName != "." && it.fileName != ".." }
                .map { item ->
                    val attributes = item.fileAttributes
                    SmbItem(
                        name = item.fileName,
                        directory = attributes has FileAttributes.FILE_ATTRIBUTE_DIRECTORY,
                        size = item.endOfFile,
                        modified = item.lastWriteTime.toEpochMillis(),
                        readOnly = attributes has FileAttributes.FILE_ATTRIBUTE_READONLY,
                        hidden = attributes has FileAttributes.FILE_ATTRIBUTE_HIDDEN,
                    )
                }
        }

        override fun stat(path: String): SmbItem? = onTree("stat") {
            val info = try {
                share.getFileInformation(wire(path))
            } catch (error: SMBApiException) {
                if (error.status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND ||
                    error.status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND) return@onTree null
                throw error
            }
            val attributes = info.basicInformation.fileAttributes
            SmbItem(
                name = path.trimEnd('/').substringAfterLast('/'),
                directory = info.standardInformation.isDirectory,
                size = info.standardInformation.endOfFile,
                modified = info.basicInformation.lastWriteTime.toEpochMillis(),
                readOnly = attributes has FileAttributes.FILE_ATTRIBUTE_READONLY,
                hidden = attributes has FileAttributes.FILE_ATTRIBUTE_HIDDEN,
            )
        }

        override fun mkdir(path: String) = onTree("mkdir") { share.mkdir(wire(path)) }

        override fun createFile(path: String) = onTree("create") {
            share.openFile(wire(path), EnumSet.of(AccessMask.GENERIC_WRITE), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_CREATE,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)).close()
        }

        override fun rename(path: String, to: String, replace: Boolean) = onTree("rename") {
            share.open(wire(path), EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).use { entry ->
                entry.rename(wire(to), replace)
            }
        }

        override fun deleteFile(path: String) = onTree("delete") { share.rm(wire(path)) }

        override fun deleteFolder(path: String) = onTree("delete") { share.rmdir(wire(path), false) }

        override fun openReader(path: String): SmbReader = onTree("read") {
            val file = openRead(path)
            object : SmbReader {
                override fun read(offset: Long, into: ByteArray, at: Int, length: Int): Int =
                    onTree("read") { file.read(into, offset, at, length) }
                override fun close() { runCatching { file.close() } }
            }
        }

        override fun write(path: String): OutputStream = onTree("write") {
            val file = share.openFile(wire(path), EnumSet.of(AccessMask.GENERIC_WRITE), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
            val stream = file.outputStream
            object : OutputStream() {
                override fun write(b: Int) = onTree("write") { stream.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) = onTree("write") { stream.write(b, off, len) }
                override fun flush() = onTree("write") { stream.flush() }
                override fun close() = onTree("write") { try { stream.close() } finally { file.close() } }
            }
        }

        private fun openRead(path: String): SmbFile = share.openFile(wire(path),
            EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))

        override fun setModified(path: String, epochMillis: Long) = onTree("set times") {
            share.setFileInformation(wire(path), FileBasicInformation(
                FileBasicInformation.DONT_UPDATE, FileBasicInformation.DONT_UPDATE,
                FileTime.ofEpochMillis(epochMillis), FileBasicInformation.DONT_UPDATE, 0))
        }

        override fun freeBytes(): Long? = runCatching { share.shareInformation.freeSpace }.getOrNull()

        // isConnected only reflects local closure; a server-side close surfaces as a failed
        // request.
        override fun alive(): Boolean = share.isConnected && connection.isConnected

        override fun close() { runCatching { share.close() } }

        private infix fun Long.has(attribute: FileAttributes) = this and attribute.value != 0L
    }

    private companion object {
        const val TAG = "SmbConnector"

        fun <T> failing(what: String, block: () -> T): T = try {
            mapping(what, block)
        } catch (failure: SmbFailure) {
            val cause = failure.cause
            if (cause == null || cause is SMBApiException) DebugLog.d(TAG) { "$what: ${failure.reason} · ${failure.message}" }
            else DebugLog.w(TAG, cause) { "$what: ${failure.reason} · ${failure.message}" }
            throw failure
        }

        private fun <T> mapping(what: String, block: () -> T): T = try {
            block()
        } catch (failure: SmbFailure) {
            throw failure
        } catch (error: SMBApiException) {
            throw SmbFailure(error.status.reason(), error.status.describe(what), error)
        } catch (error: UnknownHostException) {
            throw SmbFailure(StorageError.OFFLINE, "The server could not be found by that name", error)
        } catch (error: ConnectException) {
            throw SmbFailure(StorageError.OFFLINE, "The server refused the connection, or is not there", error)
        } catch (error: SocketTimeoutException) {
            throw SmbFailure(StorageError.TIMEOUT, "The server did not answer in time", error)
        } catch (error: TransportException) {
            if (error.isTimeout()) throw SmbFailure(StorageError.TIMEOUT, "The server did not answer in time", error)
            throw SmbFailure(StorageError.DISCONNECTED, "The connection to the server was lost", error)
        } catch (error: IOException) {
            throw SmbFailure(StorageError.DISCONNECTED, error.message ?: "The connection to the server failed", error)
        } catch (error: RuntimeException) {
            // smbj wraps TransportException and TimeoutException in runtime exceptions.
            if (error.isTimeout()) {
                throw SmbFailure(StorageError.TIMEOUT, "The server did not answer in time", error)
            }
            if (error.causedBy<TransportException>()) {
                throw SmbFailure(StorageError.DISCONNECTED, "The connection to the server was lost", error)
            }
            throw SmbFailure(StorageError.IO, error.message ?: "The server reported an error", error)
        }

        private fun Throwable.isTimeout() = causedBy<TimeoutException>() || causedBy<SocketTimeoutException>()

        private inline fun <reified T : Throwable> Throwable.causedBy(): Boolean =
            generateSequence(this) { it.cause }.take(16).any { it is T }

        // MS-SRVS share type uses low bits for kind and the high bit for administrative shares.
        const val STYPE_KIND = 0x3
        const val STYPE_DISKTREE = 0
        const val STYPE_SPECIAL = Int.MIN_VALUE // 0x80000000 as a signed Int

        /** RPC errors are Win32 system error codes, not NtStatus. */
        fun RPCException.reason(): StorageError = when (if (hasErrorCode()) errorCode else null) {
            SystemErrorCode.ERROR_ACCESS_DENIED, SystemErrorCode.ERROR_NETWORK_ACCESS_DENIED -> StorageError.PERMISSION
            SystemErrorCode.ERROR_NOT_SUPPORTED, SystemErrorCode.ERROR_INVALID_LEVEL -> StorageError.UNSUPPORTED
            else -> StorageError.IO
        }

        fun NtStatus.reason(): StorageError = when (this) {
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_BAD_NETWORK_NAME -> StorageError.NOT_FOUND
            NtStatus.STATUS_ACCESS_DENIED, NtStatus.STATUS_CANNOT_DELETE -> StorageError.PERMISSION
            NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_PASSWORD_EXPIRED,
            NtStatus.STATUS_ACCOUNT_DISABLED -> StorageError.AUTH
            NtStatus.STATUS_OBJECT_NAME_COLLISION, NtStatus.STATUS_DIRECTORY_NOT_EMPTY,
            NtStatus.STATUS_SHARING_VIOLATION -> StorageError.CONFLICT
            NtStatus.STATUS_DISK_FULL -> StorageError.NO_SPACE
            NtStatus.STATUS_NETWORK_NAME_DELETED, NtStatus.STATUS_USER_SESSION_DELETED,
            NtStatus.STATUS_CONNECTION_DISCONNECTED -> StorageError.DISCONNECTED
            NtStatus.STATUS_NOT_A_DIRECTORY, NtStatus.STATUS_FILE_IS_A_DIRECTORY -> StorageError.UNSUPPORTED
            else -> StorageError.IO
        }

        fun NtStatus.describe(what: String): String = when (this) {
            NtStatus.STATUS_LOGON_FAILURE -> "The server rejected the name or password"
            NtStatus.STATUS_PASSWORD_EXPIRED -> "The password has expired on the server"
            NtStatus.STATUS_ACCOUNT_DISABLED -> "The account is disabled on the server"
            NtStatus.STATUS_BAD_NETWORK_NAME -> "The server has no share by that name"
            NtStatus.STATUS_ACCESS_DENIED -> "The server refused: access denied"
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND -> "Not found on the server"
            NtStatus.STATUS_OBJECT_NAME_COLLISION -> "Something of that name is already there"
            NtStatus.STATUS_DIRECTORY_NOT_EMPTY -> "The folder is not empty"
            NtStatus.STATUS_SHARING_VIOLATION -> "Another program has the file open"
            NtStatus.STATUS_DISK_FULL -> "The share is full"
            else -> "The server refused to $what: $name"
        }
    }
}
