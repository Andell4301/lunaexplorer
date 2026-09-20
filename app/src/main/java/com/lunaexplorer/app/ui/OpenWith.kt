package com.lunaexplorer.app.ui

import com.lunaexplorer.app.AppGraph
import com.lunaexplorer.app.data.OpenDefault
import com.lunaexplorer.app.storage.OpenCandidate
import com.lunaexplorer.core.Entry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class OpenWith(private val graph: AppGraph, private val scope: CoroutineScope) {

    suspend fun candidates(entry: Entry, mimeType: String): List<OpenCandidate> {
        val uri = runCatching { graph.uriFor(entry.ref) }.getOrNull() ?: return emptyList()
        return graph.openTargets.candidates(uri, mimeType)
    }

    suspend fun icons(candidates: List<OpenCandidate>) = graph.openTargets.icons(candidates)

    /** Lookup keys for a remembered choice, most specific first. */
    private fun defaultKeys(entry: Entry): List<String> = buildList {
        entry.name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }?.let { add("ext:$it") }
        add("mime:${entry.mimeType}")
    }

    fun remember(entry: Entry, target: String, mime: String, label: String) {
        scope.launch {
            graph.database.rememberOpenDefault(defaultKeys(entry).first(), target, mime, label)
        }
    }

    suspend fun defaultFor(entry: Entry): OpenDefault? = graph.database.openDefault(defaultKeys(entry))

    suspend fun defaults(): List<OpenDefault> = graph.database.openDefaults()

    fun forget(key: String, onDone: () -> Unit = {}) {
        scope.launch { graph.database.forgetOpenDefault(key); onDone() }
    }

    fun forgetAll(onDone: () -> Unit = {}) {
        scope.launch { graph.database.forgetAllOpenDefaults(); onDone() }
    }
}
