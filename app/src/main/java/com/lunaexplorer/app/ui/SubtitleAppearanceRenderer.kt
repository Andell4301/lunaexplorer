package com.lunaexplorer.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.util.TypedValue
import android.widget.FrameLayout
import androidx.media3.common.text.Cue
import androidx.media3.common.text.LanguageFeatureSpan
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.lunaexplorer.app.model.SubtitleAppearance
import com.lunaexplorer.app.model.SubtitleEdge
import com.lunaexplorer.app.model.SubtitleFont
import com.lunaexplorer.app.model.SubtitlePosition
import kotlin.math.roundToInt

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class LunaSubtitleView @JvmOverloads constructor(
    context: Context,
    private val previewTextSizeSp: Float? = null,
) : FrameLayout(context) {
    private val subtitleView = SubtitleView(context)
    private var originalCues: List<Cue> = emptyList()

    var appearance: SubtitleAppearance = SubtitleAppearance()
        set(value) {
            if (field == value) return
            field = value
            applyAppearance()
        }

    init {
        addView(subtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyAppearance()
    }

    fun setCues(cues: List<Cue>?) {
        originalCues = cues?.toList() ?: emptyList()
        renderCues()
    }

    private fun applyAppearance() {
        if (appearance.enabled) {
            subtitleView.setApplyEmbeddedStyles(false)
            subtitleView.setApplyEmbeddedFontSizes(false)
            subtitleView.setStyle(subtitleCaptionStyle(appearance))
            subtitleView.setFractionalTextSize(
                SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * appearance.textSizePercent.coerceIn(50, 200) / 100f,
            )
            subtitleView.setBottomPaddingFraction(appearance.verticalMarginPercent.coerceIn(0, 30) / 100f)
        } else {
            // The defaults PlayerView gives its own SubtitleView: the user's caption style and size,
            // embedded styling on.
            subtitleView.setApplyEmbeddedStyles(true)
            subtitleView.setApplyEmbeddedFontSizes(true)
            subtitleView.setUserDefaultStyle()
            subtitleView.setUserDefaultTextSize()
            subtitleView.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        }
        previewTextSizeSp?.let { size ->
            val percent = if (appearance.enabled) appearance.textSizePercent.coerceIn(50, 200) else 100
            subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, size * percent / 100f)
        }
        renderCues()
    }

    private fun renderCues() {
        subtitleView.setCues(
            outlinedSubtitleCues(positionSubtitleCues(stackedSubtitleCues(originalCues), appearance), appearance),
        )
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun subtitleCaptionStyle(appearance: SubtitleAppearance): CaptionStyleCompat {
    val typeface = when (appearance.font) {
        SubtitleFont.DEFAULT -> Typeface.DEFAULT
        SubtitleFont.SANS_SERIF -> Typeface.SANS_SERIF
        SubtitleFont.SERIF -> Typeface.SERIF
        SubtitleFont.MONOSPACE -> Typeface.MONOSPACE
    }
    val opacity = (appearance.backgroundOpacityPercent.coerceIn(0, 100) * 255f / 100f).roundToInt()
    return CaptionStyleCompat(
        appearance.textColor,
        (appearance.backgroundColor and 0x00FFFFFF) or (opacity shl 24),
        Color.TRANSPARENT,
        when (appearance.edge) {
            SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
            SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            SubtitleEdge.SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        },
        appearance.edgeColor,
        Typeface.create(typeface, if (appearance.bold) Typeface.BOLD else Typeface.NORMAL),
    )
}

// Simultaneous text cues at the same position overlap unless merged; keep bitmap cues and other positions separate.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun stackedSubtitleCues(cues: List<Cue>): List<Cue> {
    if (cues.size < 2) return cues
    fun placement(cue: Cue): Cue = cue.buildUpon().setText("").build()
    val together = LinkedHashMap<Cue, MutableList<Cue>>()
    cues.forEach { cue ->
        if (cue.bitmap != null || cue.text.isNullOrEmpty()) return@forEach
        together.getOrPut(placement(cue)) { mutableListOf() } += cue
    }
    if (together.values.none { it.size > 1 }) return cues
    val merged = HashMap<Cue, Cue>()
    together.values.forEach { group ->
        if (group.size < 2) return@forEach
        val text = SpannableStringBuilder()
        group.forEachIndexed { index, cue ->
            if (index > 0) text.append('\n')
            text.append(cue.text)
        }
        merged[group.first()] = group.first().buildUpon().setText(text).build()
    }
    val absorbed = together.values.filter { it.size > 1 }.flatMap { it.drop(1) }.toSet()
    return cues.mapNotNull { cue ->
        if (cue in absorbed) null else merged[cue] ?: cue
    }
}

/** A chosen position overrides authored (ASS) positions; bitmap cues are never moved. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun positionSubtitleCues(cues: List<Cue>, appearance: SubtitleAppearance): List<Cue> {
    if (!appearance.enabled || appearance.position == SubtitlePosition.FROM_FILE) return cues
    val textCues = cues.filter { it.bitmap == null && !it.text.isNullOrEmpty() }
    if (textCues.isEmpty()) return cues
    // All text cues are joined into one block; giving each the same line would draw them on top
    // of each other.
    val text = SpannableStringBuilder()
    textCues.forEachIndexed { index, cue ->
        if (index > 0) text.append('\n')
        text.append(cue.text)
    }
    val margin = appearance.verticalMarginPercent.coerceIn(0, 30) / 100f
    val top = appearance.position == SubtitlePosition.TOP
    val positioned = textCues.first().buildUpon()
        .setText(text)
        .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
        .setMultiRowAlignment(Layout.Alignment.ALIGN_CENTER)
        .setPosition(.5f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
        .setSize(Cue.DIMEN_UNSET)
        .setVerticalType(Cue.TYPE_UNSET)
        .setLine(if (top) margin else 1f - margin, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(if (top) Cue.ANCHOR_TYPE_START else Cue.ANCHOR_TYPE_END)
        .build()
    return buildList {
        var addedText = false
        cues.forEach { cue ->
            if (cue.bitmap != null || cue.text.isNullOrEmpty()) add(cue)
            else if (!addedText) {
                add(positioned)
                addedText = true
            }
        }
    }
}

/**
 * Scales the outline stroke. CaptionStyleCompat has an edge type and colour but no width, and the
 * player fixes the stroke at 2dp, so this adjusts the paint instead: the outline pass uses
 * FILL_AND_STROKE and the lettering pass FILL. It is a [LanguageFeatureSpan] because
 * setApplyEmbeddedStyles(false) strips every other span from a cue.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class OutlineWidth(private val scale: Float) : CharacterStyle(), LanguageFeatureSpan {
    override fun updateDrawState(paint: TextPaint) {
        if (paint.style == Paint.Style.FILL_AND_STROKE) paint.strokeWidth *= scale
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun outlinedSubtitleCues(cues: List<Cue>, appearance: SubtitleAppearance): List<Cue> {
    if (!appearance.enabled || appearance.edge != SubtitleEdge.OUTLINE) return cues
    val scale = appearance.outlineThicknessPercent.coerceIn(
        OUTLINE_THICKNESS.first, OUTLINE_THICKNESS.last,
    ) / 100f
    if (scale == 1f) return cues
    return cues.map { cue ->
        val text = cue.text
        if (cue.bitmap != null || text.isNullOrEmpty()) return@map cue
        val widened = SpannableStringBuilder(text)
        widened.setSpan(OutlineWidth(scale), 0, widened.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        cue.buildUpon().setText(widened).build()
    }
}

internal val OUTLINE_THICKNESS = 50..300
