package com.lunaexplorer.core

import java.text.BreakIterator
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** A run of adjacent entries sharing a section label. [start] is an index into the listing. */
data class SectionRun(val label: String, val start: Int, val count: Int) {
    val end: Int get() = start + count
}

// Buckets must stay contiguous under the sort key; Han is grouped across Unicode blocks deliberately.
object Sections {
    const val FOLDERS = "Folders"
    const val DIGITS = "#"
    const val HAN = "漢字"
    const val OTHER = "Other"
    const val NO_EXTENSION = "No extension"
    const val UNKNOWN_SIZE = "Unknown size"
    const val EMPTY = "Empty"
    const val UNKNOWN_DATE = "Unknown date"
    const val LATER = "Later"
    const val TODAY = "Today"
    const val YESTERDAY = "Yesterday"
    const val THIS_WEEK = "This week"
    const val THIS_MONTH = "This month"

    // Use one code point with simple case mapping; grapheme clusters and expansions such as ß to SS split sorted runs.
    fun firstCharacterKey(text: String): String {
        if (text.isEmpty()) return OTHER
        val cp = text.codePointAt(0)
        return when {
            cp in '0'.code..'9'.code -> DIGITS
            Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN -> HAN
            else -> String(Character.toChars(Character.toUpperCase(cp)))
        }
    }

    // Labels keep the whole grapheme cluster even though grouping uses only its first code point.
    fun firstCharacterLabel(text: String): String {
        val key = firstCharacterKey(text)
        if (key == DIGITS || key == HAN || key == OTHER) return key
        val breaks = BreakIterator.getCharacterInstance(Locale.ROOT)
        breaks.setText(text)
        val end = breaks.next().let { if (it == BreakIterator.DONE) text.length else it }
        val cluster = text.substring(0, end)
        return if (cluster.isBlank() || cluster.all { Character.isISOControl(it) }) OTHER
        else cluster.uppercase(Locale.ROOT)
    }

    /** Must match the extension sort key: the name after its last dot. */
    fun extension(name: String): String {
        val extension = name.substringAfterLast('.', "")
        return if (extension.isEmpty()) NO_EXTENSION else "." + extension.lowercase(Locale.ROOT)
    }

    /** Top-level media type, which is contiguous under the MIME sort key. */
    fun mediaType(mimeType: String): String {
        val top = mimeType.substringBefore('/').trim()
        return if (top.isEmpty()) OTHER else top.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    private val SIZE_STEPS = longArrayOf(
        1L shl 10, 100L shl 10, 1L shl 20, 10L shl 20, 100L shl 20, 1L shl 30, 10L shl 30,
    )
    private val SIZE_LABELS = arrayOf(
        "Under 1 KiB", "1–100 KiB", "100 KiB–1 MiB", "1–10 MiB",
        "10–100 MiB", "100 MiB–1 GiB", "1–10 GiB", "Over 10 GiB",
    )

    fun size(bytes: Long?): String = when {
        bytes == null -> UNKNOWN_SIZE
        bytes <= 0L -> EMPTY
        else -> SIZE_LABELS[SIZE_STEPS.count { bytes >= it }]
    }

    /** Capture [now] once per listing so every entry is bucketed against the same day. */
    fun date(modified: Long?, now: Long, zone: ZoneId, locale: Locale): String {
        if (modified == null || modified <= 0L) return UNKNOWN_DATE
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = Instant.ofEpochMilli(modified).atZone(zone).toLocalDate()
        return when {
            day.isAfter(today) -> LATER
            day == today -> TODAY
            day == today.minusDays(1) -> YESTERDAY
            day.isAfter(today.minusDays(7)) -> THIS_WEEK
            day.year == today.year && day.month == today.month -> THIS_MONTH
            day.year == today.year -> day.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
            else -> day.year.toString()
        }
    }

    /** Maximal runs of equal [key]. [label] is evaluated once per run, on its first member. */
    fun <T> runs(items: List<T>, key: (T) -> String, label: (T) -> String = key): List<SectionRun> {
        if (items.isEmpty()) return emptyList()
        val out = ArrayList<SectionRun>(16)
        var start = 0
        var current = key(items[0])
        for (index in 1 until items.size) {
            val next = key(items[index])
            if (next != current) {
                out += SectionRun(label(items[start]), start, index - start)
                current = next
                start = index
            }
        }
        out += SectionRun(label(items[start]), start, items.size - start)
        return out
    }

    // Header k sits at sections[k].start + k because every previous section contributes one header.
    fun entryIndexAt(sections: List<SectionRun>, itemIndex: Int): Int? {
        if (itemIndex < 0) return null
        if (sections.isEmpty()) return itemIndex
        var low = 0
        var high = sections.lastIndex
        while (low <= high) {
            val middle = (low + high) / 2
            val header = sections[middle].start + middle
            when {
                itemIndex < header -> high = middle - 1
                itemIndex > header + sections[middle].count -> low = middle + 1
                itemIndex == header -> return null
                // middle + 1 headers precede this item.
                else -> return itemIndex - middle - 1
            }
        }
        return null
    }
}
