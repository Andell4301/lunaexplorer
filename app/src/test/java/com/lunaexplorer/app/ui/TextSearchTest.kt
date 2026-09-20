package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.Screen
import com.lunaexplorer.app.storage.b2.B2Account
import com.lunaexplorer.app.storage.b2.FakeB2Backend
import com.lunaexplorer.app.storage.b2.FakeB2Connector
import com.lunaexplorer.core.RootKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class TextSearchTest {
    private val backend = FakeB2Backend("media").apply { put("media", "notes.txt", "the word") }

    @get:Rule val harness = BrowserViewModelHarness().withB2(FakeB2Connector(backend)).startingWith { dir ->
        File(dir, "plans").mkdirs()
        File(dir, "plans/trip.md").writeText("Pack the tent\nBook the ferry\n")
        File(dir, "plans/food.md").writeText("Buy rice\n")
        File(dir, "ferry.txt").writeText("not what it says inside")
    }.withSession { it.copy(b2Accounts = listOf(B2Account(id = "cloud", name = "Cloud", keyId = "key-id", bucket = "media"))) }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    @Test fun `a search by text finds the files that hold it, wherever they are under the folder`() {
        assertTrue(harness.awaitUntil { state.ready && !state.loading })
        viewModel.showScreen(Screen.BROWSER)

        viewModel.search("", "ALL", null, null, null, text = "ferry")

        assertTrue(harness.awaitUntil { !state.searching && state.entries.isNotEmpty() })
        assertEquals("The name is not what is searched", listOf("trip.md"), state.entries.map { it.name })
    }

    @Test fun `text is offered for files on this device, and not across a network`() {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.showScreen(Screen.BROWSER)
        assertTrue(viewModel.searchesText())

        viewModel.navigateRoot(state.roots.single { it.kind == RootKind.NETWORK })
        assertTrue(harness.awaitUntil { !state.loading && state.entries.any { it.name == "notes.txt" } })

        assertFalse(viewModel.searchesText())
    }
}
