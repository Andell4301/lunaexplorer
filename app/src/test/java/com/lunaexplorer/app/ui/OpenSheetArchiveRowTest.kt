package com.lunaexplorer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.Screen
import com.lunaexplorer.app.model.ViewerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class OpenSheetArchiveRowTest {
    private val harness = BrowserViewModelHarness().startingWith { TestArchives.writeTar(File(it, "bundle.tar")) }
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(harness).around(compose)

    @Test fun `a tar file gets the archive browser row, and choosing it asks to browse`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        harness.viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { harness.state.entries.size == 1 })
        val entry = harness.state.entries.single()
        var chosen: ViewerKind? = null
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalArchives provides harness.graph.archives) {
                    OpenSheet(entry, harness.viewModel, onInternal = { chosen = it }, onExternal = { _, _ -> }, onDismiss = {})
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("Archive browser").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Archive browser").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(ViewerKind.NONE, chosen)
    }
}
