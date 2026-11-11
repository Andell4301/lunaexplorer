package com.lunaexplorer.app.ui

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ClipPlaybackCache(
    private val videoUri: Uri,
    private val upstream: DataSource.Factory,
    capacityBytes: Int = 24 * 1024 * 1024,
    private val blockBytes: Int = 64 * 1024,
) : DataSource.Factory {
    private val blocks = LinkedHashMap<Long, ByteArray>(16, 0.75f, true)
    private val capacity = capacityBytes / blockBytes
    @Volatile private var enabled = false
    @Volatile private var length: Long? = null

    init { require(blockBytes > 0 && capacity > 0) }

    @Synchronized fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) blocks.clear()
    }

    @Synchronized private fun cached(at: Long): ByteArray? = blocks[at]

    @Synchronized private fun remember(at: Long, bytes: ByteArray) {
        if (!enabled || bytes.isEmpty()) return
        blocks[at] = bytes
        while (blocks.size > capacity) blocks.remove(blocks.entries.iterator().next().key)
    }

    override fun createDataSource(): DataSource = Reader(upstream.createDataSource())

    private inner class Reader(private val source: DataSource) : DataSource {
        private var spec: DataSpec? = null
        private var position = 0L
        private var remaining = 0L
        private var upstreamPosition = -1L
        private var upstreamOpen = false
        private var upstreamBounded = false

        override fun addTransferListener(transferListener: TransferListener) = source.addTransferListener(transferListener)
        override fun getUri(): Uri? = spec?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = source.responseHeaders

        override fun open(dataSpec: DataSpec): Long {
            check(spec == null)
            spec = dataSpec
            position = dataSpec.position
            return try {
                val available = if (enabled && dataSpec.uri == videoUri && length != null) {
                    val end = checkNotNull(length)
                    if (position > end) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
                    end - position
                } else openUpstream(position, false)
                remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available
                    else if (available == C.LENGTH_UNSET.toLong()) dataSpec.length else minOf(dataSpec.length, available)
                remaining
            } catch (error: Exception) {
                try { close() } catch (closing: Exception) { error.addSuppressed(closing) }
                throw error
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val current = checkNotNull(spec)
            return try {
                checkInterrupted()
                val wanted = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
                val count = if (enabled && current.uri == videoUri) {
                    val blockPosition = position / blockBytes * blockBytes
                    val block = cached(blockPosition) ?: readBlock(blockPosition)
                    val inBlock = (position - blockPosition).toInt()
                    if (inBlock >= block.size) C.RESULT_END_OF_INPUT else {
                        val read = minOf(wanted, block.size - inBlock)
                        block.copyInto(buffer, offset, inBlock, inBlock + read)
                        read
                    }
                } else {
                    if (!upstreamOpen || upstreamPosition != position) openUpstream(position, false)
                    source.read(buffer, offset, wanted).also { if (it > 0) upstreamPosition += it }
                }
                if (count == 0) throw IOException("The file stopped returning bytes")
                if (count == C.RESULT_END_OF_INPUT) {
                    remaining = 0
                } else {
                    position += count
                    if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count
                }
                count
            } catch (error: Exception) {
                try { closeUpstream() } catch (closing: Exception) { error.addSuppressed(closing) }
                throw error
            }
        }

        private fun readBlock(at: Long): ByteArray {
            if (!upstreamOpen || upstreamPosition != at || upstreamBounded) openUpstream(at, true)
            val count = this@ClipPlaybackCache.length?.let { minOf(blockBytes.toLong(), (it - at).coerceAtLeast(0)).toInt() }
                ?: blockBytes
            val bytes = ByteArray(count)
            var read = 0
            while (read < count) {
                checkInterrupted()
                val got = source.read(bytes, read, count - read)
                if (got == C.RESULT_END_OF_INPUT) {
                    this@ClipPlaybackCache.length = at + read
                    break
                }
                if (got == 0) throw IOException("The file stopped returning bytes")
                read += got
                upstreamPosition += got
            }
            return (if (read == count) bytes else bytes.copyOf(read)).also { remember(at, it) }
        }

        private fun openUpstream(at: Long, block: Boolean): Long {
            closeUpstream()
            checkInterrupted()
            val current = checkNotNull(spec)
            val request = if (block) current.buildUpon().setPosition(at).setLength(C.LENGTH_UNSET.toLong()).build()
                else current.subrange(at - current.position)
            upstreamOpen = true
            upstreamBounded = request.length != C.LENGTH_UNSET.toLong()
            upstreamPosition = at
            val available = source.open(request)
            if (current.uri == videoUri && request.length == C.LENGTH_UNSET.toLong() && available >= 0) {
                this@ClipPlaybackCache.length = at + available
            }
            return available
        }

        private fun closeUpstream() {
            val close = upstreamOpen
            upstreamOpen = false
            upstreamBounded = false
            upstreamPosition = -1
            if (close) source.close()
        }

        override fun close() {
            spec = null
            remaining = 0
            closeUpstream()
        }

        private fun checkInterrupted() {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
        }
    }
}
