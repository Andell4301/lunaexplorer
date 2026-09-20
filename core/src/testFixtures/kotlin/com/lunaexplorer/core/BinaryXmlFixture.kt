package com.lunaexplorer.core

import java.io.ByteArrayOutputStream

/** Builds compiled Android XML chunk by chunk. String arguments are indices into the document's pool. */
object BinaryXmlFixture {
    const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

    fun manifest(
        utf8: Boolean,
        label: String = "Main",
        blankVersionCodeName: Boolean = false,
    ): ByteArray {
        val strings = listOf(
            ANDROID_NAMESPACE,                                      // 0
            if (blankVersionCodeName) "" else "versionCode",        // 1
            "debuggable",                                           // 2
            "name",                                                 // 3
            "label",                                                // 4
            "icon",                                                 // 5
            "manifest",                                             // 6
            "activity",                                             // 7
            "package",                                              // 8
            "com.example.app",                                      // 9
            ".Main",                                                // 10
            label,                                                  // 11
        )
        // Parallel to the pool: the android.R.attr id of each name string, 0 where there is none.
        val resourceMap = intArrayOf(0, 0x0101021B, 0x0101000F, 0x01010003, 0x01010001, 0x01010002)
        return document(
            strings, utf8, resourceMap,
            startNamespace(prefixIndex = -1, uriIndex = 0),
            startElement(
                nameIndex = 6,
                attributes = listOf(
                    attribute(ns = -1, name = 8, raw = 9, type = 0x03, data = 9),      // package
                    attribute(ns = 0, name = 1, raw = -1, type = 0x10, data = 42),     // versionCode
                    attribute(ns = 0, name = 2, raw = -1, type = 0x12, data = 1),      // debuggable
                ),
            ),
            startElement(
                nameIndex = 7,
                attributes = listOf(
                    attribute(ns = 0, name = 3, raw = 10, type = 0x03, data = 10),     // name
                    attribute(ns = 0, name = 4, raw = 11, type = 0x03, data = 11),     // label
                    attribute(ns = 0, name = 5, raw = -1, type = 0x01, data = 0x7F0F0001), // icon
                ),
            ),
            endElement(nameIndex = 7),
            endElement(nameIndex = 6),
            endNamespace(prefixIndex = -1, uriIndex = 0),
        )
    }

    /** The string pool starts at byte 8: its 28-byte header, then one offset per string. */
    fun document(strings: List<String>, utf8: Boolean, resourceMap: IntArray, vararg body: ByteArray): ByteArray {
        val pool = stringPool(strings, utf8)
        val map = if (resourceMap.isEmpty()) ByteArray(0)
            else chunk(0x0180, 8) { out -> resourceMap.forEach { out.int(it) } }
        val out = ByteArrayOutputStream()
        out.short(0x0003); out.short(8); out.int(8 + pool.size + map.size + body.sumOf { it.size })
        out.write(pool); out.write(map); body.forEach { out.write(it) }
        return out.toByteArray()
    }

    fun startElement(nameIndex: Int, attributes: List<ByteArray> = emptyList()): ByteArray =
        chunk(0x0102, 16) { out ->
            out.int(-1)            // namespace
            out.int(nameIndex)
            out.short(20)          // attributeStart
            out.short(20)          // attributeSize
            out.short(attributes.size)
            out.short(0); out.short(0); out.short(0)
            attributes.forEach { out.write(it) }
        }

    fun endElement(nameIndex: Int): ByteArray =
        chunk(0x0103, 16) { out -> out.int(-1); out.int(nameIndex) }

    fun startNamespace(prefixIndex: Int, uriIndex: Int): ByteArray =
        chunk(0x0100, 16) { out -> out.int(prefixIndex); out.int(uriIndex) }

    fun endNamespace(prefixIndex: Int, uriIndex: Int): ByteArray =
        chunk(0x0101, 16) { out -> out.int(prefixIndex); out.int(uriIndex) }

    fun attribute(ns: Int, name: Int, raw: Int, type: Int, data: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.int(ns); out.int(name); out.int(raw)
        out.short(8); out.write(0); out.write(type); out.int(data)
        return out.toByteArray()
    }

    private fun stringPool(strings: List<String>, utf8: Boolean): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        strings.forEachIndexed { index, value ->
            offsets[index] = data.size()
            if (utf8) {
                val encoded = value.toByteArray(Charsets.UTF_8)
                data.length8(value.length)
                data.length8(encoded.size)
                data.write(encoded)
                data.write(0)
            } else {
                require(value.length < 0x8000)
                data.short(value.length)
                data.write(value.toByteArray(Charsets.UTF_16LE))
                data.short(0)
            }
        }
        var payload = data.toByteArray()
        if (payload.size % 4 != 0) payload += ByteArray(4 - payload.size % 4)

        val headerSize = 28
        val stringsStart = headerSize + strings.size * 4
        val size = stringsStart + payload.size
        val out = ByteArrayOutputStream()
        out.short(0x0001); out.short(headerSize); out.int(size)
        out.int(strings.size); out.int(0)
        out.int(if (utf8) 0x100 else 0)
        out.int(stringsStart); out.int(0)
        offsets.forEach { out.int(it) }
        out.write(payload)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.length8(value: Int) {
        require(value in 0..0x7FFF)
        if (value >= 0x80) write((value shr 8) or 0x80)
        write(value and 0xFF)
    }

    private fun chunk(type: Int, headerSize: Int, body: (ByteArrayOutputStream) -> Unit): ByteArray {
        val inner = ByteArrayOutputStream()
        // Node chunks carry a line number and a comment index between the header and the body.
        if (headerSize == 16) { inner.int(1); inner.int(-1) }
        body(inner)
        val payload = inner.toByteArray()
        val out = ByteArrayOutputStream()
        out.short(type); out.short(headerSize); out.int(8 + payload.size)
        out.write(payload)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.short(value: Int) {
        write(value and 0xFF); write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.int(value: Int) {
        write(value and 0xFF); write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF); write((value ushr 24) and 0xFF)
    }
}
