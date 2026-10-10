package com.lunaexplorer.app.terminal

import com.lunaexplorer.app.storage.transfer.HostKeyRequired
import com.lunaexplorer.app.storage.transfer.SshHostVerifier
import com.lunaexplorer.app.storage.transfer.TransferAccount
import com.lunaexplorer.app.storage.transfer.TransferCredentials
import com.lunaexplorer.app.storage.transfer.configureSshCrypto
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.Socket
import java.security.KeyPairGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

class SshTerminalConnectorTest {
    private lateinit var ssh: FakeSsh
    private lateinit var account: TransferAccount

    @Before fun setup() {
        configureSshCrypto()
        ssh = FakeSsh()
        account = TransferAccount(host = "ssh.example", username = "user", rootPath = "/unavailable-sftp-root",
            hostKeyFingerprint = SshHostVerifier.fingerprint(ssh.hostKey))
    }

    @Test fun `a terminal allocates a PTY without opening SFTP and streams control bytes unchanged`() = runBlocking {
        SshTerminalConnector { ssh }.connect(account, TransferCredentials(password = " password "), 91, 33).use { connection ->
            assertArrayEquals(ssh.received, connection.input.readBytes())
            assertEquals("diagnostic", connection.error!!.readBytes().decodeToString())
            val sent = byteArrayOf(0, 3, 27, 91, 65, -1)
            connection.output.write(sent)
            connection.output.flush()
            assertArrayEquals(sent, ssh.sent.toByteArray())
            connection.resize(112, 44)
            assertEquals(listOf(112, 44, 0, 0), ssh.dimensions)
            assertEquals(7, connection.exitStatus)
            assertEquals(" password ", ssh.password)
            assertEquals(listOf("xterm-256color", 91, 33, 0, 0, emptyMap<Any, Any>()), ssh.pty)
            assertEquals(0, ssh.connectedSocket!!.soTimeout)
            assertEquals(0, ssh.timeoutWhenShellStarts)
            assertTrue(ssh.keepAliveWhenConnected)
            assertTrue(ssh.connectedSocket!!.keepAlive)
            assertEquals(1, ssh.shellRequests)
            assertNull(ssh.command)
        }
        assertTrue(ssh.closed.await(5, TimeUnit.SECONDS))
        assertTrue(ssh.channelClosed.await(5, TimeUnit.SECONDS))
        assertTrue(ssh.connectedSocket!!.isClosed)
    }

    @Test fun `an untrusted host stops before authentication or creating a shell`() = runBlocking {
        try {
            SshTerminalConnector { ssh }.connect(account.copy(hostKeyFingerprint = ""), TransferCredentials(password = "secret"), 80, 24)
            throw AssertionError("Expected a host key request")
        } catch (required: HostKeyRequired) {
            assertEquals(account.hostKeyFingerprint, required.fingerprint)
            assertFalse(required.changed)
        }
        assertNull(ssh.password)
        assertEquals(0, ssh.shellRequests)
        assertTrue(ssh.closed.await(5, TimeUnit.SECONDS))
    }

    @Test fun `probing credentials does not require a file subsystem or open a shell`() = runBlocking {
        SshTerminalConnector { ssh }.probe(account, TransferCredentials(password = "secret"))
        assertEquals("secret", ssh.password)
        assertEquals(0, ssh.shellRequests)
        assertTrue(ssh.closed.await(5, TimeUnit.SECONDS))
        assertTrue(ssh.connectedSocket!!.isClosed)
    }

    @Test fun `a refused PTY closes the channel and connection without exposing server details`() = runBlocking {
        ssh.rejectPty = true
        try {
            SshTerminalConnector { ssh }.connect(account, TransferCredentials(), 80, 24)
            throw AssertionError("Expected a terminal failure")
        } catch (error: StorageException) {
            assertEquals(StorageError.DISCONNECTED, error.reason)
            assertFalse(error.toString().contains("secret"))
            assertNull(error.cause)
        }
        assertTrue(ssh.closed.await(5, TimeUnit.SECONDS))
        assertTrue(ssh.channelClosed.await(5, TimeUnit.SECONDS))
    }

