package com.lunaexplorer.app.storage.transfer

import com.lunaexplorer.app.storage.RemoteReader
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import kotlinx.coroutines.CancellationException
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPClientConfig
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyStore
import java.time.Duration
import java.util.Locale
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class FtpConnector internal constructor(private val newClient: (FtpSecurity) -> FTPClient) : TransferConnector {
    constructor() : this(::ftpClient)

    override fun connect(account: TransferAccount, credentials: TransferCredentials): TransferClient = ftpFailure {
        listOf(account.host, account.rootPath, account.username, credentials.password).forEach(::commandArgument)
        val ftp = newClient(account.security)
        try {
            ftp.connectTimeout = account.timeoutSeconds * 1_000
            ftp.defaultTimeout = account.timeoutSeconds * 1_000
            ftp.setDataTimeout(Duration.ofSeconds(account.timeoutSeconds.toLong()))
            ftp.controlEncoding = "UTF-8"
            ftp.setListHiddenFiles(true)
            ftp.configure(FTPClientConfig().apply { setUnparseableEntries(true) })
            ftp.connect(account.host, account.port)
            ftp.requireReply(FTPReply.isPositiveCompletion(ftp.replyCode))
            ftp.soTimeout = account.timeoutSeconds * 1_000
            ftp.requireReply(ftp.login(if (account.anonymous) "anonymous" else account.username,
                if (account.anonymous) "anonymous@" else credentials.password))
            if (ftp is FTPSClient) {
                ftp.execPBSZ(0)
                ftp.execPROT("P")
            }
            ftp.enterLocalPassiveMode()
            ftp.requireReply(ftp.setFileType(FTP.BINARY_FILE_TYPE))
            ftp.requireReply(ftp.changeWorkingDirectory(account.rootPath.ifEmpty { "." }))
            val root = ftp.printWorkingDirectory() ?: throw StorageException(StorageError.IO, "The server did not return its working folder")
            commandArgument(root)
            ConnectedFtp(ftp, root)
        } catch (error: Exception) {
            try { ftp.disconnect() } catch (_: IOException) { }
            throw error
        }
    }
}

private fun ftpClient(security: FtpSecurity): FTPClient {
    if (security == FtpSecurity.PLAIN) return FTPClient()
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
        init(null as KeyStore?)
    }.trustManagers.filterIsInstance<X509TrustManager>().first()
    return object : FTPSClient(security == FtpSecurity.IMPLICIT_TLS) {
        private var openingData: Socket? = null

        override fun _prepareDataSocket_(socket: Socket?) { openingData = socket }

        override fun _openDataConnection_(command: String, argument: String?): Socket? {
            try {
                val data = super._openDataConnection_(command, argument)
                if (data is SSLSocket) {
                    // Passive data sockets use an IP address; bind their certificate to the verified control connection.
                    val control = (_socket_ as SSLSocket).session.peerCertificates.first()
                    if (data.session.peerCertificates.first() != control) {
                        throw SSLHandshakeException("The data connection certificate changed")
                    }
                }
                return data
            } catch (error: Exception) {
                try { openingData?.close() } catch (_: IOException) { }
                throw error
            } finally { openingData = null }
        }
    }.apply {
        setTrustManager(trust)
        isEndpointCheckingEnabled = true
    }
}

private class ConnectedFtp(private val ftp: FTPClient, private val root: String) : TransferClient {
    private var machineListing: Boolean? = null

    override fun stat(path: String): TransferItem? = ftpFailure {
        if (path.isEmpty()) {
            folder("")
            TransferItem("", true)
        } else {
            folder(path.substringBeforeLast('/', ""))
            listing().firstOrNull { it.name == path.substringAfterLast('/') }
        }
    }

    override fun list(path: String): List<TransferItem> = ftpFailure {
        folder(path)
        listing()
    }

    private fun folder(path: String) {
        val parts = components(path)
        ftp.requireReply(ftp.changeWorkingDirectory(root))
        requireWorkingDirectory(root)
        var walked = ""
        for (part in parts) {
            val entry = listing().firstOrNull { it.name == part }
                ?: throw StorageException(StorageError.NOT_FOUND, "The folder was not found")
            if (!entry.directory || entry.link) throw StorageException(StorageError.UNSUPPORTED, "The item is not a folder")
            ftp.requireReply(ftp.changeWorkingDirectory("./$part"))
            walked = if (walked.isEmpty()) part else "$walked/$part"
            requireWorkingDirectory(wire(walked))
        }
    }

