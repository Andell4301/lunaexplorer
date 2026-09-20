package com.lunaexplorer.core

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SearchTest {
    private val root = NodeRef("test", "root")
    private fun folder(key: String, hidden: Boolean = false) = Entry(NodeRef("test", key), key, true,
        capabilities = setOf(Capability.LIST), hidden = hidden)
    private fun file(key: String, size: Long? = 20, modified: Long? = 500) = Entry(NodeRef("test", key), key, false, size, modified)

    @Test fun `recursive filters retain matching files and skip hidden subtrees`() = runTest {
        val provider = SearchProvider(mapOf(root to listOf(folder("docs"), folder(".private", true), file("other.txt")),
            NodeRef("test", "docs") to listOf(file("REPORT.txt"), file("report-small.txt", 2), file("report-unknown.txt", null)),
            NodeRef("test", ".private") to listOf(file("report-secret.txt"))))
        val events = SearchEngine(ProviderRegistry(listOf(provider))).search(root,
            SearchFilter("report", SearchType.FILES, minSize = 10, maxSize = 30, modifiedAfter = 400, modifiedBefore = 600)).toList()
        assertEquals(listOf("REPORT.txt"), events.filterIsInstance<SearchEvent.Batch>().flatMap { it.entries }.map { it.name })
        assertFalse(provider.listed.contains(NodeRef("test", ".private")))
        assertEquals(SearchEvent.Complete(6, 1, false), events.last())
    }

    @Test fun `unavailable subtree reports issue and continues searching siblings`() = runTest {
        val bad = NodeRef("test", "bad")
        val provider = SearchProvider(mapOf(root to listOf(folder("bad"), folder("good")),
            NodeRef("test", "good") to listOf(file("found.txt"))), denied = bad)
        val events = SearchEngine(ProviderRegistry(listOf(provider))).search(root, SearchFilter(type = SearchType.FILES)).toList()
        assertEquals(StorageError.PERMISSION, events.filterIsInstance<SearchEvent.Issue>().single().reason)
        assertEquals("found.txt", events.filterIsInstance<SearchEvent.Batch>().flatMap { it.entries }.single().name)
        assertFalse((events.last() as SearchEvent.Complete).truncated)
    }

    @Test fun `result cap explicitly reports truncation and stops traversal`() = runTest {
        val provider = SearchProvider(mapOf(root to (1..100).map { file("$it.txt") }))
        val events = SearchEngine(ProviderRegistry(listOf(provider))).search(root, SearchFilter(maxResults = 3)).toList()
        assertEquals(3, events.filterIsInstance<SearchEvent.Batch>().sumOf { it.entries.size })
        assertEquals(SearchEvent.Complete(3, 3, true), events.last())
    }

    @Test fun `cancellation closes an in-flight provider listing without false completion`() = runTest {
        val started = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val provider = object : SearchProvider(emptyMap()) {
            override fun list(parent: NodeRef, complete: Boolean) = flow<List<Entry>> {
                started.complete(Unit)
                try { awaitCancellation() } finally { closed.complete(Unit) }
            }
        }
        val events = mutableListOf<SearchEvent>()
        val job = launch { SearchEngine(ProviderRegistry(listOf(provider))).search(root, SearchFilter()).toList(events) }
        started.await()
        job.cancelAndJoin()
        closed.await()
        assertFalse(events.any { it is SearchEvent.Complete })
    }

    @Test fun `provider cycles terminate and unsupported folders are reported`() = runTest {
        val provider = SearchProvider(mapOf(root to listOf(folder("root"), Entry(NodeRef("test", "virtual"), "virtual", true))))
        val events = SearchEngine(ProviderRegistry(listOf(provider))).search(root, SearchFilter()).toList()
        assertEquals(1, provider.listed.size)
        assertEquals(StorageError.UNSUPPORTED, events.filterIsInstance<SearchEvent.Issue>().single().reason)
        assertTrue(events.last() is SearchEvent.Complete)
    }

    private open class SearchProvider(private val content: Map<NodeRef, List<Entry>>, private val denied: NodeRef? = null) : StorageProvider {
        override val id = "test"
        val listed = mutableListOf<NodeRef>()
        override fun list(parent: NodeRef, complete: Boolean): Flow<List<Entry>> = flow {
            listed.add(parent)
            if (parent == denied) throw StorageException(StorageError.PERMISSION, "Revoked grant")
            emit(content[parent].orEmpty())
        }
        override suspend fun stat(ref: NodeRef): Entry = error("Not used")
        override suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String): Entry = error("Not used")
        override suspend fun rename(ref: NodeRef, name: String): Entry = error("Not used")
        override suspend fun delete(ref: NodeRef): Unit = error("Not used")
        override suspend fun openRead(ref: NodeRef): InputStream = error("Not used")
        override suspend fun openWrite(ref: NodeRef): OutputStream = error("Not used")
        override suspend fun isDescendant(candidate: NodeRef, ancestor: NodeRef): Boolean = error("Not used")
        override suspend fun commit(staged: NodeRef, parent: NodeRef, name: String, replace: Entry?, onRetained: RetainedObjects?): Entry = error("Not used")
    }

    private fun readable(key: String) = file(key).copy(capabilities = setOf(Capability.READ))

    private class TextProvider(content: Map<NodeRef, List<Entry>>, private val bytes: Map<String, ByteArray>,
        override val features: Set<Feature> = emptySet()) : SearchProvider(content) {
        val opened = mutableListOf<String>()
        override suspend fun openRead(ref: NodeRef): InputStream {
            opened += ref.key
            return (bytes[ref.key] ?: throw StorageException(StorageError.PERMISSION, "Not yours to read")).inputStream()
        }
    }

    private suspend fun found(provider: StorageProvider, filter: SearchFilter) =
        SearchEngine(ProviderRegistry(listOf(provider))).search(root, filter).toList()

    private fun names(events: List<SearchEvent>) = events.filterIsInstance<SearchEvent.Batch>().flatMap { it.entries }.map { it.name }

    @Test fun `text is found inside files, whatever its case, and only files the rest of the filter lets through are opened`() = runTest {
        val provider = TextProvider(
            mapOf(root to listOf(readable("notes.txt"), readable("todo.md"), readable("other.txt"), folder("docs"))),
            mapOf("notes.txt" to "Buy milk\nCall the Bank".toByteArray(), "todo.md" to "bank holiday".toByteArray(),
                "other.txt" to "nothing here".toByteArray()))

        assertEquals(listOf("notes.txt", "todo.md"), names(found(provider, SearchFilter(text = "bank"))))
        assertEquals(listOf("notes.txt"), names(found(provider, SearchFilter(text = "Bank", caseSensitive = true))))
        provider.opened.clear()
        assertEquals(listOf("todo.md"), names(found(provider, SearchFilter(query = ".md", text = "bank"))))
        assertEquals("A name that does not match is never read", listOf("todo.md"), provider.opened)
    }

    @Test fun `text that straddles two blocks of a large file is still found`() = runTest {
        val before = "x".repeat(64 * 1024 - 3)
        val provider = TextProvider(mapOf(root to listOf(readable("big.log"))), mapOf("big.log" to (before + "needle" + "y".repeat(1000)).toByteArray()))

        assertEquals(listOf("big.log"), names(found(provider, SearchFilter(text = "needle"))))
    }

    @Test fun `a pattern is matched a line at a time, and a binary file is not text`() = runTest {
        val provider = TextProvider(
            mapOf(root to listOf(readable("app.log"), readable("split.log"), readable("photo.jpg"))),
            mapOf("app.log" to "INFO start\nERROR code 42\n".toByteArray(), "split.log" to "ERROR\ncode 42".toByteArray(),
                "photo.jpg" to byteArrayOf(-1, -40, 0, 16) + "ERROR code 42".toByteArray()))

        assertEquals(listOf("app.log"), names(found(provider, SearchFilter(text = "^ERROR code \\d+$", regex = true))))
    }

    @Test fun `a file that cannot be read is reported and the search goes on`() = runTest {
        val provider = TextProvider(mapOf(root to listOf(readable("locked.txt"), readable("open.txt"))), mapOf("open.txt" to "the word".toByteArray()))

        val events = found(provider, SearchFilter(text = "word"))

        assertEquals(listOf("open.txt"), names(events))
        assertEquals(StorageError.PERMISSION, events.filterIsInstance<SearchEvent.Issue>().single().reason)
    }

    @Test fun `text is not searched across a network, where every file would have to come down it`() = runTest {
        val provider = TextProvider(mapOf(root to listOf(readable("a.txt"))), mapOf("a.txt" to "word".toByteArray()), setOf(Feature.NETWORK))

        val events = found(provider, SearchFilter(text = "word"))

        assertEquals(emptyList<String>(), provider.opened)
        assertEquals(StorageError.UNSUPPORTED, events.filterIsInstance<SearchEvent.Issue>().single().reason)
        assertEquals(SearchEvent.Complete(0, 0, false), events.last())
    }

    @Test fun `a provider that can list a whole tree at once is asked for that, not walked`() = runTest {
        class Deep : SearchProvider(emptyMap()), DeepListing {
            var hiddenAsked: Boolean? = null
            override fun listDeep(root: NodeRef, hidden: Boolean): Flow<List<Entry>> = flow {
                hiddenAsked = hidden
                emit(listOf(folder("docs"), file("docs/report.txt")))
                emit(listOf(file("report-old.txt"), file("notes.txt")))
            }
        }
        val provider = Deep()

        val events = found(provider, SearchFilter("report", SearchType.FILES))

        assertEquals(listOf("docs/report.txt", "report-old.txt"), names(events))
        assertEquals(emptyList<NodeRef>(), provider.listed)
        assertEquals(false, provider.hiddenAsked)
        assertEquals(SearchEvent.Complete(4, 2, false), events.last())
    }

    @Test fun `a regex pattern matches by expression rather than by substring`() {
        val filter = SearchFilter(query = "report-20(24|25)\\.pdf", regex = true)
        assertTrue(filter.matches(file("report-2024.pdf")))
        assertTrue(filter.matches(file("report-2025.pdf")))
        assertFalse(filter.matches(file("report-2026.pdf")))
        assertFalse("A plain substring must not slip through as a pattern", filter.matches(file("notes.txt")))
    }

    @Test fun `a regex search ignores case unless asked not to`() {
        assertTrue(SearchFilter(query = "^readme", regex = true).matches(file("README.md")))
        assertFalse(SearchFilter(query = "^readme", regex = true, caseSensitive = true).matches(file("README.md")))
        assertTrue(SearchFilter(query = "^readme", regex = true, caseSensitive = true).matches(file("readme.txt")))
    }

    @Test fun `a plain search honours case sensitivity too`() {
        assertTrue(SearchFilter(query = "alpha").matches(file("Alpha.txt")))
        assertFalse(SearchFilter(query = "alpha", caseSensitive = true).matches(file("Alpha.txt")))
    }

    @Test fun `an empty query matches everything either way`() {
        assertTrue(SearchFilter(query = "").matches(file("anything.txt")))
        assertTrue(SearchFilter(query = "", regex = true).matches(file("anything.txt")))
    }

    @Test fun `an unusable pattern is refused when the search is built`() {
        val error = assertThrows(StorageException::class.java) {
            SearchFilter(query = "report-20(24", regex = true)
        }
        assertEquals(StorageError.INVALID_NAME, error.reason)
    }
}
