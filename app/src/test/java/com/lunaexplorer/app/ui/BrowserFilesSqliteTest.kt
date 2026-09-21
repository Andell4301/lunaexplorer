package com.lunaexplorer.app.ui

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.viewModelScope
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.storage.SqlInput
import com.lunaexplorer.app.storage.SqliteBrowser
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class BrowserFilesSqliteTest {
    @get:Rule val harness = BrowserViewModelHarness().startingWith { directory ->
        SQLiteDatabase.openOrCreateDatabase(directory.resolve("notes.db"), null).use { database ->
            database.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT)")
            database.execSQL("INSERT INTO notes(body) VALUES ('first'), ('second')")
            database.execSQL("CREATE TRIGGER stop_second BEFORE UPDATE ON notes WHEN old.id = 2 " +
                "BEGIN SELECT RAISE(FAIL, 'Stop'); END")
        }
    }

    @Test fun `inserting a row refreshes the cached file size`() {
        assertTrue(harness.awaitUntil { harness.state.entries.any { it.name == "notes.db" } })
        val file = harness.directory.resolve("notes.db")
        val previousSize = file.length()
        val browser = SqliteBrowser(harness.application)
        runBlocking { browser.open(file.path, forWriting = true) }.use { handle ->
            await {
                harness.viewModel.files.insertSqliteRow(browser, handle, "notes",
                    mapOf("body" to SqlInput("x".repeat(131_072), Cursor.FIELD_TYPE_STRING))).getOrThrow()
            }
            assertTrue(file.length() > previousSize)
            assertTrue(harness.awaitUntil {
                harness.state.entries.singleOrNull { it.name == "notes.db" }?.size == file.length()
            })
        }
    }

    @Test fun `failed SQL that changes earlier rows still refreshes the cached file size`() {
        assertTrue(harness.awaitUntil { harness.state.entries.any { it.name == "notes.db" } })
        val file = harness.directory.resolve("notes.db")
        val previousSize = file.length()
        val browser = SqliteBrowser(harness.application)
        runBlocking { browser.open(file.path, forWriting = true) }.use { handle ->
            val result = await {
                harness.viewModel.files.executeSqlite(browser, handle,
                    "UPDATE notes SET body = zeroblob(131072)")
            }
            assertTrue(result.isFailure)
            val page = runBlocking { browser.page(handle, "notes", 0) }
            assertEquals(Cursor.FIELD_TYPE_BLOB, page.rows[0].values[1].type)
            assertEquals("second", page.rows[1].values[1].text)
            assertTrue(file.length() > previousSize)
            assertTrue(harness.awaitUntil {
                harness.state.entries.singleOrNull { it.name == "notes.db" }?.size == file.length()
            })
        }
    }

    private fun <T> await(block: suspend () -> T): T {
        val result = harness.viewModel.viewModelScope.async { block() }
        assertTrue(harness.awaitUntil { result.isCompleted })
        return runBlocking { result.await() }
    }
}
