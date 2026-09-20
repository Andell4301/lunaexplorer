package com.lunaexplorer.app.ui

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
// NATIVE so the platform's real image decoder runs.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimatedImageTest {
    /** A two-frame GIF, red then blue. */
    private val animatedGif = byteArrayOf(
        71, 73, 70, 56, 57, 97, 2, 0, 2, 0, -16, 0, 0, -1, 0, 0, 0, 0, -1, 33, -1, 11, 78, 69, 84,
        83, 67, 65, 80, 69, 50, 46, 48, 3, 1, 0, 0, 0, 33, -7, 4, 0, 50, 0, 0, 0, 44, 0, 0, 0, 0, 2,
        0, 2, 0, 0, 2, 3, 4, -128, 2, 0, 33, -7, 4, 0, 50, 0, 0, 0, 44, 0, 0, 0, 0, 2, 0, 2, 0, 0,
        2, 3, 76, -110, 2, 0, 59,
    )

    @Test fun `an animated gif decodes to something that can be played`() {
        val moving = decodeAnimated(animatedGif)

        assertNotNull("A GIF with more than one frame is an animation, not a picture", moving)
        assertTrue("And it knows how big it is", moving!!.intrinsicWidth > 0)
    }

    @Test fun `a still picture is not mistaken for an animation`() {
        val png = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also {
            png.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()

        assertNull(decodeAnimated(bytes))
    }

    @Test fun `rubbish is refused rather than thrown`() {
        assertNull(decodeAnimated(byteArrayOf(1, 2, 3, 4)))
    }
}
