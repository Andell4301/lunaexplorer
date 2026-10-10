package com.lunaexplorer.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

internal enum class TerminalKey {
    ESCAPE, TAB, ENTER, BACKSPACE, UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}

internal class TerminalWebView(context: Context) : WebView(context) {
    var disableSuggestions = true
    var onText: (String) -> Unit = {}
    var onComposition: (String) -> Unit = {}
    private var inputConnection: TerminalInputConnection? = null

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        inputConnection?.finishComposingText()
        outAttrs.inputType = if (disableSuggestions) InputType.TYPE_NULL else
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return TerminalInputConnection(this, { onText(it) }, { onComposition(it) }, ::dispatchKeyEvent)
            .also { inputConnection = it }
    }

    fun finishComposition(onFinished: () -> Unit) {
        inputConnection?.finishComposingText()
        onFinished()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(event.keyCode)) {
            inputConnection?.finishComposingText()
        }
        return super.dispatchKeyEvent(event)
    }
}

internal class TerminalSurfaceController : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val ready = CompletableDeferred<Unit>()
    private val writes = Mutex()
    private var web: TerminalWebView? = null
    private var closed by mutableStateOf(false)
    private var closeCause: Throwable? = null
    internal val isClosed get() = closed
    private var writeId = 0
    private var pendingWrite: Pair<Int, CompletableDeferred<Unit>>? = null
    private var pendingKeyboard: Boolean? = null
    private var pendingTap: Int? = null
    private var keyboardRequest = 0
    private var disableSuggestions = true
    private var tapOpensKeyboard = true
    private var inputOptionsVersion = 0
    private var accessibility: AccessibilityManager? = null
    @Volatile private var screenReader = false
    private val accessibilityListener = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
        onMain {
            screenReader = enabled
            evaluate("luna.screenReader($enabled)")
        }
    }
    internal var onInput: (ByteArray) -> Unit = {}
    internal var onResize: (Int, Int) -> Unit = { _, _ -> }
    internal var onDirectoryChanged: (String) -> Unit = {}
    internal var onFailure: (String) -> Unit = {}
    var controlPressed by mutableStateOf(false)
        private set

    suspend fun write(bytes: ByteArray) = writes.withLock {
        withContext(Dispatchers.Main.immediate) {
            if (withTimeoutOrNull(15000) { ready.await(); true } != true) throw IOException("Terminal did not start")
            if (closed) throw requireNotNull(closeCause)
            var offset = 0
            while (offset < bytes.size) {
                val size = minOf(16384, bytes.size - offset)
                val encoded = Base64.encodeToString(bytes, offset, size, Base64.NO_WRAP)
                val completion = CompletableDeferred<Unit>()
                val id = ++writeId
                pendingWrite = id to completion
                try {
                    web?.evaluateJavascript("luna.write('$encoded',$id)", null)
                    completion.await()
                } finally {
                    if (pendingWrite?.first == id) pendingWrite = null
                }
                offset += size
            }
        }
    }

    fun sendKey(key: TerminalKey) = onMain {
        web?.finishComposition { evaluate("luna.key('${key.name}')") }
    }

    fun paste(text: String) = onMain {
        web?.finishComposition { evaluate("luna.paste(${JSONObject.quote(text)})") }
    }

    fun focus() = evaluate("luna.focus()")

    fun showKeyboard() = requestKeyboard(explicit = true)

    fun setTapOpensKeyboard(enabled: Boolean) = onMain {
        tapOpensKeyboard = enabled
        if (!enabled && pendingKeyboard == false) pendingKeyboard = null
    }

    fun setDisableSuggestions(disabled: Boolean) = onMain {
        if (closed || disableSuggestions == disabled) return@onMain
        disableSuggestions = disabled
        if (!ready.isCompleted) web?.disableSuggestions = disabled
        applyInputOptions()
    }

    private fun applyInputOptions() {
        val view = web?.takeIf { ready.isCompleted && !closed } ?: return
        val version = ++inputOptionsVersion
        view.finishComposition {
            if (!closed && web === view && version == inputOptionsVersion) {
                view.disableSuggestions = disableSuggestions
                if (view.isAttachedToWindow) {
                    val keyboard = view.context.getSystemService(InputMethodManager::class.java)
                    if (keyboard.isActive(view)) keyboard.restartInput(view)
                }
            }
        }
    }

    private fun requestKeyboard(explicit: Boolean) = onMain {
        if (closed || (!explicit && !tapOpensKeyboard)) return@onMain
        pendingTap = null
        pendingKeyboard = explicit
        val view = web?.takeIf { ready.isCompleted } ?: return@onMain
        pendingKeyboard = null
        val request = ++keyboardRequest
        view.requestFocus()
        view.evaluateJavascript(if (explicit) "luna.focus(); true" else "luna.focusForInput()") { focused ->
            if (focused == "true" && (explicit || tapOpensKeyboard) && !closed && web === view && request == keyboardRequest) view.post {
                if ((explicit || tapOpensKeyboard) && !closed && web === view && request == keyboardRequest && view.isAttachedToWindow) {
                    val keyboard = view.context.getSystemService(InputMethodManager::class.java)
                    keyboard.restartInput(view)
                    keyboard.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }

    fun toggleControl() = onMain { controlPressed = !controlPressed }

    suspend fun selection(): String = withTimeout(5000) {
        withContext(Dispatchers.Main.immediate) {
            ready.await()
            if (closed) throw requireNotNull(closeCause)
            suspendCancellableCoroutine { continuation ->
                web?.evaluateJavascript("luna.selection()") { result ->
                    if (continuation.isActive) continuation.resume(JSONTokener(result).nextValue() as? String ?: "")
                }
            }
        }
    }

    private fun evaluate(script: String) = onMain {
        if (ready.isCompleted && !closed) web?.evaluateJavascript(script, null)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    internal fun loaded() = onMain {
        if (closed) return@onMain
        ready.complete(Unit)
        evaluate("luna.screenReader($screenReader)")
        applyInputOptions()
        pendingKeyboard?.let(::requestKeyboard)
    }

    internal fun screenReaderEnabled(): Boolean = screenReader

    internal fun tapped() = onMain {
        val gesture = pendingTap
        pendingTap = null
        if (gesture != null && gesture == keyboardRequest) requestKeyboard(explicit = false)
    }

    internal fun written(id: Int) = onMain {
        pendingWrite?.takeIf { it.first == id }?.second?.complete(Unit)
    }

    internal fun input(encoded: String) = onMain {
        acceptInput(Base64.decode(encoded, Base64.NO_WRAP))
    }

    private fun acceptInput(bytes: ByteArray) {
        if (closed) return
        if (controlPressed && bytes.size == 1) {
            val key = bytes[0].toInt() and 255
            val control = when (key) {
                in 64..95, in 97..122 -> key and 31
                32 -> 0
                63 -> 127
                else -> key
            }
            bytes[0] = control.toByte()
        }
        controlPressed = false
        onInput(bytes)
    }

    internal fun resize(columns: Int, rows: Int) = onMain {
        if (!closed && columns in 2..1000 && rows in 1..1000) onResize(columns, rows)
    }

    internal fun directory(uri: String) = onMain {
        if (!closed) onDirectoryChanged(uri)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    internal fun view(context: Context): WebView {
        check(!closed)
        val assets = context.applicationContext.assets
        val view = web ?: TerminalWebView(context.applicationContext).apply {
            disableSuggestions = this@TerminalSurfaceController.disableSuggestions
            onText = { text ->
                evaluate("luna.input(${JSONObject.quote(text)})")
            }
            onComposition = { text -> evaluate("luna.composition(${JSONObject.quote(text)})") }
            // Wrap-content WebViews cannot resolve the terminal's percentage height.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            accessibility = context.applicationContext.getSystemService(AccessibilityManager::class.java)
            screenReader = accessibility?.isTouchExplorationEnabled == true
            accessibility?.addTouchExplorationStateChangeListener(accessibilityListener, main)
            setBackgroundColor(Color.rgb(17, 19, 24))
            isFocusableInTouchMode = true
            val taps = GestureDetector(context.applicationContext, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent) = true
                override fun onSingleTapUp(event: MotionEvent): Boolean {
                    pendingTap = keyboardRequest
                    return true
                }
            })
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    keyboardRequest++
                    pendingKeyboard = null
                    pendingTap = null
                }
                taps.onTouchEvent(event)
                false
            }
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    if (disableSuggestions != this@TerminalSurfaceController.disableSuggestions) applyInputOptions()
                }
                override fun onViewDetachedFromWindow(view: View) {
                    keyboardRequest++
                    inputOptionsVersion++
                    pendingKeyboard = null
                    pendingTap = null
                }
            })
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.blockNetworkImage = true
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.setSupportZoom(false)
            setDownloadListener { _, _, _, _, _ -> }
            addJavascriptInterface(TerminalBridge(this@TerminalSurfaceController), "LunaTerminal")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    release(IOException("Terminal renderer stopped"))
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val uri = request.url
                    val name = uri.path.orEmpty().removePrefix("/")
                    val mime = when (name) {
                        "index.html" -> "text/html"
                        "terminal.js", "xterm.js", "addon-fit.js" -> "text/javascript"
                        "terminal.css", "xterm.css" -> "text/css"
                        else -> null
                    }
                    if (uri.scheme != "https" || uri.host != "luna-terminal.invalid" || uri.port != -1 ||
                        request.method != "GET" || mime == null) {
                        return WebResourceResponse("text/plain", "UTF-8", 404, "Not found", emptyMap(), "".byteInputStream())
                    }
                    return WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf(
                        "Content-Security-Policy" to "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-src 'none'; connect-src 'none'",
                        "X-Content-Type-Options" to "nosniff",
                    ), assets.open("terminal/$name"))
                }
            }
            web = this
            loadUrl("https://luna-terminal.invalid/index.html")
        }
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    override fun close() = release(CancellationException("Terminal closed"))

    private fun release(cause: Throwable) = onMain {
        if (closed) return@onMain
        closeCause = cause
        closed = true
        ready.completeExceptionally(cause)
        pendingWrite?.second?.completeExceptionally(cause)
        pendingWrite = null
        pendingKeyboard = null
        pendingTap = null
        keyboardRequest++
        inputOptionsVersion++
        accessibility?.removeTouchExplorationStateChangeListener(accessibilityListener)
        accessibility = null
        web?.apply {
            (parent as? ViewGroup)?.removeView(this)
            removeJavascriptInterface("LunaTerminal")
            stopLoading()
            destroy()
        }
        web = null
        onInput = {}
        onResize = { _, _ -> }
        onDirectoryChanged = {}
        val failed = onFailure
        onFailure = {}
        if (cause is IOException) failed(cause.message ?: "Terminal renderer stopped")
    }
}

