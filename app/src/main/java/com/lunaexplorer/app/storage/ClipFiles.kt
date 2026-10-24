package com.lunaexplorer.app.storage

import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.PathAddressable
import com.lunaexplorer.core.StorageProvider
import com.lunaexplorer.core.StorageRoot
import java.io.File
import java.nio.file.Files
import java.util.UUID

internal class ClipFiles(directory: File, probe: PathProbe = SystemProbe) {
    private val rendering = File(directory, "rendering")
    private val queued = File(directory, "queued")
    private var prepared = false
    val local = LocalStorageProvider(
        listOf(LocalRoot("exports", "Video clips", queued, hidden = true)),
        probe = probe,
        id = "video-clips",
    )
    val provider: StorageProvider = object : StorageProvider by local, PathAddressable by local {
        override suspend fun roots(): List<StorageRoot> = emptyList()
    }

    @Synchronized fun create(): File {
        if (!prepared) {
            // A dead viewer cannot finish a render; queued clips belong to the operation journal.
            rendering.deleteRecursively()
            prepared = true
        }
        check(rendering.isDirectory || rendering.mkdirs()) { "Could not prepare the clip folder" }
        return File.createTempFile("clip-", ".mp4", rendering)
    }

    fun take(file: File): Pair<File, NodeRef> {
        require(file.parentFile == rendering && file.isFile && file.length() > 0) { "The clip is unavailable" }
        check(queued.isDirectory || queued.mkdirs()) { "Could not prepare the clip folder" }
        val destination = File(queued, "${UUID.randomUUID()}.mp4")
        // A cancelled caller can delete its rendering path after the queue takes ownership.
        Files.move(file.toPath(), destination.toPath())
        return destination to requireNotNull(local.referenceTo(destination.absolutePath))
    }
}
