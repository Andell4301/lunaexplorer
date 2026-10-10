package com.lunaexplorer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class BatchRenameDialogTest {
    @get:Rule val compose = createComposeRule()

    private fun show(names: List<String>) {
        compose.setContent {
            MaterialTheme {
                BatchRenameDialog(
                    entries = names.map { Entry(NodeRef("test", it), it, directory = false) },
                    existing = names.toSet(),
                    includeExtension = false,
                    onRemember = {},
                    onDismiss = {},
                    onApply = { _, _ -> },
                )
            }
        }
    }

    @Test fun previewRemainsReachableAfterAddingManyRules() {
        show(listOf("sample.txt"))
        repeat(8) {
            compose.onNodeWithText("Case").performScrollTo().performClick()
        }

        compose.onNode(hasScrollToIndexAction()).assertHeightIsAtLeast(1.dp)
            .performScrollToNode(hasText("sample.txt"))
        compose.onNodeWithText("sample.txt").assertIsDisplayed()
        compose.onNodeWithText("→ unchanged").assertIsDisplayed()
        compose.onNodeWithText("Rename 1 items").assertIsDisplayed()
    }

    @Test fun unfinishedNumberSurvivesScrollingThroughPreview() {
        show(List(80) { "sample-$it.txt" })
        compose.onNodeWithText("Number").performScrollTo().performClick()
        val start = hasSetTextAction() and hasText("Start")
        compose.onNode(start).performScrollTo().performTextReplacement("-")
        compose.onNodeWithText("Rename").performSemanticsAction(SemanticsActions.RequestFocus) { it() }

        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("sample-79.txt"))
        compose.onNodeWithText("sample-79.txt").assertIsDisplayed()
        compose.onNode(start).assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Start"))

        compose.onNode(start).assertTextContains("-")
    }
}
