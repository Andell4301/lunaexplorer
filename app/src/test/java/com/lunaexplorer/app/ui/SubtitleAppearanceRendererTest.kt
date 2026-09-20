package com.lunaexplorer.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.graphics.Paint
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.lunaexplorer.app.model.SubtitleAppearance
import com.lunaexplorer.app.model.SubtitleEdge
import com.lunaexplorer.app.model.SubtitleFont
import com.lunaexplorer.app.model.SubtitlePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SubtitleAppearanceRendererTest {
    @Test fun `custom styling removes authored spans and disabling restores the active cue without a new event`() {
        val original = authoredCue("Caption")
        val view = LunaSubtitleView(RuntimeEnvironment.getApplication())
        view.setCues(listOf(original))
        assertSame(original, outputCues(view).single())

        view.appearance = SubtitleAppearance(enabled = true, textSizePercent = 150, textColor = Color.YELLOW,
            font = SubtitleFont.MONOSPACE, bold = true, backgroundOpacityPercent = 50, edge = SubtitleEdge.OUTLINE)
        val rendered = outputCues(view).single()
        assertEquals(original.line, rendered.line)
        assertEquals(original.position, rendered.position)
        assertEquals(Cue.DIMEN_UNSET, rendered.textSize)
        assertFalse(rendered.windowColorSet)
        assertTrue((rendered.text as Spanned).getSpans(0, rendered.text!!.length, Any::class.java).isEmpty())
        val style = ReflectionHelpers.getField<CaptionStyleCompat>(output(view), "style")
        assertEquals(Color.YELLOW, style.foregroundColor)
        assertEquals(128, Color.alpha(style.backgroundColor))
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, style.edgeType)
        assertEquals(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD), style.typeface)
        assertEquals(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.5f,
            ReflectionHelpers.getField<Float>(output(view), "textSize"), .0001f)

        view.appearance = view.appearance.copy(enabled = false)
        assertSame(original, outputCues(view).single())
        val spans = original.text as Spanned
        assertEquals(Color.RED, spans.getSpans(0, spans.length, ForegroundColorSpan::class.java).single().foregroundColor)
        assertEquals(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION,
            ReflectionHelpers.getField<Float>(output(view), "textSize"), .0001f)
        assertEquals(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION,
            ReflectionHelpers.getField<Float>(output(view), "bottomPaddingFraction"), .0001f)
    }

    @Test fun `top and bottom override explicitly authored positions with correct edge anchors`() {
        val original = authoredCue("Positioned dialogue")
        val view = LunaSubtitleView(RuntimeEnvironment.getApplication())
        view.setCues(listOf(original))
        view.appearance = SubtitleAppearance(enabled = true, position = SubtitlePosition.TOP, verticalMarginPercent = 12)
        val top = outputCues(view).single()
        assertEquals(.12f, top.line, .0001f)
        assertEquals(Cue.ANCHOR_TYPE_START, top.lineAnchor)
        assertEquals(.5f, top.position, .0001f)
        view.appearance = view.appearance.copy(position = SubtitlePosition.BOTTOM)
        val bottom = outputCues(view).single()
        assertEquals(.88f, bottom.line, .0001f)
        assertEquals(Cue.ANCHOR_TYPE_END, bottom.lineAnchor)
        view.appearance = view.appearance.copy(position = SubtitlePosition.FROM_FILE)
        val restoredPosition = outputCues(view).single()
        assertEquals(original.line, restoredPosition.line)
        assertEquals(original.position, restoredPosition.position)
    }

    @Test fun `simultaneous captions stack and bitmap subtitles retain their original pixels and placement`() {
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        val imageCue = Cue.Builder().setBitmap(bitmap).setPosition(.7f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .setLine(.4f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_END).setSize(.3f).build()
        val appearance = SubtitleAppearance(enabled = true, position = SubtitlePosition.BOTTOM)
        val result = positionSubtitleCues(listOf(authoredCue("First"), imageCue, authoredCue("Second")), appearance)
        assertEquals(2, result.size)
        assertEquals("First\nSecond", result[0].text.toString())
        assertSame(imageCue, result[1])
        val view = LunaSubtitleView(RuntimeEnvironment.getApplication())
        view.appearance = appearance
        view.setCues(listOf(imageCue))
        val rendered = outputCues(view).single()
        assertSame(bitmap, rendered.bitmap)
        assertEquals(imageCue.line, rendered.line)
        assertEquals(imageCue.position, rendered.position)
        assertEquals(imageCue.size, rendered.size)
    }

    @Test fun `clearing cues stays cleared when appearance changes and next track gets current appearance`() {
        val view = LunaSubtitleView(RuntimeEnvironment.getApplication())
        view.setCues(listOf(authoredCue("First track")))
        view.setCues(null)
        view.appearance = SubtitleAppearance(enabled = true, position = SubtitlePosition.TOP)
        assertTrue(outputCues(view).isEmpty())
        view.setCues(listOf(authoredCue("Second track")))
        val rendered = outputCues(view).single()
        assertEquals("Second track", rendered.text.toString())
        assertEquals(.08f, rendered.line, .0001f)
        assertEquals(Cue.DIMEN_UNSET, rendered.textSize)
    }

    @Test fun `preview uses readable scaled sp while playback keeps fractional video sizing`() {
        val context = RuntimeEnvironment.getApplication()
        val preview = LunaSubtitleView(context, previewTextSizeSp = 18f)
        val playback = LunaSubtitleView(context)
        val appearance = SubtitleAppearance(enabled = true, textSizePercent = 200)
        preview.appearance = appearance
        playback.appearance = appearance
        assertEquals(Cue.TEXT_SIZE_TYPE_ABSOLUTE, ReflectionHelpers.getField<Int>(output(preview), "textSizeType"))
        assertEquals(36f * context.resources.displayMetrics.scaledDensity,
            ReflectionHelpers.getField<Float>(output(preview), "textSize"), .001f)
        assertEquals(Cue.TEXT_SIZE_TYPE_FRACTIONAL, ReflectionHelpers.getField<Int>(output(playback), "textSizeType"))
        assertEquals(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 2f,
            ReflectionHelpers.getField<Float>(output(playback), "textSize"), .0001f)
    }

    @Test fun `a thicker outline survives the stripping and widens only the stroking pass`() {
        // Media3 has no outline-width setting; the span scales strokeWidth on its FILL_AND_STROKE pass.
        val view = LunaSubtitleView(RuntimeEnvironment.getApplication())
        view.setCues(listOf(authoredCue("Caption")))
        view.appearance = SubtitleAppearance(enabled = true, edge = SubtitleEdge.OUTLINE,
            outlineThicknessPercent = 250)

        val rendered = outputCues(view).single().text as Spanned
        val widths = rendered.getSpans(0, rendered.length, CharacterStyle::class.java)
        assertEquals("It has to outlive the player stripping the authored styling off the cue",
            1, widths.size)

        val outline = TextPaint().apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = 4f }
        widths.single().updateDrawState(outline)
        assertEquals(10f, outline.strokeWidth, .001f)
        val letters = TextPaint().apply { style = Paint.Style.FILL; strokeWidth = 4f }
        widths.single().updateDrawState(letters)
        assertEquals("The lettering itself is left exactly as the player drew it",
            4f, letters.strokeWidth, .001f)
    }

    @Test fun `the ordinary thickness asks for nothing and a shadow has no outline to widen`() {
        val view = LunaSubtitleView(RuntimeEnvironment.getApplication())
        view.setCues(listOf(authoredCue("Caption")))

        view.appearance = SubtitleAppearance(enabled = true, edge = SubtitleEdge.OUTLINE)
        val untouched = outputCues(view).single().text as Spanned
        assertTrue("At the player's own width there is nothing to ask for",
            untouched.getSpans(0, untouched.length, CharacterStyle::class.java).isEmpty())

        view.appearance = SubtitleAppearance(enabled = true, edge = SubtitleEdge.SHADOW,
            outlineThicknessPercent = 250)
        val shadowed = outputCues(view).single().text as Spanned
        assertTrue("A shadow is spread, not stroked",
            shadowed.getSpans(0, shadowed.length, CharacterStyle::class.java).isEmpty())
    }

    @Test fun `a picture subtitle is handed over untouched however thick the outline`() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val picture = Cue.Builder().setBitmap(bitmap).setPosition(.5f).build()
        val view = LunaSubtitleView(RuntimeEnvironment.getApplication())
        view.appearance = SubtitleAppearance(enabled = true, edge = SubtitleEdge.OUTLINE,
            outlineThicknessPercent = 300)

        view.setCues(listOf(picture))

        assertSame(bitmap, outputCues(view).single().bitmap)
    }

    @Test fun `captions that would land on the same spot are stacked, not piled up`() {
        val first = Cue.Builder().setText("First").build()
        val second = Cue.Builder().setText("Second").build()

        val stacked = stackedSubtitleCues(listOf(first, second))

        assertEquals(1, stacked.size)
        assertEquals("First\nSecond", stacked.single().text.toString())
        assertEquals("And it stays where the cues wanted to be", Cue.DIMEN_UNSET, stacked.single().line)
    }

    @Test fun `a caption the author placed is left where it was put`() {
        val dialogue = Cue.Builder().setText("Dialogue")
            .setLine(.95f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_END).build()
        val sign = Cue.Builder().setText("Sign")
            .setLine(.1f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START).build()
        val alongside = Cue.Builder().setText("More dialogue")
            .setLine(.95f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_END).build()

        val stacked = stackedSubtitleCues(listOf(dialogue, sign, alongside))

        assertEquals(2, stacked.size)
        assertEquals("Dialogue\nMore dialogue", stacked[0].text.toString())
        assertEquals(.95f, stacked[0].line, .0001f)
        assertSame("A sign of its own keeps its place and its identity", sign, stacked[1])
    }

    @Test fun `a picture caption is never merged and a lone caption is untouched`() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val picture = Cue.Builder().setBitmap(bitmap).setPosition(.5f).build()
        val lone = Cue.Builder().setText("Only one").build()

        val cues = listOf(lone, picture)
        val stacked = stackedSubtitleCues(cues)

        assertSame("Nothing collided, so nothing was rebuilt", cues, stacked)
    }

    private fun authoredCue(label: String): Cue {
        val text = SpannableString(label).apply {
            setSpan(ForegroundColorSpan(Color.RED), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.ITALIC), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(AbsoluteSizeSpan(42), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return Cue.Builder().setText(text).setTextSize(.12f, Cue.TEXT_SIZE_TYPE_FRACTIONAL)
            .setWindowColor(Color.BLUE).setPosition(.25f).setPositionAnchor(Cue.ANCHOR_TYPE_START)
            .setLine(.35f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE).build()
    }

    // Media3's internal renderer view: its private fields hold what is drawn, after embedded-style stripping.
    private fun output(view: LunaSubtitleView): View = (view.getChildAt(0) as SubtitleView).getChildAt(0)
    private fun outputCues(view: LunaSubtitleView): List<Cue> = ReflectionHelpers.getField(output(view), "cues")
}
