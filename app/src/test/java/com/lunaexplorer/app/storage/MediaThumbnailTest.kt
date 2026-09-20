package com.lunaexplorer.app.storage

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import com.lunaexplorer.app.storage.smb.FakeSmbConnector
import com.lunaexplorer.app.storage.smb.SmbAccount
import com.lunaexplorer.app.storage.smb.SmbStorageProvider
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.ProviderRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaMetadataRetriever

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ReadingThumbnailRetriever::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaThumbnailTest {
    private val connector = FakeSmbConnector()
    private val account = SmbAccount(id = "nas", name = "NAS", host = "nas.local", guest = true)
    private val provider = SmbStorageProvider({ listOf(account) }, { it }, connector)
    private val entry = Entry(NodeRef("smb", "nas:media:movie.mp4"), "movie.mp4", directory = false,
        size = 42, mimeType = "video/mp4", capabilities = setOf(Capability.READ))

    @Test fun `media previews seek through a budgeted source without downloading the whole file`() = runBlocking {
        connector.share.put("movie.mp4", ByteArray(4 * 1024 * 1024).apply { this[0] = 10; this[lastIndex] = 20 })
        val loader = ThumbnailLoader(RuntimeEnvironment.getApplication(), ProviderRegistry(listOf(provider)))
        try {
            val bitmap = loader.load(entry, 32, 512 * 1024)
            assertNotNull(bitmap)
            assertEquals(Color.rgb(10, 20, 0), requireNotNull(bitmap).getPixel(0, 0))
            assertTrue(connector.share.bytesServed in 1..512 * 1024L)
            assertEquals("The decoder releases its provider handle", 0, connector.share.readersOpen)
        } finally {
            provider.disconnect(account.id)
        }
    }

    @Test fun `exhausting the media read allowance closes the handle and a larger allowance retries`() = runBlocking {
        connector.share.put("movie.mp4", ByteArray(4 * 1024 * 1024).apply { this[0] = 10; this[lastIndex] = 20 })
        val loader = ThumbnailLoader(RuntimeEnvironment.getApplication(), ProviderRegistry(listOf(provider)))
        try {
            assertNull(loader.load(entry, 32, 1))
            assertEquals(1L, connector.share.bytesServed)
            assertEquals(0, connector.share.readersOpen)
            assertNotNull(loader.load(entry, 32, 512 * 1024))
            assertEquals(0, connector.share.readersOpen)
        } finally {
            provider.disconnect(account.id)
        }
    }
}

@Implements(MediaMetadataRetriever::class)
class ReadingThumbnailRetriever : ShadowMediaMetadataRetriever() {
    private var source: MediaDataSource? = null

    @Implementation
    override fun setDataSource(source: MediaDataSource) { this.source = source }

    @Implementation
    override fun getScaledFrameAtTime(timeUs: Long, option: Int, dstWidth: Int, dstHeight: Int): Bitmap? {
        val input = source ?: return null
        val bytes = ByteArray(4) { 100 }
        assertEquals(0, input.readAt(0, bytes, 0, 0))
        assertEquals(1, input.readAt(input.size - 1, bytes, 2, 1))
        assertEquals(-1, input.readAt(input.size, bytes, 0, 1))
        assertEquals(1, input.readAt(0, bytes, 1, 1))
        assertEquals(100.toByte(), bytes[0])
        assertEquals(100.toByte(), bytes[3])
        return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(bytes[1].toInt(), bytes[2].toInt(), 0))
        }
    }
}
