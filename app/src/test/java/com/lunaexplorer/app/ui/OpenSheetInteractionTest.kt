package com.lunaexplorer.app.ui

import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.Screen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class OpenSheetInteractionTest {
    private val harness = BrowserViewModelHarness().startingWith { File(it, "notes.txt").writeText("Hello") }
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(harness).around(compose)

    @Test fun `expanding all apps and repeated upward swipes leave the sheet settled and open`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        harness.viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { harness.state.entries.size == 1 })
        val entry = harness.state.entries.single()
        val uri = runBlocking { harness.graph.uriFor(entry.ref) }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, entry.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val apps = (1..20).map { number ->
            ResolveInfo().apply {
                nonLocalizedLabel = "Test reader %02d".format(number)
                isDefault = true
                filter = IntentFilter(Intent.ACTION_VIEW).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addDataType(entry.mimeType)
                }
                activityInfo = ActivityInfo().apply {
                    packageName = "test.reader$number"
                    name = "ReaderActivity"
                    exported = true
                    applicationInfo = ApplicationInfo().apply { packageName = "test.reader$number" }
                }
            }
        }
        shadowOf(harness.application.packageManager).setResolveInfosForIntent(intent, apps)
        var dismissed = 0
        compose.setContent {
            MaterialTheme {
                OpenSheet(entry, harness.viewModel, onInternal = {}, onExternal = { _, _ -> }, onDismiss = { dismissed++ })
            }
        }
        try {
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                compose.onAllNodesWithText("All 20 apps").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: AssertionError) {
            throw AssertionError(compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(), error)
        }
        val scroller = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasAnyAncestor(isDialog())
        fun bounds() = compose.onAllNodes(scroller).onFirst().fetchSemanticsNode().boundsInRoot
        val before = bounds()
        val dialogHeight = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot.height
        assertTrue("The chooser starts around half height, without a mostly empty full-height panel",
            before.height < dialogHeight * .6f)
        compose.onAllNodes(scroller).onFirst().performTouchInput { swipeUp(durationMillis = 200) }
        compose.waitForIdle()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")).fetchSemanticsNode()
        compose.onNodeWithText("All 20 apps").performScrollTo().performClick()
        compose.waitForIdle()
        val expanded = bounds()
        assertTrue("The expanded chooser makes room for the full app list", expanded.height > before.height * 1.3f)

        // Step frame by frame: waiting for idle would hide a sheet that moves and settles back.
        compose.mainClock.autoAdvance = false
        repeat(8) {
            compose.onAllNodes(scroller).onFirst().performTouchInput { swipeUp(durationMillis = 150) }
            repeat(60) {
                compose.mainClock.advanceTimeByFrame()
                val current = bounds()
                assertEquals("A content fling must never move the expanded sheet", expanded.top, current.top, .5f)
                assertEquals(expanded.height, current.height, .5f)
            }
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Resize open menu").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Compact")).fetchSemanticsNode()
        assertTrue("The handle can return the menu to compact height", bounds().height < dialogHeight * .6f)
        compose.onNodeWithText(entry.name).performScrollTo().performTouchInput { click() }
        assertEquals("Upward scrolling must not dismiss the chooser", 0, dismissed)
        compose.onNode(isDialog()).performTouchInput { click(Offset(centerX, 10f)) }
        compose.waitForIdle()
        assertEquals("Tapping the exposed backdrop dismisses it", 1, dismissed)
    }
}
