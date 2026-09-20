package com.lunaexplorer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchiveDialogsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `the unlock prompt sends the typed password, waits while verifying and shows a failure`() {
        var error by mutableStateOf<String?>(null)
        var verifying by mutableStateOf(false)
        var sent: String? = null
        compose.setContent {
            MaterialTheme {
                ArchivePasswordDialog("locked.zip", error, verifying, onUnlock = { sent = it }, onDismiss = {})
            }
        }
        compose.onNodeWithText("locked.zip").assertExists()
        compose.onNodeWithText("Unlock").assertIsNotEnabled()
        compose.onNodeWithText("Password").performTextInput("hunter2")
        compose.onNodeWithText("Unlock").assertIsEnabled().performClick()
        assertEquals("hunter2", sent)

        verifying = true
        compose.waitForIdle()
        compose.onNodeWithText("Unlock").assertIsNotEnabled()

        verifying = false
        error = "Wrong password"
        compose.waitForIdle()
        compose.onNodeWithText("Wrong password").assertExists()
        compose.onNodeWithText("Unlock").assertIsEnabled()
    }

    @Test fun `extracting a whole archive passes the folder and the password typed`() {
        val archive = Entry(NodeRef("local", "device:locked.7z"), "locked.7z", directory = false,
            capabilities = setOf(Capability.READ))
        var into: String? = "unset"
        var password: String? = null
        var here: Boolean? = null
        compose.setContent {
            MaterialTheme {
                ExtractDialog(archive, onDismiss = {}) { folder, typed, inPlace ->
                    into = folder; password = typed; here = inPlace
                }
            }
        }
        compose.onNodeWithText("Password").performTextInput("Secret1")
        compose.onNodeWithText("Extract here").performClick()
        assertEquals("locked", into)
        assertEquals("Secret1", password)
        assertEquals("Extracting here unpacks into the folder on screen", true, here)

        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Extract here").performClick()
        assertNull("Extracting in place passes no folder", into)
        assertEquals("Secret1", password)
    }

    @Test fun `choosing a folder carries the archive instead of unpacking it here`() {
        val archive = Entry(NodeRef("local", "device:bundle.zip"), "bundle.zip", directory = false,
            capabilities = setOf(Capability.READ))
        var into: String? = "unset"
        var here: Boolean? = null
        compose.setContent {
            MaterialTheme {
                ExtractDialog(archive, onDismiss = {}) { folder, _, inPlace -> into = folder; here = inPlace }
            }
        }
        compose.onNodeWithText("Choose folder").performClick()
        assertEquals("The folder name still travels with it", "bundle", into)
        assertEquals("But it is not unpacked where it sits", false, here)
    }

    @Test fun `a format that cannot be encrypted offers no password field`() {
        val archive = Entry(NodeRef("local", "device:bundle.tar.gz"), "bundle.tar.gz", directory = false,
            capabilities = setOf(Capability.READ))
        var password: String? = null
        compose.setContent {
            MaterialTheme {
                ExtractDialog(archive, onDismiss = {}) { _, typed, _ -> password = typed }
            }
        }
        compose.onNodeWithText("Password").assertDoesNotExist()
        compose.onNodeWithText("Extract here").performClick()
        assertEquals("Tars carry no password for the engine to use", "", password)
    }
}
