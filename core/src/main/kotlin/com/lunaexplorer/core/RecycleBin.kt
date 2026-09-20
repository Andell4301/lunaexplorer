package com.lunaexplorer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class TrashedItem(
    val id: String,
    val name: String,
    val ref: NodeRef,
    val originalParent: NodeRef,
    val originalName: String,
    val originalPath: String?,
    val directory: Boolean,
    val size: Long?,
    val deletedAt: Long,
)

/** One bin per storage root, so trashing is a rename within the volume rather than a copy. */
class RecycleBin(private val registry: ProviderRegistry) {
    companion object {
        const val FOLDER = ".LunaTrash"
    }

    /** The bin folder on [ref]'s root, created if missing. Null if that storage cannot host one. */
    suspend fun folderFor(ref: NodeRef): Entry? = withContext(Dispatchers.IO) {
        try {
            val provider = registry.provider(ref)
            val root = containingRoot(provider, ref) ?: return@withContext null
            if (Capability.CREATE !in provider.stat(root).capabilities) return@withContext null
            provider.child(root, FOLDER) ?: provider.create(root, FOLDER, directory = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            null
        }
    }

    private suspend fun containingRoot(provider: StorageProvider, ref: NodeRef): NodeRef? {
        val candidates = provider.roots().filter { !it.readOnly }
        var best: NodeRef? = null
        for (candidate in candidates) {
            try {
                if (!provider.isDescendant(ref, candidate.ref)) continue
                if (best == null || provider.isDescendant(candidate.ref, best)) best = candidate.ref
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
            }
        }
        return best
    }
}
