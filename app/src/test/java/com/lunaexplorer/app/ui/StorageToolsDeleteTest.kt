package com.lunaexplorer.app.ui

import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// On the Activity fixture, not BrowserViewModelHarness: the queue's worker runs against the
// application's graph, so only there does a queued operation actually complete.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class StorageToolsDeleteTest : RobolectricBrowserUiTest() {
    private val note get() = File(fixture.directory, "alpha/alpha-note.txt")

    private fun viewModel() =
        compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }

    @Test
    fun aRemovedFileIsRecycledWhenTheBinIsOnAndRemembersItsOwnFolder() {
        awaitListing()
        val viewModel = viewModel()

        compose.runOnUiThread { viewModel.tools.deleteFiles(listOf(note.path)) }

        awaitCondition("The file reaches the bin", 15_000) { viewModel.trash.value.any { it.name == note.name } }
        val held = viewModel.trash.value.single { it.name == note.name }
        assertEquals("Restore must return it to its own folder, not the one on screen",
            note.parent, fixture.graph.local.pathOf(held.originalParent))
        assertFalse(note.exists())
    }

    @Test
    fun aRemovedFileIsDeletedOutrightWhenTheBinIsOff() {
        awaitListing()
        val viewModel = viewModel()

        compose.runOnUiThread {
            viewModel.setPreferences(viewModel.state.value.preferences.copy(recycleBin = false))
            viewModel.tools.deleteFiles(listOf(note.path))
        }

        awaitCondition("The file is deleted", 15_000) { !note.exists() }
        assertTrue("Nothing is kept to restore", viewModel.trash.value.none { it.name == note.name })
    }
}
