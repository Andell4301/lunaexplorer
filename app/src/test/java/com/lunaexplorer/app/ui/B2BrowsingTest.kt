package com.lunaexplorer.app.ui

import androidx.lifecycle.ViewModelStore
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.Overlay
import com.lunaexplorer.app.model.VersionedDelete
import com.lunaexplorer.app.storage.b2.B2Account
import com.lunaexplorer.app.storage.b2.FakeB2Backend
import com.lunaexplorer.app.storage.b2.FakeB2Connector
import com.lunaexplorer.core.RootKind
import com.lunaexplorer.core.StorageError
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class B2BrowsingTest {
    private val backend = FakeB2Backend("media").apply {
        put("media", "notes.txt", "n")
        put("media", "photos/a.jpg", "a")
    }
    private val account = B2Account(id = "cloud", name = "Cloud", keyId = "key-id", bucket = "media")

    @get:Rule val harness = BrowserViewModelHarness().withB2(FakeB2Connector(backend)).withSession {
        it.copy(b2Accounts = listOf(account), preferences = it.preferences.copy(confirmDelete = false))
    }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    @Before fun emptyCache() = runBlocking { harness.graph.b2.forget(null) }

    private fun openBucket() {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.navigateRoot(state.roots.single { it.kind == RootKind.NETWORK })
        assertTrue(harness.awaitUntil { !state.loading && state.entries.map { it.name }.toSet() == setOf("photos", "notes.txt") })
    }

    @Test fun `a saved folder is read again by a refresh, and not by starting up in it`() {
        openBucket()
        backend.put("media", "later.txt", "l")
        val asked = backend.listCalls
        assertTrue(harness.awaitUntil { runBlocking { harness.graph.database.loadSession() }?.location?.ref?.provider == "b2" })

        val restarted = BrowserViewModel(harness.application, harness.graph)
        val store = ViewModelStore().apply { put("restarted", restarted) }
        try {
            assertTrue(harness.awaitUntil { restarted.state.value.let { it.ready && !it.loading && it.entries.size == 2 } })
            assertEquals("Starting up in the folder asked B2 nothing", asked, backend.listCalls)
        } finally { store.clear() }

        viewModel.refresh()
        assertTrue(harness.awaitUntil { state.entries.any { it.name == "later.txt" } })
    }

    @Test fun `a refreshed search forgets what was saved beneath its root`() {
        openBucket()
        viewModel.openFolder(state.entries.single { it.name == "photos" })
        assertTrue(harness.awaitUntil { !state.loading && state.entries.singleOrNull()?.name == "a.jpg" })
        backend.put("media", "photos/b.jpg", "b")
        viewModel.back()
        assertTrue(harness.awaitUntil { !state.loading && state.entries.any { it.name == "notes.txt" } })

        viewModel.search("jpg", "FILE", null, null, null)
        assertTrue("A search walks the saved listings", harness.awaitUntil { !state.searching && state.entries.map { it.name } == listOf("a.jpg") })
        viewModel.refresh()

        assertTrue(harness.awaitUntil { state.entries.map { it.name }.toSet() == setOf("a.jpg", "b.jpg") })
    }

    @Test fun `deleting from a viewer, which cannot ask which way, hides`() {
        openBucket()

        viewModel.operations.deleteEntry(state.entries.single { it.name == "notes.txt" })

        assertTrue(harness.awaitUntil { state.operations.any { it.title == "Hide 1 item(s)" } })
    }

    @Test fun `a rename keeps the old name's versions unless deleting them is the standing choice`() {
        openBucket()
        val notes = state.entries.single { it.name == "notes.txt" }

        viewModel.operations.renameEntry(notes, "kept.txt")
        assertTrue(harness.awaitUntil { state.operations.any { it.title == "Rename notes.txt to kept.txt" } })
        viewModel.setPreferences(state.preferences.copy(versionedDelete = VersionedDelete.PURGE))
        viewModel.operations.renameEntry(notes, "purged.txt")
        assertTrue(harness.awaitUntil { state.operations.any { it.title == "Rename notes.txt to purged.txt" } })

        fun queued(title: String) = runBlocking { harness.graph.database.request(state.operations.first { it.title == title }.id) }
        assertEquals(true, queued("Rename notes.txt to kept.txt")?.keepVersions)
        assertEquals(false, queued("Rename notes.txt to purged.txt")?.keepVersions)
    }

    @Test fun `a locked vault is asked about at the bucket, and unlocking shows what was saved without asking B2`() {
        assertTrue(harness.awaitUntil { state.ready })
        val bucket = state.roots.single { it.kind == RootKind.NETWORK }
        runBlocking { harness.graph.b2.list(bucket.ref).toList() }
        harness.graph.b2.disconnect(account.id)
        val asked = backend.listCalls
        assertTrue(viewModel.vault.setLocked(true))
        viewModel.vault.close()
        harness.vaultKeys.personPresent = false

        viewModel.navigateRoot(bucket)

        assertTrue("Not let in to be stopped at the first file", harness.awaitUntil { state.errorReason == StorageError.AUTH })
        assertTrue(state.entries.isEmpty())

        harness.vaultKeys.personPresent = true
        assertTrue(viewModel.vault.open())
        viewModel.reopen()

        assertTrue(harness.awaitUntil { !state.loading && state.entries.map { it.name }.toSet() == setOf("photos", "notes.txt") })
        assertEquals("The saved listing is still good: unlocking is not a refresh", asked, backend.listCalls)
    }

    @Test fun `removing the account takes its root and its saved listings with it`() {
        openBucket()
        assertTrue(runBlocking { harness.graph.b2.cachedBytes() } > 0)

        viewModel.b2.remove(account.id)

        assertTrue(harness.awaitUntil { state.roots.none { it.kind == RootKind.NETWORK } })
        assertTrue(harness.awaitUntil { runBlocking { harness.graph.b2.cachedBytes() } == 0L })
    }

    @Test fun `deleting where versions are kept asks which way, and hiding is queued as such`() {
        openBucket()
        viewModel.toggleSelection(state.entries.single { it.name == "notes.txt" }.ref)

        viewModel.requestDelete()

        assertTrue("Asked although confirmation is off", harness.awaitUntil { state.overlay == Overlay.Delete })
        assertTrue(state.deleteCheck?.versioned == true)
        viewModel.deleteChecked(toBin = false, keepVersions = true)
        assertTrue(harness.awaitUntil { state.operations.any { it.title == "Hide 1 item(s)" } })
    }

    @Test fun `a remembered answer deletes without asking, and never by way of the bin`() {
        openBucket()
        viewModel.setPreferences(state.preferences.copy(versionedDelete = VersionedDelete.PURGE, recycleBin = true))
        viewModel.toggleSelection(state.entries.single { it.name == "notes.txt" }.ref)

        viewModel.requestDelete()

        assertTrue(harness.awaitUntil { state.operations.any { it.title == "Delete 1 item(s)" } })
        assertNull(state.overlay)
    }
}
