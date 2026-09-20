package com.lunaexplorer.core

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel

const val HEX_BLOCK = 64 * 1024
const val HEX_BLOCKS_KEPT = 32
const val HEX_SEARCH_CHUNK = 1024 * 1024
/** The scrollbar's metrics are floats checked against Int.MAX_VALUE, which 2^31 - 1 rounds up to. */
const val HEX_MAX_ROWS = 1 shl 30

private const val HEX_DIGITS = "0123456789ABCDEF"

data class HexLayout(val offsetDigits: Int, val bytesPerRow: Int) {
    private val hexStart = offsetDigits + 2
    private val asciiStart = hexStart + 3 * bytesPerRow - 1 + (bytesPerRow - 1) / 8 + 2
    val columns: Int = asciiStart + bytesPerRow

    fun hexRange(byte: Int): IntRange = (hexStart + 3 * byte + byte / 8).let { it..it + 1 }
    fun asciiAt(byte: Int): Int = asciiStart + byte

    fun row(offset: Long, bytes: ByteArray, count: Int): String {
        val chars = CharArray(asciiStart + count) { ' ' }
        writeOffset(chars, offset)
        for (index in 0 until count) {
            val value = bytes[index].toInt() and 0xFF
            val at = hexRange(index).first
            chars[at] = HEX_DIGITS[value ushr 4]
            chars[at + 1] = HEX_DIGITS[value and 0xF]
            chars[asciiStart + index] = if (value in 0x20..0x7E) value.toChar() else '.'
        }
        return String(chars)
    }

    /** A row whose bytes are not here: the offset alone, or ?? pairs when the read [failed]. */
    fun unread(offset: Long, count: Int, failed: Boolean): String {
        val chars = CharArray(if (failed && count > 0) hexRange(count - 1).last + 1 else offsetDigits) { ' ' }
        writeOffset(chars, offset)
        if (failed) for (index in 0 until count) {
            val at = hexRange(index).first
            chars[at] = '?'
            chars[at + 1] = '?'
        }
        return String(chars)
    }

    private fun writeOffset(chars: CharArray, offset: Long) {
        var rest = offset
        for (index in offsetDigits - 1 downTo 0) {
            chars[index] = HEX_DIGITS[(rest and 0xF).toInt()]
            rest = rest ushr 4
        }
    }

    companion object {
        fun offsetDigits(size: Long): Int {
            var digits = 0
            var rest = (size - 1).coerceAtLeast(0)
            while (rest != 0L) { digits++; rest = rest ushr 4 }
            return maxOf(4, digits + digits % 2)
        }

        /** Powers of two, so a row starts on a multiple of its size and never crosses a block. */
        fun fit(columns: Int, offsetDigits: Int): Int =
            intArrayOf(64, 32, 16, 8).firstOrNull { HexLayout(offsetDigits, it).columns <= columns } ?: 4
    }
}

/** The rows on screen at once. Bases are quantised, so offsets near each other share a window. */
data class HexWindow(val base: Long, val rows: Int) {
    fun earlier(size: Long, bytesPerRow: Int): HexWindow? = if (base == 0L) null else around(base - 1, size, bytesPerRow)

    fun later(size: Long, bytesPerRow: Int): HexWindow? =
        (base + rows.toLong() * bytesPerRow).let { end -> if (end >= size) null else around(end, size, bytesPerRow) }

    companion object {
        private const val STEP = HEX_MAX_ROWS / 4

        /** Leaves at least a quarter of a window on each side of [offset], short of the file's ends. */
        fun around(offset: Long, size: Long, bytesPerRow: Int): HexWindow =
            starting((offset.coerceAtLeast(0) / bytesPerRow / STEP - 1) * STEP, size, bytesPerRow)

        fun at(base: Long, size: Long, bytesPerRow: Int): HexWindow =
            starting(base.coerceAtLeast(0) / bytesPerRow / STEP * STEP, size, bytesPerRow)

        private fun starting(row: Long, size: Long, bytesPerRow: Int): HexWindow {
            val total = (size + bytesPerRow - 1) / bytesPerRow
            if (total <= HEX_MAX_ROWS) return HexWindow(0, total.toInt())
            val last = (total - HEX_MAX_ROWS + STEP - 1) / STEP * STEP
            val first = row.coerceIn(0, last)
            return HexWindow(first * bytesPerRow, minOf(HEX_MAX_ROWS.toLong(), total - first).toInt())
        }
    }
}

