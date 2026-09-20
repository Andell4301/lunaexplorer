package com.lunaexplorer.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.PatternSyntaxException

internal const val MAX_FIND_MATCHES = 10_000

/** Ordered, non-overlapping match ranges over [source]. [capped] means the scan stopped at the limit. */
internal class TextMatches(val source: String, private val starts: IntArray, private val ends: IntArray, val capped: Boolean) {
    val size: Int get() = starts.size
    fun start(index: Int): Int = starts[index]
    fun end(index: Int): Int = ends[index]

    fun firstEndingAfter(offset: Int): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ends[middle] <= offset) low = middle + 1 else high = middle
        }
        return low
    }

    fun indexAtOrAfter(offset: Int): Int {
        if (size == 0) return -1
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (starts[middle] < offset) low = middle + 1 else high = middle
        }
        return if (low == size) 0 else low
    }
}

internal fun findPattern(query: String, caseSensitive: Boolean): Regex =
    Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))

/** A [pattern] is matched a line at a time and never sees a line break; a literal [query] runs over the whole text as typed. */
internal fun findMatches(
    text: String,
    query: String,
    caseSensitive: Boolean,
    pattern: Regex?,
    limit: Int = MAX_FIND_MATCHES,
    ensureActive: () -> Unit = {},
): TextMatches {
    val starts = IntList()
    val ends = IntList()
    fun add(start: Int, end: Int): Boolean {
        starts.add(start)
        ends.add(end)
        return starts.size <= limit
    }
    val length = text.length
    if (pattern != null) {
        val matcher = pattern.toPattern().matcher("")
        var lineStart = 0
        lines@ while (true) {
            var lineEnd = lineStart
            while (lineEnd < length && text[lineEnd] != '\n' && text[lineEnd] != '\r') lineEnd++
            // One line per reset: a native find() cannot be interrupted, so each call gets little to chew on.
            matcher.reset(text.substring(lineStart, lineEnd))
            while (matcher.find()) {
                if (matcher.end() > matcher.start() && !add(lineStart + matcher.start(), lineStart + matcher.end())) break@lines
            }
            if (lineEnd >= length) break
            lineStart = if (text[lineEnd] == '\r' && lineEnd + 1 < length && text[lineEnd + 1] == '\n') lineEnd + 2 else lineEnd + 1
            ensureActive()
        }
    } else if (query.isNotEmpty()) {
        val size = query.length
        var from = 0
        if (caseSensitive) {
            while (true) {
                val at = text.indexOf(query, from)
                if (at < 0 || !add(at, at + size)) break
                from = at + size
                ensureActive()
            }
        } else {
            while (from + size <= length) {
                if (from and 0xFFFF == 0) ensureActive()
                if (text.regionMatches(from, query, 0, size, ignoreCase = true)) {
                    if (!add(from, from + size)) break
                    from += size
                } else {
                    from++
                }
            }
        }
    }
    val capped = starts.size > limit
    val count = minOf(starts.size, limit)
    return TextMatches(text, starts.toArray().copyOf(count), ends.toArray().copyOf(count), capped)
}

/** One request to scroll to [offset]; the view answers it once with [TextFinder.revealed]. */
internal class TextReveal(val offset: Int)

internal class TextFinder(
    private val scope: CoroutineScope,
    // Per finder: a pattern that never returns blocks only this session's later searches.
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val source: () -> String?,
) {
    var open by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var caseSensitive by mutableStateOf(false)
        private set
    var regex by mutableStateOf(false)
        private set
    var matches by mutableStateOf<TextMatches?>(null)
        private set
    var current by mutableIntStateOf(-1)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var searching by mutableStateOf(false)
        private set
    var pendingReveal by mutableStateOf<TextReveal?>(null)
        private set
    private var job: Job? = null
    private var wantsReveal = false

    fun show(anchor: Int) {
        open = true
        search(anchor, reveal = true)
    }

    fun hide() {
        open = false
        cancel()
        forget()
    }

    fun setQuery(value: String, anchor: Int) {
        query = value
        search(anchor, reveal = true)
    }

    fun setCaseSensitive(value: Boolean, anchor: Int) {
        caseSensitive = value
        search(anchor, reveal = true)
    }

    fun setRegex(value: Boolean, anchor: Int) {
        regex = value
        search(anchor, reveal = true)
    }

    fun next() = step(1)
    fun previous() = step(-1)

    fun revealed(reveal: TextReveal) {
        if (pendingReveal === reveal) pendingReveal = null
    }

    fun sourceChanged() {
        if (open) search(rebasedAnchor(), reveal = false)
    }

    fun sourceLoaded() {
        if (open) search(0, reveal = true)
    }

    private fun rebasedAnchor(): Int {
        val found = matches?.takeIf { current >= 0 } ?: return 0
        val text = source() ?: return 0
        val old = found.source
        val start = found.start(current)
        var differs = 0
        val shared = minOf(old.length, text.length)
        while (differs < shared && old[differs] == text[differs]) differs++
        if (differs >= start) return start
        return (start + text.length - old.length).coerceAtLeast(differs)
    }

    fun cancel() {
        job?.cancel()
        searching = false
    }

    private fun step(by: Int) {
        val found = matches ?: return
        if (searching || found.size == 0 || found.source !== source()) return
        current = (current + by + found.size) % found.size
        pendingReveal = TextReveal(found.start(current))
    }

    private fun forget(message: String? = null) {
        matches = null
        current = -1
        pendingReveal = null
        error = message
    }

    private fun search(anchor: Int, reveal: Boolean) {
        cancel()
        error = null
        // A scroll asked for by a search that never landed is still owed.
        wantsReveal = reveal || wantsReveal
        val text = source()
        if (!open || query.isEmpty() || text == null) { forget(); return }
        val query = query
        val caseSensitive = caseSensitive
        val pattern = if (!regex) null else try {
            findPattern(query, caseSensitive)
        } catch (invalid: PatternSyntaxException) {
            forget((invalid.description ?: "Pattern too complex").lineSequence().first())
            return
        }
        searching = true
        job = scope.launch {
            val found = withContext(dispatcher) {
                // The debounce waits here: a delay on the main looper never elapses under Robolectric.
                delay(150)
                try {
                    findMatches(text, query, caseSensitive, pattern) { ensureActive() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: StackOverflowError) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
            }
            searching = false
            if (found == null) {
                forget("Pattern too complex")
                return@launch
            }
            matches = found
            current = found.indexAtOrAfter(anchor)
            if (wantsReveal && current >= 0) pendingReveal = TextReveal(found.start(current))
            wantsReveal = false
        }
    }
}
