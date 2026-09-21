package com.lunaexplorer.app.ui

import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.Overlay
import com.lunaexplorer.app.model.ViewerKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqliteUiTest : RobolectricBrowserUiTest() {
    private val databaseFile get() = File(fixture.directory, "browse.db")
    private val viewModel get() = compose.runOnUiThread {
        ViewModelProvider(compose.activity)[BrowserViewModel::class.java]
    }

    private fun openDatabase(rows: Int = 3, setup: (SQLiteDatabase) -> Unit = {}) {
        SQLiteDatabase.openDatabase(databaseFile.path, null,
            SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { database ->
            database.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY, title TEXT NOT NULL DEFAULT 'untitled', score INTEGER DEFAULT 7)")
            database.execSQL("CREATE TABLE other(payload TEXT)")
            database.execSQL("CREATE INDEX a_title ON notes(title)")
            database.execSQL("CREATE INDEX a_score ON notes(score)")
            database.execSQL("CREATE INDEX a_other ON other(payload)")
            database.execSQL("CREATE VIEW note_titles AS SELECT title FROM notes")
            database.beginTransaction()
            try {
                database.compileStatement("INSERT INTO notes(title) VALUES(?)").use { statement ->
                    repeat(rows) { index ->
                        statement.bindString(1, "Note ${(rows - index).toString().padStart(3, '0')}")
                        statement.executeInsert()
                    }
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            setup(database)
        }
        awaitListing()
        val model = viewModel
        compose.runOnUiThread { model.refresh() }
        awaitText(databaseFile.name)
        val entry = model.state.value.entries.first { it.name == databaseFile.name }
        compose.runOnUiThread { model.showOverlay(Overlay.Viewer(entry, ViewerKind.DATABASE)) }
        awaitRows()
    }

    private fun awaitRows() = awaitCondition("Database rows loaded", 15_000) {
        compose.onAllNodesWithTag("sqliteRows").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithTag("sqliteLoading").fetchSemanticsNodes().isEmpty()
    }

    private fun rowText(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("sqliteRows")))

    private fun awaitRow(text: String) = awaitCondition("Database row visible: $text", 15_000) {
        compose.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag("sqliteRows"))).fetchSemanticsNodes().isNotEmpty()
    }

    private fun action(text: String) {
        compose.onNodeWithContentDescription("Database actions").performClick()
        compose.onNode(hasText(text) and hasAnyAncestor(isPopup())).performClick()
    }

    private fun backToRows() {
        compose.onNode(hasContentDescription("Back") and hasAnyAncestor(isDialog())).performClick()
        awaitRows()
    }

    private fun choose(tag: String, value: String) {
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag(tag))).performClick()
        compose.onNode(hasText(value) and hasAnyAncestor(isPopup())).performClick()
    }

    private fun enableEditing() {
        compose.onNodeWithText("Edit").performClick()
        awaitCondition("Database opened for editing", 15_000) {
            compose.onAllNodes(hasText("Edit") and hasClickAction()).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("sqliteLoading").fetchSemanticsNodes().isEmpty()
        }
        awaitRows()
    }

    private fun scalar(sql: String): String = SQLiteDatabase.openDatabase(
        databaseFile.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    ).use { database ->
        database.rawQuery(sql, null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
    }

    @Test fun tableSelectorsExcludeIndexesAndViewsWhichHaveSeparateInspectors() {
        openDatabase()
        val tables = hasAnyAncestor(hasTestTag("sqliteTables"))
        compose.onNode(hasText("notes") and tables).assertExists()
        compose.onNode(hasText("other") and tables).assertExists()
        listOf("a_title", "a_score", "a_other", "note_titles").forEach { name ->
            compose.onAllNodes(hasText(name) and tables).assertCountEquals(0)
        }

        action("Indexes")
        compose.onNodeWithText("a_title").performClick()
        awaitText("CREATE INDEX a_title ON notes(title)")
        compose.onNodeWithText("CREATE INDEX a_title ON notes(title)", substring = true, useUnmergedTree = true).assertExists()
        backToRows()
        rowText("Note 003").assertExists()

        action("Views")
        compose.onNodeWithText("note_titles").performClick()
        awaitRows()
        rowText("Note 003").assertExists()
        compose.onNode(hasText("notes") and tables).performClick()
        awaitRows()
        rowText("Note 003").assertExists()
    }

    @Test fun pagingAndColumnSortingChangeTheDisplayedRows() {
        openDatabase(rows = 205)
        rowText("Note 205").assertExists()
        compose.onNodeWithText("Previous").assertIsNotEnabled()
        compose.onNodeWithText("Next").performClick()
        awaitText("Note 105")
        rowText("Note 105").assertExists()
        compose.onNodeWithText("Next").performClick()
        awaitText("Note 005")
        rowText("Note 005").assertExists()
        compose.onNodeWithText("Next").assertIsNotEnabled()
        compose.onNodeWithText("Previous").performClick()
        awaitText("Note 105")

        compose.onNode(hasText("title") and hasClickAction()).performClick()
        awaitText("Note 001")
        rowText("Note 001").assertExists()
        compose.onNodeWithText("Previous").assertIsNotEnabled()
        compose.onNode(hasText("title", substring = true) and hasClickAction()).performClick()
        awaitText("Note 205")
        rowText("Note 205").assertExists()
    }

    @Test fun readonlyCellsCanBeInspectedWithoutShowingMutationControls() {
        openDatabase()
        rowText("Note 003").performClick()
        awaitText("Note 003")
        compose.onAllNodes(hasText("Save") and hasClickAction()).assertCountEquals(0)
        compose.onAllNodes(hasText("Delete row") and hasClickAction()).assertCountEquals(0)
        compose.onNodeWithText("Note 003", useUnmergedTree = true).assertExists()
        backToRows()
        compose.onNodeWithContentDescription("Database actions").performClick()
        compose.onAllNodes(hasText("Add row") and hasClickAction() and isEnabled()).assertCountEquals(0)
        compose.onAllNodesWithTag("sqliteInsertValue:title").assertCountEquals(0)
    }

    @Test fun filterControlsRestrictRowsAndClearRestoresTheTable() {
        openDatabase(rows = 205)
        compose.onNode(hasText("Filter", substring = true) and hasClickAction()).performClick()
        choose("sqliteFilterColumn", "title")
        choose("sqliteFilterOperator", "Contains")
        compose.onNodeWithTag("sqliteFilterValue").performTextInput("Note 00")
        compose.onNodeWithText("Apply").performClick()
        awaitText("Note 009")
        rowText("Note 009").assertExists()
        rowText("Note 205").assertDoesNotExist()
        compose.onNodeWithText("Next").assertIsNotEnabled()

        compose.onNode(hasText("Filter", substring = true) and hasClickAction()).performClick()
        compose.onNodeWithText("Clear").performClick()
        awaitText("Note 205")
        rowText("Note 205").assertExists()
        compose.onNodeWithText("Next").assertIsEnabled()
    }

    @Test fun rowFormsInsertDefaultsEditOneCellAndDeleteOnlyTheChosenRow() {
        openDatabase()
        enableEditing()
        action("Add row")
        choose("sqliteInsertType:title", "Text")
        compose.onNodeWithTag("sqliteInsertValue:title").performTextInput("  new note  ")
        compose.onNodeWithText("Save").performClick()
        awaitRows()
        compose.onNodeWithTag("sqliteRows").performScrollToIndex(3)
        awaitRow("  new note  ")
        rowText("  new note  ").assertExists()
        assertEquals("7", scalar("SELECT score FROM notes WHERE title = '  new note  '"))
        assertEquals("4", scalar("SELECT COUNT(*) FROM notes"))

        rowText("  new note  ").performClick()
        compose.onNodeWithTag("sqliteCellValue").performTextReplacement("updated")
        compose.onNodeWithText("Save").performClick()
        awaitRows()
        compose.onNodeWithTag("sqliteRows").performScrollToIndex(3)
        awaitRow("updated")
        rowText("updated").assertExists()
        assertEquals("1", scalar("SELECT COUNT(*) FROM notes WHERE title = 'updated'"))
        assertEquals("3", scalar("SELECT COUNT(*) FROM notes WHERE title LIKE 'Note %'"))

        compose.onNodeWithContentDescription("Delete row 4").performClick()
        compose.onNodeWithText("Delete", substring = false).performClick()
        awaitRows()
        assertEquals("0", scalar("SELECT COUNT(*) FROM notes WHERE title = 'updated'"))
        assertEquals("3", scalar("SELECT COUNT(*) FROM notes"))
    }

    @Test fun anOverlongSingleLineCellUsesReadonlyRowsInAnEditableDatabase() {
        val content = "0123456789".repeat(2_500) + "cell end"
        openDatabase(rows = 1) { database ->
            database.execSQL("UPDATE notes SET title = ? WHERE id = 1", arrayOf(content))
        }
        enableEditing()
        rowText(content.take(512)).performClick()
        awaitCondition("Long cell loaded as rows", 15_000) {
            compose.onAllNodesWithTag("textRows").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag("sqliteCellValue").assertCountEquals(0)
        compose.onAllNodes(hasText("Save") and hasClickAction()).assertCountEquals(0)
        compose.onNodeWithTag("textRows").performScrollToIndex(24)
        compose.onNode(hasTestTag("textRow") and hasText("cell end", substring = true), useUnmergedTree = true)
            .assertExists()
        backToRows()
        assertEquals(content, scalar("SELECT title FROM notes WHERE id = 1"))
    }

    @Test fun failedSqlRefreshesRowsThatChangedBeforeTheError() {
        openDatabase(rows = 2) { database ->
            database.execSQL("CREATE TRIGGER stop_second BEFORE UPDATE ON notes WHEN old.id = 2 " +
                "BEGIN SELECT RAISE(FAIL, 'second row blocked'); END")
        }
        enableEditing()
        compose.onNodeWithTag("sqliteSql").performTextInput("UPDATE notes SET title = 'changed'")
        compose.onNodeWithText("Run").performClick()
        awaitRow("changed")
        awaitCondition("SQL failure is visible", 15_000) {
            compose.onAllNodesWithText("second row blocked", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rowText("changed").assertExists()
        compose.onNodeWithTag("sqliteRows").performScrollToIndex(1)
        rowText("Note 001").assertExists()
        assertEquals("changed", scalar("SELECT title FROM notes WHERE id = 1"))
        assertEquals("Note 001", scalar("SELECT title FROM notes WHERE id = 2"))
    }
}
