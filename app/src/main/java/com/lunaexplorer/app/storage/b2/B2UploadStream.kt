package com.lunaexplorer.app.storage.b2

import com.lunaexplorer.core.StorageException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

// B2 needs length and SHA-1 before upload; stage one bounded part at a time.
internal class B2UploadStream(
    staging: File,
    private val partSize: Long,
    private val key: String,
    private val contentType: String,
    /** Resolved when bytes are first sent, not when the stream is opened. */
    private val bucket: () -> B2Bucket,
    private val onPublished: (B2Item) -> Unit,
) : OutputStream() {
    private val held = File(staging.apply { mkdirs() }, "up-${UUID.randomUUID()}.part")
    private var out: FileOutputStream? = FileOutputStream(held)
    private var heldBytes = 0L
    private var largeFileId: String? = null
    private val partSha1s = ArrayList<String>()
    private var closed = false

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) throw IOException("The upload is already closed")
        var from = off
        var left = len
        while (left > 0) {
            // A full part is only sent once more bytes arrive, so a stream of exactly one part stays a plain upload.
            if (heldBytes == partSize) sendPart()
            val n = minOf(left.toLong(), partSize - heldBytes).toInt()
            requireNotNull(out).write(b, from, n)
            heldBytes += n; from += n; left -= n
        }
    }

    private fun sendPart() = guarded {
        requireNotNull(out).close(); out = null
        val target = bucket()
        val id = largeFileId ?: target.startLarge(key, null, contentType).also { largeFileId = it }
        partSha1s += target.uploadPart(id, partSha1s.size + 1, held)
        out = FileOutputStream(held)
        heldBytes = 0
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            guarded {
                out?.close(); out = null
                val target = bucket()
                val id = largeFileId
                val published = if (id == null) target.upload(key, held, null, contentType) else {
                    if (heldBytes > 0) partSha1s += target.uploadPart(id, partSha1s.size + 1, held)
                    target.finishLarge(id, partSha1s)
                }
                largeFileId = null
                onPublished(published)
            }
        } finally {
            held.delete()
        }
    }

    /** A failure ends the upload: the unfinished large file is cancelled so B2 stops billing its parts. */
    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (failure: Exception) {
            closed = true
            runCatching { out?.close() }; out = null
            held.delete()
            largeFileId?.let { id -> runCatching { bucket().cancelLarge(id) } }
            largeFileId = null
            throw when (failure) {
                is B2Failure -> StorageException(failure.reason, failure.message ?: "The upload failed", failure)
                is IOException -> failure
                else -> IOException(failure.message ?: "The upload failed", failure)
            }
        }
    }
}
