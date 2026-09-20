package com.lunaexplorer.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.lunaexplorer.core.HEX_BLOCK
import com.lunaexplorer.core.HexBlocks
import com.lunaexplorer.core.HexLayout
import com.lunaexplorer.core.HexPattern
import com.lunaexplorer.core.HexSearch
import com.lunaexplorer.core.HexStep
import com.lunaexplorer.core.parseHexPattern
import com.lunaexplorer.core.parseOffset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class HexMark(val start: Long, val length: Int)
internal data class HexFinding(val scanned: Long, val total: Long)
internal class HexColors(val offset: Color, val dim: Color, val markBackground: Color, val markText: Color)

/** What one open hex viewer shows. Main thread only; the blocking reads run on [io]. [scope] ends before [bytes] closes. */
internal class HexSession(
    private val bytes: HexBlocks,
    private val scope: CoroutineScope,
    private val settleMillis: Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class Wanted(val visible: LongRange, val around: LongRange, val attempt: Int)
    private class Cursor(val at: Long, val next: Long, val previous: Long)

    var size by mutableLongStateOf(bytes.size)
        private set
    /** Rows are rebuilt when this changes: a block arrived or failed, or the view moved to other blocks. */
    var tick by mutableIntStateOf(0)
        private set
    var readFailure by mutableStateOf<String?>(null)
        private set
    var mark by mutableStateOf<HexMark?>(null)
        private set
    var finding by mutableStateOf<HexFinding?>(null)
        private set
    var findStatus by mutableStateOf<String?>(null)
        private set
    var findError by mutableStateOf<String?>(null)
        private set

    private val _reveals = MutableSharedFlow<Long>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val reveals: SharedFlow<Long> = _reveals

    private val wanted = MutableStateFlow<Wanted?>(null)
    private val failed = HashSet<Long>()
    private var visibleFrom = 0L
    // Where the next search carries on from. Apart from the mark: after Go to, a match at the offset itself counts.
    private var cursor: Cursor? = null
    private var cursorShown = false
    private var findJob: Job? = null

    init {
        scope.launch {
            wanted.collectLatest { want ->
                if (want == null) return@collectLatest
                // A load that was overtaken still lands in the cache, and nothing else says so.
                tick++
                val missing = (want.visible + (want.around - want.visible)).filter { it !in failed && !bytes.has(it) }
                if (missing.isEmpty()) return@collectLatest
                delay(settleMillis)
                for (block in missing) {
                    try {
                        withContext(io) { bytes.load(block) }
                    } catch (error: Exception) {
                        // A read that fails while it is overtaken reports the original error, not cancellation.
                        currentCoroutineContext().ensureActive()
                        failed += block
                        readFailure = error.message ?: UNREADABLE
                    }
                    size = bytes.size
                    tick++
                }
            }
        }
    }

    /** The offsets on screen. Their blocks are read, and one more on each side. */
    fun show(from: Long, until: Long) {
        if (until <= from) return
        visibleFrom = from
        // While the match is off screen the next search starts at the top of the view instead.
        cursor?.let { cursorShown = it.at in from until until }
        val end = ((size - 1) / bytes.blockSize).coerceAtLeast(0)
        val first = (from / bytes.blockSize).coerceAtMost(end)
        val last = ((until - 1) / bytes.blockSize).coerceAtMost(end)
        wanted.value = Wanted(first..last, (first - 1).coerceAtLeast(0)..(last + 1).coerceAtMost(end), wanted.value?.attempt ?: 0)
    }

    fun rowText(offset: Long, layout: HexLayout, colors: HexColors): AnnotatedString {
        val count = minOf(layout.bytesPerRow.toLong(), size - offset).coerceAtLeast(0).toInt()
        val row = ByteArray(layout.bytesPerRow)
        val got = bytes.peek(offset, row, count)
        val text = if (got < 0) layout.unread(offset, count, failed = offset / bytes.blockSize in failed) else layout.row(offset, row, got)
        val builder = AnnotatedString.Builder(text)
        builder.addStyle(SpanStyle(color = colors.offset), 0, layout.offsetDigits)
        if (got < 0) {
            builder.addStyle(SpanStyle(color = colors.dim), layout.offsetDigits, text.length)
            return builder.toAnnotatedString()
        }
        val marked = mark ?: return builder.toAnnotatedString()
        val from = maxOf(marked.start, offset) - offset
        val until = minOf(marked.start + marked.length, offset + got) - offset
        if (from < until) {
            val style = SpanStyle(background = colors.markBackground, color = colors.markText)
            builder.addStyle(style, layout.hexRange(from.toInt()).first, layout.hexRange(until.toInt() - 1).last + 1)
            builder.addStyle(style, layout.asciiAt(from.toInt()), layout.asciiAt(until.toInt() - 1) + 1)
        }
        return builder.toAnnotatedString()
    }

    fun retry() {
        failed.clear()
        readFailure = null
        wanted.value = wanted.value?.let { it.copy(attempt = it.attempt + 1) }
    }

    fun goTo(text: String, hex: Boolean): String? {
        stopFinding()
        val offset = parseOffset(text, hex) ?: return "Not an offset"
        if (offset >= size) return "Past the end"
        mark = HexMark(offset, 1)
        cursor = Cursor(offset, next = offset, previous = offset - 1)
        cursorShown = true
        findStatus = null
        _reveals.tryEmit(offset)
        return null
    }

    /** [query] is bytes as hex pairs when [hex], otherwise text matched as its UTF-8 bytes, exactly as typed. */
    fun forgetFindResult() {
        findStatus = null
        findError = null
    }

    fun find(query: String, hex: Boolean, forward: Boolean) {
        stopFinding()
        forgetFindResult()
        val pattern = if (hex) when (val parsed = parseHexPattern(query)) {
            is HexPattern.Bytes -> parsed.bytes
            HexPattern.Invalid.NOT_HEX -> { findError = "Not hex"; return }
            HexPattern.Invalid.ODD -> { findError = "Odd number of digits"; return }
        } else query.toByteArray(Charsets.UTF_8)
        if (pattern.isEmpty()) return
        if (pattern.size > HEX_BLOCK) { findError = "Pattern too long"; return }
        val from = cursor?.takeIf { cursorShown }?.let { if (forward) it.next else it.previous }
            ?: if (forward) visibleFrom else visibleFrom - 1
        finding = HexFinding(0, size)
        // Lazy, so the job is the current one before its first line runs.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val search = HexSearch(bytes, pattern, from, forward)
                while (true) {
                    when (val step = withContext(io) { search.step() }) {
                        is HexStep.Found -> {
                            mark = HexMark(step.offset, pattern.size)
                            cursor = Cursor(step.offset, next = step.offset + 1, previous = step.offset - 1)
                            cursorShown = true
                            if (step.wrapped) findStatus = "wrapped"
                            _reveals.tryEmit(step.offset)
                            break
                        }
                        is HexStep.More -> finding = HexFinding(step.scanned, step.total)
                        HexStep.None -> { findStatus = "Not found"; break }
                    }
                }
            } catch (error: Exception) {
                ensureActive()
                findError = error.message ?: UNREADABLE
            } finally {
                // One that was replaced ends after its successor has begun.
                if (findJob === coroutineContext[Job]) {
                    finding = null
                    size = bytes.size
                }
            }
        }
        findJob = job
        job.start()
    }

    fun stopFinding() {
        findJob?.cancel()
        findJob = null
        finding = null
    }

    companion object {
        const val UNREADABLE = "This file could not be read"
    }
}
