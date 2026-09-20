@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.lunaexplorer.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.text.Selection
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.lunaexplorer.app.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val LOG_TEXT_TAG = "luna-debug-log-text"

// A native TextView, because selection has to span the whole log, including text outside the viewport.
@Composable
internal fun DebugLogScreen(viewModel: BrowserViewModel, currentFolder: String?, onDismiss: () -> Unit) {
    val log = viewModel.debugLog
    val recording by log.recording.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    // Separate key so a repeated identical notice restarts the timeout.
    var noticeId by remember { mutableIntStateOf(0) }
    fun show(text: String) { notice = text; noticeId++ }
    var saving by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var empty by remember { mutableStateOf(true) }
    var view by remember { mutableStateOf<ScrollView?>(null) }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val thumbColor = MaterialTheme.colorScheme.primary.toArgb()
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .10f).toArgb()

    LaunchedEffect(noticeId) { if (notice != null) { delay(4_000); notice = null } }
    LaunchedEffect(view) { view?.let { follow(log, it) { isEmpty -> empty = isEmpty } } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).zIndex(1f)
                    .height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onDismiss)
                    Text("Debug log", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
                    ToolIcon(Icons.Outlined.ContentCopy, "Copy all") {
                        scope.launch {
                            val clip = withContext(Dispatchers.IO) { DebugLog.tailForClipboard(log.snapshot().text) }
                            show(when {
                                clip.text.isEmpty() -> "Nothing to copy"
                                else -> try {
                                    context.getSystemService(ClipboardManager::class.java)
                                        .setPrimaryClip(ClipData.newPlainText("Luna debug log", clip.text))
                                    if (clip.lines < clip.totalLines) "Copied last ${clip.lines} of ${clip.totalLines} lines"
                                    else "Copied ${lines(clip.lines)}"
                                } catch (_: RuntimeException) {
                                    "Too large for the clipboard"
                                }
                            })
                        }
                    }
                    ToolIcon(Icons.Outlined.Save, "Save") { saving = true }
                    ToolIcon(Icons.Outlined.DeleteSweep, "Clear") { confirmClear = true }
                }
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).zIndex(1f).padding(horizontal = 16.dp)) {
                    SwitchRow("Record", recording) { viewModel.setDebugLogging(it) }
                }
                notice?.let {
                    Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    AndroidView(
                        factory = { ctx ->
                            FastScrollView(ctx).apply {
                                isFillViewport = true
                                addView(TextView(ctx).apply {
                                    tag = LOG_TEXT_TAG
                                    setTextIsSelectable(true)
                                    typeface = Typeface.MONOSPACE
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                                    val pad = (12 * resources.displayMetrics.density).toInt()
                                    setPadding(pad, pad, pad, pad)
                                    setText("", TextView.BufferType.EDITABLE)
                                }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                            }.also { view = it }
                        },
                        update = { scroll ->
                            (scroll.getChildAt(0) as TextView).setTextColor(textColor)
                            scroll.setFastScrollColors(thumbColor, trackColor)
                        },
                        modifier = Modifier.fillMaxSize().clipToBounds(),
                    )
                    if (empty) {
                        Text("No entries", Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
            if (saving) {
                SaveLogDialog(viewModel, currentFolder, onSaved = { path -> saving = false; show("Saved to $path") },
                    onDismiss = { saving = false })
            }
            if (confirmClear) {
                AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    title = { Text("Clear the log?") },
                    confirmButton = { TextButton(onClick = { confirmClear = false; log.clear() }) { Text("Clear") } },
                    dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
                )
            }
        }
    }
}

private fun lines(count: Int) = if (count == 1) "one line" else "$count lines"

private suspend fun follow(log: DebugLog, scroll: ScrollView, onEmpty: (Boolean) -> Unit) {
    val text = scroll.getChildAt(0) as TextView
    var sequence = -1L
    var epoch = -1
    log.changes.collect {
        // Changing the text clears an active selection, so wait for it to end.
        while (text.hasSelection()) delay(250)
        // A leftover cursor would be scrolled into view by the append.
        text.editableText?.let { if (Selection.getSelectionStart(it) >= 0) Selection.removeSelection(it) }
        val update = if (sequence < 0) null else withContext(Dispatchers.IO) { log.since(sequence, epoch) }
        if (update == null) {
            val snapshot = withContext(Dispatchers.IO) { log.snapshot() }
            text.setText(snapshot.text, TextView.BufferType.EDITABLE)
            sequence = snapshot.sequence
            epoch = snapshot.epoch
            scroll.post { scroll.scrollTo(0, text.bottom) }
        } else if (update.text.isNotEmpty()) {
            val following = atBottom(scroll, text)
            text.append(update.text)
            sequence = update.sequence
            trim(text, scroll, log.maxChars, following)
            if (following && !text.hasSelection()) scroll.post { scroll.scrollTo(0, text.bottom) }
        }
        onEmpty(text.length() == 0)
        delay(250)
    }
}

private fun atBottom(scroll: ScrollView, text: TextView): Boolean {
    val slack = (24 * scroll.resources.displayMetrics.density).toInt()
    return scroll.scrollY + scroll.height >= text.height - slack
}

private fun trim(text: TextView, scroll: ScrollView, maxChars: Int, following: Boolean) {
    val editable = text.editableText ?: return
    val excess = editable.length - maxChars
    if (excess <= 0) return
    val cut = editable.indexOf('\n', excess).let { if (it < 0) excess else it + 1 }
    val layout = text.layout
    val removedHeight = if (!following && layout != null) layout.getLineTop(layout.getLineForOffset(cut)) else 0
    editable.delete(0, cut)
    if (removedHeight > 0) scroll.scrollBy(0, -removedHeight)
}

@Composable
private fun SaveLogDialog(viewModel: BrowserViewModel, currentFolder: String?, onSaved: (String) -> Unit, onDismiss: () -> Unit) {
    val downloads = remember { viewModel.defaultLogFolder() }
    var folder by remember { mutableStateOf(downloads) }
    var name by remember { mutableStateOf(viewModel.defaultLogName()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Save log") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(folder, { folder = it; error = null }, label = { Text("Folder") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (currentFolder != null && currentFolder != downloads) {
                        FilterChip(folder == currentFolder, { folder = currentFolder; error = null }, label = { Text("Current folder") })
                    }
                }
                OutlinedTextField(name, { name = it; error = null }, label = { Text("Name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && folder.isNotBlank() && name.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    viewModel.saveDebugLog(folder, name).fold(
                        { path -> busy = false; onSaved(path) },
                        { failure -> busy = false; error = failure.message ?: "The log could not be saved" },
                    )
                }
            }) { Text(if (busy) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}
