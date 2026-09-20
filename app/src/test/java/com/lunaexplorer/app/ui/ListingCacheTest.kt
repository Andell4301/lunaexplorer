package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import com.lunaexplorer.app.model.*

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class ListingCacheTest {
    @get:Rule val harness = BrowserViewModelHarness().startingWith { dir -> File(dir, "existing.txt").writeText("x") }

    @Test fun `a file created in the folder in view appears at once, not after the cache expires`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        harness.viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { harness.state.entries.any { it.name == "existing.txt" } })

        harness.viewModel.files.createTextFile("fresh.txt", "hello")

        assertTrue("The new file must be listed immediately",
            harness.awaitUntil { harness.state.entries.any { it.name == "fresh.txt" } })
    }
}
