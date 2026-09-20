package com.lunaexplorer.core

import org.junit.Assert.*
import org.junit.Test

class BatchRenameTest {

    private fun file(name: String, size: Long = 100, modified: Long? = 1_700_000_000_000) =
        Entry(NodeRef("test", name), name, directory = false, size = size, modified = modified)

    private fun folder(name: String) = Entry(NodeRef("test", name), name, directory = true)

    private fun run(entries: List<Entry>, vararg steps: RenameStep, existing: Set<String> = emptySet()) =
        BatchRename.preview(entries, steps.toList(), existing)

    @Test fun `the extension is left alone while the name is changed`() {
        val results = run(listOf(file("Holiday PHOTO.JPG")), RenameStep.ChangeCase(CaseMode.LOWER))
        assertEquals("holiday photo.JPG", results.single().to)
    }

    @Test fun `a folder has no extension to protect`() {
        val results = run(listOf(folder("My.Documents")), RenameStep.ChangeCase(CaseMode.UPPER))
        assertEquals("MY.DOCUMENTS", results.single().to)
    }

    @Test fun `splitExtension applies the same rule the batch uses`() {
        assertEquals("holiday photo" to "JPG", splitExtension("holiday photo.JPG", directory = false))
        assertEquals(".tar" to "gz", splitExtension(".tar.gz", directory = false))
        assertEquals(".bashrc" to "", splitExtension(".bashrc", directory = false))
        assertEquals("README" to "", splitExtension("README", directory = false))
        assertEquals("trailing." to "", splitExtension("trailing.", directory = false))
        assertEquals("My.Documents" to "", splitExtension("My.Documents", directory = true))
        assertEquals("archive.2024.releasenotes" to "", splitExtension("archive.2024.releasenotes", directory = false))
        assertEquals("draft.final copy" to "", splitExtension("draft.final copy", directory = false))
    }

    @Test fun `without protection the steps act on the whole name`() {
        val entries = listOf(file("holiday photo.JPG"), file("notes.txt"))
        val results = BatchRename.preview(entries, listOf(
            RenameStep.ChangeCase(CaseMode.LOWER),
            RenameStep.Replace(find = "txt", with = "md"),
        ), keepExtension = false)
        assertEquals(listOf("holiday photo.jpg", "notes.md"), results.map { it.to })
    }

    @Test fun `a template can still name the extension when nothing protects it`() {
        val results = BatchRename.preview(listOf(file("raw.dng")),
            listOf(RenameStep.Template("{n}.{ext}")), keepExtension = false)
        assertEquals("1.dng", results.single().to)
    }

    @Test fun `replace handles literal text and leaves the rest alone`() {
        val results = run(listOf(file("IMG_0001.jpg"), file("IMG_0002.jpg")),
            RenameStep.Replace(find = "IMG", with = "Holiday"))
        assertEquals(listOf("Holiday_0001.jpg", "Holiday_0002.jpg"), results.map { it.to })
    }

    @Test fun `a regular expression can reorder with capture groups`() {
        val results = run(listOf(file("2024-05-01 report.pdf")),
            RenameStep.Replace(find = """(\d{4})-(\d{2})-(\d{2}) (.+)""", with = "$4 ($3.$2.$1)", regex = true))
        assertEquals("report (01.05.2024).pdf", results.single().to)
    }

    @Test fun `an invalid pattern is reported against the item rather than thrown`() {
        val results = run(listOf(file("a.txt")), RenameStep.Replace(find = "(unclosed", with = "x", regex = true))
        assertTrue(results.single().blocked)
        assertTrue(results.single().problem!!.contains("valid pattern"))
    }

    @Test fun `numbering pads and steps`() {
        val entries = listOf(file("a.txt"), file("b.txt"), file("c.txt"))
        val results = run(entries, RenameStep.Numbering(start = 8, step = 2, padding = 3, separator = "-"))
        assertEquals(listOf("a-008.txt", "b-010.txt", "c-012.txt"), results.map { it.to })
    }

