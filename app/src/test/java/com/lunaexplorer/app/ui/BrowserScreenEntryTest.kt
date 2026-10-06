package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.Crumb
import com.lunaexplorer.app.model.Location
import com.lunaexplorer.app.model.Screen
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.StorageProvider
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class BrowserScreenEntryTest {
    private lateinit var root: Location
    private lateinit var downloads: Location

    @get:Rule val harness: BrowserViewModelHarness = BrowserViewModelHarness().startingWith { directory ->
        File(directory, "Downloads").apply { mkdirs() }.resolve("previous.jpg").writeText("previous")
        File(directory, "root.txt").writeText("root")
    }.withSession { initial ->
        root = requireNotNull(initial.location)
        val ref = requireNotNull(harness.graph.local.referenceTo(File(harness.directory, "Downloads").absolutePath))
        downloads = Location(root.crumbs + Crumb(ref, "Downloads"))
        val tab = requireNotNull(initial.tab).copy(history = listOf(root, downloads), index = 1)
        initial.copy(tabs = listOf(tab), screen = Screen.HOME,
            preferences = initial.preferences.copy(startScreen = Screen.HOME))
    }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    private fun restored() {
        arrived(downloads)
        assertEquals(Screen.HOME, state.screen)
        assertEquals(listOf("previous.jpg"), state.entries.map { it.name })
    }

    private fun arrived(location: Location) {
        assertTrue("Never displayed ${location.title}", harness.awaitUntil {
            state.ready && !state.loading && state.listingRef == location.ref
        })
    }

    private fun noPreviousRows() {
        assertEquals(Screen.BROWSER, state.screen)
        assertTrue(state.entries.none { it.name == "previous.jpg" })
        assertTrue(state.selected.isEmpty())
    }

    @Test fun `opening a root from Home never exposes the restored Downloads listing while the root loads`() {
        restored()
        val provider = harness.graph.providers.provider(root.ref)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.graph.providers.register(object : StorageProvider by provider {
            override suspend fun stat(ref: NodeRef): Entry {
                if (ref == root.ref) {
                    entered.complete(Unit)
                    release.await()
                }
                return provider.stat(ref)
            }
        })

        try {
            viewModel.navigate(root)

            noPreviousRows()
            assertTrue(state.entries.isEmpty())
            assertTrue(state.sections.isEmpty())
            assertTrue(state.loading)
            assertTrue(harness.awaitUntil { entered.isCompleted })
            noPreviousRows()
            assertTrue(state.loading)
        } finally {
            release.complete(Unit)
        }
        arrived(root)
        assertEquals(setOf("Downloads", "root.txt"), state.entries.map { it.name }.toSet())
    }

    @Test fun `opening a cached root from Home hides the previous folder before cached rows publish`() {
        restored()
        viewModel.navigate(root)
        arrived(root)
        viewModel.navigate(downloads)
        arrived(downloads)
        viewModel.showScreen(Screen.HOME)

        viewModel.navigate(root)

        noPreviousRows()
        arrived(root)
        assertEquals(setOf("Downloads", "root.txt"), state.entries.map { it.name }.toSet())
    }

    @Test fun `returning from Home to the restored folder keeps its already correct rows`() {
        restored()
        val shown = state.entries

        viewModel.navigate(downloads)

        assertEquals(Screen.BROWSER, state.screen)
        assertEquals(downloads.ref, state.listingRef)
        assertEquals(shown, state.entries)
        arrived(downloads)
        assertEquals(shown, state.entries)
    }
}
