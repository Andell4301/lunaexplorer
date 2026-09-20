package com.lunaexplorer.core

object AndroidBinaryXml {
    private const val CHUNK_XML = 0x0003
    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_RESOURCE_MAP = 0x0180
    private const val CHUNK_START_NAMESPACE = 0x0100
    private const val CHUNK_END_NAMESPACE = 0x0101
    private const val CHUNK_START_ELEMENT = 0x0102
    private const val CHUNK_END_ELEMENT = 0x0103
    private const val CHUNK_CDATA = 0x0104

    private const val NONE = -1
    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    private const val MAX_DEPTH = 256
    private const val MAX_TEXT = 16 * 1024 * 1024

    data class Namespace(val prefix: String, val uri: String)

    private val ASSUMED_ANDROID = Namespace("android", ANDROID_NAMESPACE)

    data class Attribute(val namespace: String?, val prefix: String?, val name: String, val value: String) {
        val qualified: String
            get() = if (prefix == null) name else "$prefix:$name"
    }

    data class Element(
        val name: String,
        val xmlns: List<Namespace>,
        val attributes: List<Attribute>,
        val children: List<Element>,
    )

    fun looksCompiled(bytes: ByteArray): Boolean {
        val reader = Reader(bytes)
        return bytes.size >= 12 && reader.u16(0) == CHUNK_XML && reader.u16(2) == 8 &&
            reader.u16(8) == CHUNK_STRING_POOL
    }

    /** Throws [StorageException] with [StorageError.UNSUPPORTED] if [bytes] is not compiled Android XML. */
    fun decode(bytes: ByteArray): Element {
        val reader = Reader(bytes)
        val type = reader.u16(0)
        if (type != CHUNK_XML) {
            throw StorageException(
                StorageError.UNSUPPORTED,
                "This is not compiled Android XML: it starts with chunk type 0x${type.toString(16)}.",
            )
        }
        val headerSize = reader.u16(2)
        val pool = readStringPool(reader, headerSize)
        var cursor = pool.end
        var resourceMap = IntArray(0)

        val stack = ArrayDeque<Builder>()
        var root: Element? = null
        val scope = ArrayList<Namespace>()
        val pending = ArrayList<Namespace>()
        var androidAssumed = false

        fun prefixOf(uri: String): String? =
            scope.lastOrNull { it.uri == uri }?.prefix
                ?: if (uri == ANDROID_NAMESPACE) ASSUMED_ANDROID.prefix.also { androidAssumed = true } else null

        while (cursor >= 0 && cursor <= bytes.size - 8) {
            val chunkType = reader.u16(cursor)
            val chunkHeader = reader.u16(cursor + 2)
            val chunkSize = reader.u32(cursor + 4)
            // Stop on a chunk size that would not advance the cursor or runs past the end. cursor + chunkSize can overflow.
            if (chunkSize <= 0 || chunkSize > bytes.size - cursor) break

            when (chunkType) {
                CHUNK_RESOURCE_MAP -> {
                    val count = ((chunkSize - chunkHeader) / 4).coerceAtLeast(0)
                    resourceMap = IntArray(count) { reader.u32(cursor + chunkHeader + it * 4) }
                }
                CHUNK_START_NAMESPACE -> {
                    val namespace = readNamespace(reader, pool, cursor + chunkHeader)
                    // Without a prefix it cannot qualify an attribute.
                    if (namespace.prefix.isNotEmpty() && namespace.uri.isNotEmpty()) {
                        if (scope.size >= MAX_DEPTH) throw tooDeep()
                        scope += namespace
                        if (namespace !in pending) pending += namespace
                    }
                }
                CHUNK_END_NAMESPACE -> {
                    val namespace = readNamespace(reader, pool, cursor + chunkHeader)
                    val index = scope.lastIndexOf(namespace)
                    if (index >= 0) scope.removeAt(index)
                    pending.remove(namespace)
                }
                CHUNK_START_ELEMENT -> {
                    if (stack.size >= MAX_DEPTH) throw tooDeep()
                    val body = cursor + chunkHeader
                    val name = pool.at(reader.u32(body + 4))
                    val attributeStart = reader.u16(body + 8)
                    // A smaller size would make attributes overlap, and one chunk repeat them 65535 times.
                    val attributeSize = reader.u16(body + 10).coerceAtLeast(20)
                    val attributeCount = reader.u16(body + 12)
                    val attributes = ArrayList<Attribute>()
                    for (index in 0 until attributeCount) {
                        val at = body + attributeStart + index * attributeSize
                        if (at + 20 > cursor + chunkSize) break
                        attributes += readAttribute(reader, pool, resourceMap, at, ::prefixOf)
                    }
                    stack.addLast(Builder(name, pending.toList(), attributes))
                    pending.clear()
                }
                CHUNK_END_ELEMENT -> {
                    val finished = stack.removeLastOrNull() ?: break
                    val parent = stack.lastOrNull()
                    // The root declares the android namespace when nothing else did, so the text stays well formed.
                    if (parent != null) parent.children += finished.build()
                    else root = finished.build(if (androidAssumed) listOf(ASSUMED_ANDROID) else emptyList())
                }
                CHUNK_CDATA -> Unit
            }
            cursor += chunkSize
        }

        return root ?: throw StorageException(
            StorageError.UNSUPPORTED,
            "This compiled XML has no root element.",
        )
    }

