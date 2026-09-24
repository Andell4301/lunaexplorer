package com.lunaexplorer.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@Serializable
data class ProcedureLocation(val ref: NodeRef, val children: List<String> = emptyList())

private val procedurePlaceholder = Regex("\\{\\{(date|time|datetime)}}|\\{(date|time|datetime)}")

fun containsProcedurePlaceholder(value: String): Boolean = procedurePlaceholder.containsMatchIn(value)

fun escapeProcedurePlaceholders(value: String): String = procedurePlaceholder.replace(value) { "{${it.value}}" }

class ProcedureNames(time: LocalDateTime = LocalDateTime.now()) {
    private val values = mapOf(
        "date" to time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)),
        "time" to time.format(DateTimeFormatter.ofPattern("HH-mm-ss", Locale.ROOT)),
        "datetime" to time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)),
    )

    fun expand(value: String): String = procedurePlaceholder.replace(value) { match ->
        match.groups[1]?.let { "{${it.value}}" } ?: values.getValue(match.groupValues[2])
    }
}

enum class ProcedureEntryKind { FILES, FOLDERS, ALL }
enum class ProcedureControl { STOP }
enum class ProcedureConditionTest { SUCCEEDED, FAILED, SKIPPED, HAS_OUTPUT, NO_OUTPUT }
enum class ProcedureConditionMatch { ALL, ANY }
enum class ProcedureFailurePolicy { STOP, CONTINUE }
enum class ProcedureStepStatus { SUCCEEDED, FAILED, SKIPPED }

@Serializable
data class ProcedureCondition(val stepId: String, val test: ProcedureConditionTest)

data class ProcedureStepResult(
    val stepId: String,
    val status: ProcedureStepStatus,
    val outputCount: Int = 0,
)

@Serializable
data class ProcedureSource(
    val location: ProcedureLocation,
    val pattern: String? = null,
    val recursive: Boolean = false,
    val kind: ProcedureEntryKind = ProcedureEntryKind.FILES,
)

