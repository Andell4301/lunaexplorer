package com.lunaexplorer.app.ui

import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.Overlay
import com.lunaexplorer.app.model.ViewerKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class EditorUiTest : RobolectricBrowserUiTest() {
    private val viewModel get() = compose.runOnUiThread {
        ViewModelProvider(compose.activity)[BrowserViewModel::class.java]
    }
    private val file get() = File(fixture.directory, "beta.txt")
    private fun editor() = compose.onNodeWithTag("editorText")

    private fun openEditor() {
        awaitListing()
        val model = viewModel
        val entry = model.state.value.entries.first { it.name == "beta.txt" }
        compose.runOnUiThread { model.showOverlay(Overlay.Viewer(entry, ViewerKind.TEXT)) }
        awaitEditor()
    }

    private fun awaitEditor() = awaitCondition("Editable text has loaded", 10_000) {
        compose.onAllNodes(hasTestTag("editorText") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitClosed() = awaitCondition("Editor closed", 10_000) {
        viewModel.state.value.overlay == null && compose.onAllNodesWithTag("editorText").fetchSemanticsNodes().isEmpty()
    }

    @Test fun toolbarCloseAndAndroidBackAskBeforeDiscardingTheDraft() {
        openEditor()
        val original = file.readBytes()
        val draft = "Unsaved draft 🌓\n"
        // The editor owns a dialog dispatcher; Activity Back would exercise the wrong window.
        val editorWindow = ShadowDialog.getLatestDialog() as OnBackPressedDispatcherOwner
        editor().performTextReplacement(draft)
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Save changes?").assertIsDisplayed()
        listOf("Save", "Discard", "Cancel").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Cancel").performClick()
        editor().assertTextEquals(draft)
        assertArrayEquals(original, file.readBytes())

        compose.runOnUiThread { editorWindow.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Save changes?").assertIsDisplayed()
        compose.onNodeWithText("Discard").performClick()
        awaitClosed()
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun toolbarSaveThenSaveAndExitUsesTheNewFileIdentity() {
        openEditor()
        val first = "First saved revision\n"
        editor().performTextReplacement(first)
        compose.onNodeWithContentDescription("Save").performClick()
        awaitCondition("First save finished", 10_000) {
            file.readText() == first &&
                compose.onAllNodesWithText("Unsaved changes").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Saving…").fetchSemanticsNodes().isEmpty()
        }
        // A save publishes a new file; the next save must replace that one, not report a conflict
        // against the entry the viewer was opened with.
        val second = "Second saved revision — 日本語 🌓\n"
        editor().performTextReplacement(second)
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Save").performClick()
        awaitClosed()
        assertArrayEquals(second.toByteArray(Charsets.UTF_8), file.readBytes())
    }

    @Test fun failedSaveKeepsThePromptAndDraftWithoutOverwritingAReplacementFile() {
        openEditor()
        val draft = "My draft is still here 🌓\n"
        editor().performTextReplacement(draft)
        val external = "A different file now occupies the same path\n"
        val replacement = File(fixture.directory, "replacement.txt").apply { writeText(external) }
        Files.move(replacement.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)

        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Save").performClick()
        awaitText("The item being replaced changed; refresh and try again")
        compose.onNodeWithText("Save changes?").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        editor().assertTextEquals(draft)
        assertEquals(external, file.readText())
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Discard").performClick()
        awaitClosed()
    }

    @Test fun activityRecreationKeepsTheUnsavedDraftUntilTheUserSaves() {
        openEditor()
        val original = file.readBytes()
        val draft = "This draft survives rotation — 日本語 🌓\n"
        editor().performTextReplacement(draft)
        compose.activityRule.scenario.recreate()
        awaitEditor()
        editor().assertTextEquals(draft)
        compose.onNodeWithText("Unsaved changes").assertIsDisplayed()
        assertArrayEquals(original, file.readBytes())
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Save changes?").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        awaitClosed()
        assertArrayEquals(draft.toByteArray(Charsets.UTF_8), file.readBytes())
    }

    @Test fun codeEditorOpensFromTheChooserAndLanguageChangesPreserveSavedUnicode() {
        awaitListing()
        val model = viewModel
        val entry = model.state.value.entries.first { it.name == "beta.txt" }
        compose.runOnUiThread { model.showOverlay(Overlay.Opening(entry)) }
        awaitText("Code editor")
        compose.onNodeWithText("Code editor").performScrollTo().performClick()
        awaitEditor()
        assertEquals(ViewerKind.CODE, (model.state.value.overlay as Overlay.Viewer).kind)

        compose.onNodeWithText("Language: Plain text").performClick()
        compose.onNodeWithText("Python").performScrollTo().performClick()
        val source = "# 🌓 日本語 e\u0301\u200B\r\nprint(\"Luna\")\r\n"
        editor().performTextReplacement(source)
        compose.onNodeWithText("Language: Python").performClick()
        compose.onNodeWithText("Rust").performScrollTo().performClick()
        compose.onNodeWithText("Language: Rust").assertIsDisplayed()
        editor().assertTextEquals(source)
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Save").performClick()
        awaitClosed()
        assertArrayEquals(source.toByteArray(Charsets.UTF_8), file.readBytes())
    }
}
