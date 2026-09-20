package com.lunaexplorer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import java.io.InputStream
import java.io.InputStreamReader

enum class SearchType { ANY, FILES, FOLDERS }

data class SearchFilter(
    val query: String = "",
    val type: SearchType = SearchType.ANY,
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val modifiedAfter: Long? = null,
    val modifiedBefore: Long? = null,
    val includeHidden: Boolean = false,
    val maxResults: Int = 5_000,
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    /** Text a file must hold, read as UTF-8. A pattern when [regex] is set, matched a line at a time. Only files can match. */
    val text: String = "",
) {
    /** Compiled once. An invalid pattern throws from the constructor. */
    private val pattern: Regex? = compiled(query)
    private val textPattern: Regex? = compiled(text)

    private fun compiled(source: String): Regex? = if (!regex || source.isEmpty()) null else runCatching {
        Regex(source, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
    }.getOrElse { throw StorageException(StorageError.INVALID_NAME, "That is not a valid pattern: ${it.message}") }

    init {
        require(maxResults > 0) { "Result limit must be positive" }
        require(minSize == null || minSize >= 0) { "Minimum size cannot be negative" }
        require(maxSize == null || maxSize >= 0) { "Maximum size cannot be negative" }
        require(minSize == null || maxSize == null || minSize <= maxSize) { "Minimum size must not exceed maximum size" }
        require(modifiedAfter == null || modifiedBefore == null || modifiedAfter <= modifiedBefore) { "Start date must not follow end date" }
    }

    private fun nameMatches(name: String): Boolean = when {
        query.isEmpty() -> true
        pattern != null -> pattern.containsMatchIn(name)
        else -> name.contains(query, ignoreCase = !caseSensitive)
    }

    /** Everything but [text], which takes reading the file. */
    fun matches(entry: Entry): Boolean =
        (includeHidden || !entry.hidden) && nameMatches(entry.name) &&
        (type == SearchType.ANY || (type == SearchType.FOLDERS) == entry.directory) &&
        (minSize == null || entry.size?.let { it >= minSize } == true) &&
        (maxSize == null || entry.size?.let { it <= maxSize } == true) &&
        (modifiedAfter == null || entry.modified?.let { it >= modifiedAfter } == true) &&
        (modifiedBefore == null || entry.modified?.let { it <= modifiedBefore } == true)

    /** Whether the bytes of [input] hold [text]. [stillWanted] is called between blocks and throws to give up. */
    fun textIn(input: InputStream, stillWanted: () -> Unit): Boolean =
        TextScan.holds(input, text, textPattern, ignoreCase = !caseSensitive, stillWanted)
}

/** Looks for text in a stream of any length in bounded memory. */
internal object TextScan {
    private const val BLOCK = 64 * 1024
    /** A longer line is matched in pieces of this length. */
    private const val LONGEST_LINE = 1 shl 20

    fun holds(input: InputStream, literal: String, pattern: Regex?, ignoreCase: Boolean, stillWanted: () -> Unit): Boolean {
        val stream = if (input.markSupported()) input else input.buffered(BLOCK)
        stream.mark(BINARY_PROBE)
        val head = ByteArray(BINARY_PROBE)
        var probed = 0
        while (probed < head.size) { val n = stream.read(head, probed, head.size - probed); if (n < 0) break; probed += n }
        // A NUL this early means it is not text, as grep takes it.
        for (index in 0 until probed) if (head[index] == 0.toByte()) return false
        stream.reset()
        val reader = InputStreamReader(stream, Charsets.UTF_8)
        return if (pattern == null) literalIn(reader, literal, ignoreCase, stillWanted) else lineIn(reader, pattern, stillWanted)
    }

    private fun literalIn(reader: InputStreamReader, literal: String, ignoreCase: Boolean, stillWanted: () -> Unit): Boolean {
        val block = CharArray(BLOCK)
        // The end of the last block, long enough to hold a match that straddles two.
        var carried = ""
        while (true) {
            stillWanted()
            val read = reader.read(block)
            if (read < 0) return false
            val window = carried + String(block, 0, read)
            if (window.contains(literal, ignoreCase)) return true
            carried = window.takeLast(literal.length - 1)
        }
    }

    private fun lineIn(reader: InputStreamReader, pattern: Regex, stillWanted: () -> Unit): Boolean {
        val block = CharArray(BLOCK)
        val line = StringBuilder()
        while (true) {
            stillWanted()
            val read = reader.read(block)
            if (read < 0) return line.isNotEmpty() && pattern.containsMatchIn(line)
            for (index in 0 until read) {
                val char = block[index]
                if (char == '\n' || line.length >= LONGEST_LINE) {
                    if (pattern.containsMatchIn(line)) return true
                    line.setLength(0)
                }
                if (char != '\n' && char != '\r') line.append(char)
            }
        }
    }

    private const val BINARY_PROBE = 8 * 1024
}

sealed interface SearchEvent {
    data class Batch(val entries: List<Entry>) : SearchEvent
    data class Issue(val location: NodeRef, val message: String, val reason: StorageError) : SearchEvent
    /** [truncated]: the result limit was reached, so more matches may exist. */
    data class Complete(val visited: Long, val matches: Int, val truncated: Boolean) : SearchEvent
}

/**
 * Walks provider listings; there is no index. A folder that fails is reported as a [SearchEvent.Issue]
 * and the walk continues. A provider with [DeepListing] is asked for the whole tree at once instead.
 */
class SearchEngine(private val registry: ProviderRegistry) {
    fun search(root: NodeRef, filter: SearchFilter): Flow<SearchEvent> = flow {
        val pending = ArrayDeque<NodeRef>()
        val seen = HashSet<NodeRef>()
        var batch = ArrayList<Entry>(64)
        var visited = 0L
        var matches = 0
        var truncated = false
        val provider = registry.provider(root)
        val job = currentCoroutineContext().job

        /** False too for a file that could not be read: that is reported, and the search goes on. */
        suspend fun holdsText(entry: Entry): Boolean {
            if (filter.text.isEmpty()) return true
            if (entry.directory || Capability.READ !in entry.capabilities) return false
            return try {
                registry.provider(entry.ref).openRead(entry.ref).use { input -> filter.textIn(input) { job.ensureActive() } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                emit(SearchEvent.Issue(entry.ref, error.message ?: "This file could not be read",
                    (error as? StorageException)?.reason ?: StorageError.IO))
                false
            }
        }

        suspend fun consider(entry: Entry) {
            visited++
            if (!filter.matches(entry) || !holdsText(entry)) return
            batch.add(entry)
            matches++
            if (batch.size == 64 || matches == filter.maxResults) {
                emit(SearchEvent.Batch(batch))
                batch = ArrayList(64)
            }
            if (matches == filter.maxResults) throw ResultLimitReached()
        }

        try {
            if (filter.text.isNotEmpty() && Feature.NETWORK in provider.features) {
                // Every file would have to come down the wire to be read.
                emit(SearchEvent.Issue(root, "Text inside files is searched on this device only", StorageError.UNSUPPORTED))
            } else if (provider is DeepListing) {
                try {
                    provider.listDeep(root, filter.includeHidden).collect { entries ->
                        for (entry in entries) { currentCoroutineContext().ensureActive(); consider(entry) }
                        if (batch.isNotEmpty()) { emit(SearchEvent.Batch(batch)); batch = ArrayList(64) }
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    emit(SearchEvent.Issue(root, error.message ?: "This folder could not be searched",
                        (error as? StorageException)?.reason ?: StorageError.IO))
                }
            } else {
                pending.add(root)
                while (pending.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val folder = pending.removeFirst()
                    if (!seen.add(folder)) continue
                    try {
                        registry.provider(folder).list(folder).collect { entries ->
                            currentCoroutineContext().ensureActive()
                            for (entry in entries) {
                                currentCoroutineContext().ensureActive()
                                if (!filter.includeHidden && entry.hidden) { visited++; continue }
                                // Links are not followed: the same subtree under another ref would get past the seen set.
                                if (entry.directory && !entry.link) {
                                    if (Capability.LIST in entry.capabilities) pending.add(entry.ref)
                                    else emit(SearchEvent.Issue(entry.ref, "This folder cannot be searched with the current access", StorageError.UNSUPPORTED))
                                }
                                consider(entry)
                            }
                            if (batch.isNotEmpty()) { emit(SearchEvent.Batch(batch)); batch = ArrayList(64) }
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        emit(SearchEvent.Issue(folder, error.message ?: "This folder could not be searched",
                            (error as? StorageException)?.reason ?: StorageError.IO))
                    }
                }
            }
        } catch (_: ResultLimitReached) { truncated = true }
        if (batch.isNotEmpty()) emit(SearchEvent.Batch(batch))
        emit(SearchEvent.Complete(visited, matches, truncated))
    }.flowOn(Dispatchers.IO)

    private class ResultLimitReached : CancellationException()
}
