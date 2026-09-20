package com.lunaexplorer.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.os.Looper
import android.view.DragEvent
import android.view.View as AndroidView
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.*
import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class BrowserDragUiTest : RobolectricBrowserUiTest() {

    // Compose registers drag interests at drag start, before the rows of the opened tab exist.
    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun aDragHeldOverATabOpensItAndCanThenBeDroppedIntoAFolderInsideIt() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        val here = requireNotNull(viewModel.state.value.directoryPath)
        assertTrue(File(here, "there/inner").mkdirs())

        compose.runOnUiThread { viewModel.newTab(); viewModel.goTo("$here/there") }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.tabs.size == 2 && viewModel.state.value.entries.any { it.name == "inner" }
        }
        val inner = viewModel.state.value.entries.first { it.name == "inner" }
        val first = viewModel.state.value.tabs.first().id
        compose.runOnUiThread { viewModel.selectTab(first) }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.activeTabId == first &&
                viewModel.state.value.entries.any { it.name == "beta.txt" }
        }
        val carried = viewModel.state.value.entries.first { it.name == "beta.txt" }
        val payload = LunaDrag(listOf(carried))

        assertTrue("Nothing accepted the drag at all",
            sendDrag(DragEvent.ACTION_DRAG_STARTED, Offset.Zero, payload))
        sendDrag(DragEvent.ACTION_DRAG_LOCATION, centreOfTab("there"), payload)
        letTheDwellElapse()
        assertNotEquals("The tab the drag was held over must open",
            first, viewModel.state.value.activeTabId)
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("inner").fetchSemanticsNodes().isNotEmpty()
        }

        val over = centreOf("inner")
        sendDrag(DragEvent.ACTION_DRAG_LOCATION, over, payload)
        sendDrag(DragEvent.ACTION_DROP, over, payload)

        val overlay = viewModel.state.value.overlay
        assertTrue("A drop into the folder the tab revealed must land: $overlay", overlay is Overlay.Drop)
        overlay as Overlay.Drop
        assertEquals("inner", overlay.title)
        assertEquals(inner.ref, overlay.destination)
        assertEquals(listOf(carried.ref), overlay.entries.map { it.ref })
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun aDragPassingOverATabDoesNotOpenIt() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        val here = requireNotNull(viewModel.state.value.directoryPath)
        assertTrue(File(here, "there/inner").mkdirs())
        compose.runOnUiThread { viewModel.newTab(); viewModel.goTo("$here/there") }
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.tabs.size == 2 }
        val first = viewModel.state.value.tabs.first().id
        compose.runOnUiThread { viewModel.selectTab(first) }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.activeTabId == first &&
                viewModel.state.value.entries.any { it.name == "beta.txt" }
        }
        val payload = LunaDrag(listOf(viewModel.state.value.entries.first { it.name == "beta.txt" }))

        sendDrag(DragEvent.ACTION_DRAG_STARTED, Offset.Zero, payload)
        sendDrag(DragEvent.ACTION_DRAG_LOCATION, centreOfTab("there"), payload)
        sendDrag(DragEvent.ACTION_DRAG_LOCATION, centreOf("beta.txt"), payload)
        letTheDwellElapse()

        assertEquals("Passing over a tab must not open it", first, viewModel.state.value.activeTabId)
    }

    // Section headers occupy list positions, so a visible list index is not an entry index.
    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun aDropLandsInTheRowUnderThePointerWhenHeadersShareTheList() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        compose.runOnUiThread {
            viewModel.setPreferences(viewModel.state.value.preferences.copy(sections = true))
        }
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.sections.isNotEmpty() }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("A").fetchSemanticsNodes().isNotEmpty()
        }
        val alpha = viewModel.state.value.entries.first { it.name == "alpha" }
        val carried = viewModel.state.value.entries.first { it.name == "gamma.txt" }
        val payload = LunaDrag(listOf(carried))

        assertTrue("Nothing accepted the drag at all",
            sendDrag(DragEvent.ACTION_DRAG_STARTED, Offset.Zero, payload))
        val over = centreOf("alpha")
        sendDrag(DragEvent.ACTION_DRAG_LOCATION, over, payload)
        sendDrag(DragEvent.ACTION_DROP, over, payload)

        val overlay = viewModel.state.value.overlay
        assertTrue("The row under the pointer must be the destination: $overlay", overlay is Overlay.Drop)
        overlay as Overlay.Drop
        assertEquals("alpha", overlay.title)
        assertEquals(alpha.ref, overlay.destination)
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun aDropOnASectionHeaderLandsInTheFolderOnScreen() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        compose.runOnUiThread {
            viewModel.setPreferences(viewModel.state.value.preferences.copy(sections = true))
        }
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.sections.isNotEmpty() }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("A").fetchSemanticsNodes().isNotEmpty()
        }
        val destination = requireNotNull(viewModel.state.value.location).ref
        val carried = viewModel.state.value.entries.first { it.name == "gamma.txt" }
        val payload = LunaDrag(listOf(carried))

        sendDrag(DragEvent.ACTION_DRAG_STARTED, Offset.Zero, payload)
        val onHeader = compose.onNodeWithText("A").fetchSemanticsNode().boundsInRoot.center
        sendDrag(DragEvent.ACTION_DRAG_LOCATION, onHeader, payload)
        sendDrag(DragEvent.ACTION_DROP, onHeader, payload)

        val overlay = viewModel.state.value.overlay
        assertTrue("A header behaves like the space around the rows: $overlay", overlay is Overlay.Drop)
        overlay as Overlay.Drop
        assertEquals(destination, overlay.destination)
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun aDropOnTheSpaceAroundTheRowsLandsInTheFolderOnScreen() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        val here = requireNotNull(viewModel.state.value.directoryPath)
        assertTrue(File(here, "there").mkdirs())
        File(here, "there/only.txt").writeText("x")

        compose.runOnUiThread { viewModel.newTab(); viewModel.goTo("$here/there") }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.tabs.size == 2 && viewModel.state.value.entries.any { it.name == "only.txt" }
        }
        val destination = requireNotNull(viewModel.state.value.location).ref
        val first = viewModel.state.value.tabs.first().id
        compose.runOnUiThread { viewModel.selectTab(first) }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.activeTabId == first &&
                viewModel.state.value.entries.any { it.name == "beta.txt" }
        }
        val carried = viewModel.state.value.entries.first { it.name == "beta.txt" }
        val payload = LunaDrag(listOf(carried))

        sendDrag(DragEvent.ACTION_DRAG_STARTED, Offset.Zero, payload)
        sendDrag(DragEvent.ACTION_DRAG_LOCATION, centreOfTab("there"), payload)
        letTheDwellElapse()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("only.txt").fetchSemanticsNodes().isNotEmpty()
        }

        val below = compose.onNodeWithText("only.txt").fetchSemanticsNode().boundsInRoot
            .let { Offset(it.center.x, it.bottom + 300f) }
        sendDrag(DragEvent.ACTION_DRAG_LOCATION, below, payload)
        sendDrag(DragEvent.ACTION_DROP, below, payload)

        val overlay = viewModel.state.value.overlay
        assertTrue("Empty space must mean the folder it is the space of: $overlay", overlay is Overlay.Drop)
        overlay as Overlay.Drop
        assertEquals("there", overlay.title)
        assertEquals(destination, overlay.destination)
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun aTabShowingAToolScreenNeitherOpensUnderADragNorTakesOne() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        val here = requireNotNull(viewModel.state.value.directoryPath)
        assertTrue(File(here, "there").mkdirs())
        compose.runOnUiThread { viewModel.newTab(); viewModel.goTo("$here/there") }
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.tabs.size == 2 }
        compose.runOnUiThread { viewModel.showScreen(Screen.STORAGE) }
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.state.value.screen == Screen.STORAGE }

        val first = viewModel.state.value.tabs.first().id
        compose.runOnUiThread { viewModel.selectTab(first) }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.screen == Screen.BROWSER &&
                viewModel.state.value.entries.any { it.name == "beta.txt" }
        }
        val payload = LunaDrag(listOf(viewModel.state.value.entries.first { it.name == "beta.txt" }))

        sendDrag(DragEvent.ACTION_DRAG_STARTED, Offset.Zero, payload)
        val tool = centreOfTab(Screen.STORAGE.title)
        sendDrag(DragEvent.ACTION_DRAG_LOCATION, tool, payload)
        letTheDwellElapse()
        assertEquals("A tab with nowhere in it to let go must not open under a drag",
            first, viewModel.state.value.activeTabId)

        sendDrag(DragEvent.ACTION_DROP, tool, payload)
        assertNull("Nor take the drop into a folder nobody can see", viewModel.state.value.overlay)
    }

    // Robolectric cannot start a WindowManager drag, so the liftDrag hook records the request.
    @Test
    fun aSearchResultIsADragHandleAndLiftsOnlyOnceTheFingerHasTravelled() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        compose.runOnUiThread { viewModel.search("beta", "ALL", null, null, null) }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.state.value.view is View.Search && viewModel.state.value.entries.any { it.name == "beta.txt" }
        }

        val lifted = mutableListOf<List<String>>()
        liftDrag = { _, _, _, payload -> lifted += payload.entries.map { it.name } }
        try {
            compose.onNodeWithText("beta.txt").performTouchInput {
                down(center); advanceEventTime(1_000); moveBy(Offset(2f, 0f)); advanceEventTime(16); up()
            }
            compose.waitForIdle()
            assertEquals("A press that barely moved picks the row out without carrying it",
                emptyList<List<String>>(), lifted)
            assertEquals("Which it must still have done", 1, viewModel.state.value.selected.size)

            compose.onNodeWithText("beta.txt").performTouchInput {
                down(center); advanceEventTime(1_000); moveBy(Offset(60f, 0f)); advanceEventTime(16); up()
            }
            compose.waitForIdle()
            assertEquals("Travelling far enough carries the result itself",
                listOf(listOf("beta.txt")), lifted)
        } finally {
            liftDrag = platformDrag
        }
    }

    /** The hover delay runs on the main looper, which the Compose clock does not advance. */
    private fun letTheDwellElapse() {
        compose.mainClock.advanceTimeBy(1_500)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        compose.waitForIdle()
    }

    private fun centreOf(text: String): Offset =
        compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.center

    /** The topmost visible match is the tab; the path bar and file rows sit below it. */
    private fun centreOfTab(text: String): Offset = compose.onAllNodes(hasText(text))
        .fetchSemanticsNodes()
        // Exclude off-screen drawer nodes and nodes without layout coordinates.
        .filter { it.boundsInRoot.left >= 0f && it.boundsInRoot.width > 0f }
        .minBy { it.boundsInRoot.top }.boundsInRoot.center

    /**
     * Builds a DragEvent by reflection and hands it to Compose's registered listener.
     * Robolectric provides neither a DragEvent factory nor WindowManager drag support.
     */
    private fun sendDrag(action: Int, at: Offset, payload: Any?): Boolean {
        fun find(view: AndroidView): AndroidView? = when {
            view.javaClass.simpleName == "AndroidComposeView" -> view
            view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        val composeView = requireNotNull(find(compose.activity.window.decorView)) { "No Compose view" }
        val info = requireNotNull(AndroidView::class.java.getDeclaredField("mListenerInfo")
            .apply { isAccessible = true }.get(composeView)) { "Nothing is listening for drags" }
        val listener = info.javaClass.getDeclaredField("mOnDragListener")
            .apply { isAccessible = true }.get(info) as AndroidView.OnDragListener
        // Arguments follow the parameter types: DragEvent.obtain gained a flags argument between
        // the Android versions this suite runs against.
        val obtain = DragEvent::class.java.declaredMethods
            .filter { it.name == "obtain" }.maxBy { it.parameterCount }.apply { isAccessible = true }
        var floats = 0
        val arguments = obtain.parameterTypes.mapIndexed { index, type ->
            when {
                index == 0 -> action
                type == Float::class.javaPrimitiveType -> if (floats++ < 2) (if (floats == 1) at.x else at.y) else 0f
                type == Int::class.javaPrimitiveType -> 0
                type == Boolean::class.javaPrimitiveType -> false
                type == Any::class.java -> payload
                type == ClipDescription::class.java -> ClipDescription("Luna", arrayOf("text/plain"))
                type == ClipData::class.java -> ClipData.newPlainText("Luna", "drag")
                else -> null
            }
        }
        val event = obtain.invoke(null, *arguments.toTypedArray()) as DragEvent
        val accepted = compose.runOnUiThread { listener.onDrag(composeView, event) }
        compose.waitForIdle()
        return accepted
    }
}