@Keep
internal class TerminalBridge(private val controller: TerminalSurfaceController) {
    @JavascriptInterface fun ready() = controller.loaded()
    @JavascriptInterface fun input(encoded: String) = controller.input(encoded)
    @JavascriptInterface fun resize(columns: Int, rows: Int) = controller.resize(columns, rows)
    @JavascriptInterface fun written(id: Int) = controller.written(id)
    @JavascriptInterface fun directory(uri: String) = controller.directory(uri)
    @JavascriptInterface fun screenReaderEnabled(): Boolean = controller.screenReaderEnabled()
    @JavascriptInterface fun tapped() = controller.tapped()
}

@Composable
internal fun TerminalSurface(
    controller: TerminalSurfaceController,
    onInput: (ByteArray) -> Unit,
    onResize: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    onDirectoryChanged: (String) -> Unit = {},
) {
    if (controller.isClosed) {
        Spacer(modifier)
        return
    }
    SideEffect {
        controller.onInput = onInput
        controller.onResize = onResize
        controller.onDirectoryChanged = onDirectoryChanged
    }
    key(controller) {
        AndroidView(
            factory = { context -> controller.view(context) },
            modifier = modifier,
            onRelease = { view -> (view.parent as? ViewGroup)?.removeView(view) },
        )
    }
}
