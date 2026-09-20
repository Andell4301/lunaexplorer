package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.storage.LocalRoot
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.RetainedObjects
import com.lunaexplorer.core.StorageProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import com.lunaexplorer.app.model.*

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class SearchBackgroundTest {
    private val gate = CompletableDeferred<Unit>()
    private val storage = GatedStorage(gate)

    @get:Rule val harness = BrowserViewModelHarness()

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state
    private val names get() = state.entries.map { it.name }

    private val bothHits = listOf("hit-one.txt", "hit-two.txt")

    private fun atGated(): Entry {
        // The start folder opens some way after ready, and opening it over a search would run the search again.
        assertTrue(harness.awaitUntil { state.ready && state.view is View.Folder && !state.loading })
        harness.graph.providers.register(storage)
        viewModel.navigate(Location(listOf(Crumb(storage.root, "Gated"))))
        assertTrue("The gated storage never listed", harness.awaitUntil { showsGated() })
        return state.entries.first { it.name == "browse" }
    }

    private fun showsGated() = state.view is View.Folder && names.contains("browse")

    private fun searchHitsUntilBlocked() {
        viewModel.search("hit", "ALL", null, null, null)
        assertTrue("The first batch never arrived",
            harness.awaitUntil { state.view is View.Search && names == listOf("hit-one.txt") })
        assertTrue("The walk is held open, so it must still be running", state.searching)
    }

    private fun searchUntilBlocked(): Entry = atGated().also { searchHitsUntilBlocked() }

    private fun openBrowse() {
        viewModel.navigate(Location(listOf(Crumb(storage.browse, "browse"))))
        assertTrue(harness.awaitUntil { state.view is View.Folder && names == listOf("plain.txt") })
    }

    /** A crumb name of its own makes each step a new history entry. */
    private fun browseOnUntil(reached: (BrowserTab) -> Boolean) {
        var steps = 0
        while (!reached(state.tab!!)) {
            assertTrue("History never got there", steps < 200)
            viewModel.navigate(Location(listOf(Crumb(storage.browse, "browse ${steps++}"))))
        }
    }

    private fun searchPlain() {
        viewModel.search("plain", "ALL", null, null, null)
        assertTrue(harness.awaitUntil { state.view is View.Search && names == listOf("plain.txt") && !state.searching })
    }

    @Test fun `stepping into a folder and back leaves the search running rather than restarting it`() {
        val browse = searchUntilBlocked()

        viewModel.openFolder(browse)
        assertTrue("Never opened the folder",
            harness.awaitUntil { state.view is View.Folder && names == listOf("plain.txt") })

        viewModel.back()
        assertTrue("Back returns to the results", harness.awaitUntil { state.view is View.Search })
        assertEquals("Which are the ones it had, not a fresh list", listOf("hit-one.txt"), names)
        assertTrue("And it is still looking, because it was never stopped", state.searching)

        gate.complete(Unit)
        assertTrue("The rest of the results must arrive in the view that is showing",
            harness.awaitUntil { names == listOf("hit-one.txt", "hit-two.txt") && !state.searching })
    }

    @Test fun `a search left with no way back to it stops rather than walking on unseen`() {
        searchUntilBlocked()

        viewModel.navigate(Location(listOf(Crumb(storage.browse, "browse"))))
        assertTrue(harness.awaitUntil { state.view is View.Folder && names == listOf("plain.txt") })

        gate.complete(Unit)
        assertFalse("The walk must have been dropped when the view it was filling was",
            harness.awaitUntil(rounds = 60) { storage.finishedLater })
    }

    @Test fun `what a search finds while a folder is on screen is waiting when you come back`() {
        val browse = searchUntilBlocked()

        viewModel.openFolder(browse)
        assertTrue(harness.awaitUntil { state.view is View.Folder && names == listOf("plain.txt") })

        gate.complete(Unit)
        assertTrue("The walk must carry on with a folder on screen",
            harness.awaitUntil { storage.finishedLater })
        harness.idle()
        assertEquals("And what it finds must not appear in the folder", listOf("plain.txt"), names)
        assertFalse("Nor make the folder look like a search", state.searching)

        viewModel.back()
        assertTrue(harness.awaitUntil { state.view is View.Search })
        assertTrue("Everything it found, including what it found while away, ends up listed: $names",
            harness.awaitUntil { names == listOf("hit-one.txt", "hit-two.txt") })
        assertFalse("And it is done, because it finished while the folder was up", state.searching)
    }

    @Test fun `a search goes on while another tab is in front and is finished when its tab comes back`() {
        searchUntilBlocked()
        val first = state.activeTabId

        viewModel.newTab()
        assertTrue("The new tab shows the folder", harness.awaitUntil { showsGated() })

        gate.complete(Unit)
        assertTrue("The walk must carry on behind another tab", harness.awaitUntil { storage.finishedLater })
        assertFalse("What it finds stays out of the tab in front", harness.awaitUntil(rounds = 60) {
            names.contains("hit-two.txt") || state.searching || state.view !is View.Folder
        })

        viewModel.selectTab(first)
        assertTrue("Everything it found is there",
            harness.awaitUntil { state.view is View.Search && names == bothHits })
        assertFalse("And it is done", state.searching)
        assertEquals("Coming back must not run the search again", 1, storage.laterListings.get())
    }

    @Test fun `coming back to a tab whose search is still running shows what it has and goes on filling it`() {
        searchUntilBlocked()
        val first = state.activeTabId
        viewModel.newTab()
        assertTrue(harness.awaitUntil { showsGated() })

        viewModel.selectTab(first)
        assertTrue("The results come back",
            harness.awaitUntil { state.view is View.Search && names == listOf("hit-one.txt") })
        assertTrue("Still looking", state.searching)

        gate.complete(Unit)
        assertTrue("The rest arrives in the view that is showing",
            harness.awaitUntil { names == bothHits && !state.searching })
    }

    @Test fun `a tab left before its search found anything comes back still searching`() {
        atGated()
        val first = state.activeTabId
        viewModel.search("two", "ALL", null, null, null)
        assertTrue(harness.awaitUntil { state.view is View.Search && storage.laterListings.get() == 1 })
        viewModel.newTab()
        assertTrue(harness.awaitUntil { showsGated() })

        viewModel.selectTab(first)
        assertTrue(harness.awaitUntil { state.view is View.Search })
        assertEquals(emptyList<String>(), names)
        assertTrue("Still looking", state.searching)
        assertTrue("And saying so", state.searchSummary.isNotEmpty())

        gate.complete(Unit)
        assertTrue("What it finds arrives",
            harness.awaitUntil { names == listOf("hit-two.txt") && !state.searching })
    }

    @Test fun `results first found and finished behind another tab do not come back as nothing found`() {
        atGated()
        val first = state.activeTabId
        viewModel.search("two", "ALL", null, null, null)
        assertTrue(harness.awaitUntil { state.view is View.Search && storage.laterListings.get() == 1 })
        viewModel.newTab()
        assertTrue(harness.awaitUntil { showsGated() })

        gate.complete(Unit)
        assertTrue("The walk ends behind the other tab", harness.awaitUntil { storage.laterLister?.isCompleted == true })
        // Its last event was sent before it ended, so this delivers it.
        harness.idle()

        viewModel.selectTab(first)
        // The sorted rows land through the looper, which has not run since.
        assertTrue(state.view is View.Search)
        assertTrue("An empty list that is not busy reads as nothing found", names.isNotEmpty() || state.searching)
        assertTrue(harness.awaitUntil { names == listOf("hit-two.txt") && !state.searching })
    }

    @Test fun `closing a tab stops the search it held`() {
        searchUntilBlocked()
        val first = state.activeTabId
        viewModel.newTab()
        assertTrue(harness.awaitUntil { showsGated() })
        searchHitsUntilBlocked()
        assertTrue(harness.awaitUntil { storage.laterListings.get() == 2 })

        viewModel.closeTab(first)
        gate.complete(Unit)
        assertTrue("The tab in front goes on", harness.awaitUntil { names == bothHits && !state.searching })
        assertFalse("The closed tab's walk must not", harness.awaitUntil(rounds = 60) { storage.laterFinished.get() > 1 })
    }

    @Test fun `closing another tab leaves the results in front alone`() {
        atGated()
        val first = state.activeTabId
        viewModel.newTab()
        assertTrue(harness.awaitUntil { showsGated() && state.activeTabId != first })
        val extra = state.activeTabId
        viewModel.selectTab(first)
        assertTrue(harness.awaitUntil { showsGated() && !state.loading })
        searchHitsUntilBlocked()

        viewModel.closeTab(extra)
        assertFalse("The results stay up and go on filling",
            harness.awaitUntil(rounds = 60) { state.view !is View.Search || !state.searching })

        gate.complete(Unit)
        assertTrue(harness.awaitUntil { names == bothHits && !state.searching })
    }

    @Test fun `closing the tab in front brings back the search behind it`() {
        searchUntilBlocked()
        viewModel.newTab()
        assertTrue(harness.awaitUntil { showsGated() })

        viewModel.closeTab(state.activeTabId)
        assertTrue("The results come back",
            harness.awaitUntil { state.view is View.Search && names == listOf("hit-one.txt") })
        assertTrue("Still looking", state.searching)

        gate.complete(Unit)
        assertTrue(harness.awaitUntil { names == bothHits && !state.searching })
    }

    @Test fun `refusing to close the last tab leaves its search running`() {
        searchUntilBlocked()

        viewModel.closeTab(state.activeTabId)

        gate.complete(Unit)
        assertTrue(harness.awaitUntil { names == bothHits && !state.searching })
    }

    @Test fun `a search in one tab leaves another tab's search alone`() {
        searchUntilBlocked()
        val first = state.activeTabId
        viewModel.newTab()
        assertTrue(harness.awaitUntil { showsGated() })
        openBrowse()
        searchPlain()
        viewModel.closeSearch()
        assertTrue(harness.awaitUntil { state.view is View.Folder && names == listOf("plain.txt") })

        viewModel.selectTab(first)
        assertTrue("The first tab's results are still there",
            harness.awaitUntil { state.view is View.Search && names == listOf("hit-one.txt") })
        assertTrue("Still looking", state.searching)

        gate.complete(Unit)
        assertTrue(harness.awaitUntil { names == bothHits && !state.searching })
    }

    @Test fun `results waiting behind an opened folder survive a search in another tab`() {
        val browse = searchUntilBlocked()
        val first = state.activeTabId
        viewModel.openFolder(browse)
        assertTrue(harness.awaitUntil { state.view is View.Folder && names == listOf("plain.txt") })
        viewModel.newTab()
        assertTrue(harness.awaitUntil { state.activeTabId != first && state.view is View.Folder && !state.loading })
        searchPlain()

        viewModel.selectTab(first)
        assertTrue("The tab comes back on the folder it was showing",
            harness.awaitUntil { state.view is View.Folder && names == listOf("plain.txt") })

        viewModel.back()
        assertTrue("Back returns to the results",
            harness.awaitUntil { state.view is View.Search && names == listOf("hit-one.txt") })
        assertTrue("Still looking", state.searching)

        gate.complete(Unit)
        assertTrue(harness.awaitUntil { names == bothHits && !state.searching })
    }

    @Test fun `tapping the tab in front leaves its results up`() {
        searchUntilBlocked()

        viewModel.selectTab(state.activeTabId)
        assertFalse("The results stay up and go on filling",
            harness.awaitUntil(rounds = 60) { state.view !is View.Search || !state.searching })
    }

    @Test fun `the same search in two tabs fills each tab's own view`() {
        searchUntilBlocked()
        val first = state.activeTabId
        viewModel.newTab()
        assertTrue(harness.awaitUntil { showsGated() })
        searchHitsUntilBlocked()
        viewModel.cancelSearch()

        gate.complete(Unit)
        assertTrue("The first tab's walk goes on", harness.awaitUntil { storage.finishedLater })
        assertFalse("What it finds stays out of the stopped search in front",
            harness.awaitUntil(rounds = 60) { names != listOf("hit-one.txt") || state.searching })

        viewModel.selectTab(first)
        assertTrue("And is in its own tab", harness.awaitUntil { names == bothHits && !state.searching })
    }

    @Test fun `a sort chosen while a search was away is how its results come back`() {
        gate.complete(Unit)
        atGated()
        val first = state.activeTabId
        viewModel.search("hit", "ALL", null, null, null)
        assertTrue(harness.awaitUntil { names == bothHits && !state.searching })
        viewModel.newTab()
        assertTrue(harness.awaitUntil { showsGated() })

        viewModel.setPreferences(state.preferences.copy(descending = true))
        viewModel.selectTab(first)
        assertTrue("The results come back in the new order",
            harness.awaitUntil { state.view is View.Search && names == bothHits.reversed() })
    }

    @Test fun `a folder still opening in the tab that was left does not land in the results that come back`() {
        atGated()
        val first = state.activeTabId
        openBrowse()
        searchPlain()
        viewModel.newTab()
        assertTrue(harness.awaitUntil { state.activeTabId != first && state.view is View.Folder && !state.loading })
        viewModel.navigate(Location(listOf(Crumb(storage.later, "later"))))
        assertTrue("The listing is held open", harness.awaitUntil { storage.laterListings.get() == 1 })

        viewModel.selectTab(first)
        assertTrue(harness.awaitUntil { state.view is View.Search && names == listOf("plain.txt") })
        gate.complete(Unit)
        assertFalse("The other tab's folder must not replace the results",
            harness.awaitUntil(rounds = 60) { names != listOf("plain.txt") })
    }

    @Test fun `results under a folder stop once Back can no longer reach them`() {
        val browse = searchUntilBlocked()
        viewModel.openFolder(browse)
        assertTrue(harness.awaitUntil { state.view is View.Folder && names == listOf("plain.txt") })

        viewModel.showScreen(Screen.HOME)
        viewModel.navigate(Location(listOf(Crumb(storage.root, "Gated"))))
        assertTrue(harness.awaitUntil { showsGated() })
        assertFalse("Back leads to Home now, not to the results", state.inBrowserHistory)

        gate.complete(Unit)
        assertFalse("So the walk must have been dropped", harness.awaitUntil(rounds = 60) { storage.finishedLater })
    }

    @Test fun `results under a folder stop once history has dropped the entry they sit at`() {
        val browse = searchUntilBlocked()
        viewModel.openFolder(browse)
        assertTrue(harness.awaitUntil { state.view is View.Folder && names == listOf("plain.txt") })

        browseOnUntil { tab -> tab.history.none { it.ref == storage.root } }

        gate.complete(Unit)
        assertFalse("The walk must have been dropped", harness.awaitUntil(rounds = 60) { storage.finishedLater })
    }

    @Test fun `results under a folder go on while history still holds the entry they sit at`() {
        val browse = searchUntilBlocked()
        viewModel.openFolder(browse)
        assertTrue(harness.awaitUntil { state.view is View.Folder && names == listOf("plain.txt") })

        browseOnUntil { tab -> tab.history.first().ref == storage.root }

        gate.complete(Unit)
        assertTrue("Back can still reach them, so the walk goes on", harness.awaitUntil { storage.finishedLater })
        repeat(state.tab!!.index) { viewModel.back() }
        assertTrue("And they are at the entry they were left at",
            harness.awaitUntil { state.view is View.Search && names == bothHits && !state.searching })
    }

    @Test fun `a change of storage ends the searches behind the front tab`() {
        searchUntilBlocked()
        val first = state.activeTabId
        viewModel.newTab()
        assertTrue(harness.awaitUntil { showsGated() })

        harness.graph.additionalRoots = harness.graph.additionalRoots + LocalRoot("other", "Other", harness.directory)
        viewModel.refreshAccess()
        assertTrue(harness.awaitUntil { state.roots.any { it.title == "Other" } })

        gate.complete(Unit)
        assertFalse("The walk behind must have been dropped", harness.awaitUntil(rounds = 60) { storage.finishedLater })
        viewModel.selectTab(first)
        assertTrue("Its tab shows its folder", harness.awaitUntil { showsGated() })
    }

    @Test fun `refreshing results that were away when hidden files were turned on finds them`() {
        gate.complete(Unit)
        atGated()
        val first = state.activeTabId
        viewModel.search("hit", "ALL", null, null, null)
        assertTrue(harness.awaitUntil { names == bothHits && !state.searching })
        viewModel.newTab()
        assertTrue(harness.awaitUntil { showsGated() })
        viewModel.setPreferences(state.preferences.copy(showHidden = true))
        viewModel.selectTab(first)
        assertTrue(harness.awaitUntil { state.view is View.Search && names == bothHits })

        viewModel.refresh()
        assertTrue("The search runs again with hidden files in it",
            harness.awaitUntil { names.contains(".hit-hidden.txt") && !state.searching })
    }

    @Test fun `finished results behind the front tab are dropped, largest first, once they hold too many rows`() {
        gate.complete(Unit)
        atGated()
        val first = state.activeTabId
        viewModel.backgroundRows = 2
        viewModel.search("hit", "ALL", null, null, null)
        assertTrue(harness.awaitUntil { names == bothHits && !state.searching })
        viewModel.newTab()
        assertTrue(harness.awaitUntil { showsGated() && state.activeTabId != first })
        val second = state.activeTabId
        openBrowse()
        searchPlain()
        viewModel.newTab()
        assertTrue(harness.awaitUntil { state.activeTabId != second && state.view is View.Folder && !state.loading })

        viewModel.selectTab(second)
        assertTrue("The smaller results are kept",
            harness.awaitUntil { state.view is View.Search && names == listOf("plain.txt") })
        viewModel.selectTab(first)
        assertTrue("The larger ones gave way, so the tab shows its folder", harness.awaitUntil { showsGated() })
    }
}

