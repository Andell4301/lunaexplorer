@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lunaexplorer.app.model.SyntaxScheme
import com.lunaexplorer.core.Entry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun TextEditor(entry: Entry, viewModel: BrowserViewModel, onDismiss: () -> Unit, code: Boolean = false) {
    val session = remember(viewModel, entry.ref) { viewModel.editorSession(entry, code) }
    TextEditorScreen(session, code, onDismiss,
        onScheme = { viewModel.setPreferences(viewModel.state.value.preferences.copy(syntaxScheme = it)) }, viewModel)
}

/** A session with no file (a manifest) gets Copy instead of the file actions. */
@Composable
internal fun TextEditorScreen(
    session: TextEditorSession,
    code: Boolean,
    onDismiss: () -> Unit,
    onScheme: (SyntaxScheme) -> Unit,
    viewModel: BrowserViewModel? = null,
) {
    val file = session.file
    val text = session.text
    val document = session.document
    val failure = session.failure
    val saveError = session.saveError
    val saving = session.saving
    val changed = session.changed
    val language = session.language
    val wrap = session.wrap
    val find = session.find
    var confirmExit by session::confirmExit
    var copyStatus by remember(session) { mutableStateOf<String?>(null) }
    var fieldLayout by remember(session) { mutableStateOf<TextLayoutResult?>(null) }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val rows = rememberLazyListState()
    val clipboard = LocalClipboard.current
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()

    LaunchedEffect(session.closeReady) { if (session.closeReady) onDismiss() }

    fun requestClose() {
        if (saving) return
        if (changed) confirmExit = true else onDismiss()
    }

    // The first character on screen: a new search starts from the match at or after it.
    val anchor = {
        if (document != null) document.rowStart(rows.firstVisibleItemIndex)
        else fieldLayout?.let { it.getLineStart(it.getLineForVerticalPosition(vertical.value.toFloat())) } ?: 0
    }

    Dialog(onDismissRequest = { if (find.open) find.hide() else requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                ViewerBar(session.title, ::requestClose, entry = file, viewModel = viewModel,
                    // Renaming/deleting the file while its draft is open would invalidate the save.
                    fileActionsEnabled = !changed && !saving, closeOnRename = true) {
                    TextButton(onClick = { session.wrap = !wrap }) { Text(if (wrap) "No wrap" else "Wrap") }
                    // An icon: a third text button beside Wrap and Copy leaves the title no width.
                    IconButton(onClick = { if (find.open) find.hide() else find.show(anchor()) }) {
                        Icon(Icons.Outlined.Search, "Find")
                    }
                    if (file == null && document != null) {
                        TextButton(onClick = {
                            scope.launch {
                                copyStatus = try {
                                    // Large clips exceed the Binder transaction limit; refuse rather than copy partial XML.
                                    require(document.text.length <= 200_000) { "This manifest is too large for the clipboard. Its full text is still available here." }
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", document.text)))
                                    "Manifest copied"
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (error: Exception) { error.message ?: "The manifest could not be copied" }
                            }
                        }) { Text("Copy") }
                    }
                    if (session.editable) {
                        IconButton(onClick = { session.save(false) }, enabled = changed && !saving) {
                            if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.Save, "Save")
                        }
                    }
                }
                if (find.open) FindRow(find, anchor)
                if (code) {
                    FlowRow {
                        CodeLanguagePicker(language) { session.language = it }
                        SyntaxSchemePicker(LocalSyntaxScheme.current, onScheme)
                    }
                }
                copyStatus?.let {
                    Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
                val uncolored = code && highlightingOff(language, document?.text?.length ?: text?.length ?: 0, document != null)
                if (session.tooLarge || uncolored) {
                    FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (session.tooLarge) {
                            Text("Read-only: too large to edit here",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (uncolored) {
                            Text("Highlighting is off for large files.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                saveError?.let {
                    Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Text(it, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (changed) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text(if (saving) "Saving…" else "Unsaved changes",
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val current = text
                    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurface)
                    val matchColor = MaterialTheme.colorScheme.primary.copy(alpha = .25f)
                    val currentMatchColor = MaterialTheme.colorScheme.tertiary.copy(alpha = .55f)
                    when {
                        failure != null -> Text(failure, Modifier.align(Alignment.Center),
                            color = if (file == null) MaterialTheme.colorScheme.error else Color.Unspecified)
                        document != null -> {
                            // Oversized text never goes into a BasicTextField, which lays out all of it at once
                            // and ANRs; it is shown as read-only lazy rows.
                            val spans = if (code) rememberLazyTextSpans(document, language) else null
                            LazyTextRows(document, wrap, style, Modifier.fillMaxSize(), rowsTag = "editorRows", rowTag = "editorRow",
                                contentPadding = PaddingValues(16.dp), scroll = rows, horizontal = horizontal,
                                reveal = { find.pendingReveal }, onRevealed = find::revealed,
                            ) { index -> document.row(index, spans, find.matches, find.current, matchColor, currentMatchColor) }
                        }
                        current == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        else -> {
                            val highlighting = if (code) rememberCodeHighlighting(current, language) else VisualTransformation.None
                            RevealEffect({ find.pendingReveal }) { target ->
                                // After recreation the request can arrive before the first layout.
                                val layout = snapshotFlow { fieldLayout }.filterNotNull().first()
                                val offset = target.offset.coerceIn(0, layout.layoutInput.text.length)
                                val line = layout.getLineForOffset(offset)
                                val top = layout.getLineTop(line)
                                if (top < vertical.value || layout.getLineBottom(line) > vertical.value + vertical.viewportSize) {
                                    vertical.scrollTo((top - vertical.viewportSize / 4f).roundToInt())
                                }
                                if (!wrap) {
                                    val x = layout.getHorizontalPosition(offset, true)
                                    val margin = with(density) { 48.dp.toPx() }
                                    if (x < horizontal.value + margin || x > horizontal.value + horizontal.viewportSize - margin) {
                                        horizontal.scrollTo((x - horizontal.viewportSize / 3f).roundToInt())
                                    }
                                }
                                find.revealed(target)
                            }
                            BasicTextField(
                                value = current,
                                onValueChange = { session.text = it },
                                readOnly = !session.editable || saving,
                                textStyle = style,
                                visualTransformation = highlighting,
                                onTextLayout = { fieldLayout = it },
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxSize().padding(16.dp).testTag("editorText")
                                    // Drawn behind the text rather than added as spans, which would lay the whole field out again on every step.
                                    // Outside the scrolled layer: reading the offsets inside it re-records the whole text on every scrolled frame.
                                    .drawBehind {
                                        val left = if (wrap) null else
                                            if (rtl) horizontal.maxValue - horizontal.value else horizontal.value
                                        clipRect {
                                            translate(-(left ?: 0).toFloat(), -vertical.value.toFloat()) {
                                                drawMatches(fieldLayout, find.matches?.takeIf { it.source === session.text },
                                                    find.current, vertical.value, vertical.viewportSize,
                                                    left, horizontal.viewportSize, matchColor, currentMatchColor)
                                            }
                                        }
                                    }
                                    .fastTwoDimensionalScroll(vertical, if (wrap) null else horizontal)
                                    .then(if (wrap) Modifier else Modifier.width(4000.dp)),
                            )
                        }
                    }
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime.union(WindowInsets.navigationBars)))
            }
        }
        if (confirmExit) {
            AlertDialog(
                onDismissRequest = { if (!saving) confirmExit = false },
                title = { Text("Save changes?") },
                text = {
                    Column {
                        Text(session.title)
                        saveError?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { session.save(true) }, enabled = !saving) { Text(if (saving) "Saving…" else "Save") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { confirmExit = false; onDismiss() }, enabled = !saving) { Text("Discard") }
                        TextButton(onClick = { confirmExit = false }, enabled = !saving) { Text("Cancel") }
                    }
                },
            )
        }
    }
}

@Composable
private fun FindRow(find: TextFinder, anchor: () -> Int) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = find.query,
                    onValueChange = { find.setQuery(it, anchor()) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { find.next() }),
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp).focusRequester(focus).testTag("findField"),
                    decorationBox = { field ->
                        if (find.query.isEmpty()) {
                            Text("Find", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        field()
                    },
                )
                val matches = find.matches
                Text(when {
                    find.searching -> "…"
                    matches == null -> ""
                    else -> "${find.current + 1} of ${matches.size}${if (matches.capped) "+" else ""}"
                }, Modifier.padding(horizontal = 8.dp).testTag("findCount"),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val steps = !find.searching && matches != null && matches.size > 0
                ToolIcon(Icons.Outlined.KeyboardArrowUp, "Previous match", enabled = steps, onClick = find::previous)
                ToolIcon(Icons.Outlined.KeyboardArrowDown, "Next match", enabled = steps, onClick = find::next)
                ToolIcon(Icons.Outlined.Close, "Close find", onClick = find::hide)
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = find.caseSensitive, onClick = { find.setCaseSensitive(!find.caseSensitive, anchor()) },
                    label = { Text("Match case") })
                FilterChip(selected = find.regex, onClick = { find.setRegex(!find.regex, anchor()) },
                    label = { Text("Regex") })
                find.error?.let {
                    Text(it, Modifier.testTag("findError"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Paints the matches on the lines in view. [left] is null when the text wraps and nothing scrolls sideways. */
private fun DrawScope.drawMatches(
    layout: TextLayoutResult?, matches: TextMatches?, current: Int,
    top: Int, height: Int, left: Int?, width: Int, match: Color, currentMatch: Color,
) {
    if (layout == null || matches == null || matches.size == 0) return
    // The layout can be one edit behind the text the matches were found in.
    if (layout.layoutInput.text.length != matches.source.length) return
    val lastLine = layout.getLineForVerticalPosition((top + height).toFloat())
    for (line in layout.getLineForVerticalPosition(top.toFloat())..lastLine) {
        val lineStart = layout.getLineStart(line)
        val lineEnd = layout.getLineEnd(line)
        var from = lineStart
        var to = lineEnd
        if (left != null) {
            // One unwrapped line can hold thousands of matches, nearly all of them off to the side.
            val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2
            val first = layout.getOffsetForPosition(Offset(left.toFloat(), y))
            val last = layout.getOffsetForPosition(Offset((left + width).toFloat(), y))
            from = maxOf(lineStart, minOf(first, last) - 1)
            to = minOf(lineEnd, maxOf(first, last) + 1)
        }
        var index = matches.firstEndingAfter(from)
        while (index < matches.size && matches.start(index) < to) {
            val start = maxOf(matches.start(index), lineStart)
            val end = minOf(matches.end(index), lineEnd)
            if (start < end) drawPath(layout.getPathForRange(start, end), if (index == current) currentMatch else match)
            index++
        }
    }
}
