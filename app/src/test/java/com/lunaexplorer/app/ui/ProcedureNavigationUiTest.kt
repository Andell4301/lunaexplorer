package com.lunaexplorer.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.Screen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class, qualifiers = "w393dp-h852dp-xhdpi")
class ProcedureNavigationUiTest : RobolectricBrowserUiTest() {
    @Test
    fun homeOpensProceduresAndBackRetracesTheEditorBeforeLeaving() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        compose.runOnUiThread { viewModel.showScreen(Screen.HOME) }
        compose.onNodeWithTag("home-screen").performScrollToNode(hasText("Stored procedures"))
        compose.onNode(hasText("Stored procedures") and hasAnyAncestor(hasTestTag("home-screen"))).performClick()
        compose.onNodeWithTag("procedure-screen").assertExists()

        compose.onNodeWithText("Add procedure").performClick()
        compose.onNodeWithText("Add step").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Browse Source 1").performScrollTo().performClick()
        compose.onNodeWithTag("procedure-locations").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Save step").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Save procedure").assertExists()
        assertEquals(Screen.PROCEDURES, viewModel.state.value.screen)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Add procedure").assertExists()
        assertEquals(Screen.PROCEDURES, viewModel.state.value.screen)
        compose.onNodeWithContentDescription("Back").performClick()
        awaitCondition("Back returns home", 10_000) { viewModel.state.value.screen == Screen.HOME }
    }

    @Test
    fun sidebarOpensProceduresWithoutSettings() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        compose.onNodeWithContentDescription("Locations").performClick()
        val drawer = SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)
        compose.onNode(hasScrollAction() and hasAnyAncestor(drawer))
            .performScrollToNode(hasText("Stored procedures"))
        compose.onNode(hasText("Stored procedures") and hasAnyAncestor(drawer)).performClick()
        compose.onNodeWithTag("procedure-screen").assertExists()
        assertEquals(Screen.PROCEDURES, viewModel.state.value.screen)
        assertEquals(null, viewModel.state.value.overlay)
    }
}
