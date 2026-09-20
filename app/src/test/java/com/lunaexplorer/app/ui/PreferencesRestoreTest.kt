package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class PreferencesRestoreTest {
    @get:Rule val harness = BrowserViewModelHarness().withSession { state ->
        state.copy(preferences = state.preferences.copy(
            ignoreDotFiles = true,
            categoryExtras = mapOf("AUDIO" to setOf("opus")),
        ))
    }

    @Test fun `restored media settings reach the index without any preference being touched`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        assertTrue("A restored ignoreDotFiles must be live in the index", harness.graph.media.ignoreDotFiles)
        assertEquals(mapOf("AUDIO" to setOf("opus")), harness.graph.media.categoryExtras)
    }
}
