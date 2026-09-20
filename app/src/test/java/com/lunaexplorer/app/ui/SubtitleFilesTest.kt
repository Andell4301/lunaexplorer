package com.lunaexplorer.app.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InputStream

class SubtitleFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `styles encodings and Unicode names survive without rewriting subtitle bytes`() {
        val bytes = ("\uFEFF[Script Info]\r\nTitle: 字幕\r\n[V4+ Styles]\r\n" +
            "Style: Default,Arial,24,&H00FFFFFF\r\n" +
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\pos(40,80)\\b1}こんにちは").toByteArray(Charsets.UTF_16LE)
        val directory = temporary.newFolder()

        val subtitle = bytes.inputStream().use { copySubtitle(it, "字幕\u200b.ASS", directory) }

        assertArrayEquals(bytes, subtitle.readBytes())
        assertEquals("ass", subtitle.extension)
        assertTrue(subtitle.name.startsWith("subtitle-"))
    }

    @Test fun `provider display names cannot put cached subtitles outside the cache`() {
        val directory = temporary.newFolder()
        val subtitle = "caption".byteInputStream().use { copySubtitle(it, "../../outside.srt", directory) }

        assertEquals(directory.canonicalFile, subtitle.canonicalFile.parentFile)
        assertFalse(temporary.root.resolve("outside.srt").exists())
    }

    @Test fun `unknown input sizes cannot bypass subtitle size limit or leave partial files`() {
        val directory = temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            ByteArray(33).inputStream().use { copySubtitle(it, "test.vtt", directory, limit = 32) }
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `interrupted reads remove partial subtitle files`() {
        val directory = temporary.newFolder()
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("connection closed")
        }
        assertThrows(IOException::class.java) {
            failing.use { copySubtitle(it, "test.srt", directory) }
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `exact limit is accepted and unsupported picker files have an actionable error`() {
        val directory = temporary.newFolder()
        val subtitle = ByteArray(32).inputStream().use { copySubtitle(it, "test.srt", directory, limit = 32) }
        assertEquals(32L, subtitle.length())
        val error = assertThrows(IllegalArgumentException::class.java) {
            "not a subtitle".byteInputStream().use { copySubtitle(it, "book.pdf", directory) }
        }
        assertTrue(error.message!!.contains("subtitle file"))
        assertEquals(1, directory.listFiles()!!.size)
    }
}
