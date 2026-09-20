package com.lunaexplorer.core

import java.io.IOException

/** One composited frame. [argb] is the reader's own canvas, redrawn by the next frame: copy what has to last. */
class GifFrame(val index: Int, val delayMillis: Int, val width: Int, val height: Int, val argb: IntArray)

object Gif {
    fun isGif(bytes: ByteArray): Boolean =
        bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()

    fun frameCount(bytes: ByteArray): Int {
        val reader = GifReader(bytes, decode = false)
        var count = 0
        while (reader.next() != null) count++
        return count
    }

    /** [onFrame] returns false to stop early. */
    fun frames(bytes: ByteArray, onFrame: (GifFrame) -> Boolean) {
        val reader = GifReader(bytes)
        while (true) if (!onFrame(reader.next() ?: return)) return
    }
}

/**
 * Decodes the frames of a GIF in order, as a viewer shows them: each drawn over what the ones before
 * left behind. Pixels no frame has covered, and pixels a frame's disposal cleared, are transparent.
 * A frame cannot be reached without decoding those before it. Not safe for concurrent use.
 */
class GifReader internal constructor(bytes: ByteArray, private val decode: Boolean) {
    constructor(bytes: ByteArray) : this(bytes, decode = true)

    private val input = Cursor(bytes)
    private val width: Int
    private val height: Int
    private val globalTable: IntArray?
    private val canvas: IntArray
    private var backup: IntArray? = null
    private var index = 0
    private var finished = false

    private var delay = 0
    private var transparent = -1
    private var disposal = 0
    // What the frame before asked for, carried out just before the next one is drawn.
    private var owed = 0
    private var owedLeft = 0
    private var owedTop = 0
    private var owedWidth = 0
    private var owedHeight = 0

    init {
        if (!Gif.isGif(bytes)) throw IOException("Not a GIF")
        input.skip(6)
        width = input.u16()
        height = input.u16()
        val packed = input.u8()
        input.skip(2)
        if (width == 0 || height == 0 || width.toLong() * height > MAX_PIXELS) {
            throw IOException("This GIF is too large to show frame by frame")
        }
        globalTable = if (packed and 0x80 != 0) colorTable(input, 2 shl (packed and 7)) else null
        canvas = IntArray(if (decode) width * height else 0)
    }

    /** The next frame, or null after the last. A truncated or damaged file ends at its last whole frame. */
    fun next(): GifFrame? {
        if (finished) return null
        try {
            while (input.left > 0) {
                when (input.u8()) {
                    EXTENSION -> extension()
                    IMAGE -> return image()
                    else -> break
                }
            }
        } catch (truncated: IOException) {
            if (index == 0) throw truncated
        }
        finished = true
        return null
    }

    private fun extension() {
        val graphicControl = input.u8() == GRAPHIC_CONTROL && input.left > 0 && input.bytes[input.at].toInt() == 4
        if (graphicControl) {
            input.skip(1)
            val flags = input.u8()
            delay = input.u16() * 10
            val transparentIndex = input.u8()
            disposal = (flags shr 2) and 7
            transparent = if (flags and 1 != 0) transparentIndex else -1
        }
        input.skipSubBlocks()
    }

    private fun image(): GifFrame {
        val left = input.u16()
        val top = input.u16()
        val frameWidth = input.u16()
        val frameHeight = input.u16()
        val flags = input.u8()
        val table = if (flags and 0x80 != 0) colorTable(input, 2 shl (flags and 7)) else globalTable
        if (!decode) {
            input.skip(1)
            input.skipSubBlocks()
        } else {
            if (frameWidth.toLong() * frameHeight > MAX_PIXELS) throw IOException("A frame of this GIF is too large")
            when (owed) {
                DISPOSE_TO_BACKGROUND -> clear(canvas, width, height, owedLeft, owedTop, owedWidth, owedHeight)
                DISPOSE_TO_PREVIOUS -> backup?.copyInto(canvas)
            }
            if (disposal == DISPOSE_TO_PREVIOUS) backup = (backup ?: IntArray(canvas.size)).also { canvas.copyInto(it) }
            val pixels = lzw(input, frameWidth * frameHeight)
            draw(canvas, width, height, pixels, left, top, frameWidth, frameHeight,
                interlaced = flags and 0x40 != 0, table = table ?: IntArray(0), transparent = transparent)
            owed = disposal
            owedLeft = left; owedTop = top; owedWidth = frameWidth; owedHeight = frameHeight
        }
        val frame = GifFrame(index++, delay, width, height, canvas)
        // A graphic control block applies to the one image after it.
        delay = 0; transparent = -1; disposal = 0
        return frame
    }

