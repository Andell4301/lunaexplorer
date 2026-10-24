package com.lunaexplorer.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Size
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.text.CuesWithTiming
import com.lunaexplorer.app.model.SubtitleAppearance
import com.lunaexplorer.app.model.SubtitlePosition
import com.lunaexplorer.app.playback.PlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoClipSubtitlesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `clip boundaries and subtitle delay retain a caption already on screen`() {
        val builder = VideoClipSubtitleBuilder(2000, 4000, 500)
        builder.add(caption("Before", 0, 500_000), Format.CUE_REPLACEMENT_BEHAVIOR_MERGE)
        builder.add(caption("Crossing start", 1_000_000, 1_000_000), Format.CUE_REPLACEMENT_BEHAVIOR_MERGE)
        builder.add(caption("Crossing end", 3_000_000, 1_000_000), Format.CUE_REPLACEMENT_BEHAVIOR_MERGE)
        val subtitles = builder.build()
        assertTrue(subtitles.cuesAt(-1).isEmpty())
        assertEquals("Crossing start", subtitles.cuesAt(0).single().text.toString())
        assertTrue(subtitles.cuesAt(500_000).isEmpty())
        assertEquals("Crossing end", subtitles.cuesAt(1_500_000).single().text.toString())
        assertTrue(subtitles.cuesAt(2_000_000).isEmpty())
    }

    @Test fun `merging keeps overlapping cues and replacing ends a cue at the next sample`() {
        val first = caption("First", 0, 3_000_000)
        val second = caption("Second", 1_000_000, C.TIME_UNSET)
        val clear = CuesWithTiming(emptyList(), 2_000_000, C.TIME_UNSET)
        val merge = VideoClipSubtitleBuilder(0, 4000, 0)
        listOf(first, second, clear).forEach { merge.add(it, Format.CUE_REPLACEMENT_BEHAVIOR_MERGE) }
        val merged = merge.build()
        assertEquals(listOf("First", "Second"), merged.cuesAt(1_500_000).map { it.text.toString() })
        assertEquals("First", merged.cuesAt(2_000_000).single().text.toString())
        val replace = VideoClipSubtitleBuilder(0, 4000, 0)
        listOf(first, second, clear).forEach { replace.add(it, Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE) }
        val replaced = replace.build()
        assertEquals("Second", replaced.cuesAt(1_000_000).single().text.toString())
        assertTrue(replaced.cuesAt(2_000_000).isEmpty())
    }

    @Test fun `external subtitles are parsed with their styling and negative delay`() = runBlocking {
        val file = temporary.newFile("captions.srt").apply {
            writeText("1\n00:00:02,000 --> 00:00:04,000\n<i>Caption</i>\n\n")
        }
        val subtitles = readVideoClipSubtitles(unusedSource(), VideoClipSubtitle(identity("external"), file), 1000, 5000, -500)
        assertTrue(subtitles.cuesAt(499_999).isEmpty())
        val text = subtitles.cuesAt(500_000).single().text
        assertEquals("Caption", text.toString())
        assertTrue(text is android.text.Spanned)
        assertTrue(subtitles.cuesAt(2_500_000).isEmpty())
    }

    @Test fun `embedded export uses only the selected track and shifts its timestamps`() = runBlocking {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/subtitles/embedded.mkv")).use { it.readBytes() }
        val source = PlaybackSource(Uri.parse("luna-media://playback/embedded.mkv"), DataSource.Factory { ByteArrayDataSource(bytes) })
        val subtitle = VideoClipSubtitle(mediaTrackIdentity(Format.Builder().setId("2").setLabel("English")
            .setLanguage("eng").setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).build()))
        val timeline = readVideoClipSubtitles(source, subtitle, 1500, 3500, 0)
        assertEquals("First caption", timeline.cuesAt(0).single().text.toString())
        assertTrue(timeline.cuesAt(500_000).isEmpty())
        assertTrue(timeline.cuesAt(1_500_000).single().text.toString().contains("Second caption"))
        assertTrue(timeline.cuesAt(2_000_000).isEmpty())
    }

    @Test fun `embedded burn in reports progress while reading the video`() = runBlocking {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/subtitles/embedded.mkv")).use { it.readBytes() }
        val source = PlaybackSource(Uri.parse("luna-media://playback/embedded.mkv"), DataSource.Factory { ByteArrayDataSource(bytes) })
        val subtitle = VideoClipSubtitle(mediaTrackIdentity(Format.Builder().setId("2").setLabel("English")
            .setLanguage("eng").setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).build()))
        val progress = mutableListOf<Float?>()
        readVideoClipSubtitles(source, subtitle, 1500, 3500, 0, progress::add)
        assertEquals(0f, progress.first())
        assertTrue(progress.any { it != null && it > 0f && it < 1f })
        assertEquals(1f, progress.last())
    }

    @Test fun `negative delay retains subtitles beyond the unadjusted clip end`() = runBlocking {
        fun element(id: ByteArray, payload: ByteArray): ByteArray =
            id + ByteBuffer.allocate(4).putInt(payload.size or 0x10000000).array() + payload
        val base = checkNotNull(javaClass.getResourceAsStream("/subtitles/embedded.mkv")).use { it.readBytes() }
        val segment = base.indices.first { index ->
            index + 4 < base.size && base.copyOfRange(index, index + 4)
                .contentEquals(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67))
        }
        for (index in segment + 5 until segment + 12) base[index] = 0xff.toByte()
        val block = element(byteArrayOf(0xa1.toByte()),
            byteArrayOf(0x82.toByte(), 0, 0, 0) + "Delayed caption".toByteArray())
        val group = element(byteArrayOf(0xa0.toByte()), block + byteArrayOf(0x9b.toByte(), 0x82.toByte(), 3, 0xe8.toByte()))
        val cluster = element(byteArrayOf(0x1f, 0x43, 0xb6.toByte(), 0x75),
            byteArrayOf(0xe7.toByte(), 0x82.toByte(), 0x9c.toByte(), 0x40) + group)
        val file = temporary.newFile("delayed.mkv").apply { writeBytes(base + cluster) }
        val source = PlaybackSource(Uri.parse("luna-media://playback/embedded.mkv"),
            DataSource.Factory { error("Must use the channel") }) { Files.newByteChannel(file.toPath()) }
        val subtitle = VideoClipSubtitle(mediaTrackIdentity(Format.Builder().setId("2").setLabel("English")
            .setLanguage("eng").setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).build()))
        val timeline = readVideoClipSubtitles(source, subtitle, 0, 1000, -40_000)
        assertEquals("Delayed caption", timeline.cuesAt(0).single().text.toString())
        assertTrue(timeline.cuesAt(1_000_000).isEmpty())
    }

    @Test fun `subtitle free range succeeds when every caption cluster is beyond the clip`() = runBlocking {
        fun element(id: ByteArray, payload: ByteArray): ByteArray =
            id + ByteBuffer.allocate(4).putInt(payload.size or 0x10000000).array() + payload
        val fixture = checkNotNull(javaClass.getResourceAsStream("/subtitles/embedded.mkv")).use { it.readBytes() }
        val clusterId = byteArrayOf(0x1f, 0x43, 0xb6.toByte(), 0x75)
        val firstCluster = fixture.indices.first { index ->
            index + 4 < fixture.size && fixture.copyOfRange(index, index + 4).contentEquals(clusterId)
        }
        val header = fixture.copyOf(firstCluster)
        val segment = header.indices.first { index ->
            index + 4 < header.size && header.copyOfRange(index, index + 4)
                .contentEquals(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67))
        }
        for (index in segment + 5 until segment + 12) header[index] = 0xff.toByte()
        val block = element(byteArrayOf(0xa1.toByte()),
            byteArrayOf(0x82.toByte(), 0, 0, 0) + "Later caption".toByteArray())
        val group = element(byteArrayOf(0xa0.toByte()), block + byteArrayOf(0x9b.toByte(), 0x82.toByte(), 3, 0xe8.toByte()))
        val cluster = element(clusterId, byteArrayOf(0xe7.toByte(), 0x83.toByte(), 1, 0xd4.toByte(), 0xc0.toByte()) + group)
        val file = temporary.newFile("later.mkv").apply { writeBytes(header + cluster) }
        val source = PlaybackSource(Uri.parse("luna-media://playback/embedded.mkv"),
            DataSource.Factory { error("Must use the channel") }) { Files.newByteChannel(file.toPath()) }
        val subtitle = VideoClipSubtitle(mediaTrackIdentity(Format.Builder().setId("2").setLabel("English")
            .setLanguage("eng").setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).build()))
        val timeline = readVideoClipSubtitles(source, subtitle, 0, 1000, 0)
        assertTrue(timeline.cuesAt(0).isEmpty())
        assertTrue(timeline.cuesAt(999_999).isEmpty())
    }

    @Test fun `missing selected subtitle track still fails for an empty clip range`() = runBlocking {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/subtitles/embedded.mkv")).use { it.readBytes() }
        val source = PlaybackSource(Uri.parse("luna-media://playback/embedded.mkv"), DataSource.Factory { ByteArrayDataSource(bytes) })
        try {
            readVideoClipSubtitles(source, VideoClipSubtitle(identity("99")), 0, 1000, 0)
            fail("Expected unavailable subtitle track")
        } catch (_: IllegalStateException) { }
    }

    @Test fun `external burn in reports preparation before completing its parse`() = runBlocking {
        val file = temporary.newFile("progress.srt").apply {
            writeText("1\n00:00:01,000 --> 00:00:03,000\nCaption\n\n")
        }
        val progress = mutableListOf<Float?>()
        val subtitles = readVideoClipSubtitles(unusedSource(), VideoClipSubtitle(identity("external"), file),
            0, 4000, 0, progress::add)
        assertEquals(listOf(0f, 1f), progress)
        assertEquals("Caption", subtitles.cuesAt(1_000_000).single().text.toString())
    }

    @Test fun `cancelling preparation from progress does not report completion`() = runBlocking {
        val file = temporary.newFile("cancelled.srt").apply {
            writeText("1\n00:00:01,000 --> 00:00:03,000\nCaption\n\n")
        }
        val cancelled = CancellationException("Cancelled")
        val progress = mutableListOf<Float?>()
        try {
            readVideoClipSubtitles(unusedSource(), VideoClipSubtitle(identity("external"), file), 0, 4000, 0) {
                progress += it
                throw cancelled
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(listOf(0f), progress)
    }

    @Test fun `unsupported vertical text fails before exporting a frame`() {
        val builder = VideoClipSubtitleBuilder(0, 2000, 0)
        val cue = Cue.Builder().setText("Vertical").setVerticalType(Cue.VERTICAL_TYPE_RL).build()
        builder.add(CuesWithTiming(listOf(cue), 0, 1_000_000), Format.CUE_REPLACEMENT_BEHAVIOR_MERGE)
        try {
            builder.build()
            fail("Expected unsupported subtitle layout")
        } catch (_: IllegalArgumentException) {
            // The export must fail instead of silently omitting the selected subtitles.
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `burned text uses the selected appearance and frames outside a cue are transparent`() {
        val builder = VideoClipSubtitleBuilder(0, 3000, 0)
        builder.add(caption("Caption", 1_000_000, 1_000_000), Format.CUE_REPLACEMENT_BEHAVIOR_MERGE)
        val overlay = createVideoClipSubtitleOverlay(RuntimeEnvironment.getApplication(), builder.build(),
            SubtitleAppearance(enabled = true, textColor = Color.YELLOW, position = SubtitlePosition.TOP,
                backgroundOpacityPercent = 0, preserveColors = false))
        try {
            overlay.configure(Size(640, 360))
            assertTrue(pixels(overlay.getBitmap(0)).all { Color.alpha(it) == 0 })
            val rendered = overlay.getBitmap(1_000_000)
            val visible = pixels(rendered).withIndex().filter { Color.alpha(it.value) > 0 }
            assertFalse(visible.isEmpty())
            assertTrue(visible.all { it.index / rendered.width < rendered.height / 2 })
            assertTrue(visible.any { Color.red(it.value) > 200 && Color.green(it.value) > 200 && Color.blue(it.value) < 50 })
            assertSame(rendered, overlay.getBitmap(1_500_000))
            assertTrue(pixels(overlay.getBitmap(2_000_000)).all { Color.alpha(it) == 0 })
        } finally {
            overlay.release()
            overlay.release()
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `bitmap subtitle pixels and placement survive appearance overrides`() {
        val image = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val cue = Cue.Builder().setBitmap(image).setPosition(.25f).setPositionAnchor(Cue.ANCHOR_TYPE_START)
            .setLine(.5f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START).setSize(.5f).build()
        val builder = VideoClipSubtitleBuilder(0, 2000, 0)
        builder.add(CuesWithTiming(listOf(cue), 0, 1_000_000), Format.CUE_REPLACEMENT_BEHAVIOR_MERGE)
        val overlay = createVideoClipSubtitleOverlay(RuntimeEnvironment.getApplication(), builder.build(),
            SubtitleAppearance(enabled = true, position = SubtitlePosition.TOP, textColor = Color.YELLOW))
        try {
            overlay.configure(Size(160, 90))
            val rendered = overlay.getBitmap(0)
            assertEquals(Color.MAGENTA, rendered.getPixel(60, 55))
            assertEquals(Color.TRANSPARENT, rendered.getPixel(60, 20))
        } finally {
            overlay.release()
            image.recycle()
        }
    }

    private fun caption(text: String, startUs: Long, durationUs: Long) =
        CuesWithTiming(listOf(Cue.Builder().setText(text).build()), startUs, durationUs)

    private fun identity(id: String) = MediaTrackIdentity(id, null, null, null, 0)
    private fun unusedSource() = PlaybackSource(Uri.parse("luna-media://playback/unused"),
        DataSource.Factory { error("External subtitles must not read the video") })
    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}
