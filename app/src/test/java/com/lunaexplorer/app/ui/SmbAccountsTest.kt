package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.storage.smb.SmbAccount
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class SmbAccountsTest {
    private val original = SmbAccount(id = "nas", name = "NAS", host = "old-host")
    @get:Rule val harness = BrowserViewModelHarness().withSession { it.copy(smbAccounts = listOf(original)) }

    @Test fun `a failed password save restores the existing account and its secret`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        assertNull(harness.viewModel.smb.save(original, "old-password"))
        assertTrue(harness.viewModel.vault.setLocked(true))
        harness.vaultKeys.personPresent = false

        val failure = harness.viewModel.smb.save(original.copy(host = "new-host"), "new-password")

        assertNotNull(failure)
        assertEquals(listOf(original), harness.state.smbAccounts)
        assertEquals("old-password", harness.graph.vault.secrets.value!!.smbPasswords[original.id])
    }
}