/** ASCII digits only, no sign and no separators. A 0x prefix means hex whatever [hex] says. */
fun parseOffset(text: String, hex: Boolean): Long? {
    val trimmed = text.trim()
    val prefixed = trimmed.startsWith("0x") || trimmed.startsWith("0X")
    val digits = if (prefixed) trimmed.substring(2) else trimmed
    val radix = if (hex || prefixed) 16 else 10
    if (digits.isEmpty() || digits.any { hexValue(it).let { value -> value < 0 || value >= radix } }) return null
    return digits.toULongOrNull(radix)?.takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong()
}

sealed interface HexPattern {
    class Bytes(val bytes: ByteArray) : HexPattern
    enum class Invalid : HexPattern { NOT_HEX, ODD }
}

/** Whitespace between digits is ignored. Nothing but whitespace gives no bytes. */
fun parseHexPattern(text: String): HexPattern {
    val digits = text.filterNot(Char::isWhitespace)
    if (digits.any { hexValue(it) < 0 }) return HexPattern.Invalid.NOT_HEX
    if (digits.length % 2 != 0) return HexPattern.Invalid.ODD
    return HexPattern.Bytes(ByteArray(digits.length / 2) { (hexValue(digits[it * 2]) shl 4 or hexValue(digits[it * 2 + 1])).toByte() })
}

private fun hexValue(char: Char): Int = when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> -1
}

/**
 * Blocks of one open file, the most recently used kept. [peek] answers from those alone and never waits
 * on the channel, so it is safe on the main thread; [load] and [read] block.
 */
class HexBlocks(
    private val channel: SeekableByteChannel,
    val blockSize: Int = HEX_BLOCK,
    kept: Int = HEX_BLOCKS_KEPT,
) : Closeable {
    /** Only ever shrinks: growth is not seen until the file is opened again. */
    @Volatile var size: Long = channel.size()
        private set

    // The channel has one position. Never taken on the main thread.
    private val channelLock = Any()
    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<Long, ByteArray>(kept, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > kept
    }
    private var closed = false

    fun has(block: Long): Boolean = synchronized(cacheLock) { cache.containsKey(block) }

    /** The bytes copied, fewer than [length] at the end of the file; -1 when a block they lie in is not loaded. */
    fun peek(offset: Long, into: ByteArray, length: Int): Int {
        val count = minOf(length.toLong(), size - offset).coerceAtLeast(0).toInt()
        synchronized(cacheLock) {
            var copied = 0
            while (copied < count) {
                val at = offset + copied
                val bytes = cache[at / blockSize] ?: return -1
                val from = (at % blockSize).toInt()
                if (from >= bytes.size) return copied
                val n = minOf(count - copied, bytes.size - from)
                System.arraycopy(bytes, from, into, copied, n)
                copied += n
            }
            return copied
        }
    }

    fun load(block: Long) {
        synchronized(channelLock) {
            if (closed) throw ClosedChannelException()
            if (has(block)) return
            val start = block * blockSize
            val wanted = minOf(blockSize.toLong(), size - start).toInt()
            if (wanted <= 0) return
            val bytes = ByteArray(wanted)
            val filled = fill(start, bytes, wanted, wanted)
            if (filled > 0) synchronized(cacheLock) { cache[block] = if (filled == wanted) bytes else bytes.copyOf(filled) }
        }
    }

    /** Past the cache. Fewer than [length] bytes only at the end of the file. */
    fun read(offset: Long, into: ByteArray, length: Int): Int {
        synchronized(channelLock) {
            if (closed) throw ClosedChannelException()
            val wanted = minOf(length.toLong(), size - offset).toInt()
            if (wanted <= 0) return 0
            // A FileChannel copies through a per-thread native buffer as large as the read.
            return fill(offset, into, wanted, if (channel is FileChannel) HEX_BLOCK else wanted)
        }
    }

    private fun fill(start: Long, into: ByteArray, wanted: Int, slice: Int): Int {
        channel.position(start)
        var filled = 0
        while (filled < wanted) {
            val got = channel.read(ByteBuffer.wrap(into, filled, minOf(slice, wanted - filled)))
            if (got < 0) break
            if (got == 0) throw IOException("The file stopped returning bytes")
            filled += got
        }
        if (filled < wanted) {
            // A remote channel reports the length it opened with and ends early on a fault, not a truncation.
            val actual = channel.size()
            if (actual < size) size = actual
            if (start + filled < size) throw IOException("The file ended early")
        }
        return filled
    }

    /** Waits for a read in progress. */
    override fun close() {
        synchronized(channelLock) {
            if (closed) return
            closed = true
            channel.close()
        }
    }
}

