package com.lunaexplorer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.storage.smb.SmbAccount
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class ExportTreeRowsTest {
    private val harness = BrowserViewModelHarness()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(harness).around(compose)

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    @Test fun `every setting the Network page counts is a row you can see`() {
        assertTrue(harness.awaitUntil { state.ready })
        viewModel.smb.save(SmbAccount(id = "s1", name = "NAS", host = "nas.test"), password = null)
        assertTrue(harness.awaitUntil { state.smbAccounts.isNotEmpty() })

        compose.setContent {
            MaterialTheme {
                val current by viewModel.state.collectAsState()
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsTransferPage(current, viewModel, LunaActions(
                        grantFolder = {}, open = { _, _ -> }, openWith = { _, _, _ -> },
                        shareReport = {}, requestFullAccess = {}, openSystemBrowser = {},
                    ))
                }
            }
        }
        compose.onNodeWithText("Export").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Network").performScrollTo().performClick()
        compose.waitForIdle()

        listOf("SMB servers", "Stored passwords", "SMB thumbnails", "B2 thumbnails", "Deleting on B2",
            "Keep the vault behind authentication").forEach {
            compose.onNodeWithText(it).performScrollTo()
                .assertExists("\"$it\" is counted by the page but never drawn")
        }
    }
}
