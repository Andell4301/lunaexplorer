package com.lunaexplorer.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

@Serializable
data class ProcedureLocation(val ref: NodeRef, val children: List<String> = emptyList())

enum class ProcedureEntryKind { FILES, FOLDERS, ALL }

@Serializable
data class ProcedureSource(
    val location: ProcedureLocation,
    val pattern: String? = null,
    val recursive: Boolean = false,
    val kind: ProcedureEntryKind = ProcedureEntryKind.FILES,
)

@Serializable
data class ProcedureStep(
    val type: OperationType,
    val sources: List<ProcedureSource> = emptyList(),
    val destination: ProcedureLocation? = null,
    val name: String? = null,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
    val archive: ArchiveSpec? = null,
    val keepVersions: Boolean = false,
) {
    fun validate() {
        require(type != OperationType.PROCEDURE) { "Procedures cannot contain procedures" }
        if (type == OperationType.CREATE_FOLDER) {
            require(sources.isEmpty()) { "Create folder does not accept sources" }
        } else {
            require(sources.isNotEmpty()) { "Select at least one source" }
        }
        if (type in setOf(OperationType.COPY, OperationType.MOVE, OperationType.CREATE_FOLDER,
                OperationType.CREATE_ARCHIVE, OperationType.EXTRACT_ARCHIVE)) {
            requireNotNull(destination) { "A destination folder is required" }
        }
        if (type in setOf(OperationType.RENAME, OperationType.CREATE_FOLDER, OperationType.CREATE_ARCHIVE)) {
            requireNotNull(name) { "A name is required" }
        }
        name?.let(::validateName)
        sources.forEach { source ->
            require(source.pattern == null || source.pattern.isNotEmpty()) { "A pattern is required" }
        }
        (sources.map { it.location } + listOfNotNull(destination)).forEach { location ->
            require(location.children.all { it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\u0000' !in it }) {
                "Invalid child name"
            }
        }
        if (type == OperationType.RENAME || type == OperationType.EXTRACT_ARCHIVE) {
            require(sources.size == 1) { "Select exactly one source" }
        }
        require(archive?.needsPassword != true && (archive == null || archive.encryption == ZipEncryption.NONE)) {
            "Stored procedures cannot use archive passwords"
        }
        if (type == OperationType.CREATE_ARCHIVE) {
            requireNotNull(archive) { "Archive options are required" }
            require(archive.format.creatable) { "This archive format cannot be created" }
        }
    }
}

class ProcedurePlanner(private val registry: ProviderRegistry) {
    suspend fun plan(step: ProcedureStep): List<OperationRequest> {
        currentCoroutineContext().ensureActive()
        step.validate()
        val selected = linkedMapOf<NodeRef, Entry>()
        for (source in step.sources) {
            currentCoroutineContext().ensureActive()
            for (entry in select(source)) selected[entry.ref] = entry
        }
        if (step.type != OperationType.CREATE_FOLDER && selected.isEmpty()) return emptyList()
        if (step.type == OperationType.RENAME || step.type == OperationType.EXTRACT_ARCHIVE) {
            require(selected.size == 1) { "Select exactly one item" }
        }
        val sources = if (step.type in setOf(OperationType.COPY, OperationType.MOVE,
                OperationType.DELETE, OperationType.CREATE_ARCHIVE)) {
            withoutDescendants(selected.values.toList())
        } else selected.keys.toList()
        val destination = step.destination?.let { location ->
            resolve(location).also { requireFolder(it) }.ref
        } ?: if (step.type == OperationType.RENAME) {
            registry.provider(sources.single()).parentOf(sources.single())
        } else null
        return listOf(OperationRequest(
            type = step.type,
            sources = sources,
            destination = destination,
            name = step.name,
            conflictPolicy = step.conflictPolicy,
            archive = step.archive,
            keepVersions = step.keepVersions,
        ))
    }

    private suspend fun resolve(location: ProcedureLocation): Entry {
        var entry = registry.provider(location.ref).stat(location.ref)
        for (name in location.children) {
            currentCoroutineContext().ensureActive()
            requireFolder(entry)
            entry = registry.provider(entry.ref).child(entry.ref, name)
                ?: throw StorageException(StorageError.NOT_FOUND, "Item not found: $name")
        }
        return entry
    }

    private suspend fun select(source: ProcedureSource): List<Entry> {
        val root = resolve(source.location)
        val pattern = source.pattern ?: return listOf(root)
        requireFolder(root)
        if (root.link) throw StorageException(StorageError.UNSUPPORTED, "Cannot match files through a folder link")
        val pending = ArrayDeque<NodeRef>()
        val visited = mutableSetOf<NodeRef>()
        val selected = mutableListOf<Entry>()
        pending.addLast(root.ref)
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val folder = pending.removeFirst()
            if (!visited.add(folder)) throw StorageException(StorageError.IO, "Folder cycle detected")
            registry.provider(folder).list(folder, complete = true).collect { batch ->
                for (entry in batch) {
                    currentCoroutineContext().ensureActive()
                    val kindMatches = when (source.kind) {
                        ProcedureEntryKind.FILES -> !entry.directory
                        ProcedureEntryKind.FOLDERS -> entry.directory
                        ProcedureEntryKind.ALL -> true
                    }
                    if (kindMatches && matches(pattern, entry.name)) {
                        selected += entry
                    } else if (source.recursive && entry.directory && !entry.link) {
                        pending.addLast(entry.ref)
                    }
                }
            }
        }
        return selected
    }

    private suspend fun withoutDescendants(entries: List<Entry>): List<NodeRef> {
        val folders = entries.filter { it.directory && !it.link }
        return entries.filter { entry ->
            currentCoroutineContext().ensureActive()
            folders.none { folder ->
                entry.ref != folder.ref && entry.ref.provider == folder.ref.provider &&
                    registry.provider(entry.ref).isDescendant(entry.ref, folder.ref)
            }
        }.map { it.ref }
    }

    private fun requireFolder(entry: Entry) {
        if (!entry.directory) throw StorageException(StorageError.UNSUPPORTED, "Not a folder")
    }

    private fun matches(pattern: String, name: String): Boolean {
        var patternAt = 0
        var nameAt = 0
        var starAt = -1
        var starEnd = 0
        while (nameAt < name.length) {
            when {
                patternAt < pattern.length && pattern[patternAt] == '*' -> {
                    starAt = patternAt++
                    starEnd = nameAt
                }
                patternAt < pattern.length && (pattern[patternAt] == '?' || pattern[patternAt] == name[nameAt]) -> {
                    patternAt++
                    nameAt++
                }
                starAt >= 0 -> {
                    patternAt = starAt + 1
                    nameAt = ++starEnd
                }
                else -> return false
            }
        }
        while (patternAt < pattern.length && pattern[patternAt] == '*') patternAt++
        return patternAt == pattern.length
    }
}
