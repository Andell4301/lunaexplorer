package com.lunaexplorer.app.ui

import androidx.media3.common.C
import androidx.media3.extractor.ExtractorInput
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SubtitleExtractorInput(
    private val channel: SeekableByteChannel,
    private val active: () -> Unit,
) : ExtractorInput {
    private val size = channel.size()
    private var position = channel.position()
    private var peekPosition = position
    private val buffer = ByteArray(16 * 1024)
    private var bufferedAt = -1L
    private var buffered = 0

    fun seek(position: Long) {
        active()
        if (position < 0 || position > size) throw EOFException()
        this.position = position
        peekPosition = position
    }

    private fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        active()
        if (length == 0) return 0
        if (position == size) return C.RESULT_END_OF_INPUT
        if (position < bufferedAt || position - bufferedAt >= buffered) {
            channel.position(position)
            bufferedAt = position
            buffered = channel.read(ByteBuffer.wrap(this.buffer, 0, minOf(this.buffer.size.toLong(), size - position).toInt()))
            if (buffered == 0) throw IOException("The file stopped returning bytes")
            if (buffered == C.RESULT_END_OF_INPUT) throw EOFException()
        }
        val from = (position - bufferedAt).toInt()
        val count = minOf(length, buffered - from)
        this.buffer.copyInto(buffer, offset, from, from + count)
        return count
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = readAt(position, buffer, offset, length)
        if (count != C.RESULT_END_OF_INPUT) position += count
        resetPeekPosition()
        return count
    }

    override fun peek(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = readAt(peekPosition, buffer, offset, length)
        if (count != C.RESULT_END_OF_INPUT) peekPosition += count
        return count
    }

    private fun fully(length: Int, allowEndOfInput: Boolean, read: (Int, Int) -> Int): Boolean {
        var total = 0
        while (total < length) {
            val count = read(total, length - total)
            if (count == C.RESULT_END_OF_INPUT) {
                if (total == 0 && allowEndOfInput) return false
                throw EOFException()
            }
            total += count
        }
        return true
    }

    override fun readFully(buffer: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean) =
        fully(length, allowEndOfInput) { at, count -> read(buffer, offset + at, count) }
    override fun readFully(buffer: ByteArray, offset: Int, length: Int) { readFully(buffer, offset, length, false) }
    override fun peekFully(buffer: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean) =
        fully(length, allowEndOfInput) { at, count -> peek(buffer, offset + at, count) }
    override fun peekFully(buffer: ByteArray, offset: Int, length: Int) { peekFully(buffer, offset, length, false) }

    override fun skip(length: Int): Int {
        active()
        require(length >= 0)
        if (length == 0) return 0
        val remaining = size - position
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = minOf(length.toLong(), remaining).toInt()
        seek(position + count)
        return count
    }

    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
        val count = skip(length)
        if (count == C.RESULT_END_OF_INPUT && allowEndOfInput) return false
        if (count != length) throw EOFException()
        return true
    }
    override fun skipFully(length: Int) { skipFully(length, false) }

    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
        active()
        require(length >= 0)
        if (length > 0 && peekPosition == size && allowEndOfInput) return false
        if (length > size - peekPosition) throw EOFException()
        peekPosition += length
        return true
    }
    override fun advancePeekPosition(length: Int) { advancePeekPosition(length, false) }
    override fun resetPeekPosition() { peekPosition = position }
    override fun getPeekPosition() = peekPosition
    override fun getPosition() = position
    override fun getLength() = size
    override fun <E : Throwable> setRetryPosition(position: Long, exception: E) {
        seek(position)
        throw exception
    }
}
