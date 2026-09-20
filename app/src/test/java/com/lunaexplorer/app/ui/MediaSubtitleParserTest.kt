package com.lunaexplorer.app.ui

import android.app.Application
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.DataReader
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaSubtitleParserTest {
    @Test fun `ASS color emphasis and positioning survive through the actual lightweight parser`() {
        val parser = OffsetSubtitleParserFactory(SubtitleOffset()).create(Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build())
        val script = """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 640
            PlayResY: 360
            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, Bold, Italic, Underline, StrikeOut, Alignment
            Style: Default,Arial,24,&H000000FF,-1,-1,0,0,2
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\pos(320,180)}Styled caption
        """.trimIndent().toByteArray()
        val parsed = mutableListOf<CuesWithTiming>()
        parser.parse(script, SubtitleParser.OutputOptions.allCues()) { parsed.add(it) }

        val timed = parsed.first { it.cues.isNotEmpty() }
        assertEquals(1_000_000L, timed.startTimeUs)
        val cue = timed.cues.single()
        assertEquals(.5f, cue.position, .001f)
        assertEquals(.5f, cue.line, .001f)
        val text = cue.text as Spanned
        assertEquals("Styled caption", text.toString())
        assertEquals(Typeface.BOLD_ITALIC, text.getSpans(0, text.length, StyleSpan::class.java).single().style)
        assertEquals(Color.RED, text.getSpans(0, text.length, ForegroundColorSpan::class.java).single().foregroundColor)
    }

    @Test fun `positive and negative delays shift real SRT cues without changing duration`() {
        listOf(-250_000L, 250_000L).forEach { delay ->
            val offset = SubtitleOffset().apply { microseconds = delay }
            val parser = OffsetSubtitleParserFactory(offset).create(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build())
            val parsed = mutableListOf<CuesWithTiming>()
            parser.parse("1\n00:00:01,000 --> 00:00:03,000\nCaption\n\n".toByteArray(),
                SubtitleParser.OutputOptions.allCues()) { parsed.add(it) }
            val cue = parsed.first { it.cues.isNotEmpty() }
            assertEquals(1_000_000L + delay, cue.startTimeUs)
            assertEquals(2_000_000L, cue.durationUs)
        }
    }

    @Test fun `seek filtering uses the original subtitle time and retains remaining cue policy`() {
        val delegate = RecordingParser()
        val parser = OffsetSubtitleParser(delegate, 250_000L)
        parser.parse(byteArrayOf(), SubtitleParser.OutputOptions.cuesAfterThenRemainingCuesBefore(2_000_000L)) {}
        assertEquals(1_750_000L, delegate.options!!.startTimeUs)
        assertTrue(delegate.options!!.outputAllCues)
        parser.parse(byteArrayOf(), SubtitleParser.OutputOptions.onlyCuesAfter(2_000_000L)) {}
        assertFalse(delegate.options!!.outputAllCues)
    }

    @Test fun `sample relative subtitles shift without arithmetic on TIME_UNSET or rebuilding styling`() {
        val cue = Cue.Builder().setText("Caption").build()
        val delegate = RecordingParser(CuesWithTiming(listOf(cue), C.TIME_UNSET, 1_000_000L))
        val parsed = mutableListOf<CuesWithTiming>()
        OffsetSubtitleParser(delegate, -250_000L).parse(byteArrayOf(), SubtitleParser.OutputOptions.allCues()) { parsed.add(it) }
        assertEquals(-250_000L, parsed.single().startTimeUs)
        assertEquals(1_000_000L, parsed.single().durationUs)
        assertSame(cue, parsed.single().cues.single())
    }

    @Test fun `cached subtitle extensions map only to formats supported by the parser`() {
        val factory = OffsetSubtitleParserFactory(SubtitleOffset())
        listOf("字幕\u200b.ASS", "sub.ssa", "sub.srt", "sub.vtt", "sub.ttml", "sub.dfxp").forEach { filename ->
            assertTrue(factory.supportsFormat(Format.Builder().setSampleMimeType(subtitleMimeType(filename)).build()))
        }
        assertFalse(isSubtitleFile("unsupported.sami"))
    }

    @Test fun `Media3 extraction keeps embedded sample timestamps with zero and nonzero delays`() {
        listOf(0L, -250_000L, 250_000L).forEach { delay ->
            val timestamps = mutableListOf<Long>()
            val trackOutput = object : TrackOutput {
                override fun format(format: Format) = Unit
                override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
                    input.read(ByteArray(length), 0, length)
                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                    timestamps.add(timeUs)
                }
            }
            val delegate = object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = trackOutput
                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) = Unit
            }
            val factory = object : SubtitleParser.Factory {
                override fun supportsFormat(format: Format): Boolean = true
                override fun getCueReplacementBehavior(format: Format): Int = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
                override fun create(format: Format): SubtitleParser = OffsetSubtitleParser(
                    RecordingParser(CuesWithTiming(listOf(Cue.Builder().setText("Embedded").build()), C.TIME_UNSET, 1_000_000L)), delay)
            }
            val input = SubtitleTranscodingExtractorOutput(delegate, factory).track(0, C.TRACK_TYPE_TEXT)
            input.format(Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA)
                .setSubsampleOffsetUs(Format.OFFSET_SAMPLE_RELATIVE).build())
            input.sampleData(ParsableByteArray(byteArrayOf(1)), 1)
            input.sampleMetadata(5_000_000L, C.BUFFER_FLAG_KEY_FRAME, 1, 0, null)
            assertEquals(listOf(5_000_000L + delay), timestamps)
        }
    }

    @Test fun `merged external tracks match their configuration and audio choices survive first subtitle attachment`() {
        assertEquals("subtitle-123.ass", unmergedMediaTrackId("1:subtitle-123.ass"))
        assertEquals("subtitle-123.ass", unmergedMediaTrackId("0:1:subtitle-123.ass"))
        val audio = Format.Builder().setId("2").setLabel("Japanese commentary").setLanguage("ja")
            .setSampleMimeType(MimeTypes.AUDIO_AAC).setRoleFlags(C.ROLE_FLAG_COMMENTARY).build()
        val mergedAudio = audio.buildUpon().setId("0:2").build()
        assertEquals(mediaTrackIdentity(audio), mediaTrackIdentity(mergedAudio))
        assertFalse(mediaTrackIdentity(audio) == mediaTrackIdentity(mergedAudio.buildUpon().setRoleFlags(0).build()))
    }

    @Test fun `normalizing compact ASS headers keeps UTF16 Unicode text and complete sections`() {
        val compact = "\uFEFF[Script Info]\nPlayResY: 360\n[V4+ Styles]\nStyle: Default,字幕\n[Events]\nDialogue: 字幕"
        val normalized = String(normalizeSsaSections(compact.toByteArray(Charsets.UTF_16LE)), Charsets.UTF_8)
        assertTrue(normalized.contains("PlayResY: 360\n\n[V4+ Styles]"))
        assertTrue(normalized.contains("Style: Default,字幕\n\n[Events]"))
        assertTrue(normalized.endsWith("Dialogue: 字幕"))
    }

    private class RecordingParser(private val result: CuesWithTiming? = null) : SubtitleParser {
        var options: SubtitleParser.OutputOptions? = null
        override fun getCueReplacementBehavior(): Int = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
        override fun parse(data: ByteArray, offset: Int, length: Int, outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>) {
            options = outputOptions
            result?.let(output::accept)
        }
    }
}
