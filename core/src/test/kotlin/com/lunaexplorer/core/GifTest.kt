package com.lunaexplorer.core

import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GifTest {
    private fun checksum(frame: GifFrame): Long {
        val raw = ByteBuffer.allocate(frame.argb.size * 4)
        for (pixel in frame.argb) raw.putInt(if (pixel ushr 24 == 0) 0 else pixel)
        return CRC32().apply { update(raw.array()) }.value
    }

    private fun check(name: String, fixture: GifFixture) {
        val delays = ArrayList<Int>()
        val frames = ArrayList<Long>()
        Gif.frames(fixture.bytes) { frame ->
            assertEquals("$name frame order", frames.size, frame.index)
            delays += frame.delayMillis
            frames += checksum(frame)
            true
        }
        assertEquals("$name delays", fixture.delays, delays)
        assertEquals("$name frames", fixture.frames.map { "%08X".format(it) }, frames.map { "%08X".format(it) })
        assertEquals("$name count", fixture.frames.size, Gif.frameCount(fixture.bytes))
    }

    @Test fun `frames that repaint only a rectangle are drawn over what came before`() =
        check("partial", GifFixtureData.PARTIAL_FRAMES)

    @Test fun `a frame restored to the previous one leaves no trace in the next`() {
        check("previous", GifFixtureData.RESTORE_PREVIOUS)
        check("previous over nothing", GifFixtureData.RESTORE_PREVIOUS_TRANSPARENT)
    }

    @Test fun `each frame is coloured from its own palette`() = check("palettes", GifFixtureData.LOCAL_PALETTES)

    @Test fun `interlaced rows land in order and a cleared frame leaves transparency behind`() =
        check("cleared", GifFixtureData.CLEARED_INTERLACED)

    @Test fun `decoding stops when the caller has seen enough`() {
        var seen = 0
        Gif.frames(GifFixtureData.PARTIAL_FRAMES.bytes) { ++seen < 2 }
        assertEquals(2, seen)
    }

    @Test fun `a file cut off part way gives the frames that are whole`() {
        val whole = GifFixtureData.PARTIAL_FRAMES
        var seen = 0
        Gif.frames(whole.bytes.copyOf(whole.bytes.size * 2 / 3)) { seen++; true }
        assertTrue("Some frames, not all, and no exception: $seen", seen in 1 until whole.frames.size)
    }

    @Test fun `what is not a GIF is refused`() {
        assertFalse(Gif.isGif(byteArrayOf(1, 2, 3)))
        assertTrue(runCatching { Gif.frames(ByteArray(64)) { true } }.exceptionOrNull() is IOException)
        val hostile = GifFixtureData.PARTIAL_FRAMES.bytes.copyOf().also { it[6] = -1; it[7] = -1; it[8] = -1; it[9] = -1 }
        assertTrue("A 65535 x 65535 canvas is not allocated",
            runCatching { Gif.frames(hostile) { true } }.exceptionOrNull() is IOException)
    }
}
