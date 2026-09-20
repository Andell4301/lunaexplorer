package com.lunaexplorer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.LunaApplication
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class PlayerControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `controls that fit sit in the middle`() {
        compose.setContent {
            Box(Modifier.width(400.dp).testTag("bar")) {
                CentredControls {
                    Box(Modifier.size(40.dp).background(Color.Red).testTag("first"))
                    Box(Modifier.size(40.dp).background(Color.Blue).testTag("last"))
                }
            }
        }

        val bar = compose.onNodeWithTag("bar").getBoundsInRoot()
        val first = compose.onNodeWithTag("first").getBoundsInRoot()
        val last = compose.onNodeWithTag("last").getBoundsInRoot()
        val before = (first.left - bar.left).value
        val after = (bar.right - last.right).value

        assertTrue("Even margins either side, not shoved left: $before vs $after", abs(before - after) < 2f)
        assertTrue("And actually inset from the edge", before > 10f)
    }

    @Test fun `controls that do not fit wrap instead of running off the end`() {
        compose.setContent {
            Box(Modifier.width(200.dp).testTag("bar")) {
                CentredControls {
                    repeat(6) { index ->
                        Box(Modifier.size(60.dp).background(Color.Green).testTag("item$index"))
                    }
                }
            }
        }

        // Clipped bounds stay inside the viewport even for an overflowing item, so compare unclipped ones.
        val bar = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        repeat(6) { index ->
            val item = compose.onNodeWithTag("item$index").getUnclippedBoundsInRoot()
            assertTrue("Control $index runs off the left: ${item.left} vs ${bar.left}",
                (item.left - bar.left).value >= -1f)
            assertTrue("Control $index runs off the right: ${item.right} vs ${bar.right}",
                (bar.right - item.right).value >= -1f)
        }
    }
}
