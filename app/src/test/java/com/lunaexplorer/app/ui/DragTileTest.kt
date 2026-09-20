package com.lunaexplorer.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.view.View
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DragTileTest {
    private val scale = 2f
    private val colours = DragColours(
        tile = 0xFF334455.toInt(), second = 0xFF993355.toInt(), third = 0xFF335599.toInt(),
        badge = 0xFFFFFFFF.toInt(), badgeInk = 0xFF000000.toInt(),
    )
    private val tilePx = (96 * scale).toInt()
    private val stepPx = (10 * scale).toInt()

    private fun render(visual: DragVisual, count: Int): Pair<Bitmap, Point> {
        val view = View(ApplicationProvider.getApplicationContext())
        val shadow = DragTile(view, scale, LayoutDirection.Ltr, visual, count, colours)
        val size = Point(); val touch = Point()
        shadow.onProvideShadowMetrics(size, touch)
        val bitmap = Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888)
        shadow.onDrawShadow(Canvas(bitmap))
        return bitmap to touch
    }

    @Test fun `the tile sits above the finger with a clear band between`() {
        val (bitmap, touch) = render(DragVisual(null, null, photo = false), 1)
        assertEquals("The finger is at the bottom edge", bitmap.height - 1, touch.y)
        assertEquals("The tile is drawn where it says", colours.tile, bitmap.getPixel(tilePx / 2, tilePx / 2))
        assertEquals("Under the tile is clear, so the finger covers nothing", 0, bitmap.getPixel(tilePx / 2, bitmap.height - 4))
    }

    @Test fun `further items show as cards behind, and a count`() {
        val (bitmap, _) = render(DragVisual(null, null, photo = false), 3)
        // Right of the top tile and the first card, only the second card is drawn.
        val x = tilePx + stepPx * 2 - 6
        val y = stepPx * 2 + tilePx / 2
        assertEquals(colours.third, bitmap.getPixel(x, y))
        assertEquals(colours.second, bitmap.getPixel(tilePx + stepPx - 6, stepPx + tilePx / 2))
        // The badge in the top corner is not the tile colour.
        assert(bitmap.getPixel(tilePx - (14 * scale).toInt(), (14 * scale).toInt()) != colours.tile)
    }

    // Sampled along the top edge: a clipped badge's centre looks the same as an intact one.
    @Test fun `the count badge is drawn whole rather than cut off at the top`() {
        val (bitmap, _) = render(DragVisual(null, null, photo = false), 2)
        val acrossTheTop = (0 until bitmap.width).count { bitmap.getPixel(it, 0) == colours.badge }
        assertTrue("The badge is cut flat along the top edge, $acrossTheTop pixels wide",
            acrossTheTop <= 4)
    }

    @Test fun `a picture fills the tile, through the toolkit's own drawing`() {
        val picture = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val (bitmap, _) = render(DragVisual(BitmapPainter(picture.asImageBitmap()), null, photo = true), 1)
        assertEquals(Color.RED, bitmap.getPixel(tilePx / 2, tilePx / 2))
        assertEquals("Cropped to the tile's rounded corner", 0, bitmap.getPixel(1, 1))
    }
}
