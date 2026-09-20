package com.lunaexplorer.core

import com.lunaexplorer.core.BinaryXmlFixture.ANDROID_NAMESPACE
import com.lunaexplorer.core.BinaryXmlFixture.document
import com.lunaexplorer.core.BinaryXmlFixture.endElement
import com.lunaexplorer.core.BinaryXmlFixture.endNamespace
import com.lunaexplorer.core.BinaryXmlFixture.manifest
import com.lunaexplorer.core.BinaryXmlFixture.startElement
import com.lunaexplorer.core.BinaryXmlFixture.startNamespace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class AndroidBinaryXmlTest {

    @Test fun `a manifest with a UTF-16 pool decodes to its elements and values`() {
        val bytes = manifest(utf8 = false)
        val root = AndroidBinaryXml.decode(bytes)

        assertEquals("manifest", root.name)
        assertEquals("com.example.app", root.attribute("package", namespace = null))
        assertEquals("42", root.attribute("versionCode"))
        assertEquals("true", root.attribute("debuggable"))

        val activity = root.all("activity").single()
        assertEquals(".Main", activity.attribute("name"))
    }

    @Test fun `a UTF-8 pool reads the byte length rather than the character count`() {
        // "héllo" is 5 characters but 6 bytes; a decoder that uses the first length truncates it.
        // Longer labels exercise two-byte length prefixes, independently for chars and bytes.
        for (label in listOf("héllo", "é".repeat(100), "😀".repeat(100))) {
            val root = AndroidBinaryXml.decode(manifest(utf8 = true, label = label))
            assertEquals(label, root.all("activity").single().attribute("label"))
        }
    }

    @Test fun `an attribute whose name was stripped is still identified by the resource map`() {
        val bytes = manifest(utf8 = false, blankVersionCodeName = true)
        val root = AndroidBinaryXml.decode(bytes)
        val attribute = root.attributes.single { it.value == "42" }
        assertEquals("The resource id is the only name left", "attr0x101021b", attribute.name)
    }

    @Test fun `an unresolvable reference is reported as one rather than as a number`() {
        val root = AndroidBinaryXml.decode(manifest(utf8 = false))
        val icon = root.all("activity").single().attributes.single { it.name == "icon" }
        assertEquals("@0x7f0f0001", icon.value)
    }

    @Test fun `text output is indented and namespaced the way a manifest is written`() {
        val text = AndroidBinaryXml.toText(AndroidBinaryXml.decode(manifest(utf8 = false)))
        assertTrue(text.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"))
        assertTrue(text.contains("xmlns:android=\"http://schemas.android.com/apk/res/android\""))
        assertTrue(text.contains("android:versionCode=\"42\""))
        assertTrue("The package attribute carries no namespace", text.contains("\n    package=\"com.example.app\""))
        assertTrue("Children are indented under their parent", text.contains("\n    <activity"))
    }

    @Test fun `something that is not compiled XML is refused by name`() {
        val error = assertThrows(StorageException::class.java) {
            AndroidBinaryXml.decode("<?xml version=\"1.0\"?><manifest/>".toByteArray())
        }
        assertEquals(StorageError.UNSUPPORTED, error.reason)
        assertTrue(error.message!!.contains("not compiled Android XML"))
    }

    @Test fun `a truncated file does not read past its end`() {
        val whole = manifest(utf8 = false)
        // Any prefix must either decode or throw StorageException; anything else fails the test.
        for (length in 8 until whole.size step 7) {
            try {
                AndroidBinaryXml.decode(whole.copyOf(length))
            } catch (expected: StorageException) {
            }
        }
    }

    @Test fun `a truncated UTF-8 pool does not read past its end`() {
        val whole = manifest(utf8 = true)
        for (length in 8 until whole.size) {
            try {
                AndroidBinaryXml.decode(whole.copyOf(length))
            } catch (expected: StorageException) {
            }
        }
    }

    @Test fun `a negative pool size does not read before the start`() {
        val bytes = manifest(utf8 = false)
        bytes.int(POOL_SIZE, -100)
        val error = assertThrows(StorageException::class.java) { AndroidBinaryXml.decode(bytes) }
        assertEquals(StorageError.UNSUPPORTED, error.reason)
    }

    @Test fun `a pool size that ends at the Int limit does not read past the end`() {
        for (size in listOf(Int.MAX_VALUE - POOL, Int.MAX_VALUE - POOL - 4)) {
            val bytes = manifest(utf8 = false)
            bytes.int(POOL_SIZE, size)
            val error = assertThrows(StorageException::class.java) { AndroidBinaryXml.decode(bytes) }
            assertEquals(StorageError.UNSUPPORTED, error.reason)
        }
    }

    @Test(timeout = 10_000) fun `a string count beyond the file is bounded`() {
        val bytes = manifest(utf8 = false)
        bytes.int(POOL_COUNT, Int.MAX_VALUE)
        assertEquals("manifest", AndroidBinaryXml.decode(bytes).name)
    }

    @Test(timeout = 10_000) fun `a chunk size at the Int limit is bounded`() {
        val bytes = manifest(utf8 = false)
        val resourceMap = POOL + bytes.int(POOL_SIZE)
        bytes.int(resourceMap + 4, Int.MAX_VALUE)
        val error = assertThrows(StorageException::class.java) { AndroidBinaryXml.decode(bytes) }
        assertEquals(StorageError.UNSUPPORTED, error.reason)
    }

    @Test fun `pool entries that overlap cannot amplify past the file size`() {
        val copies = 600
        val strings = listOf("a", "n", "x".repeat(30_000)) + List(copies - 1) { "" }
        val attributes = List(copies) { string(name = 1, value = 2 + it) }
        val bytes = document(strings, false, IntArray(0), startElement(0, attributes), endElement(0))
        val long = bytes.int(POOL_OFFSETS + 2 * 4)
        for (index in 3 until strings.size) bytes.int(POOL_OFFSETS + index * 4, long)

        val root = AndroidBinaryXml.decode(bytes)
        assertEquals(30_000, root.attributes.first().value.length)
        assertTrue(root.attributes.sumOf { it.value.length } <= bytes.size)
    }

    @Test fun `attributes that overlap cannot repeat past their chunk`() {
        val element = startElement(0, listOf(string(name = 1, value = 2)))
        element.short(ELEMENT_ATTRIBUTE_SIZE, 0)
        element.short(ELEMENT_ATTRIBUTE_SIZE + 2, 0xFFFF)
        val root = AndroidBinaryXml.decode(document(listOf("a", "n", "v"), false, IntArray(0), element, endElement(0)))
        assertEquals(1, root.attributes.size)
    }

    @Test fun `nesting deeper than any real file is refused`() {
        val depth = 5_000
        val body = Array(depth) { startElement(0) } + Array(depth) { endElement(0) }
        val error = assertThrows(StorageException::class.java) {
            AndroidBinaryXml.decode(document(listOf("a"), false, IntArray(0), *body))
        }
        assertEquals(StorageError.UNSUPPORTED, error.reason)
    }

    @Test fun `attributes sharing one long string cannot amplify the text without limit`() {
        val strings = listOf("a", "n", "x".repeat(30_000))
        val element = startElement(0, List(600) { string(name = 1, value = 2) })
        val root = AndroidBinaryXml.decode(document(strings, false, IntArray(0), element, endElement(0)))
        val error = assertThrows(StorageException::class.java) { AndroidBinaryXml.toText(root) }
        assertEquals(StorageError.UNSUPPORTED, error.reason)
    }

    @Test fun `compiled XML is told from text`() {
        assertTrue(AndroidBinaryXml.looksCompiled(manifest(utf8 = false)))
        assertTrue(AndroidBinaryXml.looksCompiled(manifest(utf8 = true)))
        assertFalse(AndroidBinaryXml.looksCompiled(manifest(utf8 = false).copyOf(11)))
        val texts = listOf("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest />", "<manifest", "\uFEFF<a/>", "")
        for (text in texts) {
            assertFalse(text, AndroidBinaryXml.looksCompiled(text.toByteArray()))
            assertFalse(text, AndroidBinaryXml.looksCompiled(text.toByteArray(Charsets.UTF_16LE)))
        }
    }

    @Test fun `a second namespace keeps its prefix on the element that declared it`() {
        val strings = listOf(
            ANDROID_NAMESPACE, "android",                           // 0, 1
            "http://schemas.android.com/apk/res-auto", "app",       // 2, 3
            "title", "showAsAction", "menu", "item",                // 4, 5, 6, 7
            "Go", "always",                                         // 8, 9
        )
        val bytes = document(
            strings, false, IntArray(0),
            startNamespace(prefixIndex = 1, uriIndex = 0),
            startElement(6),
            startNamespace(prefixIndex = 3, uriIndex = 2),
            startElement(7, listOf(string(name = 4, value = 8, ns = 0), string(name = 5, value = 9, ns = 2))),
            endElement(7),
            endNamespace(prefixIndex = 3, uriIndex = 2),
            startElement(7, listOf(string(name = 5, value = 9, ns = 2))),
            endElement(7),
            endElement(6),
            endNamespace(prefixIndex = 1, uriIndex = 0),
        )
        assertEquals(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <menu
                xmlns:android="http://schemas.android.com/apk/res/android">
                <item
                    xmlns:app="http://schemas.android.com/apk/res-auto"
                    android:title="Go"
                    app:showAsAction="always" />
                <item
                    showAsAction="always" />
            </menu>

            """.trimIndent(),
            AndroidBinaryXml.toText(AndroidBinaryXml.decode(bytes)),
        )
    }

    @Test fun `an element declares a prefix once`() {
        val other = "http://example.com/other"
        val strings = listOf(ANDROID_NAMESPACE, "android", other, "manifest", "versionCode", "1")
        val element = startElement(3, listOf(string(name = 4, value = 5, ns = 0)))

        val twice = document(
            strings, false, IntArray(0),
            startNamespace(prefixIndex = 1, uriIndex = 0), startNamespace(prefixIndex = 1, uriIndex = 0),
            element, endElement(3),
            endNamespace(prefixIndex = 1, uriIndex = 0), endNamespace(prefixIndex = 1, uriIndex = 0),
        )
        assertEquals(
            listOf(AndroidBinaryXml.Namespace("android", ANDROID_NAMESPACE)),
            AndroidBinaryXml.decode(twice).xmlns,
        )

        // The android attribute has no declaration in scope, so its prefix is assumed, and already taken.
        val taken = document(
            strings, false, IntArray(0),
            startNamespace(prefixIndex = 1, uriIndex = 2), element, endElement(3),
            endNamespace(prefixIndex = 1, uriIndex = 2),
        )
        assertEquals(listOf(AndroidBinaryXml.Namespace("android", other)), AndroidBinaryXml.decode(taken).xmlns)
    }

    private fun string(name: Int, value: Int, ns: Int = -1): ByteArray =
        BinaryXmlFixture.attribute(ns, name, raw = value, type = 0x03, data = value)

    private fun AndroidBinaryXml.Element.attribute(name: String, namespace: String? = ANDROID_NAMESPACE): String? =
        attributes.firstOrNull { it.name == name && it.namespace == namespace }?.value

    private fun AndroidBinaryXml.Element.all(name: String): List<AndroidBinaryXml.Element> =
        children.flatMap { child -> listOfNotNull(child.takeIf { it.name == name }) + child.all(name) }

    private fun ByteArray.int(at: Int): Int = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).getInt(at)

    private fun ByteArray.int(at: Int, value: Int) {
        ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(at, value)
    }

    private fun ByteArray.short(at: Int, value: Int) {
        ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putShort(at, value.toShort())
    }

    private companion object {
        const val POOL = 8
        const val POOL_SIZE = POOL + 4
        const val POOL_COUNT = POOL + 8
        const val POOL_OFFSETS = POOL + 28
        // In a start-element chunk: after its header, line and comment, namespace and name, and attributeStart.
        const val ELEMENT_ATTRIBUTE_SIZE = 8 + 8 + 8 + 2
    }
}
