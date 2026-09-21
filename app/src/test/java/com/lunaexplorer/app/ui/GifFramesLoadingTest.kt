package com.lunaexplorer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.ui.GifFramesFixture.frames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GifFramesLoadingTest {
    private val harness = BrowserViewModelHarness()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(harness).around(compose)

    private fun awaitNode(matcher: SemanticsMatcher) = compose.waitUntil(15_000) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }

    private fun frameIndex(index: Int): Int =
        compose.onNodeWithTag("gifFrames").fetchSemanticsNode().config[SemanticsProperties.IndexForKey](index)

    @Test fun `a visible grid receives later thumbnails and keeps its position after viewing a frame`() {
        val bytes = frames(60)
        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        compose.setContent {
            MaterialTheme {
                GifFrames("long.gif", bytes, onBack = {}, decode = { source, side ->
                    gifThumbnails(source, side).buffer(0).onEach {
                        if (it.index == 2) {
                            paused.complete(Unit)
                            resume.await()
                        }
                    }
                })
            }
        }
        compose.waitUntil(15_000) { paused.isCompleted }
        awaitNode(hasText("2  ·  500 ms"))
        compose.onNodeWithText("60 frames").assertIsDisplayed()
        compose.onNodeWithText("1  ·  500 ms").assertIsDisplayed()
        assertEquals(-1, frameIndex(59))

        resume.complete(Unit)
        compose.waitUntil(15_000) { frameIndex(59) == 59 }
        compose.onNodeWithTag("gifFrames").performScrollToIndex(59)
        compose.onNodeWithText("60  ·  500 ms").performClick()
        awaitNode(hasText("Frame 60 of 60"))
        awaitNode(hasTestTag("gifFrame"))
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("60  ·  500 ms").assertIsDisplayed()
    }

    @Test fun `closing the grid cancels pending thumbnails`() {
        val bytes = frames(60)
        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Boolean>()
        var shown by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (shown) GifFrames("long.gif", bytes, onBack = { shown = false }, decode = { source, side ->
                    gifThumbnails(source, side).buffer(0).onEach {
                        if (it.index == 2) {
                            paused.complete(Unit)
                            resume.await()
                        }
                    }.onCompletion { stopped.complete(it is CancellationException) }
                }) else Text("Closed")
            }
        }
        compose.waitUntil(15_000) { paused.isCompleted }
        awaitNode(hasText("2  ·  500 ms"))
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitUntil(15_000) { stopped.isCompleted }
        assertTrue(runBlocking { stopped.await() })
        compose.onNodeWithText("Closed").assertIsDisplayed()
        compose.onAllNodesWithTag("gifFrames").assertCountEquals(0)
    }
}
