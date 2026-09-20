package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.storage.LocalRoot
import com.lunaexplorer.app.storage.shizuku.HelperState
import com.lunaexplorer.core.RootKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class ShizukuAccessTest {
    @get:Rule val harness = BrowserViewModelHarness()

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state
    private val volume by lazy { File(harness.directory.parentFile, "volume") }

    /** A volume of the kind whose Android/data the helper answers for. */
    private fun mountVolume() {
        File(volume, "Android/data/com.game/files").mkdirs()
        File(volume, "Android/data/com.game/files/save.dat").writeText("progress")
        harness.graph.additionalRoots = harness.graph.additionalRoots +
            LocalRoot("volume", "Volume", volume, RootKind.INTERNAL, followLinks = true)
        viewModel.refreshAccess()
        assertTrue(harness.awaitUntil { state.roots.any { it.title == "Volume" } })
    }

    @Test fun `switching it on asks Shizuku once, and the helper starts when it says yes`() {
        assertTrue(harness.awaitUntil { state.ready })
        harness.shizuku.start(permitted = false)
        assertEquals(HelperState.OFF, state.helper)

        viewModel.useShizuku()

        assertTrue(harness.awaitUntil { state.helper == HelperState.NEEDS_PERMISSION })
        assertEquals(1, harness.shizuku.requests)
        harness.shizuku.grant()
        assertTrue(harness.awaitUntil { state.helper == HelperState.READY })
        assertTrue(state.preferences.shizuku)
    }

    @Test fun `allowing Luna inside Shizuku's own app is noticed on the way back`() {
        assertTrue(harness.awaitUntil { state.ready })
        harness.shizuku.refused = true
        harness.shizuku.start(permitted = false)
        viewModel.useShizuku()
        assertTrue(harness.awaitUntil { state.helper == HelperState.REFUSED })

        harness.shizuku.grantSilently()
        viewModel.refreshAccess()

        assertTrue("Shizuku sends no word of it, so returning to Luna is when it looks", harness.awaitUntil { state.helper == HelperState.READY })
    }

    @Test @Config(sdk = [29])
    fun `before Android 11 there is nothing to switch on`() {
        assertTrue(harness.awaitUntil { state.ready })
        harness.shizuku.start(permitted = true)

        viewModel.useShizuku()
        harness.idle()

        assertFalse("The setting has no switch there, so nothing may turn it on", state.preferences.shizuku)
        assertEquals(0, harness.shizuku.binds)
    }

    @Test fun `a folder under Android data opens where it is, path and all`() {
        assertTrue(harness.awaitUntil { state.ready })
        mountVolume()
        harness.shizuku.start(permitted = true)
        viewModel.useShizuku()
        assertTrue(harness.awaitUntil { state.helper == HelperState.READY })
        val files = File(volume, "Android/data/com.game/files").canonicalPath

        viewModel.goTo(files)

        assertTrue(harness.awaitUntil { !state.loading && state.entries.singleOrNull()?.name == "save.dat" })
        assertEquals("The path bar, bookmarks and the empty states all go by this", files, state.directoryPath)
    }

    @Test fun `a closed folder that will not open still says where it is`() {
        assertTrue(harness.awaitUntil { state.ready })
        mountVolume()
        val missing = File(volume, "Android/data/com.gone").canonicalPath

        viewModel.goTo(missing)

        assertTrue(harness.awaitUntil { state.error != null })
        assertEquals(missing, state.directoryPath)
    }

    @Test fun `when Shizuku stops, the folder on screen is read again without it`() {
        assertTrue(harness.awaitUntil { state.ready })
        mountVolume()
        harness.shizuku.start(permitted = true)
        viewModel.useShizuku()
        assertTrue(harness.awaitUntil { state.helper == HelperState.READY })
        viewModel.goTo(File(volume, "Android/data/com.game/files").canonicalPath)
        assertTrue(harness.awaitUntil { !state.loading && state.entries.size == 1 })
        File(volume, "Android/data/com.game/files/new.dat").writeText("x")

        harness.shizuku.stop()

        assertTrue(harness.awaitUntil { state.helper == HelperState.NOT_RUNNING })
        assertTrue("Who answers changed, so what was listed may have too", harness.awaitUntil { state.entries.size == 2 })
    }
}