@Serializable
data class ProcedureStep(
    val type: OperationType = OperationType.COPY,
    val sources: List<ProcedureSource> = emptyList(),
    val destination: ProcedureLocation? = null,
    val name: String? = null,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
    val archive: ArchiveSpec? = null,
    val keepVersions: Boolean = false,
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val control: ProcedureControl? = null,
    val createDestination: Boolean = false,
    val conditions: List<ProcedureCondition> = emptyList(),
    val conditionMatch: ProcedureConditionMatch = ProcedureConditionMatch.ALL,
    val onFailure: ProcedureFailurePolicy = ProcedureFailurePolicy.STOP,
    val ignoreMissingSources: Boolean = false,
) {
    fun validate() {
        require(id.isNotEmpty()) { "A step ID is required" }
        if (control == ProcedureControl.STOP) {
            require(sources.isEmpty() && destination == null && name == null && archive == null &&
                !createDestination && !keepVersions && !ignoreMissingSources) { "Stop does not accept file options" }
            return
        }
        require(type != OperationType.PROCEDURE) { "Procedures cannot contain procedures" }
        require(!createDestination || type in setOf(OperationType.COPY, OperationType.MOVE,
            OperationType.CREATE_FOLDER, OperationType.CREATE_ARCHIVE, OperationType.EXTRACT_ARCHIVE)) {
            "This action does not use a destination folder"
        }
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

fun validateProcedureSteps(steps: List<ProcedureStep>) {
    require(steps.isNotEmpty()) { "Add an action" }
    val previous = mutableSetOf<String>()
    for (step in steps) {
        step.validate()
        require(step.id !in previous) { "Step IDs must be unique" }
        require(step.conditions.all { it.stepId in previous }) { "Conditions must refer to an earlier step" }
        previous += step.id
    }
}

fun ProcedureStep.conditionsMet(results: List<ProcedureStepResult>): Boolean {
    if (conditions.isEmpty()) return true
    val byId = results.associateBy { it.stepId }
    fun matches(condition: ProcedureCondition): Boolean {
        val result = byId[condition.stepId] ?: return false
        return when (condition.test) {
            ProcedureConditionTest.SUCCEEDED -> result.status == ProcedureStepStatus.SUCCEEDED
            ProcedureConditionTest.FAILED -> result.status == ProcedureStepStatus.FAILED
            ProcedureConditionTest.SKIPPED -> result.status == ProcedureStepStatus.SKIPPED
            ProcedureConditionTest.HAS_OUTPUT -> result.status != ProcedureStepStatus.SKIPPED && result.outputCount > 0
            ProcedureConditionTest.NO_OUTPUT -> result.status != ProcedureStepStatus.SKIPPED && result.outputCount == 0
        }
    }
    return when (conditionMatch) {
        ProcedureConditionMatch.ALL -> conditions.all(::matches)
        ProcedureConditionMatch.ANY -> conditions.any(::matches)
    }
}

class ProcedurePlanner(private val registry: ProviderRegistry) {
    suspend fun plan(
        step: ProcedureStep,
        names: ProcedureNames = ProcedureNames(),
        createFolder: (suspend (OperationRequest) -> OperationResult)? = null,
    ): List<OperationRequest> {
        currentCoroutineContext().ensureActive()
        step.validate()
        if (step.control == ProcedureControl.STOP) return emptyList()
        val selected = linkedMapOf<NodeRef, Entry>()
        for (source in step.sources) {
            currentCoroutineContext().ensureActive()
            val expanded = source.copy(location = expand(source.location, names), pattern = source.pattern?.let(names::expand))
            for (entry in select(expanded, step.ignoreMissingSources)) selected[entry.ref] = entry
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
            resolveDestination(expand(location, names), step.createDestination, createFolder).ref
        } ?: if (step.type == OperationType.RENAME) {
            registry.provider(sources.single()).parentOf(sources.single())
        } else null
        return listOf(OperationRequest(
            type = step.type,
            sources = sources,
            destination = destination,
            name = step.name?.let(names::expand),
            conflictPolicy = step.conflictPolicy,
            archive = step.archive,
            keepVersions = step.keepVersions,
        ))
    }

    private fun expand(location: ProcedureLocation, names: ProcedureNames): ProcedureLocation =
        location.copy(children = location.children.map(names::expand))

    private suspend fun resolveDestination(
        location: ProcedureLocation,
        create: Boolean,
        createFolder: (suspend (OperationRequest) -> OperationResult)?,
    ): Entry {
        var entry = registry.provider(location.ref).stat(location.ref)
        var throughLink = entry.link
        requireFolder(entry)
        for (name in location.children) {
            currentCoroutineContext().ensureActive()
            val provider = registry.provider(entry.ref)
            var child = provider.child(entry.ref, name)
            if (child == null) {
                if (!create) throw StorageException(StorageError.NOT_FOUND, "Item not found: $name")
                if (throughLink) throw StorageException(StorageError.UNSUPPORTED, "Cannot create folders through a folder link")
                val prepare = requireNotNull(createFolder) { "Creating a destination requires the operation queue" }
                val result = prepare(OperationRequest(type = OperationType.CREATE_FOLDER,
                    destination = entry.ref, name = name, conflictPolicy = ConflictPolicy.MERGE))
                currentCoroutineContext().ensureActive()
                if (!result.successful) {
                    val failure = result.outcomes.firstOrNull { it.status != ItemStatus.SUCCESS }
                    throw StorageException(failure?.error ?: StorageError.IO,
                        failure?.message ?: "Could not create destination folder")
                }
                child = provider.child(entry.ref, name)
                    ?: throw StorageException(StorageError.NOT_FOUND, "Created folder not found: $name")
            }
            requireFolder(child)
            entry = child
            throughLink = throughLink || entry.link
        }
        return entry
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

    private suspend fun select(source: ProcedureSource, ignoreMissing: Boolean): List<Entry> {
        val root = try {
            resolve(source.location)
        } catch (failure: StorageException) {
            currentCoroutineContext().ensureActive()
            if (ignoreMissing && failure.reason == StorageError.NOT_FOUND) return emptyList()
            throw failure
        }
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
