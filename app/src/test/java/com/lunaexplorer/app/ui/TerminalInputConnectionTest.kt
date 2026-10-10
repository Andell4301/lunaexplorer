package com.lunaexplorer.app.ui

import android.app.Application
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.CorrectionInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class TerminalInputConnectionTest {
    private val sent = mutableListOf<String>()
    private val previews = mutableListOf<String>()
    private val keys = mutableListOf<KeyEvent>()
    private val connection = TerminalInputConnection(View(RuntimeEnvironment.getApplication()),
        onText = sent::add, onComposition = previews::add, onKey = { keys.add(it); true })

    @Test fun `autocorrect replaces a composing word and enter commits it once`() {
        connection.setComposingText("l", 1)
        connection.setComposingText("ls", 1)
        assertEquals(emptyList<String>(), sent)
        assertEquals("ls", previews.last())

        connection.commitText("L's ", 1)
        connection.finishComposingText()
        connection.performEditorAction(EditorInfo.IME_ACTION_DONE)
        assertEquals(listOf("L's ", "\r"), sent)
        assertEquals("", previews.last())
    }

    @Test fun `finishing composition before changing keyboard mode keeps the partial word`() {
        connection.setComposingText("hel", 1)
        connection.finishComposingText()
        connection.finishComposingText()
        connection.commitText("lo ", 1)
        assertEquals("hello ", sent.joinToString(""))
    }

    @Test fun `unicode composition and code point deletion preserve complete characters`() {
        connection.setComposingText("に", 1)
        connection.setComposingText("日本🦉", 1)
        connection.deleteSurroundingTextInCodePoints(1, 0)
        assertEquals("日本", previews.last())
        assertEquals(emptyList<String>(), sent)
        connection.commitText("日本🦉\n", 1)
        assertEquals(listOf("日本🦉\r"), sent)
    }

    @Test fun `deletion removes pending composition before sending shell backspace`() {
        connection.commitText("x", 1)
        connection.setComposingText("ab", 1)
        connection.deleteSurroundingText(3, 0)
        connection.commitText("z", 1)
        assertEquals(listOf("x", "\u007f", "z"), sent)
    }

    @Test fun `terminal keys finish composition and retain modifier flags`() {
        connection.setComposingText("word", 1)
        val event = KeyEvent(1, 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0,
            KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        connection.sendKeyEvent(event)
        assertEquals(listOf("word"), sent)
        assertEquals(listOf(event), keys)
    }

    @Test fun `attributed commits and replacement edits emit the final composition once`() {
        connection.setComposingText("ls", 1, null)
        connection.commitText("L's ", 1, null)
        connection.commitCorrection(CorrectionInfo(0, "ls", "L's "))
        assertEquals(listOf("L's "), sent)

        connection.setComposingText("teh", 1, null)
        connection.replaceText(0, 3, "the ", 1, null)
        connection.finishComposingText()
        assertEquals(listOf("L's ", "the "), sent)
        connection.replaceText(0, 4, "old replacement", 1, null)
        assertEquals(listOf("L's ", "the "), sent)
    }
}
