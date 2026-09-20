package com.lunaexplorer.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnalysisFilesTest {
    @get:Rule val compose = createComposeRule()

    private val revealed = mutableListOf<String>()
    private var removed = emptyList<String>()

    private fun list() {
        val rows = listOf(
            AnalysisRow("a", "alpha.bin", "application/octet-stream", 10, path = "/one/alpha.bin"),
            AnalysisRow("b", "beta.bin", "application/octet-stream", 20, path = "/one/beta.bin"),
        )
        // In a Column, as the card lays them out, so the rows have distinct touch targets.
        compose.setContent {
            Column {
                AnalysisFiles(rows, onReveal = { revealed += it.key },
                    onDelete = { removed = it.map { r -> r.key } })
            }
        }
    }

    @Test fun `a tap goes to the file while nothing is selected`() {
        list()
        compose.onNodeWithText("alpha.bin").performClick()
        compose.waitForIdle()

        assertEquals(listOf("a"), revealed)
        assertEquals("And nothing is picked out by it", emptyList<String>(), removed)
    }

    @Test fun `a press and hold selects, and what is selected can be removed`() {
        list()
        compose.onNodeWithText("alpha.bin").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals("A hold picks out rather than going anywhere", emptyList<String>(), revealed)

        compose.onNodeWithText("beta.bin").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<String>(), revealed)

        compose.onNodeWithText("Remove 2").assertIsDisplayed()
        compose.onNodeWithText("Remove 2").performClick()
        compose.waitForIdle()
        assertEquals(listOf("a", "b"), removed)
    }

    @Test fun `another analysis result arriving preserves ticks for unchanged files`() {
        val revision = mutableIntStateOf(0)
        compose.setContent {
            Column {
                Text("Analysis update ${revision.intValue}")
                // Built inside the composition: storage cards pass a new list as analysis results arrive.
                val rows = listOf(
                    AnalysisRow("a", "alpha.bin", "application/octet-stream", 10, path = "/one/alpha.bin"),
                    AnalysisRow("b", "beta.bin", "application/octet-stream", 20, path = "/one/beta.bin"),
                )
                AnalysisFiles(rows, onReveal = { revealed += it.key }, onDelete = { removed = it.map { row -> row.key } })
            }
        }
        compose.onNodeWithText("alpha.bin").performTouchInput { longClick() }
        compose.onNodeWithText("Remove 1").assertIsDisplayed()
        compose.runOnIdle { revision.intValue++ }
        compose.onNodeWithText("Analysis update 1").assertIsDisplayed()
        compose.onNodeWithText("Remove 1").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("a"), removed) }
    }
}
