package com.lunaexplorer.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

// Paths are normalized member paths without trailing slashes; the rewriter resolves stored names.
data class ArchiveEdits(
    val removed: Set<String> = emptySet(),
    val renamed: Map<String, String> = emptyMap(),
    val added: List<ArchiveAddition> = emptyList(),
) {
    val empty: Boolean get() = removed.isEmpty() && renamed.isEmpty() && added.isEmpty()
    val touched: Set<String> get() = removed + renamed.keys
}

/** A member to add at [path]. A directory has no content; it keeps an empty folder in the zip. */
data class ArchiveAddition(
    val path: String,
    val directory: Boolean = false,
    /** Local file holding the content; null for a directory. */
    val file: File? = null,
)

// The CRC detects content moved to the wrong name during a rewrite.
private class MemberFacts(
    val encrypted: Boolean,
    /** Null for an added member, which has nothing to compare against. */
    val crc: Long?,
)

// A folder may have no stored entry; its normalized path still selects every descendant.
private fun Set<String>.covering(path: String): List<String> {
    // Compare normalized names: stored names may use backslashes or a "./" prefix.
    val itself = filter { normalizedMemberPath(it) == path }
    // A plain file "docs" can coexist with "docs/a.txt"; editing the file must not touch the folder.
    if (itself.isNotEmpty() && itself.none { it.endsWith("/") }) return itself
    val prefix = "$path/"
    return itself + filter { normalizedMemberPath(it).startsWith(prefix) }
}

private class Resolution(
    val renamed: Map<String, String>,
    val removed: Set<String>,
    /** [renamed] split into ordered zip4j calls; see [renamePasses]. */
    val passes: List<Map<String, String>>,
) {
    val empty: Boolean get() = renamed.isEmpty() && removed.isEmpty()
}

// Expand folder renames per stored member, including folders without their own ZIP entry.
private fun resolve(members: Set<String>, edits: ArchiveEdits): Resolution {
    val removed = edits.removed.flatMapTo(LinkedHashSet()) { members.covering(it) }
    val renamed = LinkedHashMap<String, String>()
    // Shallowest first, so a rename inside a renamed folder overrides the name inherited from it.
    edits.renamed.entries.sortedBy { it.key.length }.forEach { (from, to) ->
        members.covering(from).forEach { stored ->
            // The stored name may not start with `from`, so take the remainder from the normalized
            // name. A folder entry keeps its trailing slash.
            val rest = normalizedMemberPath(stored).removePrefix(from)
            renamed[stored] = to + rest + if (stored.endsWith("/")) "/" else ""
        }
    }
    // Removals are applied first, under the current stored name, so a removed member is not renamed.
    removed.forEach { renamed.remove(it) }
    return Resolution(renamed, removed, renamePasses(renamed, members))
}

/**
 * Splits renames into ordered `ZipFile.renameFiles` calls. zip4j matches renames by prefix, and two
 * kinds of pair go wrong inside a single call:
 *
 * - Keys where one prefixes the other and they disagree about where a shared entry lands (a folder
 *   rename plus a different rename of something inside it). zip4j takes the first matching key from
 *   a `HashMap`, so the result depends on iteration order. The more specific rename goes in an
 *   earlier pass.
 * - A rename whose new name begins with another rename's old name. zip4j matches the name it has
 *   just written against the remaining keys and renames it again: a→b with b→c puts a's contents
 *   under c and leaves b's in place. The rename that frees the name goes in an earlier pass. A new
 *   name that begins with its own old name ("docs/"→"docs2/") is fine.
 *
 * A cycle, such as two members trading names, has no valid first step, so one member is first moved
 * to a temporary name and takes its real name in a later pass.
 */
