package com.lunaexplorer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class SectionsTest {
    private val utc = ZoneId.of("UTC")
    private val us = Locale.US

    @Test fun `case folds but accents keep their own bucket`() {
        assertEquals("A", Sections.firstCharacterKey("apple"))
        assertEquals("A", Sections.firstCharacterKey("Apple"))
        assertEquals("Ä", Sections.firstCharacterKey("ÄPPLE"))
        assertEquals("Ä", Sections.firstCharacterKey("äpple"))
    }

    @Test fun `uppercasing never expands a key into more than one character`() {
        assertEquals(1, Sections.firstCharacterKey("ßeta").length)
        assertEquals(1, Sections.firstCharacterKey("ﬁle").length)
    }

    @Test fun `only ASCII digits share the number bucket`() {
        assertEquals("#", Sections.firstCharacterKey("7zip.7z"))
        assertEquals("#", Sections.firstCharacterKey("0"))
        assertEquals("０", Sections.firstCharacterKey("０ok"))
    }

    @Test fun `kana keep their own buckets and Han shares one`() {
        assertEquals("あ", Sections.firstCharacterKey("あさ.txt"))
        assertEquals("ア", Sections.firstCharacterKey("アニメ.mkv"))
        assertEquals("漢字", Sections.firstCharacterKey("漢字.txt"))
        assertEquals("漢字", Sections.firstCharacterKey("字典.txt"))
    }

    @Test fun `other scripts bucket by their own letter`() {
        assertEquals("Я", Sections.firstCharacterKey("Яблоко"))
        assertEquals("Ω", Sections.firstCharacterKey("ωμέγα"))
    }

    @Test fun `a surrogate pair is never split`() {
        val key = Sections.firstCharacterKey("🎉party.png")
        assertEquals(2, key.length)
        assertEquals("🎉", key)
    }

    @Test fun `a label keeps the whole grapheme cluster the key only starts`() {
        // A flag is two regional indicators: the key is the first, the label is both.
        val flag = String(Character.toChars(0x1F1EF)) + String(Character.toChars(0x1F1F5))
        assertEquals(2, Sections.firstCharacterKey(flag + "tokyo.jpg").length)
        assertEquals(flag, Sections.firstCharacterLabel(flag + "tokyo.jpg"))
        // A decomposed e-acute keys as E, and labels with its combining mark intact.
        val decomposed = "e" + String(Character.toChars(0x0301)) + "clair"
        assertEquals("E", Sections.firstCharacterKey(decomposed))
        assertEquals("E" + String(Character.toChars(0x0301)), Sections.firstCharacterLabel(decomposed))
        // The precomposed form is its own single code point, so key and label agree.
        val precomposed = String(Character.toChars(0x00E9)) + "clair"
        assertEquals(String(Character.toChars(0x00C9)), Sections.firstCharacterKey(precomposed))
        assertEquals(String(Character.toChars(0x00C9)), Sections.firstCharacterLabel(precomposed))
    }

    @Test fun `nothing to bucket on falls to Other`() {
        assertEquals("Other", Sections.firstCharacterKey(""))
        assertEquals("Other", Sections.firstCharacterLabel(""))
        assertEquals("Other", Sections.firstCharacterLabel(String(Character.toChars(0)) + "x"))
        assertEquals("Other", Sections.firstCharacterLabel(" leading"))
    }

    private val mixed = listOf(
        "apple.txt", "Apricot.txt", "ßeta.txt", "ﬁle.txt", "banana.txt", "Cherry.txt",
        "äpfel.txt", "Ähre.txt", "éclair.txt", "égg.txt", "7zip.7z", "0001.bin",
        "Яблоко.txt", "ягода.txt", "あさ.txt", "アニメ.mkv", "漢字.txt", "字典.txt",
        "-dash.txt", "_under.txt", "[bracket].txt", "🎉party.png", "zebra.txt",
        "Sugar.txt", "ssa.txt", "tea.txt", "ﬂour.txt", "Flour.txt",
    )

    private fun assertContiguous(names: List<String>) {
        val runs = Sections.runs(names, Sections::firstCharacterKey)
        val keys = runs.map { Sections.firstCharacterKey(names[it.start]) }
        assertEquals("A key may only open one run: $keys", keys.size, keys.toSet().size)
    }

    @Test fun `every bucket is one run under the case-insensitive comparator`() {
        assertContiguous(mixed.sortedWith(String.CASE_INSENSITIVE_ORDER))
    }

    @Test fun `every bucket is one run under natural order`() {
        assertContiguous(mixed.sortedWith { a, b -> NaturalOrder.compareNames(a, b) })
    }

    /** Han spans several Unicode blocks, and a non-Han character can sort between them. */
    @Test fun `Han can legitimately open more than one run`() {
        // CJK Extension A, a Yijing hexagram (not Han), then the main ideograph block.
        val names = listOf(
            String(Character.toChars(0x3400)) + "a.txt",
            String(Character.toChars(0x4DC0)) + "b.txt",
            String(Character.toChars(0x4E00)) + "c.txt",
        )
        val runs = Sections.runs(names.sortedWith(String.CASE_INSENSITIVE_ORDER), Sections::firstCharacterKey)
        assertEquals(3, runs.size)
        assertEquals(listOf(Sections.HAN, Sections.HAN), runs.filter { it.label == Sections.HAN }.map { it.label })
    }

    @Test fun `extension mirrors the sort key exactly`() {
        assertEquals(".jpg", Sections.extension("a.JPG"))
        assertEquals(".gz", Sections.extension("a.tar.gz"))
        assertEquals("No extension", Sections.extension("Makefile"))
        assertEquals(".bashrc", Sections.extension(".bashrc"))
    }

    @Test fun `media type is the part before the slash`() {
        assertEquals("Image", Sections.mediaType("image/png"))
        assertEquals("Application", Sections.mediaType("application/vnd.android.package-archive"))
        assertEquals("Inode", Sections.mediaType("inode/directory"))
        assertEquals("Other", Sections.mediaType(""))
    }

    @Test fun `size buckets sit on the boundaries they name`() {
        assertEquals("Unknown size", Sections.size(null))
        assertEquals("Empty", Sections.size(0))
        assertEquals("Empty", Sections.size(-1))
        assertEquals("Under 1 KiB", Sections.size(1))
        assertEquals("Under 1 KiB", Sections.size(1023))
        assertEquals("1–100 KiB", Sections.size(1024))
        assertEquals("1–100 KiB", Sections.size(102_399))
        assertEquals("100 KiB–1 MiB", Sections.size(102_400))
        assertEquals("100 KiB–1 MiB", Sections.size((1L shl 20) - 1))
        assertEquals("1–10 MiB", Sections.size(1L shl 20))
        assertEquals("1–10 GiB", Sections.size((10L shl 30) - 1))
        assertEquals("Over 10 GiB", Sections.size(10L shl 30))
        assertEquals("Over 10 GiB", Sections.size(Long.MAX_VALUE))
    }

    @Test fun `each size bucket forms exactly one run when sorted by size`() {
        val sizes = listOf<Long?>(null, 0, 7, 900, 5_000, 200_000, 3L shl 20, 60L shl 20,
            700L shl 20, 4L shl 30, 40L shl 30, 1024, 102_400).shuffled(java.util.Random(7))
            .sortedBy { it ?: -1L }
        val labels = Sections.runs(sizes, key = { Sections.size(it) }).map { it.label }
        assertEquals("A size label may only open one run: $labels", labels.size, labels.toSet().size)
    }

    private fun at(year: Int, month: Int, day: Int): Long =
        ZonedDateTime.of(year, month, day, 12, 0, 0, 0, utc).toInstant().toEpochMilli()

    @Test fun `date buckets read newest first`() {
        val now = at(2025, 9, 16)
        assertEquals("Unknown date", Sections.date(null, now, utc, us))
        assertEquals("Unknown date", Sections.date(0, now, utc, us))
        assertEquals("Unknown date", Sections.date(-5, now, utc, us))
        assertEquals("Later", Sections.date(at(2025, 9, 17), now, utc, us))
        assertEquals("Today", Sections.date(now, now, utc, us))
        assertEquals("Yesterday", Sections.date(at(2025, 9, 15), now, utc, us))
        assertEquals("This week", Sections.date(at(2025, 9, 13), now, utc, us))
        assertEquals("This week", Sections.date(at(2025, 9, 10), now, utc, us))
        assertEquals("This month", Sections.date(at(2025, 9, 2), now, utc, us))
        assertEquals("August", Sections.date(at(2025, 8, 4), now, utc, us))
        assertEquals("2024", Sections.date(at(2024, 12, 31), now, utc, us))
    }

    @Test fun `each date bucket forms exactly one run when sorted by time`() {
        val now = at(2025, 9, 16)
        val times = listOf(at(2025, 9, 17), now, at(2025, 9, 15), at(2025, 9, 12), at(2025, 9, 3),
            at(2025, 8, 20), at(2025, 2, 1), at(2024, 6, 6), at(2023, 1, 1))
            .shuffled(java.util.Random(3)).sorted()
        val labels = Sections.runs(times, key = { Sections.date(it, now, utc, us) }).map { it.label }
        assertEquals("A date label may only open one run: $labels", labels.size, labels.toSet().size)
    }

    @Test fun `runs cover the list and take each label from its first member`() {
        assertEquals(emptyList<SectionRun>(), Sections.runs(emptyList<String>(), { it }))
        assertEquals(listOf(SectionRun("a", 0, 1)), Sections.runs(listOf("a"), { it }))
        assertEquals(listOf(SectionRun("a", 0, 3)), Sections.runs(listOf("a", "a", "a"), { it }))
        assertEquals(
            listOf(SectionRun("a", 0, 2), SectionRun("b", 2, 1), SectionRun("a", 3, 1)),
            Sections.runs(listOf("a", "a", "b", "a"), { it }),
        )
        assertEquals(listOf("first"), Sections.runs(listOf("first", "second"), key = { "k" }, label = { it }).map { it.label })
    }

    @Test fun `entry index skips the header each section contributes`() {
        val sections = listOf(SectionRun("A", 0, 2), SectionRun("B", 2, 1), SectionRun("C", 3, 3))
        // Items: [0]=A header, [1,2]=entries 0,1, [3]=B header, [4]=entry 2, [5]=C header, [6,7,8]=3,4,5
        assertEquals(listOf(null, 0, 1, null, 2, null, 3, 4, 5), (0..8).map { Sections.entryIndexAt(sections, it) })
        assertNull(Sections.entryIndexAt(sections, -1))
        assertNull(Sections.entryIndexAt(sections, 9))
        assertEquals(4, Sections.entryIndexAt(emptyList(), 4))
    }

    @Test fun `entry index agrees with a brute-force expansion for random runs`() {
        val random = java.util.Random(11)
        repeat(50) {
            var start = 0
            val sections = List(random.nextInt(6) + 1) {
                val count = random.nextInt(4) + 1
                SectionRun("s$it", start, count).also { start += count }
            }
            val expanded = mutableListOf<Int?>()
            sections.forEach { section ->
                expanded += null
                repeat(section.count) { offset -> expanded += section.start + offset }
            }
            assertEquals(expanded, expanded.indices.map { Sections.entryIndexAt(sections, it) })
        }
    }
}
