package com.lunaexplorer.app.ui

import android.app.Application
import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import com.lunaexplorer.app.playback.PlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoFrameIndexTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `steps follow presentation order across irregular and repeated timestamps`() {
        val frames = VideoFrameIndex(longArrayOf(0, 80_500, 40_250, 123_456, 80_500, -40_000))
        assertEquals(40_250L, frames.adjacent(0, true))
        assertEquals(80_500L, frames.adjacent(40_250, true))
        assertEquals(123_456L, frames.adjacent(80_500, true))
        assertEquals(80_500L, frames.adjacent(123_456, false))
        assertEquals(40_250L, frames.adjacent(80_500, false))
        assertEquals(0L, frames.adjacent(40_250, false))
        assertNull(frames.adjacent(0, false))
        assertNull(frames.adjacent(123_456, true))
    }

    @Test fun `a seek between frames finds its neighbors without rounding their timestamps`() {
        val frames = VideoFrameIndex(longArrayOf(0, 33_366, 66_733, 100_100))
        assertEquals(66_733L, frames.adjacent(66_000, true))
        assertEquals(33_366L, frames.adjacent(66_000, false))
        assertEquals(33L, videoFramePositionMs(33_366))
        assertEquals(66L, videoFramePositionMs(66_733))
    }

    @Test fun `mp4 frame timestamps are read from the source and its stream closes`() = runBlocking {
        var closed = false
        val bytes = fixture("mp4")
        val source = PlaybackSource(Uri.parse("luna-media://playback/frames.mp4"), DataSource.Factory {
            val delegate = ByteArrayDataSource(bytes)
            object : DataSource by delegate {
                override fun close() { closed = true; delegate.close() }
            }
        })
        val frames = readVideoFrameIndex(source, null)
        assertTrue(closed)
        assertEquals(1_000_000L, frames.adjacent(0, true))
        assertEquals(2_000_000L, frames.adjacent(1_000_000, true))
        assertEquals(0L, frames.adjacent(1_000_000, false))
        assertNull(frames.adjacent(4_000_000, true))
    }

    @Test fun `matroska frame timestamps use the provider channel and close it`() = runBlocking {
        val file = temporary.newFile("frames.mkv").apply { writeBytes(fixture("mkv")) }
        val channel = Files.newByteChannel(file.toPath())
        val source = PlaybackSource(Uri.parse("luna-media://playback/frames.mkv"),
            DataSource.Factory { error("The provider channel is available") }) { channel }
        val frames = readVideoFrameIndex(source, null)
        assertTrue(!channel.isOpen)
        assertEquals(1_000_000L, frames.adjacent(0, true))
        assertEquals(2_000_000L, frames.adjacent(1_000_000, true))
        assertEquals(0L, frames.adjacent(1_000_000, false))
    }

    @Test fun `mp4 indexing skips payload before its sample table`() = runBlocking {
        val bytes = fixture("mp4")
        var at = 0
        while (String(bytes, at + 4, 4, Charsets.US_ASCII) != "moov") at += ByteBuffer.wrap(bytes, at, 4).int
        val padding = ByteBuffer.allocate(2 * 1024 * 1024).apply {
            putInt(capacity()); put("free".toByteArray(Charsets.US_ASCII))
        }.array()
        val file = temporary.newFile("padded.mp4").apply { writeBytes(bytes.copyOfRange(0, at) + padding + bytes.copyOfRange(at, bytes.size)) }
        var read = 0
        val delegate = Files.newByteChannel(file.toPath())
        val channel = object : SeekableByteChannel by delegate {
            override fun read(dst: ByteBuffer): Int = delegate.read(dst).also { if (it > 0) read += it }
        }
        val source = PlaybackSource(Uri.parse("luna-media://playback/padded.mp4"),
            DataSource.Factory { error("The provider channel is available") }) { channel }
        val frames = readVideoFrameIndex(source, null)
        assertEquals(1_000_000L, frames.adjacent(0, true))
        assertTrue("Read $read bytes", read < 64 * 1024)
        assertTrue(!channel.isOpen)
    }

    @Test fun `a missing selected video track fails without substituting another track`() = runBlocking {
        val delegate = ByteArrayDataSource(fixture("mp4"))
        var closed = false
        val source = PlaybackSource(Uri.parse("luna-media://playback/frames.mp4"), DataSource.Factory {
            object : DataSource by delegate {
                override fun close() { closed = true; delegate.close() }
            }
        })
        try {
            readVideoFrameIndex(source, mediaTrackIdentity(Format.Builder().setId("999").build()))
            fail("Expected a missing video track failure")
        } catch (_: IllegalStateException) {
            assertTrue(closed)
        }
    }

    @Test fun `cancellation closes the provider channel without returning a partial index`() = runBlocking {
        var closed = false
        val delegate = Files.newByteChannel(temporary.newFile("frames.mp4").apply { writeBytes(fixture("mp4")) }.toPath())
        val channel = object : SeekableByteChannel by delegate {
            override fun read(dst: ByteBuffer): Int = throw CancellationException("Cancelled frame lookup")
            override fun close() { closed = true; delegate.close() }
        }
        val source = PlaybackSource(Uri.parse("luna-media://playback/frames.mp4"),
            DataSource.Factory { error("The provider channel is available") }) { channel }
        try {
            readVideoFrameIndex(source, null)
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(closed)
        }
    }

    @Test fun `cancelling a blocked frame read interrupts it and closes its source`() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val delegate = ByteArrayDataSource(fixture("mp4"))
        var closed = false
        val source = PlaybackSource(Uri.parse("luna-media://playback/frames.mp4"), DataSource.Factory {
            object : DataSource by delegate {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    reading.complete(Unit)
                    Thread.sleep(30_000)
                    return delegate.read(buffer, offset, length)
                }
                override fun close() { closed = true; delegate.close() }
            }
        })
        val load = launch { readVideoFrameIndex(source, null) }
        withTimeout(5000) { reading.await(); load.cancelAndJoin() }
        assertTrue(load.isCancelled)
        assertTrue(closed)
    }

    private fun fixture(extension: String) = checkNotNull(javaClass.getResourceAsStream("/subtitles/embedded.$extension"))
        .use { it.readBytes() }
}
