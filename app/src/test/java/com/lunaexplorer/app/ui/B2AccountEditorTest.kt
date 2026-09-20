package com.lunaexplorer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.storage.b2.B2Account
import com.lunaexplorer.app.storage.b2.FakeB2Backend
import com.lunaexplorer.app.storage.b2.FakeB2Connector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class B2AccountEditorTest {
    private val held = B2Account(id = "cloud", name = "Cloud", keyId = "0012ab", bucket = "media")
    private val harness = BrowserViewModelHarness().withB2(FakeB2Connector(FakeB2Backend("media", "archive")))
        .withSession { it.copy(b2Accounts = listOf(held)) }
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(harness).around(compose)
    private var open by mutableStateOf(true)
    private var prompts = 0
    private var prove = true
    private val actions = LunaActions({}, { _, _ -> }, { _, _, _ -> }, {}, {}, {},
        unlockVault = { done -> prompts++; if (prove) harness.vaultKeys.personPresent = true; done(prove) })

    private fun edit(account: B2Account, isNew: Boolean) {
        assertTrue(harness.awaitUntil { harness.state.ready })
        compose.setContent {
            MaterialTheme { if (open) B2AccountEditor(account, isNew, harness.viewModel, actions) { open = false } }
        }
    }

    @Test fun `a new account cannot be saved without both halves of its key`() {
        edit(B2Account(name = "", keyId = ""), isNew = true)
        compose.onNodeWithText("Save").assertIsNotEnabled()

        compose.onNodeWithText("Key ID").performTextInput("0012ab")
        compose.onNodeWithText("Save").assertIsNotEnabled()

        compose.onNodeWithText("Application key").performTextInput("K001secret")
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    private fun typeNewAccount() {
        edit(B2Account(id = "fresh", name = "", keyId = ""), isNew = true)
        compose.onNodeWithText("Key ID").performTextInput("0034cd")
        compose.onNodeWithText("Application key").performTextInput("K001secret")
    }

    /** The vault sits behind authentication and was opened a while ago, so its key no longer answers. */
    private fun lockedVaultOpenedLongAgo() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        assertTrue(harness.viewModel.vault.setLocked(true))
        harness.vaultKeys.personPresent = false
    }

    @Test fun `saving asks for authentication again when the open vault's key has stopped answering`() {
        lockedVaultOpenedLongAgo()
        typeNewAccount()

        compose.onNodeWithText("Save").performClick()

        assertTrue("The editor closes once the key is stored", harness.awaitUntil { !open })
        assertEquals(1, prompts)
        assertEquals("K001secret", harness.graph.vault.secrets.value?.b2Keys?.get("fresh"))
        assertTrue(harness.state.b2Accounts.any { it.id == "fresh" })
    }

    @Test fun `a save that cannot store the key says so in the editor and keeps nothing`() {
        lockedVaultOpenedLongAgo()
        prove = false
        typeNewAccount()

        compose.onNodeWithText("Save").performClick()

        compose.onNodeWithTag("editorResult").assertIsDisplayed()
        assertTrue(open)
        assertTrue(harness.state.b2Accounts.none { it.id == "fresh" })
    }

    @Test fun `an account is edited without typing its key again, and tested with the one it has`() {
        edit(held, isNew = false)

        compose.onNodeWithText("Test connection").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Connected", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("media").performTextReplacement("archive")
        compose.onNodeWithText("Save").performClick()

        assertTrue(harness.awaitUntil { !open })
        assertEquals("archive", harness.state.b2Accounts.single().bucket)
        assertTrue("The bucket is the root now", harness.awaitUntil { harness.state.roots.any { it.description == "b2://archive" } })
    }
}
