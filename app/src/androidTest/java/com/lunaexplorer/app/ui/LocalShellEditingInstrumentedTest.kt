package com.lunaexplorer.app.ui

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lunaexplorer.app.terminal.LocalShellConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

@RunWith(AndroidJUnit4::class)
class LocalShellEditingInstrumentedTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun firstCommandCanBeErasedBackToItsInitialPrompt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { TerminalSurfaceController() }
        val output = ByteArrayOutputStream()
        var session: TerminalSession? = null
        lateinit var web: WebView
        try {
            activity.scenario.onActivity {
                web = controller.view(it)
                it.setContentView(web)
            }
            withTimeout(30_000) {
                controller.write(byteArrayOf())
                evaluate(web, "terminal.resize(51, 12)")
                session = withContext(Dispatchers.Main) {
                    TerminalSession(owner, "Local shell", null, render = { bytes ->
                        synchronized(output) { output.write(bytes) }
                        controller.write(bytes)
                    }) { columns, rows ->
                        LocalShellConnection.open(context, columns = columns, rows = rows)
                    }.also {
                        controller.onInput = it::input
                        controller.onResize = it::resize
                        it.resize(51, 12)
                    }
                }
                await(web, "Initial prompt appears", """
                    Array.from({ length: terminal.buffer.active.length }, (_, row) =>
                        terminal.buffer.active.getLine(row).translateToString(true).trimEnd())
                        .some(row => row.endsWith('$'))
                """.trimIndent())
                controller.focus()
                await(web, "Terminal receives keys", "document.activeElement === terminal.textarea")
                val initial = snapshot(web)
                val connection = withContext(Dispatchers.Main) {
                    requireNotNull(web.onCreateInputConnection(EditorInfo()))
                }
                withContext(Dispatchers.Main) { connection.commitText("abcd efgh ijk", 1) }
                await(web, "Typed command is visible", """
                    Array.from({ length: terminal.buffer.active.length }, (_, row) =>
                        terminal.buffer.active.getLine(row).translateToString(true)).join('').includes('ijk')
                """.trimIndent())
                sendKey(connection, KeyEvent.KEYCODE_DEL, "abcd efgh ijk".length)
                assertRestored(web, initial, output)

                sendKey(connection, KeyEvent.KEYCODE_ENTER)
                await(web, "The next prompt keeps the initial layout", """
                    (() => {
                        const previous = $initial;
                        const buffer = terminal.buffer.active;
                        if (buffer.cursorY <= previous.y || buffer.cursorX !== previous.x) return false;
                        return previous.rows.slice(0, previous.y + 1).every((row, index) =>
                            buffer.getLine(buffer.cursorY - previous.y + index)
                                .translateToString(true).trimEnd() === row);
                    })()
                """.trimIndent())
                val subsequent = snapshot(web)
                val longCommand = "abcdefghijklmnop ".repeat(12) + "tail"
                withContext(Dispatchers.Main) { connection.commitText(longCommand, 1) }
                await(web, "The end of a command wider than the screen remains visible", """
                    Array.from({ length: terminal.buffer.active.length }, (_, row) =>
                        terminal.buffer.active.getLine(row).translateToString(true)).join('').includes('tail')
                """.trimIndent())
                sendKey(connection, KeyEvent.KEYCODE_DEL, longCommand.length)
                assertRestored(web, subsequent, output)
                assertTrue(session!!.running.value)
            }
        } finally {
            session?.close()
            owner.cancel()
            withContext(Dispatchers.Main) { controller.close() }
        }
    }

    private suspend fun sendKey(connection: InputConnection, keyCode: Int, count: Int = 1) =
        withContext(Dispatchers.Main) {
            repeat(count) {
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }
        }

    private suspend fun assertRestored(web: WebView, initial: String, output: ByteArrayOutputStream) {
        val restored = withTimeoutOrNull(5_000) {
            var restoredAt = 0L
            while (true) {
                val now = SystemClock.uptimeMillis()
                if (snapshot(web) != initial) restoredAt = 0L else if (restoredAt == 0L) restoredAt = now
                if (restoredAt != 0L && now - restoredAt >= 100) break
                delay(20)
            }
            true
        } == true
        val echo = synchronized(output) { JSONObject.quote(output.toString("UTF-8")) }
        assertTrue("Initial: $initial; after erasing: ${snapshot(web)}; shell output: $echo", restored)
    }

    private suspend fun await(web: WebView, message: String, expression: String) {
        val reached = withTimeoutOrNull(5_000) {
            while (evaluate(web, expression) != "true") delay(20)
            true
        } == true
        assertTrue("$message: ${snapshot(web)}", reached)
    }

    private suspend fun snapshot(web: WebView): String {
        val encoded = evaluate(web, """
            JSON.stringify({
                x: terminal.buffer.active.cursorX,
                y: terminal.buffer.active.cursorY,
                rows: Array.from({ length: terminal.buffer.active.length }, (_, row) =>
                    terminal.buffer.active.getLine(row).translateToString(true).trimEnd())
            })
        """.trimIndent())
        return JSONTokener(encoded).nextValue() as String
    }

    private suspend fun evaluate(web: WebView, script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            web.evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }
    }
}
