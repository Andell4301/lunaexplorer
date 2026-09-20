package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.storage.smb.FakeSmbConnector
import com.lunaexplorer.app.storage.smb.SmbAccount
import com.lunaexplorer.app.storage.smb.SmbFailure
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.RootKind
import com.lunaexplorer.core.StorageError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.lunaexplorer.app.model.*

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class SmbBookmarkTest {
    private val connector = FakeSmbConnector().apply {
        refuse = SmbFailure(StorageError.OFFLINE, "The server could not be found by that name")
    }
    private val account = SmbAccount(id = "nas", name = "NAS", host = "nas.local", guest = true)
    private val films = Location(listOf(Crumb(NodeRef("smb", "nas::"), "NAS"), Crumb(NodeRef("smb", "nas:media:"), "media"),
        Crumb(NodeRef("smb", "nas:media:films"), "films")))

    @get:Rule val harness = BrowserViewModelHarness().withSmb(connector).withSession {
        it.copy(smbAccounts = listOf(account), bookmarks = listOf(Bookmark("Films", Destination.Place(location = films))))
    }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    @Test fun `a share that is away is listed, kept, and never touched until it is opened`() {
        assertTrue(harness.awaitUntil { state.ready })

        assertTrue("The share is a root like any other",
            state.roots.any { it.kind == RootKind.NETWORK && it.title == "NAS" })
        assertEquals("And the bookmark into it is still there", 1, state.bookmarks.size)
        assertEquals("Listing roots and drawing the drawer connect to nothing", 0, connector.connections)

        viewModel.openBookmark(state.bookmarks.single())
        assertTrue("Opening it is what tries", harness.awaitUntil { state.error != null })

        assertEquals(StorageError.OFFLINE, state.errorReason)
        assertEquals(1, connector.connections)
        assertTrue("The bookmark survives the share being away", state.bookmarks.size == 1)
        assertNull("And nothing is listed as if it had answered", state.entries.firstOrNull())
    }

    @Test fun `when the share answers, the same bookmark opens where it points`() {
        assertTrue(harness.awaitUntil { state.ready })
        connector.refuse = null
        connector.share.mkdir("films")
        connector.share.createFile("films/one.mkv")

        viewModel.openBookmark(state.bookmarks.single())

        assertTrue("It lands in the folder the crumbs name",
            harness.awaitUntil { state.location?.ref == NodeRef("smb", "nas:media:films") && state.entries.any { it.name == "one.mkv" } })
        assertNull(state.error)
    }

    @Test fun `the top of a server is its shares, and any one of them opens as a folder`() {
        assertTrue(harness.awaitUntil { state.ready })
        connector.refuse = null
        connector.server.add("backup")

        viewModel.navigateRoot(state.roots.single { it.kind == RootKind.NETWORK })

        assertTrue("The shares are the listing",
            harness.awaitUntil { state.entries.map { it.name }.toSet() == setOf("media", "backup") })
        viewModel.openFolder(state.entries.single { it.name == "backup" })
        assertTrue("A share opens where it is, under the server",
            harness.awaitUntil { state.location?.ref == NodeRef("smb", "nas:backup:") && !state.loading && state.error == null })
        assertEquals(listOf("NAS", "backup"), state.location?.crumbs?.map { it.name })
        assertEquals("On the one session", 1, connector.connections)
    }

    @Test fun `a server that will not name its shares is shown refusing in its own words`() {
        assertTrue(harness.awaitUntil { state.ready })
        connector.refuse = null
        connector.server.refuseListing = SmbFailure(StorageError.PERMISSION, "The server refused: access denied")

        viewModel.navigateRoot(state.roots.single { it.kind == RootKind.NETWORK })

        assertTrue(harness.awaitUntil { state.error != null })
        assertEquals(StorageError.PERMISSION, state.errorReason)
        assertTrue("Not the wording for a folder on the device: ${state.error}",
            state.error!!.contains("access denied") && !state.error!!.contains("Android"))
    }
}
