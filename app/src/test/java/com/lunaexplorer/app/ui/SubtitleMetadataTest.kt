package com.lunaexplorer.app.ui

import android.app.Application
import android.text.Annotation
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SubtitleMetadataTest {
    @Test fun `metadata includes source tags and decoded styling ranges and annotations`() {
        val header = "[V4+ Styles]\nStyle: Sign,Arial,20"
        val raw = "Dialogue: 0,0:00:01.00,0:00:02.00,Sign,,0,0,0,,{\\fs10\\c&HFF0000&}Sign"
        val format = Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA)
            .setInitializationData(listOf(header.toByteArray())).build()
        val text = SpannableString("Sign").apply {
            setSpan(StrikethroughSpan(), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(0xff0000ff.toInt()), 1, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(Annotation("subtitle-size", "0.5"), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val metadata = SubtitleMetadata()
        metadata.format(format)
        metadata.sample(format, raw.toByteArray(), 0, raw.toByteArray().size)
        metadata.cues(CuesWithTiming(listOf(Cue.Builder().setText(text).setVerticalType(Cue.VERTICAL_TYPE_RL).build()),
            1_000_000L, 1_000_000L))
        val result = metadata.text()
        assertTrue(result.contains(header))
        assertTrue(result.contains(raw))
        assertTrue(result.contains("[0, 4) StrikethroughSpan"))
        assertTrue(result.contains("[1, 3) ForegroundColorSpan color=#FF0000FF"))
        assertTrue(result.contains("subtitle-size=0.5"))
    }

    @Test fun `oversized metadata stops growing without preventing access to subtitle text`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build()
        val metadata = SubtitleMetadata()
        val sample = ByteArray(1024 * 1024) { 'x'.code.toByte() }
        repeat(9) { metadata.sample(format, sample, 0, sample.size) }
        val after = "must not append after truncation".toByteArray()
        metadata.sample(format, after, 0, after.size)
        val result = metadata.text()
        assertTrue(result.length <= 8 * 1024 * 1024)
        assertFalse(result.contains(after.toString(Charsets.UTF_8)))
    }
}