private fun renamePasses(renamed: Map<String, String>, members: Set<String>): List<Map<String, String>> {
    if (renamed.size <= 1) return if (renamed.isEmpty()) emptyList() else listOf(renamed)
    fun agree(a: Map.Entry<String, String>, b: Map.Entry<String, String>): Boolean = when {
        b.key.startsWith(a.key) -> b.value == a.value + b.key.removePrefix(a.key)
        a.key.startsWith(b.key) -> a.value == b.value + a.key.removePrefix(b.key)
        else -> true
    }
    val passes = mutableListOf<Map<String, String>>()
    val waiting = LinkedHashMap(renamed)
    var parked = 0
    while (waiting.isNotEmpty()) {
        // Ready: no other pending rename's old name prefixes this one's new name.
        val ready = waiting.filter { (from, to) ->
            waiting.keys.none { other -> other != from && to.startsWith(other) }
        }
        val pass: Map<String, String>
        if (ready.isEmpty()) {
            // A cycle. Move one member to a temporary name that collides with no member or rename.
            // It stays queued and is re-keyed to the temporary name below.
            val first = waiting.keys.first()
            val suffix = if (first.endsWith("/")) "/" else ""
            val aside = generateSequence(parked) { it + 1 }
                .map { ".luna-parked-$it-${UUID.randomUUID()}$suffix" }
                .first { it !in members && it !in waiting && it !in renamed.values }
            parked++
            pass = mapOf(first to aside)
        } else {
            val chosen = LinkedHashMap<String, String>()
            // Deepest first: a rename that disagrees with its folder's must run while the entry is
            // still stored under the name it is keyed by.
            ready.entries.sortedByDescending { it.key.length }.forEach { entry ->
                if (chosen.entries.all { agree(it, entry) }) chosen[entry.key] = entry.value
            }
            pass = chosen
            chosen.keys.forEach { waiting.remove(it) }
        }
        passes += pass
        // A pass also moves everything under the renamed prefix. Re-key pending renames to the new
        // location; zip4j silently ignores a key that matches nothing.
        val carried = waiting.entries.mapNotNull { waited ->
            val moved = pass.entries.firstOrNull { waited.key.startsWith(it.key) } ?: return@mapNotNull null
            Triple(waited.key, moved.value + waited.key.removePrefix(moved.key), waited.value)
        }
        carried.forEach { (was, now, target) -> waiting.remove(was); waiting[now] = target }
    }
    return passes
}

