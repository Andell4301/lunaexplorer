package com.lunaexplorer.app.storage

import com.lunaexplorer.app.model.NetworkThumbnails
import com.lunaexplorer.app.storage.smb.FakeSmbConnector
import com.lunaexplorer.app.storage.smb.SmbAccount
import com.lunaexplorer.app.storage.smb.SmbStorageProvider
import com.lunaexplorer.core.ArchiveProvider
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.ProviderRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class NetworkThumbnailTest {
    @get:Rule val temporary = TemporaryFolder()
    private val account = SmbAccount(id = "nas", name = "NAS", host = "nas.local", guest = true)
    private val connector = FakeSmbConnector()
    private val smb = SmbStorageProvider({ listOf(account) }, { it }, connector)
    private var registry = ProviderRegistry(listOf(smb))
    private val inside = ArchiveProvider({ registry })
    private val onShare = NodeRef("smb", "nas:media:picture.jpg")

    private fun loader(): ThumbnailLoader {
        val local = LocalStorageProvider(listOf(LocalRoot("workspace", "workspace", temporary.root)), probe = PathProbe.OF_FILESYSTEM)
        registry = ProviderRegistry(listOf(local, smb, inside))
        return ThumbnailLoader(RuntimeEnvironment.getApplication(), registry)
    }

    private fun picture(ref: NodeRef, megabytes: Long) = Entry(ref, "picture.jpg", directory = false,
        size = megabytes shl 20, mimeType = "image/jpeg", capabilities = setOf(Capability.READ))

    @Test fun `large and unknown network files are eligible until thumbnails are off`() {
        val loader = loader()
        assertTrue(loader.supports(picture(onShare, 10), NetworkThumbnails.MB_25.bytes))
        assertTrue(loader.supports(picture(onShare, 4096), NetworkThumbnails.MB_25.bytes))
        assertTrue(loader.supports(picture(onShare, 1).copy(size = null), NetworkThumbnails.MB_25.bytes))
        assertFalse("Off means none at all", loader.supports(picture(onShare, 1), NetworkThumbnails.OFF.bytes))
        assertTrue(loader.supports(picture(onShare, 4096), NetworkThumbnails.ANY.bytes))
    }

    @Test fun `the limit is about the network, so a large picture on this device still has one`() {
        val loader = loader()
        assertTrue(loader.supports(picture(NodeRef("local", "workspace:/picture.jpg"), 300), NetworkThumbnails.MB_25.bytes))
        assertTrue(loader.supports(picture(NodeRef("local", "workspace:/picture.jpg"), 300), NetworkThumbnails.OFF.bytes))
    }

    @Test fun `a picture inside an archive on a share is held to the same limit`() = runBlocking {
        val loader = loader()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { it.putNextEntry(ZipEntry("picture.jpg")); it.write(ByteArray(16)); it.closeEntry() }
        connector.share.put("pictures.zip", out.toByteArray())
        val root = inside.open(NodeRef("smb", "nas:media:pictures.zip"), "pictures.zip")
        val member = NodeRef(root.provider, root.key + "picture.jpg")

        assertTrue(loader.supports(picture(member, 4096), NetworkThumbnails.MB_25.bytes))
        assertFalse(loader.supports(picture(member, 10), NetworkThumbnails.OFF.bytes))
        assertEquals("And it is that share's provider whose limit applies", "smb", loader.networkOf(member))
    }

    @Test fun `each file is held to the limit of the provider it is on`() {
        val loader = loader()
        assertEquals("smb", loader.networkOf(onShare))
        assertNull(loader.networkOf(NodeRef("local", "workspace:/picture.jpg")))
    }

    private fun packageEntry(size: Long?) = Entry(NodeRef("smb", "nas:media:test.apk"), "test.apk",
        directory = false, size = size, mimeType = "application/vnd.android.package-archive",
        capabilities = setOf(Capability.READ))

    @Test fun `package staging shares the read allowance and a higher allowance retries without leaving copies`() = runBlocking {
        val loader = loader()
        connector.share.put("test.apk", ByteArray(8192))
        val entry = packageEntry(null)
        assertNull(loader.load(entry, 48, 128))
        assertEquals(128L, connector.share.bytesServed)
        assertEquals(0, connector.share.readersOpen)
        assertNull(loader.load(entry, 48, 128))
        assertEquals("Same refused preview is not downloaded again", 128L, connector.share.bytesServed)
        assertNull(loader.load(entry, 48, 256))
        assertEquals("A higher allowance tries again", 384L, connector.share.bytesServed)
        assertEquals(0, connector.share.readersOpen)
        val leftovers = RuntimeEnvironment.getApplication().cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith("thumbnail-stage-") }
        assertTrue("Partial package copies are deleted", leftovers.isEmpty())
    }

    @Test fun `a package known to require more than the allowance is skipped before opening it`() = runBlocking {
        val loader = loader()
        connector.share.put("test.apk", ByteArray(8192))
        assertNull(loader.load(packageEntry(8192), 48, 128))
        assertEquals(0, connector.share.readersOpened)
        assertEquals(0L, connector.share.bytesServed)
    }

    @Test fun `PDF staging stops at the read allowance and removes its partial file`() = runBlocking {
        val loader = loader()
        connector.share.put("test.pdf", ByteArray(8192))
        val entry = Entry(NodeRef("smb", "nas:media:test.pdf"), "test.pdf", directory = false,
            size = null, mimeType = "application/pdf", capabilities = setOf(Capability.READ))
        assertNull(loader.load(entry, 48, 128))
        assertEquals(128L, connector.share.bytesServed)
        assertEquals(0, connector.share.readersOpen)
        val leftovers = RuntimeEnvironment.getApplication().cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith("thumbnail-stage-") }
        assertTrue(leftovers.isEmpty())
    }
}
