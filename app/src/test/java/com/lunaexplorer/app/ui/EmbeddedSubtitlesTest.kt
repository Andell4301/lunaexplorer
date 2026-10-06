package com.lunaexplorer.app.ui

import android.app.Application
import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.lunaexplorer.app.playback.PlaybackSource
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EmbeddedSubtitlesTest {
    @Test fun `seekable subtitle extraction skips large video packets and padding without downloading them`() = runBlocking {
        fun element(id: ByteArray, payload: ByteArray): ByteArray {
            val size = payload.size or 0x10000000
            return id + ByteBuffer.allocate(4).putInt(size).array() + payload
        }
        val base = fixture("mkv")
        val segment = base.indices.first { index ->
            index + 4 < base.size && base.copyOfRange(index, index + 4).contentEquals(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67))
        }
        for (index in segment + 5 until segment + 12) base[index] = 0xff.toByte()
        val block = element(byteArrayOf(0xa3.toByte()), byteArrayOf(0x81.toByte(), 0, 0, 0) + ByteArray(1024 * 1024))
        val cluster = element(byteArrayOf(0x1f, 0x43, 0xb6.toByte(), 0x75), byteArrayOf(0xe7.toByte(), 0x81.toByte(), 0) + block)
        val bytes = base + cluster + element(byteArrayOf(0xec.toByte()), ByteArray(1024 * 1024))
        val channel = TrackingChannel(bytes)
        val data = TrackingSource(bytes)
        val playback = PlaybackSource(Uri.parse("luna-media://playback/embedded.mkv"), DataSource.Factory { data }) { channel }
        val progress = mutableListOf<Float?>()
        val document = readEmbeddedSubtitleDocument(playback, identity("2", "English", "eng"), progress::add)
        assertTrue(document.text.contains("First caption"))
        assertTrue("Downloaded ${channel.bytesRead} of ${bytes.size} bytes", channel.bytesRead < 64 * 1024)
        assertTrue("Used ${channel.reads} reads", channel.reads < 10)
        assertEquals(0, data.opens)
        assertEquals(1, channel.closes)
        assertEquals(1f, progress.last())
    }

    @Test fun `Matroska text extraction selects one track and preserves times and Unicode`() = runBlocking {
        val data = TrackingSource(fixture("mkv"))
        val text = readEmbeddedSubtitles(source(data, "mkv"), identity("2", "English", "eng"))
        assertTrue(text.contains("00:00:01.000 --> 00:00:02.000\nFirst caption"))
        assertTrue(text.contains("00:00:03.000 --> 00:00:04.000\nSecond caption\n字幕"))
        assertFalse(text.contains("別の字幕"))
        assertEquals(data.opens, data.closes)
    }

    @Test fun `MP4 timed text opens independently of the selected playback track`() = runBlocking {
        val data = TrackingSource(fixture("mp4"))
        val text = readEmbeddedSubtitles(source(data, "mp4"), identity("3", null, "jpn"))
        assertTrue(text.contains("00:00:01.500"))
        assertTrue(text.contains("別の字幕"))
        assertFalse(text.contains("First caption"))
        assertEquals(data.opens, data.closes)
    }

    @Test fun `absent track and unreadable media fail without leaving the reader open`() = runBlocking {
        listOf(fixture("mkv"), byteArrayOf(1, 2, 3)).forEach { bytes ->
            val data = TrackingSource(bytes)
            try {
                readEmbeddedSubtitles(source(data, "mkv"), identity("99", null, "eng"))
                fail("Expected a missing subtitle failure")
            } catch (_: IllegalStateException) {
                assertEquals(data.opens, data.closes)
            }
        }
    }

    @Test fun `closing a subtitle load cancels a blocked read and closes the source`() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val data = TrackingSource(fixture("mkv")) {
            reading.complete(Unit)
            Thread.sleep(30_000L)
        }
        val load = launch { readEmbeddedSubtitles(source(data, "mkv"), identity("2", "English", "eng")) }
        withTimeout(5_000L) { reading.await(); load.cancelAndJoin() }
        assertTrue(load.isCancelled)
        assertEquals(1, data.opens)
        assertEquals(data.opens, data.closes)
    }

    @Test fun `closing a seekable subtitle load interrupts its read and closes the channel`() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val channel = TrackingChannel(fixture("mkv")) {
            reading.complete(Unit)
            Thread.sleep(30_000L)
        }
        val playback = PlaybackSource(Uri.parse("luna-media://playback/embedded.mkv"),
            DataSource.Factory { error("Must use the channel") }) { channel }
        val load = launch { readEmbeddedSubtitles(playback, identity("2", "English", "eng")) }
        withTimeout(5_000L) { reading.await(); load.cancelAndJoin() }
        assertTrue(load.isCancelled)
        assertEquals(1, channel.closes)
    }

    private fun fixture(extension: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/subtitles/embedded.$extension")).use { it.readBytes() }

    private fun identity(id: String, label: String?, language: String): MediaTrackIdentity = mediaTrackIdentity(
        Format.Builder().setId(id).setLabel(label).setLanguage(language)
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).build())

    private fun source(data: DataSource, extension: String) =
        PlaybackSource(Uri.parse("luna-media://playback/embedded.$extension"), DataSource.Factory { data })

    private class TrackingSource(bytes: ByteArray, private val onRead: () -> Unit = {}) : DataSource {
        private val delegate = ByteArrayDataSource(bytes)
        var opens = 0
        var closes = 0
        override fun open(dataSpec: DataSpec): Long { opens++; return delegate.open(dataSpec) }
        override fun close() { closes++; delegate.close() }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            onRead()
            return delegate.read(buffer, offset, length)
        }
        override fun getUri(): Uri? = delegate.uri
        override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)
    }

    private class TrackingChannel(private val bytes: ByteArray, private val onRead: () -> Unit = {}) : SeekableByteChannel {
        private var position = 0
        var bytesRead = 0
        var reads = 0
        var closes = 0
        override fun read(dst: ByteBuffer): Int {
            reads++
            onRead()
            if (position == bytes.size) return -1
            val count = minOf(dst.remaining(), bytes.size - position)
            dst.put(bytes, position, count)
            position += count
            bytesRead += count
            return count
        }
        override fun position(): Long = position.toLong()
        override fun position(newPosition: Long): SeekableByteChannel = apply { position = newPosition.toInt() }
        override fun size(): Long = bytes.size.toLong()
        override fun isOpen(): Boolean = closes == 0
        override fun close() { closes++ }
        override fun write(src: ByteBuffer): Int = error("Read only")
        override fun truncate(size: Long): SeekableByteChannel = error("Read only")
    }
}
