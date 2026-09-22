package com.lunaexplorer.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.data.SettingsRegistry
import com.lunaexplorer.app.data.TransferCodec
import com.lunaexplorer.app.data.TransferSource
import com.lunaexplorer.app.model.Preferences
import com.lunaexplorer.app.model.ThemeMode
import com.lunaexplorer.app.storage.smb.SmbAccount
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class, qualifiers = "w393dp-h852dp-xhdpi")
class SettingsImportAuthenticationTest {
    private val harness = BrowserViewModelHarness()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(harness).around(compose)
    private val viewModel get() = harness.viewModel
    private val state get() = harness.state
    private val server = SmbAccount(id = "imported", name = "Imported", host = "server.test")
    private val password = " imported password "
    private var prompts = 0
    private var pendingAuthentication: ((Boolean) -> Unit)? = null
    private val actions = LunaActions({}, { _, _ -> }, { _, _, _ -> }, {}, {}, {},
        unlockVault = { done -> prompts++; pendingAuthentication = done })

    private fun showImport(source: TransferSource, vararg ids: String) {
        assertTrue(harness.awaitUntil { state.ready })
        val document = TransferCodec.export(source, ids.toSet(), "test", 0)
        val file = File(harness.directory, "settings.json").apply { writeText(TransferCodec.encode(document)) }
        var opened = false
        viewModel.transfer.openForImport(Uri.fromFile(file)) { opened = it }
        assertTrue(harness.awaitUntil { opened && viewModel.transfer.preview.value != null })
        compose.setContent {
            MaterialTheme {
                val current by viewModel.state.collectAsState()
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SettingsTransferPage(current, viewModel, actions)
                }
            }
        }
    }

    private fun import(count: Int) {
        compose.onNodeWithText("Import $count").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun authenticate(allowed: Boolean) {
        compose.runOnIdle {
            harness.vaultKeys.personPresent = allowed
            val callback = requireNotNull(pendingAuthentication)
            pendingAuthentication = null
            callback(allowed)
        }
    }

    private fun awaitImported() {
        assertTrue(harness.awaitUntil { viewModel.transfer.preview.value == null })
        compose.waitForIdle()
    }

    private fun protectedCredentials(): TransferSource = TransferSource(
        preferences = Preferences(theme = ThemeMode.DARK),
        smbAccounts = listOf(server), passwords = mapOf(server.id to password), vaultLocked = true,
    )

    private fun assertFreshInstallation() {
        assertTrue(state.smbAccounts.isEmpty())
        assertTrue(harness.graph.vault.secrets.value?.smbPasswords.isNullOrEmpty())
        assertFalse(state.vaultLocked)
        assertNotEquals(ThemeMode.DARK, state.preferences.theme)
        assertNotNull(viewModel.transfer.preview.value)
    }

    private fun assertProtectedRoundtrip(passwords: Map<String, String> = mapOf(server.id to password)) {
        assertTrue(state.vaultLocked)
        viewModel.vault.close()
        harness.vaultKeys.personPresent = false
        assertFalse(viewModel.vault.open())
        harness.vaultKeys.personPresent = true
        assertTrue(viewModel.vault.open())
        assertEquals(passwords, harness.graph.vault.secrets.value?.smbPasswords)
    }

    @Test fun `a fresh installation authenticates before importing credentials and enabling protection`() {
        harness.vaultKeys.personPresent = false
        showImport(protectedCredentials(), "network.smb", "network.smbPasswords", "network.vaultLocked", "appearance.theme")

        import(4)

        assertEquals(1, prompts)
        assertFreshInstallation()
        authenticate(true)
        awaitImported()

        assertEquals(listOf(server), state.smbAccounts)
        assertEquals(ThemeMode.DARK, state.preferences.theme)
        assertProtectedRoundtrip()
    }

    @Test fun `declined authentication leaves the import unchanged and allows retry`() {
        harness.vaultKeys.personPresent = false
        showImport(protectedCredentials(), "network.smb", "network.smbPasswords", "network.vaultLocked", "appearance.theme")
        import(4)
        assertEquals(1, prompts)

        authenticate(false)
        compose.waitForIdle()

        assertFreshInstallation()
        compose.onNodeWithText("Import 4").assertIsEnabled()
        import(4)
        assertEquals(2, prompts)
        assertFreshInstallation()
        authenticate(true)
        awaitImported()
        assertProtectedRoundtrip()
    }

    @Test fun `enabling protection authenticates even when no credentials are imported`() {
        harness.vaultKeys.personPresent = false
        showImport(TransferSource(vaultLocked = true), "network.vaultLocked")

        import(1)

        assertEquals(1, prompts)
        assertFalse(state.vaultLocked)
        assertNotNull(viewModel.transfer.preview.value)
        authenticate(true)
        awaitImported()
        assertProtectedRoundtrip(emptyMap())
    }

    @Test fun `disabling protection authenticates before rewriting an open vault`() {
        assertTrue(harness.awaitUntil { state.ready })
        assertNull(viewModel.smb.save(server, password))
        assertTrue(viewModel.vault.setLocked(true))
        showImport(TransferSource(vaultLocked = false), "network.vaultLocked")

        import(1)

        assertEquals(1, prompts)
        assertTrue(state.vaultLocked)
        assertNotNull(viewModel.transfer.preview.value)
        authenticate(true)
        awaitImported()
        assertFalse(state.vaultLocked)
        viewModel.vault.close()
        harness.vaultKeys.personPresent = false
        assertTrue(viewModel.vault.open())
        assertEquals(mapOf(server.id to password), harness.graph.vault.secrets.value?.smbPasswords)
    }

    @Test fun `importing credentials renews expired authentication for an open protected vault`() {
        assertTrue(harness.awaitUntil { state.ready })
        assertNull(viewModel.smb.save(server, "kept password"))
        assertTrue(viewModel.vault.setLocked(true))
        harness.vaultKeys.personPresent = false
        showImport(protectedCredentials(), "network.smbPasswords")

        import(1)

        assertEquals(1, prompts)
        assertEquals("kept password", harness.graph.vault.secrets.value?.smbPasswords?.get(server.id))
        assertNotNull(viewModel.transfer.preview.value)
        authenticate(true)
        awaitImported()
        assertProtectedRoundtrip()
    }

    @Test fun `ordinary settings import without authenticating an expired protected vault`() {
        assertTrue(harness.awaitUntil { state.ready })
        assertTrue(viewModel.vault.setLocked(true))
        harness.vaultKeys.personPresent = false
        showImport(TransferSource(preferences = Preferences(theme = ThemeMode.DARK)), "appearance.theme")

        import(1)
        awaitImported()

        assertEquals(0, prompts)
        assertEquals(ThemeMode.DARK, state.preferences.theme)
        assertTrue(state.vaultLocked)
    }

    @Test fun `deselected protection and credentials do not require authentication`() {
        harness.vaultKeys.personPresent = false
        showImport(protectedCredentials(), "network.smb", "network.smbPasswords", "network.vaultLocked", "appearance.theme")
        compose.onNodeWithContentDescription("Network").performScrollTo().performClick()

        import(1)
        awaitImported()

        assertEquals(0, prompts)
        assertEquals(ThemeMode.DARK, state.preferences.theme)
        assertFalse(state.vaultLocked)
        assertTrue(state.smbAccounts.isEmpty())
        assertTrue(harness.graph.vault.secrets.value?.smbPasswords.isNullOrEmpty())
    }

    @Test fun `a device without a screen lock imports other settings and reports refused protection`() {
        harness.vaultKeys.screenLock = false
        harness.vaultKeys.personPresent = false
        showImport(TransferSource(preferences = Preferences(theme = ThemeMode.DARK), vaultLocked = true),
            "appearance.theme", "network.vaultLocked")

        import(2)
        awaitImported()

        assertEquals(0, prompts)
        assertEquals(ThemeMode.DARK, state.preferences.theme)
        assertFalse(state.vaultLocked)
        val label = requireNotNull(SettingsRegistry.unit("network.vaultLocked")).label
        assertTrue(state.message.orEmpty().contains(label))
    }

    @Test fun `authentication completing after the preview is dropped cannot apply the import`() {
        harness.vaultKeys.personPresent = false
        showImport(protectedCredentials(), "network.smb", "network.smbPasswords", "network.vaultLocked", "appearance.theme")
        import(4)
        assertEquals(1, prompts)
        compose.runOnIdle { viewModel.transfer.dropImport() }
        compose.waitForIdle()

        authenticate(true)
        compose.waitForIdle()
        harness.idle()

        assertNull(viewModel.transfer.preview.value)
        assertTrue(state.smbAccounts.isEmpty())
        assertTrue(harness.graph.vault.secrets.value?.smbPasswords.isNullOrEmpty())
        assertFalse(state.vaultLocked)
        assertNotEquals(ThemeMode.DARK, state.preferences.theme)
    }
}
