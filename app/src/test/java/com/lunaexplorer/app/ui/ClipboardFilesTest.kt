package com.lunaexplorer.app.ui

import android.content.ClipboardManager
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.storage.b2.B2Account
import com.lunaexplorer.app.storage.b2.FakeB2Backend
import com.lunaexplorer.app.storage.b2.FakeB2Connector
import com.lunaexplorer.core.RootKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class ClipboardFilesTest {
    private val backend = FakeB2Backend("media").apply { put("media", "remote.txt", "far away") }

    @get:Rule val harness = BrowserViewModelHarness().withB2(FakeB2Connector(backend)).startingWith { dir ->
        File(dir, "one.txt").writeText("1")
        File(dir, "two.png").writeBytes(ByteArray(4))
    }.withSession { it.copy(b2Accounts = listOf(B2Account(id = "cloud", name = "Cloud", keyId = "key-id", bucket = "media"))) }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state
    private val clipboard get() = harness.application.getSystemService(ClipboardManager::class.java)

    @Test fun `files on this device go on the clipboard as themselves, each with its type`() {
        assertTrue(harness.awaitUntil { state.ready && state.entries.size == 2 })

        viewModel.files.copyToClipboard(state.entries)

        assertTrue(harness.awaitUntil { clipboard.primaryClip?.itemCount == 2 })
        val clip = requireNotNull(clipboard.primaryClip)
        assertEquals(setOf("one.txt", "two.png"), (0 until clip.itemCount).map { clip.getItemAt(it).uri.lastPathSegment }.toSet())
        assertTrue((0 until clip.itemCount).all { clip.getItemAt(it).uri.scheme == "content" })
        assertTrue(clip.description.hasMimeType("image/png") && clip.description.hasMimeType("text/plain"))
    }

    @Test fun `a file Luna would have to stream is not put there, and the user is told`() {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.navigateRoot(state.roots.single { it.kind == RootKind.NETWORK })
        assertTrue(harness.awaitUntil { !state.loading && state.entries.any { it.name == "remote.txt" } })

        viewModel.files.copyToClipboard(state.entries)

        assertTrue(harness.awaitUntil { state.message != null })
        assertNull("A paste can come when nothing of Luna's is running to serve it", clipboard.primaryClip)
    }
}