/** Listing "later" blocks until [gate] completes. */
private class GatedStorage(private val gate: CompletableDeferred<Unit>) : StorageProvider {
    override val id = "gated"
    val root = NodeRef(id, "/")
    val later = NodeRef(id, "/later")
    val browse = NodeRef(id, "/browse")

    val laterListings = AtomicInteger()
    /** Whatever last listed "later". A search's walk ends only after its last event is sent. */
    @Volatile var laterLister: Job? = null
    val laterFinished = AtomicInteger()
    val finishedLater get() = laterFinished.get() > 0

    private fun folder(ref: NodeRef, name: String) =
        Entry(ref, name, directory = true, capabilities = setOf(Capability.LIST, Capability.READ))
    private fun file(name: String) =
        Entry(NodeRef(id, "/$name"), name, directory = false, size = 1L, capabilities = setOf(Capability.READ))

    override suspend fun stat(ref: NodeRef): Entry = when (ref) {
        root -> folder(root, "Gated")
        later -> folder(later, "later")
        browse -> folder(browse, "browse")
        else -> file(ref.key.substringAfterLast('/'))
    }

    override fun list(parent: NodeRef, complete: Boolean): Flow<List<Entry>> = flow {
        when (parent) {
            root -> emit(listOf(file("hit-one.txt"), file(".hit-hidden.txt"), folder(later, "later"), folder(browse, "browse")))
            later -> {
                laterLister = currentCoroutineContext().job
                laterListings.incrementAndGet()
                gate.await()
                emit(listOf(file("hit-two.txt")))
                laterFinished.incrementAndGet()
            }
            browse -> emit(listOf(file("plain.txt")))
            else -> emit(emptyList())
        }
    }

    override suspend fun isDescendant(candidate: NodeRef, ancestor: NodeRef): Boolean =
        candidate == ancestor || ancestor == root
    override suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String): Entry = TODO()
    override suspend fun rename(ref: NodeRef, name: String): Entry = TODO()
    override suspend fun delete(ref: NodeRef) = TODO()
    override suspend fun openRead(ref: NodeRef): InputStream = TODO()
    override suspend fun openWrite(ref: NodeRef): OutputStream = TODO()
    override suspend fun commit(staged: NodeRef, parent: NodeRef, name: String, replace: Entry?,
        onRetained: RetainedObjects?): Entry = TODO()
}
