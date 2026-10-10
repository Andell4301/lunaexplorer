package com.lunaexplorer.app.terminal

import com.lunaexplorer.app.storage.transfer.TransferAccount
import com.lunaexplorer.app.storage.transfer.TransferCredentials
import com.lunaexplorer.app.storage.transfer.authenticatedSsh
import com.lunaexplorer.app.storage.transfer.closeSshResource
import com.lunaexplorer.app.storage.transfer.newSshClient
import com.lunaexplorer.app.storage.transfer.sshFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resumeWithException

class SshTerminalConnector internal constructor(private val newClient: () -> SSHClient) {
    constructor() : this(::newSshClient)

    suspend fun connect(
        account: TransferAccount,
        credentials: TransferCredentials,
        columns: Int,
        rows: Int,
        directory: String? = null,
    ): TerminalConnection {
        require(columns > 0 && rows > 0)
        return opening(account, credentials, { it.close() }) { ssh, owner ->
            val session = ssh.startSession()
            try {
                session.allocatePTY("xterm-256color", columns, rows, 0, 0, emptyMap())
                // Clear the read deadline before the shell reply starts the next blocking socket read.
                ssh.socket?.soTimeout = 0
                val shell = if (directory == null) session.startShell() else {
                    val command = "cd -- ${shellQuote(directory)} && exec \"\${SHELL:-/bin/sh}\" -l"
                    session.exec("sh -c ${shellQuote(command)}") as Session.Shell
                }
                ConnectedShell(owner, shell)
            } catch (error: Throwable) {
                closeSshResource(session)
                throw error
            }
        }
    }

    suspend fun probe(account: TransferAccount, credentials: TransferCredentials) {
        opening(account, credentials, {}) { _, owner -> owner.close() }
    }

    private suspend fun <T> opening(
        account: TransferAccount,
        credentials: TransferCredentials,
        discard: (T) -> Unit,
        action: (SSHClient, SshOwner) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val owner = SshOwner()
        continuation.invokeOnCancellation {
            owner.cancelled.set(true)
            background { owner.close() }
        }
        background {
            try {
                if (!continuation.isActive) throw CancellationException()
                val ssh = sshFailure("SSH") {
                    authenticatedSsh(account, credentials) {
                        newClient().also { ssh ->
                            owner.attach(ssh)
                            ssh.connection.keepAlive.keepAliveInterval = 30
                        }
                    }
                }
                if (!continuation.isActive) throw CancellationException()
                val result = sshFailure("SSH") { action(ssh, owner) }
                continuation.resume(result) { _, abandoned, _ -> background { discard(abandoned) } }
            } catch (error: Throwable) {
                try { owner.close() } finally { continuation.resumeWithException(error) }
            }
        }
    }
}

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

private class ConnectedShell(private val owner: SshOwner, private val shell: Session.Shell) : TerminalConnection {
    private val closed = AtomicBoolean()
    override val input: InputStream = shell.inputStream.safeRead()
    override val error: InputStream = shell.errorStream.safeRead()
    override val output: OutputStream = object : OutputStream() {
        override fun write(value: Int) = sshFailure("SSH") { shell.outputStream.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) = sshFailure("SSH") {
            shell.outputStream.write(bytes, offset, length)
        }
        override fun flush() = sshFailure("SSH") { shell.outputStream.flush() }
    }
    override val exitStatus: Int? get() = (shell as? Session.Command)?.exitStatus

    override fun resize(columns: Int, rows: Int) = sshFailure("SSH") {
        require(columns > 0 && rows > 0)
        shell.changeWindowDimensions(columns, rows, 0, 0)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { owner.close() } finally { closeSshResource(shell) }
        }
    }
}

private fun InputStream.safeRead(): InputStream = object : InputStream() {
    override fun read(): Int = sshFailure("SSH") { this@safeRead.read() }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = sshFailure("SSH") {
        this@safeRead.read(bytes, offset, length)
    }
}

private class SshOwner : Closeable {
    val cancelled = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val client = AtomicReference<SSHClient?>()
    private val socket = AtomicReference<Socket?>()

    fun attach(ssh: SSHClient) {
        val delegate = ssh.socketFactory
        ssh.socketFactory = object : SocketFactory() {
            override fun createSocket(): Socket = own(delegate.createSocket())
            override fun createSocket(host: String, port: Int): Socket = own(delegate.createSocket(host, port))
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
                own(delegate.createSocket(host, port, local, localPort))
            override fun createSocket(host: InetAddress, port: Int): Socket = own(delegate.createSocket(host, port))
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
                own(delegate.createSocket(host, port, local, localPort))
        }
        client.set(ssh)
        if (cancelled.get()) {
            closeSshResource(ssh)
            throw CancellationException()
        }
    }

    private fun own(value: Socket): Socket {
        socket.set(value)
        // Closing an SSHClient before connect starts does not stop it from opening a new socket.
        if (cancelled.get()) {
            closeSshResource(value)
            throw CancellationException()
        }
        value.keepAlive = true
        return value
    }

    override fun close() {
        cancelled.set(true)
        if (closed.compareAndSet(false, true)) {
            try { socket.get()?.let(::closeSshResource) } finally { client.get()?.let(::closeSshResource) }
        }
    }
}

private fun background(block: () -> Unit) = Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable(block))
