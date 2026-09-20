package com.lunaexplorer.app.ui

import android.content.ClipboardManager
import android.text.Selection
import android.widget.TextView
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.debug.DebugLog
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class DebugLogUiTest : RobolectricBrowserUiTest() {

    @Test
    fun theDebugLogIsRecordedFollowedCopiedAndSaved() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        val folder = requireNotNull(viewModel.state.value.directoryPath)
        openSettingsPage("Debug log")

        compose.onNodeWithContentDescription("Record debug log").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.debugLog.enabled }
        compose.onNodeWithText("View log").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); compose.onAllNodesWithContentDescription("Copy all").fetchSemanticsNodes().isNotEmpty() }

        viewModel.debugLog.log(DebugLog.Level.INFO, "Test", "marker-line-4242")
        fun logView(): TextView? = ShadowDialog.getShownDialogs().firstNotNullOfOrNull { dialog ->
            dialog.window?.decorView?.findViewWithTag<TextView>(LOG_TEXT_TAG)
        }
        fun shown(): String = compose.runOnUiThread { logView()?.text?.toString().orEmpty() }
        compose.waitUntil(10_000) { compose.waitForIdle(); shown().contains("marker-line-4242") }

        fun selecting(on: Boolean) = compose.runOnUiThread {
            val text = requireNotNull(logView()?.editableText)
            if (on) Selection.setSelection(text, 0, 10) else Selection.removeSelection(text)
        }
        selecting(true)
        viewModel.debugLog.log(DebugLog.Level.INFO, "Test", "held-line-5151")
        val appearedWhileSelecting = runCatching {
            compose.waitUntil(1_500) { compose.waitForIdle(); shown().contains("held-line-5151") }
        }.isSuccess
        assertFalse("A line was appended under a selection, which ends the selection", appearedWhileSelecting)
        selecting(false)
        compose.waitUntil(10_000) { compose.waitForIdle(); shown().contains("held-line-5151") }

        compose.onNodeWithContentDescription("Copy all").performClick()
        val clipboard = compose.activity.application.getSystemService(ClipboardManager::class.java)
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            clipboard.primaryClip?.getItemAt(0)?.text?.contains("marker-line-4242") == true
        }

        compose.onNodeWithContentDescription("Save").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); compose.onAllNodesWithText("Save log").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(viewModel.defaultLogFolder()).assertExists()
        compose.onNodeWithText("Current folder").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            File(folder).listFiles().orEmpty().any { it.name.startsWith("luna-log-") && it.readText().contains("marker-line-4242") }
        }
    }
}
