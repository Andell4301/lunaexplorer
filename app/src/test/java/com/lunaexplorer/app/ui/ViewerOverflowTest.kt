package com.lunaexplorer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lunaexplorer.app.LunaApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class ViewerOverflowTest {
    private val harness = BrowserViewModelHarness().startingWith { File(it, "notes.txt").writeText("Notes") }
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(harness).around(compose)
    private var shown by mutableStateOf(true)
    private var holdsChrome = false

    private fun show() {
        val entry = runBlocking {
            val ref = requireNotNull(harness.graph.local.referenceTo(File(harness.directory, "notes.txt").path))
            harness.graph.local.stat(ref)
        }
        compose.setContent {
            MaterialTheme {
                if (shown) ViewerBar(entry.name, {}, entry, harness.viewModel,
                    onOverlayVisibilityChange = { holdsChrome = it })
            }
        }
        compose.waitForIdle()
    }

    @Test fun `overflow dialogs keep viewer chrome visible until dismissed`() {
        show()
        for (action in listOf("File info", "Rename", "Delete")) {
            compose.onNodeWithContentDescription("More").performClick()
            compose.waitForIdle()
            assertTrue(holdsChrome)

            compose.onNodeWithText(action).performClick()
            compose.waitForIdle()
            assertTrue("$action must keep its parent toolbar alive", holdsChrome)

            if (action == "File info") compose.onNodeWithContentDescription("Back").performClick()
            else compose.onNodeWithText("Cancel").performClick()
            compose.waitForIdle()
            assertFalse(holdsChrome)
        }
    }

    @Test fun `removing the toolbar releases its menu visibility callback`() {
        show()
        compose.onNodeWithContentDescription("More").performClick()
        compose.waitForIdle()
        assertTrue(holdsChrome)

        compose.runOnIdle { shown = false }
        compose.waitForIdle()

        assertFalse(holdsChrome)
    }
}