    private fun requireWorkingDirectory(expected: String) {
        if (ftp.printWorkingDirectory() != expected) {
            throw StorageException(StorageError.UNSUPPORTED, "The server folder changed")
        }
    }

    private fun listing(): List<TransferItem> {
        var files: Array<FTPFile>? = null
        if (machineListing != false) {
            files = ftp.mlistDir()
            if (FTPReply.isPositiveCompletion(ftp.replyCode)) machineListing = true
            else if (ftp.replyCode in setOf(500, 501, 502, 504)) { machineListing = false; files = null }
            else ftp.requireReply(false)
        }
        if (files == null) {
            val config = FTPClientConfig(listingSystem(ftp.systemType)).apply { setUnparseableEntries(true) }
            ftp.configure(config)
            files = ftp.listFiles()
            ftp.requireReply(FTPReply.isPositiveCompletion(ftp.replyCode))
        }
        return files.mapNotNull { file ->
            if (!file.isValid) throw StorageException(StorageError.UNSUPPORTED, "The server listing could not be read")
            val name = file.name ?: throw StorageException(StorageError.IO, "The server returned an invalid name")
            if (name == "." || name == "..") return@mapNotNull null
            if (components(name).size != 1) throw StorageException(StorageError.IO, "The server returned an invalid name")
            TransferItem(name, file.isDirectory && !file.isSymbolicLink,
                if (file.isFile && (machineListing == false || file.rawListing.orEmpty().substringBefore(' ')
                    .split(';').any { it.startsWith("size=", ignoreCase = true) })) file.size.takeIf { it >= 0 } else null,
                file.timestamp?.timeInMillis, file.isSymbolicLink || (!file.isFile && !file.isDirectory))
        }
    }

    override fun openReader(path: String): RemoteReader = ftpFailure {
        components(path)
        folder(path.substringBeforeLast('/', ""))
        val name = path.substringAfterLast('/')
        val item = listing().firstOrNull { it.name == name }
            ?: throw StorageException(StorageError.NOT_FOUND, "The file was not found")
        if (item.directory || item.link) throw StorageException(StorageError.UNSUPPORTED, "The item is not a file")
        val stream = ftp.retrieveFileStream("./$name") ?: run { ftp.requireReply(false); error("No data stream") }
        object : RemoteReader {
            private var position = 0L
            private var closed = false
            private var complete = false

            override fun read(offset: Long, into: ByteArray, at: Int, length: Int): Int = ftpFailure {
                if (closed) throw IOException("The stream is closed")
                if (offset != position) throw StorageException(StorageError.UNSUPPORTED, "FTP reads are sequential")
                val count = stream.read(into, at, length)
                if (count > 0) position += count
                if (count < 0 && !complete) {
                    stream.close()
                    ftp.requireReply(ftp.completePendingCommand())
                    complete = true
                }
                count
            }

            override fun close() {
                if (closed) return
                closed = true
                ftpFailure { stream.close() }
            }
        }
    }

    override fun mkdir(path: String): Unit = ftpFailure {
        val target = newTarget(path)
        ftp.requireReply(ftp.makeDirectory(target))
    }

    override fun createFile(path: String): Unit = ftpFailure {
        upload(newTarget(path)).use { }
    }

    override fun rename(from: String, to: String): Unit = ftpFailure {
        existing(from)
        val target = newTarget(to)
        // FTP has no exclusive RNTO; the existence check cannot exclude concurrent server changes.
        ftp.requireReply(ftp.rename(wire(from), target))
    }

    override fun deleteFile(path: String): Unit = ftpFailure {
        val item = existing(path)
        if (item.directory && !item.link) throw StorageException(StorageError.UNSUPPORTED, "The item is a folder")
        ftp.requireReply(ftp.deleteFile(wire(path)))
    }