sealed interface HexStep {
    data class Found(val offset: Long, val wrapped: Boolean) : HexStep
    data class More(val scanned: Long, val total: Long) : HexStep
    data object None : HexStep
}

/**
 * Looks for [pattern] from [from] towards one end of the file, then round from the other end back to [from].
 * A [from] beyond the end a search heads for leaves only the wrapped part; one beyond the other end starts
 * at that end. Each [step] is one read of at most [chunk] bytes, so the caller can stop between them.
 */
class HexSearch(
    private val bytes: HexBlocks,
    private val pattern: ByteArray,
    from: Long,
    private val forward: Boolean,
    private val chunk: Int = HEX_SEARCH_CHUNK,
) {
    private class Segment(val low: Long, val high: Long, val wrapped: Boolean)

    private val last = bytes.size - pattern.size
    private val total = last + 1
    // Reads overlap by all but one byte of the pattern, so a match across two of them is whole in the second.
    private val stride = chunk - (pattern.size - 1)
    private val segments = ArrayDeque<Segment>()
    private var cursor = 0L
    private var scanned = 0L
    private var buffer: ByteArray? = null

    init {
        if (pattern.isNotEmpty() && last >= 0) {
            require(stride > 0)
            if (forward) {
                val start = from.coerceAtLeast(0)
                add(start, last, false)
                add(0, minOf(start - 1, last), true)
            } else {
                val start = from.coerceAtMost(last)
                add(0, start, false)
                add(maxOf(start + 1, 0), last, true)
            }
            enter()
        }
    }

    private fun add(low: Long, high: Long, wrapped: Boolean) {
        if (low <= high) segments += Segment(low, high, wrapped)
    }

    private fun enter() {
        segments.firstOrNull()?.let { cursor = if (forward) it.low else it.high }
    }

    fun step(): HexStep {
        val segment = segments.firstOrNull() ?: return HexStep.None
        val count = minOf(stride.toLong(), if (forward) segment.high - cursor + 1 else cursor - segment.low + 1).toInt()
        val low = if (forward) cursor else cursor - count + 1
        val wanted = count + pattern.size - 1
        val into = buffer ?: ByteArray(minOf(chunk.toLong(), bytes.size).toInt()).also { buffer = it }
        val got = bytes.read(low, into, wanted)
        val usable = minOf(count, got - pattern.size + 1)
        val found = if (forward) (0 until usable).firstOrNull { matches(into, it) }
            else (usable - 1 downTo 0).firstOrNull { matches(into, it) }
        if (found != null) return HexStep.Found(low + found, segment.wrapped)
        scanned += count
        cursor = if (forward) low + count else low - 1
        // Going forward, a short read means the file ends here.
        if ((forward && got < wanted) || cursor > segment.high || cursor < segment.low) {
            segments.removeFirst()
            enter()
        }
        return if (segments.isEmpty()) HexStep.None else HexStep.More(scanned, total)
    }

    private fun matches(buffer: ByteArray, at: Int): Boolean {
        if (buffer[at] != pattern[0]) return false
        for (index in 1 until pattern.size) if (buffer[at + index] != pattern[index]) return false
        return true
    }
}
