package com.lunaexplorer.app.storage

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class IncomingStorageProviderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val resolver get() = shadowOf(context.contentResolver)
    private val uri: Uri = Uri.parse("content://${FakeShareProvider.AUTHORITY}/attachments/7")
    private val bytes = "hello, world".toByteArray()
    private lateinit var provider: IncomingStorageProvider

    @Before fun share() {
        FakeShareProvider.reset()
        Robolectric.buildContentProvider(FakeShareProvider::class.java).create(FakeShareProvider.AUTHORITY)
        resolver.registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        provider = IncomingStorageProvider(context)
    }

    private suspend fun failing(block: suspend () -> Any?): StorageException =
        try { block(); throw AssertionError("Expected a storage error") } catch (error: StorageException) { error }

    @Test fun `stat takes the name, size and type the sender reports`() = runTest {
        FakeShareProvider.name = "notes.txt"
        FakeShareProvider.size = 12
        FakeShareProvider.type = "text/plain"

        val entry = provider.stat(provider.referenceTo(uri, "application/octet-stream"))

        assertEquals("notes.txt", entry.name)
        assertEquals(12L, entry.size)
        assertEquals("text/plain", entry.mimeType)
        assertFalse(entry.directory)
        assertEquals(setOf(Capability.READ), entry.capabilities)
    }

    @Test fun `without a reported type the declared one counts, then the name's extension`() = runTest {
        FakeShareProvider.name = "scan.png"
        assertEquals("image/webp", provider.stat(provider.referenceTo(uri, "image/webp")).mimeType)
        assertEquals("A wildcard from the sender says nothing", "image/png",
            provider.stat(provider.referenceTo(uri, "image/*")).mimeType)
        assertEquals("image/png", provider.stat(provider.referenceTo(uri)).mimeType)

        FakeShareProvider.name = null
        val unnamed = provider.stat(provider.referenceTo(uri))
        assertEquals("The URI's last segment stands in for a missing name", "7", unnamed.name)
        assertEquals("application/octet-stream", unnamed.mimeType)
        assertNull(unnamed.size)
    }

    @Test fun `openRead streams the sender's bytes and a seekable descriptor gives a channel`() = runTest {
        FakeShareProvider.file = temporary.newFile("attachment").apply { writeBytes(bytes) }
        val ref = provider.referenceTo(uri)

        assertArrayEquals(bytes, provider.openRead(ref).use { it.readBytes() })
        val channel = provider.openChannel(ref)
        assertNotNull("A file-backed descriptor can seek", channel)
        channel!!.use {
            assertEquals(bytes.size.toLong(), it.size())
            it.position(7)
            val buffer = ByteBuffer.allocate(5)
            while (buffer.hasRemaining() && it.read(buffer) > 0) { }
            assertEquals("world", String(buffer.array()))
        }
        assertEquals("Sharing passes on the sender's own URI", uri, provider.contentUri(ref))
    }

    @Test fun `a sender that only streams gives no channel but still reads`() = runTest {
        val ref = provider.referenceTo(uri)
        assertNull(provider.openChannel(ref))
        assertArrayEquals(bytes, provider.openRead(ref).use { it.readBytes() })
    }

    @Test fun `a refused grant is a permission error before any viewer opens`() = runTest {
        val refused = Uri.parse("content://${FakeShareProvider.AUTHORITY}/attachments/8")
        resolver.registerInputStreamSupplier(refused) { throw SecurityException("no grant") }
        assertEquals(StorageError.PERMISSION, failing { provider.stat(provider.referenceTo(refused)) }.reason)
        assertEquals(StorageError.PERMISSION, failing { provider.openRead(provider.referenceTo(refused)) }.reason)
    }

    @Test fun `it has no roots and refuses every change`() = runTest {
        val ref = provider.referenceTo(uri)
        assertTrue(provider.roots().isEmpty())
        assertFalse(provider.isDescendant(ref, ref))
        val changes = listOf<suspend () -> Any?>(
            { provider.create(ref, "new.txt", directory = false) },
            { provider.rename(ref, "renamed.txt") },
            { provider.delete(ref) },
            { provider.openWrite(ref) },
            { provider.commit(ref, ref, "published.txt") },
        )
        for (change in changes) assertEquals(StorageError.UNSUPPORTED, failing(change).reason)
    }

    @Test fun `a file URI outside Luna's roots is read from the filesystem`() = runTest {
        val file = temporary.newFile("loose.txt").apply { writeBytes(bytes) }
        val ref = provider.referenceTo(Uri.fromFile(file))

        val entry = provider.stat(ref)
        assertEquals("loose.txt", entry.name)
        assertEquals(bytes.size.toLong(), entry.size)
        assertEquals("text/plain", entry.mimeType)
        assertArrayEquals(bytes, provider.openRead(ref).use { it.readBytes() })
        assertNotNull(provider.openChannel(ref)?.also { it.close() })
        assertNull("Only the sender's own URI carries a grant to pass on", provider.contentUri(ref))

        file.delete()
        assertEquals(StorageError.NOT_FOUND, failing { provider.stat(ref) }.reason)
    }
}
