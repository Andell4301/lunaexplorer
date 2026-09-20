package com.lunaexplorer.app.ui

import com.lunaexplorer.app.model.ViewerKind
import com.lunaexplorer.core.ArchiveEngine
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.ProviderRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentRoutingTest {
    private fun entry(name: String, mime: String = "application/octet-stream") = Entry(
        NodeRef("smb", "nas:share:$name"), name, directory = false, mimeType = mime,
        capabilities = setOf(Capability.READ),
    )

    @Test fun `PDF and book formats route internally even with a generic provider MIME type`() {
        assertEquals(ViewerKind.PDF, viewerFor(entry("manual.PDF")))
        for (name in listOf("novel.epub", "report.docx", "slides.pptx")) {
            assertEquals(name, ViewerKind.DOCUMENT, viewerFor(entry(name)))
        }
    }

    @Test fun `document MIME types work without an extension`() {
        assertEquals(ViewerKind.PDF, viewerFor(entry("manual", "application/pdf")))
        assertEquals(ViewerKind.DOCUMENT, viewerFor(entry("book", "application/epub+zip")))
        assertEquals(ViewerKind.DOCUMENT, viewerFor(entry("document", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")))
    }

    @Test fun `a book opens in the reader although it is also a browsable zip`() {
        val archives = ArchiveEngine(ProviderRegistry(emptyList()))
        assertTrue("The archive browser can still be chosen for it", archives.canBrowse(entry("novel.epub")))
        assertFalse(opensAsArchive(entry("novel.epub"), archives))
        assertTrue(opensAsArchive(entry("photos.zip"), archives))
    }

    @Test fun `document-looking directories and unreadable files have no built-in viewer`() {
        assertEquals(ViewerKind.NONE, viewerFor(entry("folder.pdf").copy(directory = true)))
        assertEquals(ViewerKind.NONE, viewerFor(entry("private.docx").copy(capabilities = emptySet())))
    }
}