    @Test fun `canceling authentication closes the socket and releases the connecting worker`() = runBlocking {
        ssh.blockAuthentication = true
        val job = launch { SshTerminalConnector { ssh }.connect(account, TransferCredentials(), 80, 24) }
        kotlinx.coroutines.yield()
        assertTrue(ssh.authenticating.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(ssh.closed.await(5, TimeUnit.SECONDS))
        assertTrue(ssh.authenticationFinished.await(5, TimeUnit.SECONDS))
        assertTrue(ssh.connectedSocket!!.isClosed)
    }

    @Test fun `canceling before client creation cannot leak the later client`() = runBlocking {
        val creating = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connector = SshTerminalConnector {
            creating.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            ssh
        }
        val job = launch { connector.connect(account, TransferCredentials(), 80, 24) }
        kotlinx.coroutines.yield()
        assertTrue(creating.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        release.countDown()
        assertTrue(ssh.closed.await(5, TimeUnit.SECONDS))
        assertNull(ssh.password)
    }

    @Test fun `a connection completed just before cancellation is still closed`() = runBlocking {
        val dispatch = QueuedDispatcher()
        var delivered = false
        val job = launch(dispatch) {
            SshTerminalConnector { ssh }.connect(account, TransferCredentials(), 80, 24)
            delivered = true
        }
        dispatch.next().run()
        val resume = dispatch.next()
        job.cancel()
        resume.run()
        job.join()
        assertFalse(delivered)
        assertTrue(ssh.closed.await(5, TimeUnit.SECONDS))
        assertTrue(ssh.channelClosed.await(5, TimeUnit.SECONDS))
    }

    @Test fun `folder launch requests an interactive shell in the selected directory`() = runBlocking {
        val shell = java.io.File.createTempFile("luna-test-shell", ".sh")
        val directory = java.nio.file.Files.createTempDirectory("luna-terminal").toFile()
        try {
            shell.writeText("#!/bin/sh\nprintf '%s' \"\$PWD\"\n")
            assertTrue(shell.setExecutable(true))
            val target = java.io.File(directory, "a folder/it's \"here\"\n\$value `pwd`").apply { mkdirs() }
            SshTerminalConnector { ssh }.connect(account, TransferCredentials(), 80, 24, target.path).close()
            val process = ProcessBuilder("/bin/sh", "-c", ssh.command!!).apply { environment()["SHELL"] = shell.path }.start()
            assertEquals(target.path, process.inputStream.readBytes().decodeToString())
            assertEquals(0, process.waitFor())
            assertEquals(0, ssh.shellRequests)
        } finally {
            shell.delete()
            directory.deleteRecursively()
        }
    }

    @Test fun `quoted paths preserve spaces quotes newlines and shell expressions as literal text`() {
        val value = "  /a'b/\n\$(printf injected); `printf injected` \" *  "
        val process = ProcessBuilder("/bin/sh", "-c", "printf '%s' ${shellQuote(value)}").start()
        assertEquals(value, process.inputStream.readBytes().decodeToString())
        assertEquals(0, process.waitFor())
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.put(block) }
        fun next(): Runnable = checkNotNull(tasks.poll(5, TimeUnit.SECONDS))
    }

    private class FakeSsh : SSHClient() {
        val hostKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2_048) }.generateKeyPair().public
        val received = byteArrayOf(27, 91, 50, 74, -30, -104, -125, 13, 10)
        val sent = ByteArrayOutputStream()
        val closed = CountDownLatch(1)
        val channelClosed = CountDownLatch(1)
        val authenticating = CountDownLatch(1)
        val authenticationFinished = CountDownLatch(1)
        var connectedSocket: Socket? = null
        var keepAliveWhenConnected = false
        var timeoutWhenShellStarts: Int? = null
        var password: String? = null
        var rejectPty = false
        var blockAuthentication = false
        var shellRequests = 0
        var command: String? = null
        var pty: List<Any?>? = null
        var dimensions: List<Any?>? = null
        private lateinit var verifier: HostKeyVerifier
        private val session = Proxy.newProxyInstance(Session::class.java.classLoader,
            arrayOf(Session::class.java, Session.Shell::class.java, Session.Command::class.java)) { proxy, method, args ->
            when (method.name) {
                "allocatePTY" -> { if (rejectPty) throw TransportException("secret"); pty = args.toList(); null }
                "startShell" -> { timeoutWhenShellStarts = connectedSocket!!.soTimeout; shellRequests++; proxy }
                "exec" -> { timeoutWhenShellStarts = connectedSocket!!.soTimeout; command = args[0] as String; proxy }
                "getInputStream" -> ByteArrayInputStream(received)
                "getErrorStream" -> ByteArrayInputStream("diagnostic".toByteArray())
                "getOutputStream" -> sent
                "getExitStatus" -> 7
                "changeWindowDimensions" -> { dimensions = args.toList(); null }
                "close" -> { channelClosed.countDown(); null }
                else -> null
            }
        } as Session

        override fun addHostKeyVerifier(verifier: HostKeyVerifier) { this.verifier = verifier }
        override fun connect(hostname: String, port: Int) {
            keepAliveWhenConnected = connection.keepAlive.isEnabled
            connectedSocket = socketFactory.createSocket().also { it.soTimeout = timeout }
            if (!verifier.verify(hostname, port, hostKey)) throw IOException("Host key rejected")
        }
        override fun getSocket(): Socket? = connectedSocket
        override fun authPassword(username: String, password: CharArray) {
            this.password = password.concatToString()
            authenticating.countDown()
            try {
                if (blockAuthentication) {
                    check(closed.await(5, TimeUnit.SECONDS))
                    throw IOException("closed")
                }
            } finally { authenticationFinished.countDown() }
        }
        override fun startSession(): Session = session
        override fun newSFTPClient(): SFTPClient = throw AssertionError("A shell must not require SFTP")
        override fun close() { closed.countDown() }
    }
}
