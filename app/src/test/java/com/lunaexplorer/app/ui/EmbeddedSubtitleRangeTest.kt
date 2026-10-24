package com.lunaexplorer.app.ui

import android.app.Application
import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.lunaexplorer.app.playback.PlaybackSource
import com.lunaexplorer.app.storage.RemoteChannel
import com.lunaexplorer.app.storage.RemoteReader
import com.lunaexplorer.app.storage.RemoteReading
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EmbeddedSubtitleRangeTest {
    @Test fun `bounded remote scan skips late clusters without losing reordered or negative-offset subtitles`() = runBlocking {
        val bytes = movie(listOf(
            cluster(1000, subtitle("At start", 0)),
            cluster(120_000, videoPackets()),
            cluster(34_000, subtitle("Negative offset", Short.MIN_VALUE.toInt())),
            cluster(1000, subtitle("Earlier after late cluster", 500)),
        ))
        val remote = CountingReader(bytes, 256 * 1024)
        val captions = read(remote, 2_000_000)
        assertEquals(listOf(1_000_000L, 1_232_000L, 1_500_000L), captions.map { it.first })
        assertTrue(captions.any { it.second == "Earlier after late cluster" })
        assertTrue(remote.closed)
        assertTrue("Read ${remote.served} bytes", remote.served < 256 * 1024)
    }

    @Test fun `cluster bound scales the full signed timestamp range`() = runBlocking {
        val bytes = movie(listOf(
            cluster(1000, subtitle("At start", 0)),
            cluster(120_000, videoPackets()),
            cluster(34_000, subtitle("Scaled negative offset", Short.MIN_VALUE.toInt())),
        ), timestampScale = 2_000_000)
        val remote = CountingReader(bytes, 256 * 1024)
        val captions = read(remote, 3_000_000)
        assertTrue(captions.contains(2_464_000L to "Scaled negative offset"))
        assertTrue(remote.closed)
    }

    @Test fun `unknown-size clusters remain readable and do not hide following earlier clusters`() = runBlocking {
        val unknown = id(0x1F43B675) + byteArrayOf(0xff.toByte()) + element(0xE7, integer(120_000)) + videoPackets()
        val remote = CountingReader(movie(listOf(unknown, cluster(1000, subtitle("After unknown cluster", 0)))))
        assertTrue(read(remote, 2_000_000).contains(1_000_000L to "After unknown cluster"))
        assertTrue(remote.closed)
    }

    @Test fun `nondefault subtitle track scale falls back to reading clusters`() = runBlocking {
        val scale = element(0x23314F, ByteBuffer.allocate(8).putDouble(2.0).array())
        val remote = CountingReader(movie(listOf(cluster(120_000,
            videoPackets() + subtitle("Track scale", 0))), subtitleExtras = scale))
        assertTrue(read(remote, 2_000_000).any { it.second == "Track scale" })
        assertTrue(remote.closed)
    }

    @Test fun `subtitle codec delay widens the range that must be read`() = runBlocking {
        val delay = element(0x56AA, integer(120_000_000_000L))
        val remote = CountingReader(movie(listOf(cluster(120_000,
            videoPackets() + subtitle("Codec delay", 0))), subtitleExtras = delay))
        assertTrue(read(remote, 2_000_000).any { it.second == "Codec delay" })
        assertTrue(remote.closed)
    }

    @Test fun `a selected text track remains readable when its clusters are all outside the range`() = runBlocking {
        val remote = CountingReader(movie(listOf(cluster(120_000, videoPackets() + subtitle("Too late", 0)))), 256 * 1024)
        assertTrue(read(remote, 2_000_000).isEmpty())
        assertTrue(remote.closed)
    }

    @Test fun `empty late clusters do not hide following earlier subtitles`() = runBlocking {
        val remote = CountingReader(movie(listOf(
            cluster(120_000, byteArrayOf()),
            cluster(1000, subtitle("After empty cluster", 0)),
        )))
        assertEquals(listOf(1_000_000L to "After empty cluster"), read(remote, 2_000_000))
        assertTrue(remote.closed)
    }

    @Test fun `sequential sources read late clusters without reopening from the beginning`() = runBlocking {
        val bytes = movie(listOf(
            cluster(120_000, subtitle("Later", 0)),
            cluster(180_000, subtitle("Latest", 0)),
            cluster(1000, subtitle("Earlier after late clusters", 0)),
        ))
        val delegate = ByteArrayDataSource(bytes)
        var opens = 0
        var closes = 0
        val data = object : DataSource by delegate {
            override fun open(dataSpec: DataSpec): Long { opens++; return delegate.open(dataSpec) }
            override fun close() { closes++; delegate.close() }
        }
        val source = PlaybackSource(Uri.parse("luna-media://test/sequential.mkv"), DataSource.Factory { data }) { null }
        val identity = mediaTrackIdentity(Format.Builder().setId("2").setLanguage("eng")
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).build())
        val captions = mutableListOf<String>()
        assertTrue(readEmbeddedSubtitleCues(source, identity, endTimeUs = 2_000_000) { cues, _ ->
            captions += cues.cues.map { it.text.toString() }
        })
        assertTrue(captions.contains("Earlier after late clusters"))
        assertEquals(1, opens)
        assertEquals(opens, closes)
    }

    private suspend fun read(reader: CountingReader, endTimeUs: Long): List<Pair<Long, String>> {
        val source = PlaybackSource(Uri.parse("luna-media://test/range.mkv"),
            DataSource.Factory { error("The provider channel is available") }) {
            RemoteChannel(RemoteReading(reader, "range.mkv") { error("No reconnect expected") }, reader.size)
        }
        val identity = mediaTrackIdentity(Format.Builder().setId("2").setLanguage("eng")
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).build())
        val captions = mutableListOf<Pair<Long, String>>()
        val readable = readEmbeddedSubtitleCues(source, identity, endTimeUs = endTimeUs) { cues, _ ->
            captions += cues.startTimeUs to cues.cues.joinToString { it.text.toString() }
        }
        assertTrue("The selected subtitle track was found", readable)
        return captions
    }

    private fun movie(clusters: List<ByteArray>, timestampScale: Long = 1_000_000, subtitleExtras: ByteArray = byteArrayOf()): ByteArray {
        val video = element(0xAE, element(0xD7, integer(1)) + element(0x83, integer(1)) +
            element(0x86, "V_MPEG4/ISO/ASP".toByteArray()) +
            element(0xE0, element(0xB0, integer(16)) + element(0xBA, integer(16))))
        val subtitle = element(0xAE, element(0xD7, integer(2)) + element(0x83, integer(17)) +
            element(0x86, "S_TEXT/UTF8".toByteArray()) + element(0x22B59C, "eng".toByteArray()) + subtitleExtras)
        val segment = ByteArrayOutputStream().apply {
            write(element(0x1549A966, element(0x2AD7B1, integer(timestampScale))))
            write(element(0x1654AE6B, video + subtitle))
            clusters.forEach(::write)
        }.toByteArray()
        return element(0x1A45DFA3, element(0x4282, "matroska".toByteArray()) + element(0x4285, integer(2))) +
            element(0x18538067, segment)
    }

    private fun cluster(time: Long, payload: ByteArray) = element(0x1F43B675, element(0xE7, integer(time)) + payload)

    private fun subtitle(text: String, relativeTime: Int): ByteArray = element(0xA0,
        element(0xA1, byteArrayOf(0x82.toByte(), (relativeTime shr 8).toByte(), relativeTime.toByte(), 0) + text.toByteArray()) +
            element(0x9B, integer(500)))

    private fun videoPackets(): ByteArray = ByteArrayOutputStream().apply {
        repeat(512) { write(element(0xA3, byteArrayOf(0x81.toByte(), 0, 0, 0) + ByteArray(12 * 1024))) }
    }.toByteArray()

    private fun integer(value: Long) = ByteBuffer.allocate(8).putLong(value).array()
        .dropWhile { it == 0.toByte() }.toByteArray().let { if (it.isEmpty()) byteArrayOf(0) else it }

    private fun id(value: Int) = (3 downTo 0).map { (value ushr (it * 8)).toByte() }
        .dropWhile { it == 0.toByte() }.toByteArray()

    private fun element(id: Int, payload: ByteArray): ByteArray =
        id(id) + ByteBuffer.allocate(4).putInt(payload.size or 0x10000000).array() + payload

    private class CountingReader(private val bytes: ByteArray, private val limit: Int = Int.MAX_VALUE) : RemoteReader {
        val size get() = bytes.size.toLong()
        var served = 0
            private set
        var closed = false
            private set
        override fun read(offset: Long, into: ByteArray, at: Int, length: Int): Int {
            if (offset >= bytes.size) return -1
            val count = minOf(length, bytes.size - offset.toInt())
            if (count > limit - served) throw IOException("Subtitle scan exceeded its remote read allowance")
            bytes.copyInto(into, at, offset.toInt(), offset.toInt() + count)
            served += count
            return count
        }
        override fun close() { closed = true }
    }
}
