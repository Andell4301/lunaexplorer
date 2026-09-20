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
class RevealTest {
    @get:Rule val harness = BrowserViewModelHarness().startingWith { dir ->
        File(dir, "a").mkdirs(); File(dir, "b").mkdirs()
        File(dir, "a/same.txt").writeText("in a")
        File(dir, "b/same.txt").writeText("in b")
    }

    @Test fun `revealing a file picks it out in its own folder`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        harness.viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { harness.state.entries.size == 2 })
        val a = harness.state.entries.single { it.name == "a" }
        harness.viewModel.navigate(Location(harness.state.location!!.crumbs + Crumb(a.ref, "a")))
        assertTrue(harness.awaitUntil { harness.state.entries.any { it.name == "same.txt" } })
        val inA = harness.state.entries.single()
        harness.viewModel.navigate(harness.state.tab!!.history.first())
        assertTrue(harness.awaitUntil { harness.state.entries.size == 2 })

        harness.viewModel.revealEntry(inA)
        assertTrue(harness.awaitUntil { harness.state.selected.isNotEmpty() })
        assertEquals(setOf(inA.ref), harness.state.selected)
        assertEquals("a", harness.state.location!!.title)
    }

    @Test fun `a reveal does not land on a same-named file in another folder`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        harness.viewModel.showScreen(Screen.BROWSER)
        assertTrue(harness.awaitUntil { harness.state.entries.size == 2 })
        val root = harness.state.location!!
        val a = harness.state.entries.single { it.name == "a" }
        val b = harness.state.entries.single { it.name == "b" }
        harness.viewModel.navigate(Location(root.crumbs + Crumb(a.ref, "a")))
        assertTrue(harness.awaitUntil { harness.state.entries.any { it.name == "same.txt" } })
        val inA = harness.state.entries.single()

        harness.viewModel.revealEntry(inA)
        assertTrue(harness.awaitUntil { harness.state.location?.title == "a" && harness.state.selected == setOf(inA.ref) })
        harness.viewModel.navigate(Location(root.crumbs + Crumb(b.ref, "b")))
        assertTrue(harness.awaitUntil { harness.state.location?.title == "b" && harness.state.entries.any { it.name == "same.txt" } })
        harness.idle()
        assertTrue("b's same.txt must not be selected on a's behalf", harness.state.selected.isEmpty())
    }
}
