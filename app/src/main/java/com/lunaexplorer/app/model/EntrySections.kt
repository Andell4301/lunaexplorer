package com.lunaexplorer.app.model

import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.SectionRun
import com.lunaexplorer.core.Sections
import java.time.ZoneId
import java.util.Locale

/**
 * Folders sort before files, so under name and date sorts a label can open one run among the folders
 * and another among the files. Under the other sorts all folders share [Sections.FOLDERS].
 */
fun sectionKey(entry: Entry, sort: SortOrder, now: Long, zone: ZoneId, locale: Locale): String = when (sort) {
    SortOrder.NAME, SortOrder.NATURAL -> Sections.firstCharacterKey(entry.name)
    SortOrder.MODIFIED -> Sections.date(entry.modified, now, zone, locale)
    SortOrder.EXTENSION -> if (entry.directory) Sections.FOLDERS else Sections.extension(entry.name)
    SortOrder.TYPE -> if (entry.directory) Sections.FOLDERS else Sections.mediaType(entry.mimeType)
    SortOrder.SIZE -> if (entry.directory) Sections.FOLDERS else Sections.size(entry.size)
}

fun sectionLabel(entry: Entry, sort: SortOrder, now: Long, zone: ZoneId, locale: Locale): String =
    if (sort == SortOrder.NAME || sort == SortOrder.NATURAL) {
        Sections.firstCharacterLabel(entry.name)
    } else {
        sectionKey(entry, sort, now, zone, locale)
    }

/** [entries] must already be in [sort] order. [now] is read once so date boundaries cannot move mid-pass. */
fun sectionsOf(
    entries: List<Entry>,
    sort: SortOrder,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<SectionRun> = Sections.runs(
    entries,
    key = { sectionKey(it, sort, now, zone, locale) },
    label = { sectionLabel(it, sort, now, zone, locale) },
)
