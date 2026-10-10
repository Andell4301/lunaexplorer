package com.lunaexplorer.app.ui

import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.core.ArchiveFormat
import com.lunaexplorer.core.ArchiveOptions
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class ArchiveTrashUiTest : RobolectricBrowserUiTest() {
    private val viewModel get() = compose.runOnUiThread {
        ViewModelProvider(compose.activity)[BrowserViewModel::class.java]
    }

    private fun recycle(entry: Entry): Entry {
        compose.runOnUiThread { viewModel.operations.deleteEntries(listOf(entry), toBin = true) }
        awaitCondition("Source recycled", 15_000) {
            fixture.graph.database.trash.value.any { it.originalName == entry.name }
        }
        val ref = fixture.graph.database.trash.value.single { it.originalName == entry.name }.ref
        return runBlocking { fixture.graph.providers.provider(ref).stat(ref) }
    }

    private fun awaitOperation(title: String) {
        awaitCondition("$title completed", 15_000) {
            fixture.graph.database.queue.value.any { it.title == title && it.status == "SUCCEEDED" }
        }
    }

    private fun assertRetained(entry: Entry) {
        assertEquals(entry.ref, runBlocking { fixture.graph.providers.provider(entry.ref).stat(entry.ref).ref })
        assertTrue(fixture.graph.database.trash.value.any { it.ref == entry.ref })
    }

    private fun assertRemoved(entry: Entry) {
        val error = assertThrows(StorageException::class.java) {
            runBlocking { fixture.graph.providers.provider(entry.ref).stat(entry.ref) }
        }
        assertEquals(StorageError.NOT_FOUND, error.reason)
        assertFalse(fixture.graph.database.trash.value.any { it.ref == entry.ref })
    }

    @Test fun archivingRemovesTrashRecordOnlyAfterSourceDeletion() {
        awaitListing()
        val source = recycle(viewModel.state.value.entries.single { it.name == "beta.txt" })

        compose.runOnUiThread {
            viewModel.operations.createArchive(listOf(source), "kept.zip", ArchiveOptions(ArchiveFormat.ZIP))
        }
        awaitOperation("Create archive kept.zip")
        assertRetained(source)

        compose.runOnUiThread {
            viewModel.operations.createArchive(listOf(source), "removed.zip", ArchiveOptions(ArchiveFormat.ZIP),
                deleteAfter = true)
        }
        awaitOperation("Create archive removed.zip")
        assertTrue(File(fixture.directory, "removed.zip").isFile)
        assertRemoved(source)
    }

    @Test fun extractionRemovesTrashRecordOnlyAfterArchiveDeletion() {
        awaitListing()
        val archive = File(fixture.directory, "input.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("sample.txt"))
            zip.write("Archived content".toByteArray())
            zip.closeEntry()
        }
        val ref = requireNotNull(fixture.graph.local.refFor(archive.absolutePath))
        val source = recycle(runBlocking { fixture.graph.providers.provider(ref).stat(ref) })

        compose.runOnUiThread { viewModel.operations.extractArchive(source, "kept") }
        awaitOperation("Extract ${source.name}")
        assertRetained(source)
        val completed = fixture.graph.database.queue.value.map { it.id }.toSet()

        compose.runOnUiThread { viewModel.operations.extractArchive(source, "removed", deleteAfter = true) }
        awaitCondition("Extraction and archive deletion completed", 15_000) {
            fixture.graph.database.queue.value.any { it.id !in completed && it.status == "SUCCEEDED" }
        }
        assertTrue(File(fixture.directory, "removed/sample.txt").isFile)
        assertRemoved(source)
    }
}
