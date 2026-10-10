package com.lunaexplorer.app.terminal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class LocalShellConnection private constructor(handles: IntArray) : TerminalConnection {
    private val master = handles[0]
    private val cancellation = handles[1]
    private var process = handles[2]
    private val closed = AtomicBoolean()
    private val readLock = Any()
    private val writeLock = Any()
    private val processLock = Any()
    @Volatile private var status: Int? = null

    override val exitStatus: Int? get() = status

    init {
        val pid = process
        thread(name = "luna-shell-reaper", isDaemon = true) {
            val result = NativePty.awaitExit(pid)
            synchronized(processLock) {
                NativePty.reap(pid)
                process = 0
                status = result.takeIf { it >= 0 }
            }
        }
    }

    override val input = object : InputStream() {
        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            checkBounds(bytes, offset, length)
            if (length == 0) return 0
            return synchronized(readLock) {
                if (closed.get()) -1 else NativePty.read(master, cancellation, bytes, offset, length)
            }
        }

        override fun close() = this@LocalShellConnection.close()
    }

    override val output = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            checkBounds(bytes, offset, length)
            synchronized(writeLock) {
                var written = 0
                while (written < length) {
                    if (closed.get()) throw IOException("Terminal closed")
                    written += NativePty.write(master, cancellation, bytes, offset + written, length - written)
                }
            }
        }

        override fun close() = this@LocalShellConnection.close()
    }

    override fun resize(columns: Int, rows: Int) {
        synchronized(processLock) {
            if (!closed.get()) NativePty.resize(master, columns, rows)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(processLock) {
            NativePty.cancel(cancellation)
            if (process > 0) NativePty.terminate(master, process)
            synchronized(readLock) {
                synchronized(writeLock) { NativePty.dispose(master, cancellation) }
            }
        }
    }

    companion object {
        suspend fun open(context: Context, directory: String? = null, columns: Int = 80, rows: Int = 24): LocalShellConnection {
            var opened: LocalShellConnection? = null
            try {
                return withContext(Dispatchers.IO) {
                    val home = context.filesDir.absolutePath
                    val path = directory ?: home
                    val environment = System.getenv().toMutableMap().apply {
                        this["HOME"] = home
                        this["TMPDIR"] = context.cacheDir.absolutePath
                        this["TERM"] = "xterm-256color"
                        this["COLORTERM"] = "truecolor"
                        this["PWD"] = path
                        if (this["ENV"].isNullOrEmpty() || this["ENV"] == "/system/etc/mkshrc") {
                            this["ENV"] = shellStartup(context).absolutePath
                        }
                    }.map { (name, value) -> "$name=$value".toByteArray(Charsets.UTF_8) }.toTypedArray()
                    val handles = NativePty.open(path.toByteArray(Charsets.UTF_8), environment, columns, rows)
                    try {
                        LocalShellConnection(handles).also { opened = it }
                    } catch (failure: Throwable) {
                        NativePty.terminate(handles[0], handles[2])
                        NativePty.reap(handles[2])
                        NativePty.dispose(handles[0], handles[1])
                        throw failure
                    }
                }
            } catch (failure: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) { opened?.close() }
                throw failure
            }
        }

        private fun shellStartup(context: Context): File {
            val target = File(context.cacheDir, "terminal-shellrc")
            val staging = File.createTempFile("terminal-shellrc-", ".tmp", context.cacheDir)
            try {
                context.assets.open("terminal/shellrc").use { input ->
                    staging.outputStream().use { output -> input.copyTo(output) }
                }
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
                return target
            } finally { staging.delete() }
        }

        private fun checkBounds(bytes: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
        }
    }
}

internal object NativePty {
    init { System.loadLibrary("luna_pty") }

    external fun open(directory: ByteArray, environment: Array<ByteArray>, columns: Int, rows: Int): IntArray
    external fun read(master: Int, cancellation: Int, bytes: ByteArray, offset: Int, length: Int): Int
    external fun write(master: Int, cancellation: Int, bytes: ByteArray, offset: Int, length: Int): Int
    external fun resize(master: Int, columns: Int, rows: Int)
    external fun cancel(cancellation: Int)
    external fun terminate(master: Int, pid: Int)
    external fun awaitExit(pid: Int): Int
    external fun reap(pid: Int)
    external fun dispose(master: Int, cancellation: Int)
}
