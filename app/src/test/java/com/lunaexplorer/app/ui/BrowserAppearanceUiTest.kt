package com.lunaexplorer.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.*
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class BrowserAppearanceUiTest : RobolectricBrowserUiTest() {

    // A density change must not restart the active pinch handler or trigger pull-to-refresh.
    @Test
    fun aPinchKeepsResizingPastTheFirstStep() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        assertEquals(1f, viewModel.state.value.preferences.uiScale)

        // The largest root is the window's; tooltips and menus have roots of their own.
        val roots = compose.onAllNodes(isRoot())
        val sizes = roots.fetchSemanticsNodes().map { it.size.width.toLong() * it.size.height }
        roots[sizes.indices.maxBy { sizes[it] }].performTouchInput {
            val reach = Offset(width * 0.44f, 0f)
            val start = Offset(width * 0.05f, 0f)
            pinch(start0 = center - start, end0 = center - reach, start1 = center + start, end1 = center + reach, durationMillis = 600)
        }
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.preferences.uiScale >= 1.5f }
        assertTrue("A pinch this wide reaches the top of the range, not one step past where it began",
            viewModel.state.value.preferences.uiScale >= 1.5f)
    }

    @Test
    fun aPinchShowsTheSizeItHasReachedUntilTheFingersLift() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        val roots = compose.onAllNodes(isRoot())
        val sizes = roots.fetchSemanticsNodes().map { it.size.width.toLong() * it.size.height }
        val window = roots[sizes.indices.maxBy { sizes[it] }]

        window.performTouchInput {
            down(0, center - Offset(width * 0.05f, 0f))
            down(1, center + Offset(width * 0.05f, 0f))
            repeat(6) { step ->
                val reach = Offset(width * (0.05f + 0.03f * (step + 1)), 0f)
                updatePointerTo(0, center - reach)
                updatePointerTo(1, center + reach)
                move()
            }
        }
        compose.waitForIdle()
        val scale = viewModel.state.value.preferences.uiScale
        assertTrue("The pinch must have resized something", scale > 1f)
        val label = "${(scale * 100).roundToInt()}%"
        compose.onNodeWithText(label).assertIsDisplayed()

        window.performTouchInput { up(0); up(1) }
        compose.waitUntil(10_000) { compose.waitForIdle(); compose.onAllNodesWithText(label).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aColorfulIconStillSelectsAndTheSwitchTurnsThemOff() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        assertTrue("Colorful icons arrive switched on", viewModel.state.value.preferences.colorfulIcons)

        compose.onNodeWithContentDescription("Select beta.txt").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.selectedEntries.singleOrNull()?.name == "beta.txt" }
        compose.runOnUiThread { viewModel.clearSelection() }

        openSettingsPage("Appearance")
        compose.onNodeWithText("Colorful icons").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); !viewModel.state.value.preferences.colorfulIcons }
    }

    @Test
    fun aSizeChangeDoesNotThrowTheSettingsScreenAway() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        openSettingsPage("Appearance")
        val scroller = hasScrollAction() and hasAnyAncestor(isDialog())
        compose.waitUntil(10_000) { compose.waitForIdle(); compose.onAllNodes(scroller).fetchSemanticsNodes().isNotEmpty() }
        fun scrolled(): Float =
            compose.onAllNodes(scroller).onFirst().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

        compose.onAllNodes(scroller).onFirst().performTouchInput { swipeUp() }
        compose.waitUntil(10_000) { compose.waitForIdle(); scrolled() > 0f }
        val before = scrolled()

        compose.runOnUiThread { viewModel.setPreferences(viewModel.state.value.preferences.copy(uiScale = 1.2f)) }
        compose.waitForIdle()
        assertEquals("The page must keep its place across a size change", before, scrolled(), 0.5f)
    }

    @Test
    fun theSizePreviewGrowsWhileTheSettingsPageStaysPut() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        openSettingsPage("Appearance")
        val heading = hasText("INTERFACE SIZE") and hasAnyAncestor(isDialog())
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithTag("sizePreviewIcon", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        fun height(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).onFirst().fetchSemanticsNode().size.height
        // Measure the dp-sized icon; Robolectric's stub font metrics do not scale with density.
        fun icon() = compose.onNodeWithTag("sizePreviewIcon", useUnmergedTree = true).fetchSemanticsNode().size.height

        val iconBefore = icon()
        val pageBefore = height(heading)

        compose.runOnUiThread { viewModel.setPreferences(viewModel.state.value.preferences.copy(uiScale = 1.6f)) }
        compose.waitForIdle()

        assertTrue("The preview must show the new size, or the setting has no visible effect",
            icon() > iconBefore)
        assertEquals("The page itself must not resize under the finger", pageBefore, height(heading))
    }

    @Test
    fun anExactSizeCanBeTyped() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        openSettingsPage("Appearance")
        val field = hasContentDescription("Interface size") and hasSetTextAction()
        compose.waitUntil(10_000) { compose.waitForIdle(); compose.onAllNodes(field).fetchSemanticsNodes().isNotEmpty() }

        compose.onAllNodes(field).onFirst().performTextReplacement("137")
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.preferences.uiScale == 1.37f }
        assertEquals(1.37f, viewModel.state.value.preferences.uiScale)

        compose.onAllNodes(field).onFirst().performTextReplacement("9")
        compose.waitForIdle()
        assertEquals("An unfinished number must not resize anything", 1.37f, viewModel.state.value.preferences.uiScale)
    }

    @Test
    fun theFadeOfHiddenItemsCanBeTypedForIconAndTextSeparately() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        fun preferences() = viewModel.state.value.preferences
        openSettingsPage("Appearance")
        val icon = hasContentDescription("Hidden item icon fade") and hasSetTextAction()
        val text = hasContentDescription("Hidden item text fade") and hasSetTextAction()
        compose.waitUntil(10_000) { compose.waitForIdle(); compose.onAllNodes(icon).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(icon).performScrollTo().performTextReplacement("72")
        compose.waitUntil(10_000) { compose.waitForIdle(); preferences().hiddenIconFade == 72 }
        compose.onNode(text).performScrollTo().performTextReplacement("30")
        compose.waitUntil(10_000) { compose.waitForIdle(); preferences().hiddenTextFade == 30 }
        assertEquals("The two are independent", 72, preferences().hiddenIconFade)

        // Key by key, as a keyboard delivers it: "1" and "10" are valid on the way to "100".
        listOf("1", "10", "100").forEach { compose.onNode(text).performTextReplacement(it); compose.waitForIdle() }
        compose.waitUntil(10_000) { compose.waitForIdle(); preferences().hiddenTextFade == 90 }
        assertEquals("Past the limit is the limit, not the prefix typed on the way", 90, preferences().hiddenTextFade)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun themeChangesRenderAndSurviveActivityRecreation() {
        awaitListing()
        changeTheme("Light")
        capture("luna-light.png")
        changeTheme("Dark")
        capture("luna-dark.png")
        compose.activityRule.scenario.recreate()
        awaitListing()
        val theme = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java].state.value.preferences.theme }
        assertEquals(ThemeMode.DARK, theme)
    }

    private fun changeTheme(label: String) {
        compose.onNodeWithContentDescription("Locations").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Appearance").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(label).performScrollTo().performClick()
        repeat(2) {
            compose.onAllNodesWithContentDescription("Back").onLast().performClick()
            compose.waitForIdle()
        }
    }

    private fun capture(name: String) {
        val bitmap = compose.onNode(isRoot() and hasAnyDescendant(hasText("beta.txt")), useUnmergedTree = true)
            .captureToImage().asAndroidBitmap()
        val output = File("build/reports/screenshots", name)
        output.parentFile?.mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
