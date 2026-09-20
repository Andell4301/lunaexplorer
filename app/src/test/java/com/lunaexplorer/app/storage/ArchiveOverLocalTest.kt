package com.lunaexplorer.app.storage

import com.lunaexplorer.core.ArchiveProvider
import com.lunaexplorer.core.ProviderRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveOverLocalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `browsed through the channel the local provider offers`() = runBlocking {
        val folder = temporary.newFolder()
        val zip = File(folder, "bundle.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("a.txt")); out.write("alpha".toByteArray()); out.closeEntry()
            out.putNextEntry(ZipEntry("dir/b.txt")); out.write("bravo".toByteArray()); out.closeEntry()
        }
        val local = LocalStorageProvider(listOf(LocalRoot("workspace", "workspace", folder)), probe = PathProbe.OF_FILESYSTEM)
        lateinit var registry: ProviderRegistry
        val inside = ArchiveProvider({ registry })
        registry = ProviderRegistry(listOf(local, inside))

        val root = inside.open(requireNotNull(local.referenceTo(zip.absolutePath)), "bundle.zip")
        val top = inside.list(root).toList().flatten()
        val nested = inside.list(top.single { it.directory }.ref).toList().flatten().single()

        assertEquals(listOf("a.txt", "dir"), top.map { it.name }.sorted())
        assertEquals("alpha", inside.openRead(top.single { it.name == "a.txt" }.ref).use { it.readBytes().decodeToString() })
        assertEquals("bravo", inside.openRead(nested.ref).use { it.readBytes().decodeToString() })
    }
}
