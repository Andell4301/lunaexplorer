package com.lunaexplorer.app.model

import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.Sections
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class EntrySectionsTest {
    private val utc = ZoneId.of("UTC")
    private val us = Locale.US
    private val now = ZonedDateTime.of(2025, 9, 16, 12, 0, 0, 0, utc).toInstant().toEpochMilli()

    private fun entry(name: String, directory: Boolean = false) = Entry(
        NodeRef("memory", name), name, directory, size = 10L, modified = now,
        mimeType = if (directory) "inode/directory" else "text/plain",
    )

    @Test fun `a folder is bucketed by a key that means something for it`() {
        val folder = entry("alpha", directory = true)
        listOf(SortOrder.NAME, SortOrder.NATURAL).forEach { sort ->
            assertEquals("$sort must bucket a folder by its name", "A",
                sectionKey(folder, sort, now, utc, us))
        }
        assertEquals("Today", sectionKey(folder, SortOrder.MODIFIED, now, utc, us))
        listOf(SortOrder.EXTENSION, SortOrder.TYPE, SortOrder.SIZE).forEach { sort ->
            assertEquals("$sort has nothing to say about a folder", Sections.FOLDERS,
                sectionKey(folder, sort, now, utc, us))
        }
    }

    @Test fun `folders are sectioned by name, ahead of the files sectioned the same way`() {
        // Already in browser order: folders first, then case-insensitive by name.
        val listing = listOf(
            entry("alpha", directory = true), entry("Beta", directory = true),
            entry("apple.txt"), entry("banana.txt"), entry("cherry.md"),
        )
        val runs = sectionsOf(listing, SortOrder.NAME, now, utc, us)

        assertEquals(listOf("A", "B", "A", "B", "C"), runs.map { it.label })
        assertEquals("Each run is one entry, folders included", List(5) { 1 }, runs.map { it.count })
    }
}
