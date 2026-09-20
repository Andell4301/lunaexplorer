package com.lunaexplorer.app.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.lunaexplorer.app.AppGraph
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.storage.ContentAddressable
import com.lunaexplorer.app.storage.FileFacts
import com.lunaexplorer.app.storage.ViewerFiles
import com.lunaexplorer.app.playback.PlaybackSource
import com.lunaexplorer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.SeekableByteChannel
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class BrowserFiles internal constructor(
    private val application: Application,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val _state: MutableStateFlow<BrowserState>,
    private val resolver: PathResolver,
    private val onChanged: (preserveSelection: Boolean) -> Unit,
    private val showMessage: (String) -> Unit,
) {
    fun measure(refs: List<NodeRef>): Flow<FolderTotals> = graph.sizer.measure(refs)

    // Clipboard reads can arrive long after the handoff, when Luna can no longer start its streaming service.
    fun copyToClipboard(entries: List<Entry>) {
        if (entries.isEmpty()) return
        scope.launch {
            val uris = withContext(Dispatchers.IO) {
                entries.map { entry -> runCatching { clipboardUri(entry.ref) }.getOrNull() }
            }
            if (uris.any { it == null }) {
                showMessage("Only files Android can open by itself go on the clipboard. Share these instead.")
                return@launch
            }
            val types = entries.map { it.mimeType }.distinct().toTypedArray()
            val clip = ClipData(ClipDescription(entries.first().name, types), ClipData.Item(uris.first()))
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            try {
                application.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                showMessage(if (entries.size == 1) "Copied ${entries.first().name} to the clipboard" else "Copied ${entries.size} files to the clipboard")
            } catch (error: Exception) {
                showMessage("The clipboard would not take them: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    suspend fun commonFolders(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        buildList {
            for (root in _state.value.roots) {
                val path = resolver.pathOf(root.ref) ?: continue
                add(root.title to path)
                if (root.kind != RootKind.INTERNAL) continue
                USUAL_FOLDERS.forEach { name -> if (File(path, name).isDirectory) add(name to "${path.trimEnd('/')}/$name") }
            }
        }
    }

    suspend fun pathRoots(): List<StorageRoot> = withContext(Dispatchers.IO) {
        _state.value.roots.filter { resolver.shownPathOf(it.ref) != null }
    }

    suspend fun foldersIn(ref: NodeRef, hidden: Boolean): List<Entry> = withContext(Dispatchers.IO) {
        val found = ArrayList<Entry>()
        graph.providers.provider(ref).list(ref).collect { batch -> batch.filterTo(found) { it.directory && (hidden || !it.hidden) } }
        found.sortedWith(NaturalOrder)
    }

    suspend fun shownPathOf(ref: NodeRef): String? = withContext(Dispatchers.IO) { resolver.shownPathOf(ref) }

    private fun clipboardUri(ref: NodeRef): Uri? {
        val provider = graph.providers.provider(ref)
        (provider as? PathAddressable)?.pathOf(ref)?.let { path ->
            return FileProvider.getUriForFile(application, "${application.packageName}.files", File(path))
        }
        return (provider as? ContentAddressable)?.contentUri(ref)
    }

    /** The viewer owns this temporary file and deletes it when it closes. */
    suspend fun stageForViewer(entry: Entry, limit: Long, tooLarge: String = ViewerFiles.TOO_LARGE): Result<File> =
        ViewerFiles.stage(application.cacheDir, graph.providers.provider(entry.ref), entry, limit, tooLarge)

    /** Null where this storage only streams [entry]. The caller closes the channel. */
    suspend fun openChannel(entry: Entry): SeekableByteChannel? = graph.providers.provider(entry.ref).openChannel(entry.ref)

    fun overNetwork(entry: Entry): Boolean = Feature.NETWORK in graph.providers.provider(entry.ref).features

    suspend fun readBytes(entry: Entry, limit: Long): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val size = entry.size
            if (size != null && size > limit) {
                throw StorageException(StorageError.UNSUPPORTED,
                    "This file is ${formatSize(size)}, too large to open here. Open it with another app instead.")
            }
            val bytes = graph.providers.provider(entry.ref).openRead(entry.ref).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(128 * 1024)
                var total = 0L
                while (true) {
                    ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > limit) {
                        throw StorageException(StorageError.UNSUPPORTED, "This file is too large to open here")
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            Result.success(bytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ensureActive()
            Result.failure(error)
        }
    }

    /** Stages text in a sibling file before replacing the original to avoid truncation on write failure. */
    fun writeText(entry: Entry, content: String, onResult: (Result<Entry>) -> Unit) {
        scope.launch {
            val result = try {
                val saved = withContext(Dispatchers.IO) {
                    val provider = graph.providers.provider(entry.ref)
                    val parent = provider.parentOf(entry.ref)
                        ?: throw StorageException(StorageError.UNSUPPORTED, "This storage does not expose the containing folder")
                    // Same staging-name pattern as file operations. SAF providers may append a MIME
                    // extension, which only a staging name can tolerate.
                    val staging = ".luna-${UUID.randomUUID().toString().take(12)}-${UUID.randomUUID()}.partial"
                    val staged = provider.create(parent, staging, directory = false, mimeType = entry.mimeType)
                    try {
                        provider.openWrite(staged.ref).use { it.write(content.toByteArray(Charsets.UTF_8)) }
                        provider.commit(staged.ref, parent, entry.name, entry)
                    } catch (error: Throwable) {
                        withContext(NonCancellable) { runCatching { provider.delete(staged.ref) } }
                        throw error
                    }
                }
                Result.success(saved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            // The commit can change the file's ref and version, so the caller must keep the returned entry.
            // onResult runs outside the try: a throwing callback is not a write failure.
            onResult(result)
            if (result.isSuccess) onChanged(true)
        }
    }

    fun createTextFile(name: String, content: String = "") {
        val destination = _state.value.location?.ref ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val provider = graph.providers.provider(destination)
                    val created = provider.create(destination, name, directory = false,
                        mimeType = "text/plain")
                    if (content.isNotEmpty()) {
                        provider.openWrite(created.ref).use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    }
                }
                onChanged(false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ensureActive()
                showMessage(error.message ?: "The file could not be created")
            }
        }
    }

    internal fun playbackSource(entry: Entry): PlaybackSource = PlaybackSource.create(application, graph.providers, entry)

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB")
        var value = bytes.toDouble() / 1024
        var index = 0
        while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
        return "%.1f %s".format(value, units[index])
    }

    suspend fun facts(entry: Entry): FileFacts = graph.inspector.inspect(entry)

    fun digestsFor(entry: Entry): Flow<DigestProgress> = graph.digests.compute(entry.ref, totalBytes = entry.size)

    fun setModified(entry: Entry, epochMillis: Long, onResult: (String) -> Unit) {
        scope.launch {
            try {
                val stored = graph.inspector.setModified(entry.ref, epochMillis)
                onResult(if (stored == epochMillis) "Modification time updated"
                    else "Stored as ${DateFormat.getDateTimeInstance().format(Date(stored))}; " +
                        "this filesystem rounds timestamps")
                onChanged(true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ensureActive()
                onResult(error.message ?: "The modification time could not be changed")
            }
        }
    }

    suspend fun currentCapabilities(entry: Entry): Set<Capability> = withContext(Dispatchers.IO) {
        try {
            graph.providers.provider(entry.ref).stat(entry.ref).capabilities
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ensureActive()
            entry.capabilities
        }
    }

    fun absolutePathOf(entry: Entry): String? = resolver.pathOf(entry.ref)

    fun canEditTags(entry: Entry) = graph.inspector.canEditTags(entry)
    suspend fun readTags(entry: Entry): List<EditableTag> = graph.inspector.readTags(entry)

    fun writeTags(entry: Entry, values: Map<String, String>, onDone: () -> Unit) {
        scope.launch {
            val result = graph.inspector.writeTags(entry, values)
            when {
                result == null -> showMessage("Tags can only be written to a real file")
                result.isSuccess -> { showMessage("Tags saved"); onChanged(false) }
                else -> showMessage("Could not save tags: ${result.exceptionOrNull()?.message}")
            }
            onDone()
        }
    }

    private companion object {
        val USUAL_FOLDERS = listOf("Download", "Documents", "DCIM", "Pictures", "Music", "Movies")
    }
}
