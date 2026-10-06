package com.lunaexplorer.app.ui

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.ParserException
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.util.zip.DataFormatException
import java.util.zip.Inflater

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun lunaExtractorsFactory(parsers: SubtitleParser.Factory, matroskaFlags: Int = 0): ExtractorsFactory {
    val delegate = DefaultExtractorsFactory().setSubtitleParserFactory(parsers).setMatroskaExtractorFlags(matroskaFlags)
    var currentParsers = parsers
    fun replace(extractors: Array<Extractor>): Array<Extractor> = extractors.map {
        if (it is MatroskaExtractor) LunaMatroskaExtractor(currentParsers, matroskaFlags) else it
    }.toTypedArray()
    return object : ExtractorsFactory {
        override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
            currentParsers = subtitleParserFactory
            delegate.setSubtitleParserFactory(subtitleParserFactory)
            return this
        }
        override fun createExtractors(): Array<Extractor> = replace(delegate.createExtractors())
        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            replace(delegate.createExtractors(uri, responseHeaders))
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class LunaMatroskaExtractor(parsers: SubtitleParser.Factory, flags: Int) : MatroskaExtractor(parsers, flags) {
    private val compressedTracks = mutableSetOf<Long>()
    private var compression: Long? = null

    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        if (id == TRACK_ENTRY) compression = null
        if (id == CONTENT_COMPRESSION) compression = 0L
        super.startMasterElement(id, contentPosition, contentSize)
    }

    override fun integerElement(id: Int, value: Long) {
        if (id == COMPRESSION_ALGORITHM) {
            compression = value
            if (value == 0L) {
                // Media3 accepts only header stripping; zlib is handled before its block reader.
                super.integerElement(id, 3L)
                return
            }
        }
        super.integerElement(id, value)
    }

    override fun endMasterElement(id: Int) {
        if (id == TRACK_ENTRY && compression == 0L) {
            val track = getCurrentTrack(id)
            if (track.type != C.TRACK_TYPE_TEXT || track.hasContentEncryption) {
                throw malformed("Compressed track is not a readable subtitle")
            }
            compressedTracks.add(track.number.toLong())
        }
        super.endMasterElement(id)
    }

    override fun binaryElement(id: Int, contentSize: Int, input: ExtractorInput) {
        if ((id != BLOCK && id != SIMPLE_BLOCK) || compressedTracks.isEmpty() || contentSize < 4) {
            super.binaryElement(id, contentSize, input)
            return
        }
        val first = ByteArray(1)
        input.peekFully(first, 0, 1)
        val mask = (first[0].toInt() and 255).takeHighestOneBit()
        val trackBytes = if (mask == 0) 0 else 8 - mask.countTrailingZeroBits()
        input.resetPeekPosition()
        if (trackBytes == 0 || trackBytes + 3 > contentSize) {
            super.binaryElement(id, contentSize, input)
            return
        }
        val header = ByteArray(trackBytes + 3)
        input.peekFully(header, 0, header.size)
        input.resetPeekPosition()
        var track = (header[0].toInt() and (mask - 1)).toLong()
        for (index in 1 until trackBytes) track = (track shl 8) or (header[index].toInt() and 255).toLong()
        if (track !in compressedTracks) {
            super.binaryElement(id, contentSize, input)
            return
        }
        if (header.last().toInt() and 6 != 0) throw malformed("Compressed subtitle lacing is not supported")
        if (contentSize > MAX_COMPRESSED_SUBTITLE_BYTES) throw malformed("Compressed subtitle is too large")
        val position = input.position
        val payload = ByteArray(contentSize - header.size)
        input.skipFully(header.size)
        input.readFully(payload, 0, payload.size)
        val inflated = inflate(payload, header)
        val bytes = ByteArrayInputStream(inflated)
        val expanded = DefaultExtractorInput(DataReader { target, offset, length -> bytes.read(target, offset, length) },
            position, position + inflated.size)
        super.binaryElement(id, inflated.size, expanded)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun inflate(payload: ByteArray, header: ByteArray): ByteArray {
    val inflater = Inflater()
    try {
        inflater.setInput(payload)
        val output = ByteArrayOutputStream().apply { write(header) }
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val count = inflater.inflate(buffer)
            if (count == 0 && !inflater.finished()) throw malformed("Compressed subtitle is incomplete")
            if (count > MAX_COMPRESSED_SUBTITLE_BYTES - output.size()) throw malformed("Compressed subtitle is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    } catch (error: DataFormatException) {
        throw ParserException.createForMalformedContainer("Compressed subtitle could not be read", error)
    } finally {
        inflater.end()
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun malformed(message: String): ParserException = ParserException.createForMalformedContainer(message, null)

private const val TRACK_ENTRY = 0xAE
private const val CONTENT_COMPRESSION = 0x5034
private const val COMPRESSION_ALGORITHM = 0x4254
private const val BLOCK = 0xA1
private const val SIMPLE_BLOCK = 0xA3
private const val MAX_COMPRESSED_SUBTITLE_BYTES = 2 * 1024 * 1024
