package com.lunaexplorer.app.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.AppGraph
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.MainActivity
import com.lunaexplorer.app.storage.LocalRoot
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import com.lunaexplorer.app.model.*

/** Activity fixture and browser actions shared by Robolectric and device UI tests. */
abstract class BrowserUiTest {
    protected val compose = createAndroidComposeRule<MainActivity>()
    protected val fixture = BrowserFixture(::prepareRuntime, ::releaseRuntime)

    // Hooks for the Robolectric runtime; a device needs none.
    protected open fun prepareRuntime(application: LunaApplication) = Unit
    protected open fun releaseRuntime() = Unit
    protected open fun runtimeDiagnostics(): String = ""

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)

    protected fun awaitListing() {
        compose.waitForIdle()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        // Session restore can replace a screen selected before it finishes.
        awaitCondition("Session restored", 15_000) { viewModel.state.value.ready }
        compose.runOnUiThread { viewModel.showScreen(Screen.BROWSER) }
        awaitText("beta.txt")
        // The first-run access prompt only appears once the listing has loaded.
        dismissFirstRunPrompt()
        compose.onNode(hasText(fixture.token) and hasClickAction()).assertIsDisplayed()
    }

    private fun dismissFirstRunPrompt() {
        compose.waitForIdle()
        val prompt = compose.onAllNodesWithText("Not now")
        if (prompt.fetchSemanticsNodes().isNotEmpty()) {
            prompt.onFirst().performClick()
            compose.waitForIdle()
        }
    }

    protected fun openAlphaFolder() {
        compose.onNode(hasText("alpha") and hasClickAction() and hasAnyDescendant(hasContentDescription("Select alpha"))).performClick()
    }

    protected fun openFolderActions() {
        val folderActions = compose.onNodeWithContentDescription("Folder actions")
        if (generateSequence(folderActions.fetchSemanticsNode().parent) { it.parent }
                .any { it.config.contains(SemanticsActions.ScrollBy) }) folderActions.performScrollTo()
        folderActions.performClick()
    }

    // Matched through the tab's close button: a file row can carry the same text.
    protected fun selectTab(title: String) {
        compose.onNode(hasClickAction() and hasAnyDescendant(hasContentDescription("Close $title")))
            .performScrollTo().performClick()
    }

    // Reads the history cursor from state; opening the Forward menu would leave it covering the next action.
    protected fun assertForwardAvailable(available: Boolean) {
        val state = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java].state.value }
        val tab = requireNotNull(state.tab) { "No active tab" }
        assertEquals(available, tab.index < tab.history.lastIndex)
    }

    protected fun useForward() {
        openFolderActions()
        // The menu item can be below the screen edge.
        val forward = compose.onNode(hasText("Forward") and hasAnyAncestor(isPopup()))
        runCatching { forward.performScrollTo() }
        forward.performClick()
    }

    protected fun fileList() = compose.onNode(hasScrollToIndexAction() and
        hasAnyDescendant(hasText("alpha") or hasText("beta.txt") or hasText("gamma.txt")))

    protected fun awaitText(text: String) {
        awaitCondition("Visible text: $text", 15_000) {
            compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    protected fun awaitCondition(description: String, timeoutMillis: Long, condition: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis) { compose.waitForIdle(); condition() } }
        catch (timeout: ComposeTimeoutException) {
            val tree = runCatching {
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                (0 until roots.fetchSemanticsNodes().size).joinToString("\n") { roots[it].printToString(maxDepth = 12) }
            }.getOrElse { "Semantics unavailable: ${it.message}" }
            val state = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java].state.value }
            throw AssertionError("Timed out: $description\nState: $state\nQueue: ${fixture.graph.database.queue.value}\n$tree\n${runtimeDiagnostics()}", timeout)
        }
    }

    protected fun openQueue() {
        compose.waitForIdle()
        // A conflict or failure opens the queue on its own.
        if (compose.onAllNodes(hasText("Operations") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithContentDescription("Locations").performClick()
            compose.onNodeWithText("Operations").performClick()
            compose.waitForIdle()
        }
        compose.onNode(hasText("Operations") and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    protected fun closeQueue() {
        compose.onAllNodesWithContentDescription("Close").onFirst().performClick()
        compose.waitForIdle()
    }

    protected class BrowserFixture(private val prepare: (LunaApplication) -> Unit, private val release: () -> Unit) : ExternalResource() {
        val token = "smoke-${UUID.randomUUID().toString().take(8)}"
        lateinit var graph: AppGraph
        lateinit var directory: File
        private var previous: BrowserState? = null
        private lateinit var fallback: BrowserState

        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val application = instrumentation.targetContext.applicationContext as LunaApplication
            prepare(application)
            graph = application.graph
            if (Build.VERSION.SDK_INT >= 33) {
                instrumentation.uiAutomation.grantRuntimePermission(application.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
            runBlocking {
                previous = graph.database.loadSession()
                // Register a temporary app-files root that needs no storage permission.
                val harness = File(requireNotNull(application.getExternalFilesDir(null)), "smoke-fixture")
                graph.additionalRoots = listOf(LocalRoot("harness", "Test fixture", harness))
                val root = graph.local.root("harness")
                val entry = graph.local.create(root, token, directory = true)
                directory = File(requireNotNull(graph.local.pathOf(entry.ref)))
                File(directory, "alpha").mkdir()
                File(directory, "alpha/alpha-note.txt").writeText("Nested folder fixture\n")
                File(directory, "beta.txt").writeText("Original bytes must survive name collisions.\n")
                File(directory, "gamma.txt").writeText("Second visible file\n")
                File(directory, ".hidden.txt").writeText("Excluded from visible selection\n")
                val rootCrumb = Crumb(root, "Test fixture")
                val startTab = BrowserTab(history = listOf(Location(listOf(rootCrumb, Crumb(entry.ref, token)))))
                // List view: grid tiles have no "Select <name>" icon until selection is active.
                graph.database.saveSession(BrowserState(tabs = listOf(startTab), activeTabId = startTab.id,
                    preferences = Preferences(view = ViewMode.LIST)))
                val fallbackTab = BrowserTab(history = listOf(Location(listOf(rootCrumb))))
                fallback = BrowserState(tabs = listOf(fallbackTab), activeTabId = fallbackTab.id)
            }
        }

        override fun after() {
            // Wait for workers to stop before deleting their destination.
            try { runBlocking {
                graph.database.queue.value.filter { it.title.contains(token) && it.status in setOf("RUNNING", "QUEUED", "CONFLICT") }
                    .forEach { graph.queue.cancel(it.id) }
                val idle = withTimeoutOrNull(15_000) {
                    graph.database.queue.first { items -> items.none { it.title.contains(token) && it.status in setOf("RUNNING", "QUEUED") } }
                }
                if (idle != null) directory.deleteRecursively()
                graph.database.saveSession(previous ?: fallback)
                graph.additionalRoots = emptyList()
            } } finally {
                try { release() } finally { graph.procedures.close() }
            }
        }
    }
}
