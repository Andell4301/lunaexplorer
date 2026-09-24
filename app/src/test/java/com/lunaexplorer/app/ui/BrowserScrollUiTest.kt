package com.lunaexplorer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.Screen
import com.lunaexplorer.app.model.ViewMode
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
@Config(sdk = [35], application = LunaApplication::class, qualifiers = "w393dp-h852dp-xhdpi")
class BrowserScrollUiTest {
    private val harness = BrowserViewModelHarness().startingWith { directory ->
        repeat(80) { File(directory, "folder-${it.toString().padStart(2, '0')}").mkdir() }
        File(directory, "folder-40/child.txt").writeText("Child listing")
    }.withSession {
        it.copy(screen = Screen.BROWSER, preferences = it.preferences.copy(thumbnails = false, view = ViewMode.GRID_MEDIUM,
            startScreen = Screen.BROWSER))
    }
    private val compose = createComposeRule()
    private val restoration = StateRestorationTester(compose)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(harness).around(compose)
    private val viewModel get() = harness.viewModel
    private val state get() = harness.state
    private val actions = LunaActions(grantFolder = {}, open = { _, _ -> }, openWith = { _, _, _ -> },
        shareReport = {}, requestFullAccess = {}, openSystemBrowser = {})

    private fun show(mode: ViewMode = ViewMode.GRID_MEDIUM) {
        assertTrue(harness.awaitUntil { state.ready && !state.loading && state.entries.size == 80 })
        viewModel.setPreferences(state.preferences.copy(view = mode))
        restoration.setContent {
            MaterialTheme {
                val current by viewModel.state.collectAsState()
                BrowserContent(current, viewModel, actions, onOpen = {}, onPackage = {})
            }
        }
    }

    private fun scrollTo(name: String): Float {
        val index = state.entries.indexOfFirst { it.name == name }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
        compose.onNode(hasScrollToIndexAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 37f) }
        return compose.onNodeWithText(name).getUnclippedBoundsInRoot().top.value
    }

    private fun roundTrip(name: String, mode: ViewMode = ViewMode.GRID_MEDIUM, restore: Boolean = false) {
        show(mode)
        val parent = state.listingRef
        val top = scrollTo(name)
        val child = state.entries.single { it.name == name }.ref
        compose.onNodeWithText(name).performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); state.listingRef == child && !state.loading }
        if (restore) restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { viewModel.back() }
        compose.waitUntil(10_000) { compose.waitForIdle(); state.listingRef == parent && !state.loading }
        compose.onNodeWithText(name).assertIsDisplayed()
        assertEquals(top, compose.onNodeWithText(name).getUnclippedBoundsInRoot().top.value, 0.5f)
    }

    @Test fun `grid returns to the same offset after visiting a short child listing`() = roundTrip("folder-40")

    @Test fun `grid returns to the same offset after visiting an empty folder`() = roundTrip("folder-42")

    @Test fun `list returns to the same offset after visiting an empty folder`() = roundTrip("folder-42", ViewMode.LIST)

    @Test fun `a parent folder position survives content recreation in its child`() = roundTrip("folder-40", restore = true)

    @Test fun `tabs keep independent scroll positions for the same folder`() {
        show()
        val original = state.activeTabId
        val firstTop = scrollTo("folder-40")
        compose.runOnIdle { viewModel.newTab() }
        compose.waitUntil(10_000) { compose.waitForIdle(); state.activeTabId != original && !state.loading }
        val second = state.activeTabId
        compose.onNodeWithText("folder-00").assertIsDisplayed()
        val secondTop = scrollTo("folder-60")
        compose.runOnIdle { viewModel.selectTab(original) }
        compose.waitUntil(10_000) { compose.waitForIdle(); state.activeTabId == original && !state.loading }
        compose.onNodeWithText("folder-40").assertIsDisplayed()
        assertEquals(firstTop, compose.onNodeWithText("folder-40").getUnclippedBoundsInRoot().top.value, 0.5f)
        compose.runOnIdle { viewModel.selectTab(second) }
        compose.waitUntil(10_000) { compose.waitForIdle(); state.activeTabId == second && !state.loading }
        compose.onNodeWithText("folder-60").assertIsDisplayed()
        assertEquals(secondTop, compose.onNodeWithText("folder-60").getUnclippedBoundsInRoot().top.value, 0.5f)
    }
}
