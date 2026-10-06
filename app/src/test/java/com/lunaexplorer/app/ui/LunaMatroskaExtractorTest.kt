package com.lunaexplorer.app.ui

import android.app.Application
import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import com.lunaexplorer.app.playback.PlaybackSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LunaMatroskaExtractorTest {
    @Test fun `Matroska zlib subtitles decode with implicit and explicit compression algorithms`() = runBlocking {
        listOf(false, true).forEach { explicit ->
            val bytes = matroska(explicit, "Princess! {\\s1}字幕")
            val text = read(bytes)
            assertTrue(text.contains("00:00:01.000 --> 00:00:02.350"))
            assertTrue(text.contains("Princess! 字幕"))
        }
    }

    @Test fun `corrupt and oversized compressed subtitles fail before entering the text parser`() = runBlocking {
        listOf(
            matroska(false, "", byteArrayOf(0x78, 0x01, 1, 2, 3)),
            matroska(false, "x".repeat(2 * 1024 * 1024)),
        ).forEach { bytes ->
            try {
                read(bytes)
                fail("Expected invalid compressed subtitles to fail")
            } catch (_: ParserException) {
                Unit
            }
        }
    }

    private suspend fun read(bytes: ByteArray): String {
        val source = PlaybackSource(Uri.parse("luna-media://test/subtitles.mkv"), DataSource.Factory { ByteArrayDataSource(bytes) })
        val identity = mediaTrackIdentity(Format.Builder().setId("1").setLanguage("eng")
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).build())
        return readEmbeddedSubtitles(source, identity)
    }

    private fun matroska(explicit: Boolean, text: String, compressed: ByteArray? = null): ByteArray {
        val header = """
            [Script Info]
            PlayResX: 640
            PlayResY: 360
            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, Bold, Italic, Underline, StrikeOut, Alignment
            Style: Main,Arial,24,&H00FFFFFF,0,0,0,0,2
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        """.trimIndent().toByteArray()
        val payload = compressed ?: ByteArrayOutputStream().apply {
            DeflaterOutputStream(this).use { it.write("0,0,Main,,0,0,0,,$text".toByteArray()) }
        }.toByteArray()
        val compression = element(0x5034, if (explicit) element(0x4254, byteArrayOf(0)) else byteArrayOf())
        val track = element(0xAE, element(0xD7, byteArrayOf(1)) + element(0x83, byteArrayOf(17)) +
            element(0x86, "S_TEXT/ASS".toByteArray()) + element(0x63A2, header) +
            element(0x22B59C, "eng".toByteArray()) + element(0x6D80, element(0x6240, compression)))
        val cluster = element(0x1F43B675, element(0xE7, byteArrayOf(3, 0xe8.toByte())) +
            element(0xA0, element(0xA1, byteArrayOf(0x81.toByte(), 0, 0, 0) + payload) +
                element(0x9B, byteArrayOf(5, 0x46))))
        return element(0x1A45DFA3, element(0x4282, "matroska".toByteArray()) + element(0x4285, byteArrayOf(2))) +
            element(0x18538067, element(0x1549A966, element(0x2AD7B1, byteArrayOf(0x0f, 0x42, 0x40))) +
                element(0x1654AE6B, track) + cluster)
    }

    private fun element(id: Int, bytes: ByteArray): ByteArray {
        val idBytes = (3 downTo 0).map { (id ushr (it * 8)).toByte() }.dropWhile { it == 0.toByte() }.toByteArray()
        val length = if (bytes.size < 127) byteArrayOf((0x80 or bytes.size).toByte())
            else byteArrayOf((0x40 or (bytes.size ushr 8)).toByte(), bytes.size.toByte())
        return idBytes + length + bytes
    }
}
