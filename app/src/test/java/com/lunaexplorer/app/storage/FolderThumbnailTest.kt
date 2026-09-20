package com.lunaexplorer.app.storage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.ProviderRegistry
import com.lunaexplorer.core.StorageProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderThumbnailTest {
    @get:Rule val temporary = TemporaryFolder()

    private val local by lazy {
        LocalStorageProvider(listOf(LocalRoot("workspace", "workspace", temporary.root)), probe = PathProbe.OF_FILESYSTEM)
    }

    private fun loader() = ThumbnailLoader(RuntimeEnvironment.getApplication(), ProviderRegistry(listOf(local)))

    private fun picture(into: File, name: String, colour: Int) {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }
        File(into, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun cutOut(into: File, name: String, colour: Int) {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawCircle(16f, 16f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour })
        File(into, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun entryOf(file: File) = runBlocking { local.stat(requireNotNull(local.referenceTo(file.path))) }

    @Test fun `a folder of pictures previews as a picture of its own`() {
        val loader = loader()
        val folder = File(temporary.root, "album").apply { mkdirs() }
        picture(folder, "one.png", Color.RED)
        picture(folder, "two.png", Color.GREEN)

        val entry = entryOf(folder)
        assertTrue("A listable folder is previewable", loader.supports(entry))
        val preview = runBlocking { loader.load(entry, 128) }

        assertNotNull("The folder gets a picture of what is inside it", preview)
        assertEquals(128, preview!!.width)
        assertEquals(128, preview.height)
    }

    @Test fun `a preview abandoned while the folder is listed is not remembered as a failure`() = runBlocking {
        val folder = File(temporary.root, "album").apply { mkdirs() }
        picture(folder, "one.png", Color.RED)
        val entry = entryOf(folder)
        val listing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = object : StorageProvider by local {
            override fun list(parent: NodeRef, complete: Boolean): Flow<List<Entry>> = flow {
                listing.complete(Unit)
                release.await()
                emitAll(local.list(parent, complete))
            }
        }
        val loader = ThumbnailLoader(RuntimeEnvironment.getApplication(), ProviderRegistry(listOf(gated)))

        val abandoned = launch(Dispatchers.Default) { loader.load(entry, 128) }
        listing.await()
        abandoned.cancelAndJoin()
        release.complete(Unit)

        assertNotNull("Returning to that size must load the preview", loader.load(entry, 128))
    }

    @Test fun `a folder of see-through pictures gets no pale border round them`() {
        val folder = File(temporary.root, "icons").apply { mkdirs() }
        repeat(3) { cutOut(folder, "icon$it.png", Color.rgb(0, 120, 120)) }

        val preview = requireNotNull(runBlocking { loader().load(entryOf(folder), 128) })

        // The pictures are dark teal, so any pale opaque pixel was painted behind them.
        var palest = 0
        for (y in 0 until preview.height) for (x in 0 until preview.width) {
            val pixel = preview.getPixel(x, y)
            if (Color.alpha(pixel) < 40) continue
            val brightness = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
            if (brightness > palest) palest = brightness
        }
        assertTrue("Something pale is being painted behind the pictures: $palest", palest < 150)
    }

    @Test fun `a folder holding nothing previewable gets no picture`() {
        val folder = File(temporary.root, "notes").apply { mkdirs() }
        File(folder, "a.txt").writeText("nothing to look at")

        assertNull("There is nothing to show, so it keeps its folder icon",
            runBlocking { loader().load(entryOf(folder), 128) })
    }

    @Test fun `a folder that cannot be listed is not previewable`() {
        val folder = File(temporary.root, "album").apply { mkdirs() }
        picture(folder, "one.png", Color.RED)
        val entry = entryOf(folder).let { it.copy(capabilities = it.capabilities - Capability.LIST) }

        assertFalse(loader().supports(entry))
    }

    @Test fun `the same folder is only walked once`() {
        var listings = 0
        val counting = object : StorageProvider by local {
            override fun list(parent: NodeRef, complete: Boolean): Flow<List<Entry>> =
                local.list(parent, complete).onStart { listings++ }
        }
        val loader = ThumbnailLoader(RuntimeEnvironment.getApplication(), ProviderRegistry(listOf(counting)))
        val folder = File(temporary.root, "album").apply { mkdirs() }
        picture(folder, "one.png", Color.RED)
        val entry = entryOf(folder)

        val first = requireNotNull(runBlocking { loader.load(entry, 128) })
        assertSame(first, runBlocking { loader.load(entry, 128) })
        assertEquals(1, listings)
    }
}
