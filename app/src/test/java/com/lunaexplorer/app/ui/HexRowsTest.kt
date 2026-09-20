package com.lunaexplorer.app.ui

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.lunaexplorer.core.HEX_MAX_ROWS
import com.lunaexplorer.core.HexBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HexRowsTest {
    @get:Rule val compose = createComposeRule()

    private var frameWidth by mutableStateOf(360.dp)
    private var frameHeight by mutableStateOf(400.dp)

    private fun show(channel: FakeHexChannel): HexSession {
        lateinit var session: HexSession
        compose.setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                session = remember { HexSession(HexBlocks(channel), scope, settleMillis = 0) }
                // Required, not preferred: a frame wider than Robolectric's screen is the point of one test.
                HexRows(session, Modifier.requiredSize(frameWidth, frameHeight).testTag("frame"))
            }
        }
        compose.waitUntil(10_000) { rows().size > 1 }
        return session
    }

    private fun show(size: Long): HexSession = show(FakeHexChannel(size) { (it % 251).toByte() })

    private fun rows(): List<String> = compose.onAllNodesWithTag("hexRow").fetchSemanticsNodes()
        .map { it.config[SemanticsProperties.Text].first().text }

    private fun topOffset(): Long = rows().first().substringBefore(' ').toLong(16)

    private fun label(offset: Long) = offset.toString(16).uppercase().padStart(rows().first().substringBefore(' ').length, '0')

    private fun rowSize(): Long = rows()[1].substringBefore(' ').toLong(16) - topOffset()

    private fun awaitRow(start: String) {
        compose.waitUntil(10_000) { rows().any { it.startsWith(start) } }
        compose.onNode(hasTestTag("hexRow") and hasText(start, substring = true)).assertIsDisplayed()
    }

    @Test fun `a terabyte file is walked a window at a time, and go to reaches its last row`() {
        val size = 1L shl 40
        val session = show(size)
        assertTrue(rows().first().startsWith("0000000000"))
        val bytesPerRow = rowSize()
        fun window() = compose.onNodeWithTag("hexWindow").fetchSemanticsNode().config[SemanticsProperties.Text].first().text
        val first = window()
        compose.onNodeWithText("Earlier").assertIsNotEnabled()

        compose.runOnUiThread { assertNull(session.goTo("FFFFFFFFFF", hex = true)) }
        awaitRow((size - bytesPerRow).toString(16).uppercase())
        val last = window()
        assertNotEquals(first, last)
        assertTrue(last.endsWith("FFFFFFFFFF"))
        compose.onNodeWithText("Later").assertIsNotEnabled()

        compose.onNodeWithText("Earlier").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { window() != last }
        compose.onNodeWithText("Later").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { window() == last }

        compose.runOnUiThread { assertNull(session.goTo("0", hex = true)) }
        awaitRow("0000000000")
        assertEquals(first, window())
    }

    @Test fun `the thumb reaches the last row of a file with half a billion rows`() {
        val size = 4L shl 30
        show(size)
        val bytesPerRow = rowSize()
        assertTrue("More rows than Compose can measure in pixels", size / bytesPerRow > 50_000_000)
        compose.onNodeWithTag("frame").performTouchInput {
            swipe(Offset(width - 4f, 12f), Offset(width - 4f, height - 1f), durationMillis = 500)
        }
        awaitRow((size - bytesPerRow).toString(16).uppercase())
    }

    @Test fun `a narrower row keeps the place in a file past the row limit`() {
        val size = 40L shl 30
        frameWidth = 800.dp
        show(size)
        val wide = rowSize()
        assertTrue("The file spans more than one window", size / wide > HEX_MAX_ROWS)
        compose.onNodeWithTag("hexRows").performScrollToIndex(HEX_MAX_ROWS - 1)
        compose.waitUntil(10_000) { topOffset() > HEX_MAX_ROWS * wide / 2 }
        val top = topOffset()

        // The text metrics decide how many bytes fit, so narrow the frame until a row holds fewer.
        while (rowSize() == wide && frameWidth > 200.dp) {
            frameWidth -= 40.dp
            compose.waitForIdle()
        }
        assertTrue("The rows never got narrower", rowSize() < wide)
        assertEquals(label(top), rows().first().substringBefore(' '))
    }

    @Test fun `a match is on screen when only two rows fit`() {
        val bytes = ByteArray(1024) { '.'.code.toByte() }
        "ab".toByteArray().copyInto(bytes, 0x100)
        "ab".toByteArray().copyInto(bytes, 0x180)
        val session = show(FakeHexChannel(bytes))
        val rowPx = compose.onAllNodesWithTag("hexRow").fetchSemanticsNodes().first().size.height
        frameHeight = with(compose.density) { (2 * rowPx).toDp() }
        compose.waitUntil(10_000) { rows().size == 2 }

        compose.runOnUiThread { session.find("ab", hex = false, forward = true) }
        awaitRow(label(0x100))
        compose.runOnUiThread { session.find("ab", hex = false, forward = true) }
        awaitRow(label(0x180))
    }

    @Test fun `a find result does not stay beside a query that has changed`() {
        lateinit var session: HexSession
        compose.setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                session = remember { HexSession(HexBlocks(FakeHexChannel(ByteArray(64))), scope, settleMillis = 0) }
                var query by remember { mutableStateOf("zz") }
                var hex by remember { mutableStateOf(false) }
                HexFindRow(session, query, { query = it }, hex, { hex = it }, onClose = {})
            }
        }
        compose.onNodeWithContentDescription("Next match").performClick()
        compose.waitUntil(10_000) { status() == "Not found" }
        compose.onNodeWithTag("hexFind").performTextInput("z")
        assertEquals("", status())

        compose.onNodeWithText("Hex").performClick()
        compose.onNodeWithTag("hexFind").performTextReplacement("6")
        compose.onNodeWithContentDescription("Next match").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("hexFindError").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Hex").performClick()
        compose.onNodeWithTag("hexFindError").assertDoesNotExist()
    }

    private fun status(): String =
        compose.onNodeWithTag("hexFindStatus").fetchSemanticsNode().config[SemanticsProperties.Text].first().text
}
