package com.lunaexplorer.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.model.Preferences
import java.io.Closeable
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class PdfPageFrame(
    val image: ImageBitmap,
    val width: Int,
    val height: Int,
    val text: String?,
    val textFailure: String?,
    val textChunks: List<String>,
)

internal class PdfReadingFile(file: File, checkActive: () -> Unit = {}) : Closeable {
    private val renderer: PdfRenderer
    private var closed = false
    val pageSizes: List<IntSize>
    init {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = try { PdfRenderer(descriptor) } catch (error: Throwable) { descriptor.close(); throw error }
        pageSizes = try {
            List(renderer.pageCount) { index ->
                checkActive()
                renderer.openPage(index).use { IntSize(it.width, it.height) }
            }.also { require(it.isNotEmpty()) { "This PDF has no pages." } }
        } catch (error: Throwable) { renderer.close(); throw error }
    }
    val pageCount: Int get() = pageSizes.size

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

private data class PdfPageSelectionRequest(val page: Int, val selection: PdfSelectionRequest)

@Composable
internal fun PdfPages(
    pdf: PdfReadingFile,
    preferences: Preferences,
    onPreferences: (Preferences) -> Unit,
    modifier: Modifier,
    fullScreen: Boolean = false,
    onFullScreenChange: (Boolean) -> Unit = {},
) {
    var savedPage by rememberSaveable(pdf) { mutableIntStateOf(0) }
    val viewport = remember(pdf, preferences.pdfDirection, preferences.pdfPageLayout) {
        PdfViewportState(pdf.pageSizes, preferences.pdfDirection, preferences.pdfPageLayout, savedPage)
    }
    val page = viewport.page
    LaunchedEffect(page) { savedPage = page }
    val frames = remember(pdf) { mutableStateMapOf<Int, PdfPageFrame>() }
    val renderLock = remember(pdf) { Mutex() }
    var goTo by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var selectionPage by remember(pdf) { mutableStateOf<Int?>(null) }
    var selection by remember(pdf) { mutableStateOf<PdfTextSelection?>(null) }
    var selectionMessage by remember(pdf) { mutableStateOf<String?>(null) }
    var selectionBusy by remember(pdf) { mutableStateOf(false) }
    var pageText by remember(pdf) { mutableStateOf<Pair<Int, PdfPageFrame>?>(null) }
    var requestSerial by remember(pdf) { mutableLongStateOf(0L) }
    val selectionRequests = remember(pdf) { MutableStateFlow<PdfPageSelectionRequest?>(null) }
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    fun clearSelection() {
        selectionRequests.value = null
        selection = null
        selectionPage = null
        selectionMessage = null
    }
    fun navigate(index: Int) {
        clearSelection()
        viewport.goToPage(index)
    }
    fun copyText(text: String) {
        selectionMessage = try {
            require(text.length <= 400_000) { "Select a smaller passage to copy." }
            clipboard.setPrimaryClip(ClipData.newPlainText("PDF text", text))
            "Copied"
        } catch (error: Exception) { error.message ?: "This text could not be copied." }
    }
    fun requestSelection(index: Int, start: PdfTextBoundary, end: PdfTextBoundary, clear: Boolean = false) {
        if (fullScreen) onFullScreenChange(false)
        if (clear || selectionPage != index) selection = null
        selectionPage = index
        selectionMessage = null
        selectionRequests.value = PdfPageSelectionRequest(index, PdfSelectionRequest(start, end, ++requestSerial))
    }
    LaunchedEffect(viewport, page) {
        if (selectionPage != null && selectionPage !in frames) clearSelection()
    }
    LaunchedEffect(pdf, selectionRequests) {
        selectionRequests.collectLatest { request ->
            if (request == null) return@collectLatest
            selectionBusy = true
            try {
                val selected = withContext(Dispatchers.IO) {
                    renderLock.withLock { ensureActive(); pdf.select(request.page, request.selection) }
                }
                if (selectionRequests.value != request) return@collectLatest
                selection = selected
                if (selected == null) selectionMessage = "No selectable text here."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                ensureActive()
                if (selectionRequests.value == request) selectionMessage = error.message ?: "This text could not be selected."
            }
            finally { selectionBusy = false }
        }
    }
    Column(modifier.fillMaxWidth()) {
        if (!fullScreen) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navigate(page - 1) }, enabled = page > 0) { Icon(Icons.AutoMirrored.Outlined.NavigateBefore, "Previous page") }
                TextButton(onClick = { goTo = true }, modifier = Modifier.weight(1f)) { Text("Page ${page + 1} of ${pdf.pageCount}") }
                IconButton(onClick = { navigate(page + 1) }, enabled = page + 1 < pdf.pageCount) { Icon(Icons.AutoMirrored.Outlined.NavigateNext, "Next page") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewport.zoomBy(1f / 1.3f) }, enabled = viewport.zoom > 1f) { Icon(Icons.Outlined.ZoomOut, "Zoom out") }
                if (pdf.pageCount > 1) Slider(value = page.toFloat(), onValueChange = { navigate(it.toInt()) },
                    valueRange = 0f..(pdf.pageCount - 1).toFloat(), modifier = Modifier.weight(1f))
                else Spacer(Modifier.weight(1f))
                IconButton(onClick = { viewport.zoomBy(1.3f) }, enabled = viewport.zoom < 6f) { Icon(Icons.Outlined.ZoomIn, "Zoom in") }
                IconButton(onClick = { options = true }) { Icon(Icons.Outlined.Settings, "PDF settings") }
            }
            val activePage = selectionPage ?: page
            val pageFrame = frames[activePage]
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (pageFrame != null) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val selected = selection
                        if (selected != null) {
                            TextButton(onClick = { copyText(selected.text) }, enabled = !selectionBusy) { Text("Copy") }
                            TextButton(onClick = {
                                pageFrame.text?.takeIf(String::isNotEmpty)?.let {
                                    requestSelection(activePage, PdfTextBoundary(index = 0), PdfTextBoundary(index = it.length))
                                }
                            }, enabled = !pageFrame.text.isNullOrEmpty()) { Text("Select page") }
                            TextButton(onClick = ::clearSelection) { Text("Clear") }
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
                            if (!pageFrame.text.isNullOrBlank()) TextButton(onClick = { pageText = activePage to pageFrame }) { Text("Page text") }
                        }
                    }
                }
            }
            if (selection != null && selectionMessage != null) Text(selectionMessage!!,
                Modifier.fillMaxWidth().padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        }
        PdfViewport(viewport, preferences.pdfTapToTurn && selection == null && !selectionBusy, preferences.pdfSwipeToTurn,
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFF353535))) { index ->
            var failure by remember(pdf, index) { mutableStateOf<String?>(null) }
            LaunchedEffect(pdf, index) {
                try {
                    frames[index] = withContext(Dispatchers.IO) {
                        renderLock.withLock { ensureActive(); pdf.render(index) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    ensureActive()
                    failure = error.message ?: "This page could not be rendered."
                }
            }
            DisposableEffect(pdf, index) {
                onDispose {
                    frames.remove(index)
                    if (selectionPage == index) clearSelection()
                }
            }
            val frame = frames[index]
            when {
                frame != null -> PdfSelectablePage(frame.image, index + 1, frame.width, frame.height,
                    textSelectable = !frame.text.isNullOrBlank(), selection = selection.takeIf { selectionPage == index },
                    onSelect = { start, end, clear -> requestSelection(index, start, end, clear) },
                    displayScale = viewport.zoom, modifier = Modifier.fillMaxSize())
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (failure != null) Text(failure!!, color = Color.White, modifier = Modifier.padding(24.dp))
                    else CircularProgressIndicator()
                }
            }
        }
    }
    pageText?.let { (index, frame) ->
        AlertDialog(onDismissRequest = { pageText = null }, title = { Text("Text on page ${index + 1}") }, text = {
            SelectionContainer {
                FastLazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    itemsIndexed(frame.textChunks) { _, text -> Text(text, style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }, confirmButton = { TextButton(onClick = { pageText = null }) { Text("Close") } },
            dismissButton = { TextButton(onClick = { copyText(frame.text!!) }) { Text("Copy page") } })
    }
    if (options) AlertDialog(onDismissRequest = { options = false }, title = { Text("PDF settings") }, text = {
        Column(Modifier.fillMaxWidth().fastVerticalScroll(rememberScrollState())) {
            PdfSettings(preferences, onPreferences, fullScreen, onFullScreenChange)
        }
    }, confirmButton = { TextButton(onClick = { options = false }) { Text("Close") } })
    if (goTo) {
        var number by remember { mutableStateOf((page + 1).toString()) }
        val target = number.toIntOrNull()
        AlertDialog(onDismissRequest = { goTo = false }, title = { Text("Go to page") }, text = {
            OutlinedTextField(number, { number = it }, label = { Text("1–${pdf.pageCount}") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }, confirmButton = { TextButton(onClick = { navigate(target!! - 1); goTo = false },
            enabled = target != null && target in 1..pdf.pageCount) { Text("Go") } },
            dismissButton = { TextButton(onClick = { goTo = false }) { Text("Cancel") } })
    }
}