    @Test fun `a template rebuilds the name from tokens`() {
        val results = run(listOf(file("raw.dng")),
            RenameStep.Template("{date}_{n}_{name}"), RenameStep.Numbering(start = 5, padding = 2))
        // The template consumed {n}, so the numbering step must not append a second number.
        assertEquals("2023-11-14_5_raw.dng", results.single().to)
    }

    @Test fun `insert and remove count from the end when given a negative position`() {
        assertEquals("prefix-name.txt",
            run(listOf(file("name.txt")), RenameStep.Insert("prefix-", 0)).single().to)
        assertEquals("nam.txt",
            run(listOf(file("name.txt")), RenameStep.Remove(-1, 1)).single().to)
        assertEquals("na.txt",
            run(listOf(file("name.txt")), RenameStep.Remove(-2, 2)).single().to)
        assertEquals("name-end.txt",
            run(listOf(file("name.txt")), RenameStep.Insert("-end", 99)).single().to)
    }

    @Test fun `steps apply in the order they are given`() {
        val forwards = run(listOf(file("photo.jpg")),
            RenameStep.Insert("x", 0), RenameStep.ChangeCase(CaseMode.UPPER)).single().to
        val backwards = run(listOf(file("photo.jpg")),
            RenameStep.ChangeCase(CaseMode.UPPER), RenameStep.Insert("x", 0)).single().to
        assertEquals("XPHOTO.jpg", forwards)
        assertEquals("xPHOTO.jpg", backwards)
    }

    @Test fun `two items collapsing onto one name are both blocked`() {
        val results = run(listOf(file("a.txt"), file("b.txt")),
            RenameStep.Template("same"))
        assertTrue(results.all { it.blocked })
        assertTrue(results.first().problem!!.contains("Two items"))
    }

    @Test fun `a name already taken in the folder is blocked`() {
        val results = run(listOf(file("draft.txt")), RenameStep.Template("final"),
            existing = setOf("final.txt", "other.txt"))
        assertTrue(results.single().blocked)
        assertTrue(results.single().problem!!.contains("already exists"))
    }

    @Test fun `renaming onto a name in the batch is allowed and staged, not blocked`() {
        val entries = listOf(file("a.txt"), file("b.txt"))
        val results = BatchRename.preview(entries, listOf(
            RenameStep.Replace(find = "a", with = "TEMP"),
            RenameStep.Replace(find = "b", with = "a"),
            RenameStep.Replace(find = "TEMP", with = "b"),
        ))
        assertEquals(listOf("b.txt", "a.txt"), results.map { it.to })
        assertTrue("A swap is executable, so it must not be blocked", results.none { it.blocked })

        val plan = BatchRename.plan(results)
        assertEquals(2, plan.size)
        assertTrue("Both sides of a swap must go through a temporary name",
            plan.all { it.viaTemporary })
    }

    @Test fun `a name the filesystem would refuse is caught before anything is renamed`() {
        val results = run(listOf(file("fine.txt")), RenameStep.Template("bad/name"))
        assertTrue(results.single().blocked)
    }

    @Test fun `an unchanged item is not renamed`() {
        val results = run(listOf(file("keep.txt")), RenameStep.Replace(find = "zzz", with = "x"))
        assertFalse(results.single().changed)
        assertTrue(BatchRename.plan(results).isEmpty())
    }

    @Test fun `the plan only stages what actually has to move out of the way`() {
        val entries = listOf(file("one.txt"), file("two.txt"))
        val results = BatchRename.preview(entries, listOf(RenameStep.Insert("new-", 0)))
        val plan = BatchRename.plan(results)
        assertEquals(2, plan.size)
        assertTrue("Nothing collides, so nothing needs a temporary name", plan.none { it.viaTemporary })
    }
}
