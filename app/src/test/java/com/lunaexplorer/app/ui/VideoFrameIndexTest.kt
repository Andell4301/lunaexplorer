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
import org.junit.Assert.assertFalse
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

    @Test fun `matroska indexing reads nearby clusters without scanning the rest of the video`() = runBlocking {
        val bytes = indexedMatroska()
        val delegate = Files.newByteChannel(temporary.newFile("indexed.mkv").apply { writeBytes(bytes) }.toPath())
        var read = 0
        val channel = object : SeekableByteChannel by delegate {
            override fun read(dst: ByteBuffer): Int = delegate.read(dst).also { if (it > 0) read += it }
        }
        val source = PlaybackSource(Uri.parse("luna-media://playback/indexed.mkv"),
            DataSource.Factory { error("The provider channel is available") }) { channel }

        val frames = readVideoFrameIndex(source, null)

        assertEquals(1_000_000L, frames.adjacent(0, true))
        assertTrue("Read $read of ${bytes.size} bytes", read < 64 * 1024)
        assertTrue(!channel.isOpen)
    }

    @Test fun `a later matroska frame lookup seeks past earlier video packets`() = runBlocking {
        val bytes = indexedMatroska(largeAt = 0)
        val delegate = Files.newByteChannel(temporary.newFile("later.mkv").apply { writeBytes(bytes) }.toPath())
        var read = 0
        val channel = object : SeekableByteChannel by delegate {
            override fun read(dst: ByteBuffer): Int = delegate.read(dst).also { if (it > 0) read += it }
        }
        val source = PlaybackSource(Uri.parse("luna-media://playback/later.mkv"),
            DataSource.Factory { error("The provider channel is available") }) { channel }

        val frames = readVideoFrameIndex(source, null, 90_000_000)

        assertEquals(10_000_000L, frames.adjacent(90_000_000, false))
        assertTrue(frames.covers(90_000_000, false))
        assertFalse(frames.covers(0, true))
        assertTrue("Read $read of ${bytes.size} bytes", read < 64 * 1024)
        assertTrue(!channel.isOpen)
    }

    @Test fun `physically reordered matroska clusters retain exact neighboring frames`() = runBlocking {
        val bytes = indexedMatroska(physicalOrder = listOf(0, 3, 2, 1))
        val channel = Files.newByteChannel(temporary.newFile("reordered.mkv").apply { writeBytes(bytes) }.toPath())
        val source = PlaybackSource(Uri.parse("luna-media://playback/reordered.mkv"),
            DataSource.Factory { error("The provider channel is available") }) { channel }

        val frames = readVideoFrameIndex(source, null, 90_000_000)

        assertEquals(90_001_000L, frames.adjacent(90_000_000, true))
        assertEquals(10_000_000L, frames.adjacent(90_000_000, false))
        assertTrue(!channel.isOpen)
    }

    @Test fun `frame windows request another lookup only when the next step is outside their coverage`() {
        val frames = VideoFrameIndex(longArrayOf(10, 20, 30), 10, 40)
        assertTrue(frames.covers(20, true))
        assertTrue(frames.covers(20, false))
        assertFalse(frames.covers(10, false))
        assertFalse(frames.covers(30, true))
        assertFalse(frames.covers(5, true))
        val complete = VideoFrameIndex(longArrayOf(0, 20))
        assertTrue(complete.covers(0, false))
        assertTrue(complete.covers(20, true))
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

    private fun indexedMatroska(
        largeAt: Long = 90_000,
        physicalOrder: List<Int> = listOf(0, 1, 2, 3),
    ): ByteArray {
        fun id(value: Int) = (3 downTo 0).map { (value ushr (it * 8)).toByte() }
            .dropWhile { it == 0.toByte() }.toByteArray()
        fun element(id: Int, payload: ByteArray) =
            id(id) + ByteBuffer.allocate(4).putInt(payload.size or 0x10000000).array() + payload
        fun integer(value: Long) = ByteBuffer.allocate(8).putLong(value).array()
        val info = element(0x1549A966, element(0x2AD7B1, integer(1_000_000)) +
            element(0x4489, ByteBuffer.allocate(8).putDouble(100_000.0).array()))
        val tracks = element(0x1654AE6B, element(0xAE, element(0xD7, integer(1)) +
            element(0x83, integer(1)) + element(0x86, "V_MPEG4/ISO/ASP".toByteArray()) +
            element(0xE0, element(0xB0, integer(16)) + element(0xBA, integer(16)))))
        val times = listOf(0L, 1000L, 10_000L, 90_000L)
        val clusters = times.map { time ->
            val blocks = if (time == largeAt) (0 until 512).map { offset ->
                element(0xA3, byteArrayOf(0x81.toByte(), (offset shr 8).toByte(), offset.toByte(), 0x80.toByte()) + ByteArray(1024))
            }.fold(byteArrayOf()) { bytes, block -> bytes + block }
            else element(0xA3, byteArrayOf(0x81.toByte(), 0, 0, 0x80.toByte()) + ByteArray(16))
            element(0x1F43B675, element(0xE7, integer(time)) + blocks)
        }
        fun cues(positions: List<Long>) = element(0x1C53BB6B, times.indices.map { index ->
            element(0xBB, element(0xB3, integer(times[index])) +
                element(0xB7, element(0xF7, integer(1)) + element(0xF1, integer(positions[index]))))
        }.fold(byteArrayOf()) { bytes, cue -> bytes + cue })
        var position = (info.size + tracks.size + cues(List(times.size) { 0L }).size).toLong()
        val positions = MutableList(times.size) { 0L }
        for (index in physicalOrder) { positions[index] = position; position += clusters[index].size }
        val segment = info + tracks + cues(positions) + physicalOrder.fold(byteArrayOf()) { bytes, index -> bytes + clusters[index] }
        return element(0x1A45DFA3, element(0x4282, "matroska".toByteArray()) + element(0x4285, integer(2))) +
            element(0x18538067, segment)
    }

    private fun fixture(extension: String) = checkNotNull(javaClass.getResourceAsStream("/subtitles/embedded.$extension"))
        .use { it.readBytes() }
}
