package com.lunaexplorer.app.ui

import android.app.Application
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import com.lunaexplorer.app.AppGraph
import com.lunaexplorer.app.storage.AppSize
import com.lunaexplorer.app.storage.DuplicateReport
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.RootKind
import com.lunaexplorer.core.StorageRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Read budget of the automatic duplicate scan. The user can rerun it with a larger one. */
internal const val DUPLICATE_BUDGET = 3L * 1024 * 1024 * 1024

class StorageTools(
    private val application: Application,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val resolver: PathResolver,
    private val roots: () -> List<StorageRoot>,
    /** The browser's delete, which recycles when the bin is on. */
    private val delete: (List<Entry>) -> Unit,
    private val message: (String) -> Unit,
) {
    fun analyse(onPart: (StorageAnalysisPart) -> Unit) {
        scope.launch {
            val totals = runCatching { graph.media.categoryTotals() }.getOrNull()
            if (totals != null) onPart(StorageAnalysisPart.Categories(totals))

            onPart(StorageAnalysisPart.Older(runCatching { graph.media.oldestFiles(60) }.getOrDefault(emptyList())))
            onPart(StorageAnalysisPart.Largest(runCatching { graph.media.largestFiles(60) }.getOrDefault(emptyList())))
            onPart(StorageAnalysisPart.Apps(runCatching { graph.analysis.appSizes() }.getOrDefault(emptyList()),
                graph.analysis.canMeasureApps()))

            // The duplicate scan reads file contents, so it runs last. It covers the first scope on offer.
            findDuplicates(duplicateScopes().firstOrNull()?.second.orEmpty(), DUPLICATE_BUDGET) { report ->
                if (report != null) onPart(StorageAnalysisPart.Duplicates(report))
            }
        }
    }

    fun findDuplicates(under: List<String>, budgetBytes: Long, onResult: (DuplicateReport?) -> Unit) {
        scope.launch {
            val all = runCatching { graph.media.pathsAndSizes() }.getOrDefault(emptyList())
            // Filter the index by path prefix instead of walking the directories again.
            val prefixes = under.map { it.trimEnd('/') + '/' }
            val candidates = if (prefixes.isEmpty()) all
            else all.filter { file -> prefixes.any { file.first.startsWith(it) } }
            onResult(runCatching { graph.analysis.duplicates(candidates, budgetBytes) }.getOrNull())
        }
    }

    fun duplicateScopes(): List<Pair<String, List<String>>> {
        val base = sharedBase() ?: return emptyList()
        fun under(vararg names: String) = names.map { "$base/$it" }
        return listOf(
            "Internal storage" to listOf(base),
            "Downloads" to under(Environment.DIRECTORY_DOWNLOADS),
            "Music" to under(Environment.DIRECTORY_MUSIC),
            "Media" to under(Environment.DIRECTORY_DCIM, Environment.DIRECTORY_PICTURES,
                Environment.DIRECTORY_MOVIES),
        )
    }

    fun measureApps(onResult: (List<AppSize>, Boolean) -> Unit) {
        scope.launch {
            onResult(runCatching { graph.analysis.appSizes() }.getOrDefault(emptyList()),
                graph.analysis.canMeasureApps())
        }
    }

    fun deleteEntries(entries: List<Entry>) {
        if (entries.isEmpty()) message("Nothing to remove") else delete(entries)
    }

    fun deleteFiles(paths: List<String>) {
        scope.launch {
            deleteEntries(paths.mapNotNull { path ->
                val ref = resolver.refFor(path) ?: return@mapNotNull null
                runCatching { graph.providers.provider(ref).stat(ref) }.getOrNull()
            })
        }
    }

    /** Usage access has no runtime permission prompt; it is granted on this settings screen. */
    fun openUsageAccessSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = runCatching { application.startActivity(intent) }.isSuccess
        if (!started) message("This device has no usage access screen.")
    }

    private fun sharedBase(): String? = roots()
        .firstOrNull { it.kind == RootKind.INTERNAL }
        ?.let { resolver.pathOf(it.ref) }
        ?: runCatching { Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull()
}
