package com.lunaexplorer.app.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.Preferences
import com.lunaexplorer.core.StorageException
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DatabaseSnapshotTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "snapshot-test-${UUID.randomUUID()}.db"
    private val source = context.getDatabasePath(name)
    private val database = LunaDatabase(context, name)

    @After fun cleanup() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test fun `a snapshot includes committed WAL data and remains independent of later writes`() = runBlocking {
        database.writableDatabase.enableWriteAheadLogging()
        val before = BrowserState(preferences = Preferences(showHidden = true))
        database.saveSession(before)
        assertTrue(File("${source.path}-wal").length() > 0)
        val copy = requireNotNull(database.snapshot(source.path, "日本語\u200b.db"))
        try {
            database.saveSession(before.copy(preferences = Preferences(showHidden = false)))
            SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { snapshot ->
                snapshot.rawQuery("SELECT payload FROM session WHERE id=1", null).use { row ->
                    assertTrue(row.moveToFirst())
                    assertTrue(SessionCodec.decode(row.getString(0)).preferences.showHidden)
                }
                snapshot.rawQuery("PRAGMA integrity_check", null).use { row ->
                    assertTrue(row.moveToFirst())
                    assertEquals("ok", row.getString(0))
                }
            }
            assertFalse(requireNotNull(database.loadSession()).preferences.showHidden)
        } finally {
            copy.parentFile?.deleteRecursively()
        }
    }

    @Test fun `an owned snapshot failure is explicit and cleans up without deleting the source`() = runBlocking {
        source.parentFile!!.mkdirs()
        val bytes = "This is not a database".toByteArray()
        source.writeBytes(bytes)
        val before = context.cacheDir.listFiles().orEmpty().map { it.name }.toSet()
        val failure = runCatching { database.snapshot(source.path, name) }.exceptionOrNull()
        assertTrue("A failed owned snapshot must not look like a non-owned file", failure is StorageException)
        assertArrayEquals(bytes, source.readBytes())
        assertEquals(before, context.cacheDir.listFiles().orEmpty().map { it.name }.toSet())

        val sibling = File(source.parentFile!!.path + "-other", name)
        sibling.parentFile!!.mkdirs()
        sibling.writeBytes(bytes)
        try {
            assertNull(database.snapshot(sibling.path, name))
            assertArrayEquals(bytes, sibling.readBytes())
        } finally {
            sibling.delete()
            sibling.parentFile?.delete()
        }
    }
}
