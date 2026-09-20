package com.lunaexplorer.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextFindTest {
    private fun ranges(matches: TextMatches) = (0 until matches.size).map { matches.start(it) to matches.end(it) }

    private fun literal(text: String, query: String, caseSensitive: Boolean = false, limit: Int = MAX_FIND_MATCHES) =
        findMatches(text, query, caseSensitive, null, limit)

    private fun pattern(text: String, query: String, caseSensitive: Boolean = false, limit: Int = MAX_FIND_MATCHES) =
        findMatches(text, query, caseSensitive, findPattern(query, caseSensitive), limit)

    @Test fun `literal matches come in order without overlapping and ignore case unless asked`() {
        assertEquals(listOf(0 to 2, 2 to 4), ranges(literal("aaaaa", "aa")))
        assertEquals(listOf(0 to 2, 2 to 4), ranges(literal("aaaaa", "aa", caseSensitive = true)))
        assertEquals(listOf(0 to 3, 4 to 7), ranges(literal("Abc aBC", "abc")))
        assertEquals(listOf(4 to 7), ranges(literal("Abc abc", "abc", caseSensitive = true)))
        assertEquals(listOf(1 to 4), ranges(literal("a \nb", " \nb")))
        assertEquals(listOf(0 to 2), ranges(literal("a.c", "a.")))
        assertEquals(0, literal("abc", "abcd").size)
    }

    @Test fun `a regex finds matches of differing length, anchors per line and skips empty matches`() {
        assertEquals(listOf(0 to 1, 2 to 5), ranges(pattern("a bbb", "[ab]+")))
        assertEquals(listOf(0 to 3, 9 to 12), ranges(pattern("one two\r\none", "^one")))
        assertEquals(listOf(4 to 7), ranges(pattern("one two\r\none", "two$")))
        assertEquals(listOf(0 to 1, 3 to 4), ranges(pattern("a  a", "a*")))
        assertEquals(listOf(0 to 1, 2 to 3), ranges(pattern("A a", "a")))
        assertEquals(listOf(2 to 3), ranges(pattern("A a", "a", caseSensitive = true)))
    }

    @Test fun `a pattern never matches a line break`() {
        val text = "a\r\nb\n\nc\rd"
        assertEquals(0, pattern(text, "\\s").size)
        assertEquals(listOf(0 to 1, 3 to 4, 6 to 7, 8 to 9), ranges(pattern(text, "[^x]+")))
    }

    @Test fun `the cap stops the scan and says more exist`() {
        val three = literal("x x x", "x", limit = 3)
        assertEquals(3, three.size)
        assertFalse(three.capped)
        val four = literal("x x x x", "x", limit = 3)
        assertEquals(listOf(0 to 1, 2 to 3, 4 to 5), ranges(four))
        assertTrue(four.capped)
        val lines = pattern("x\nx\nx\nx\nx", "x", limit = 3)
        assertEquals(listOf(0 to 1, 2 to 3, 4 to 5), ranges(lines))
        assertTrue(lines.capped)
    }

    @Test fun `a cancelled scan throws instead of finishing`() {
        val cancelled = { throw CancellationException("cancelled") }
        assertThrows(CancellationException::class.java) { findMatches("a\na\n", "a", false, null, ensureActive = cancelled) }
        assertThrows(CancellationException::class.java) { findMatches("a\na\n", "a", true, null, ensureActive = cancelled) }
        assertThrows(CancellationException::class.java) {
            findMatches("a\na\n", "a", false, findPattern("a", false), ensureActive = cancelled)
        }
    }

    @Test fun `the match at or after the view top becomes current and wraps to the first`() {
        val matches = literal("ab ab ab", "ab")
        assertEquals(0, matches.indexAtOrAfter(0))
        assertEquals(1, matches.indexAtOrAfter(1))
        assertEquals(1, matches.indexAtOrAfter(3))
        assertEquals(0, matches.indexAtOrAfter(7))
        assertEquals(-1, literal("ab", "x").indexAtOrAfter(0))
        assertEquals(0, matches.firstEndingAfter(1))
        assertEquals(1, matches.firstEndingAfter(2))
        assertEquals(3, matches.firstEndingAfter(8))
    }

    @Test fun `an invalid pattern is reported and leaves no matches`() = runTest {
        val finder = TextFinder(this, StandardTestDispatcher(testScheduler)) { "one (two)" }
        finder.show(0)
        finder.setQuery("(", 0)
        advanceUntilIdle()
        assertEquals(1, finder.matches?.size)
        finder.setRegex(true, 0)
        assertNotNull(finder.error)
        assertNull(finder.matches)
        assertEquals(-1, finder.current)
        assertFalse(finder.searching)
        finder.setRegex(false, 0)
        advanceUntilIdle()
        assertNull(finder.error)
        assertEquals(1, finder.matches?.size)
    }

    @Test fun `a newer query cancels the older search`() = runTest {
        val finder = TextFinder(this, StandardTestDispatcher(testScheduler)) { "aaa bbb" }
        finder.show(0)
        finder.setQuery("aaa", 0)
        advanceTimeBy(100)
        finder.setQuery("b", 0)
        advanceTimeBy(100)
        assertNull("The first search would have finished by now", finder.matches)
        assertTrue(finder.searching)
        advanceUntilIdle()
        assertEquals(3, finder.matches?.size)
        assertFalse(finder.searching)
    }

    @Test fun `an edit re-runs the search near the old match without asking for a scroll`() = runTest {
        var text = "needle one\nneedle two\nneedle three"
        val finder = TextFinder(this, StandardTestDispatcher(testScheduler)) { text }
        finder.show(0)
        finder.setQuery("needle", 0)
        advanceUntilIdle()
        assertEquals(0, finder.current)
        assertEquals(0, finder.pendingReveal?.offset)
        finder.next()
        assertEquals(1, finder.current)
        val reveal = requireNotNull(finder.pendingReveal)
        assertEquals(11, reveal.offset)
        finder.revealed(reveal)
        assertNull(finder.pendingReveal)

        text = "a needle one\nneedle two\nneedle three"
        finder.sourceChanged()
        finder.next()
        assertEquals("Stale matches cannot be stepped through", 1, finder.current)
        advanceUntilIdle()
        assertSame(text, finder.matches?.source)
        assertEquals(1, finder.current)
        assertNull(finder.pendingReveal)
    }

    @Test fun `deleting text above the current match keeps that match current`() = runTest {
        var text = "needle one\nneedle two\nneedle three"
        val finder = TextFinder(this, StandardTestDispatcher(testScheduler)) { text }
        finder.show(0)
        finder.setQuery("needle", 0)
        advanceUntilIdle()
        finder.next()
        assertEquals(1, finder.current)

        text = "needle ne\nneedle two\nneedle three"
        finder.sourceChanged()
        advanceUntilIdle()
        assertEquals(1, finder.current)
        assertEquals(10, finder.matches?.start(1))

        finder.next()
        assertEquals(2, finder.current)
        text = "needle ne\nneedle tw\nneedle three"
        finder.sourceChanged()
        advanceUntilIdle()
        assertEquals("The last match must not wrap to the first", 2, finder.current)
    }

    @Test fun `a query typed while the text is still loading is scrolled to once it arrives`() = runTest {
        var text: String? = null
        val finder = TextFinder(this, StandardTestDispatcher(testScheduler)) { text }
        finder.show(0)
        finder.setQuery("needle", 0)
        advanceUntilIdle()
        assertNull(finder.matches)

        text = "one\ntwo\nneedle"
        finder.sourceLoaded()
        advanceUntilIdle()
        assertEquals(0, finder.current)
        assertEquals(8, finder.pendingReveal?.offset)
    }

    @Test fun `an edit before the first search lands keeps the scroll it asked for`() = runTest {
        var text = "needle"
        val finder = TextFinder(this, StandardTestDispatcher(testScheduler)) { text }
        finder.show(0)
        finder.setQuery("needle", 0)
        advanceTimeBy(100)
        text = "a\nneedle"
        finder.sourceChanged()
        advanceUntilIdle()
        assertEquals(2, finder.pendingReveal?.offset)
    }

    @Test fun `closing keeps the query and reopening searches from the view top`() = runTest {
        val finder = TextFinder(this, StandardTestDispatcher(testScheduler)) { "needle\nneedle" }
        finder.show(0)
        finder.setQuery("needle", 0)
        advanceUntilIdle()
        finder.hide()
        assertFalse(finder.open)
        assertNull(finder.matches)
        assertNull(finder.pendingReveal)
        assertEquals("needle", finder.query)
        finder.show(3)
        advanceUntilIdle()
        assertEquals(1, finder.current)
        assertEquals(7, finder.pendingReveal?.offset)
    }
}
