package com.lunaexplorer.app.ui

import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.RootKind
import com.lunaexplorer.core.StorageRoot
import java.io.File

internal object DeleteGuard {
    /** Storage kinds whose top-level Android folder holds every app's data and downloads. */
    private val VOLUMES = setOf(RootKind.INTERNAL, RootKind.SD_CARD, RootKind.USB)

    fun thresholdBytes(largeDeleteGb: Int): Long = largeDeleteGb * 1024L * 1024 * 1024

    fun volumePaths(roots: List<StorageRoot>, pathOf: (NodeRef) -> String?): List<String> =
        roots.filter { it.kind in VOLUMES }.mapNotNull { pathOf(it.ref) }

    fun androidFolders(entries: List<Entry>, pathOf: (NodeRef) -> String?, volumes: List<String>): List<String> {
        if (volumes.isEmpty()) return emptyList()
        val guarded = volumes.mapTo(HashSet()) { resolved(it.trimEnd('/') + "/Android") }
        return entries.asSequence()
            .filter { it.directory }
            .mapNotNull { pathOf(it.ref)?.trimEnd('/') }
            // Compare canonical paths: /sdcard and /storage/self/primary alias the same volume. The result
            // keeps each path as it was navigated.
            .filter { resolved(it) in guarded }
            .toList()
    }

    private fun resolved(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)
}
