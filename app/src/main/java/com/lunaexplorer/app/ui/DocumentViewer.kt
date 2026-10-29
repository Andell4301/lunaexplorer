@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lunaexplorer.app.ui

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lunaexplorer.app.storage.ReadingDocument
import com.lunaexplorer.app.storage.packagePath
import com.lunaexplorer.app.storage.resourceUrl
import com.lunaexplorer.core.Entry
import java.io.Closeable
import java.io.File
import java.util.zip.ZipException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
internal fun DocumentViewer(
    entry: Entry,
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit,
    asPdf: Boolean = false,
    fullScreen: Boolean = false,
    onFullScreenChange: (Boolean) -> Unit = {},
) {
    var pdf by remember(entry.ref) { mutableStateOf<PdfReadingFile?>(null) }
    var book by remember(entry.ref) { mutableStateOf<ReadingDocument?>(null) }
    var failure by remember(entry.ref) { mutableStateOf<String?>(null) }
    val browserState by viewModel.state.collectAsState()
    val extension = remember(entry, asPdf) { if (asPdf) "pdf" else documentExtension(entry) }
    if (extension == "pdf") SystemBars(shown = !fullScreen)
    LaunchedEffect(entry.ref) {
        var staged: File? = null
        var opened: Closeable? = null
        try {
            staged = viewModel.files.stageForViewer(entry, ReadingDocument.MAX_FILE_BYTES).getOrThrow()
            val local = staged
            // Assign inside the IO block: withContext drops its result if cancelled on return, which
            // would leak the open renderer or archive.
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                opened = if (extension == "pdf") PdfReadingFile(local) { context.ensureActive() }
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
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().then(if (fullScreen) Modifier else Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)))) {
            if (!fullScreen) ViewerBar(entry.name, onDismiss, entry, viewModel,
                subtitle = when (extension) { "pdf" -> "PDF"; "epub" -> "EPUB"; "docx" -> "Word · DOCX"; "pptx" -> "PowerPoint · PPTX"; else -> "Document" }) {
                if (pdf != null) IconButton(onClick = { onFullScreenChange(true) }) { Icon(Icons.Outlined.Fullscreen, "Full screen") }
            }
            when {
                failure != null -> Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(failure!!, color = MaterialTheme.colorScheme.error)
                }
                pdf != null -> PdfPages(pdf!!, browserState.preferences, viewModel::setPreferences, Modifier.weight(1f),
                    fullScreen = fullScreen, onFullScreenChange = onFullScreenChange)
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
        if (fullScreen) FilledTonalIconButton(onClick = { onFullScreenChange(false) },
            modifier = Modifier.align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(8.dp)) {
            Icon(Icons.Outlined.FullscreenExit, "Exit full screen")
        }
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
