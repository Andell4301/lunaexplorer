package com.lunaexplorer.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Steps run in order, each on the previous step's output. */
sealed interface RenameStep {

    data class Replace(
        val find: String,
        val with: String,
        val regex: Boolean = false,
        val ignoreCase: Boolean = true,
        val firstOnly: Boolean = false,
    ) : RenameStep

    data class ChangeCase(val mode: CaseMode) : RenameStep

    /** Inserts [text] at [position]; a negative position counts back from the end. */
    data class Insert(val text: String, val position: Int) : RenameStep

    /** Removes [count] characters from [position]; a negative position counts back from the end. */
    data class Remove(val position: Int, val count: Int) : RenameStep

    data class Trim(val leading: Boolean = true, val trailing: Boolean = true) : RenameStep

    /**
     * Replaces the name with a template. Tokens: `{name}`, `{ext}`, `{n}`, `{index}`, `{date}`,
     * `{time}`, `{size}`. `{n}` is the counter; the others come from the entry or its batch index.
     */
    data class Template(val pattern: String) : RenameStep

    data class Numbering(
        val start: Int = 1,
        val step: Int = 1,
        val padding: Int = 1,
        /** Where the number goes when no template consumed it. */
        val append: Boolean = true,
        val separator: String = "",
    ) : RenameStep
}

enum class CaseMode(val label: String) {
    LOWER("lower"), UPPER("UPPER"), TITLE("Title"), SENTENCE("Sentence"),
}

/**
 * Splits a file name into base and extension (without the dot). The part after the last dot counts
 * as an extension only if it is 1-8 characters with no spaces. Otherwise, and for directories and
 * dotfiles such as ".bashrc", the whole name is returned with an empty extension.
 */
fun splitExtension(name: String, directory: Boolean): Pair<String, String> {
    if (directory) return name to ""
    val dot = name.lastIndexOf('.')
    if (dot <= 0) return name to ""
    val extension = name.substring(dot + 1)
    if (extension.isEmpty() || extension.length > 8 || extension.contains(' ')) return name to ""
    return name.substring(0, dot) to extension
}

data class RenamePreview(
    val entry: Entry,
    val from: String,
    val to: String,
    val problem: String? = null,
) {
    val changed: Boolean get() = from != to
    val blocked: Boolean get() = problem != null
}

object BatchRename {

    /**
     * Applies [steps] to [entries]. [existing] holds every name in the destination folder, so
     * collisions with items outside the batch are detected. With [keepExtension], a file's extension
     * (see [splitExtension]) is set aside and re-attached after the steps; without it, the steps act
     * on the whole name. `{ext}` in a template is the file's extension either way.
     */
    fun preview(
        entries: List<Entry>,
        steps: List<RenameStep>,
        existing: Set<String> = emptySet(),
        keepExtension: Boolean = true,
    ): List<RenamePreview> {
        val numbering = steps.filterIsInstance<RenameStep.Numbering>().lastOrNull()
        val results = entries.mapIndexed { index, entry ->
            val counter = numbering?.let { it.start + index * it.step } ?: (index + 1)
            val produced = runCatching { apply(entry, steps, index, counter, keepExtension) }
            RenamePreview(
                entry = entry,
                from = entry.name,
                to = produced.getOrNull() ?: entry.name,
                problem = produced.exceptionOrNull()?.message,
            )
        }
        return flagCollisions(results, existing)
    }

    private fun apply(
        entry: Entry,
        steps: List<RenameStep>,
        index: Int,
        counter: Int,
        keepExtension: Boolean,
    ): String {
        val (base, extension) = splitExtension(entry.name, entry.directory)
        val protected = if (keepExtension) extension else ""

        var working = if (keepExtension) base else entry.name
        var numbered = false
        for (step in steps) {
            working = when (step) {
                is RenameStep.Replace -> replace(working, step)
                is RenameStep.ChangeCase -> changeCase(working, step.mode)
                is RenameStep.Insert -> insert(working, step.text, step.position)
                is RenameStep.Remove -> remove(working, step.position, step.count)
                is RenameStep.Trim -> working.let {
                    var value = it
                    if (step.leading) value = value.trimStart()
                    if (step.trailing) value = value.trimEnd()
                    value
                }
                is RenameStep.Template -> {
                    val expanded = expand(step.pattern, entry, working, extension, index, counter)
                    if (step.pattern.contains("{n}")) numbered = true
                    expanded
                }
                is RenameStep.Numbering -> {
                    if (numbered) working else {
                        numbered = true
                        val text = counter.toString().padStart(step.padding.coerceIn(1, 12), '0')
                        if (step.append) working + step.separator + text else text + step.separator + working
                    }
                }
            }
        }

        val whole = if (protected.isEmpty()) working else "$working.$protected"
        validateName(whole)
        return whole
    }

