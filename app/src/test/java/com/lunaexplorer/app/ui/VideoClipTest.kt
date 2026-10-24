package com.lunaexplorer.app.ui

import android.app.Application
import android.media.metrics.LogSessionId
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.transformer.Codec
import androidx.media3.transformer.ExportException
import com.lunaexplorer.app.model.SubtitleAppearance
import com.lunaexplorer.app.playback.PlaybackSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoClipTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `an unselected audio track cannot silently produce a muted clip`() {
        val selection = VideoClipSelection(null, false, null, 0, SubtitleAppearance(), hasAudio = true)
        assertThrows(IllegalArgumentException::class.java) { VideoClipRequest(0, 1000, false, selection) }
    }

    @Test fun `lossless clip writes original compressed frames and leaves subtitles out`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/subtitles/embedded.mp4")).use { it.readBytes() }
        val source = PlaybackSource(Uri.parse("luna-media://playback/movie.mp4"), DataSource.Factory { ByteArrayDataSource(bytes) })
        val output = temporary.newFile("clip.mp4")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val export = executor.submit<Unit> {
                runBlocking {
                    exportVideoClip(RuntimeEnvironment.getApplication(), source,
                        VideoClipRequest(1500, 3500, false, VideoClipSelection(null, false, null, 0, SubtitleAppearance())), output)
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!export.isDone && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
                Thread.sleep(10)
            }
            export.get(1, TimeUnit.SECONDS)
            val original = packets(bytes).single { it.type == C.TRACK_TYPE_VIDEO }.samples
            val clipped = packets(output.readBytes())
            assertEquals(listOf(C.TRACK_TYPE_VIDEO), clipped.map { it.type })
            assertTrue(clipped.single().samples.isNotEmpty())
            assertTrue(clipped.single().samples.size < original.size)
            clipped.single().samples.forEach { (timeUs, payload) ->
                assertTrue(timeUs < 2_000_000)
                assertTrue(original.any { it.second.contentEquals(payload) })
            }
        } finally {
            executor.shutdownNow()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!executor.isTerminated && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
        }
    }

    @Test fun `cancelling subtitle preparation closes the remote read and removes unfinished output`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/subtitles/embedded.mkv")).use { it.readBytes() }
        val reading = CompletableDeferred<Unit>()
        val closed = AtomicBoolean(false)
        val updates = Collections.synchronizedList(mutableListOf<VideoClipProgress>())
        val source = PlaybackSource(Uri.parse("luna-media://playback/movie.mkv"), DataSource.Factory {
            val delegate = ByteArrayDataSource(bytes)
            object : DataSource by delegate {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    reading.complete(Unit)
                    Thread.sleep(30_000)
                    return delegate.read(buffer, offset, length)
                }
                override fun close() { closed.set(true); delegate.close() }
            }
        })
        val output = temporary.newFile("unfinished.mp4")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val stopped = executor.submit<Unit> {
                runBlocking {
                    val subtitle = VideoClipSubtitle(MediaTrackIdentity("2", null, null, null, 0))
                    val export = launch {
                        exportVideoClip(RuntimeEnvironment.getApplication(), source,
                            VideoClipRequest(0, 1000, true, VideoClipSelection(null, false, subtitle, 0, SubtitleAppearance())),
                            output) {
                            assertSame(Looper.getMainLooper(), Looper.myLooper())
                            updates += it
                        }
                    }
                    withTimeout(5000) { reading.await(); export.cancelAndJoin() }
                    assertTrue(export.isCancelled)
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!stopped.isDone && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
                Thread.sleep(10)
            }
            stopped.get(1, TimeUnit.SECONDS)
            assertTrue(closed.get())
            assertFalse(output.exists())
            assertTrue(updates.isNotEmpty())
            assertTrue(updates.all { it.phase == VideoClipPhase.READING_SUBTITLES && it.percent == null })
        } finally {
            executor.shutdownNow()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!executor.isTerminated && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
        }
    }

    @Test fun `selected audio survives merged player track ids and is not replaced with the default language`() {
        val english = audio("1", "en")
        val japanese = audio("2", "ja")
        val groups = listOf(TrackGroup("english", english), TrackGroup("japanese", japanese))
        val selection = selectedClipTrack(groups, mediaTrackIdentity(japanese.buildUpon().setId("0:2").build()))
        assertSame(groups[1], selection!!.first)
        assertEquals(0, selection.second)
        assertNull(selectedClipTrack(groups, mediaTrackIdentity(audio("3", "fr"))))
    }

    @Test fun `indistinguishable audio tracks fail instead of silently selecting one`() {
        val format = audio(null, "en")
        assertThrows(VideoClipFailure::class.java) {
            selectedClipTrack(listOf(TrackGroup("first", format), TrackGroup("second", format)), mediaTrackIdentity(format))
        }
    }

    @Test fun `lossless export refuses both encoders`() {
        val factory = ClipEncoderFactory(null)
        val audioFailure = assertThrows(ExportException::class.java) { factory.createForAudioEncoding(audio("1", "en"), null) }
        val videoFailure = assertThrows(ExportException::class.java) {
            factory.createForVideoEncoding(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(), null)
        }
        assertTrue(audioFailure.cause is VideoClipFailure)
        assertTrue(videoFailure.cause is VideoClipFailure)
    }

    @Test fun `subtitle export delegates video encoding but never audio encoding`() {
        var videoCalled = false
        var audioCalled = false
        val failure = IllegalStateException("encoder unavailable")
        val delegate = object : Codec.EncoderFactory {
            override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec {
                audioCalled = true
                throw failure
            }
            override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec {
                videoCalled = true
                throw failure
            }
        }
        val factory = ClipEncoderFactory(delegate)
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            factory.createForVideoEncoding(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(), null)
        })
        assertThrows(ExportException::class.java) { factory.createForAudioEncoding(audio("1", "en"), null) }
        assertTrue(videoCalled)
        assertFalse(audioCalled)
    }

    private fun audio(id: String?, language: String) = Format.Builder().setId(id)
        .setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage(language).build()

    private fun packets(bytes: ByteArray): List<Packets> {
        val tracks = linkedMapOf<Int, Packets>()
        val extractor = Mp4Extractor()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { Packets(type) }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        var position = 0L
        val seek = PositionHolder()
        try {
            while (true) {
                val stream = ByteArrayInputStream(bytes, position.toInt(), bytes.size - position.toInt())
                val input = DefaultExtractorInput(DataReader { target, offset, length -> stream.read(target, offset, length) },
                    position, bytes.size.toLong())
                var result: Int
                do { result = extractor.read(input, seek) } while (result == Extractor.RESULT_CONTINUE)
                if (result == Extractor.RESULT_END_OF_INPUT) break
                position = seek.position
            }
        } finally {
            extractor.release()
        }
        return tracks.values.toList()
    }

    private class Packets(val type: Int) : TrackOutput {
        val samples = mutableListOf<kotlin.Pair<Long, ByteArray>>()
        private var pending = ByteArrayOutputStream()
        override fun format(format: Format) = Unit
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val bytes = ByteArray(length)
            return input.read(bytes, 0, length).also { if (it > 0) pending.write(bytes, 0, it) }
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val bytes = ByteArray(length)
            data.readBytes(bytes, 0, length)
            pending.write(bytes)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val bytes = pending.toByteArray()
            samples += timeUs to bytes.copyOfRange(bytes.size - offset - size, bytes.size - offset)
            pending = ByteArrayOutputStream().also { it.write(bytes, bytes.size - offset, offset) }
        }
    }
}
