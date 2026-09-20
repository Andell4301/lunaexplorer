package com.lunaexplorer.app.storage

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
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
                assertEquals(2L, tables.single { it.name == "notes" }.rowCount)
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
}
