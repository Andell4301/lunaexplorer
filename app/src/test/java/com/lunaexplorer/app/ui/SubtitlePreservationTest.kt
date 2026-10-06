package com.lunaexplorer.app.ui

import android.app.Application
import android.graphics.Color
import android.graphics.Typeface
import android.text.Annotation
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.Base64
import android.view.ViewGroup
import android.webkit.WebView
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import com.lunaexplorer.app.model.SubtitleAppearance
import com.lunaexplorer.app.model.SubtitlePosition
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SubtitlePreservationTest {
    @Test fun `custom appearance keeps emphasis and inline colors while replacing base styling`() {
        val text = SpannableString("Normal marked").apply {
            setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(Annotation("luna.subtitle.colorOverride", "false"), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Color.RED), 7, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(Annotation("luna.subtitle.colorOverride", "true"), 7, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.ITALIC), 7, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StrikethroughSpan(), 7, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(UnderlineSpan(), 7, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(TypefaceSpan("serif"), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(BackgroundColorSpan(Color.BLUE), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val original = Cue.Builder().setText(text).setTextSize(.08f, Cue.TEXT_SIZE_TYPE_FRACTIONAL)
            .setWindowColor(Color.BLUE).build()
        val rendered = styledSubtitleCues(listOf(original), SubtitleAppearance(enabled = true)).single()
        val spans = rendered.text as Spanned
        assertEquals(listOf(Color.RED), spans.getSpans(0, spans.length, ForegroundColorSpan::class.java).map { it.foregroundColor })
        assertEquals(Typeface.ITALIC, spans.getSpans(0, spans.length, StyleSpan::class.java).single().style)
        assertEquals(1, spans.getSpans(0, spans.length, StrikethroughSpan::class.java).size)
        assertEquals(1, spans.getSpans(0, spans.length, UnderlineSpan::class.java).size)
        assertTrue(spans.getSpans(0, spans.length, TypefaceSpan::class.java).isEmpty())
        assertTrue(spans.getSpans(0, spans.length, BackgroundColorSpan::class.java).isEmpty())
        assertFalse(rendered.windowColorSet)
        assertEquals(Cue.DIMEN_UNSET, rendered.textSize)

        val removed = styledSubtitleCues(listOf(original), SubtitleAppearance(enabled = true,
            preserveEmphasis = false, preserveColors = false)).single().text as Spanned
        assertTrue(removed.getSpans(0, removed.length, StyleSpan::class.java).isEmpty())
        assertTrue(removed.getSpans(0, removed.length, StrikethroughSpan::class.java).isEmpty())
        assertTrue(removed.getSpans(0, removed.length, UnderlineSpan::class.java).isEmpty())
        assertTrue(removed.getSpans(0, removed.length, ForegroundColorSpan::class.java).isEmpty())
        assertSame(original, styledSubtitleCues(listOf(original), SubtitleAppearance()).single())
    }

    @Test fun `authored small signs and inline sizes remain relative to the custom text size`() {
        val text = SpannableString("Small sign").apply {
            setSpan(Annotation("luna.subtitle.relativeSize", "0.5"), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(.8f), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val original = Cue.Builder().setText(text).setTextSize(.03f, Cue.TEXT_SIZE_TYPE_FRACTIONAL)
            .setPosition(.2f).setLine(.3f, Cue.LINE_TYPE_FRACTION).build()
        val cue = styledSubtitleCues(listOf(original), SubtitleAppearance(enabled = true, textSizePercent = 150)).single()
        val spans = cue.text as Spanned
        val paint = TextPaint().apply { textSize = 100f }
        spans.getSpans(0, 5, RelativeSizeSpan::class.java).forEach { it.updateMeasureState(paint) }
        assertEquals(40f, paint.textSize, .001f)
        assertEquals(Cue.DIMEN_UNSET, cue.textSize)
        val removed = styledSubtitleCues(listOf(original), SubtitleAppearance(enabled = true,
            preserveRelativeSizes = false)).single().text as Spanned
        assertTrue(removed.getSpans(0, removed.length, RelativeSizeSpan::class.java).isEmpty())
    }

    @Test fun `top bottom and default cues discard authored sizes while retaining italic emphasis`() {
        val text = SpannableString("Italic dialogue").apply {
            setSpan(RelativeSizeSpan(.5f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.ITALIC), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val original = Cue.Builder().setText(text).setTextSize(.03f, Cue.TEXT_SIZE_TYPE_FRACTIONAL).build()
        val positioned = listOf(original) + listOf(0f, .1f, .9f, 1f).map {
            original.buildUpon().setLine(it, Cue.LINE_TYPE_FRACTION).build()
        } + listOf(1f, -1f).map {
            original.buildUpon().setLine(it, Cue.LINE_TYPE_NUMBER).build()
        }
        styledSubtitleCues(positioned, SubtitleAppearance(enabled = true)).forEach { cue ->
            val spans = cue.text as Spanned
            assertTrue(spans.getSpans(0, spans.length, RelativeSizeSpan::class.java).isEmpty())
            assertEquals(Typeface.ITALIC, spans.getSpans(0, spans.length, StyleSpan::class.java).single().style)
            assertEquals(Cue.DIMEN_UNSET, cue.textSize)
        }
        assertSame(original, styledSubtitleCues(listOf(original), SubtitleAppearance()).single())
    }

    @Test fun `absolute text sizes remain relative only between the top and bottom regions`() {
        val text = SpannableString("Small LARGE").apply {
            setSpan(AbsoluteSizeSpan(20), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(AbsoluteSizeSpan(40), 6, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        listOf(.1f to 100f, .11f to 50f, .5f to 50f, .89f to 50f, .9f to 100f).forEach { (line, expected) ->
            val original = Cue.Builder().setText(text).setPosition(.5f).setLine(line, Cue.LINE_TYPE_FRACTION).build()
            val cue = styledSubtitleCues(listOf(original), SubtitleAppearance(enabled = true)).single()
            val spans = cue.text as Spanned
            assertTrue(spans.getSpans(0, spans.length, AbsoluteSizeSpan::class.java).isEmpty())
            val paint = TextPaint().apply { textSize = 100f }
            spans.getSpans(0, 1, RelativeSizeSpan::class.java).forEach { it.updateMeasureState(paint) }
            assertEquals("line=$line", expected, paint.textSize, .001f)
        }
    }

    @Test fun `custom top positioning keeps vertical text separate and moves its vertical anchor`() {
        val vertical = Cue.Builder().setText("縦書き").setVerticalType(Cue.VERTICAL_TYPE_RL)
            .setPosition(.2f).setPositionAnchor(Cue.ANCHOR_TYPE_START)
            .setLine(.1f, Cue.LINE_TYPE_FRACTION).build()
        val horizontal = Cue.Builder().setText("Dialogue").build()
        val result = positionSubtitleCues(listOf(vertical, horizontal), SubtitleAppearance(enabled = true,
            position = SubtitlePosition.TOP, verticalMarginPercent = 12))
        assertEquals(2, result.size)
        assertEquals(Cue.VERTICAL_TYPE_RL, result[0].verticalType)
        assertEquals(.12f, result[0].position, .0001f)
        assertEquals(Cue.ANCHOR_TYPE_START, result[0].positionAnchor)
        assertEquals(Cue.TYPE_UNSET, result[1].verticalType)
        assertEquals(.12f, result[1].line, .0001f)
        assertEquals("縦書き", result[0].text.toString())
        assertEquals("Dialogue", result[1].text.toString())
    }

    @Test fun `vertical cues reuse their writing mode renderer between lines and release it on close`() {
        val view = LunaSubtitleView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 1280, 720)
        view.setCues(listOf(Cue.Builder().setText("縦書き").setVerticalType(Cue.VERTICAL_TYPE_RL).build()))
        val vertical = view.getChildAt(1) as SubtitleView
        val output = vertical.getChildAt(0) as ViewGroup
        val web = (0 until output.childCount).map { output.getChildAt(it) }.filterIsInstance<WebView>().single()
        val html = String(Base64.decode(shadowOf(web).lastLoadData.data, Base64.DEFAULT), Charsets.UTF_8)
        assertTrue(html.contains("writing-mode:vertical-rl"))
        view.setCues(emptyList())
        assertEquals(2, view.childCount)
        assertEquals(android.view.View.INVISIBLE, vertical.visibility)
        assertFalse(shadowOf(web).wasDestroyCalled())
        view.setCues(listOf(Cue.Builder().setText("続き").setVerticalType(Cue.VERTICAL_TYPE_RL).build()))
        assertSame(vertical, view.getChildAt(1))
        assertEquals(android.view.View.VISIBLE, vertical.visibility)
        view.setCues(null)
        assertEquals(1, view.childCount)
        assertTrue(shadowOf(web).wasDestroyCalled())
    }
}
