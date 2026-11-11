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
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.util.zip.DataFormatException
import java.util.zip.Inflater

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun lunaExtractorsFactory(
    parsers: SubtitleParser.Factory,
    matroskaFlags: Int = 0,
    subtitleEndTimeUs: Long? = null,
    subtitleOnly: Boolean = false,
): ExtractorsFactory {
    val delegate = DefaultExtractorsFactory().setSubtitleParserFactory(parsers).setMatroskaExtractorFlags(matroskaFlags)
    var currentParsers = parsers
    fun replace(extractors: Array<Extractor>): Array<Extractor> = extractors.map {
        if (it is MatroskaExtractor) {
            val matroska = LunaMatroskaExtractor(currentParsers, matroskaFlags, subtitleEndTimeUs, subtitleOnly)
            if (subtitleEndTimeUs == null) matroska else object : Extractor by matroska {
                override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = try {
                    matroska.read(input, seekPosition)
                } catch (skip: SkipSubtitleCluster) {
                    if (skip.position < input.position || (input.length >= 0 && skip.position > input.length)) {
                        throw malformed("Invalid subtitle cluster size")
                    }
                    matroska.seek(skip.position, 0)
                    seekPosition.position = skip.position
                    Extractor.RESULT_SEEK
                }
            }
        } else it
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
private class LunaMatroskaExtractor(
    private val parsers: SubtitleParser.Factory,
    flags: Int,
    private val subtitleEndTimeUs: Long?,
    private val subtitleOnly: Boolean,
) : MatroskaExtractor(parsers, flags) {
    private val compressedTracks = mutableSetOf<Long>()
    private val ignoredTracks = mutableSetOf<Long>()
    private val blockHeader = ByteArray(11)
    private var ignoredBlockGroup = false
    private var compression: Long? = null
    private var timestampScaleNs = 1_000_000L
    private var trackTimestampScale = 1.0
    private var selectedTextTrack = false
    private var canBoundClusters = true
    private var subtitleCodecDelayNs = 0L
    private var clusterEnd: Long? = null

    override fun seek(position: Long, timeUs: Long) {
        super.seek(position, timeUs)
        ignoredBlockGroup = false
        clusterEnd = null
    }

    override fun getElementType(id: Int): Int {
        val type = super.getElementType(id)
        return if (subtitleEndTimeUs != null && id == TRACK_TIMESTAMP_SCALE) ELEMENT_TYPE_FLOAT else type
    }

    override fun floatElement(id: Int, value: Double) {
        if (id == TRACK_TIMESTAMP_SCALE) trackTimestampScale = value
        super.floatElement(id, value)
    }

    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        if (id == TRACK_ENTRY) { compression = null; trackTimestampScale = 1.0 }
        if (id == BLOCK_GROUP) ignoredBlockGroup = false
        if (id == CONTENT_COMPRESSION) compression = 0L
        if (id == CLUSTER) {
            clusterEnd = if (contentSize < 0 || contentSize in UNKNOWN_ELEMENT_SIZES ||
                contentSize > Long.MAX_VALUE - contentPosition) null else contentPosition + contentSize
        }
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
        if (id == TIMESTAMP_SCALE) timestampScaleNs = value
        if (id == CLUSTER_TIMESTAMP && clusterIsLate(value)) clusterEnd?.let { throw SkipSubtitleCluster(it) }
    }

    override fun endMasterElement(id: Int) {
        val track = if (id == TRACK_ENTRY) getCurrentTrack(id) else null
        if (id == TRACK_ENTRY && compression == 0L) {
            if (checkNotNull(track).type != C.TRACK_TYPE_TEXT || track.hasContentEncryption) {
                throw malformed("Compressed track is not a readable subtitle")
            }
            compressedTracks.add(track.number.toLong())
        }
        super.endMasterElement(id)
        if (subtitleOnly && track != null && (track.type != C.TRACK_TYPE_TEXT ||
            track.format?.let(parsers::supportsFormat) != true)) {
            ignoredTracks.add(track.number.toLong())
        }
        if (subtitleEndTimeUs != null && track?.type == C.TRACK_TYPE_TEXT &&
            track.format?.let(parsers::supportsFormat) == true) {
            selectedTextTrack = true
            canBoundClusters = canBoundClusters && readableSubtitleFormat(checkNotNull(track.format)) &&
                trackTimestampScale == 1.0 && track.codecDelayNs >= 0
            subtitleCodecDelayNs = maxOf(subtitleCodecDelayNs, track.codecDelayNs)
        }
        if (id == CLUSTER) clusterEnd = null
        if (id == BLOCK_GROUP) ignoredBlockGroup = false
    }

    private fun clusterIsLate(timestamp: Long): Boolean {
        val end = subtitleEndTimeUs ?: return false
        if (!selectedTextTrack || !canBoundClusters || timestamp < 0 || timestampScaleNs <= 0 || clusterEnd == null) return false
        return try {
            // A block can precede its cluster timestamp by 32768 ticks; later clusters may be reordered.
            val earliestNs = Math.subtractExact(Math.multiplyExact(timestamp - 32768L, timestampScaleNs), subtitleCodecDelayNs)
            Math.floorDiv(earliestNs, 1000L) >= end
        } catch (_: ArithmeticException) {
            false
        }
    }

    override fun binaryElement(id: Int, contentSize: Int, input: ExtractorInput) {
        if (id == BLOCK_ADDITIONAL && ignoredBlockGroup) {
            input.skipFully(contentSize)
            return
        }
        if ((id != BLOCK && id != SIMPLE_BLOCK) || (!subtitleOnly && compressedTracks.isEmpty()) || contentSize < 4) {
            super.binaryElement(id, contentSize, input)
            return
        }
        input.peekFully(blockHeader, 0, 1)
        val mask = (blockHeader[0].toInt() and 255).takeHighestOneBit()
        val trackBytes = if (mask == 0) 0 else 8 - mask.countTrailingZeroBits()
        input.resetPeekPosition()
        if (trackBytes == 0 || trackBytes + 3 > contentSize) {
            super.binaryElement(id, contentSize, input)
            return
        }
        val headerSize = trackBytes + 3
        input.peekFully(blockHeader, 0, headerSize)
        input.resetPeekPosition()
        var track = (blockHeader[0].toInt() and (mask - 1)).toLong()
        for (index in 1 until trackBytes) track = (track shl 8) or (blockHeader[index].toInt() and 255).toLong()
        if (track in ignoredTracks) {
            // TrackOutput sees AVC/HEVC data only after Media3 has read its NAL headers.
            if (id == BLOCK) ignoredBlockGroup = true
            input.skipFully(contentSize)
            return
        }
        if (track !in compressedTracks) {
            super.binaryElement(id, contentSize, input)
            return
        }
        if (blockHeader[headerSize - 1].toInt() and 6 != 0) throw malformed("Compressed subtitle lacing is not supported")
        if (contentSize > MAX_COMPRESSED_SUBTITLE_BYTES) throw malformed("Compressed subtitle is too large")
        val position = input.position
        val payload = ByteArray(contentSize - headerSize)
        input.skipFully(headerSize)
        input.readFully(payload, 0, payload.size)
        val inflated = inflate(payload, blockHeader.copyOf(headerSize))
        val bytes = ByteArrayInputStream(inflated)
        val expanded = DefaultExtractorInput(DataReader { target, offset, length -> bytes.read(target, offset, length) },
            position, position + inflated.size)
        super.binaryElement(id, inflated.size, expanded)
    }
}

private class SkipSubtitleCluster(val position: Long) : RuntimeException(null, null, false, false)

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
private const val ELEMENT_TYPE_FLOAT = 5
private const val TRACK_TIMESTAMP_SCALE = 0x23314F
private const val TIMESTAMP_SCALE = 0x2AD7B1
private const val CLUSTER = 0x1F43B675
private const val CLUSTER_TIMESTAMP = 0xE7
private const val CONTENT_COMPRESSION = 0x5034
private const val COMPRESSION_ALGORITHM = 0x4254
private const val BLOCK_GROUP = 0xA0
private const val BLOCK = 0xA1
private const val BLOCK_ADDITIONAL = 0xA5
private const val SIMPLE_BLOCK = 0xA3
private const val MAX_COMPRESSED_SUBTITLE_BYTES = 2 * 1024 * 1024
private val UNKNOWN_ELEMENT_SIZES = (1..8).map { (1L shl (it * 7)) - 1 }.toSet()
