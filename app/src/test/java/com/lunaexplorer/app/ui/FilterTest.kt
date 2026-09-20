package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import org.junit.Assert.assertEquals
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
class FilterTest {
    @get:Rule val harness = BrowserViewModelHarness().startingWith { dir ->
        listOf("report.pdf", "notes.txt", "photo.jpg").forEach { File(dir, it).writeText("x") }
    }

    @Test fun `typing a filter narrows the rows and leaves the preferences alone`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        harness.viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { harness.state.entries.size == 3 })
        val before = harness.state.preferences

        harness.viewModel.setFilter("ot")
        assertTrue(harness.awaitUntil { harness.state.entries.map { it.name } == listOf("notes.txt", "photo.jpg") })
        assertEquals("ot", harness.state.filter)
        assertEquals("A filter is not a preference", before, harness.state.preferences)

        harness.viewModel.setFilter("")
        assertTrue(harness.awaitUntil { harness.state.entries.size == 3 })
    }
}
