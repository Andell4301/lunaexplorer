package com.lunaexplorer.app.storage

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqliteBrowserTest {
    private val browser = SqliteBrowser(ApplicationProvider.getApplicationContext())

    @Test fun `a file that is not a database is refused and left exactly as it was`() = runBlocking {
        val file = File.createTempFile("luna-not-a-db", ".db")
        try {
            val garbage = ByteArray(4096) { (it * 31).toByte() }
            file.writeBytes(garbage)
            val result = runCatching { browser.open(file.absolutePath) }
            assertTrue("A non-database must be refused", result.isFailure)
            assertTrue("The file must still exist", file.isFile)
            assertArrayEquals("The file must be untouched", garbage, file.readBytes())
        } finally {
            file.delete()
        }
    }

    @Test fun `a real database opens read-only by default and lists its tables`() = runBlocking {
        val file = File.createTempFile("luna-real", ".db")
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT)")
                db.execSQL("INSERT INTO notes(body) VALUES ('first'), ('second')")
            }
            browser.open(file.absolutePath).use { handle ->
                assertFalse("Read-only unless asked", handle.writable)
                // The platform-created android_metadata table is listed too.
                val tables = browser.objects(handle).filter { it.kind == "table" }
                assertTrue(tables.any { it.name == "notes" })
                assertNull("Listing the schema does not count table rows", tables.single { it.name == "notes" }.rowCount)
                val page = browser.page(handle, "notes", 0)
                assertEquals(listOf("id", "body"), page.columns)
                assertEquals("first", page.rows[0].values[1].text)
                assertNotNull("Rows carry an identity for editing", page.rows[0].identity)
            }
        } finally {
            file.delete()
        }
    }

    @Test fun `editing works only after asking for it, and changes exactly one row`() = runBlocking {
        val file = File.createTempFile("luna-edit", ".db")
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT)")
                db.execSQL("INSERT INTO notes(body) VALUES ('first'), ('second')")
            }
            browser.open(file.absolutePath, forWriting = true).use { handle ->
                assertTrue(handle.writable)
                val page = browser.page(handle, "notes", 0)
                val identity = page.rows[0].identity!!
                browser.updateCell(handle, "notes", "body", identity, "changed", Cursor.FIELD_TYPE_STRING).getOrThrow()
                val after = browser.page(handle, "notes", 0)
                assertEquals("changed", after.rows[0].values[1].text)
                assertEquals("The other row is untouched", "second", after.rows[1].values[1].text)
            }
        } finally {
            file.delete()
        }
    }

    @Test fun `views never expose row identities for editing`() = runBlocking {
        withDatabase(
            "CREATE TABLE notes (body TEXT)",
            "INSERT INTO notes VALUES ('first')",
            "CREATE VIEW visible_notes AS SELECT 99 AS _rowid_, body FROM notes",
        ) { handle ->
            assertNull(browser.page(handle, "visible_notes", 0).rows.single().identity)
        }
    }

    @Test fun `raw queries report when their result is truncated`() = runBlocking {
        withDatabase("CREATE TABLE notes (body TEXT)") { handle ->
            val page = browser.execute(handle,
                "WITH RECURSIVE numbers(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM numbers WHERE n<501) SELECT n FROM numbers",
            ).getOrThrow()
            assertEquals(500, page.rows.size)
            assertTrue(page.more)
        }
    }

    @Test fun `raw queries accept comments around statements without stripping quoted text`() = runBlocking {
        withDatabase("CREATE TABLE notes (body TEXT)") { handle ->
            browser.open(handle.path).use { readOnly ->
                val result = browser.execute(readOnly,
                    "-- read\n/* query */ SELECT 'a; -- /* quoted */' AS value; -- trailing comment",
                ).getOrThrow()
                assertEquals("a; -- /* quoted */", result.rows.single().values.single().text)
                assertEquals("2", browser.execute(readOnly, "SELECT(2) -- trailing comment").getOrThrow()
                    .rows.single().values.single().text)
            }
        }
    }

    @Test fun `transaction commands are refused without blocking later queries or mutations`() = runBlocking {
        withDatabase("CREATE TABLE notes (body TEXT)") { handle ->
            for (sql in listOf(
                "BEGIN", "BEGIN IMMEDIATE", "; /* transaction */ BEGIN;",
                "SAVEPOINT editing", "RELEASE editing", "COMMIT", "END", "ROLLBACK", "ROLLBACK TO editing",
            )) {
                assertTrue("Transaction command must be refused: $sql", browser.execute(handle, sql).isFailure)
            }
            withTimeout(10_000) {
                browser.insertRow(handle, "notes", mapOf("body" to SqlInput("original", Cursor.FIELD_TYPE_STRING))).getOrThrow()
                browser.execute(handle, "UPDATE notes SET body = 'changed'").getOrThrow()
                assertEquals("changed", browser.page(handle, "notes", 0).rows.single().values.single().text)
                assertEquals("1", browser.execute(handle, "SELECT COUNT(*) FROM notes").getOrThrow()
                    .rows.single().values.single().text)
            }
        }
    }

    @Test fun `CTE mutations require editing and execute their changes`() = runBlocking {
        withDatabase("CREATE TABLE notes (body TEXT)") { handle ->
            val insert = "WITH source(body) AS (SELECT 'original') INSERT INTO notes SELECT body FROM source"
            browser.open(handle.path).use { readOnly ->
                assertTrue(browser.execute(readOnly, insert).isFailure)
            }
            browser.execute(handle, insert).getOrThrow()
            browser.execute(handle,
                "WITH source(body) AS (SELECT 'changed'), ignored AS (SELECT '(UPDATE)') UPDATE notes SET body = (SELECT body FROM source)",
            ).getOrThrow()
            assertEquals("changed", browser.page(handle, "notes", 0).rows.single().values.single().text)
            browser.execute(handle, "WITH target AS (SELECT 'changed' AS body) DELETE FROM notes WHERE body IN (SELECT body FROM target)").getOrThrow()
            assertEquals(0L, browser.count(handle, "notes"))
        }
    }

    @Test fun `closing a handle during a row mutation lets its transaction finish`() = runBlocking {
        withDatabase(
            "CREATE TABLE notes (body TEXT)",
            "INSERT INTO notes VALUES ('original')",
            "CREATE TRIGGER wait_update BEFORE UPDATE ON notes BEGIN SELECT luna_wait(new.body); END",
        ) { handle ->
            val started = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            handle.database.setCustomScalarFunction("luna_wait") { value ->
                started.complete(Unit)
                release.await()
                value
            }
            val identity = browser.page(handle, "notes", 0).rows.single().identity!!
            withTimeout(10_000) {
                val editing = async(Dispatchers.IO) {
                    browser.updateCell(handle, "notes", "body", identity, "changed", Cursor.FIELD_TYPE_STRING)
                }
                try {
                    started.await()
                    handle.close()
                } finally {
                    release.countDown()
                }
                editing.await().getOrThrow()
                browser.open(handle.path, forWriting = true).use { reopened ->
                    assertEquals("changed", browser.page(reopened, "notes", 0).rows.single().values.single().text)
                    browser.insertRow(reopened, "notes", mapOf("body" to SqlInput("second", Cursor.FIELD_TYPE_STRING))).getOrThrow()
                    assertEquals(2L, browser.count(reopened, "notes"))
                }
            }
        }
    }

    @Test fun `canceling a running query releases its cursor and keeps the database usable`() = runBlocking {
        withDatabase("CREATE TABLE notes (body TEXT)", "INSERT INTO notes VALUES ('original')") { handle ->
            val started = CompletableDeferred<Unit>()
            handle.database.setCustomScalarFunction("luna_started") { value ->
                started.complete(Unit)
                value
            }
            withTimeout(10_000) {
                val running = async(Dispatchers.IO) {
                    browser.execute(handle,
                        "WITH RECURSIVE numbers(n) AS (VALUES(CAST(luna_started('1') AS INTEGER)) UNION ALL SELECT n+1 FROM numbers WHERE n<1000000000) SELECT sum(n) FROM numbers",
                    )
                }
                started.await()
                running.cancelAndJoin()
                assertTrue(running.isCancelled)
                assertEquals("original", browser.page(handle, "notes", 0).rows.single().values.single().text)
            }
        }
    }

    @Test fun `schema reports column constraints generated columns and index ownership`() = runBlocking {
        withDatabase(
            "CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL DEFAULT 'new', size INTEGER GENERATED ALWAYS AS (length(body)) VIRTUAL)",
            "CREATE INDEX notes_body ON notes(body)",
        ) { handle ->
            val columns = browser.columns(handle, "notes")
            assertEquals(1, columns.single { it.name == "id" }.primaryKey)
            assertTrue(columns.single { it.name == "body" }.notNull)
            assertEquals("'new'", columns.single { it.name == "body" }.defaultValue)
            assertTrue(columns.single { it.name == "size" }.hidden != 0)
            assertEquals("notes", browser.objects(handle).single { it.name == "notes_body" }.tableName)
        }
    }

    @Test fun `filters and descending sort persist across page boundaries and explicit counts`() = runBlocking {
        withDatabase(
            "CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT)",
            "INSERT INTO notes VALUES (1, 'first'), (2, NULL), (3, 'third'), (4, 'fourth')",
        ) { handle ->
            val filter = SqlFilter("body", SqlFilterOperator.IS_NOT_NULL)
            val sort = SqlSort("id", descending = true)
            val first = browser.page(handle, "notes", 0, 2, filter, sort)
            val second = browser.page(handle, "notes", 2, 2, filter, sort)
            assertEquals(listOf("4", "3"), first.rows.map { it.values[0].text })
            assertTrue(first.more)
            assertEquals(listOf("1"), second.rows.map { it.values[0].text })
            assertFalse(second.more)
            assertEquals(3L, browser.count(handle, "notes", filter))
            assertEquals(1L, browser.count(handle, "notes", SqlFilter("body", SqlFilterOperator.IS_NULL)))
            assertEquals(2L, browser.count(handle, "notes", SqlFilter("id", SqlFilterOperator.GREATER, "2")))
            assertEquals(1L, browser.count(handle, "notes", SqlFilter("id", SqlFilterOperator.LESS, "2")))
            assertEquals(3L, browser.count(handle, "notes", SqlFilter("id", SqlFilterOperator.NOT_EQUAL, "2")))
        }
    }

    @Test fun `quoted names and literal filter values cannot become SQL or LIKE wildcards`() = runBlocking {
        withDatabase("CREATE TABLE \"odd \"\"table\" (\"body\"\"text\" TEXT)") { handle ->
            val table = "odd \"table"
            val column = "body\"text"
            val literal = "50%_\\' OR 1=1 --"
            for (value in listOf(literal, "50zzzother", "ordinary")) {
                browser.insertRow(handle, table, mapOf(column to SqlInput(value, Cursor.FIELD_TYPE_STRING))).getOrThrow()
            }
            val matching = browser.page(handle, table, 0, filter = SqlFilter(column, SqlFilterOperator.CONTAINS, "%_\\"))
            assertEquals(listOf(literal), matching.rows.map { it.values.single().text })
            assertEquals(1L, browser.count(handle, table, SqlFilter(column, SqlFilterOperator.EQUAL, literal)))
            val identity = matching.rows.single().identity!!
            browser.updateCell(handle, table, column, identity, "changed", Cursor.FIELD_TYPE_STRING).getOrThrow()
            browser.deleteRow(handle, table, identity).getOrThrow()
            assertEquals(2L, browser.count(handle, table))
        }
    }

    @Test fun `insert preserves omitted defaults explicit nulls empty strings and typed values`() = runBlocking {
        withDatabase("CREATE TABLE notes (body TEXT DEFAULT 'new', number, payload)") { handle ->
            browser.insertRow(handle, "notes", emptyMap()).getOrThrow()
            browser.insertRow(handle, "notes", mapOf(
                "body" to SqlInput(null, Cursor.FIELD_TYPE_NULL),
                "number" to SqlInput("42", Cursor.FIELD_TYPE_INTEGER),
                "payload" to SqlInput("00ff", Cursor.FIELD_TYPE_BLOB),
            )).getOrThrow()
            browser.insertRow(handle, "notes", mapOf(
                "body" to SqlInput("", Cursor.FIELD_TYPE_STRING),
                "number" to SqlInput("2.5", Cursor.FIELD_TYPE_FLOAT),
                "payload" to SqlInput("42", Cursor.FIELD_TYPE_STRING),
            )).getOrThrow()
            val rows = browser.page(handle, "notes", 0).rows
            assertEquals("new", rows[0].values[0].text)
            assertTrue(rows[1].values[0].isNull)
            assertEquals(Cursor.FIELD_TYPE_INTEGER, rows[1].values[1].type)
            assertTrue(rows[1].values[2].isBlob)
            assertEquals("", rows[2].values[0].text)
            assertEquals(Cursor.FIELD_TYPE_FLOAT, rows[2].values[1].type)
            assertEquals(Cursor.FIELD_TYPE_STRING, rows[2].values[2].type)
            val blob = browser.execute(handle, "SELECT hex(payload) FROM notes WHERE number = 42").getOrThrow()
            assertEquals("00FF", blob.rows.single().values.single().text)
        }
    }

    @Test fun `read only handles refuse every row mutation`() = runBlocking {
        withDatabase("CREATE TABLE notes (body TEXT)", "INSERT INTO notes VALUES ('original')") { writable ->
            browser.open(writable.path).use { handle ->
                val identity = browser.page(handle, "notes", 0).rows.single().identity!!
                assertTrue(browser.insertRow(handle, "notes", emptyMap()).isFailure)
                assertTrue(browser.deleteRow(handle, "notes", identity).isFailure)
                assertTrue(browser.updateCell(handle, "notes", "body", identity, "changed", Cursor.FIELD_TYPE_STRING).isFailure)
                assertEquals("original", browser.page(handle, "notes", 0).rows.single().values.single().text)
            }
        }
    }

    @Test fun `invalid typed input and stale identities leave rows untouched`() = runBlocking {
        withDatabase("CREATE TABLE notes (number INTEGER)", "INSERT INTO notes VALUES (1), (2)") { handle ->
            val identity = browser.page(handle, "notes", 0).rows.first().identity!!
            assertTrue(browser.insertRow(handle, "notes", mapOf("number" to SqlInput("wrong", Cursor.FIELD_TYPE_INTEGER))).isFailure)
            assertTrue(browser.updateCell(handle, "notes", "number", identity, "wrong", Cursor.FIELD_TYPE_INTEGER).isFailure)
            assertTrue(browser.deleteRow(handle, "notes", "999").isFailure)
            browser.deleteRow(handle, "notes", identity).getOrThrow()
            assertEquals("2", browser.page(handle, "notes", 0).rows.single().values.single().text)
        }
    }

    @Test fun `failed single row mutations roll back trigger side effects`() = runBlocking {
        withDatabase(
            "CREATE TABLE notes (body TEXT)",
            "CREATE TABLE audit (body TEXT)",
            "INSERT INTO notes VALUES ('original')",
            "CREATE TRIGGER ignore_update BEFORE UPDATE ON notes BEGIN INSERT INTO audit VALUES ('update'); SELECT RAISE(IGNORE); END",
            "CREATE TRIGGER ignore_delete BEFORE DELETE ON notes BEGIN INSERT INTO audit VALUES ('delete'); SELECT RAISE(IGNORE); END",
        ) { handle ->
            val identity = browser.page(handle, "notes", 0).rows.single().identity!!
            assertTrue(browser.updateCell(handle, "notes", "body", identity, "changed", Cursor.FIELD_TYPE_STRING).isFailure)
            assertTrue(browser.deleteRow(handle, "notes", identity).isFailure)
            assertEquals(0L, browser.count(handle, "audit"))
            assertEquals("original", browser.page(handle, "notes", 0).rows.single().values.single().text)
        }
    }

    @Test fun `row mutations enforce foreign keys and declared cascades`() = runBlocking {
        withDatabase(
            "CREATE TABLE parents (id INTEGER PRIMARY KEY)",
            "CREATE TABLE children (parent INTEGER REFERENCES parents(id))",
            "CREATE TABLE cascading (parent INTEGER REFERENCES parents(id) ON DELETE CASCADE)",
            "INSERT INTO parents VALUES (1)",
            "INSERT INTO children VALUES (1)",
            "INSERT INTO cascading VALUES (1)",
        ) { handle ->
            val parent = browser.page(handle, "parents", 0).rows.single().identity!!
            val child = browser.page(handle, "children", 0).rows.single().identity!!
            assertTrue(browser.insertRow(handle, "children", mapOf("parent" to SqlInput("2", Cursor.FIELD_TYPE_INTEGER))).isFailure)
            assertTrue(browser.updateCell(handle, "children", "parent", child, "2", Cursor.FIELD_TYPE_INTEGER).isFailure)
            assertTrue(browser.deleteRow(handle, "parents", parent).isFailure)
            assertEquals(1L, browser.count(handle, "parents"))
            assertEquals(1L, browser.count(handle, "cascading"))
            browser.deleteRow(handle, "children", child).getOrThrow()
            browser.deleteRow(handle, "parents", parent).getOrThrow()
            assertEquals(0L, browser.count(handle, "parents"))
            assertEquals(0L, browser.count(handle, "cascading"))
        }
    }

    @Test fun `rowid shadowing never addresses a user column and absent rowids disable mutations`() = runBlocking {
        withDatabase(
            "CREATE TABLE shadows (_rowid_ TEXT, body TEXT)",
            "INSERT INTO shadows VALUES ('same', 'first'), ('same', 'second')",
            "CREATE TABLE all_shadows (_rowid_ TEXT, rowid TEXT, oid TEXT)",
            "INSERT INTO all_shadows VALUES ('one', 'two', 'three')",
            "CREATE TABLE keyed (key TEXT PRIMARY KEY, body TEXT) WITHOUT ROWID",
            "INSERT INTO keyed VALUES ('key', 'original')",
        ) { handle ->
            val first = browser.page(handle, "shadows", 0).rows.first()
            browser.updateCell(handle, "shadows", "body", first.identity!!, "changed", Cursor.FIELD_TYPE_STRING).getOrThrow()
            assertEquals(listOf("changed", "second"), browser.page(handle, "shadows", 0).rows.map { it.values[1].text })
            assertNull(browser.page(handle, "all_shadows", 0).rows.single().identity)
            assertNull(browser.page(handle, "keyed", 0).rows.single().identity)
            assertTrue(browser.deleteRow(handle, "keyed", "key").isFailure)
        }
    }

    private suspend fun withDatabase(vararg statements: String, block: suspend (SqliteBrowser.Handle) -> Unit) {
        val file = File.createTempFile("luna-sqlite", ".db")
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db -> statements.forEach(db::execSQL) }
            browser.open(file.absolutePath, forWriting = true).use { block(it) }
        } finally {
            file.delete()
        }
    }
}
