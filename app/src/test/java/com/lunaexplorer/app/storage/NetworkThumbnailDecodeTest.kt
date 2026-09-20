package com.lunaexplorer.app.storage

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NetworkThumbnailDecodeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `provider image streams retain EXIF orientation within the read allowance`() = runBlocking {
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAIAAAB7QOjdAAAAK2VYSWZJSSoACAAAAAIADwECAAUAAAAmAAAA" +
                "EgEDAAEAAAAGAAAAAAAAAEx1bmEADdIWogAAAA9JREFUeJxj+M/AwPCfAQAH/wH/AX+JpwAAAABJRU5ErkJggg==",
        )
        for ((orientation, top) in listOf(ExifInterface.ORIENTATION_ROTATE_90 to Color.RED,
            ExifInterface.ORIENTATION_TRANSVERSE to Color.GREEN)) {
            val file = temporary.newFile("$orientation.png").apply { writeBytes(png) }
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            val bytes = file.readBytes()
            val connector = FakeSmbConnector()
            connector.share.put("picture.png", bytes)
            val account = SmbAccount(id = "nas", name = "NAS", host = "nas.local", guest = true)
            val provider = SmbStorageProvider({ listOf(account) }, { it }, connector)
            val entry = Entry(NodeRef("smb", "nas:media:picture.png"), "picture.png", directory = false,
                size = bytes.size.toLong(), mimeType = "image/png", capabilities = setOf(Capability.READ))
            val loader = ThumbnailLoader(RuntimeEnvironment.getApplication(), ProviderRegistry(listOf(provider)))
            try {
                val bitmap = requireNotNull(loader.load(entry, 32, 2L * bytes.size))
                assertEquals(1, bitmap.width)
                assertEquals(2, bitmap.height)
                assertEquals(top, bitmap.getPixel(0, 0))
                assertTrue(connector.share.bytesServed <= 2L * bytes.size)
                assertEquals(0, connector.share.readersOpen)
            } finally {
                provider.disconnect(account.id)
            }
        }
    }

    @Test fun `image decoding shares one budget and retries when the allowance increases`() = runBlocking {
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val png = ByteArrayOutputStream().also {
            assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it))
            source.recycle()
        }.toByteArray()
        val connector = FakeSmbConnector()
        connector.share.put("picture.png", png)
        val account = SmbAccount(id = "nas", name = "NAS", host = "nas.local", guest = true)
        val provider = SmbStorageProvider({ listOf(account) }, { it }, connector)
        try {
            // Same bytes under a huge and an unknown listed size: only the download budget matters.
            for (declaredSize in listOf<Long?>(16L shl 30, null)) {
                val entry = Entry(NodeRef("smb", "nas:media:picture.png"), "picture.png", directory = false,
                    size = declaredSize, mimeType = "image/png", capabilities = setOf(Capability.READ))
                val loader = ThumbnailLoader(RuntimeEnvironment.getApplication(), ProviderRegistry(listOf(provider)))
                val before = connector.share.bytesServed
                val openedBefore = connector.share.readersOpened

                // Decoding reads the source twice (bounds, then pixels), so one file's worth of
                // budget is too small.
                val tooSmall = png.size.toLong()
                assertNull(loader.load(entry, sizePx = 32, networkBudget = tooSmall))
                assertTrue("The pixel-read pass was reached", connector.share.readersOpened - openedBefore >= 2)
                assertTrue("Both passes stay within one budget", connector.share.bytesServed - before <= tooSmall)
                assertEquals(0, connector.share.readersOpen)

                val afterFailure = connector.share.bytesServed
                val openedAfterFailure = connector.share.readersOpened
                assertNull(loader.load(entry, sizePx = 32, networkBudget = tooSmall))
                assertEquals("The same failed allowance is not retried", afterFailure, connector.share.bytesServed)
                assertEquals(openedAfterFailure, connector.share.readersOpened)

                val enough = 2L * png.size
                val bitmap = loader.load(entry, sizePx = 32, networkBudget = enough)
                assertNotNull("Increasing the budget permits decoding even for a $declaredSize-byte listing", bitmap)
                assertEquals(Color.RED, requireNotNull(bitmap).getPixel(3, 3))
                assertTrue("Retry stays within its own budget", connector.share.bytesServed - afterFailure <= enough)
                assertEquals(0, connector.share.readersOpen)

                val afterSuccess = connector.share.bytesServed
                val openedAfterSuccess = connector.share.readersOpened
                assertSame(bitmap, loader.load(entry, sizePx = 32, networkBudget = enough))
                assertNull("Off hides even an already cached network preview", loader.load(entry, sizePx = 32, networkBudget = 0))
                assertEquals("Cache hits and Off fetch nothing", afterSuccess, connector.share.bytesServed)
                assertEquals(openedAfterSuccess, connector.share.readersOpened)
            }
        } finally {
            provider.disconnect(account.id)
        }
    }
}
