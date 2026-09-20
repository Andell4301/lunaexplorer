@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lunaexplorer.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lunaexplorer.app.storage.ReadingDocument
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.HexBlocks
import com.lunaexplorer.core.HexLayout
import com.lunaexplorer.core.HexWindow
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext

/** A file on storage that only streams is copied out first, up to what the document viewers copy. */
private const val HEX_STAGE_LIMIT = ReadingDocument.MAX_FILE_BYTES
private const val HEX_TOO_LARGE = "This file is too large to show as bytes from here."
// Keeps a thumb drag over a network from asking for every block it passes.
private const val NETWORK_SETTLE_MILLIS = 150L

private enum class HexPanel { GO_TO, FIND }

@Composable
internal fun HexViewer(entry: Entry, viewModel: BrowserViewModel, onDismiss: () -> Unit) {
    var session by remember(entry.ref) { mutableStateOf<HexSession?>(null) }
    var failure by remember(entry.ref) { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.ref) {
        var staged: File? = null
        var bytes: HexBlocks? = null
        try {
            // Assigned inside the IO block: withContext drops its result if cancelled on return, which
            // would leak the open channel or the copy.
            withContext(Dispatchers.IO) {
                val channel = viewModel.files.openChannel(entry)?.let(::sized) ?: run {
                    val copy = viewModel.files.stageForViewer(entry, HEX_STAGE_LIMIT, HEX_TOO_LARGE).getOrThrow()
                    staged = copy
                    FileChannel.open(copy.toPath(), StandardOpenOption.READ)
                }
                bytes = try { HexBlocks(channel) } catch (error: Throwable) { channel.close(); throw error }
            }
            val settle = if (staged == null && viewModel.files.overNetwork(entry)) NETWORK_SETTLE_MILLIS else 0
            session = HexSession(bytes!!, this, settle)
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error.message ?: HexSession.UNREADABLE
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { bytes?.close() }
                staged?.delete()
            }
        }
    }

    var panel by rememberSaveable { mutableStateOf<HexPanel?>(null) }
    var goToText by rememberSaveable { mutableStateOf("") }
    var goToHex by rememberSaveable { mutableStateOf(true) }
    var findText by rememberSaveable { mutableStateOf("") }
    var findHex by rememberSaveable { mutableStateOf(false) }
    val current = session
    val usable = current != null && current.size > 0
    fun open(next: HexPanel?) {
        current?.stopFinding()
        panel = next
    }
    BackHandler(enabled = panel != null && usable) { open(null) }

    Column(Modifier.fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
        ViewerBar(entry.name, onDismiss, entry, viewModel,
            subtitle = current?.let { "%,d ${if (it.size == 1L) "byte" else "bytes"}".format(it.size) },
            closeOnRename = true) {
            TextButton(onClick = { open(if (panel == HexPanel.GO_TO) null else HexPanel.GO_TO) }, enabled = usable) { Text("Go to") }
            TextButton(onClick = { open(if (panel == HexPanel.FIND) null else HexPanel.FIND) }, enabled = usable) { Text("Find") }
        }
        if (current != null && usable) {
            when (panel) {
                HexPanel.GO_TO -> HexGoToRow(current, goToText, { goToText = it }, goToHex, { goToHex = it })
                HexPanel.FIND -> HexFindRow(current, findText, { findText = it }, findHex, { findHex = it }, onClose = { open(null) })
                null -> Unit
            }
        }
        current?.finding?.let { finding ->
            LinearProgressIndicator(progress = { if (finding.total > 0) (finding.scanned.toDouble() / finding.total).toFloat() else 0f },
                modifier = Modifier.fillMaxWidth())
        }
        current?.readFailure?.let {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = current::retry) { Text("Retry") }
                }
            }
        }
        when {
            failure != null -> Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(failure!!, color = MaterialTheme.colorScheme.error)
            }
            current == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            current.size == 0L -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> HexRows(current, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** Null for a channel that says it is empty and still has bytes: it does not know its length. It is closed then. */
private fun sized(channel: SeekableByteChannel): SeekableByteChannel? {
    val known = try {
        channel.size() > 0 || channel.read(ByteBuffer.allocate(1)) <= 0
    } catch (error: Throwable) {
        channel.close()
        throw error
    }
    if (!known) channel.close()
    return channel.takeIf { known }
}

@Composable
private fun HexGoToRow(session: HexSession, text: String, onText: (String) -> Unit, hex: Boolean, onHex: (Boolean) -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun go() { error = session.goTo(text, hex) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { onText(it); error = null }, Modifier.weight(1f).focusRequester(focus).testTag("hexGoTo"),
                label = { Text("Offset") }, singleLine = true, isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }))
            FilterChip(selected = hex, onClick = { onHex(!hex); error = null }, label = { Text("Hex") })
            TextButton(onClick = ::go) { Text("Go") }
        }
    }
}

