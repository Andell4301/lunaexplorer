package com.lunaexplorer.app.storage

import com.lunaexplorer.core.ArchiveEngine
import com.lunaexplorer.core.ProviderRegistry
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveExtractionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `extraction refuses existing symbolic links without changing their targets`() = runBlocking {
        val provider = LocalStorageProvider(
            listOf(LocalRoot("device", "Device", temporary.root, followLinks = true)),
            probe = PathProbe.OF_FILESYSTEM,
        )
        val engine = ArchiveEngine(ProviderRegistry(listOf(provider)))
        for (target in listOf("directory", "directory-entry", "file")) {
            val destination = temporary.newFolder("output-$target")
            val outside = temporary.newFolder("outside-$target")
            val original = File(outside, "keep.txt").apply { writeText("Original contents") }
            val link = File(destination, "linked")
            Files.createSymbolicLink(link.toPath(), (if (target == "file") original else outside).toPath())
            val archive = temporary.newFile("input-$target.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                if (target == "directory-entry") {
                    zip.putNextEntry(ZipEntry("linked/"))
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry(if (target == "file") "linked" else "linked/keep.txt"))
                zip.write("Replaced by archive".toByteArray())
                zip.closeEntry()
            }

            val failure = runCatching {
                engine.extract(
                    requireNotNull(provider.referenceTo(archive.path)),
                    requireNotNull(provider.referenceTo(destination.path)),
                    into = null,
                ).last()
            }.exceptionOrNull()

            assertEquals("Extraction must not overwrite a link's target", "Original contents", original.readText())
            assertTrue("Extraction must report the symbolic link", failure is StorageException)
            assertEquals(StorageError.UNSUPPORTED, (failure as StorageException).reason)
            assertTrue(Files.isSymbolicLink(link.toPath()))
            // Ordinary browsing still follows links on this root; only extraction refuses them.
            val linked = requireNotNull(provider.referenceTo(link.path))
            assertTrue(provider.stat(linked).link)
            if (target == "file") {
                assertEquals("Original contents", provider.openRead(linked).use { it.readBytes().decodeToString() })
            } else {
                assertEquals(listOf("keep.txt"), provider.list(linked).toList().flatten().map { it.name })
            }
        }
    }
}
