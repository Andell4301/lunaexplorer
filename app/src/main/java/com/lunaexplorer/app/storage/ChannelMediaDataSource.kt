package com.lunaexplorer.app.storage

import android.media.MediaDataSource
import android.os.CancellationSignal
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SeekableByteChannel

internal class ChannelMediaDataSource(
    private val channel: SeekableByteChannel,
    private val signal: CancellationSignal,
) : MediaDataSource() {
    private var closed = false

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        checkReadable()
        if (size == 0) return 0
        channel.position(position)
        return channel.read(ByteBuffer.wrap(buffer, offset, size)).also { checkReadable() }
    }

    @Synchronized
    override fun getSize(): Long {
        checkReadable()
        return channel.size()
    }

    private fun checkReadable() {
        if (closed) throw ClosedChannelException()
        if (signal.isCanceled) throw InterruptedIOException("The thumbnail is no longer wanted")
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        channel.close()
    }
}
