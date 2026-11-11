package com.lunaexplorer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.MemoryStorageProvider
import com.lunaexplorer.core.StorageProvider
import com.lunaexplorer.core.StorageRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class, qualifiers = "w393dp-h852dp-xhdpi")
class FolderDestinationChooserTest {
    private val harness = BrowserViewModelHarness().startingWith { File(it, "Clips").mkdir() }
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(harness).around(compose)
    private var chosen: Pair<Entry, String>? = null

    private fun show() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        compose.setContent {
            MaterialTheme {
                FolderDestinationChooser(harness.viewModel, false, onDismiss = {},
                    onChoose = { entry, path -> chosen = entry to path })
            }
        }
    }

    private fun choose(name: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(name).performClick()
    }

    @Test fun `opaque destinations return the storage and complete chosen folder trail`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        val storage = MemoryStorageProvider("network")
        val shared = storage.folder(storage.root, "Shared media")
        val clips = storage.folder(shared, " clips ")
        harness.graph.providers.register(object : StorageProvider by storage {
            override suspend fun roots() = listOf(StorageRoot(storage.root, "NAS"))
        })
        harness.viewModel.refreshAccess()
        assertTrue(harness.awaitUntil { harness.state.roots.any { it.ref == storage.root } })
        show()
        choose("NAS")
        choose("Shared media")
        choose(" clips ")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("No folders in here").fetchSemanticsNodes().isNotEmpty() }
        choose("Choose this folder")

        assertEquals(clips, chosen?.first?.ref)
        assertEquals("NAS / Shared media /  clips ", chosen?.second)
    }

    @Test fun `local destinations return the displayed absolute path`() {
        show()
        choose("Harness")
        choose("Clips")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("No folders in here").fetchSemanticsNodes().isNotEmpty() }
        choose("Choose this folder")

        assertEquals(File(harness.directory, "Clips").absolutePath, chosen?.second)
    }
}
