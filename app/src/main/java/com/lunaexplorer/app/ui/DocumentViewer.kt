@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lunaexplorer.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lunaexplorer.app.storage.ReadingDocument
import com.lunaexplorer.app.storage.packagePath
import com.lunaexplorer.app.storage.resourceUrl
import com.lunaexplorer.core.Entry
import java.io.Closeable
import java.io.File
import java.util.zip.ZipException
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

internal fun documentExtension(entry: Entry): String? {
    val extension = entry.name.substringAfterLast('.', "").lowercase()
    if (extension in setOf("pdf", "epub", "docx", "pptx")) return extension
    return when (entry.mimeType.lowercase()) {
        "application/pdf" -> "pdf"
        "application/epub+zip" -> "epub"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
        else -> null
    }
}

@Composable
internal fun DocumentViewer(entry: Entry, viewModel: BrowserViewModel, onDismiss: () -> Unit, asPdf: Boolean = false) {
    var pdf by remember(entry.ref) { mutableStateOf<PdfReadingFile?>(null) }
    var book by remember(entry.ref) { mutableStateOf<ReadingDocument?>(null) }
    var failure by remember(entry.ref) { mutableStateOf<String?>(null) }
    val extension = remember(entry, asPdf) { if (asPdf) "pdf" else documentExtension(entry) }
    LaunchedEffect(entry.ref) {
        var staged: File? = null
        var opened: Closeable? = null
        try {
            staged = viewModel.files.stageForViewer(entry, ReadingDocument.MAX_FILE_BYTES).getOrThrow()
            val local = staged
            // Assign inside the IO block: withContext drops its result if cancelled on return, which
            // would leak the open renderer or archive.
            withContext(Dispatchers.IO) {
                opened = if (extension == "pdf") PdfReadingFile(local)
                    else ReadingDocument.open(local, extension.orEmpty())
            }
            when (val ready = opened) {
                is PdfReadingFile -> pdf = ready
                is ReadingDocument -> book = ready
            }
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = when (error) {
                is SecurityException -> "This PDF requires a password or cannot be opened by this device’s PDF renderer."
                is ZipException -> "This file could not be read as EPUB, DOCX or PPTX. Password-protected Office files need another app."
                else -> error.message ?: "This document could not be opened."
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { opened?.close() }
                staged?.delete()
            }
        }
    }
    Column(Modifier.fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
        ViewerBar(entry.name, onDismiss, entry, viewModel,
            subtitle = when (extension) { "pdf" -> "PDF"; "epub" -> "EPUB"; "docx" -> "Word · DOCX"; "pptx" -> "PowerPoint · PPTX"; else -> "Document" })
        when {
            failure != null -> Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(failure!!, color = MaterialTheme.colorScheme.error)
            }
            pdf != null -> PdfPages(pdf!!, Modifier.weight(1f))
            book != null -> ReadingPages(book!!, Modifier.weight(1f))
            else -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Opening document…")
                }
            }
        }
    }
}

internal data class PdfPageFrame(
    val image: ImageBitmap,
    val width: Int,
    val height: Int,
    val text: String?,
    val textFailure: String?,
    val textChunks: List<String>,
)

internal class PdfReadingFile(file: File) : Closeable {
    private val renderer: PdfRenderer
    private var closed = false
    init {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = try { PdfRenderer(descriptor) } catch (error: Throwable) { descriptor.close(); throw error }
    }
    val pageCount: Int = renderer.pageCount
    @Synchronized
    fun render(index: Int): PdfPageFrame {
        check(!closed) { "The PDF has been closed." }
        return renderer.openPage(index).use { page ->
            val scale = minOf(2800.0 / maxOf(page.width, page.height),
                sqrt(7_000_000.0 / (page.width.toDouble() * page.height)))
            val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                var textFailure: String? = null
                val text = if (Build.VERSION.SDK_INT >= 35) {
                    try { PdfTextApi.pageText(page) }
                    catch (error: Exception) { textFailure = error.message ?: "Text on this page could not be read."; null }
                } else null
                PdfPageFrame(bitmap.asImageBitmap(), page.width, page.height, text, textFailure,
                    text?.let(::pdfTextChunks).orEmpty())
            } catch (error: Throwable) { bitmap.recycle(); throw error }
        }
    }

    @Synchronized
    fun select(index: Int, request: PdfSelectionRequest): PdfTextSelection? {
        check(!closed) { "The PDF has been closed." }
        if (Build.VERSION.SDK_INT < 35) return null
        return renderer.openPage(index).use { page -> PdfTextApi.select(page, request) }
    }

    @Synchronized override fun close() {
        if (!closed) { closed = true; renderer.close() }
    }
}