    private fun replace(value: String, step: RenameStep.Replace): String {
        if (step.find.isEmpty()) return value
        return if (step.regex) {
            val options = if (step.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            val pattern = runCatching { Regex(step.find, options) }.getOrElse {
                throw StorageException(StorageError.INVALID_NAME, "Not a valid pattern: ${it.message}")
            }
            if (step.firstOnly) pattern.replaceFirst(value, step.with) else pattern.replace(value, step.with)
        } else {
            if (step.firstOnly) {
                val at = value.indexOf(step.find, ignoreCase = step.ignoreCase)
                if (at < 0) value else value.replaceRange(at, at + step.find.length, step.with)
            } else {
                value.replace(step.find, step.with, ignoreCase = step.ignoreCase)
            }
        }
    }

    private fun changeCase(value: String, mode: CaseMode): String = when (mode) {
        CaseMode.LOWER -> value.lowercase()
        CaseMode.UPPER -> value.uppercase()
        CaseMode.TITLE -> value.split(' ').joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
        CaseMode.SENTENCE -> value.lowercase()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    /** Clamps to the name bounds. A negative position counts from the end; -1 is the last character. */
    private fun resolve(length: Int, position: Int): Int =
        (if (position < 0) length + position else position).coerceIn(0, length)

    private fun insert(value: String, text: String, position: Int): String {
        val at = resolve(value.length, position)
        return value.substring(0, at) + text + value.substring(at)
    }

    private fun remove(value: String, position: Int, count: Int): String {
        if (count <= 0) return value
        val at = resolve(value.length, position)
        val end = (at + count).coerceAtMost(value.length)
        return value.removeRange(at, end)
    }

    private fun expand(
        pattern: String,
        entry: Entry,
        current: String,
        extension: String,
        index: Int,
        counter: Int,
    ): String {
        val stamp = entry.modified?.let { Date(it) }
        return pattern
            .replace("{name}", current)
            .replace("{ext}", extension)
            .replace("{n}", counter.toString())
            .replace("{index}", index.toString())
            .replace("{size}", (entry.size ?: 0).toString())
            .replace("{date}", stamp?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(it) } ?: "")
            .replace("{time}", stamp?.let { SimpleDateFormat("HHmmss", Locale.US).format(it) } ?: "")
    }

    /**
     * Flags duplicate targets and collisions with names outside the batch. A target that is another
     * batch item's current name is fine: [plan] routes those through temporary names.
     */
    private fun flagCollisions(results: List<RenamePreview>, existing: Set<String>): List<RenamePreview> {
        val produced = results.groupBy { it.to.lowercase() }
        val originals = results.mapTo(HashSet()) { it.from.lowercase() }
        val taken = existing.mapTo(HashSet()) { it.lowercase() }
        return results.map { result ->
            if (result.problem != null) return@map result
            val key = result.to.lowercase()
            val problem = when {
                produced[key].orEmpty().size > 1 -> "Two items would both be named ${result.to}"
                key in taken && key !in originals -> "${result.to} already exists here"
                else -> null
            }
            result.copy(problem = problem)
        }
    }

    /**
     * Renames whose target is another batch item's current name (swaps included) are marked
     * [RenameAction.viaTemporary] and listed first, so they move aside before the direct renames run.
     */
    fun plan(previews: List<RenamePreview>): List<RenameAction> {
        val active = previews.filter { it.changed && !it.blocked }
        val currentNames = active.mapTo(HashSet()) { it.from.lowercase() }
        val (staged, direct) = active.partition { it.to.lowercase() in currentNames }
        return staged.map { RenameAction(it.entry, it.to, viaTemporary = true) } +
            direct.map { RenameAction(it.entry, it.to, viaTemporary = false) }
    }
}

data class RenameAction(val entry: Entry, val target: String, val viaTemporary: Boolean)
