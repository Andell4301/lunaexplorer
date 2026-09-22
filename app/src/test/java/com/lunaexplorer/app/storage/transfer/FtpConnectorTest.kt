package com.lunaexplorer.app.storage.transfer

import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class FtpConnectorTest {
    private val account = TransferAccount(protocol = TransferProtocol.FTP, host = "files.example", rootPath = " /exact root/ ",
        username = " user ", security = FtpSecurity.PLAIN)

    @Test fun `connection preserves credentials and root and selects binary passive transfers`() {
        val ftp = FakeFtp()
        FtpConnector { ftp }.connect(account, TransferCredentials(password = " password ")).use {
            assertEquals(" user " to " password ", ftp.loggedIn)
            assertEquals(listOf(" /exact root/ "), ftp.folders)
            assertEquals(FTP.BINARY_FILE_TYPE, ftp.type)
            assertEquals(FTPClient.PASSIVE_LOCAL_DATA_CONNECTION_MODE, ftp.dataConnectionMode)
        }
        assertTrue(ftp.closed)
    }

    @Test fun `listing falls back only for unsupported MLSD and preserves literal file names`() {
        val ftp = FakeFtp().apply { machineSupported = false }
        FtpConnector { ftp }.connect(account, TransferCredentials()).use { client ->
            assertEquals(listOf(" file[*] "), client.list("").map { it.name })
            assertEquals(" file[*] ", client.stat(" file[*] ")?.name)
            assertEquals(1, ftp.machineAttempts)
            assertEquals(2, ftp.legacyAttempts)
        }
    }

    @Test fun `listing failure cannot become an empty folder`() {
        val ftp = FakeFtp().apply { listingReply = 550 }
        FtpConnector { ftp }.connect(account, TransferCredentials()).use { client ->
            assertEquals(StorageError.PERMISSION, assertThrows(StorageException::class.java) { client.list("") }.reason)
            assertEquals(0, ftp.legacyAttempts)
        }
    }

    @Test fun `download reads exact requests and checks final server completion`() {
        val ftp = FakeFtp().apply { transferComplete = false }
        FtpConnector { ftp }.connect(account, TransferCredentials()).use { client ->
            client.openReader(" file[*] ").use { reader ->
                val bytes = ByteArray(3)
                assertEquals(2, reader.read(0, bytes, 0, 2))
                assertEquals(1, reader.read(2, bytes, 2, 1))
                assertEquals("abc", bytes.decodeToString())
                assertEquals("./ file[*] ", ftp.retrieved)
                assertEquals(StorageError.OFFLINE, assertThrows(StorageException::class.java) {
                    reader.read(3, bytes, 0, 1)
                }.reason)
            }
        }
        assertEquals(1, ftp.completions)
        assertTrue(ftp.closed)
    }

    @Test fun `early close releases data without waiting for transfer completion`() {
        val ftp = FakeFtp()
        FtpConnector { ftp }.connect(account, TransferCredentials()).use { client ->
            client.openReader(" file[*] ").use { it.read(0, ByteArray(1), 0, 1) }
        }
        assertTrue(ftp.dataClosed)
        assertTrue(ftp.closed)
        assertEquals(0, ftp.completions)
    }

    @Test fun `command injection and traversal never reach FTP commands`() {
        val ftp = FakeFtp()
        assertThrows(StorageException::class.java) {
            FtpConnector { ftp }.connect(account.copy(username = "user\r\nDELE file"), TransferCredentials())
        }
        assertNull(ftp.loggedIn)
        FtpConnector { ftp }.connect(account, TransferCredentials()).use { client ->
            assertThrows(StorageException::class.java) { client.list("../outside") }
            assertThrows(StorageException::class.java) { client.openReader("name\nQUIT") }
        }
        assertEquals(listOf(" /exact root/ "), ftp.folders)
    }

    @Test fun `server exceptions cannot expose credentials`() {
        val ftp = FakeFtp().apply { failLogin = true }
        val error = assertThrows(StorageException::class.java) {
            FtpConnector { ftp }.connect(account, TransferCredentials(password = "private-password"))
        }
        assertFalse(error.toString().contains("private-password"))
        assertNull(error.cause)
        assertTrue(ftp.closed)
    }

    @Test fun `symbolic link folders are not followed`() {
        val ftp = FakeFtp().apply { entries = arrayOf(file("link", FTPFile.SYMBOLIC_LINK_TYPE)) }
        FtpConnector { ftp }.connect(account, TransferCredentials()).use { client ->
            assertTrue(client.list("").single().link)
            assertThrows(StorageException::class.java) { client.list("link") }
            assertThrows(StorageException::class.java) { client.openReader("link") }
        }
        assertFalse(ftp.folders.contains("./link"))
    }

    @Test fun `uploads finish before publishing and existing destinations are refused`() {
        val ftp = FakeFtp()
        FtpConnector { ftp }.connect(account, TransferCredentials()).use { client ->
            client.createFile(" stage ")
            client.write(" stage ").use { it.write("payload".toByteArray()) }
            assertEquals("payload", ftp.uploaded.getValue(" stage ").decodeToString())
            assertEquals(2, ftp.completions)
            assertThrows(StorageException::class.java) { client.write(" stage ") }
            assertThrows(StorageException::class.java) { client.createFile(" file[*] ") }
            assertThrows(StorageException::class.java) { client.rename(" stage ", " file[*] ") }
            assertEquals(0, ftp.renames.size)
            client.rename(" stage ", " final ")
            assertEquals(listOf("/actual/root/ stage " to "/actual/root/ final "), ftp.renames)
            client.deleteFile(" final ")
            assertNull(client.stat(" final "))
        }
    }

    @Test fun `an upload rejected at completion is reported without replay`() {
        val ftp = FakeFtp().apply { transferComplete = false }
        FtpConnector { ftp }.connect(account, TransferCredentials()).use { client ->
            assertEquals(StorageError.OFFLINE, assertThrows(StorageException::class.java) { client.createFile("stage") }.reason)
        }
        assertEquals(1, ftp.completions)
        assertEquals(1, ftp.uploadAttempts)
    }

    @Test fun `folders are created and removed without recursive server deletion`() {
        val ftp = FakeFtp()
        FtpConnector { ftp }.connect(account, TransferCredentials()).use { client ->
            client.mkdir("folder")
            assertTrue(client.stat("folder")!!.directory)
            assertThrows(StorageException::class.java) { client.deleteFolder("folder") }
            assertTrue(ftp.removedFolders.isEmpty())
        }
    }

    private class FakeFtp : FTPClient() {
        var responseCode = 220
        var loggedIn: Pair<String, String>? = null
        var closed = false
        var type = -1
        val folders = mutableListOf<String>()
        var workingFolder = "/actual/root"
        var machineSupported = true
        var listingReply = 226
        var machineAttempts = 0
        var legacyAttempts = 0
        var completions = 0
        var transferComplete = true
        var retrieved: String? = null
        var dataClosed = false
        var failLogin = false
        var entries = arrayOf(file(" file[*] "))
        val uploaded = mutableMapOf<String, ByteArray>()
        var uploadAttempts = 0
        val renames = mutableListOf<Pair<String, String>>()
        val removedFolders = mutableListOf<String>()

        override fun connect(host: String, port: Int) { responseCode = 220 }
        override fun setSoTimeout(timeout: Int) { }
        override fun getReplyCode() = responseCode
        override fun login(username: String, password: String): Boolean {
            if (failLogin) throw IOException(password)
            loggedIn = username to password
            return true
        }
        override fun changeWorkingDirectory(path: String): Boolean {
            folders += path
            workingFolder = if (path.startsWith("./")) workingFolder + "/" + path.removePrefix("./") else "/actual/root"
            return true
        }
        override fun printWorkingDirectory() = workingFolder
        override fun getSystemType() = "UNIX"
        override fun setFileType(fileType: Int): Boolean { type = fileType; return true }
        override fun mlistDir(): Array<FTPFile> {
            machineAttempts++
            responseCode = if (machineSupported) listingReply else 500
            return if (responseCode == 226) entries else emptyArray()
        }
        override fun listFiles(): Array<FTPFile> { legacyAttempts++; responseCode = listingReply; return entries }
        override fun retrieveFileStream(path: String): ByteArrayInputStream {
            retrieved = path
            return object : ByteArrayInputStream("abc".toByteArray()) {
                override fun close() { dataClosed = true; super.close() }
            }
        }
        override fun storeFileStream(path: String): ByteArrayOutputStream {
            uploadAttempts++
            val name = path.substringAfterLast('/')
            return object : ByteArrayOutputStream() {
                override fun close() {
                    uploaded[name] = toByteArray()
                    entries = entries.filterNot { it.name == name }.toTypedArray() + file(name).apply { size = size().toLong() }
                }
            }
        }
        override fun rename(from: String, to: String): Boolean {
            renames += from to to
            entries.first { it.name == from.substringAfterLast('/') }.name = to.substringAfterLast('/')
            return true
        }
        override fun makeDirectory(path: String): Boolean {
            entries += file(path.substringAfterLast('/'), FTPFile.DIRECTORY_TYPE)
            return true
        }
        override fun deleteFile(path: String): Boolean {
            entries = entries.filterNot { it.name == path.substringAfterLast('/') }.toTypedArray()
            return true
        }
        override fun removeDirectory(path: String): Boolean { removedFolders += path; return true }
        override fun completePendingCommand(): Boolean {
            completions++
            responseCode = if (transferComplete) 226 else 426
            return transferComplete
        }
        override fun disconnect() { closed = true }
    }

    companion object {
        private fun file(name: String, type: Int = FTPFile.FILE_TYPE) = FTPFile().apply {
            this.name = name
            this.type = type
            size = 3
            rawListing = "type=file;size=3; $name"
        }
    }
}
