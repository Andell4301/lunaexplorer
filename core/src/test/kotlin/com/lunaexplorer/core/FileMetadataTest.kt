package com.lunaexplorer.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

class FileMetadataTest {

    @Test fun `a png is reported in the sections the format defines`() {
        val report = FileMetadata.read(png(width = 7, height = 5).inputStream())
        assertNull(report.failure)
        assertFalse(report.isEmpty)

        val header = report.sections.single { it.name == "PNG-IHDR" }
        assertEquals("7", header.tags.single { it.name == "Image Width" }.value)
        assertEquals("5", header.tags.single { it.name == "Image Height" }.value)

        val type = report.sections.single { it.name == "File Type" }
        assertEquals("image/png", type.tags.single { it.name == "Detected MIME Type" }.value)
    }

    @Test fun `something that is not an image is reported rather than thrown`() {
        val report = FileMetadata.read("this is just text, not a picture".toByteArray().inputStream())
        assertNotNull("An unreadable file must say so", report.failure)
        assertTrue(report.isEmpty)
    }

    private fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))

        val header = ByteArrayOutputStream().apply {
            writeInt(width); writeInt(height)
            write(8)            // bit depth
            write(2)            // colour type: truecolour
            write(0); write(0); write(0)
        }.toByteArray()
        out.writeChunk("IHDR", header)

        // One filter byte plus three bytes per pixel, per row, deflated.
        val raw = ByteArray(height * (1 + width * 3))
        val deflater = Deflater()
        deflater.setInput(raw); deflater.finish()
        val compressed = ByteArray(raw.size + 64)
        val length = deflater.deflate(compressed)
        deflater.end()
        out.writeChunk("IDAT", compressed.copyOf(length))
        out.writeChunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write((value ushr 24) and 0xFF); write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF); write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeChunk(type: String, body: ByteArray) {
        writeInt(body.size)
        val typed = type.toByteArray(Charsets.US_ASCII)
        write(typed); write(body)
        val crc = CRC32().apply { update(typed); update(body) }
        writeInt(crc.value.toInt())
    }
}
