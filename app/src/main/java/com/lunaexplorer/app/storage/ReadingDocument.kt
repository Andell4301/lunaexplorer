package com.lunaexplorer.app.storage

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

internal class ReadingDocument private constructor(
    private val archive: ZipFile,
    val sections: List<Section>,
    val notice: String?,
) : Closeable {
    data class Section(val title: String, val path: String, val html: String? = null)
    class Resource(val mime: String, val bytes: ByteArray)

    fun resource(path: String): Resource? {
        val normalized = packagePath("", path) ?: return null
        sections.firstOrNull { it.path == normalized && it.html != null }?.let {
            return Resource("text/html", it.html!!.toByteArray(Charsets.UTF_8))
        }
        val entry = archive.getEntry(normalized) ?: return null
        if (entry.isDirectory) return null
        return Resource(resourceMime(normalized), readPart(archive, normalized))
    }

    override fun close() = archive.close()

    companion object {
        const val MAX_FILE_BYTES = 256L * 1024 * 1024
        private const val MAX_PART_BYTES = 24L * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 384L * 1024 * 1024
        private const val MAX_PREVIEW_CHARS = 16 * 1024 * 1024
        private const val MAX_PARTS = 20_000

        fun open(file: File, extension: String): ReadingDocument {
            val archive = ZipFile(file)
            try {
                require(archive.size() <= MAX_PARTS) { "This document contains too many parts to preview." }
                var expanded = 0L
                val names = HashSet<String>()
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    require(names.add(entry.name)) { "This document has duplicate package parts." }
                    require(entry.size >= 0 && entry.size <= MAX_PART_BYTES) {
                        "A part of this document is too large to preview."
                    }
                    expanded += entry.size
                    require(expanded <= MAX_EXPANDED_BYTES) { "This document is too large to preview." }
                }
                val format = extension.lowercase().ifBlank {
                    when {
                        archive.getEntry("META-INF/container.xml") != null -> "epub"
                        archive.getEntry("word/document.xml") != null -> "docx"
                        archive.getEntry("ppt/presentation.xml") != null -> "pptx"
                        else -> ""
                    }
                }
                return when (format) {
                    "epub" -> epub(archive)
                    "docx" -> word(archive)
                    "pptx" -> presentation(archive)
                    else -> error("This document format is not supported by the built-in reader.")
                }
            } catch (failure: Throwable) {
                archive.close()
                throw failure
            }
        }

        private fun epub(zip: ZipFile): ReadingDocument {
            val container = xml(zip, "META-INF/container.xml")
            val packageName = container.descendants("rootfile").firstOrNull()?.attr("full-path")
                ?.let { packagePath("", it) } ?: error("The EPUB has no readable package document.")
            val opf = xml(zip, packageName)
            val manifest = opf.descendants("manifest").firstOrNull()?.children("item").orEmpty()
                .associateBy { it.attr("id") }
            val sections = opf.descendants("spine").firstOrNull()?.children("itemref").orEmpty()
                .mapIndexed { index, ref ->
                    var item = manifest[ref.attr("idref")] ?: error("An EPUB chapter is missing from its manifest.")
                    val visited = mutableSetOf<String>()
                    while (item.attr("media-type") !in setOf("application/xhtml+xml", "text/html", "image/svg+xml")) {
                        require(visited.add(item.attr("id"))) { "The EPUB contains a cyclic chapter fallback." }
                        item = manifest[item.attr("fallback")] ?: error("This EPUB uses an unsupported chapter format.")
                    }
                    val path = packagePath(packageName, item.attr("href"))
                        ?: error("The EPUB references a chapter outside the book.")
                    require(zip.getEntry(path) != null) { "An EPUB chapter is missing: $path" }
                    Section("Chapter ${index + 1}", path)
                }
            require(sections.isNotEmpty()) { "The EPUB has no chapters." }
            if (zip.getEntry("META-INF/encryption.xml") != null) {
                val methods = xml(zip, "META-INF/encryption.xml").descendants("EncryptionMethod")
                require(methods.all { it.attr("Algorithm") in setOf(
                    "http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC",
                ) }) { "This EPUB is protected by DRM. Open it in the app that unlocks this book." }
            }
            val labels = mutableMapOf<String, String>()
            manifest.values.firstOrNull { "nav" in it.attr("properties").split(' ') }?.let { nav ->
                packagePath(packageName, nav.attr("href"))?.let { path ->
                    runCatching { xml(zip, path) }.getOrNull()?.descendants("a")?.forEach { link ->
                        packagePath(path, link.attr("href"))?.let { target ->
                            labels.putIfAbsent(target, link.textContent.trim())
                        }
                    }
                }
            }
            manifest.values.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }?.let { ncx ->
                packagePath(packageName, ncx.attr("href"))?.let { path ->
                    runCatching { xml(zip, path) }.getOrNull()?.descendants("navPoint")?.forEach { point ->
                        val href = point.children("content").firstOrNull()?.attr("src").orEmpty()
                        val label = point.children("navLabel").firstOrNull()?.textContent?.trim().orEmpty()
                        packagePath(path, href)?.let { labels.putIfAbsent(it, label) }
                    }
                }
            }
            return ReadingDocument(zip, sections.map { it.copy(title = labels[it.path]?.takeIf(String::isNotBlank) ?: it.title) }, null)
        }

        private fun word(zip: ZipFile): ReadingDocument {
            val main = mainPart(zip, "word/document.xml")
            val document = xml(zip, main)
            val relationships = relationships(zip, main)
            val body = document.descendants("body").firstOrNull() ?: error("The Word document has no body.")
            val content = wordBlocks(body, relationships)
            val html = htmlPage("Word document", content)
            require(html.length <= MAX_PREVIEW_CHARS) { "This document is too large to preview." }
            return ReadingDocument(zip, listOf(Section("Document", "__luna_document.html", html)),
                "Reading preview · Complex page layout, tracked changes, charts and embedded objects may differ.")
        }

        private fun wordBlocks(parent: Element, rels: Map<String, String>): String = buildString {
            for (child in parent.children()) when (child.tag()) {
                "p" -> {
                    val properties = child.children("pPr").firstOrNull()
                    val style = properties?.children("pStyle")?.firstOrNull()?.attr("val").orEmpty()
                    val heading = Regex("(?i)heading([1-6])").matchEntire(style)?.groupValues?.get(1)
                    val tag = if (heading != null) "h$heading" else "p"
                    val align = properties?.children("jc")?.firstOrNull()?.attr("val")
                    val css = when (align) { "center", "right" -> "text-align:$align;"; "both" -> "text-align:justify;"; else -> "" }
                    append("<$tag style=\"$css\">")
                    if (properties?.children("numPr")?.isNotEmpty() == true) append("<span>• </span>")
                    append(wordInline(child, rels)).append("</$tag>")
                }
                "tbl" -> {
                    append("<table>")
                    for (row in child.children("tr")) {
                        append("<tr>")
                        for (cell in row.children("tc")) {
                            val span = cell.children("tcPr").firstOrNull()?.children("gridSpan")?.firstOrNull()
                                ?.attr("val")?.toIntOrNull()?.coerceIn(1, 100) ?: 1
                            append("<td colspan=\"$span\">").append(wordBlocks(cell, rels)).append("</td>")
                        }
                        append("</tr>")
                    }
                    append("</table>")
                }
                "sdt" -> child.children("sdtContent").firstOrNull()?.let { append(wordBlocks(it, rels)) }
            }
        }

        private fun wordInline(parent: Element, rels: Map<String, String>): String = buildString {
            for (child in parent.children()) when (child.tag()) {
                "r" -> {
                    val properties = child.children("rPr").firstOrNull()
                    val css = buildString {
                        if (properties?.enabled("b") == true) append("font-weight:bold;")
                        if (properties?.enabled("i") == true) append("font-style:italic;")
                        if (properties?.enabled("u") == true) append("text-decoration:underline;")
                        if (properties?.enabled("strike") == true) append("text-decoration:line-through;")
                        properties?.children("sz")?.firstOrNull()?.attr("val")?.toIntOrNull()
                            ?.takeIf { it in 8..192 }?.let { append("font-size:${it / 2.0}pt;") }
                        properties?.children("color")?.firstOrNull()?.attr("val")?.takeIf(::isColor)
                            ?.let { append("color:#$it;") }
                    }
                    append("<span style=\"$css\">").append(wordInline(child, rels)).append("</span>")
                }
                "t" -> append(escapeHtml(child.textContent))
                "tab" -> append("&emsp;")
                "br", "cr" -> append("<br>")
                "drawing", "pict" -> {
                    val image = child.descendants("blip").firstOrNull()?.attr("embed")
                        ?: child.descendants("imagedata").firstOrNull()?.attr("id")
                    rels[image]?.let { append("<img alt=\"Document image\" src=\"").append(escapeHtml(resourceUrl(it))).append("\">") }
                }
                "hyperlink", "smartTag", "sdtContent", "ins" -> append(wordInline(child, rels))
                "sdt" -> child.children("sdtContent").firstOrNull()?.let { append(wordInline(it, rels)) }
            }
        }

        private fun presentation(zip: ZipFile): ReadingDocument {
            val main = mainPart(zip, "ppt/presentation.xml")
            val presentation = xml(zip, main)
            val rels = relationships(zip, main)
            val size = presentation.descendants("sldSz").firstOrNull()
            val width = size?.attr("cx")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 9144000.0
            val height = size?.attr("cy")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 6858000.0
            var previewChars = 0L
            val sections = presentation.descendants("sldIdLst").firstOrNull()?.children("sldId").orEmpty()
                .mapIndexed { index, id ->
                    val relation = (0 until id.attributes.length).map { id.attributes.item(it) }
                        .firstOrNull { it.localName == "id" && !it.prefix.isNullOrEmpty() }?.nodeValue.orEmpty()
                    val path = rels[relation] ?: error("A slide is missing from the presentation.")
                    val slide = xml(zip, path)
                    val slideRels = relationships(zip, path)
                    val shapes = slide.descendants("spTree").firstOrNull()?.children().orEmpty()
                    val content = buildString {
                        append("<svg role=\"img\" aria-label=\"Slide ${index + 1}\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $width $height\" style=\"width:100%;background:white\">")
                        val background = slide.descendants("bgPr").firstOrNull()?.children("solidFill")?.firstOrNull()
                            ?.children("srgbClr")?.firstOrNull()?.attr("val")?.takeIf(::isColor) ?: "FFFFFF"
                        append("<rect width=\"100%\" height=\"100%\" fill=\"#$background\"/>")
                        var fallbackY = 0.0
                        for (shape in shapes) {
                            if (shape.tag() !in setOf("sp", "pic", "graphicFrame")) continue
                            val transform = shape.descendants("xfrm").firstOrNull()
                            val off = transform?.children("off")?.firstOrNull()
                            val extent = transform?.children("ext")?.firstOrNull()
                            val x = off?.attr("x")?.toDoubleOrNull() ?: (width * .05)
                            val y = off?.attr("y")?.toDoubleOrNull() ?: fallbackY
                            val w = extent?.attr("cx")?.toDoubleOrNull()?.takeIf { it > 0 } ?: (width * .9)
                            val h = extent?.attr("cy")?.toDoubleOrNull()?.takeIf { it > 0 } ?: (height * .25)
                            fallbackY = y + h
                            if (shape.tag() == "pic") {
                                slideRels[shape.descendants("blip").firstOrNull()?.attr("embed")]?.let { image ->
                                    append("<image x=\"$x\" y=\"$y\" width=\"$w\" height=\"$h\" href=\"").append(escapeHtml(resourceUrl(image))).append("\" preserveAspectRatio=\"xMidYMid meet\"/>")
                                }
                            } else {
                                val fill = shape.children("spPr").firstOrNull()?.children("solidFill")?.firstOrNull()
                                    ?.children("srgbClr")?.firstOrNull()?.attr("val")?.takeIf(::isColor)
                                fill?.let { append("<rect x=\"$x\" y=\"$y\" width=\"$w\" height=\"$h\" fill=\"#$it\"/>") }
                                // Convert DrawingML EMUs to a 960px HTML canvas.
                                val scale = 960.0 / width
                                append("<foreignObject x=\"$x\" y=\"$y\" width=\"$w\" height=\"$h\"><div xmlns=\"http://www.w3.org/1999/xhtml\" style=\"width:${w * scale}px;height:${h * scale}px;transform:scale(${1 / scale});transform-origin:top left;overflow:hidden;color:black;font-size:24px;line-height:1.15\">")
                                if (shape.tag() == "graphicFrame") {
                                    append("<table>")
                                    shape.descendants("tr").forEach { row ->
                                        append("<tr>")
                                        row.children("tc").forEach { cell -> append("<td>").append(slideText(cell)).append("</td>") }
                                        append("</tr>")
                                    }
                                    append("</table>")
                                } else append(slideText(shape))
                                append("</div></foreignObject>")
                            }
                        }
                        append("</svg>")
                    }
                    val title = slide.descendants("t").firstOrNull()?.textContent?.trim()?.take(80)
                        ?.takeIf(String::isNotBlank) ?: "Slide ${index + 1}"
                    val html = htmlPage(title, content, slide = true)
                    previewChars += html.length
                    require(previewChars <= MAX_PREVIEW_CHARS) { "This presentation is too large to preview." }
                    Section("${index + 1}. $title", "__luna_slide_$index.html", html)
                }
            require(sections.isNotEmpty()) { "The presentation has no slides." }
            return ReadingDocument(zip, sections,
                "Slide preview · Fonts, themes, diagrams and complex layouts may differ. Animations and embedded media are not played.")
        }

        private fun slideText(shape: Element): String = buildString {
            shape.descendants("p").forEach { paragraph ->
                val align = paragraph.children("pPr").firstOrNull()?.attr("algn")
                append("<p style=\"margin:0 0 .3em;text-align:${when (align) { "ctr" -> "center"; "r" -> "right"; else -> "left" }}\">")
                for (part in paragraph.children()) when (part.tag()) {
                    "r", "fld" -> {
                        val props = part.children("rPr").firstOrNull()
                        val css = buildString {
                            props?.attr("sz")?.toIntOrNull()?.takeIf { it in 100..40_000 }
                                ?.let { append("font-size:${it / 100.0 * 96 / 72}px;") }
                            if (props?.attr("b") in setOf("1", "true")) append("font-weight:bold;")
                            if (props?.attr("i") in setOf("1", "true")) append("font-style:italic;")
                            props?.children("solidFill")?.firstOrNull()?.children("srgbClr")?.firstOrNull()
                                ?.attr("val")?.takeIf(::isColor)?.let { append("color:#$it;") }
                        }
                        append("<span style=\"$css\">")
                        part.children("t").forEach { append(escapeHtml(it.textContent)) }
                        append("</span>")
                    }
                    "br" -> append("<br>")
                }
                append("</p>")
            }
        }

        private fun mainPart(zip: ZipFile, fallback: String): String =
            if (zip.getEntry("_rels/.rels") == null) fallback else {
                val root = xml(zip, "_rels/.rels")
                root.descendants("Relationship").firstOrNull { it.attr("Type").endsWith("/officeDocument") }
                    ?.takeUnless { it.attr("TargetMode").equals("External", true) }
                    ?.attr("Target")?.let { packagePath("", it) } ?: fallback
            }

        private fun relationships(zip: ZipFile, part: String): Map<String, String> {
            val directory = part.substringBeforeLast('/', "")
            val relPath = (if (directory.isEmpty()) "" else "$directory/") + "_rels/${part.substringAfterLast('/')}.rels"
            if (zip.getEntry(relPath) == null) return emptyMap()
            return xml(zip, relPath).descendants("Relationship")
                .filterNot { it.attr("TargetMode").equals("External", true) }
                .mapNotNull { rel -> packagePath(part, rel.attr("Target"))?.let { rel.attr("Id") to it } }.toMap()
        }

        private fun readPart(zip: ZipFile, path: String): ByteArray {
            val part = zip.getEntry(path) ?: error("A required document part is missing: $path")
            require(part.size in 0..MAX_PART_BYTES) { "A document part is too large to preview." }
            return zip.getInputStream(part).use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= MAX_PART_BYTES) { "A document part is too large to preview." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }

        private fun xml(zip: ZipFile, path: String): Element {
            require((zip.getEntry(path)?.size ?: 0) <= 8L * 1024 * 1024) { "This document is too complex to preview." }
            val bytes = readPart(zip, path)
            // Android's DOM parser has no depth or entity limits, so build the DOM from a bounded
            // pull parser. DOCDECL processing is off; a plain EPUB DOCTYPE is still accepted.
            val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                setInput(bytes.inputStream(), null)
            }
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
            val stack = mutableListOf<Element>()
            var count = 0
            while (true) {
                val event = parser.nextToken()
                if (event == XmlPullParser.END_DOCUMENT) break
                require(++count <= 200_000) { "This document is too complex to preview." }
                when (event) {
                    XmlPullParser.START_TAG -> {
                        require(stack.size < 100) { "This document is too complex to preview." }
                        count += parser.attributeCount
                        require(count <= 200_000) { "This document is too complex to preview." }
                        val name = parser.prefix?.takeIf(String::isNotEmpty)?.let { "$it:${parser.name}" } ?: parser.name
                        val element = document.createElementNS(parser.namespace?.takeIf(String::isNotEmpty), name)
                        for (index in 0 until parser.attributeCount) {
                            val local = parser.getAttributeName(index)
                            val attr = parser.getAttributePrefix(index)?.takeIf(String::isNotEmpty)?.let { "$it:$local" } ?: local
                            element.setAttributeNS(parser.getAttributeNamespace(index)?.takeIf(String::isNotEmpty),
                                attr, parser.getAttributeValue(index))
                        }
                        if (stack.isEmpty()) document.appendChild(element) else stack.last().appendChild(element)
                        stack += element
                    }
                    XmlPullParser.END_TAG -> stack.removeAt(stack.lastIndex)
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                        parser.text?.let { text -> stack.lastOrNull()?.appendChild(document.createTextNode(text)) }
                    }
                }
            }
            return document.documentElement ?: error("A document part is empty: $path")
        }
    }
}

