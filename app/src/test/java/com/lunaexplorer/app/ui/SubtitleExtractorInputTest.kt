package com.lunaexplorer.app.ui

import androidx.media3.common.C
import java.io.EOFException
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SubtitleExtractorInputTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `peeking leaves reads unchanged and skipping resets the peek position`() {
        val file = temporary.newFile().apply { writeText("0123456789") }
        Files.newByteChannel(file.toPath()).use { channel ->
            val input = SubtitleExtractorInput(channel) {}
            val bytes = ByteArray(3)
            input.peekFully(bytes, 0, bytes.size)
            assertArrayEquals("012".toByteArray(), bytes)
            input.advancePeekPosition(2)
            assertEquals(5L, input.peekPosition)
            assertEquals(0L, input.position)
            input.readFully(bytes, 0, 2)
            assertEquals(2L, input.peekPosition)
            assertEquals(3, input.skip(3))
            input.readFully(bytes, 0, bytes.size)
            assertArrayEquals("567".toByteArray(), bytes)
            input.seek(1)
            input.peekFully(bytes, 0, bytes.size)
            assertArrayEquals("123".toByteArray(), bytes)
            input.readFully(bytes, 0, bytes.size)
            assertArrayEquals("123".toByteArray(), bytes)
        }
    }

    @Test fun `full reads and skips distinguish exact EOF from a truncated block`() {
        val file = temporary.newFile().apply { writeText("0123456789") }
        Files.newByteChannel(file.toPath()).use { channel ->
            val input = SubtitleExtractorInput(channel) {}
            input.skipFully(10)
            assertEquals(0, input.skip(0))
            assertEquals(C.RESULT_END_OF_INPUT, input.read(ByteArray(1), 0, 1))
            assertFalse(input.skipFully(1, true))
            assertFalse(input.peekFully(ByteArray(1), 0, 1, true))
            input.seek(9)
            assertThrows(EOFException::class.java) { input.readFully(ByteArray(2), 0, 2, true) }
            input.seek(9)
            assertThrows(EOFException::class.java) { input.skipFully(2, true) }
        }
    }

    @Test fun `a channel ending before its reported length fails instead of spinning`() {
        val file = temporary.newFile().apply { writeText("0123456789") }
        Files.newByteChannel(file.toPath()).use { channel ->
            val truncated = object : SeekableByteChannel by channel {
                override fun size() = channel.size() + 1
            }
            val input = SubtitleExtractorInput(truncated) {}
            assertThrows(EOFException::class.java) { input.readFully(ByteArray(11), 0, 11) }
        }
    }
}
