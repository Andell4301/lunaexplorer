package com.lunaexplorer.app.ui

import androidx.compose.ui.test.*
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs under Robolectric (BrowserSmokeRobolectricTest) and on a device (BrowserSmokeTest). */
abstract class BrowserSmokeSuite : BrowserUiTest() {
    @Test
    fun createFolderPublishesAndReportsSuccess() {
        awaitListing()
        val name = "${fixture.token}-created"
        openFolderActions()
        compose.onNodeWithText("New folder").performClick()
        compose.onNode(hasSetTextAction()).performTextInput(name)
        compose.onNodeWithText("Create").performClick()

        awaitCondition("Folder publication and durable success", 30_000) {
            File(fixture.directory, name).isDirectory &&
                fixture.graph.database.queue.value.any { it.title == "Create folder $name" && it.status == "SUCCEEDED" }
        }
        awaitText(name)
        compose.onNodeWithText(name).assertIsDisplayed()
        openQueue()
        compose.onNode(hasText("Create folder $name") and hasText("Succeeded")).assertExists()
    }

    @Test
    fun copyCollisionWaitsForDecisionAndKeepBothPreservesOriginal() {
        awaitListing()
        val original = File(fixture.directory, "beta.txt")
        val expected = original.readText()
        compose.onNodeWithContentDescription("Select beta.txt").performClick()
        compose.onNodeWithText("Copy").performClick()
        compose.onNodeWithText("Paste here").performClick()

        awaitCondition("Copy conflict", 30_000) {
            fixture.graph.database.queue.value.any { it.title.contains(fixture.token) && it.status == "CONFLICT" }
        }
        assertEquals(expected, original.readText())
        assertTrue("Conflict must wait without publishing an extra file", !File(fixture.directory, "beta (1).txt").exists())
        openQueue()
        compose.onAllNodesWithText("Keep both").onFirst().performClick()

        val copy = File(fixture.directory, "beta (1).txt")
        awaitCondition("Keep both publication and durable success", 30_000) {
            copy.exists() && fixture.graph.database.queue.value.any {
                it.title.startsWith("Resolve:") && it.title.contains(fixture.token) && it.status == "SUCCEEDED"
            }
        }
        assertEquals("Copy must preserve all source bytes", expected, copy.readText())
        assertEquals("Keep both must leave the original unchanged", expected, original.readText())
        closeQueue()
        awaitText("beta (1).txt")
        compose.onNodeWithText("beta (1).txt").assertIsDisplayed()
    }

    @Test
    fun copyCollisionOverwriteReplacesTheDestinationFile() {
        awaitListing()
        val source = File(fixture.directory, "beta.txt")
        val expected = source.readText()
        val target = File(fixture.directory, "alpha/beta.txt")
        target.writeText("stale content that must be replaced\n")

        compose.onNodeWithContentDescription("Select beta.txt").performClick()
        compose.onNodeWithText("Copy").performClick()
        openAlphaFolder()
        awaitText("alpha-note.txt")
        compose.onNodeWithText("Paste here").performClick()

        awaitCondition("Copy conflict", 30_000) {
            fixture.graph.database.queue.value.any { it.title.contains("alpha") && it.status == "CONFLICT" }
        }
        openQueue()
        compose.onAllNodesWithText("Overwrite").onFirst().performClick()

        awaitCondition("Overwrite publication and durable success", 30_000) {
            target.readText() == expected && fixture.graph.database.queue.value.any {
                it.title.startsWith("Resolve:") && it.status == "SUCCEEDED"
            }
        }
        assertEquals("Overwrite must leave the source untouched", expected, source.readText())
        assertTrue("Overwriting must not leave a second copy behind", !File(fixture.directory, "alpha/beta (1).txt").exists())
        closeQueue()
    }

    @Test
    fun tabsKeepIndependentLocationsAndBackForwardHistory() {
        awaitListing()
        openFolderActions()
        compose.onNodeWithText("New tab").performClick()
        openAlphaFolder()
        awaitText("alpha-note.txt")

        selectTab(fixture.token)
        awaitText("beta.txt")
        assertForwardAvailable(false)
        openAlphaFolder()
        awaitText("alpha-note.txt")
        compose.onNodeWithContentDescription("Back").performClick()
        awaitText("beta.txt")
        assertForwardAvailable(true)

        selectTab("alpha")
        awaitText("alpha-note.txt")
        compose.onNodeWithContentDescription("Back").assertIsEnabled()
        assertForwardAvailable(false)
        compose.onNodeWithContentDescription("Back").performClick()
        awaitText("beta.txt")
        useForward()
        awaitText("alpha-note.txt")
    }

    @Test
    fun invertSelectionUsesVisibleItemsAndDoesNotSelectHiddenFiles() {
        awaitListing()
        compose.onNodeWithText(".hidden.txt").assertDoesNotExist()
        compose.onNodeWithContentDescription("Select alpha").performClick()
        compose.onNodeWithContentDescription("Invert selection").performClick()
        compose.onNodeWithText("2 of 3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Deselect beta.txt").assertExists()
        fileList().performScrollToNode(hasText("gamma.txt"))
        compose.onNodeWithContentDescription("Deselect gamma.txt").assertExists()
        fileList().performScrollToIndex(0)
        compose.onNodeWithContentDescription("Select alpha").assertExists()
        compose.onNodeWithContentDescription("Clear selection").performClick()
        compose.onNodeWithText("2 of 3").assertDoesNotExist()
        compose.onNodeWithContentDescription("Select alpha").assertExists()
    }
}
