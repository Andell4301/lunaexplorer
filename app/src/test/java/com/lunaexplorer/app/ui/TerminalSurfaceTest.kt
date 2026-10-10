package com.lunaexplorer.app.ui

import android.app.Application
import android.app.Activity
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class TerminalSurfaceTest {
    private val dispatcher = StandardTestDispatcher()
    private val controller = TerminalSurfaceController()
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }

    @After fun cleanup() {
        controller.close()
        Dispatchers.resetMain()
    }

    @Test fun `keyboard waits for the terminal editor to receive focus`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = controller.view(activity)
        activity.setContentView(view)
        val bridge = shadowOf(view).getJavascriptInterface("LunaTerminal") as TerminalBridge
        bridge.ready()
        val keyboard = shadowOf(activity.getSystemService(InputMethodManager::class.java))

        controller.showKeyboard()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(keyboard.isSoftInputVisible)
        shadowOf(view).lastEvaluatedJavascriptCallback.onReceiveValue("true")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(keyboard.isSoftInputVisible)
        activity.finish()
    }

    @Test fun `a short tap requests input but scrolling and long presses do not`() {
        val view = controller.view(context)
        val bridge = shadowOf(view).getJavascriptInterface("LunaTerminal") as TerminalBridge
        bridge.ready()
        val loaded = shadowOf(view).lastEvaluatedJavascriptCallback
        var down = SystemClock.uptimeMillis()
        fun touch(action: Int, x: Float, y: Float, elapsed: Long) {
            val event = MotionEvent.obtain(down, down + elapsed, action, x, y, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }

        touch(MotionEvent.ACTION_DOWN, 20f, 20f, 0)
        touch(MotionEvent.ACTION_UP, 20f, 20f, 80)
        assertSame(loaded, shadowOf(view).lastEvaluatedJavascriptCallback)
        bridge.tapped()
        val tap = shadowOf(view).lastEvaluatedJavascriptCallback
        assertTrue(tap != null)
        assertNotSame(loaded, tap)
        tap.onReceiveValue("false")

        down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, 20f, 20f, 0)
        touch(MotionEvent.ACTION_MOVE, 20f, 160f, 40)
        touch(MotionEvent.ACTION_UP, 20f, 160f, 90)
        bridge.tapped()
        assertSame(tap, shadowOf(view).lastEvaluatedJavascriptCallback)

        down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, 20f, 20f, 0)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        touch(MotionEvent.ACTION_UP, 20f, 20f, 1_000)
        bridge.tapped()
        assertSame(tap, shadowOf(view).lastEvaluatedJavascriptCallback)
    }

    @Test fun `a keyboard request waits for terminal startup`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = controller.view(activity)
        activity.setContentView(view)
        val bridge = shadowOf(view).getJavascriptInterface("LunaTerminal") as TerminalBridge
        val keyboard = shadowOf(activity.getSystemService(InputMethodManager::class.java))
        try {
            controller.showKeyboard()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shadowOf(view).lastEvaluatedJavascriptCallback)
            assertFalse(keyboard.isSoftInputVisible)

            bridge.ready()
            val focus = shadowOf(view).lastEvaluatedJavascriptCallback
            assertTrue(focus != null)
            assertFalse(keyboard.isSoftInputVisible)
            focus.onReceiveValue("true")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(keyboard.isSoftInputVisible)
        } finally { activity.finish() }
    }

    @Test fun `tap to open keyboard can be disabled while the keyboard button still works`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        controller.setTapOpensKeyboard(false)
        val view = controller.view(activity)
        activity.setContentView(view)
        val bridge = shadowOf(view).getJavascriptInterface("LunaTerminal") as TerminalBridge
        val keyboard = shadowOf(activity.getSystemService(InputMethodManager::class.java))
        try {
            bridge.ready()
            val loaded = shadowOf(view).lastEvaluatedJavascript
            val now = SystemClock.uptimeMillis()
            for ((action, time) in listOf(MotionEvent.ACTION_DOWN to now, MotionEvent.ACTION_UP to now + 80)) {
                val event = MotionEvent.obtain(now, time, action, 20f, 20f, 0)
                view.dispatchTouchEvent(event)
                event.recycle()
            }
            bridge.tapped()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(loaded, shadowOf(view).lastEvaluatedJavascript)
            assertFalse(keyboard.isSoftInputVisible)

            controller.showKeyboard()
            shadowOf(view).lastEvaluatedJavascriptCallback.onReceiveValue("true")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(keyboard.isSoftInputVisible)
        } finally { activity.finish() }
    }

    @Test fun `reattaching the terminal discards an old keyboard request`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(activity)
        val view = controller.view(activity)
        parent.addView(view)
        activity.setContentView(parent)
        val bridge = shadowOf(view).getJavascriptInterface("LunaTerminal") as TerminalBridge
        val keyboard = shadowOf(activity.getSystemService(InputMethodManager::class.java))
        try {
            bridge.ready()
            controller.showKeyboard()
            val oldFocus = shadowOf(view).lastEvaluatedJavascriptCallback
            parent.removeView(view)
            parent.addView(view)
            oldFocus.onReceiveValue("true")
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(keyboard.isSoftInputVisible)

            controller.showKeyboard()
            shadowOf(view).lastEvaluatedJavascriptCallback.onReceiveValue("true")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(keyboard.isSoftInputVisible)
        } finally { activity.finish() }
    }

    @Test fun `closing after focus acknowledgement cancels the pending keyboard show`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = controller.view(activity)
        activity.setContentView(view)
        val bridge = shadowOf(view).getJavascriptInterface("LunaTerminal") as TerminalBridge
        val keyboard = shadowOf(activity.getSystemService(InputMethodManager::class.java))
        try {
            bridge.ready()
            controller.showKeyboard()
            shadowOf(view).lastEvaluatedJavascriptCallback.onReceiveValue("true")
            controller.close()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(keyboard.isSoftInputVisible)
        } finally { activity.finish() }
    }

    @Test fun `a new gesture cancels a delayed keyboard request`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = controller.view(activity)
        activity.setContentView(view)
        val bridge = shadowOf(view).getJavascriptInterface("LunaTerminal") as TerminalBridge
        val keyboard = shadowOf(activity.getSystemService(InputMethodManager::class.java))
        try {
            bridge.ready()
            controller.showKeyboard()
            val oldFocus = shadowOf(view).lastEvaluatedJavascriptCallback
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 20f, 20f, 0)
            view.dispatchTouchEvent(down)
            down.recycle()
            oldFocus.onReceiveValue("true")
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(keyboard.isSoftInputVisible)
        } finally { activity.finish() }
    }

    @Test fun `screen reader support follows accessibility changes until close`() {
        val accessibility = shadowOf(context.getSystemService(AccessibilityManager::class.java))
        accessibility.setTouchExplorationEnabled(true)
        val view = controller.view(context)
        val bridge = shadowOf(view).getJavascriptInterface("LunaTerminal") as TerminalBridge
        assertTrue(bridge.screenReaderEnabled())

        accessibility.setTouchExplorationEnabled(false)
        assertFalse(bridge.screenReaderEnabled())
        bridge.ready()

        accessibility.setTouchExplorationEnabled(true)
        assertTrue(bridge.screenReaderEnabled())
        assertEquals("luna.screenReader(true)", shadowOf(view).lastEvaluatedJavascript)

        controller.close()
        accessibility.setTouchExplorationEnabled(false)
        assertTrue(bridge.screenReaderEnabled())
        assertEquals("luna.screenReader(true)", shadowOf(view).lastEvaluatedJavascript)
    }

    @Test fun `output waits for rendering before accepting the next chunk`() = runTest(dispatcher) {
        val view = controller.view(context)
        val bridge = shadowOf(view).getJavascriptInterface("LunaTerminal") as TerminalBridge
        bridge.ready()
        val output = launch { controller.write(ByteArray(20000) { 97 }) }
        runCurrent()
        assertFalse(output.isCompleted)
        val first = shadowOf(view).lastEvaluatedJavascript
        bridge.written(99)
        runCurrent()
        assertEquals(first, shadowOf(view).lastEvaluatedJavascript)
        bridge.written(1)
        runCurrent()
        assertFalse(output.isCompleted)
        assertTrue(first != shadowOf(view).lastEvaluatedJavascript)
        bridge.written(2)
        runCurrent()
        assertTrue(output.isCompleted)
    }

    @Test fun `closing releases both pending initialization and output`() = runTest(dispatcher) {
        val initializing = launch { controller.write(byteArrayOf(1)) }
        runCurrent()
        controller.close()
        runCurrent()
        assertTrue(initializing.isCancelled)

        val loaded = TerminalSurfaceController()
        try {
            val view = loaded.view(context)
            val bridge = shadowOf(view).getJavascriptInterface("LunaTerminal") as TerminalBridge
            bridge.ready()
            val output = launch { loaded.write(byteArrayOf(1)) }
            runCurrent()
            loaded.close()
            runCurrent()
            assertTrue(output.isCancelled)
            assertTrue(shadowOf(view).wasDestroyCalled())
        } finally { loaded.close() }
    }

    @Test fun `moving the surface between parents keeps the running emulator`() {
        val view = controller.view(context)
        FrameLayout(context).addView(view)
        val remounted = controller.view(context)
        assertSame(view, remounted)
        assertEquals(null, remounted.parent)
        assertFalse(shadowOf(view).wasDestroyCalled())
    }

    @Test fun `renderer loss fails output and releases the broken view`() = runTest(dispatcher) {
        val view = controller.view(context)
        val bridge = shadowOf(view).getJavascriptInterface("LunaTerminal") as TerminalBridge
        bridge.ready()
        var failure: IOException? = null
        var reported: String? = null
        controller.onFailure = { reported = it }
        val output = launch {
            try { controller.write(byteArrayOf(1)) }
            catch (error: IOException) { failure = error }
        }
        runCurrent()
        val handled = shadowOf(view).webViewClient.onRenderProcessGone(view, object : RenderProcessGoneDetail() {
            override fun didCrash() = true
            override fun rendererPriorityAtExit() = 0
        })
        runCurrent()
        assertTrue(handled)
        assertTrue(output.isCompleted)
        assertFalse(output.isCancelled)
        assertTrue(failure != null)
        assertTrue(reported != null)
        assertTrue(controller.isClosed)
        assertTrue(shadowOf(view).wasDestroyCalled())
    }

    @Test fun `control applies once while binary and unicode input remain intact`() {
        val input = mutableListOf<ByteArray>()
        controller.onInput = { input.add(it) }
        val bridge = TerminalBridge(controller)
        controller.toggleControl()
        bridge.input(Base64.encodeToString("c".toByteArray(), Base64.NO_WRAP))
        bridge.input(Base64.encodeToString("é".toByteArray(), Base64.NO_WRAP))
        bridge.input(Base64.encodeToString(byteArrayOf(0xff.toByte()), Base64.NO_WRAP))
        assertArrayEquals(byteArrayOf(3), input[0])
        assertArrayEquals("é".toByteArray(), input[1])
        assertArrayEquals(byteArrayOf(0xff.toByte()), input[2])
        assertFalse(controller.controlPressed)
    }

    @Test fun `the terminal serves only its bundled assets and refuses navigation`() {
        val view = controller.view(context)
        val client = shadowOf(view).webViewClient
        val allowed = request("https://luna-terminal.invalid/index.html")
        val document = client.shouldInterceptRequest(view, allowed)!!
        assertEquals(200, document.statusCode)
        document.data.use { assertTrue(it.readBytes().toString(Charsets.UTF_8).contains("terminal.js")) }
        for (url in listOf(
            "https://example.com/xterm.js", "file:///data/local/tmp/index.html", "content://some/file",
            "https://luna-terminal.invalid:443/xterm.js", "https://luna-terminal.invalid/../third-party-notices.txt",
        )) {
            val resource = request(url)
            client.shouldInterceptRequest(view, resource)!!.data.use { stream ->
                assertEquals(-1, stream.read())
            }
            assertTrue(client.shouldOverrideUrlLoading(view, resource))
        }
    }

    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }
}
