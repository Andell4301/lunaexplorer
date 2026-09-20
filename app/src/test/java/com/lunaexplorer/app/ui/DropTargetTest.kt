package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import com.lunaexplorer.app.model.*

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class DropTargetTest {
    @get:Rule val harness = BrowserViewModelHarness().startingWith { dir ->
        File(dir, "quarry").mkdirs()
        File(dir, "quarry/deep.txt").writeText("x")
        File(dir, "carried.txt").writeText("x")
    }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    private fun browsing() {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { state.view is View.Folder && state.entries.isNotEmpty() })
    }

    @Test fun `a search has no folder under it for a drop to land in`() {
        browsing()
        val folder = requireNotNull(state.location).ref

        viewModel.search("deep", "ALL", null, null, null)
        assertTrue(harness.awaitUntil { state.view is View.Search })

        assertEquals("The tab still points at the folder it was searched from",
            folder, requireNotNull(state.location).ref)
        assertNull("But the rows on screen are not that folder's, so a drop has nowhere to land",
            state.dropHere)
    }

    @Test fun `a folder that has stopped opening is not one to drop into`() {
        browsing()
        val inner = state.entries.first { it.directory }
        viewModel.openFolder(inner)
        assertTrue(harness.awaitUntil { state.dropHere?.ref == inner.ref })

        harness.directory.resolve("quarry").deleteRecursively()
        viewModel.refresh()
        assertTrue("The listing must fail", harness.awaitUntil { state.error != null })

        assertNull("A folder that would not open is not somewhere to put things", state.dropHere)
    }

    // States are built directly: reaching them by navigation depends on timing and cache expiry.
    @Test fun `a drop that lands on nothing needs a folder that is settled, open and writable`() {
        val here = NodeRef("test", "here")
        val folder = Entry(here, "here", directory = true, capabilities = setOf(Capability.CREATE))
        fun showing(view: View.Folder, error: String? = null, screen: Screen = Screen.BROWSER) =
            BrowserState(view = view, error = error, screen = screen).dropHere

        assertEquals("A folder on screen is where an untargeted drop goes",
            here, showing(View.Folder(here, folder, listingRef = here))?.ref)
        assertNull("A folder left behind another screen is not on screen at all",
            showing(View.Folder(here, folder, listingRef = here), screen = Screen.HOME))
        assertNull("Its rows belong to the folder being left until the listing catches up",
            showing(View.Folder(here, folder, listingRef = NodeRef("test", "left behind"))))
        assertNull("A folder that would not open is not somewhere to put things",
            showing(View.Folder(here, folder, listingRef = here), error = "It is gone"))
        assertNull("Nor is one that cannot be written to, which is every archive's insides",
            showing(View.Folder(here, folder.copy(capabilities = emptySet()), listingRef = here)))
    }

    @Test fun `letting go where they already are is not a question worth asking`() {
        browsing()
        val file = state.entries.first { !it.directory }
        val here = requireNotNull(state.location)

        viewModel.beginDrag(file)
        viewModel.dropOnto(listOf(file), here.ref, here.title)

        assertNull("Nothing to ask: they are there already", state.overlay)
        assertTrue("And nothing to queue", state.operations.isEmpty())
        assertNotNull("It says so rather than looking broken", state.message)
    }

    @Test fun `each tab keeps the screen it was left on`() {
        browsing()
        val first = requireNotNull(state.tab).id

        viewModel.newTab()
        val second = requireNotNull(state.tab).id
        assertEquals("A new tab opens on the folder it was opened from", Screen.BROWSER, state.screen)
        viewModel.showScreen(Screen.APPS)
        assertEquals("Which is the tab's own, not the app's", Screen.APPS,
            state.tabs.first { it.id == second }.screen)

        viewModel.selectTab(first)
        assertEquals("The other tab was left in a folder and is still in one", Screen.BROWSER, state.screen)
        viewModel.selectTab(second)
        assertEquals("And this one was left on Applications", Screen.APPS, state.screen)
    }
}
