package com.lunaexplorer.app.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.lunaexplorer.core.Feature
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.StorageProvider
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlinx.coroutines.runBlocking

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class StorageDataSource(
    private val provider: StorageProvider,
    private val ref: NodeRef,
) : BaseDataSource(Feature.NETWORK in provider.features) {
    private var channel: SeekableByteChannel? = null
    private var stream: InputStream? = null
    private var sourceUri: Uri? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        check(!opened)
        transferInitializing(dataSpec)
        try {
            checkInterrupted()
            runBlocking {
                channel = provider.openChannel(ref)
                if (channel == null) stream = provider.openRead(ref)
            }
            checkInterrupted()
            val seekable = channel
            remaining = if (seekable != null) {
                val size = seekable.size()
                if (dataSpec.position > size) throw outOfRange()
                seekable.position(dataSpec.position)
                size - dataSpec.position
            } else {
                skipTo(dataSpec.position)
                C.LENGTH_UNSET.toLong()
            }
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                remaining = if (remaining == C.LENGTH_UNSET.toLong()) dataSpec.length else minOf(remaining, dataSpec.length)
            }
            sourceUri = dataSpec.uri
            opened = true
            transferStarted(dataSpec)
            if (remaining == 0L) releaseReader()
            return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else remaining
        } catch (error: Exception) {
            val failure = if (error is InterruptedException) {
                Thread.currentThread().interrupt()
                InterruptedIOException().apply { initCause(error) }
            } else error
            try {
                close()
            } catch (closing: Exception) {
                failure.addSuppressed(closing)
            }
            throw failure
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        try {
            checkInterrupted()
            val count = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
            val read = channel?.read(ByteBuffer.wrap(buffer, offset, count)) ?: checkNotNull(stream).read(buffer, offset, count)
            if (read == C.RESULT_END_OF_INPUT) {
                if (channel != null) throw EOFException("The file ended before its reported size")
                remaining = 0
                releaseReader()
                return C.RESULT_END_OF_INPUT
            }
            if (read == 0) throw IOException("The file stopped returning bytes")
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
            bytesTransferred(read)
            if (remaining == 0L) releaseReader()
            return read
        } catch (error: Exception) {
            try {
                releaseReader()
            } catch (closing: Exception) {
                error.addSuppressed(closing)
            }
            throw error
        }
    }

    override fun getUri(): Uri? = sourceUri

    override fun close() {
        sourceUri = null
        remaining = 0
        try {
            releaseReader()
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun skipTo(position: Long) {
        if (position == 0L) return
        val input = checkNotNull(stream)
        val buffer = ByteArray(minOf(position, 8192).toInt())
        var left = position
        // InputStream.skip can advance past EOF, so consume bytes to validate the position.
        while (left > 0) {
            checkInterrupted()
            val read = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (read == -1) throw outOfRange()
            if (read == 0) throw IOException("The file stopped returning bytes")
            left -= read
        }
    }

    private fun releaseReader() {
        val reader: Closeable? = channel ?: stream
        channel = null
        stream = null
        reader?.close()
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
    }

    private fun outOfRange() = DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
}
