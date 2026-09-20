package com.lunaexplorer.app.storage.shizuku

import android.os.Build
import com.lunaexplorer.app.storage.LocalStorageProvider
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.PathAddressable
import com.lunaexplorer.core.RetainedObjects
import com.lunaexplorer.core.RootKind
import com.lunaexplorer.core.StorageException
import com.lunaexplorer.core.StorageProvider
import com.lunaexplorer.core.StorageRoot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel

// Delegating with by direct would make default methods bypass the helper.
internal class AssistedLocalProvider(
    private val direct: LocalStorageProvider,
    /** Luna's package, whose own folder in there Android leaves open to it. */
    private val ownPackage: String,
    /** Null while the helper is switched off or not connected. */
    private val helper: () -> HelperFiles?,
) : StorageProvider, PathAddressable {
    override val id get() = direct.id
    override val features get() = direct.features

    override fun namesEqual(first: String, second: String) = direct.namesEqual(first, second)
    override suspend fun roots(): List<StorageRoot> = direct.roots()
    override suspend fun forgetRoot(root: StorageRoot) = direct.forgetRoot(root)
    override suspend fun parentOf(ref: NodeRef): NodeRef? = direct.parentOf(ref)

    /** By name where the helper is involved: the direct provider compares real paths, which it cannot read in there. */
    override suspend fun isDescendant(candidate: NodeRef, ancestor: NodeRef): Boolean {
        if (assisting(candidate) == null && assisting(ancestor) == null) return direct.isDescendant(candidate, ancestor)
        val below = direct.locationOf(candidate) ?: return false
        val above = direct.locationOf(ancestor)?.trimEnd('/') ?: return false
        return below == above || below.startsWith("$above/")
    }
    override val notices get() = direct.notices

    override fun refFor(path: String): NodeRef? = direct.refFor(path)

    /** Null for a file only the helper can open: a caller given a path would try to open it itself. */
    override fun pathOf(ref: NodeRef): String? = if (assisting(ref) != null) null else direct.pathOf(ref)

    override fun shownPathOf(ref: NodeRef): String? = direct.pathOf(ref) ?: direct.locationOf(ref)?.takeIf { closed(it) }

    override suspend fun stat(ref: NodeRef): Entry = assisting(ref)?.stat(ref) ?: direct.stat(ref)

    override fun list(parent: NodeRef, complete: Boolean): Flow<List<Entry>> = flow {
        val assisted = assisting(parent)
        if (assisted != null) { emitAll(assisted.list(parent, complete)); return@flow }
        val files = helper()
        val here = direct.locationOf(parent)
        // In a volume's Android folder, "data" and "obb" are described by whoever will open them.
        if (files == null || here == null || !closed("$here/data")) { emitAll(direct.list(parent, complete)); return@flow }
        emitAll(direct.list(parent, complete).map { batch ->
            batch.map { entry ->
                if (!closed("$here/${entry.name}")) entry
                // Android's own description stands if the helper cannot give one.
                else try { files.stat(entry.ref) } catch (failed: StorageException) { entry }
            }
        })
    }

    override suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String): Entry =
        assisting(parent)?.create(parent, name, directory, mimeType) ?: direct.create(parent, name, directory, mimeType)

    override suspend fun rename(ref: NodeRef, name: String): Entry = assisting(ref)?.rename(ref, name) ?: direct.rename(ref, name)

    override suspend fun delete(ref: NodeRef) { assisting(ref)?.delete(ref) ?: direct.delete(ref) }

    override suspend fun setModified(ref: NodeRef, epochMillis: Long): Long =
        assisting(ref)?.setModified(ref, epochMillis) ?: direct.setModified(ref, epochMillis)

    override suspend fun openRead(ref: NodeRef): InputStream = assisting(ref)?.openRead(ref) ?: direct.openRead(ref)

    override suspend fun openChannel(ref: NodeRef): SeekableByteChannel = assisting(ref)?.openChannel(ref) ?: direct.openChannel(ref)

    override suspend fun openWrite(ref: NodeRef): OutputStream = assisting(ref)?.openWrite(ref) ?: direct.openWrite(ref)

    override suspend fun availableBytes(parent: NodeRef): Long? {
        val assisted = assisting(parent) ?: return direct.availableBytes(parent)
        return assisted.availableBytes(parent)
    }

    /** The helper reaches both sides, so a move across the boundary is still a rename and not a copy. */
    override suspend fun relocate(ref: NodeRef, parent: NodeRef, name: String): Entry? {
        val assisted = assisting(ref) ?: assisting(parent) ?: return direct.relocate(ref, parent, name)
        return assisted.relocate(ref, parent, name)
    }

    override suspend fun commit(staged: NodeRef, parent: NodeRef, name: String, replace: Entry?, onRetained: RetainedObjects?): Entry =
        assisting(parent)?.commit(staged, parent, name, replace, onRetained) ?: direct.commit(staged, parent, name, replace, onRetained)

    private fun assisting(ref: NodeRef): HelperFiles? {
        val files = helper() ?: return null
        val path = direct.locationOf(ref) ?: return null
        return files.takeIf { closed(path) }
    }

    /** Whether Android closes [ref] to Luna, helper or no helper. For the screens that explain an empty folder. */
    fun closes(ref: NodeRef): Boolean = direct.locationOf(ref)?.let(::closed) == true

    /** Whether [path] is, or lies under, Android/data or Android/obb on one of the volumes, outside Luna's own folder there. */
    private fun closed(path: String): Boolean {
        // Before Android 11 these folders are open to any app with storage access.
        if (Build.VERSION.SDK_INT < 30 || "/Android/" !in path) return false
        return direct.bases().any { (root, base) ->
            if (root.kind != RootKind.INTERNAL && root.kind != RootKind.SD_CARD && root.kind != RootKind.USB) return@any false
            CLOSED.any { folder ->
                val top = "${base.trimEnd('/')}/$folder"
                (path == top || path.startsWith("$top/")) && path != "$top/$ownPackage" && !path.startsWith("$top/$ownPackage/")
            }
        }
    }

    private companion object {
        val CLOSED = listOf("Android/data", "Android/obb")
    }
}
