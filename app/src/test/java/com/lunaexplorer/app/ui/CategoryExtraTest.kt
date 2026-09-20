package com.lunaexplorer.app.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.storage.MediaCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.lunaexplorer.app.model.*
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class CategoryExtraTest {
    class RecordingMedia : ContentProvider() {
        val queries = AtomicInteger()
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<String>?, selection: String?, arguments: Array<String>?, order: String?): Cursor {
            queries.incrementAndGet()
            return MatrixCursor(projection ?: emptyArray())
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, arguments: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, arguments: Array<String>?) = 0
    }

    @get:Rule val harness = BrowserViewModelHarness()
        .withSession { it.copy(preferences = it.preferences.copy(categoryExtras = mapOf("AUDIO" to setOf("opus")))) }

    private lateinit var media: RecordingMedia
    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    @Before fun recordQueries() {
        media = Robolectric.buildContentProvider(RecordingMedia::class.java).create("media").get()
    }

    private fun showing() {
        assertTrue(harness.awaitUntil {
            val current = state
            val folder = current.view as? View.Folder
            current.ready && !current.loading && folder != null && folder.listingRef == folder.ref
        })
        assertEquals("The index must have been told what the session carried",
            setOf("opus"), harness.graph.media.categoryExtras["AUDIO"])
        viewModel.showCategory(MediaCategory.AUDIO)
        assertTrue("The category never finished reading",
            harness.awaitUntil { state.view is View.Category && !state.searching })
        viewModel.setExtensions(setOf("opus"))
    }

    @Test fun `removing an extra asks the category again with it gone`() {
        showing()
        val before = media.queries.get()

        viewModel.removeCategoryExtra(MediaCategory.AUDIO, "opus")

        assertEquals("Gone from the settings", emptySet<String>(), state.preferences.categoryExtras["AUDIO"].orEmpty())
        assertEquals("And from the index the query is built from",
            emptySet<String>(), harness.graph.media.categoryExtras["AUDIO"].orEmpty())
        assertFalse("Filtering on it would now show nothing, so it stops filtering",
            "opus" in state.extensions)
        assertTrue("Removing an extra must query MediaStore again",
            harness.awaitUntil { media.queries.get() == before + 1 && !state.searching })
        assertTrue("And it says what went", state.message?.contains("opus") == true)
    }

    @Test fun `a built-in extension is not something there is anywhere to remove`() {
        showing()
        val before = media.queries.get()

        viewModel.removeCategoryExtra(MediaCategory.AUDIO, "mp3")
        harness.idle()

        assertEquals("Nothing added, nothing to take away", setOf("opus"),
            state.preferences.categoryExtras["AUDIO"])
        assertEquals("And nothing asked again", before, media.queries.get())
    }
}
