package com.lunaexplorer.app.storage

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ReadingDocumentTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun archive(vararg entries: Pair<String, String>): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, value) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
    }

    @Test fun `EPUB follows spine order and navigation names rather than ZIP order`() {
        val file = archive(
            "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="Book/content.opf"/></rootfiles></container>""",
            "Book/content.opf" to """<package xmlns="http://www.idpf.org/2007/opf"><manifest>
                <item id="two" href="second.xhtml" media-type="application/xhtml+xml"/>
                <item id="one" href="chapter%201.xhtml" media-type="application/xhtml+xml"/>
                <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
                </manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>""",
            "Book/second.xhtml" to "<html><body>Second</body></html>",
            "Book/chapter 1.xhtml" to "<html><body>First</body></html>",
            "Book/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><nav><a href="chapter%201.xhtml#start">The beginning</a><a href="second.xhtml">Afterwards</a></nav></body></html>""",
        )
        ReadingDocument.open(file, "epub").use { book ->
            assertEquals(listOf("Book/chapter 1.xhtml", "Book/second.xhtml"), book.sections.map { it.path })
            assertEquals(listOf("The beginning", "Afterwards"), book.sections.map { it.title })
            assertTrue(book.resource("Book/chapter%201.xhtml")!!.bytes.toString(Charsets.UTF_8).contains("First"))
        }
    }

    @Test fun `archive paths keep unicode plus signs and hidden characters but reject escaping the package`() {
        assertEquals("Book/text/chapter one.xhtml", packagePath("Book/package.opf", "text/chapter%20one.xhtml#title"))
        assertEquals("Book/日本語+\u200b.xhtml", packagePath("Book/package.opf", "日本語+\u200b.xhtml"))
        assertEquals("Book/chapter.xhtml", packagePath("Book/chapter.xhtml", "#one"))
        assertEquals("images/pic.png", packagePath("Book/package.opf", "../images/pic.png"))
        assertNull(packagePath("Book/package.opf", "../../outside"))
        assertNull(packagePath("Book/package.opf", "%2e%2e/%2e%2e/outside"))
        assertNull(packagePath("Book/package.opf", "https://example.org/chapter.xhtml"))
        assertNull(packagePath("Book/package.opf", "//example.org/chapter.xhtml"))
        assertNull(packagePath("Book/package.opf", "file:///data/private"))
        assertNull(packagePath("Book/package.opf", "..\\outside"))
    }

    @Test fun `DOCX preserves headings styled escaped text tables and embedded images`() {
        val file = archive(
            "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body>
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Heading</w:t></w:r></w:p>
                <w:p><w:r><w:rPr><w:b/><w:i/></w:rPr><w:t>&lt;script&gt; &amp; content</w:t></w:r><w:r><w:drawing><a:blip r:embed="image1"/></w:drawing></w:r></w:p>
                <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Cell text</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                </w:body></w:document>""",
            "word/_rels/document.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="image1" Target="media/picture.png" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"/></Relationships>""",
            "word/media/picture.png" to "test-image",
        )
        ReadingDocument.open(file, "docx").use { book ->
            val html = book.sections.single().html!!
            assertTrue(html.contains("<h1"))
            assertTrue(html.contains("font-weight:bold;font-style:italic;"))
            assertTrue(html.contains("&lt;script&gt; &amp; content"))
            assertFalse(html.contains("<script>"))
            assertTrue(html.contains("<table><tr><td"))
            assertTrue(html.contains("Cell text"))
            assertTrue(html.contains("https://luna-document.invalid/word/media/picture.png"))
            assertNotNull(book.notice)
        }
    }

    @Test fun `PPTX respects presentation relationship order and slide positions`() {
        fun slide(text: String) = """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:spPr><a:xfrm><a:off x="100" y="200"/><a:ext cx="5000" cy="3000"/></a:xfrm></p:spPr><p:txBody><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>"""
        val file = archive(
            "ppt/presentation.xml" to """<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId id="256" r:id="rSecond"/><p:sldId id="257" r:id="rFirst"/></p:sldIdLst><p:sldSz cx="9144000" cy="6858000"/></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rFirst" Target="slides/slide1.xml"/><Relationship Id="rSecond" Target="slides/slide2.xml"/></Relationships>""",
            "ppt/slides/slide1.xml" to slide("Later slide"),
            "ppt/slides/slide2.xml" to slide("First slide"),
        )
        ReadingDocument.open(file, "pptx").use { book ->
            assertEquals(listOf("1. First slide", "2. Later slide"), book.sections.map { it.title })
            val first = book.sections.first().html!!
            assertTrue(first.contains("<foreignObject x=\"100.0\" y=\"200.0\" width=\"5000.0\" height=\"3000.0\""))
            assertTrue(first.contains("First slide"))
        }
    }

    @Test fun `external relationships do not become document image requests`() {
        val file = archive(
            "word/document.xml" to """<w:document xmlns:w="urn:w" xmlns:a="urn:a" xmlns:r="urn:r"><w:body><w:p><w:r><w:drawing><a:blip r:embed="remote"/></w:drawing></w:r></w:p></w:body></w:document>""",
            "word/_rels/document.xml.rels" to """<Relationships xmlns="urn:r"><Relationship Id="remote" Target="https://example.org/tracking.png" TargetMode="External"/></Relationships>""",
        )
        ReadingDocument.open(file, "docx").use { assertFalse(it.sections.single().html!!.contains("tracking.png")) }
    }

    @Test fun `XML entity cannot expose a local file`() {
        val secret = temporary.newFile().apply { writeText("LOCAL-SECRET-CONTENT") }
        val file = archive("word/document.xml" to """<!DOCTYPE document [<!ENTITY leak SYSTEM "${secret.toURI()}">]><w:document xmlns:w="urn:w"><w:body><w:p><w:r><w:t>&leak;</w:t></w:r></w:p></w:body></w:document>""")
        ReadingDocument.open(file, "docx").use { assertFalse(it.sections.single().html!!.contains("LOCAL-SECRET-CONTENT")) }
    }

    @Test fun `EPUB remote spine entries fail clearly instead of loading the network`() {
        val file = archive(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""",
            "book.opf" to """<package><manifest><item id="one" href="https://example.org/book.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/></spine></package>""",
        )
        val failure = runCatching { ReadingDocument.open(file, "epub") }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("outside the book"))
    }

    @Test fun `oversized decompressed parts are rejected before reading`() {
        val file = temporary.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            val block = ByteArray(1024 * 1024)
            repeat(25) { zip.write(block) }
            zip.closeEntry()
        }
        val failure = runCatching { ReadingDocument.open(file, "docx") }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("too large"))
    }

    @Test fun `excessive XML nesting is rejected before building a recursive DOM`() {
        val file = archive("word/document.xml" to "<document>" + "<body>".repeat(150) + "</body>".repeat(150) + "</document>")
        val failure = runCatching { ReadingDocument.open(file, "docx") }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("too complex"))
    }
}
