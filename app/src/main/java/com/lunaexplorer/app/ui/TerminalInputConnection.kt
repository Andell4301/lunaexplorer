package com.lunaexplorer.app.ui

import android.os.Handler
import android.os.Looper
import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.TextAttribute
import androidx.annotation.RequiresApi

internal class TerminalInputConnection(
    view: View,
    private val onText: (String) -> Unit,
    private val onComposition: (String) -> Unit,
    private val onKey: (KeyEvent) -> Boolean,
) : BaseInputConnection(view, true) {
    private val main = Handler(Looper.getMainLooper())
    private var composition = ""

    override fun getHandler(): Handler = main

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val result = super.setComposingText(text, newCursorPosition)
        showComposition(requireNotNull(editable).toString())
        return result
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val result = super.commitText(text, newCursorPosition)
        flush()
        return result
    }

    override fun commitCompletion(text: CompletionInfo?): Boolean = text?.let { commitText(it.text, 1) } ?: false

    @RequiresApi(34)
    override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int,
        textAttribute: TextAttribute?): Boolean {
        val buffer = requireNotNull(editable)
        if (start !in 0..buffer.length || end !in 0..buffer.length) return false
        val result = super.replaceText(start, end, text, newCursorPosition, textAttribute)
        flush()
        return result
    }

    override fun finishComposingText(): Boolean {
        val result = super.finishComposingText()
        flush()
        return result
    }

    private fun flush() {
        val buffer = requireNotNull(editable)
        val text = buffer.toString()
        buffer.clear()
        Selection.setSelection(buffer, 0)
        showComposition("")
        if (text.isNotEmpty()) onText(text.replace('\n', '\r'))
    }

    private fun showComposition(text: String) {
        if (composition == text) return
        composition = text
        onComposition(text)
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        delete(beforeLength, afterLength, codePoints = false)

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        delete(beforeLength, afterLength, codePoints = true)

    private fun delete(beforeLength: Int, afterLength: Int, codePoints: Boolean): Boolean {
        if (beforeLength < 0 || afterLength < 0) return false
        val buffer = requireNotNull(editable)
        val start = minOf(Selection.getSelectionStart(buffer), Selection.getSelectionEnd(buffer)).coerceAtLeast(0)
        val end = maxOf(Selection.getSelectionStart(buffer), Selection.getSelectionEnd(buffer)).coerceAtLeast(start)
        val before = if (codePoints) Character.codePointCount(buffer, 0, start) else start
        val after = if (codePoints) Character.codePointCount(buffer, end, buffer.length) else buffer.length - end
        val deleteStart = if (codePoints) Character.offsetByCodePoints(buffer, start, -minOf(beforeLength, before))
            else start - minOf(beforeLength, before)
        val deleteEnd = if (codePoints) Character.offsetByCodePoints(buffer, end, minOf(afterLength, after))
            else end + minOf(afterLength, after)
        buffer.delete(end, deleteEnd)
        buffer.delete(deleteStart, start)
        showComposition(buffer.toString())
        repeat((beforeLength - before).coerceAtLeast(0)) { onText("\u007f") }
        repeat((afterLength - after).coerceAtLeast(0)) {
            onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL))
            onKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_FORWARD_DEL))
        }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_MULTIPLE && event.characters != null) {
            finishComposingText()
            onText(event.characters.replace('\n', '\r'))
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(event.keyCode)) finishComposingText()
        return onKey(event)
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        finishComposingText()
        onText("\r")
        return true
    }

    override fun getCursorCapsMode(reqModes: Int) = 0
}