    private class Cursor(val bytes: ByteArray) {
        var at = 0
        val left: Int get() = bytes.size - at
        fun u8(): Int { if (at >= bytes.size) throw IOException("The GIF ends early"); return bytes[at++].toInt() and 0xff }
        fun u16(): Int = u8() or (u8() shl 8)
        fun skip(count: Int) { if (count > left) throw IOException("The GIF ends early"); at += count }
        fun skipSubBlocks() { while (true) { val size = u8(); if (size == 0) return; skip(size) } }
    }

    private fun colorTable(input: Cursor, size: Int): IntArray =
        IntArray(size) { (0xff shl 24) or (input.u8() shl 16) or (input.u8() shl 8) or input.u8() }

    private fun clear(canvas: IntArray, width: Int, height: Int, left: Int, top: Int, w: Int, h: Int) {
        val from = left.coerceIn(0, width)
        val to = (left + w).coerceIn(0, width)
        for (y in top.coerceAtLeast(0) until (top + h).coerceAtMost(height)) canvas.fill(0, y * width + from, y * width + to)
    }

    private fun draw(
        canvas: IntArray, width: Int, height: Int, pixels: ByteArray,
        left: Int, top: Int, w: Int, h: Int, interlaced: Boolean, table: IntArray, transparent: Int,
    ) {
        var source = 0
        fun row(y: Int) {
            val canvasY = top + y
            for (x in 0 until w) {
                val code = pixels[source++].toInt() and 0xff
                val canvasX = left + x
                if (code != transparent && code < table.size && canvasY in 0 until height && canvasX in 0 until width) {
                    canvas[canvasY * width + canvasX] = table[code]
                }
            }
        }
        if (!interlaced) {
            for (y in 0 until h) row(y)
            return
        }
        // Stored as four passes: every 8th row from 0, every 8th from 4, every 4th from 2, every 2nd from 1.
        for ((start, step) in listOf(0 to 8, 4 to 8, 2 to 4, 1 to 2)) {
            var y = start
            while (y < h) { row(y); y += step }
        }
    }

    /** Returns [count] colour indices; any the data does not reach stay 0. Leaves [input] after the image's last sub-block. */
    private fun lzw(input: Cursor, count: Int): ByteArray {
        val minimum = input.u8().coerceIn(2, 8)
        val clear = 1 shl minimum
        val end = clear + 1
        val prefix = ShortArray(MAX_CODES)
        val suffix = ByteArray(MAX_CODES)
        val stack = ByteArray(MAX_CODES + 1)
        val out = ByteArray(count)
        var written = 0
        var codeSize = minimum + 1
        var next = end + 1
        var previous = -1
        var first = 0
        var bits = 0
        var held = 0
        var finished = false

        while (true) {
            val size = input.u8()
            if (size == 0) break
            if (size > input.left) throw IOException("The GIF ends early")
            val blockEnd = input.at + size
            while (input.at < blockEnd && !finished) {
                held = held or ((input.bytes[input.at++].toInt() and 0xff) shl bits)
                bits += 8
                while (bits >= codeSize && !finished) {
                    val code = held and ((1 shl codeSize) - 1)
                    held = held ushr codeSize
                    bits -= codeSize
                    if (code == clear) {
                        codeSize = minimum + 1; next = end + 1; previous = -1
                    } else if (code == end || code > next || (previous == -1 && code >= clear)) {
                        finished = true
                    } else if (previous == -1) {
                        if (written < count) out[written++] = code.toByte()
                        previous = code; first = code
                    } else {
                        var depth = 0
                        var cursor = code
                        // The one code that may name the entry about to be added: previous string plus its own first byte.
                        if (code == next) { stack[depth++] = first.toByte(); cursor = previous }
                        while (cursor >= clear && depth < MAX_CODES) {
                            stack[depth++] = suffix[cursor]
                            cursor = prefix[cursor].toInt()
                        }
                        first = cursor
                        stack[depth++] = cursor.toByte()
                        while (depth > 0 && written < count) out[written++] = stack[--depth]
                        if (next < MAX_CODES) {
                            prefix[next] = previous.toShort()
                            suffix[next] = first.toByte()
                            next++
                            if (next == (1 shl codeSize) && codeSize < 12) codeSize++
                        }
                        previous = code
                    }
                    // Data past the last pixel draws nothing, and a hostile file can carry a great deal of it.
                    if (written >= count) finished = true
                }
            }
            input.at = blockEnd
        }
        return out
    }

    private companion object {
        /** The canvas and its restore copy each take four bytes a pixel. */
        const val MAX_PIXELS = 24_000_000
        const val MAX_CODES = 4096

        const val EXTENSION = 0x21
        const val IMAGE = 0x2C
        const val GRAPHIC_CONTROL = 0xF9

        const val DISPOSE_TO_BACKGROUND = 2
        const val DISPOSE_TO_PREVIOUS = 3
    }
}
