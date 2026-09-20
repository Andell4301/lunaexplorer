package com.lunaexplorer.app.ui

import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.RootKind
import com.lunaexplorer.core.StorageRoot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class DeleteGuardTest {
    @get:Rule val temporary = TemporaryFolder()
    private val paths = mutableMapOf<NodeRef, String>()

    private fun entry(path: String, directory: Boolean = true): Entry {
        val ref = NodeRef("local", path)
        paths[ref] = path
        return Entry(ref, path.trimEnd('/').substringAfterLast('/'), directory)
    }

    private fun root(path: String, kind: RootKind): StorageRoot {
        val ref = NodeRef("local", "root:$path")
        paths[ref] = path
        return StorageRoot(ref, path, kind = kind)
    }

    private fun guarded(entries: List<Entry>, vararg roots: StorageRoot): List<String> =
        DeleteGuard.androidFolders(entries, paths::get, DeleteGuard.volumePaths(roots.toList(), paths::get))

    @Test fun `the Android folder at the top of internal storage is guarded`() {
        val internal = root("/storage/emulated/0", RootKind.INTERNAL)
        assertEquals(listOf("/storage/emulated/0/Android"),
            guarded(listOf(entry("/storage/emulated/0/Android")), internal))
    }

    @Test fun `folders inside it, and a file called Android, are not`() {
        val internal = root("/storage/emulated/0", RootKind.INTERNAL)
        assertEquals(emptyList<String>(),
            guarded(listOf(entry("/storage/emulated/0/Android/data")), internal))
        assertEquals(emptyList<String>(),
            guarded(listOf(entry("/storage/emulated/0/Android", directory = false)), internal))
        assertEquals("Case matters: this is not the folder Android owns", emptyList<String>(),
            guarded(listOf(entry("/storage/emulated/0/android")), internal))
    }

    @Test fun `removable volumes are guarded too, granted folders and app data are not`() {
        val card = root("/storage/1234-5678", RootKind.SD_CARD)
        val usb = root("/mnt/media_rw/USB1", RootKind.USB)
        val granted = root("/storage/emulated/0/Pictures", RootKind.FOLDER)
        val appData = root("/data/user/0/com.lunaexplorer.app", RootKind.APP)
        val selection = listOf(
            entry("/storage/1234-5678/Android"),
            entry("/mnt/media_rw/USB1/Android"),
            entry("/storage/emulated/0/Pictures/Android"),
            entry("/data/user/0/com.lunaexplorer.app/Android"),
        )
        assertEquals(listOf("/storage/1234-5678/Android", "/mnt/media_rw/USB1/Android"),
            guarded(selection, card, usb, granted, appData))
    }

    @Test fun `trailing slashes on either side do not hide the match`() {
        val internal = root("/storage/emulated/0/", RootKind.INTERNAL)
        assertEquals(listOf("/storage/emulated/0/Android"),
            guarded(listOf(entry("/storage/emulated/0/Android/")), internal))
    }

    @Test fun `a selection without a path, or without any volume, is never guarded`() {
        val internal = root("/storage/emulated/0", RootKind.INTERNAL)
        val pathless = Entry(NodeRef("smb", "Android"), "Android", directory = true)
        assertEquals(emptyList<String>(), guarded(listOf(pathless), internal))
        assertEquals(emptyList<String>(), guarded(listOf(entry("/storage/emulated/0/Android"))))
    }

    @Test fun `the same folder reached through a linked volume path is guarded`() {
        // /sdcard and /storage/self/primary are symlinks to the volume.
        val volume = File(temporary.root, "storage/emulated/0").apply { mkdirs() }
        File(volume, "Android").mkdirs()
        val alias = File(temporary.root, "sdcard")
        Files.createSymbolicLink(alias.toPath(), volume.toPath())
        val internal = root(volume.path, RootKind.INTERNAL)

        assertEquals("The alias reaches the guarded folder", listOf("${alias.path}/Android"),
            guarded(listOf(entry("${alias.path}/Android")), internal))
        assertEquals("What is inside it is still not guarded", emptyList<String>(),
            guarded(listOf(entry("${alias.path}/Android/data")), internal))
    }
}