/** Resolve an archive URI without permitting traversal above its root or remote resources. */
internal fun packagePath(base: String, reference: String): String? = runCatching {
    if (reference.contains('\\') || reference.contains('\u0000')) return null
    val uri = URI(reference.replace(" ", "%20"))
    if (uri.isAbsolute || uri.rawAuthority != null) return null
    val rawPath = uri.rawPath.orEmpty()
    if (rawPath.isEmpty()) return base.takeIf(String::isNotEmpty)
    val decoded = URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
    if (decoded.contains('\\') || decoded.contains('\u0000')) return null
    val joined = if (decoded.startsWith('/')) decoded.drop(1)
        else base.substringBeforeLast('/', "").let { if (it.isEmpty()) decoded else "$it/$decoded" }
    val components = mutableListOf<String>()
    for (part in joined.split('/')) when (part) {
        "", "." -> Unit
        ".." -> if (components.isEmpty()) return null else components.removeAt(components.lastIndex)
        else -> components += part
    }
    components.joinToString("/").takeIf(String::isNotEmpty)
}.getOrNull()

internal fun resourceUrl(path: String): String = "https://luna-document.invalid/" +
    URI(null, null, "/$path", null).rawPath.removePrefix("/")

private fun resourceMime(path: String): String = when (path.substringAfterLast('.').lowercase()) {
    "html", "htm", "xhtml" -> "text/html"
    "css" -> "text/css"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    "otf" -> "font/otf"
    "ttf" -> "font/ttf"
    else -> "application/octet-stream"
}

