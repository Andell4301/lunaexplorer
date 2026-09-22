package com.lunaexplorer.app.storage.transfer

import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import kotlinx.coroutines.CancellationException
import net.schmizz.concurrent.Promise
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.LoggerFactory
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.SessionFactory
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.PacketType
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPEngine
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.sftp.SFTPPacket
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.Proxy
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.EncryptedPrivateKeyInfo
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class SshjConnectorTest {
    private lateinit var server: FakeSftp
    private lateinit var ssh: FakeSsh
    private lateinit var account: TransferAccount

    @Before fun setup() {
        configureSshCrypto()
        server = FakeSftp()
        ssh = FakeSsh(server)
        account = TransferAccount(host = "sftp.example", username = "user", rootPath = "/vault",
            hostKeyFingerprint = SshHostVerifier.fingerprint(ssh.hostKey))
    }

    @Test fun `a new host key stops before any authentication`() {
        val required = try {
            SshjConnector { ssh }.connect(account.copy(hostKeyFingerprint = ""), TransferCredentials(password = "secret"))
            throw AssertionError("Expected a host key request")
        } catch (required: HostKeyRequired) { required }

        assertEquals(account.hostKeyFingerprint, required.fingerprint)
        assertEquals("ssh-rsa", required.algorithm)
        assertFalse(required.changed)
        assertNull(ssh.password)
        assertNull(ssh.privateKey)
        assertTrue(ssh.closed)
    }

    @Test fun `a changed host key stops before any authentication`() {
        val required = try {
            SshjConnector { ssh }.connect(account.copy(hostKeyFingerprint = "SHA256:different"), TransferCredentials(password = "secret"))
            throw AssertionError("Expected a host key request")
        } catch (required: HostKeyRequired) { required }

        assertTrue(required.changed)
        assertNull(ssh.password)
        assertTrue(ssh.closed)
    }

    @Test fun `a pinned host authenticates with the selected password method`() {
        connect(TransferCredentials(password = " secret ", privateKey = "an unused saved key")).close()

        assertEquals(" secret ", ssh.password)
        assertNull(ssh.privateKey)
        assertTrue(ssh.closed)
        assertTrue(server.closed)
    }

    @Test fun `a PEM private key is loaded from memory for public key authentication`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2_048) }.generateKeyPair()
        account = account.copy(authentication = TransferAuthentication.PRIVATE_KEY)

        connect(TransferCredentials(password = "unused", privateKey = pem("PRIVATE KEY", pair.private.encoded))).close()

        assertArrayEquals(pair.private.encoded, ssh.privateKey!!.encoded)
        assertNull(ssh.password)
    }

    @Test fun `a passphrase opens an encrypted PEM key without falling back to password authentication`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2_048) }.generateKeyPair()
        val algorithm = "PBEWithSHA1AndDESede"
        val key = SecretKeyFactory.getInstance(algorithm).generateSecret(PBEKeySpec("key secret".toCharArray()))
        val cipher = Cipher.getInstance(algorithm).apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = EncryptedPrivateKeyInfo(cipher.parameters, cipher.doFinal(pair.private.encoded))
        account = account.copy(authentication = TransferAuthentication.PRIVATE_KEY)

        connect(TransferCredentials(privateKey = pem("ENCRYPTED PRIVATE KEY", encrypted.encoded), passphrase = "key secret")).close()

        assertArrayEquals(pair.private.encoded, ssh.privateKey!!.encoded)
        assertNull(ssh.password)
    }

    @Test fun `an OpenSSH Ed25519 key signs with the bundled crypto provider`() {
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        account = account.copy(authentication = TransferAuthentication.PRIVATE_KEY)

        connect(TransferCredentials(privateKey = pem("OPENSSH PRIVATE KEY", OpenSSHPrivateKeyUtil.encodePrivateKey(privateKey)))).close()

        val signature = SecurityUtils.getSignature("Ed25519").apply { initSign(ssh.privateKey) }
        signature.update(byteArrayOf(1, 2, 3))
        val signed = signature.sign()
        signature.initVerify(ssh.publicKey)
        signature.update(byteArrayOf(1, 2, 3))
        assertTrue(signature.verify(signed))
        assertNull(ssh.password)
    }

    @Test fun `a malformed private key reports no secret and never falls back to the password`() {
        account = account.copy(authentication = TransferAuthentication.PRIVATE_KEY)

        val error = failure { connect(TransferCredentials(password = "password secret", privateKey = "private key secret")) }

        assertEquals(StorageError.AUTH, error.reason)
        assertFalse(error.toString().contains("private key secret"))
        assertNull(error.cause)
        assertNull(ssh.password)
        assertTrue(ssh.closed)
    }

    @Test fun `a reader requests only the requested offset and length`() {
        server.files["/vault/data"] = ByteArray(1_000) { it.toByte() }
        val into = ByteArray(20)

        connect().use { client ->
            client.openReader("data").use { reader ->
                assertEquals(13, reader.read(77, into, 2, 13))
                assertEquals(0, reader.read(300, into, 0, 0))
            }
        }

        assertArrayEquals(server.files.getValue("/vault/data").copyOfRange(77, 90), into.copyOfRange(2, 15))
        assertEquals(listOf(77L to 13), server.reads)
        assertEquals(1, server.closedFiles)
    }

    @Test fun `a sequential stream pipelines reads and preserves every byte`() {
        val bytes = ByteArray(1_000_019) { (it * 31).toByte() }
        server.files["/vault/data"] = bytes

        connect().use { client ->
            client.openStream("data").use { assertArrayEquals(bytes, it.readBytes()) }
        }

        assertTrue(server.maxPendingReads > 1)
        assertTrue(server.maxPendingReads <= 16)
        assertTrue(server.reads.all { it.second <= 32 * 1_024 })
        assertEquals(1, server.closedFiles)
    }

    @Test fun `inspecting a file uses the attributes already checked for links`() {
        server.files["/vault/data"] = byteArrayOf(1)

        connect().use { client ->
            server.stats.clear()
            assertEquals(1L, client.stat("data")!!.size)
            assertEquals(listOf("/vault", "/vault/data"), server.stats)
        }
    }

    @Test fun `small sequential reads share fetched blocks`() {
        val bytes = ByteArray(128 * 1_024) { it.toByte() }
        server.files["/vault/data"] = bytes

        connect().use { client ->
            client.openStream("data").use { stream ->
                for (index in 0 until 64 * 1_024) assertEquals(bytes[index].toInt() and 0xff, stream.read())
                assertTrue(server.reads.size < 32)
                assertArrayEquals(bytes.copyOfRange(64 * 1_024, bytes.size), stream.readBytes())
            }
        }

        assertEquals(1, server.closedFiles)
    }

    @Test fun `an exclusive create cannot replace a file which appears after the existence check`() {
        server.raceCreate = true

        connect().use { client -> assertEquals(StorageError.CONFLICT, failure { client.createFile("racing") }.reason) }

        assertEquals("preserved", server.files.getValue("/vault/racing").decodeToString())
    }

    @Test fun `renaming to an existing name preserves both files`() {
        server.files["/vault/source"] = "source".toByteArray()
        server.files["/vault/target"] = "target".toByteArray()

        connect().use { client -> assertEquals(StorageError.CONFLICT, failure { client.rename("source", "target") }.reason) }

        assertEquals("source", server.files.getValue("/vault/source").decodeToString())
        assertEquals("target", server.files.getValue("/vault/target").decodeToString())
        assertEquals(1, server.renames)
    }

    @Test fun `opening a nonempty write target does not truncate it`() {
        server.files["/vault/data"] = "preserved".toByteArray()

        connect().use { client -> assertEquals(StorageError.CONFLICT, failure { client.write("data") }.reason) }

        assertEquals("preserved", server.files.getValue("/vault/data").decodeToString())
        assertEquals(1, server.closedFiles)
    }

    @Test fun `writes append acknowledged chunks to an empty created file`() {
        val bytes = ByteArray(1_000_019) { (it * 17).toByte() }

        connect().use { client ->
            client.createFile("staging")
            client.write("staging").use { it.write(bytes) }
        }

        assertArrayEquals(bytes, server.files.getValue("/vault/staging"))
        assertTrue(server.maxPendingWrites > 1)
        assertTrue(server.maxPendingWrites <= 16)
        assertTrue(server.writePacketSizes.all { it <= 32 * 1_024 })
        assertEquals(0, server.pendingWrites)
        assertEquals(2, server.closedFiles)
    }

    @Test fun `closing a write checks pending acknowledgements and still closes the file on failure`() {
        server.files["/vault/staging"] = byteArrayOf()
        server.rejectWrite = true

        connect().use { client ->
            val stream = client.write("staging")
            stream.write(ByteArray(90_000))
            assertEquals(StorageError.PERMISSION, failure { stream.close() }.reason)
        }

        assertEquals(1, server.closedFiles)
    }

    @Test fun `symlinks can be inspected but cannot be read or traversed`() {
        server.links += "/vault/link"

        connect().use { client ->
            assertTrue(client.stat("link")!!.link)
            assertFalse(client.stat("link")!!.directory)
            assertEquals(StorageError.UNSUPPORTED, failure { client.openReader("link") }.reason)
            assertEquals(StorageError.UNSUPPORTED, failure { client.createFile("link/outside") }.reason)
            assertEquals(StorageError.INVALID_NAME, failure { client.createFile("../outside") }.reason)
        }

        assertTrue(server.files.isEmpty())
    }

    @Test fun `transport failures discard server text and nested causes`() {
        val error = failure { sshFailure<Unit> { throw IOException("password secret", IOException("private key secret")) } }

        assertEquals(StorageError.DISCONNECTED, error.reason)
        assertFalse(error.toString().contains("secret"))
        assertNull(error.cause)
    }

    @Test fun `a root redirected after connecting cannot expose files outside it`() {
        server.files["/vault/data"] = "outside".toByteArray()

        connect().use { client ->
            server.redirectRoot = true
            assertEquals(StorageError.UNSUPPORTED, failure { client.openReader("data") }.reason)
        }

        assertTrue(server.reads.isEmpty())
    }

    @Test fun `missing sizes stay unknown and special files are inaccessible leaves`() {
        server.metadata["/vault/unknown"] = FileAttributes.Builder().withType(FileMode.Type.REGULAR).build()
        server.metadata["/vault/pipe"] = FileAttributes.Builder().withType(FileMode.Type.FIFO_SPECIAL).build()

        connect().use { client ->
            assertNull(client.stat("unknown")!!.size)
            assertTrue(client.stat("pipe")!!.link)
            assertFalse(client.stat("pipe")!!.directory)
            assertEquals(StorageError.UNSUPPORTED, failure { client.openReader("pipe") }.reason)
        }
    }

    @Test fun `cancellation is never translated to a storage failure`() {
        val cancelled = CancellationException("cancelled")

        try {
            sshFailure<Unit> { throw cancelled }
            throw AssertionError("Expected cancellation")
        } catch (caught: CancellationException) { assertSame(cancelled, caught) }
    }

    private fun connect(credentials: TransferCredentials = TransferCredentials()): TransferClient =
        SshjConnector { ssh }.connect(account, credentials)

    private class FakeSsh(private val sftp: FakeSftp) : SSHClient() {
        val hostKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2_048) }.generateKeyPair().public
        private lateinit var verifier: HostKeyVerifier
        var password: String? = null
        var privateKey: PrivateKey? = null
        var publicKey: PublicKey? = null
        var closed = false

        override fun addHostKeyVerifier(verifier: HostKeyVerifier) { this.verifier = verifier }
        override fun connect(hostname: String, port: Int) {
            if (!verifier.verify(hostname, port, hostKey)) throw IOException("Host key rejected")
        }
        override fun authPassword(username: String, password: CharArray) { this.password = password.concatToString() }
        override fun authPublickey(username: String, vararg keyProviders: KeyProvider) {
            privateKey = keyProviders.single().private
            publicKey = keyProviders.single().public
        }
        override fun newSFTPClient(): SFTPClient = sftp
        override fun close() { closed = true }
    }

    private class FakeSftp : SFTPClient(fakeEngine()) {
        val files = HashMap<String, ByteArray>()
        val metadata = HashMap<String, FileAttributes>()
        val links = HashSet<String>()
        val reads = ArrayList<Pair<Long, Int>>()
        val stats = ArrayList<String>()
        val writePacketSizes = ArrayList<Int>()
        var maxPendingReads = 0
        var pendingReads = 0
        var maxPendingWrites = 0
        var pendingWrites = 0
        var rejectWrite = false
        var raceCreate = false
        var redirectRoot = false
        var renames = 0
        var closedFiles = 0
        var closed = false

        override fun canonicalize(path: String): String = if (redirectRoot && path == "/vault") "/outside" else path
        override fun lstat(path: String): FileAttributes {
            stats += path
            return when {
                path in metadata -> metadata.getValue(path)
                path == "/vault" -> attributes(FileMode.Type.DIRECTORY)
                path in links -> attributes(FileMode.Type.SYMLINK)
                path in files -> attributes(FileMode.Type.REGULAR, files.getValue(path).size.toLong())
                else -> throw SFTPException(StatusCode.NO_SUCH_FILE, "absent")
            }
        }

        override fun open(path: String, modes: Set<OpenMode>): RemoteFile {
            if (raceCreate && OpenMode.CREAT in modes) files[path] = "preserved".toByteArray()
            if (OpenMode.EXCL in modes && path in files) throw SFTPException(StatusCode.FILE_ALREADY_EXISTS, "exists")
            if (OpenMode.CREAT in modes) files.putIfAbsent(path, byteArrayOf())
            if (OpenMode.TRUNC in modes) files[path] = byteArrayOf()
            return object : RemoteFile(sftpEngine, path, byteArrayOf(1)) {
                override fun fetchAttributes(): FileAttributes = lstat(path)
                override fun read(offset: Long, into: ByteArray, at: Int, length: Int): Int {
                    reads += offset to length
                    val source = files.getValue(path)
                    if (offset >= source.size) return -1
                    val count = minOf(length, source.size - offset.toInt())
                    source.copyInto(into, at, offset.toInt(), offset.toInt() + count)
                    return count
                }
                override fun asyncRead(offset: Long, length: Int): Promise<Response, SFTPException> {
                    reads += offset to length
                    pendingReads++
                    maxPendingReads = maxOf(maxPendingReads, pendingReads)
                    return response {
                        pendingReads--
                        val source = files.getValue(path)
                        if (offset >= source.size) status(StatusCode.EOF) else {
                            val count = minOf(length, source.size - offset.toInt())
                            Response(SFTPPacket<Response>().apply {
                                putType(PacketType.DATA)
                                putUInt32(1)
                                putString(source, offset.toInt(), count)
                            }, 3)
                        }
                    }
                }
                override fun write(offset: Long, data: ByteArray, at: Int, length: Int) {
                    files[path] = files.getValue(path).copyOf(maxOf(files.getValue(path).size, offset.toInt() + length)).also {
                        data.copyInto(it, offset.toInt(), at, at + length)
                    }
                }
                override fun asyncWrite(offset: Long, data: ByteArray, at: Int, length: Int): Promise<Response, SFTPException> {
                    writePacketSizes += length + outgoingPacketOverhead
                    val bytes = data.copyOfRange(at, at + length)
                    pendingWrites++
                    maxPendingWrites = maxOf(maxPendingWrites, pendingWrites)
                    return response {
                        pendingWrites--
                        if (rejectWrite) status(StatusCode.PERMISSION_DENIED) else {
                            write(offset, bytes, 0, bytes.size)
                            status(StatusCode.OK)
                        }
                    }
                }
                override fun close() { closedFiles++ }
            }
        }

        override fun rename(source: String, target: String, flags: Set<RenameFlags>) {
            renames++
            if (target in files && RenameFlags.OVERWRITE !in flags) throw SFTPException(StatusCode.FILE_ALREADY_EXISTS, "exists")
            files[target] = files.remove(source)!!
        }

        override fun close() { closed = true }
    }

    private companion object {
        fun failure(block: () -> Unit): StorageException = try {
            block()
            throw AssertionError("Expected a storage failure")
        } catch (error: StorageException) { error }

        fun pem(type: String, bytes: ByteArray): String =
            "-----BEGIN $type-----\n${Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(bytes)}\n-----END $type-----\n"

        fun attributes(type: FileMode.Type, size: Long = 0): FileAttributes =
            FileAttributes.Builder().withType(type).withSize(size).build()

        fun status(code: StatusCode): Response = Response(SFTPPacket<Response>().apply {
            putType(PacketType.STATUS)
            putUInt32(1)
            putUInt32(code.code.toLong())
            putString("")
            putString("")
        }, 3)

        fun response(answer: () -> Response): Promise<Response, SFTPException> =
            object : Promise<Response, SFTPException>("test", SFTPException.chainer, LoggerFactory.DEFAULT) {
                override fun retrieve(timeout: Long, unit: TimeUnit): Response = answer()
            }

        fun fakeEngine(): SFTPEngine {
            val subsystem = proxy<Session.Subsystem> { name -> when (name) {
                "getInputStream" -> ByteArrayInputStream(byteArrayOf())
                "getOutputStream" -> ByteArrayOutputStream()
                "getRemoteMaxPacketSize" -> 32 * 1_024
                else -> null
            } }
            val session = proxy<Session> { name -> when (name) {
                "getLoggerFactory" -> LoggerFactory.DEFAULT
                "startSubsystem" -> subsystem
                else -> null
            } }
            val factory = proxy<SessionFactory> { name -> if (name == "startSession") session else null }
            return SFTPEngine(factory)
        }

        inline fun <reified T> proxy(crossinline answer: (String) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> answer(method.name) } as T
    }
}
