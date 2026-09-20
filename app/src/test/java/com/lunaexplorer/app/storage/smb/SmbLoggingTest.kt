package com.lunaexplorer.app.storage.smb

import com.lunaexplorer.app.debug.DebugLog
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

class SmbLoggingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val account = SmbAccount(id = "nas", name = "NAS", host = "nas.local", username = "me")
    private val connector = FakeSmbConnector()
    private val provider = SmbStorageProvider({ listOf(account) }, { it.copy(password = PASSWORD) }, connector)
    private val media = NodeRef("smb", "nas:media:")
    private var previous: DebugLog? = null
    private lateinit var log: DebugLog
    private val text get() = log.snapshot().text

    @Before fun install() {
        previous = DebugLog.installed
        log = DebugLog(temporary.root).apply { setRecording(true) }
        DebugLog.installed = log
    }

    @After fun restore() { log.close(); DebugLog.installed = previous }

    @Test fun `a request is logged with where it went and how long it took, and why a session was made for it`() = runBlocking {
        connector.share.mkdir("films")
        provider.list(NodeRef("smb", "nas:media:films")).toList()

        assertTrue(text, text.contains("Signing in to NAS: no session yet"))
        assertTrue("Asked as well as answered", text.contains("list NAS/media/films…"))
        assertTrue(text, text.contains("Opened NAS/media in "))
        assertTrue(text, Regex("""list NAS/media/films · \d+ ms""").containsMatchIn(text))
    }

    @Test fun `a failure is logged with its reason and what the connector threw, and keeps that as its cause`() = runBlocking {
        provider.stat(media)
        connector.share.failNext = SmbFailure(StorageError.DISCONNECTED, "The connection to the server was lost")

        val thrown = try { provider.stat(media); fail("Expected a failure"); null } catch (error: StorageException) { error }

        assertTrue("The cause is kept: ${thrown?.cause}", thrown?.cause is SmbFailure)
        assertTrue(text, Regex("""stat NAS/media failed after \d+ ms: DISCONNECTED · The connection to the server was lost""").containsMatchIn(text))
        assertTrue(text, text.contains("Dropped the connection to NAS/media after a failure on it"))
        assertTrue("With the failure's trace", text.contains("SmbFailure: The connection to the server was lost"))
    }

    @Test fun `a read that finds its connection gone says so, and that it opened the file again`() = runBlocking {
        connector.share.put("clip.bin", ByteArray(200_000))
        val channel = provider.openChannel(NodeRef("smb", "nas:media:clip.bin"))!!
        channel.read(ByteBuffer.allocate(1000))
        channel.position(150_000)
        connector.share.failNext = SmbFailure(StorageError.DISCONNECTED, "The connection to the server was lost")

        channel.read(ByteBuffer.allocate(1000))
        channel.close()

        // The logged offset is the cache block's, not the position asked for.
        assertTrue(text, Regex("""Read of NAS/media/clip.bin at \d+ found its connection gone after \d+ ms; opening the file again""").containsMatchIn(text))
    }

    @Test fun `the password is never in the log`() = runBlocking {
        connector.share.mkdir("films")
        provider.list(NodeRef("smb", "nas:media:films")).toList()
        connector.refuse = SmbFailure(StorageError.AUTH, "The server rejected the name or password")
        provider.disconnect(account.id)
        try { provider.stat(media) } catch (_: StorageException) {}

        assertTrue("Something was logged", text.contains("NAS"))
        assertFalse("The password leaked into the log:\n$text", text.contains(PASSWORD))
        assertEquals("Signed in twice", 2, connector.connections)
    }

    private companion object {
        const val PASSWORD = "hunter2-sentinel-7731"
    }
}
