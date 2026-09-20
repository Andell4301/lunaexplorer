package com.lunaexplorer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class RenameDialogTest {
    @get:Rule val compose = createComposeRule()

    private var saved by mutableStateOf(false)
    private var confirmed: String? = null
    private var remembered: Boolean? = null

    private fun show(name: String, directory: Boolean = false) {
        compose.setContent {
            MaterialTheme {
                RenameDialog(
                    entry = Entry(NodeRef("test", name), name, directory = directory),
                    includeExtension = saved,
                    onRemember = { remembered = it; saved = it },
                    onDismiss = {},
                    onConfirm = { confirmed = it },
                )
            }
        }
    }

    private fun field() = compose.onNode(hasSetTextAction())
    // The dialog title is also "Rename".
    private fun confirm() = compose.onNode(hasText("Rename") and hasClickAction())
    private fun typed(): String = field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun suffixShown(suffix: String): Boolean =
        compose.onAllNodes(hasText(suffix)).fetchSemanticsNodes().isNotEmpty()

    @Test fun `the extension waits outside the box and rides along on confirm`() {
        show("myfile.zip")
        assertEquals("myfile", typed())
        assertTrue("The extension is shown next to the name", suffixShown(".zip"))
        confirm().assertIsNotEnabled()

        field().performTextReplacement("renamed")
        confirm().assertIsEnabled().performClick()
        assertEquals("renamed.zip", confirmed)
    }

    @Test fun `including the extension moves it into the box and back out again with edits kept`() {
        show("myfile.zip")
        compose.onNodeWithText("Include extension").performClick()
        assertEquals("myfile.zip", typed())
        assertTrue("Nothing is left hanging outside the box", !suffixShown(".zip"))

        field().performTextReplacement("myfile.tar.gz")
        compose.onNodeWithText("Include extension").performClick()
        assertEquals("myfile.tar", typed())
        assertTrue(suffixShown(".gz"))
        confirm().performClick()
        assertEquals("myfile.tar.gz", confirmed)
    }

    @Test fun `remember saves the checkbox and settles once the saved value matches`() {
        show("myfile.zip")
        compose.onNodeWithText("Remember").assertIsNotEnabled()
        compose.onNodeWithText("Include extension").performClick()
        compose.onNodeWithText("Remember").assertIsEnabled().performClick()
        assertEquals(true, remembered)
        compose.onNodeWithText("Remember").assertIsNotEnabled()
        compose.onNodeWithText("Include extension").performClick()
        compose.onNodeWithText("Remember").assertIsEnabled()
    }

    @Test fun `a saved preference starts the dialog with the whole name in the box`() {
        saved = true
        show("myfile.zip")
        assertEquals("myfile.zip", typed())
        assertTrue(!suffixShown(".zip"))
        field().performTextReplacement("other.txt")
        confirm().performClick()
        assertEquals("other.txt", confirmed)
    }

    @Test fun `folders and names without an extension have nothing to set aside`() {
        show("My.Documents", directory = true)
        assertEquals("My.Documents", typed())
        compose.onNodeWithText("Include extension").assertDoesNotExist()
        compose.onNodeWithText("Remember").assertDoesNotExist()
        field().performTextReplacement("Archive")
        confirm().performClick()
        assertEquals("Archive", confirmed)
    }

    @Test fun `a dotfile is one name, not an empty base with an extension`() {
        show(".bashrc")
        assertEquals(".bashrc", typed())
        compose.onNodeWithText("Include extension").assertDoesNotExist()
        assertNull(confirmed)
    }

    @Test fun `a name the storage would refuse cannot be confirmed`() {
        show("myfile.zip")
        field().performTextReplacement("bad/name")
        confirm().assertIsNotEnabled()
        field().performTextReplacement("")
        confirm().assertIsNotEnabled()
        assertNull(confirmed)
    }
}