    /** Throws [StorageException] with [StorageError.UNSUPPORTED] once the text passes 16M characters. */
    fun toText(root: Element): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        write(root, 0)
    }

    private fun StringBuilder.write(element: Element, depth: Int) {
        if (depth >= MAX_DEPTH) throw tooDeep()
        val pad = "    ".repeat(depth)
        append(pad).append('<').append(element.name).bounded()
        element.xmlns.forEach {
            append("\n").append(pad).append("    xmlns:").append(it.prefix).append("=\"").bounded()
            escaped(it.uri).append('"')
        }
        element.attributes.forEach {
            append("\n").append(pad).append("    ").append(it.qualified).append("=\"").bounded()
            escaped(it.value).append('"')
        }
        if (element.children.isEmpty()) {
            append(" />\n")
        } else {
            append(">\n")
            element.children.forEach { write(it, depth + 1) }
            append(pad).append("</").append(element.name).append(">\n").bounded()
        }
    }

    private fun StringBuilder.escaped(value: String): StringBuilder {
        value.forEach {
            when (it) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(it)
            }
            bounded()
        }
        return this
    }

    private fun StringBuilder.bounded(): StringBuilder {
        if (length > MAX_TEXT) {
            throw StorageException(StorageError.UNSUPPORTED, "This compiled XML is too large to show as text.")
        }
        return this
    }

    private fun tooDeep() = StorageException(StorageError.UNSUPPORTED, "This compiled XML is nested too deeply.")

    private class Builder(val name: String, val xmlns: List<Namespace>, val attributes: List<Attribute>) {
        val children = mutableListOf<Element>()
        fun build(assumed: List<Namespace> = emptyList()) =
            Element(name, assumed.filter { it.prefix !in xmlns.map(Namespace::prefix) } + xmlns, attributes, children)
    }

    private fun readNamespace(reader: Reader, pool: StringPool, body: Int) =
        Namespace(pool.at(reader.u32(body)), pool.at(reader.u32(body + 4)))

    private fun readAttribute(
        reader: Reader,
        pool: StringPool,
        resourceMap: IntArray,
        at: Int,
        prefixOf: (String) -> String?,
    ): Attribute {
        val namespaceIndex = reader.u32(at)
        val nameIndex = reader.u32(at + 4)
        val rawValue = reader.u32(at + 8)
        val valueType = reader.u8(at + 15)
        val data = reader.u32(at + 16)

        val resourceId = if (nameIndex in resourceMap.indices) resourceMap[nameIndex] else 0
        // A packer can blank the attribute name strings; the resource map still identifies them.
        val name = pool.at(nameIndex).ifEmpty {
            if (resourceId != 0) "attr0x${resourceId.toUInt().toString(16)}" else "?"
        }
        val namespace = if (namespaceIndex == NONE) null else pool.at(namespaceIndex)

        val value = when (valueType) {
            0x00 -> ""
            0x01 -> "@0x${data.toUInt().toString(16).padStart(8, '0')}"
            0x02 -> "?0x${data.toUInt().toString(16).padStart(8, '0')}"
            0x03 -> if (rawValue != NONE) pool.at(rawValue) else pool.at(data)
            0x04 -> Float.fromBits(data).toString()
            0x05 -> dimension(data)
            0x06 -> fraction(data)
            0x10 -> data.toString()
            0x11 -> "0x${data.toUInt().toString(16)}"
            0x12 -> if (data != 0) "true" else "false"
            in 0x1C..0x1F -> "#${data.toUInt().toString(16).padStart(8, '0')}"
            else -> data.toString()
        }
        return Attribute(namespace, namespace?.let(prefixOf), name, value)
    }

    private val DIMENSION_UNITS = arrayOf("px", "dip", "sp", "pt", "in", "mm")

    private fun dimension(data: Int): String {
        val unit = data and 0xFF
        return complex(data) + (DIMENSION_UNITS.getOrNull(unit and 0x0F) ?: "")
    }

    private fun fraction(data: Int): String =
        complex(data) + if (data and 0x0F == 1) "%p" else "%"

    /** A complex value packs a mantissa and a radix into the top 24 bits. */
    private fun complex(data: Int): String {
        val mantissa = (data shr 8) and 0xFFFFFF
        val radix = (data shr 4) and 0x03
        val shift = when (radix) {
            0 -> 0
            1 -> 7
            2 -> 15
            else -> 23
        }
        val value = (mantissa.toFloat() * Math.pow(2.0, -shift.toDouble()).toFloat()) / (1 shl 8)
        return if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
    }

    private class StringPool(val strings: List<String>, val end: Int) {
        fun at(index: Int): String = strings.getOrElse(index) { "" }
    }

    private fun readStringPool(reader: Reader, offset: Int): StringPool {
        val type = reader.u16(offset)
        if (type != CHUNK_STRING_POOL) {
            throw StorageException(
                StorageError.UNSUPPORTED,
                "Compiled XML must begin with a string pool; found chunk 0x${type.toString(16)}.",
            )
        }
        val headerSize = reader.u16(offset + 2)
        val size = reader.u32(offset + 4)
        val count = reader.u32(offset + 8).coerceIn(0, reader.left(offset + headerSize) / 4)
        val flags = reader.u32(offset + 16)
        val stringsStart = reader.u32(offset + 20)
        val utf8 = (flags and 0x100) != 0

        val strings = ArrayList<String>(count.coerceAtMost(1 shl 16))
        // Entries can point at the same bytes; a real pool never decodes more than the file holds.
        var budget = reader.size
        fun affordable(length: Int) = (length <= budget).also { if (it) budget -= length }
        for (index in 0 until count) {
            val entryOffset = reader.u32(offset + headerSize + index * 4)
            var at = offset + stringsStart + entryOffset
            if (at < 0 || at >= reader.size) { strings += ""; continue }
            strings += if (utf8) {
                // A UTF-8 entry stores its character count, then its byte count. Read with the byte count.
                val characters = reader.u8(at); at++
                if (characters and 0x80 != 0) at++
                var bytes = reader.u8(at); at++
                if (bytes and 0x80 != 0) { bytes = ((bytes and 0x7F) shl 8) or reader.u8(at); at++ }
                bytes = bytes.coerceAtMost(reader.left(at))
                if (affordable(bytes)) reader.utf8(at, bytes) else ""
            } else {
                var units = reader.u16(at); at += 2
                if (units and 0x8000 != 0) { units = ((units and 0x7FFF) shl 16) or reader.u16(at); at += 2 }
                units = units.coerceAtMost(reader.left(at) / 2)
                if (affordable(units)) reader.utf16(at, units) else ""
            }
        }
        return StringPool(strings, offset + size)
    }

    private class Reader(private val bytes: ByteArray) {
        val size get() = bytes.size

        fun left(at: Int): Int = (bytes.size - at).coerceAtLeast(0)

        fun u8(at: Int): Int = if (at < 0 || at >= bytes.size) 0 else bytes[at].toInt() and 0xFF

        fun u16(at: Int): Int =
            if (at < 0 || at > bytes.size - 2) 0
            else (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

        fun u32(at: Int): Int =
            if (at < 0 || at > bytes.size - 4) 0
            else (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)

        fun utf8(at: Int, length: Int): String {
            val end = (at + length).coerceAtMost(bytes.size)
            if (at >= end) return ""
            return String(bytes, at, end - at, Charsets.UTF_8)
        }

        fun utf16(at: Int, units: Int): String {
            val end = (at + units * 2).coerceAtMost(bytes.size)
            if (at >= end) return ""
            return String(bytes, at, end - at, Charsets.UTF_16LE)
        }
    }
}