@Composable
private fun PdfPages(pdf: PdfReadingFile, modifier: Modifier) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var frame by remember(pdf) { mutableStateOf<PdfPageFrame?>(null) }
    var failure by remember(pdf) { mutableStateOf<String?>(null) }
    var goTo by remember { mutableStateOf(false) }
    var selection by remember(pdf, page) { mutableStateOf<PdfTextSelection?>(null) }
    var selectionMessage by remember(pdf, page) { mutableStateOf<String?>(null) }
    var selectionBusy by remember(pdf, page) { mutableStateOf(false) }
    var showPageText by remember(pdf, page) { mutableStateOf(false) }
    var requestSerial by remember(pdf, page) { mutableLongStateOf(0L) }
    val selectionRequests = remember(pdf, page) { MutableStateFlow<PdfSelectionRequest?>(null) }
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    fun copyText(text: String) {
        selectionMessage = try {
            require(text.length <= 400_000) { "Select a smaller passage to copy." }
            clipboard.setPrimaryClip(ClipData.newPlainText("PDF text", text))
            "Copied"
        } catch (error: Exception) { error.message ?: "This text could not be copied." }
    }
    fun requestSelection(start: PdfTextBoundary, end: PdfTextBoundary, clear: Boolean = false) {
        if (clear) selection = null
        selectionMessage = null
        selectionRequests.value = PdfSelectionRequest(start, end, ++requestSerial)
    }
    LaunchedEffect(pdf, page, selectionRequests) {
        // The running native select call finishes before the newest request starts, and requests in
        // between are dropped, so handle drags do not build a backlog.
        selectionRequests.collectLatest { request ->
            if (request == null) return@collectLatest
            selectionBusy = true
            try {
                val selected = withContext(Dispatchers.IO) { pdf.select(page, request) }
                if (selectionRequests.value != request) return@collectLatest
                selection = selected
                if (selected == null) selectionMessage = "No selectable text here."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (selectionRequests.value == request) selectionMessage = error.message ?: "This text could not be selected."
            }
            finally { selectionBusy = false }
        }
    }
    LaunchedEffect(pdf, page) {
        frame = null
        failure = null
        zoom = 1f
        try {
            frame = withContext(Dispatchers.IO) { pdf.render(page) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message ?: "This page could not be rendered." }
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { page-- }, enabled = page > 0) { Icon(Icons.AutoMirrored.Outlined.NavigateBefore, "Previous page") }
            TextButton(onClick = { goTo = true }, modifier = Modifier.weight(1f)) { Text("Page ${page + 1} of ${pdf.pageCount}") }
            IconButton(onClick = { page++ }, enabled = page + 1 < pdf.pageCount) { Icon(Icons.AutoMirrored.Outlined.NavigateNext, "Next page") }
            IconButton(onClick = { zoom = (zoom / 1.3f).coerceAtLeast(1f) }, enabled = zoom > 1f) { Icon(Icons.Outlined.ZoomOut, "Zoom out") }
            IconButton(onClick = { zoom = (zoom * 1.3f).coerceAtMost(6f) }, enabled = zoom < 6f) { Icon(Icons.Outlined.ZoomIn, "Zoom in") }
        }
        if (pdf.pageCount > 1) Slider(value = page.toFloat(), onValueChange = { page = it.toInt() },
            valueRange = 0f..(pdf.pageCount - 1).toFloat(), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        val pageFrame = frame
        if (pageFrame != null) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val selected = selection
                if (selected != null) {
                    TextButton(onClick = { copyText(selected.text) }, enabled = !selectionBusy) { Text("Copy") }
                    TextButton(onClick = {
                        pageFrame.text?.takeIf(String::isNotEmpty)?.let {
                            requestSelection(PdfTextBoundary(index = 0), PdfTextBoundary(index = it.length))
                        }
                    }, enabled = !pageFrame.text.isNullOrEmpty()) { Text("Select page") }
                    TextButton(onClick = {
                        selectionRequests.value = null
                        selection = null
                        selectionMessage = null
                    }) { Text("Clear") }
                    Spacer(Modifier.weight(1f))
                    if (selectionBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    val hint = selectionMessage ?: pageFrame.textFailure ?: when {
                        Build.VERSION.SDK_INT < 35 -> "Text selection needs Android 15 or later."
                        pageFrame.text.isNullOrBlank() -> "This page has no selectable text."
                        else -> "Long-press text to select."
                    }
                    Text(hint, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!pageFrame.text.isNullOrBlank()) TextButton(onClick = { showPageText = true }) { Text("Page text") }
                }
            }
            if (selection != null && selectionMessage != null) Text(selectionMessage!!,
                Modifier.fillMaxWidth().padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF353535)), contentAlignment = Alignment.TopCenter) {
            val bitmap = frame?.image
            when {
                failure != null -> Text(failure!!, color = Color.White, modifier = Modifier.padding(24.dp))
                bitmap == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> {
                    val vertical = rememberScrollState()
                    val horizontal = rememberScrollState()
                    val ratio = bitmap.width.toFloat() / bitmap.height
                    val fittedWidth = minOf(maxWidth, maxHeight * ratio)
                    val fittedHeight = fittedWidth / ratio
                    LaunchedEffect(page) { vertical.scrollTo(0); horizontal.scrollTo(0) }
                    Box(Modifier.fillMaxSize()
                        .pointerInput(pdf) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.count { it.pressed } >= 2) {
                                        zoom = (zoom * event.calculateZoom()).coerceIn(1f, 6f)
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .fastTwoDimensionalScroll(vertical, horizontal), contentAlignment = Alignment.TopCenter) {
                        PdfSelectablePage(bitmap, page + 1, frame!!.width, frame!!.height,
                            textSelectable = !frame!!.text.isNullOrBlank(), selection = selection,
                            onSelect = ::requestSelection,
                            modifier = Modifier.size(fittedWidth * zoom, fittedHeight * zoom))
                    }
                }
            }
        }
    }
    if (showPageText && frame?.text != null) {
        AlertDialog(onDismissRequest = { showPageText = false }, title = { Text("Text on page ${page + 1}") }, text = {
            SelectionContainer {
                FastLazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    itemsIndexed(frame!!.textChunks) { _, text -> Text(text, style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }, confirmButton = { TextButton(onClick = { showPageText = false }) { Text("Close") } },
            dismissButton = { TextButton(onClick = { copyText(frame!!.text!!) }) { Text("Copy page") } })
    }
    if (goTo) {
        var number by remember { mutableStateOf((page + 1).toString()) }
        val target = number.toIntOrNull()
        AlertDialog(onDismissRequest = { goTo = false }, title = { Text("Go to page") }, text = {
            OutlinedTextField(number, { number = it }, label = { Text("1–${pdf.pageCount}") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }, confirmButton = { TextButton(onClick = { page = target!! - 1; goTo = false },
            enabled = target != null && target in 1..pdf.pageCount) { Text("Go") } },
            dismissButton = { TextButton(onClick = { goTo = false }) { Text("Cancel") } })
    }
}

@Composable
private fun ReadingPages(book: ReadingDocument, modifier: Modifier) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var contents by remember { mutableStateOf(false) }
    var textSize by rememberSaveable { mutableIntStateOf(100) }
    var fontMenu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        book.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (book.sections.size > 1) {
                IconButton(onClick = { section-- }, enabled = section > 0) { Icon(Icons.AutoMirrored.Outlined.NavigateBefore, "Previous section") }
                TextButton(onClick = { contents = true }, modifier = Modifier.weight(1f)) { Text("${section + 1} of ${book.sections.size}") }
                IconButton(onClick = { section++ }, enabled = section + 1 < book.sections.size) { Icon(Icons.AutoMirrored.Outlined.NavigateNext, "Next section") }
                IconButton(onClick = { contents = true }) { Icon(Icons.AutoMirrored.Outlined.List, "Contents") }
            } else Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { fontMenu = true }) { Icon(Icons.Outlined.FormatSize, "Text size") }
                FastDropdownMenu(fontMenu, onDismissRequest = { fontMenu = false }) {
                    listOf(80, 100, 120, 150, 180, 220).forEach { size ->
                        DropdownMenuItem(text = { Text("$size%") }, onClick = { textSize = size; fontMenu = false })
                    }
                }
            }
        }
        OfflineDocumentWebView(book, section, textSize, onSectionChanged = { section = it }, Modifier.weight(1f))
    }
    if (contents) AlertDialog(onDismissRequest = { contents = false }, title = { Text("Contents") }, text = {
        FastLazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            itemsIndexed(book.sections) { index, item ->
                TextButton(onClick = { section = index; contents = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(item.title, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { contents = false }) { Text("Close") } })
}

@Composable
private fun OfflineDocumentWebView(book: ReadingDocument, section: Int, textSize: Int,
    onSectionChanged: (Int) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val latestSectionChanged by rememberUpdatedState(onSectionChanged)
    val thumb = MaterialTheme.colorScheme.primary.toArgb()
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = .1f).toArgb()
    val web = remember(book) {
        FastScrollWebView(context).apply {
            setBackgroundColor(AndroidColor.WHITE)
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.blockNetworkImage = false
            settings.domStorageEnabled = false
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            setDownloadListener { _, _, _, _, _ -> }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val uri = request.url
                    // data: subresources (embedded images and fonts) load natively; the CSP limits their
                    // kinds, and a main-frame data: navigation falls through to the 404 below.
                    if (uri.scheme == "data" && !request.isForMainFrame) return null
                    val resource = if (uri.scheme == "https" && uri.host == "luna-document.invalid" && uri.port == -1)
                        runCatching { book.resource(uri.encodedPath.orEmpty().removePrefix("/")) }.getOrNull() else null
                    if (resource == null) return WebResourceResponse("text/plain", "UTF-8", 404, "Not found", emptyMap(), "".byteInputStream())
                    return WebResourceResponse(resource.mime, "UTF-8", 200, "OK", mapOf(
                        "Content-Security-Policy" to "default-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; font-src 'self' data:; base-uri 'self'; form-action 'none'; frame-src 'none'",
                        "X-Content-Type-Options" to "nosniff",
                    ), resource.bytes.inputStream())
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme != "https" || uri.host != "luna-document.invalid" || uri.port != -1) return true
                    val path = packagePath("", uri.encodedPath.orEmpty().removePrefix("/")) ?: return true
                    val index = book.sections.indexOfFirst { it.path == path }
                    if (index >= 0) latestSectionChanged(index)
                    return false
                }
            }
        }
    }
    DisposableEffect(web) { onDispose { web.stopLoading(); web.destroy() } }
    LaunchedEffect(web, section) {
        val uri = Uri.parse(web.url.orEmpty())
        val current = packagePath("", uri.encodedPath.orEmpty().removePrefix("/"))
        if (current != book.sections[section].path) web.loadUrl(resourceUrl(book.sections[section].path))
    }
    LaunchedEffect(web, textSize) { web.settings.textZoom = textSize }
    AndroidView(factory = { web }, modifier = modifier.fillMaxWidth(),
        update = { it.setFastScrollColors(thumb, track) })
}