// zip4j deletes before publishing, so edit a scratch copy, verify content and encryption, then publish through commit.
class ArchiveRewriter(
    private val registry: ProviderRegistry,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Where scratch copies are made; null uses the JVM's temporary directory. */
    private val scratchDirectory: File? = null,
) {
    /** The final step carries the republished archive in [ArchiveProgress.produced]. */
    fun rewrite(archive: NodeRef, edits: ArchiveEdits): Flow<ArchiveProgress> = flow {
        if (edits.empty) throw StorageException(StorageError.UNSUPPORTED, "Nothing to change in this archive")
        val provider = registry.provider(archive)
        val original = provider.stat(archive)
        if (archiveFormatOf(original.name) != ArchiveFormat.ZIP) {
            throw StorageException(StorageError.UNSUPPORTED,
                "${original.name} can be opened but not edited. Only zip archives can be changed in place.")
        }
        val parent = provider.parentOf(archive)
            ?: throw StorageException(StorageError.UNSUPPORTED, "Luna cannot reach the folder holding ${original.name}")
        val folder = provider.stat(parent)
        if (Capability.ATOMIC_REPLACE !in folder.capabilities && Capability.REPLACE !in folder.capabilities) {
            throw StorageException(StorageError.UNSUPPORTED,
                "${original.name} cannot be replaced where it is stored")
        }
        edits.renamed.forEach { (from, to) -> validateMemberRename(from, to) }

        val scratch = File(scratchDirectory ?: File(System.getProperty("java.io.tmpdir")),
            "luna-rewrite-${UUID.randomUUID()}.zip")
        var staged: Entry? = null
        var published: Entry? = null
        try {
            emit(ArchiveProgress(original.name, bytesTotal = original.size))
            val copied = withContext(dispatcher) {
                scratch.parentFile?.mkdirs()
                provider.openRead(archive).use { input ->
                    scratch.outputStream().use { output -> input.copyTo(output, COPY_BUFFER) }
                }
                scratch.length()
            }
            currentCoroutineContext().ensureActive()
            emit(ArchiveProgress(original.name, bytesDone = copied, bytesTotal = original.size))

            val before = readMembers(scratch)
            val plan = resolve(before.keys, edits)
            guardEdits(before, edits, plan)

            withContext(dispatcher) { applyEdits(scratch, edits, plan, before) }
            currentCoroutineContext().ensureActive()

            verifyRewrite(before, readMembers(scratch), edits, plan, original.name)

            val target = registry.provider(parent)
            staged = target.create(parent, stagingName(), directory = false, mimeType = "application/zip")
            withContext(dispatcher) {
                // Flush instead of closing: closing the buffer would close the provider's stream
                // before use() does.
                target.openWrite(staged.ref).use { raw ->
                    val sink = BufferedOutputStream(raw, COPY_BUFFER)
                    scratch.inputStream().use { it.copyTo(sink, COPY_BUFFER) }
                    sink.flush()
                }
            }
            published = target.commit(staged.ref, parent, original.name, replace = original)
            emit(ArchiveProgress(original.name, bytesDone = scratch.length(), bytesTotal = scratch.length(),
                complete = true, produced = published.ref))
        } finally {
            withContext(NonCancellable) {
                runCatching { scratch.delete() }
                val leftover = staged
                if (published == null && leftover != null) {
                    runCatching { registry.provider(parent).delete(leftover.ref) }
                }
            }
        }
    }

    private suspend fun readMembers(file: File): Map<String, MemberFacts> = withContext(dispatcher) {
        ZipFile(file).use { zip ->
            zip.fileHeaders.associate { it.fileName to MemberFacts(it.isEncrypted, it.crc) }
        }
    }

    private suspend fun applyEdits(
        file: File,
        edits: ArchiveEdits,
        plan: Resolution,
        before: Map<String, MemberFacts>,
    ) {
        val password = this.password
        ZipFile(file).use { zip ->
            if (zip.isSplitArchive) {
                throw StorageException(StorageError.UNSUPPORTED, "Split archives cannot be changed")
            }
            // Removals first: a folder rename's key prefixes everything inside the folder, so a
            // member due for removal would otherwise be renamed away from the name the removal uses.
            if (plan.removed.isNotEmpty()) zip.removeFiles(plan.removed.toList())
            plan.passes.forEach { pass -> zip.renameFiles(pass) }
        }
        if (edits.added.isEmpty()) return
        // Additions to an encrypted archive must be encrypted too; refuse rather than add in the clear.
        val encrypting = before.values.any { it.encrypted }
        if (encrypting && password == null) {
            throw StorageException(StorageError.AUTH,
                "Unlock this archive before putting anything into it, so what you add is protected too")
        }
        val zip = if (password == null) ZipFile(file) else ZipFile(file, password)
        zip.use { open ->
            for (addition in edits.added) {
                currentCoroutineContext().ensureActive()
                val parameters = ZipParameters().apply {
                    // A zip marks a folder by the trailing slash on its name.
                    fileNameInZip = if (addition.directory) "${addition.path.trimEnd('/')}/" else addition.path
                    compressionMethod = CompressionMethod.DEFLATE
                    if (encrypting && !addition.directory) {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                }
                val content = if (addition.directory) ByteArrayInputStream(ByteArray(0))
                    else requireNotNull(addition.file) { "A file addition needs somewhere to read from" }.inputStream()
                content.use { open.addStream(it, parameters) }
            }
        }
    }

    @Volatile private var password: CharArray? = null

    fun unlockedWith(password: String?): ArchiveRewriter {
        this.password = password?.takeIf { it.isNotEmpty() }?.toCharArray()
        return this
    }

    // zip4j renames by prefix: every member a pass can match must be one the plan moves.
    private fun guardEdits(members: Map<String, MemberFacts>, edits: ArchiveEdits, plan: Resolution) {
        edits.touched.forEach { path ->
            if (members.keys.covering(path).isEmpty()) {
                throw StorageException(StorageError.NOT_FOUND, "$path is no longer in this archive")
            }
        }
        // An edit that resolved to nothing would rewrite the archive unchanged and report success.
        if ((edits.renamed.isNotEmpty() || edits.removed.isNotEmpty()) && plan.empty) {
            throw StorageException(StorageError.IO, "Nothing in this archive answers to what changed")
        }
        // Simulated pass by pass, since a later pass matches against the names an earlier one
        // produced. `moving` carries the entries the plan renames through the same passes.
        fun Set<String>.through(pass: Map<String, String>): Set<String> = mapTo(LinkedHashSet()) { stored ->
            pass.entries.firstOrNull { stored.startsWith(it.key) }
                ?.let { it.value + stored.removePrefix(it.key) } ?: stored
        }
        val surviving = members.keys - plan.removed
        var names = surviving
        var moving = plan.renamed.keys.toSet()
        plan.passes.forEach { pass ->
            pass.keys.forEach { from ->
                val stray = names.firstOrNull { it.startsWith(from) && it !in moving }
                if (stray != null) {
                    throw StorageException(StorageError.UNSUPPORTED,
                        "Renaming ${from.trimEnd('/')} would also rename $stray. " +
                            "Rename it from outside the archive instead.")
                }
            }
            names = names.through(pass)
            moving = moving.through(pass)
        }
        // The passes together must land every member where the plan says.
        val intended = surviving.mapTo(LinkedHashSet()) { plan.renamed[it] ?: it }
        if (names != intended) {
            throw StorageException(StorageError.IO,
                "Luna could not find a safe order for these renames")
        }
    }

    // Verify stored names, content CRCs and encryption; normalized names can conceal a rewrite that changed nothing.
    private fun verifyRewrite(
        before: Map<String, MemberFacts>,
        after: Map<String, MemberFacts>,
        edits: ArchiveEdits,
        plan: Resolution,
        name: String,
    ) {
        fun refuse(why: String): Nothing =
            throw StorageException(StorageError.IO, "$name was left unchanged: $why")

        val encrypting = before.values.any { it.encrypted }
        val expected = buildMap {
            before.forEach { (stored, facts) ->
                if (stored in plan.removed) return@forEach
                // A renamed member keeps its content, so its CRC is expected under the new name.
                put(plan.renamed[stored] ?: stored, facts)
            }
            // Additions must be present, and encrypted if the archive is. New content has no CRC
            // to check.
            edits.added.forEach {
                if (it.directory) put("${it.path.trimEnd('/')}/", MemberFacts(false, crc = null))
                else put(it.path, MemberFacts(encrypting, crc = null))
            }
        }
        val missing = expected.keys - after.keys
        if (missing.isNotEmpty()) refuse("${missing.first()} went missing from the rewritten copy")
        val extra = after.keys - expected.keys
        if (extra.isNotEmpty()) refuse("${extra.first()} appeared in the rewritten copy")
        expected.forEach { (path, want) ->
            val got = after.getValue(path)
            if (got.encrypted != want.encrypted) {
                refuse(if (edits.added.any { it.path == path }) "$path would not be protected like the rest"
                else "$path would no longer be encrypted as it was")
            }
            // A changed CRC means content ended up under the wrong name.
            if (want.crc != null && got.crc != want.crc) {
                refuse("$path would hold the wrong contents")
            }
        }
    }

    private fun validateMemberRename(from: String, to: String) {
        validateName(to.trimEnd('/').substringAfterLast('/'))
        // Member refs are "<handle>|<path>", split at the last '|'.
        if (to.contains('|')) {
            throw StorageException(StorageError.UNSUPPORTED, "A name inside an archive cannot contain |")
        }
        if (from == to) throw StorageException(StorageError.UNSUPPORTED, "That is already its name")
    }

    private fun stagingName(): String = ".luna-archive-${UUID.randomUUID()}.partial"

    private companion object {
        const val COPY_BUFFER = 128 * 1024
    }
}
