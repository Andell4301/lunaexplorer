package com.lunaexplorer.app.ui

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lunaexplorer.app.terminal.LocalShellConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

@RunWith(AndroidJUnit4::class)
class TerminalInputInstrumentedTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun tappingToOpenKeyboardIsOptionalAndExplicitKeyboardAlwaysOpens() = runBlocking {
        val controller = withContext(Dispatchers.Main) {
            TerminalSurfaceController().apply { setTapOpensKeyboard(false) }
        }
        lateinit var web: WebView
        var container: View? = null
        suspend fun visible() = withContext(Dispatchers.Main) {
            ViewCompat.getRootWindowInsets(web)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        suspend fun awaitKeyboard(shown: Boolean, stage: String) {
            val reached = withTimeoutOrNull(5_000) {
                while (visible() != shown) delay(20)
                true
            } == true
            assertTrue(stage, reached)
        }
        suspend fun tap() {
            var bounds: Rect? = null
            var settledAt = 0L
            val settled = withTimeoutOrNull(5_000) {
                while (true) {
                    val current = withContext(Dispatchers.Main) {
                        val visible = Rect()
                        val frame = Rect()
                        val origin = IntArray(2)
                        if (!web.hasWindowFocus() || !web.getLocalVisibleRect(visible)) null else {
                            web.getLocationOnScreen(origin)
                            visible.offset(origin[0], origin[1])
                            web.getWindowVisibleDisplayFrame(frame)
                            visible.takeIf { it.intersect(frame) && it.width() > 4 && it.height() > 4 }
                        }
                    }
                    val now = SystemClock.uptimeMillis()
                    if (current == null || current != bounds) settledAt = now
                    bounds = current
                    if (current != null && now - settledAt >= 100) break
                    delay(20)
                }
                true
            } == true
            assertTrue("Terminal has settled visible bounds", settled)
            val position = requireNotNull(bounds).let { it.exactCenterX() to it.bottom - 2f }
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, position.first, position.second, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                InstrumentationRegistry.getInstrumentation().sendPointerSync(event)
                event.recycle()
            }
        }
        try {
            activity.scenario.onActivity {
                it.setContent {
                    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        val view = LocalView.current
                        SideEffect { container = view }
                        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()) {
                            Spacer(Modifier.height(56.dp))
                            TerminalSurface(controller, onInput = {}, onResize = { _, _ -> },
                                modifier = Modifier.weight(1f).fillMaxWidth())
                            Spacer(Modifier.height(96.dp))
                        }
                    }
                }
            }
            withTimeout(30_000) {
                web = withContext(Dispatchers.Main) {
                    var view: WebView? = null
                    while (view == null) {
                        view = terminalView(container)
                        if (view == null) delay(20)
                    }
                    view
                }
                controller.write("$ ".toByteArray())
                withContext(Dispatchers.Main) {
                    web.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(web.windowToken, 0)
                }
                awaitKeyboard(false, "Keyboard starts hidden")
                tap()
                delay(400)
                assertFalse("Tap disabled keeps keyboard hidden", visible())
                controller.setTapOpensKeyboard(true)
                tap()
                awaitKeyboard(true, "Tap enabled opens keyboard")
                withContext(Dispatchers.Main) {
                    web.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(web.windowToken, 0)
                }
                awaitKeyboard(false, "Keyboard can be hidden after tap")
                tap()
                awaitKeyboard(true, "Tap reopens keyboard after hiding")
                withContext(Dispatchers.Main) {
                    web.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(web.windowToken, 0)
                }
                awaitKeyboard(false, "Keyboard can be hidden after reopening")
                controller.setTapOpensKeyboard(false)
                tap()
                delay(400)
                assertFalse("Disabling tap again keeps keyboard hidden", visible())
                controller.showKeyboard()
                awaitKeyboard(true, "Explicit keyboard opens with tap disabled")
            }
        } finally {
            withContext(Dispatchers.Main) { controller.close() }
        }
    }

    @Test fun rawKeyboardKeysReachTerminalOnceWithModifiersAndApplicationCursorMode() = runBlocking {
        val controller = withContext(Dispatchers.Main) { TerminalSurfaceController() }
        val input = Channel<ByteArray>(Channel.UNLIMITED)
        lateinit var web: WebView
        try {
            activity.scenario.onActivity {
                controller.onInput = { bytes -> input.trySend(bytes) }
                web = controller.view(it)
                it.setContentView(web)
            }
            withTimeout(15_000) {
                controller.write("$ ".toByteArray())
                controller.showKeyboard()
                while (evaluate(web, "document.activeElement === terminal.textarea") != "true") delay(20)
                val connection = terminalInputConnection(web, disabled = true).second
                sendToInputConnection(connection) {
                    commitText("one", 1)
                    sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                    commitText("two", 1)
                }
                assertEquals("one\rtwo", receiveInput(input, 7))
                for (key in listOf(KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER)) {
                    sendToInputConnection(connection) {
                        sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                        sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                    }
                }
                assertEquals("ls \r", receiveInput(input, 4))
                controller.write("ls\r\n\u001b[?1h".toByteArray())
                withContext(Dispatchers.Main) {
                    for ((key, modifiers) in listOf(KeyEvent.KEYCODE_C to KeyEvent.META_CTRL_ON,
                            KeyEvent.KEYCODE_X to KeyEvent.META_ALT_ON, KeyEvent.KEYCODE_DPAD_UP to 0,
                            KeyEvent.KEYCODE_DEL to 0)) {
                        val now = SystemClock.uptimeMillis()
                        web.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0, modifiers))
                        web.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0, modifiers))
                    }
                }
                assertEquals("\u0003\u001bx\u001bOA\u007f", receiveInput(input, 7))
                controller.write("ok".toByteArray())
                assertEquals(null, input.tryReceive().getOrNull())
            }
        } finally {
            withContext(Dispatchers.Main) { controller.close() }
            input.close()
        }
    }

    @Test fun togglingSuggestionsKeepsAnUnfinishedComposingWord() = runBlocking {
        val controller = withContext(Dispatchers.Main) {
            TerminalSurfaceController().apply { setDisableSuggestions(false) }
        }
        val input = Channel<ByteArray>(Channel.UNLIMITED)
        lateinit var web: WebView
        try {
            activity.scenario.onActivity {
                controller.onInput = { bytes -> input.trySend(bytes) }
                web = controller.view(it)
                it.setContentView(web)
            }
            withTimeout(15_000) {
                controller.write("$ ".toByteArray())
                controller.showKeyboard()
                while (evaluate(web, "document.activeElement === terminal.textarea") != "true") delay(20)
                var connection = terminalInputConnection(web, disabled = false).second
                for ((disabled, prefix, suffix) in listOf(Triple(true, "hel", "lo "), Triple(false, "に", "ほん "))) {
                    sendToInputConnection(connection) { setComposingText(prefix, 1) }
                    while (evaluate(web,
                            "document.querySelector('.composition-view.active')?.textContent === '$prefix'") != "true") {
                        delay(20)
                    }
                    assertEquals(null, input.tryReceive().getOrNull())
                    controller.setDisableSuggestions(disabled)
                    connection = terminalInputConnection(web, disabled).second
                    sendToInputConnection(connection) { commitText(suffix, 1) }
                    val expected = prefix + suffix
                    val received = receiveInput(input, expected.toByteArray().size)
                    assertEquals(expected, received)
                    controller.write(received.toByteArray())
                    while (evaluate(web,
                            "document.querySelector('.xterm-rows').textContent.includes('$expected')") != "true") {
                        delay(20)
                    }
                    assertEquals(null, input.tryReceive().getOrNull())
                }
            }
        } finally {
            withContext(Dispatchers.Main) { controller.close() }
            input.close()
        }
    }

    @Test fun keyboardSuggestionsCanChangeWithoutInterruptingTerminalInput() = runBlocking {
        val controller = withContext(Dispatchers.Main) { TerminalSurfaceController() }
        val input = Channel<ByteArray>(Channel.UNLIMITED)
        lateinit var web: WebView
        try {
            activity.scenario.onActivity {
                controller.onInput = { bytes -> input.trySend(bytes) }
                web = controller.view(it)
                it.setContentView(web)
            }
            withTimeout(15_000) {
                controller.write("$ ".toByteArray())
                controller.showKeyboard()
                while (evaluate(web, "document.activeElement === terminal.textarea") != "true") delay(20)
                val blocked = terminalInputConnection(web, disabled = true)
                assertEquals(InputType.TYPE_NULL, blocked.first.inputType)
                assertEquals("true", evaluate(web, "terminal.textarea.readOnly"))
                sendToInputConnection(blocked.second) { commitText("echo café 🦉", 1) }
                assertEquals("echo café 🦉", receiveInput(input, "echo café 🦉".toByteArray().size))
                controller.write("echo café 🦉".toByteArray())
                while (evaluate(web,
                        "document.querySelector('.xterm-rows').textContent.includes('$ echo café 🦉')") != "true") {
                    delay(20)
                }

                controller.setDisableSuggestions(false)
                val allowed = terminalInputConnection(web, disabled = false)
                assertEquals(InputType.TYPE_CLASS_TEXT, allowed.first.inputType and InputType.TYPE_MASK_CLASS)
                assertEquals(blocked.first.imeOptions, allowed.first.imeOptions)
                sendToInputConnection(allowed.second) { setComposingText("に", 1) }
                sendToInputConnection(allowed.second) { setComposingText("日本", 1) }
                sendToInputConnection(allowed.second) { commitText("日本", 1) }
                assertEquals("日本", receiveInput(input, "日本".toByteArray().size))
                sendToInputConnection(allowed.second) {
                    sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                    sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
                    sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB))
                }
                assertEquals("\r\t", receiveInput(input, 2))

                evaluate(web, "terminal.select(2, 0, 4)")
                assertEquals("echo", controller.selection())
                controller.setDisableSuggestions(true)
                terminalInputConnection(web, disabled = true)
                assertEquals("echo", controller.selection())
                assertEquals(null, input.tryReceive().getOrNull())
            }
        } finally {
            withContext(Dispatchers.Main) { controller.close() }
            input.close()
        }
    }

    @Test fun surfaceWaitsForVisibleDimensionsBeforeStarting() = runBlocking {
        val controller = withContext(Dispatchers.Main) { TerminalSurfaceController() }
        val visible = mutableStateOf(false)
        val sizes = Channel<Pair<Int, Int>>(Channel.UNLIMITED)
        var container: View? = null
        try {
            activity.scenario.onActivity {
                it.setContent {
                    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        val view = LocalView.current
                        SideEffect { container = view }
                        Column(Modifier.fillMaxSize()) {
                            TerminalSurface(controller, onInput = {}, onResize = { columns, rows ->
                                sizes.trySend(columns to rows)
                            }, modifier = if (visible.value) Modifier.weight(1f).fillMaxWidth() else Modifier.size(0.dp))
                        }
                    }
                }
            }
            withTimeout(15_000) {
                val web = withContext(Dispatchers.Main) {
                    var view: WebView? = null
                    while (view == null) {
                        view = terminalView(container)
                        if (view == null) delay(20)
                    }
                    view
                }
                while (evaluate(web, "typeof luna !== 'undefined'") != "true") delay(20)
                val writing = launch { controller.write("$ ".toByteArray()) }
                delay(100)
                assertTrue("A hidden surface must not publish fallback dimensions", sizes.tryReceive().isFailure)
                assertFalse("A hidden surface must not report ready", writing.isCompleted)
                withContext(Dispatchers.Main) { visible.value = true }
                val measured = sizes.receive()
                assertTrue(measured.first > 2 && measured.second > 1)
                writing.join()
                assertEquals(measured.first, evaluate(web, "terminal.cols").toInt())
                assertEquals(measured.second, evaluate(web, "terminal.rows").toInt())
            }
        } finally {
            withContext(Dispatchers.Main) { controller.close() }
            sizes.close()
        }
    }

    @Test fun localShellShowsItsInitialPromptBeforeTyping() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { TerminalSurfaceController() }
        val output = ByteArrayOutputStream()
        val sizes = mutableListOf<Pair<Int, Int>>()
        val session = withContext(Dispatchers.Main) {
            TerminalSession(owner, "Local shell", null, render = { bytes ->
                synchronized(output) { output.write(bytes) }
                controller.write(bytes)
            }) { columns, rows -> LocalShellConnection.open(context, columns = columns, rows = rows) }
        }
        var container: View? = null
        try {
            activity.scenario.onActivity {
                it.setContent {
                    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        val view = LocalView.current
                        SideEffect { container = view }
                        Column(Modifier.fillMaxSize()) {
                            Spacer(Modifier.height(56.dp))
                            TerminalSurface(controller, onInput = session::input, onResize = { columns, rows ->
                                sizes.add(columns to rows)
                                session.resize(columns, rows)
                            },
                                modifier = Modifier.weight(1f).fillMaxWidth())
                            Spacer(Modifier.height(96.dp))
                        }
                    }
                }
            }
            withTimeout(15_000) {
                val web = withContext(Dispatchers.Main) {
                    var view: WebView? = null
                    while (view == null) {
                        view = terminalView(container)
                        if (view == null) delay(20)
                    }
                    view
                }
                val visible = withTimeoutOrNull(10_000) {
                    var visibleSince = 0L
                    while (true) {
                        val prompt = evaluate(web, """
                            typeof terminal !== 'undefined' && terminal.rows > 1 &&
                            /[\u0024#]/.test(document.querySelector('.xterm-rows').textContent)
                        """.trimIndent()) == "true"
                        val now = SystemClock.elapsedRealtime()
                        if (!prompt) visibleSince = 0L else if (visibleSince == 0L) visibleSince = now
                        if (visibleSince != 0L && now - visibleSince >= 500) break
                        delay(20)
                    }
                    true
                } == true
                val received = synchronized(output) { output.toString("UTF-8") }
                val measured = withContext(Dispatchers.Main) { sizes.toList() }
                assertTrue("${session.status.value}; sizes: $measured; initial output: $received", visible)
            }
        } finally {
            session.close()
            owner.cancel()
            withContext(Dispatchers.Main) { controller.close() }
        }
    }

    @Test fun softKeyboardTextReachesShellAndRendersEcho() = runBlocking {
        val controller = withContext(Dispatchers.Main) { TerminalSurfaceController() }
        val input = Channel<ByteArray>(Channel.UNLIMITED)
        val footerHeight = mutableStateOf(96.dp)
        var container: View? = null
        try {
            activity.scenario.onActivity {
                it.setContent {
                    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        val view = LocalView.current
                        SideEffect { container = view }
                        Column(Modifier.fillMaxSize()) {
                            Spacer(Modifier.height(56.dp))
                            TerminalSurface(controller, onInput = { bytes -> input.trySend(bytes) }, onResize = { _, _ -> },
                                modifier = Modifier.weight(1f).fillMaxWidth())
                            Spacer(Modifier.height(footerHeight.value))
                        }
                    }
                }
            }
            withTimeout(15_000) {
                val web = withContext(Dispatchers.Main) {
                    var view: WebView? = null
                    while (view == null) {
                        view = terminalView(container)
                        if (view == null) delay(20)
                    }
                    view
                }
                controller.write("$ ".toByteArray())
                assertTrue("Terminal must fill its Compose window", withTimeoutOrNull(5_000) {
                    while (evaluate(web,
                            "terminal.rows > 1 && document.getElementById('terminal').getBoundingClientRect().height > 100") != "true") {
                        delay(20)
                    }
                    true
                } == true)
                val rows = evaluate(web, "terminal.rows").toInt()
                controller.showKeyboard()
                while (evaluate(web, "document.activeElement === terminal.textarea") != "true") delay(20)
                val connection = terminalInputConnection(web, disabled = true).second
                sendToInputConnection(connection) { commitText("echo café", 1) }
                val typed = withTimeout(5_000) { input.receive() }
                assertEquals("echo café", typed.toString(Charsets.UTF_8))
                controller.write(typed)
                withTimeout(5_000) {
                    while (evaluate(web,
                            "document.querySelector('.xterm-rows').textContent.includes('$ echo café')") != "true") {
                        delay(20)
                    }
                }
                assertEquals(null, input.tryReceive().getOrNull())
                assertEquals("\"echo\"", evaluate(web, """
                    (() => {
                        const span = [...document.querySelectorAll('.xterm-rows span')]
                            .find(element => element.textContent.includes('echo'));
                        const start = span.firstChild.textContent.indexOf('echo');
                        const range = document.createRange();
                        range.setStart(span.firstChild, start);
                        range.setEnd(span.firstChild, start + 4);
                        const rect = range.getBoundingClientRect();
                        span.dispatchEvent(new MouseEvent('contextmenu', {
                            clientX: rect.x + rect.width / 2, clientY: rect.y + rect.height / 2,
                            button: 2, bubbles: true, cancelable: true
                        }));
                        return luna.selection();
                    })()
                """.trimIndent()))
                assertEquals("\"none\"", evaluate(web, "terminal.textarea.getAttribute('inputmode')"))
                assertEquals("false", evaluate(web, "luna.focusForInput()"))
                assertEquals("\"echo\"", evaluate(web, "luna.selection()"))
                evaluate(web, "luna.focus()")
                assertEquals("\"none\"", evaluate(web, "terminal.textarea.getAttribute('inputmode')"))
                withContext(Dispatchers.Main) { footerHeight.value = 216.dp }
                withTimeout(5_000) {
                    while (evaluate(web, "terminal.rows > 1 && terminal.rows < $rows") != "true") delay(20)
                }
                assertEquals("true", evaluate(web,
                    "document.querySelector('.xterm-rows').textContent.includes('$ echo café')"))
            }
        } finally {
            withContext(Dispatchers.Main) { controller.close() }
            input.close()
        }
    }

    private fun terminalView(view: View?): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) terminalView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private suspend fun terminalInputConnection(web: WebView, disabled: Boolean): Pair<EditorInfo, InputConnection> =
        withTimeout(5_000) {
            var result: Pair<EditorInfo, InputConnection>? = null
            while (result == null) {
                val candidate = withContext(Dispatchers.Main) {
                    val info = EditorInfo()
                    web.onCreateInputConnection(info)?.let { info to it }
                }
                if (candidate != null) {
                    val type = candidate.first.inputType
                    val blocked = type == InputType.TYPE_NULL
                    val corrected = type and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT != 0
                    if (blocked == disabled && corrected != disabled) result = candidate
                }
                if (result == null) delay(20)
            }
            result
        }

    private suspend fun sendToInputConnection(connection: InputConnection, action: InputConnection.() -> Unit) =
        suspendCancellableCoroutine<Unit> { continuation ->
            val handler = requireNotNull(connection.handler)
            handler.post {
                connection.action()
                if (continuation.isActive) continuation.resume(Unit)
            }
        }

    private suspend fun receiveInput(input: Channel<ByteArray>, size: Int): String = withTimeout(5_000) {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < size) bytes.write(input.receive())
        bytes.toString("UTF-8")
    }

    private suspend fun evaluate(view: WebView, script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }
    }
}
