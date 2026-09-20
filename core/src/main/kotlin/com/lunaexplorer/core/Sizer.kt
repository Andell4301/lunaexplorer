package com.lunaexplorer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

private const val MAX_DEPTH = 256

/** Running totals; only the final emission has [complete] set. */
data class FolderTotals(
    val files: Long = 0,
    val folders: Long = 0,
    val bytes: Long = 0,
    val unknownSizes: Long = 0,
    val unreadable: Long = 0,
    val complete: Boolean = false,
)

/** Measures through stat and list only. A subtree that cannot be read is counted in [FolderTotals.unreadable] and skipped. */
class FolderSizer(
    private val registry: ProviderRegistry,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun measure(refs: List<NodeRef>, emitEveryNanos: Long = 120_000_000L): Flow<FolderTotals> = flow {
        var totals = FolderTotals()
        var lastEmit = 0L
        suspend fun publish(force: Boolean) {
            val now = System.nanoTime()
            if (force || now - lastEmit >= emitEveryNanos) {
                lastEmit = now
                emit(totals)
            }
        }
        val seen = HashSet<NodeRef>()

        suspend fun walk(ref: NodeRef, entry: Entry?, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (depth > MAX_DEPTH || !seen.add(ref)) {
                totals = totals.copy(unreadable = totals.unreadable + 1)
                return
            }
            val provider = registry.provider(ref)
            val item = entry ?: try {
                provider.stat(ref)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                totals = totals.copy(unreadable = totals.unreadable + 1)
                return
            }
            // A link counts as one item and is not followed, so a linked subtree is not measured twice.
            if (!item.directory || item.link) {
                totals = totals.copy(
                    files = totals.files + 1,
                    bytes = totals.bytes + if (item.link) 0L else (item.size ?: 0L),
                    unknownSizes = totals.unknownSizes + if (item.size == null && !item.link) 1 else 0,
                )
                publish(false)
                return
            }
            if (depth > 0) totals = totals.copy(folders = totals.folders + 1)
            if (Capability.LIST !in item.capabilities) {
                totals = totals.copy(unreadable = totals.unreadable + 1)
                return
            }
            val children = mutableListOf<Entry>()
            try {
                provider.list(item.ref).collect { children.addAll(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                totals = totals.copy(unreadable = totals.unreadable + 1)
                return
            }
            publish(false)
            for (child in children) walk(child.ref, child, depth + 1)
        }

        for (ref in refs) walk(ref, null, 0)
        totals = totals.copy(complete = true)
        publish(true)
    }.flowOn(dispatcher)
}
