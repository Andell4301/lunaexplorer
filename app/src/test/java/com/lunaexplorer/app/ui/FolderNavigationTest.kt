package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.Crumb
import com.lunaexplorer.app.model.Location
import com.lunaexplorer.app.model.View
import com.lunaexplorer.core.MemoryStorageProvider
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.StorageProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class FolderNavigationTest {
    @get:Rule val harness = BrowserViewModelHarness()
    private val memory = MemoryStorageProvider("navigation")
    private val first = memory.folder(memory.root, "folder1")
    private val second = memory.folder(memory.root, "folder2")
    private val child = memory.folder(second, "child")
    private val home = Location(listOf(Crumb(memory.root, "home")))
    private val gates = mutableMapOf<NodeRef, CompletableDeferred<Unit>>()
    private val afterListing = mutableMapOf<NodeRef, CompletableDeferred<Unit>>()
    private val entered = mutableSetOf<NodeRef>()
    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    private fun browsing() {
        assertTrue(harness.awaitUntil { state.ready && !state.loading && state.view is View.Folder })
        harness.graph.providers.register(object : StorageProvider by memory {
            override fun list(parent: NodeRef, complete: Boolean) = flow {
                entered += parent
                gates[parent]?.await()
                emitAll(memory.list(parent, complete))
                afterListing[parent]?.await()
            }
        })
        viewModel.navigate(home)
        arrived(memory.root)
    }

    private fun arrived(ref: NodeRef) {
        assertTrue("Never displayed $ref", harness.awaitUntil { state.listingRef == ref && !state.loading })
    }

    private fun hold(ref: NodeRef) {
        gates[ref] = CompletableDeferred()
        entered -= ref
    }

    private fun open(ref: NodeRef) = viewModel.openFolder(state.entries.single { it.ref == ref })

    private fun held(ref: NodeRef) {
        assertTrue("Never started listing $ref", harness.awaitUntil { ref in entered })
        assertTrue(state.loading)
    }

    @Test fun `a second tap opens a sibling using the displayed folders parent`() {
        browsing()
        hold(first)
        open(first)
        held(first)
        open(second)

        val destination = Location(home.crumbs + Crumb(second, "folder2"))
        assertEquals(destination, state.location)
        arrived(second)
        open(child)
        assertEquals(Location(destination.crumbs + Crumb(child, "child")), state.location)
        arrived(child)

        viewModel.back()
        arrived(second)
        assertEquals(destination, state.location)
        viewModel.back()
        assertEquals(Location(home.crumbs + Crumb(first, "folder1")), state.location)
        gates.getValue(first).complete(Unit)
        arrived(first)
    }

    @Test fun `tapping the same loading folder twice does not duplicate its breadcrumb or history`() {
        browsing()
        val historySize = state.tab!!.history.size
        hold(first)
        open(first)
        held(first)
        open(first)

        assertEquals(Location(home.crumbs + Crumb(first, "folder1")), state.location)
        assertEquals(historySize + 1, state.tab!!.history.size)
        gates.getValue(first).complete(Unit)
        arrived(first)
    }

    @Test fun `a second tap on retained search results starts its own breadcrumb chain`() {
        browsing()
        viewModel.search("folder", "ALL", null, null, null)
        assertTrue(harness.awaitUntil { state.view is View.Search && !state.searching && state.entries.size == 2 })
        hold(first)
        open(first)
        held(first)
        open(second)

        assertEquals(Location(listOf(Crumb(second, "folder2"))), state.location)
        arrived(second)
    }

    @Test fun `rows retained while switching tabs keep their original parent`() {
        browsing()
        val originalTab = state.activeTabId
        viewModel.newTab()
        val otherTab = state.activeTabId
        arrived(memory.root)
        hold(first)
        viewModel.navigate(Location(listOf(Crumb(first, "elsewhere"))))
        held(first)
        viewModel.selectTab(originalTab)
        arrived(memory.root)

        viewModel.selectTab(otherTab)
        open(second)

        assertEquals(Location(home.crumbs + Crumb(second, "folder2")), state.location)
        arrived(second)
    }

    @Test fun `cached rows supply the parent for the next folder tap`() {
        browsing()
        open(second)
        arrived(second)
        viewModel.navigate(home)
        arrived(memory.root)

        open(second)
        arrived(second)
        open(child)

        assertEquals(Location(home.crumbs + Crumb(second, "folder2") + Crumb(child, "child")), state.location)
        arrived(child)
    }

    @Test fun `a tap from the previous render keeps its parent after new rows arrive`() {
        browsing()
        val rendered = state
        val openRow = {
            viewModel.openFolder(rendered.entries.single { it.ref == second }, (rendered.view as View.Folder).listingLocation)
        }
        open(first)
        arrived(first)

        openRow()

        assertEquals(Location(home.crumbs + Crumb(second, "folder2")), state.location)
        arrived(second)
    }

    @Test fun `a folder in a partial listing uses the newly displayed parent`() {
        repeat(255) { memory.folder(second, "item$it") }
        afterListing[second] = CompletableDeferred()
        browsing()
        open(second)
        assertTrue(harness.awaitUntil { state.listingRef == second && state.entries.size == 256 })
        assertTrue(state.loading)

        open(child)

        assertEquals(Location(home.crumbs + Crumb(second, "folder2") + Crumb(child, "child")), state.location)
        arrived(child)
    }

    @Test fun `an archive tapped while a folder loads keeps the displayed parent`() {
        val archive = memory.file(memory.root, "contents.zip", "")
        runBlocking {
            ZipOutputStream(memory.openWrite(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("inside.txt"))
                zip.write("contents".toByteArray())
                zip.closeEntry()
            }
        }
        browsing()
        hold(first)
        open(first)
        held(first)

        viewModel.browseArchive(state.entries.single { it.ref == archive })

        assertTrue(harness.awaitUntil { state.location?.ref?.provider == "archive" && !state.loading })
        assertEquals(home.crumbs, state.location!!.crumbs.dropLast(1))
        assertEquals("contents.zip", state.location!!.title)
    }
}
