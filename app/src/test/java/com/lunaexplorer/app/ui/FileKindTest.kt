package com.lunaexplorer.app.ui

import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileKindTest {
    private fun file(name: String, mimeType: String = "application/octet-stream") =
        Entry(NodeRef("test", name), name, directory = false, mimeType = mimeType)

    @Test fun `a media type wins over an extension it shares with a language`() {
        assertEquals(FileKind.VIDEO, fileKind(file("clip.ts", "video/mp2t")))
        assertEquals(FileKind.CODE, fileKind(file("view.ts")))
    }

    @Test fun `a link stays a link whatever it is named`() {
        assertEquals(FileKind.LINK, fileKind(file("main.py", "inode/symlink")))
    }

    @Test fun `the name decides when the provider reports no useful type`() {
        assertEquals(FileKind.WORD, fileKind(file("Report.DOCX")))
        assertEquals(FileKind.ARCHIVE, fileKind(file("backup.7z")))
        assertEquals(FileKind.OTHER, fileKind(file("Makefile")))
    }

    @Test fun `a badge is labelled with the extension only where that reads`() {
        assertEquals("PY", badgeLabel(file("main.py"), FileKind.CODE))
        assertNull("Too long to fit the badge", badgeLabel(file("View.swift"), FileKind.CODE))
        assertNull("Office files keep their glyph", badgeLabel(file("report.docx"), FileKind.WORD))
    }
}
