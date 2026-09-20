package com.lunaexplorer.core

import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TagEditorTest {

    @Test fun `mp3 is offered and other formats are not`() {
        assertTrue(TagEditor.supports("song.mp3"))
        assertTrue(TagEditor.supports("SONG.MP3"))
        assertFalse(TagEditor.supports("song.flac"))
        assertFalse(TagEditor.supports("photo.jpg"))
    }

    @Test fun `tags round trip and leave the audio alone`() {
        val file = silentMp3()
        try {
            val before = frameCount(file)

            TagEditor.write(file, mapOf(
                "title" to "The Title",
                "artist" to "The Artist",
                "album" to "The Album",
                "year" to "2026",
            )).getOrThrow()

            val read = TagEditor.read(file).associate { it.key to it.value }
            assertEquals("The Title", read["title"])
            assertEquals("The Artist", read["artist"])
            assertEquals("The Album", read["album"])
            assertEquals("2026", read["year"])

            // Count frame sync words: a rewritten tag can change mp3agic's duration estimate.
            assertEquals("The audio must survive a tag write", before, frameCount(file))
        } finally {
            file.delete()
        }
    }

    @Test fun `a field Luna does not edit is preserved`() {
        val file = silentMp3()
        try {
            val mp3 = Mp3File(file)
            val tag = ID3v24Tag().apply { title = "Original"; copyright = "Someone, 1999" }
            mp3.id3v2Tag = tag
            val staged = File(file.parentFile, "staged.mp3")
            mp3.save(staged.absolutePath)
            staged.renameTo(file)

            TagEditor.write(file, mapOf("title" to "Changed")).getOrThrow()

            val after = Mp3File(file).id3v2Tag
            assertEquals("Changed", after.title)
            assertEquals("An untouched frame must survive", "Someone, 1999", after.copyright)
        } finally {
            file.delete()
        }
    }

    @Test fun `writing to something that is not an mp3 fails rather than corrupting it`() {
        val file = File.createTempFile("luna-not-audio", ".mp3")
        try {
            file.writeText("definitely not audio")
            val result = TagEditor.write(file, mapOf("title" to "x"))
            assertTrue(result.isFailure)
            assertEquals("definitely not audio", file.readText())
        } finally {
            file.delete()
        }
    }

    private fun frameCount(file: File): Int {
        val bytes = file.readBytes()
        var count = 0
        for (index in 0 until bytes.size - 1) {
            if (bytes[index] == 0xFF.toByte() && bytes[index + 1] == 0xFB.toByte()) count++
        }
        return count
    }

    private fun silentMp3(): File {
        val file = File.createTempFile("luna-tag", ".mp3")
        val frame = ByteArray(418)
        // 0xFF 0xFB = MPEG-1 Layer III, no CRC; 0x90 = 128 kbps at 44100 Hz; 0x00 = stereo.
        frame[0] = 0xFF.toByte(); frame[1] = 0xFB.toByte(); frame[2] = 0x90.toByte(); frame[3] = 0x00
        val out = file.outputStream()
        out.use { repeat(40) { out.write(frame) } }
        return file
    }
}
