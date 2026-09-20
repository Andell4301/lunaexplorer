package com.lunaexplorer.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.SubtitleAppearance
import com.lunaexplorer.app.model.SubtitleEdge
import com.lunaexplorer.app.model.SubtitleFont
import com.lunaexplorer.app.model.SubtitlePosition
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class SubtitleAppearanceDialogTest {
    private val harness = BrowserViewModelHarness().withSession {
        it.copy(preferences = it.preferences.copy(showHidden = true, uiScale = 1.2f, thumbnails = false))
    }
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(harness).around(compose)
    private val restoredModels = ViewModelStore()
    private var model by mutableStateOf<BrowserViewModel?>(null)
    private var shown by mutableStateOf(true)

    @After fun closeRestoredModels() { restoredModels.clear() }

    private fun show() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        model = harness.viewModel
        compose.setContent {
            MaterialTheme {
                val current = requireNotNull(model)
                val state by current.state.collectAsState()
                if (shown) SubtitleAppearanceDialog(state.preferences.subtitleAppearance,
                    onChange = { current.setPreferences(current.state.value.preferences.copy(subtitleAppearance = it)) },
                    onDismiss = { shown = false })
            }
        }
    }

    private fun setProgress(label: String, value: Float) {
        compose.onNodeWithContentDescription(label).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    }

    private fun showChoice(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        // performScrollTo only scrolls the nearest scrollable ancestor, which for a chip is its
        // horizontal row: bring the row into view first, then the chip.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
            and hasAnyDescendant(matcher)).performScrollTo().assertIsDisplayed()
        return compose.onNode(matcher).performScrollTo().assertIsDisplayed()
    }

    private fun clickChoice(matcher: SemanticsMatcher) {
        showChoice(matcher).performTouchInput { click() }
    }

    private fun awaitSaved(expected: SubtitleAppearance) {
        assertTrue("Appearance changes should reach the saved session", harness.awaitUntil {
            runBlocking { harness.graph.database.loadSession() }?.preferences?.subtitleAppearance == expected
        })
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun `default file styling is preserved until enabled then controls survive a fresh viewmodel`() {
        show()
        compose.onNodeWithText("Custom appearance").assertIsOff()
        compose.onNodeWithContentDescription("Text size").assertIsNotEnabled()
        compose.onNodeWithText("Bold text").assertIsNotEnabled()
        compose.onNodeWithText("Custom appearance").performClick()
        compose.onNodeWithContentDescription("Text size").assertIsEnabled()

        setProgress("Text size", 150f)
        clickChoice(hasText("Serif", substring = false))
        compose.onNodeWithText("Bold text").performScrollTo().performClick()
        clickChoice(hasContentDescription("Text color: Yellow"))
        clickChoice(hasText("Outline", substring = false))
        setProgress("Background opacity", 0f)
        clickChoice(hasText("Top", substring = false))
        setProgress("Vertical margin", 12f)
        val expected = SubtitleAppearance(enabled = true, textSizePercent = 150,
            font = SubtitleFont.SERIF, bold = true, textColor = 0xFFFFFF00.toInt(),
            edge = SubtitleEdge.OUTLINE, backgroundOpacityPercent = 0,
            position = SubtitlePosition.TOP, verticalMarginPercent = 12)
        compose.runOnIdle { assertEquals(expected, harness.state.preferences.subtitleAppearance) }
        awaitSaved(expected)

        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Subtitle appearance").assertDoesNotExist()
        lateinit var restored: BrowserViewModel
        compose.runOnIdle {
            restored = BrowserViewModel(harness.application, harness.graph)
            restoredModels.put("restored", restored)
        }
        assertTrue(harness.awaitUntil { restored.state.value.ready })
        compose.runOnIdle { model = restored; shown = true }
        compose.onNodeWithText("Custom appearance").assertIsOn()
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        var yellowPixels = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.red(pixel) > 180 && Color.green(pixel) > 180 && Color.blue(pixel) < 100) yellowPixels++
        }
        assertTrue("The dialog capture must include the native yellow subtitle preview", yellowPixels > 50)
        val screenshot = File("build/reports/screenshots/subtitle-appearance.png")
        screenshot.parentFile?.mkdirs()
        screenshot.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        compose.onNodeWithText("Text size: 150%").performScrollTo().assertIsDisplayed()
        showChoice(hasText("Serif", substring = false)).assertIsSelected()
        showChoice(hasText("Top", substring = false)).assertIsSelected()
        compose.runOnIdle { assertEquals(expected, restored.state.value.preferences.subtitleAppearance) }

        compose.onNodeWithText("Custom appearance").performScrollTo().performClick()
        compose.onNodeWithText("Bold text").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals("Turning customization off preserves the chosen settings",
                expected.copy(enabled = false), restored.state.value.preferences.subtitleAppearance)
        }
    }

    @Test fun `custom hex edits reject incomplete values and reset affects only subtitle appearance`() {
        show()
        val unrelated = harness.state.preferences
        compose.onNodeWithText("Custom appearance").performClick()
        clickChoice(hasContentDescription("Text color: Custom"))
        compose.onNodeWithText("Text color hex").performScrollTo().performTextReplacement("#12")
        compose.runOnIdle { assertEquals(0xFFFFFFFF.toInt(), harness.state.preferences.subtitleAppearance.textColor) }
        compose.onNodeWithText("Text color hex").performTextReplacement("#1a8cde")
        compose.runOnIdle { assertEquals(0xFF1A8CDE.toInt(), harness.state.preferences.subtitleAppearance.textColor) }
        compose.onNodeWithText("Text color hex").performTextReplacement("#XYZ123")
        compose.runOnIdle { assertEquals(0xFF1A8CDE.toInt(), harness.state.preferences.subtitleAppearance.textColor) }
        awaitSaved(SubtitleAppearance(enabled = true, textColor = 0xFF1A8CDE.toInt()))

        compose.onNodeWithText("Reset appearance").performClick()
        compose.runOnIdle { assertEquals(unrelated, harness.state.preferences) }
        awaitSaved(SubtitleAppearance())
        compose.onNodeWithText("Custom appearance").performScrollTo().assertIsOff()
        compose.onNodeWithContentDescription("Text size").assertIsNotEnabled()
        compose.onNodeWithText("Subtitle appearance").assertIsDisplayed()
    }
}
