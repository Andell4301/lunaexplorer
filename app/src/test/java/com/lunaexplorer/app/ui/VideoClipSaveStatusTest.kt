package com.lunaexplorer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lunaexplorer.app.model.ConflictRow
import com.lunaexplorer.app.model.QueueItem
import com.lunaexplorer.core.NodeRef
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class VideoClipSaveStatusTest {
    @get:Rule val compose = createComposeRule()
    private var operationId by mutableStateOf("clip-save")
    private var operation by mutableStateOf<QueueItem?>(null)
    private var dismissed = 0
    private var openedOperations = 0

    private fun item(status: String, detail: String = "", bytes: Long = 0, results: List<String> = emptyList()) =
        QueueItem(operationId, "Save clip", status, detail = detail, bytes = bytes, results = results)

    private fun show() {
        compose.setContent {
            MaterialTheme {
                VideoClipSaveStatus(operationId, " clip .mp4 ", "NAS / Media / Clips", operation,
                    onDismiss = { dismissed++ }, onOperations = { openedOperations++ })
            }
        }
    }

    @Test fun `queue publication delay shows filename destination and pending state`() {
        show()
        compose.onNodeWithText(" clip .mp4 ").assertIsDisplayed()
        compose.onNodeWithText("NAS / Media / Clips").assertIsDisplayed()
        compose.onNodeWithText("Clip queued").assertIsDisplayed()
        compose.onNodeWithText("Clip saved").assertDoesNotExist()
        compose.onNodeWithTag("clipSavingProgress").assertIsDisplayed()
        compose.onNodeWithText("Operations").performClick()
        assertEquals(1, openedOperations)
        compose.onNodeWithText("Close").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun `queued transfer becomes saved only after the operation succeeds`() {
        operation = item("QUEUED", detail = "Waiting for background execution")
        show()
        compose.onNodeWithText("Waiting for background execution").assertIsDisplayed()
        compose.runOnIdle { operation = item("RUNNING", bytes = 512) }
        compose.onNodeWithText("Saving clip…").assertIsDisplayed()
        compose.onNodeWithTag("clipSaveBytes").assertTextContains("512 B", substring = true)
        compose.onNodeWithText("Clip saved").assertDoesNotExist()
        compose.runOnIdle { operation = item("SUCCEEDED", bytes = 512, detail = "1 success") }
        compose.onNodeWithText("Clip saved").assertIsDisplayed()
        compose.onNodeWithTag("clipSavingProgress").assertDoesNotExist()
        compose.onNodeWithText("Done").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun `failure exposes its recorded cause instead of only the failed item count`() {
        operation = item("FAILED", detail = "1 failed", results = listOf("Staging clip", "NAS refused write access"))
        show()
        compose.onNodeWithText("Could not save clip").assertIsDisplayed()
        compose.onNodeWithText("NAS refused write access").assertIsDisplayed()
        compose.onNodeWithText("1 failed").assertDoesNotExist()
        compose.onNodeWithText("Operations").performClick()
        assertEquals(1, openedOperations)
    }

    @Test fun `name conflicts expose the decision and a route to operations`() {
        operation = item("CONFLICT", detail = "clip.mp4 already exists").copy(
            conflicts = listOf(ConflictRow(NodeRef("clips", "rendered"), name = "clip.mp4")))
        show()
        compose.onNodeWithText("clip.mp4 already exists").assertIsDisplayed()
        compose.onNodeWithText("Clip saved").assertDoesNotExist()
        compose.onNodeWithText("Operations").performClick()
        assertEquals(1, openedOperations)
    }

    @Test fun `completed state survives removal from queue history and resets for another save`() {
        operation = item("SUCCEEDED")
        show()
        compose.onNodeWithText("Clip saved").assertIsDisplayed()
        compose.runOnIdle { operation = null }
        compose.onNodeWithText("Clip saved").assertIsDisplayed()
        compose.runOnIdle { operationId = "another-save" }
        compose.onNodeWithText("Clip saved").assertDoesNotExist()
        compose.onNodeWithText("Clip queued").assertIsDisplayed()
    }

    @Test fun `cancelled or interrupted operations never look like saved clips`() {
        operation = item("CANCELLED", detail = "Cancelled by user")
        show()
        compose.onNodeWithText("Cancelled by user").assertIsDisplayed()
        compose.onNodeWithText("Clip saved").assertDoesNotExist()
        compose.onNodeWithTag("clipSavingProgress").assertDoesNotExist()
        compose.runOnIdle { operation = item("INTERRUPTED", detail = "Background execution stopped") }
        compose.onNodeWithText("Background execution stopped").assertIsDisplayed()
        compose.onNodeWithText("Clip saved").assertDoesNotExist()
        compose.onNodeWithText("Operations").assertIsDisplayed()
    }
}
