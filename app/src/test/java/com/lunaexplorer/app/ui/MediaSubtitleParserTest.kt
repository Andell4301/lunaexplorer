package com.lunaexplorer.app.ui

import android.app.Application
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spanned
import android.text.Annotation
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
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
import androidx.media3.extractor.text.CueEncoder
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput
import com.lunaexplorer.app.model.SubtitleAppearance
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
    @Test fun `ASS inline emphasis resets without applying strike to preceding text`() {
        val text = parseAss("Gyoku       {\\s1}Yi{\\s0} {\\i1\\u1}aside{\\r} plain").text as Spanned
        assertEquals("Gyoku       Yi aside plain", text.toString())
        val strike = text.getSpans(0, text.length, StrikethroughSpan::class.java).single()
        assertEquals("Yi", text.subSequence(text.getSpanStart(strike), text.getSpanEnd(strike)).toString())
        val italic = text.getSpans(0, text.length, StyleSpan::class.java).single()
        assertEquals(Typeface.ITALIC, italic.style)
        assertEquals("aside", text.subSequence(text.getSpanStart(italic), text.getSpanEnd(italic)).toString())
        assertEquals(1, text.getSpans(0, text.length, UnderlineSpan::class.java).size)
    }

    @Test fun `ASS sign styles retain smaller relative size and distinguish authored color overrides`() {
        val parsed = parseAss("Small {\\fs10.5\\c&H0000FF&}red{\\r} sign", "Sign")
        val encoded = CueEncoder().encode(listOf(parsed), 2_000_000L)
        val cue = CueDecoder().decode(1_000_000L, encoded, 0, encoded.size).cues.single()
        assertEquals(21f / 720f, cue.textSize, .0001f)
        val text = cue.text as Spanned
        val size = text.getSpans(0, text.length, RelativeSizeSpan::class.java).single()
        assertEquals(.5f, size.sizeChange, .0001f)
        assertEquals("red", text.subSequence(text.getSpanStart(size), text.getSpanEnd(size)).toString())
        val ratio = text.getSpans(0, text.length, Annotation::class.java).single { it.key == "luna.subtitle.relativeSize" }
        assertEquals(21f / 52f, ratio.value.toFloat(), .0001f)
        assertTrue(text.getSpans(0, text.length, ForegroundColorSpan::class.java).any { it.foregroundColor == Color.RED })
        val baseline = parseAss("Default").text as Spanned
        assertTrue(baseline.getSpans(0, baseline.length, Annotation::class.java)
            .any { it.key == "luna.subtitle.colorOverride" && it.value == "false" })
    }

    @Test fun `custom appearance keeps top and bottom ASS text at the chosen size regardless of style and explicit position`() {
        listOf(
            "Dialogue" to "Sign",
            "Italic dialogue" to "Aside",
            "{\\i1\\fs26}Inline italic dialogue" to "Main",
            "{\\an8\\fs26}Top dialogue" to "Main",
            "{\\an7\\fs26}Top left text" to "Main",
            "{\\pos(320,36)\\frz15\\i1\\fs10.5}Positioned top text" to "Sign",
            "{\\pos(320,684)\\frz15\\i1\\fs10.5}Positioned bottom text" to "Sign",
            "{\\fscx125\\fscy125\\frz15\\fsp5\\i1\\fs26}Formatted bottom text" to "Main",
            "{\\fn@MS Gothic\\fs26}Vertical bottom text" to "Main",
            "{\\fn@MS Gothic\\pos(320,36)\\fs26}Vertical top text" to "Main",
            "{\\fn@MS Gothic\\r\\fs26}Horizontal dialogue" to "Main",
        ).forEach { (text, style) ->
            val cue = styledAssCue(text, style)
            assertEquals(text, 100f, effectiveTextSize(cue), .001f)
            assertEquals(Cue.DIMEN_UNSET, cue.textSize)
        }
        val italic = styledAssCue("{\\i1\\fs26}Italic dialogue").text as Spanned
        assertEquals(Typeface.ITALIC, italic.getSpans(0, italic.length, StyleSpan::class.java).single().style)
    }

    @Test fun `custom appearance preserves relative sizes for ASS text away from the top and bottom including italics`() {
        listOf(
            "\\pos(320,180)",
            "\\move(320,180,640,180)",
            "\\an5",
            "\\fn@MS Gothic\\pos(320,180)",
            "\\frz15\\pos(320,180)",
        ).forEach { placement ->
            val cue = styledAssCue("{$placement\\i1\\fs10.5}Sign", "Sign")
            assertEquals(placement, 100f * 10.5f / 52f, effectiveTextSize(cue), .001f)
            val text = cue.text as Spanned
            assertTrue(text.getSpans(0, text.length, StyleSpan::class.java).any { it.style and Typeface.ITALIC != 0 })
        }
    }

    @Test fun `ASS vertical font cues retain top to bottom writing and authored position`() {
        val cue = parseAss("{\\fn@MS Gothic\\pos(320,180)}字幕")
        assertEquals(Cue.VERTICAL_TYPE_RL, cue.verticalType)
        assertEquals(.25f, cue.position, .0001f)
        assertEquals(.75f, cue.line, .0001f)
        assertEquals(Cue.TYPE_UNSET, parseAss("{\\fn@MS Gothic\\r}Horizontal").verticalType)
    }

    @Test fun `ASS omitted emphasis and size parameters restore the style defaults`() {
        val text = parseAss("{\\i1\\s1\\u1\\fs13}small{\\i\\s\\u\\fs} plain{\\b400} regular").text as Spanned
        assertEquals("small plain regular", text.toString())
        val italic = text.getSpans(0, text.length, StyleSpan::class.java).single()
        assertEquals(Typeface.ITALIC, italic.style)
        assertEquals(5, text.getSpanEnd(italic))
        assertEquals(5, text.getSpanEnd(text.getSpans(0, text.length, RelativeSizeSpan::class.java).single()))
        assertEquals(5, text.getSpanEnd(text.getSpans(0, text.length, StrikethroughSpan::class.java).single()))
        assertEquals(5, text.getSpanEnd(text.getSpans(0, text.length, UnderlineSpan::class.java).single()))
    }

    @Test fun `Matroska ASS initialization and relative samples retain English dialogue`() {
        val initialization = assScript("").replace("\n", "\r\n").substringBefore("Dialogue:")
        val format = Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).setInitializationData(listOf(
            "Format: Start, End, ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text".toByteArray(),
            initialization.toByteArray(),
        )).build()
        val parser = OffsetSubtitleParserFactory(SubtitleOffset()).create(format)
        val parsed = mutableListOf<CuesWithTiming>()
        parser.parse("Dialogue: 0:00:00:00,0:00:01:35,0,0,Main,islander 1,0000,0000,0000,,Princess!".toByteArray(),
            SubtitleParser.OutputOptions.allCues()) { parsed.add(it) }
        assertEquals("Princess!", parsed.single().cues.single().text.toString())
        assertEquals(1_350_000L, parsed.single().durationUs)
    }

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

    private fun parseAss(text: String, style: String = "Main"): Cue {
        val parser = OffsetSubtitleParserFactory(SubtitleOffset()).create(Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build())
        val parsed = mutableListOf<CuesWithTiming>()
        parser.parse(assScript(text, style).toByteArray(), SubtitleParser.OutputOptions.allCues()) { parsed.add(it) }
        return parsed.single { it.cues.isNotEmpty() }.cues.single()
    }

    private fun styledAssCue(text: String, style: String = "Main"): Cue {
        val encoded = CueEncoder().encode(listOf(parseAss(text, style)), 2_000_000L)
        val decoded = CueDecoder().decode(1_000_000L, encoded, 0, encoded.size).cues.single()
        return styledSubtitleCues(listOf(decoded), SubtitleAppearance(enabled = true)).single()
    }

    private fun effectiveTextSize(cue: Cue): Float {
        val text = cue.text as Spanned
        val paint = TextPaint().apply { textSize = 100f }
        text.getSpans(0, 1, MetricAffectingSpan::class.java).forEach { it.updateMeasureState(paint) }
        return paint.textSize
    }

    private fun assScript(text: String, style: String = "Main") = """
        [Script Info]
        PlayResX: 1280
        PlayResY: 720
        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, Bold, Italic, Underline, StrikeOut, Alignment
        Style: Main,Arial,52,&H00FFFFFF,0,0,0,0,2
        Style: Main2,Arial,52,&H00FFFFFF,0,0,0,0,2
        Style: Sign,Arial,21,&H00ADC6D1,-1,0,0,0,2
        Style: Aside,Serif,30,&H00FFFFFF,0,-1,0,0,2
        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.00,0:00:03.00,$style,,0,0,0,,$text
    """.trimIndent()
}
