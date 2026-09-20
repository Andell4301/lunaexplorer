package com.lunaexplorer.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.ScrollView
import kotlin.math.roundToInt

internal class FastScrollView(context: Context) : ScrollView(context) {
    private val fastScroller = NativeFastScroller(this,
        metrics = { FastScrollMetrics(computeVerticalScrollOffset().toFloat(), computeVerticalScrollExtent().toFloat(), computeVerticalScrollRange().toFloat()) },
        stopFling = { event -> stopNativeFling(event) { super.onTouchEvent(it) } },
        scroll = { fraction -> scrollTo(scrollX, (fraction * (computeVerticalScrollRange() - computeVerticalScrollExtent())).roundToInt()) })

    init { isVerticalScrollBarEnabled = false }

    fun setFastScrollColors(thumb: Int, track: Int) { fastScroller.setColors(thumb, track) }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        fastScroller.draw(canvas)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = fastScroller.touch(event) || super.dispatchTouchEvent(event)
}

internal class FastScrollWebView(context: Context) : WebView(context) {
    private val fastScroller = NativeFastScroller(this,
        metrics = { FastScrollMetrics(computeVerticalScrollOffset().toFloat(), computeVerticalScrollExtent().toFloat(), computeVerticalScrollRange().toFloat()) },
        stopFling = { event -> stopNativeFling(event) { super.onTouchEvent(it) } },
        scroll = { fraction -> scrollTo(scrollX, (fraction * (computeVerticalScrollRange() - computeVerticalScrollExtent())).roundToInt()) })
    private val horizontalScroller = NativeFastScroller(this, horizontal = true,
        metrics = { FastScrollMetrics(computeHorizontalScrollOffset().toFloat(), computeHorizontalScrollExtent().toFloat(), computeHorizontalScrollRange().toFloat()) },
        stopFling = { event -> stopNativeFling(event) { super.onTouchEvent(it) } },
        scroll = { fraction -> scrollTo((fraction * (computeHorizontalScrollRange() - computeHorizontalScrollExtent())).roundToInt(), scrollY) })

    init { isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false }

    fun setFastScrollColors(thumb: Int, track: Int) {
        fastScroller.setColors(thumb, track)
        horizontalScroller.setColors(thumb, track)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        fastScroller.draw(canvas)
        horizontalScroller.draw(canvas)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        fastScroller.touch(event) || horizontalScroller.touch(event) || super.dispatchTouchEvent(event)
}

private class NativeFastScroller(
    private val view: View,
    private val metrics: () -> FastScrollMetrics,
    private val scroll: (Float) -> Unit,
    private val stopFling: (MotionEvent) -> Unit,
    private val horizontal: Boolean = false,
) {
    private val density = view.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var thumbColor = Color.rgb(110, 125, 200)
    private var trackColor = Color.argb(35, 128, 128, 128)
    private var drag: FastScrollThumb? = null
    private var grabOffset = 0f
    private var pointerId = -1

    fun setColors(thumb: Int, track: Int) {
        if (thumbColor != thumb || trackColor != track) {
            thumbColor = thumb
            trackColor = track
            view.invalidate()
        }
    }

    fun draw(canvas: Canvas) {
        val track = (if (horizontal) view.width else view.height).toFloat()
        val thumb = fastScrollThumb(metrics(), track, 48 * density) ?: return
        val width = (if (drag != null) 8 else 5) * density
        val cross = if (horizontal) view.height - 3 * density - width else
            if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) 3 * density else view.width - 3 * density - width
        // The canvas is in content coordinates; translate by the scroll offset to pin the bar to the viewport.
        canvas.save()
        canvas.translate(view.scrollX.toFloat(), view.scrollY.toFloat())
        paint.color = trackColor
        if (horizontal) canvas.drawRoundRect(0f, cross, track, cross + width, width / 2, width / 2, paint)
        else canvas.drawRoundRect(cross, 0f, cross + width, track, width / 2, width / 2, paint)
        paint.color = thumbColor
        if (drag == null) paint.alpha = (Color.alpha(thumbColor) * .7f).roundToInt()
        if (horizontal) canvas.drawRoundRect(thumb.start, cross, thumb.start + thumb.length, cross + width, width / 2, width / 2, paint)
        else canvas.drawRoundRect(cross, thumb.start, cross + width, thumb.start + thumb.length, width / 2, width / 2, paint)
        canvas.restore()
    }

    fun touch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val track = (if (horizontal) view.width else view.height).toFloat()
                val thumb = fastScrollThumb(metrics(), track, 48 * density) ?: return false
                val edge = if (horizontal) event.y >= view.height - 12 * density else
                    if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) event.x <= 24 * density else event.x >= view.width - 24 * density
                val position = if (horizontal) event.x else event.y
                if (!edge || position < thumb.start - 4 * density || position > thumb.start + thumb.length + 4 * density) return false
                drag = thumb
                stopFling(event)
                grabOffset = position - thumb.start
                pointerId = event.getPointerId(0)
                view.parent?.requestDisallowInterceptTouchEvent(true)
                view.invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val thumb = drag ?: return false
                val index = event.findPointerIndex(pointerId)
                if (index >= 0) scroll(thumb.fractionAt(if (horizontal) event.getX(index) else event.getY(index), grabOffset))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag == null) return false
                drag = null
                pointerId = -1
                view.parent?.requestDisallowInterceptTouchEvent(false)
                view.invalidate()
                return true
            }
        }
        return drag != null
    }
}

/** Passes the down event on so the view stops its fling, then a cancel so it stops tracking the touch. */
private inline fun stopNativeFling(event: MotionEvent, dispatch: (MotionEvent) -> Unit) {
    dispatch(event)
    val cancel = MotionEvent.obtain(event)
    try {
        cancel.action = MotionEvent.ACTION_CANCEL
        dispatch(cancel)
    } finally { cancel.recycle() }
}
