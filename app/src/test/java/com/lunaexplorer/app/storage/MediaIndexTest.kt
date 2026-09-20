package com.lunaexplorer.app.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
import android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_NONE
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MediaIndexTest {
    class TestMediaStore : ContentProvider() {
        private lateinit var database: SQLiteDatabase

        override fun onCreate(): Boolean {
            database = SQLiteDatabase.create(null)
            database.execSQL("CREATE TABLE media (_data TEXT, _display_name TEXT, _size INTEGER, date_modified INTEGER, mime_type TEXT, media_type INTEGER)")
            return true
        }

        fun add(file: File, mediaType: Int) {
            database.insertOrThrow("media", null, ContentValues().apply {
                put("_data", file.path)
                put("_display_name", file.name)
                put("_size", 100)
                put("date_modified", 1)
                put("mime_type", "image/jpeg")
                put("media_type", mediaType)
            })
        }

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, arguments: Array<String>?, order: String?): Cursor =
            database.query("media", projection, selection, arguments, null, null, order)

        override fun shutdown() { database.close(); super.shutdown() }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, arguments: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, arguments: Array<String>?) = 0
    }

    @get:Rule val temporary = TemporaryFolder()
    private lateinit var media: TestMediaStore
    private val index by lazy {
        val local = LocalStorageProvider(listOf(LocalRoot("test", "Test", temporary.root)), probe = PathProbe.OF_FILESYSTEM)
        MediaIndex(RuntimeEnvironment.getApplication(), local)
    }

    @Before fun media() { media = Robolectric.buildContentProvider(TestMediaStore::class.java).create("media").get() }
    @After fun close() { media.shutdown() }

    private fun photos(): Set<String> = runBlocking {
        index.query(MediaCategory.IMAGES).toList().flatMap { it.entries }.map { it.name }.toSet()
    }

    @Test fun `unclassified photos are included only when the nomedia option is enabled`() {
        media.add(File(temporary.root, "visible.jpg"), MEDIA_TYPE_IMAGE)
        media.add(File(temporary.root, "hidden/photo.jpg"), MEDIA_TYPE_NONE)

        assertEquals(setOf("visible.jpg"), photos())

        index.includeNomedia = true
        assertEquals(setOf("visible.jpg", "photo.jpg"), photos())
    }

    @Test fun `recycled photos stay excluded when unclassified photos are included`() {
        media.add(File(temporary.root, "visible.jpg"), MEDIA_TYPE_IMAGE)
        media.add(File(temporary.root, ".LunaTrash/classified.jpg"), MEDIA_TYPE_IMAGE)
        media.add(File(temporary.root, ".LunaTrash/unclassified.jpg"), MEDIA_TYPE_NONE)

        listOf(false, true).forEach { include ->
            index.includeNomedia = include
            assertEquals(setOf("visible.jpg"), photos())
        }
    }
}
