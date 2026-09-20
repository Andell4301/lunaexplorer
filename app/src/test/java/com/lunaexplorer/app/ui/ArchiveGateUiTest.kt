package com.lunaexplorer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.core.ArchiveFormat
import com.lunaexplorer.core.ArchiveOptions
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.ZipEncryption
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import com.lunaexplorer.app.model.*

// SDK 35: Robolectric's SDK 36 framework jar bundles an older commons-compress that shadows the
// one the archive engine is built against.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class ArchiveGateUiTest {
    private val harness = BrowserViewModelHarness().startingWith { folder ->
        File(folder, "app.apk").writeBytes(ByteArray(64) { it.toByte() })
        File(folder, "plain.txt").writeText("nothing secret")
    }.withSession {
        it.copy(preferences = it.preferences.copy(thumbnails = false, introSeen = true))
    }
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(harness).around(compose)

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state
    private val graph get() = harness.graph

    private fun ref(file: File): NodeRef = requireNotNull(graph.local.referenceTo(file.path))
    private fun ref(name: String): NodeRef = ref(File(harness.directory, name))

    private fun encryptedZip(): Entry = runBlocking {
        val produced = graph.archives.create(listOf(ref("app.apk")), ref(harness.directory), "locked.zip",
            ArchiveOptions(ArchiveFormat.ZIP, encryption = ZipEncryption.AES_256, password = "hunter2"))
            .last().produced
        graph.local.stat(requireNotNull(produced))
    }

    private fun ready() = assertTrue(harness.awaitUntil { state.ready })

    private fun lockedMember(): Entry {
        val archive = encryptedZip()
        viewModel.browseArchive(archive)
        assertTrue("Lands inside ${archive.name}", harness.awaitUntil {
            state.location?.ref?.provider == graph.insideArchives.id && state.entries.isNotEmpty() && !state.loading
        })
        val member = state.entries.single { it.name == "app.apk" }
        assertTrue("The member is encrypted", graph.insideArchives.needsPassword(member.ref))
        return member
    }

    @Composable
    private fun CarriedFilesBar() {
        MaterialTheme {
            val current by viewModel.state.collectAsState()
            current.clipboard?.let { ClipboardBar(it, current, viewModel) }
        }
    }

    @Test fun `tapping an apk inside an encrypted zip asks for the password before the package sheet`() {
        ready()
        lockedMember()
        val actions = LunaActions(grantFolder = {}, open = { _, _ -> }, openWith = { _, _, _ -> },
            shareReport = {}, requestFullAccess = {}, openSystemBrowser = {})
        compose.setContent { LunaApp(viewModel, actions) }

        compose.onNodeWithText("app.apk").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); state.overlay is Overlay.ArchivePassword }
        assertEquals("The prompt names the member", "app.apk", (state.overlay as Overlay.ArchivePassword).title)

        compose.runOnIdle { viewModel.unlockArchive("hunter2") }
        compose.waitUntil(10_000) { compose.waitForIdle(); state.overlay is Overlay.Package }
        compose.onNodeWithText("Install").assertExists()
    }

    @Test fun `pasting encrypted members asks for the password first`() {
        ready()
        val home = requireNotNull(state.location)
        val member = lockedMember()
        viewModel.toggleSelection(member.ref)
        viewModel.operations.copySelection(false)
        harness.idle()
        assertEquals(listOf("app.apk"), state.clipboard?.entries?.map { it.name })

        viewModel.navigate(home)
        assertTrue(harness.awaitUntil { state.location == home && state.entries.isNotEmpty() && !state.loading })
        compose.setContent { CarriedFilesBar() }

        compose.onNodeWithText("Extract here").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); state.overlay is Overlay.ArchivePassword }
        assertTrue("Nothing is queued before the password", state.operations.isEmpty())

        compose.runOnIdle { viewModel.unlockArchive("hunter2") }
        assertTrue("Unlocking lets the paste through",
            harness.awaitUntil { state.operations.any { it.title.startsWith("Copy 1 item(s)") } })
        assertNull(state.overlay)
    }

    @Test fun `pasting ordinary files is not gated`() {
        ready()
        viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { state.entries.any { it.name == "plain.txt" } })
        viewModel.toggleSelection(state.entries.first { it.name == "plain.txt" }.ref)
        viewModel.operations.copySelection(false)
        harness.idle()
        compose.setContent { CarriedFilesBar() }

        compose.onNodeWithText("Paste here").assertIsEnabled().performClick()
        assertTrue(harness.awaitUntil { state.operations.isNotEmpty() })
        assertNull("Plain files are never asked about", state.overlay)
    }
}
