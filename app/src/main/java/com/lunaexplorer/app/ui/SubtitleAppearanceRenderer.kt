package com.lunaexplorer.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Annotation
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
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
    private var verticalView: SubtitleView? = null
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
        if (cues == null) releaseVerticalView()
        renderCues()
    }

    private fun applyAppearance() {
        applyAppearance(subtitleView)
        verticalView?.let(::applyAppearance)
        renderCues()
    }

    private fun applyAppearance(view: SubtitleView) {
        view.setApplyEmbeddedStyles(true)
        view.setApplyEmbeddedFontSizes(true)
        if (appearance.enabled) {
            view.setStyle(subtitleCaptionStyle(appearance))
            view.setFractionalTextSize(
                SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * appearance.textSizePercent.coerceIn(50, 200) / 100f,
            )
            view.setBottomPaddingFraction(appearance.verticalMarginPercent.coerceIn(0, 30) / 100f)
        } else {
            // The defaults PlayerView gives its own SubtitleView: the user's caption style and size,
            // embedded styling on.
            view.setUserDefaultStyle()
            view.setUserDefaultTextSize()
            view.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        }
        previewTextSizeSp?.let { size ->
            val percent = if (appearance.enabled) appearance.textSizePercent.coerceIn(50, 200) else 100
            view.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, size * percent / 100f)
        }
    }

    private fun renderCues() {
        val styled = styledSubtitleCues(originalCues, appearance)
        val positioned = positionSubtitleCues(stackedSubtitleCues(styled), appearance)
        val (vertical, horizontal) = positioned.partition { it.bitmap == null && it.verticalType != Cue.TYPE_UNSET }
        subtitleView.setCues(outlinedSubtitleCues(horizontal, appearance))
        if (vertical.isEmpty()) {
            verticalView?.let { it.setCues(emptyList()); it.visibility = INVISIBLE }
        } else {
            val view = verticalView ?: SubtitleView(context).also {
                it.setViewType(SubtitleView.VIEW_TYPE_WEB)
                addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                verticalView = it
                applyAppearance(it)
            }
            view.visibility = VISIBLE
            view.setCues(vertical.map { cue ->
                if (!appearance.enabled) cue else {
                    val text = SpannableStringBuilder(cue.text)
                    val family = when (appearance.font) {
                        SubtitleFont.DEFAULT, SubtitleFont.SANS_SERIF -> "sans-serif"
                        SubtitleFont.SERIF -> "serif"
                        SubtitleFont.MONOSPACE -> "monospace"
                    }
                    text.setSpan(TypefaceSpan(family), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (appearance.bold) text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    cue.buildUpon().setText(text).build()
                }
            })
        }
    }

    private fun releaseVerticalView() {
        verticalView?.let {
            removeView(it)
            it.setViewType(SubtitleView.VIEW_TYPE_CANVAS)
        }
        verticalView = null
    }

    override fun onDetachedFromWindow() {
        releaseVerticalView()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderCues()
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun styledSubtitleCues(cues: List<Cue>, appearance: SubtitleAppearance): List<Cue> {
    if (!appearance.enabled) return cues
    return cues.map { cue ->
        if (cue.bitmap != null || cue.text.isNullOrEmpty()) return@map cue
        val text = SpannableStringBuilder(cue.text)
        val annotations = text.getSpans(0, text.length, Annotation::class.java)
        val colorOverrides = annotations.filter { it.key == "luna.subtitle.colorOverride" }
            .groupBy { text.getSpanStart(it) to text.getSpanEnd(it) }
            .mapValues { (_, marks) -> marks.any { it.value == "true" } || marks.none { it.value == "false" } }
        val relative = annotations.firstOrNull { it.key == "luna.subtitle.relativeSize" }?.value?.toFloatOrNull()
        val vertical = cue.verticalType != Cue.TYPE_UNSET
        val position = if (vertical) cue.position else cue.line
        val fractional = vertical || cue.lineType == Cue.LINE_TYPE_FRACTION
        val preserveSizes = appearance.preserveRelativeSizes && fractional && position > .1f && position < .9f
        val absoluteSizes = text.getSpans(0, text.length, AbsoluteSizeSpan::class.java)
        val baseSize = absoluteSizes.maxOfOrNull { it.size }?.toFloat()
        text.getSpans(0, text.length, Any::class.java).forEach { span ->
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            val flags = text.getSpanFlags(span)
            val keep = when (span) {
                is StyleSpan, is StrikethroughSpan, is UnderlineSpan, is SuperscriptSpan, is SubscriptSpan ->
                    appearance.preserveEmphasis
                is ForegroundColorSpan -> appearance.preserveColors && colorOverrides[start to end] != false
                is RelativeSizeSpan -> preserveSizes
                is AbsoluteSizeSpan -> {
                    if (preserveSizes && baseSize != null && baseSize > 0f)
                        text.setSpan(RelativeSizeSpan(span.size / baseSize), start, end, flags)
                    false
                }
                is LanguageFeatureSpan -> true
                else -> false
            }
            if (!keep) text.removeSpan(span)
        }
        if (preserveSizes && relative != null && relative.isFinite() && relative > 0f && relative != 1f)
            text.setSpan(RelativeSizeSpan(relative), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        cue.buildUpon().setText(text).setTextSize(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).clearWindowColor().build()
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
    val margin = appearance.verticalMarginPercent.coerceIn(0, 30) / 100f
    val top = appearance.position == SubtitlePosition.TOP
    val positioned = textCues.groupBy { it.verticalType }.mapValues { (orientation, group) ->
        val text = SpannableStringBuilder()
        group.forEachIndexed { index, cue ->
            if (index > 0) text.append('\n')
            text.append(cue.text)
        }
        val builder = group.first().buildUpon().setText(text)
            .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMultiRowAlignment(Layout.Alignment.ALIGN_CENTER)
            .setSize(Cue.DIMEN_UNSET)
        if (orientation == Cue.TYPE_UNSET) {
            builder.setPosition(.5f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                .setLine(if (top) margin else 1f - margin, Cue.LINE_TYPE_FRACTION)
                .setLineAnchor(if (top) Cue.ANCHOR_TYPE_START else Cue.ANCHOR_TYPE_END)
        } else {
            // Vertical cues use position for the vertical axis and line for the horizontal axis.
            builder.setPosition(if (top) margin else 1f - margin)
                .setPositionAnchor(if (top) Cue.ANCHOR_TYPE_START else Cue.ANCHOR_TYPE_END)
        }
        builder.build()
    }
    return buildList {
        val added = mutableSetOf<Int>()
        cues.forEach { cue ->
            if (cue.bitmap != null || cue.text.isNullOrEmpty()) add(cue)
            else if (added.add(cue.verticalType)) add(positioned.getValue(cue.verticalType))
        }
    }
}

/**
 * Scales the outline stroke. CaptionStyleCompat has an edge type and colour but no width, and the
 * player fixes the stroke at 2dp, so this adjusts the paint instead: the outline pass uses
 * FILL_AND_STROKE and the lettering pass FILL.
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