@Composable
internal fun HexFindRow(
    session: HexSession, query: String, onQuery: (String) -> Unit, hex: Boolean, onHex: (Boolean) -> Unit, onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = query,
                    onValueChange = { onQuery(it); session.forgetFindResult() },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { session.find(query, hex, forward = true) }),
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp).focusRequester(focus).testTag("hexFind"),
                    decorationBox = { field ->
                        if (query.isEmpty()) {
                            Text("Find", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        field()
                    },
                )
                Text(session.findStatus.orEmpty(), Modifier.padding(horizontal = 8.dp).testTag("hexFindStatus"),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (session.finding != null) {
                    ToolIcon(Icons.Outlined.Stop, "Stop", onClick = session::stopFinding)
                } else {
                    ToolIcon(Icons.Outlined.KeyboardArrowUp, "Previous match", enabled = query.isNotEmpty()) {
                        session.find(query, hex, forward = false)
                    }
                    ToolIcon(Icons.Outlined.KeyboardArrowDown, "Next match", enabled = query.isNotEmpty()) {
                        session.find(query, hex, forward = true)
                    }
                }
                ToolIcon(Icons.Outlined.Close, "Close find", onClick = onClose)
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = hex, onClick = { onHex(!hex); session.forgetFindResult() }, label = { Text("Hex") })
                session.findError?.let {
                    Text(it, Modifier.testTag("hexFindError"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private class HexView(val list: LazyListState, val window: HexWindow, val bytesPerRow: Int)

/** Rows are one height, so the scrollbar counts in rows: Compose's own content size is Int pixels and overflows here. */
internal fun hexScrollMetrics(first: Int, firstOffsetPx: Int, rowPx: Int, viewportPx: Int, rows: Int): FastScrollMetrics =
    if (rowPx <= 0) FastScrollMetrics(0f, 0f, 0f)
    else FastScrollMetrics(first + firstOffsetPx.toFloat() / rowPx, viewportPx.toFloat() / rowPx, rows.toFloat())

internal fun hexRowAt(fraction: Float, rows: Int, viewportRows: Float): Int =
    if (fraction >= 1f) rows - 1 else (fraction.toDouble() * (rows - viewportRows)).toInt().coerceIn(0, (rows - 1).coerceAtLeast(0))

@Composable
internal fun HexRows(session: HexSession, modifier: Modifier = Modifier) {
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurface)
    val measurer = rememberTextMeasurer()
    val cell = remember(measurer, style) { measurer.measure("0".repeat(64), style).size.width / 64f }
    val scheme = MaterialTheme.colorScheme
    val colors = remember(scheme) {
        HexColors(scheme.onSurfaceVariant, scheme.onSurfaceVariant.copy(alpha = .5f), scheme.tertiary.copy(alpha = .55f), scheme.onSurface)
    }
    // Written on every scrolled row, so it is read only where that does not recompose.
    val anchor = rememberSaveable { mutableLongStateOf(0L) }
    var windowBase by rememberSaveable { mutableLongStateOf(0L) }
    val horizontal = rememberScrollState()
    val size = session.size
    val digits = HexLayout.offsetDigits(size)
    val padding = with(LocalDensity.current) { 12.dp.toPx() }

    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val bytesPerRow = HexLayout.fit(((width - 2 * padding) / cell).toInt(), digits)
        val layout = remember(digits, bytesPerRow) { HexLayout(digits, bytesPerRow) }
        // A narrower row shortens the window in bytes, which can leave the anchor outside it.
        val window = remember(windowBase, size, bytesPerRow) {
            val kept = HexWindow.at(windowBase, size, bytesPerRow)
            val at = Snapshot.withoutReadObservation { anchor.longValue }
            if (at in kept.base until kept.base + kept.rows.toLong() * bytesPerRow) kept
            else HexWindow.around(at, size, bytesPerRow)
        }
        SideEffect { if (windowBase != window.base) windowBase = window.base }
        // A new state rather than a scroll: the row size or the window changed, and the old index means another offset.
        val list = remember(bytesPerRow, window.base) {
            val row = (Snapshot.withoutReadObservation { anchor.longValue } - window.base) / bytesPerRow
            LazyListState(row.coerceIn(0, (window.rows - 1L).coerceAtLeast(0)).toInt())
        }
        val view by rememberUpdatedState(HexView(list, window, bytesPerRow))

        LaunchedEffect(list) {
            snapshotFlow { list.firstVisibleItemIndex }.collect { anchor.longValue = window.base + it.toLong() * bytesPerRow }
        }
        LaunchedEffect(list, session) {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.let { if (it.isEmpty()) null else it.first().index to it.last().index } }
                .filterNotNull()
                .collect { (first, last) -> session.show(window.base + first.toLong() * bytesPerRow, window.base + (last + 1L) * bytesPerRow) }
        }
        LaunchedEffect(session) {
            val reveals = this
            session.reveals.collect { offset ->
                val shown = view
                val row = offset / shown.bytesPerRow - shown.window.base / shown.bytesPerRow
                val info = shown.list.layoutInfo
                val rowPx = info.visibleItemsInfo.firstOrNull()?.size ?: 0
                // Rows to leave above the target, kept short of the view so the target itself is on screen.
                val lead = if (rowPx <= 0) 0 else minOf(2, info.viewportSize.height / rowPx - 1).coerceAtLeast(0)
                if (row < 0 || row >= shown.window.rows) {
                    val next = HexWindow.around(offset, session.size, shown.bytesPerRow)
                    // Highest priority: a plain stopScroll throws into this collector while the user drags.
                    shown.list.stopScroll(MutatePriority.PreventUserInput)
                    anchor.longValue = maxOf(next.base, offset - offset % shown.bytesPerRow - lead.toLong() * shown.bytesPerRow)
                    windowBase = next.base
                } else {
                    val item = info.visibleItemsInfo.firstOrNull { it.index == row.toInt() }
                    // Never animated: the distance is Int pixels and overflows.
                    if (item == null || item.offset < info.viewportStartOffset || item.offset + item.size > info.viewportEndOffset) {
                        // Losing the scroll to the user's own drag throws; in a child that ends the scroll, not this collector.
                        reveals.launch { shown.list.scrollToItem(maxOf(0, row.toInt() - lead)) }
                    }
                }
            }
        }

        fun move(to: HexWindow) {
            anchor.longValue = anchor.longValue.coerceIn(to.base, to.base + (to.rows - 1L).coerceAtLeast(0) * bytesPerRow)
            windowBase = to.base
        }

        Column(Modifier.fillMaxSize()) {
            val end = window.base + window.rows.toLong() * bytesPerRow
            if (window.base > 0 || end < size) {
                val earlier = window.earlier(size, bytesPerRow)
                val later = window.later(size, bytesPerRow)
                fun label(offset: Long) = java.lang.Long.toHexString(offset).uppercase().padStart(digits, '0')
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${label(window.base)}–${label(minOf(end, size) - 1)}", Modifier.weight(1f).testTag("hexWindow"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { earlier?.let(::move) }, enabled = earlier != null) { Text("Earlier") }
                    TextButton(onClick = { later?.let(::move) }, enabled = later != null) { Text("Later") }
                }
            }
            val wide = cell * layout.columns + 2 * padding > width
            Box(Modifier.weight(1f).fillMaxWidth().fastScrollbar(metrics = {
                val info = list.layoutInfo
                hexScrollMetrics(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset,
                    info.visibleItemsInfo.firstOrNull()?.size ?: 0, info.viewportSize.height, window.rows)
            }, onScrollToFraction = { fraction ->
                val info = list.layoutInfo
                val rowPx = info.visibleItemsInfo.firstOrNull()?.size ?: 0
                if (rowPx > 0) list.requestScrollToItem(hexRowAt(fraction, window.rows, info.viewportSize.height.toFloat() / rowPx))
            })) {
                LazyColumn(state = list,
                    modifier = Modifier.fillMaxHeight()
                        .then(if (wide) Modifier.fastHorizontalScroll(horizontal)
                            .width(with(LocalDensity.current) { (cell * layout.columns + 2 * padding).toDp() })
                            else Modifier.fillMaxWidth())
                        .testTag("hexRows"),
                    contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(window.rows, contentType = { 0 }) { index ->
                        HexRow(session, window.base + index.toLong() * bytesPerRow, layout, colors, style)
                    }
                }
            }
        }
    }
}

@Composable
private fun HexRow(session: HexSession, offset: Long, layout: HexLayout, colors: HexColors, style: TextStyle) {
    val tick = session.tick
    val mark = session.mark
    val text = remember(offset, layout, tick, mark, colors) { session.rowText(offset, layout, colors) }
    Text(text, Modifier.testTag("hexRow"), style = style, softWrap = false, maxLines = 1)
}
