package com.lunaexplorer.app.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Base64
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.debug.DebugLog
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.StorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// External descriptors hold a service lease; start that service before launching the receiving app.
class StreamProvider : ContentProvider() {
    private val thread by lazy { HandlerThread("luna-stream").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }

    override fun onCreate(): Boolean = true

    private fun registry() = (requireNotNull(context).applicationContext as LunaApplication).graph.providers

    private fun refOf(uri: Uri): NodeRef {
        val segments = uri.pathSegments
        if (segments.size != 2) throw FileNotFoundException("Not one of Luna's files")
        val key = decodeKey(segments[1]) ?: throw FileNotFoundException("Not one of Luna's files")
        return NodeRef(segments[0], key)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Luna's files are opened for reading only")
        val ref = refOf(uri)
        val caller = runCatching { callingPackage }.getOrNull() ?: "Another app"
        val provider = runCatching { registry().provider(ref) }.getOrElse { throw FileNotFoundException(it.message) }
        val context = requireNotNull(context)
        val external = Binder.getCallingPid() != Process.myPid() && caller != context.packageName
        val lease = if (external) ExternalStreamService.acquire(context, uri) else null
        return openForReading(context, provider, ref, handler, caller, allowProxy = external) { lease?.close() }
    }

    override fun getType(uri: Uri): String? = runCatching {
        val ref = refOf(uri)
        runBlocking { registry().provider(ref).stat(ref) }.mimeType
    }.getOrNull()

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val ref = refOf(uri)
        val entry = runCatching { runBlocking { registry().provider(ref).stat(ref) } }
            .getOrElse { throw FileNotFoundException(it.message) }
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        cursor.addRow(columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> entry.name
                OpenableColumns.SIZE -> entry.size
                else -> null
            }
        })
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    /** A failed read is retried once on a reopened channel; a second failure surfaces as EIO. */
    internal class Proxy(
        private var channel: SeekableByteChannel,
        private val size: Long,
        private val label: String = "",
        private val release: () -> Unit = {},
        private val reopen: () -> SeekableByteChannel?,
    ) : ProxyFileDescriptorCallback() {
        private var released = false

        override fun onGetSize(): Long = size

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            if (released) throw ErrnoException("read", OsConstants.EBADF)
            if (offset >= this.size) return 0
            return try {
                readAt(offset, data, size)
            } catch (first: IOException) {
                // InterruptedIOException is a cancelled read; don't log it as a failure.
                if (first !is InterruptedIOException) DebugLog.w(TAG, first) { "Read of $label at $offset failed; opening it again" }
                runCatching { channel.close() }
                try {
                    channel = reopen() ?: throw IOException("The file cannot be opened again", first)
                    readAt(offset, data, size)
                } catch (again: IOException) {
                    DebugLog.w(TAG, again) { "Read of $label at $offset could not recover; the app reading it is told EIO" }
                    throw ErrnoException("read", OsConstants.EIO, again)
                }
            }
        }

        // A short read would look like EOF to the reader, so fill the range or throw.
        private fun readAt(offset: Long, data: ByteArray, size: Int): Int {
            channel.position(offset)
            val length = minOf(size.toLong(), this.size - offset).toInt()
            val buffer = ByteBuffer.wrap(data, 0, length)
            var total = 0
            while (buffer.hasRemaining()) {
                val got = channel.read(buffer)
                if (got <= 0) throw IOException("The file stopped returning bytes before its end")
                total += got
            }
            return total
        }

        override fun onRelease() {
            if (released) return
            released = true
            try { runCatching { channel.close() } } finally { release() }
        }
    }

    companion object {
        private const val TAG = "Stream"
        // Full pipes must not occupy the dispatcher's threads needed by their readers.
        private val streams = CoroutineScope(SupervisorJob() + Executors.newCachedThreadPool { task ->
            Thread(task, "luna-stream-pipe").apply { isDaemon = true }
        }.asCoroutineDispatcher())

        fun uriFor(context: Context, ref: NodeRef): Uri = Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}.stream")
            .appendPath(ref.provider)
            .appendPath(encodeKey(ref.key))
            .build()

        internal fun encodeKey(key: String): String =
            Base64.encodeToString(key.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)

        internal fun decodeKey(encoded: String): String? =
            runCatching { String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP)) }.getOrNull()

        // Blocks on providers; release runs once on open failure, proxy release, or pipe completion.
        internal fun openForReading(
            context: Context,
            provider: StorageProvider,
            ref: NodeRef,
            handler: Handler,
            caller: String,
            allowProxy: Boolean,
            release: () -> Unit = {},
        ): ParcelFileDescriptor {
            val started = System.nanoTime()
            val label = "${ref.provider}:${ref.key}"
            val released = AtomicBoolean()
            val releaseOnce = { if (released.compareAndSet(false, true)) release() }
            try {
                val entry = try { runBlocking { provider.stat(ref) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    DebugLog.w(TAG, error) { "$caller could not open $label: ${error.message}" }
                    throw FileNotFoundException(error.message)
                }
                if (entry.directory) throw FileNotFoundException("${entry.name} is a folder")
                val size = entry.size
                // A reader must not wait in the kernel for callbacks owned by its own process.
                val channel = if (allowProxy) {
                    try { runBlocking { provider.openChannel(ref) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                } else null
                if (channel != null && size != null) {
                    val proxy = Proxy(channel, size, label, release = releaseOnce) {
                        runBlocking { provider.openChannel(ref) }
                    }
                    try {
                        val storage = context.getSystemService(StorageManager::class.java)
                        val descriptor = storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, proxy, handler)
                        DebugLog.i(TAG) { "$caller opened $label, $size bytes, to read from any offset, in ${DebugLog.millisSince(started)} ms" }
                        return descriptor
                    } catch (error: Exception) {
                        proxy.onRelease()
                        throw error
                    }
                }
                runCatching { channel?.close() }
                DebugLog.i(TAG) { "$caller opened $label as a stream from its start" }
                val (read, write) = ParcelFileDescriptor.createPipe().let { it[0] to it[1] }
                streams.launch {
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                            provider.openRead(ref).use { it.copyTo(out) }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        ensureActive()
                    } finally {
                        releaseOnce()
                    }
                }
                return read
            } catch (error: Exception) {
                releaseOnce()
                throw error
            }
        }
    }
}
