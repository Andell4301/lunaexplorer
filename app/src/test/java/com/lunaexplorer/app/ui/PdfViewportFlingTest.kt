package com.lunaexplorer.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.model.PdfDirection
import com.lunaexplorer.app.model.PdfPageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfViewportFlingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `turning to a requested page stops the previous scroll fling`() {
        val state = PdfViewportState(List(20) { IntSize(300, 400) }, PdfDirection.VERTICAL, PdfPageLayout.CONTINUOUS)
        compose.setContent {
            PdfViewport(state, tapToTurn = false, swipeToTurn = true,
                modifier = Modifier.size(300.dp, 400.dp).testTag("viewport")) {
                Box(Modifier.fillMaxSize())
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("viewport").performTouchInput {
            swipe(Offset(width * .5f, height * .8f), Offset(width * .5f, height * .2f), durationMillis = 120)
        }
        var draggedTop = 0f
        compose.runOnIdle { draggedTop = state.bounds(0).top }
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle { assertTrue(state.bounds(0).top < draggedTop) }
        var destination = Rect.Zero
        compose.runOnIdle {
            state.goToPage(8)
            destination = state.bounds(8)
        }
        compose.mainClock.advanceTimeBy(2000)
        compose.runOnIdle {
            assertEquals(8, state.page)
            assertEquals(destination, state.bounds(8))
        }
    }
}
