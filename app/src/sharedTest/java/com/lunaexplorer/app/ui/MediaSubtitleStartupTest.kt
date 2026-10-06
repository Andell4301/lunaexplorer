package com.lunaexplorer.app.ui

import android.text.Spanned
import android.text.style.StrikethroughSpan
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaSubtitleStartupTest {
    @Test fun assTrackInitializesAndDecodesOverrideBlocks() {
        val header = """
            [Script Info]
            PlayResX: 640
            PlayResY: 360
            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, Bold, Italic, Underline, StrikeOut, Alignment
            Style: Main,Arial,24,&H00FFFFFF,0,0,0,0,2
            [Events]
        """.trimIndent()
        val format = Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).setInitializationData(listOf(
            "Format: Start, End, ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text".toByteArray(),
            header.toByteArray(),
        )).build()
        val parser = OffsetSubtitleParserFactory(SubtitleOffset()).create(format)
        val parsed = mutableListOf<CuesWithTiming>()
        parser.parse("Dialogue: 0:00:00:00,0:00:01:35,0,0,Main,,0,0,0,,{\\s1}Marked{\\s0} plain 字幕".toByteArray(),
            SubtitleParser.OutputOptions.allCues()) { parsed.add(it) }
        val timed = parsed.single()
        assertEquals(1_350_000L, timed.durationUs)
        val text = timed.cues.single().text as Spanned
        assertEquals("Marked plain 字幕", text.toString())
        val strike = text.getSpans(0, text.length, StrikethroughSpan::class.java).single()
        assertEquals(0, text.getSpanStart(strike))
        assertEquals(6, text.getSpanEnd(strike))
    }
}