private fun Element.tag(): String = localName ?: tagName.substringAfter(':')
private fun Element.attr(name: String): String = (0 until attributes.length).asSequence()
    .map { attributes.item(it) }.firstOrNull { (it.localName ?: it.nodeName.substringAfter(':')) == name }?.nodeValue.orEmpty()
private fun Element.children(name: String? = null): List<Element> = (0 until childNodes.length).mapNotNull {
    (childNodes.item(it) as? Element)?.takeIf { element -> name == null || element.tag() == name }
}
private fun Element.descendants(name: String): List<Element> = buildList {
    if (tag() == name) add(this@descendants)
    val nodes = getElementsByTagNameNS("*", name)
    for (index in 0 until nodes.length) add(nodes.item(index) as Element)
}
private fun Element.enabled(name: String): Boolean = children(name).firstOrNull()?.let {
    it.attr("val") !in setOf("0", "false", "off", "none")
} ?: false
private fun isColor(value: String): Boolean = value.matches(Regex("[0-9A-Fa-f]{6}"))
private fun escapeHtml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
private fun htmlPage(title: String, body: String, slide: Boolean = false): String = """
    <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <title>${escapeHtml(title)}</title><style>
    body{margin:${if (slide) "0" else "20px"};color:#191919;background:#fff;font:18px/1.55 sans-serif;overflow-wrap:break-word}
    body>${if (slide) "svg" else "main"}{display:block;max-width:100%;margin:auto}main{max-width:55em}
    p{white-space:pre-wrap;min-height:1em}img{max-width:100%;height:auto}table{border-collapse:collapse;max-width:100%}
    td{border:1px solid #bbb;padding:.35em;vertical-align:top}td p{margin:.2em}h1,h2,h3{line-height:1.2}
    </style></head><body>${if (slide) body else "<main>$body</main>"}</body></html>
""".trimIndent()