    override fun deleteFolder(path: String): Unit = ftpFailure {
        val item = existing(path)
        if (!item.directory || item.link) throw StorageException(StorageError.UNSUPPORTED, "The item is not a folder")
        folder(path)
        if (listing().isNotEmpty()) throw StorageException(StorageError.CONFLICT, "The folder is not empty")
        ftp.requireReply(ftp.changeWorkingDirectory(root))
        ftp.requireReply(ftp.removeDirectory(wire(path)))
    }

    override fun write(path: String): OutputStream = ftpFailure {
        val item = existing(path)
        if (item.directory || item.link) throw StorageException(StorageError.UNSUPPORTED, "The item is not a file")
        if (item.size != 0L) throw StorageException(StorageError.CONFLICT, "Refusing to truncate a nonempty file")
        upload(wire(path))
    }

    private fun existing(path: String): TransferItem {
        if (path.isEmpty()) throw StorageException(StorageError.UNSUPPORTED, "A storage root cannot be changed")
        return stat(path) ?: throw StorageException(StorageError.NOT_FOUND, "The item was not found")
    }

    private fun newTarget(path: String): String {
        if (path.isEmpty()) throw StorageException(StorageError.UNSUPPORTED, "A storage root cannot be changed")
        components(path)
        if (stat(path) != null) throw StorageException(StorageError.CONFLICT, "The destination already exists")
        return wire(path)
    }

    private fun wire(path: String): String = root + (if (root.endsWith('/')) "" else "/") + path

    private fun upload(path: String): OutputStream {
        val stream = ftp.storeFileStream(path) ?: run { ftp.requireReply(false); error("No data stream") }
        return object : OutputStream() {
            private var closed = false
            override fun write(value: Int) = ftpFailure { stream.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) = ftpFailure { stream.write(bytes, offset, length) }
            override fun flush() = ftpFailure { stream.flush() }
            override fun close() {
                if (closed) return
                closed = true
                ftpFailure {
                    stream.close()
                    ftp.requireReply(ftp.completePendingCommand())
                }
            }
        }
    }

    override fun setModified(path: String, millis: Long): Boolean = false
    override fun close() = ftpFailure { ftp.disconnect() }
}

private fun listingSystem(system: String): String {
    val type = system.uppercase(Locale.ROOT)
    return when {
        "WINDOWS" in type || "WIN32" in type -> FTPClientConfig.SYST_NT
        "OS/2" in type -> FTPClientConfig.SYST_OS2
        "OS/400" in type || "AS/400" in type -> FTPClientConfig.SYST_OS400
        "VMS" in type -> FTPClientConfig.SYST_VMS
        "MVS" in type -> FTPClientConfig.SYST_MVS
        "NETWARE" in type -> FTPClientConfig.SYST_NETWARE
        else -> FTPClientConfig.SYST_UNIX
    }
}

private fun components(path: String): List<String> {
    commandArgument(path)
    if (path.isEmpty()) return emptyList()
    val parts = path.split('/')
    if (parts.any { it.isEmpty() || it == "." || it == ".." }) {
        throw StorageException(StorageError.INVALID_NAME, "Invalid FTP path")
    }
    return parts
}

private fun commandArgument(value: String) {
    if (value.any { it == '\r' || it == '\n' || it == '\u0000' }) {
        throw StorageException(StorageError.INVALID_NAME, "FTP arguments cannot contain line breaks or NUL")
    }
}

private fun FTPClient.requireReply(success: Boolean) {
    if (success) return
    val reason = when (replyCode) {
        530, 532 -> StorageError.AUTH
        550, 553 -> StorageError.PERMISSION
        421, 425, 426 -> StorageError.OFFLINE
        500, 501, 502, 504 -> StorageError.UNSUPPORTED
        else -> StorageError.IO
    }
    throw StorageException(reason, "FTP request failed ($replyCode)")
}

private inline fun <T> ftpFailure(block: () -> T): T = try {
    block()
} catch (error: Exception) {
    when (error) {
        is CancellationException, is StorageException -> throw error
        is SocketTimeoutException -> throw StorageException(StorageError.TIMEOUT, "FTP request timed out")
        is UnknownHostException, is ConnectException -> throw StorageException(StorageError.OFFLINE, "The FTP server could not be reached")
        is SSLException -> throw StorageException(StorageError.AUTH, "The TLS connection could not be verified")
        else -> throw StorageException(StorageError.IO, "FTP request failed")
    }
}
