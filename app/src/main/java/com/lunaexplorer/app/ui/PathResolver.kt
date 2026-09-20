package com.lunaexplorer.app.ui

import com.lunaexplorer.app.model.Crumb
import com.lunaexplorer.app.model.Location
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.PathAddressable
import com.lunaexplorer.core.ProviderRegistry
import com.lunaexplorer.core.StorageRoot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** [roots] is read on every call because mounts and settings change the root list. */
class PathResolver(
    private val providers: ProviderRegistry,
    private val roots: () -> List<StorageRoot>,
) {
    data class Located(val location: Location, val used: String)

    suspend fun locate(path: String): Located? {
        val target = path.trimEnd('/').ifEmpty { "/" }
        return withContext(Dispatchers.IO) {
            // The deepest matching root gives the shortest breadcrumb chain.
            val roots = roots()
            val match = providers.all.asSequence()
                .mapNotNull { provider -> (provider as? PathAddressable)?.let { provider.id to it } }
                .flatMap { (id, addressable) ->
                    roots.filter { it.ref.provider == id }.asSequence()
                        .mapNotNull { root -> addressable.pathOf(root.ref)?.let { base -> Triple(root, base, addressable) } }
                }
                .filter { (_, base, _) -> target == base || target.startsWith(base.trimEnd('/') + "/") }
                .maxByOrNull { (_, base, _) -> base.length }
                ?: return@withContext null
            val (root, base, addressable) = match
            val relative = target.removePrefix(base.trimEnd('/')).trim('/')

            // Build the breadcrumbs from the path alone, without listing parents: a directory can be reachable
            // by name while its parent cannot be enumerated.
            var crumbs = listOf(Crumb(root.ref, root.title))
            var walked = base.trimEnd('/')
            for (name in relative.split('/').filter { it.isNotEmpty() }) {
                walked = "$walked/$name"
                val ref = addressable.refFor(walked) ?: return@withContext null
                crumbs = crumbs + Crumb(ref, name)
            }
            Located(Location(crumbs), path)
        }
    }

    suspend fun resolves(location: Location): Boolean = withContext(Dispatchers.IO) {
        try {
            providers.provider(location.ref).stat(location.ref)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            false
        }
    }

    /** A path the caller may open itself. */
    fun pathOf(ref: NodeRef): String? =
        (runCatching { providers.provider(ref) }.getOrNull() as? PathAddressable)?.pathOf(ref)

    /** The path to show, which this process may not be able to open. */
    fun shownPathOf(ref: NodeRef): String? =
        (runCatching { providers.provider(ref) }.getOrNull() as? PathAddressable)?.shownPathOf(ref)

    fun refFor(path: String): NodeRef? =
        providers.all.firstNotNullOfOrNull { (it as? PathAddressable)?.refFor(path) }

    suspend fun crumbsTo(ref: NodeRef): Location? {
        val provider = providers.provider(ref)
        val roots = roots().filter { it.ref.provider == ref.provider }
        val chain = ArrayList<Crumb>()
        var current = provider.parentOf(ref) ?: return null
        while (true) {
            val root = roots.firstOrNull { it.ref == current }
            if (root != null) { chain.add(Crumb(root.ref, root.title)); break }
            val name = try {
                provider.stat(current).name
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                return null
            }
            chain.add(Crumb(current, name))
            current = provider.parentOf(current) ?: return null
        }
        return Location(chain.asReversed())
    }
}
