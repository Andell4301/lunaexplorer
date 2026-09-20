package com.lunaexplorer.app.debug

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Nothing is redacted; callers must keep secrets out of messages and exceptions.
class DebugLog(
    private val directory: File,
    val maxChars: Int = DEFAULT_MAX_CHARS,
    private val mirror: ((Level, String) -> Unit)? = null,
) {
    enum class Level(val letter: Char) { DEBUG('D'), INFO('I'), WARN('W'), ERROR('E') }

    class Snapshot(val text: String, val sequence: Long, val epoch: Int)

    class Update(val text: String, val sequence: Long)

    private val lock = Any()
    private val fileLock = Any()
    private val entries = ArrayDeque<String>()
    private var chars = 0
    private var sequence = 0L
    private var epoch = 0
    private var loaded = false
    private val pending = StringBuilder()
    private var flushScheduled = false
    @Volatile private var closed = false
    private var fileChars = 0L

    private val logFile = File(directory, "luna.log")
    private val marker = File(directory, "recording")
    private val writer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "luna-log-writer").apply { isDaemon = true }
    }

    private val _recording = MutableStateFlow(marker.exists())
    val recording: StateFlow<Boolean> = _recording.asStateFlow()
    val enabled: Boolean get() = _recording.value

    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    init {
        if (enabled) writer.execute { synchronized(lock) { ensureLoadedLocked() } }
    }

    fun log(level: Level, tag: String, message: String, error: Throwable? = null) {
        if (!enabled) return
        val entry = format(level, tag, message, error)
        synchronized(lock) {
            ensureLoadedLocked()
            entries.addLast(entry)
            chars += entry.length
            sequence++
            while (chars > maxChars && entries.size > 1) chars -= entries.removeFirst().length
            pending.append(entry)
            if (!flushScheduled && !closed) {
                flushScheduled = true
                runCatching { writer.schedule({ flush() }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS) }
                    .onFailure { flushScheduled = false }
            }
            _changes.value = _changes.value + 1
        }
        mirror?.let { runCatching { it(level, entry) } }
    }

    fun setRecording(on: Boolean, about: String? = null) {
        if (on == enabled) return
        if (on) {
            runCatching { directory.mkdirs(); marker.createNewFile() }
            _recording.value = true
            log(Level.INFO, TAG, "Recording started" + (about?.let { " · $it" } ?: ""))
        } else {
            log(Level.INFO, TAG, "Recording stopped")
            _recording.value = false
            runCatching { marker.delete() }
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        ensureLoadedLocked()
        Snapshot(entries.joinToString(""), sequence, epoch)
    }

    /** Entries after [sequence], or null if the caller must take a new [snapshot]. */
    fun since(sequence: Long, epoch: Int): Update? = synchronized(lock) {
        ensureLoadedLocked()
        if (epoch != this.epoch) return null
        val first = this.sequence - entries.size
        if (sequence < first || sequence > this.sequence) return null
        val count = (this.sequence - sequence).toInt()
        val text = buildString { for (index in entries.size - count until entries.size) append(entries[index]) }
        Update(text, this.sequence)
    }

    fun clear() {
        // Same lock order as flush; holding fileLock also stops a flush between the reset and the delete.
        synchronized(fileLock) {
            synchronized(lock) {
                loaded = true
                entries.clear()
                chars = 0
                sequence = 0
                epoch++
                pending.setLength(0)
                fileChars = 0
                _changes.value = _changes.value + 1
            }
            runCatching { logFile.delete() }
        }
    }

    /** Synchronous, so the crash handler can call it. */
    fun flush() {
        synchronized(fileLock) {
            val (chunk, whole) = synchronized(lock) {
                flushScheduled = false
                val text = pending.toString()
                pending.setLength(0)
                fileChars += text.length
                if (fileChars > maxChars * 2L) {
                    fileChars = chars.toLong()
                    text to entries.joinToString("")
                } else text to null
            }
            runCatching {
                if (whole == null && chunk.isEmpty()) return@runCatching
                directory.mkdirs()
                if (whole != null) {
                    val staged = File(directory, "luna.log.tmp")
                    staged.writeText(whole)
                    if (!staged.renameTo(logFile)) { logFile.delete(); staged.renameTo(logFile) }
                } else if (chunk.isNotEmpty()) {
                    FileOutputStream(logFile, true).use { it.write(chunk.toByteArray()) }
                }
            }
        }
    }

    /** Flushes and stops the writer. Later entries stay in memory only. */
    fun close() {
        closed = true
        writer.shutdownNow()
        runCatching { writer.awaitTermination(1, TimeUnit.SECONDS) }
        flush()
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val text = runCatching { if (logFile.isFile) logFile.readText() else "" }.getOrDefault("")
        fileChars = text.length.toLong()
        val kept = if (text.length <= maxChars) text else {
            val cut = text.length - maxChars
            text.indexOf('\n', cut).let { if (it < 0) text.substring(cut) else text.substring(it + 1) }
        }
        var start = 0
        while (start < kept.length) {
            val end = kept.indexOf('\n', start).let { if (it < 0) kept.length else it + 1 }
            val line = kept.substring(start, end).let { if (it.endsWith('\n')) it else it + "\n" }
            entries.addLast(line)
            chars += line.length
            start = end
        }
        sequence = entries.size.toLong()
    }

    private fun format(level: Level, tag: String, message: String, error: Throwable?): String = buildString {
        append(LocalDateTime.now().format(STAMP)).append(' ').append(level.letter).append(' ')
        append(tag).append(" [").append(Thread.currentThread().name).append("]: ")
        append(readable(message.take(maxChars / 5)))
        if (error != null) append('\n').append(readable(trace(error)))
        append('\n')
    }

    /** Exceptions already written out, so a wrapper logged later does not repeat their trace. */
    private val traced: MutableMap<Throwable, Unit> = Collections.synchronizedMap(WeakHashMap())

    private fun trace(error: Throwable): String {
        val chain = generateSequence(error) { current -> current.cause?.takeIf { it !== current } }.take(32).toList()
        val already = chain.firstOrNull { traced.containsKey(it) }
        if (already != null) {
            return "    ${summary(error)}" + (if (already !== error) ", from ${summary(already)}" else "") + " (trace above)"
        }
        chain.forEach { traced[it] = Unit }
        return error.stackTraceToString().trimEnd().take(maxChars / 5)
    }

    private fun summary(error: Throwable) = "${error.javaClass.name}: ${error.message}"

    /** Replaces control characters other than newline and tab; NodeRef keys contain NUL separators. */
    private fun readable(text: String): String =
        if (text.none { it < ' ' && it != '\n' && it != '\t' }) text
        else buildString(text.length) { text.forEach { append(if (it < ' ' && it != '\n' && it != '\t') '|' else it) } }

    class Clip(val text: String, val lines: Int, val totalLines: Int)

    private class CrashRecorder(private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching {
                installed?.let { log ->
                    log.log(Level.ERROR, TAG, "Crashed on ${thread.name}", error)
                    log.flush()
                }
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else { System.err.print("Exception in thread \"${thread.name}\" "); error.printStackTrace() }
        }
    }

    companion object {
        private const val TAG = "Luna"
        /** As UTF-16 this stays under the 1 MB Binder transaction limit. */
        const val CLIPBOARD_CHARS = 250_000
        /** No larger than [CLIPBOARD_CHARS], so the whole log can be copied. */
        const val DEFAULT_MAX_CHARS = CLIPBOARD_CHARS
        private const val FLUSH_DELAY_MS = 300L
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

        @Volatile var installed: DebugLog? = null

        fun install(context: Context): DebugLog {
            // Must be set before SLF4J's first logger lookup.
            System.setProperty("slf4j.provider", LunaSlf4jProvider::class.java.name)
            val log = DebugLog(File(context.applicationContext.filesDir, "logs"), mirror = ::logcat)
            installed?.close()
            installed = log
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous !is CrashRecorder) Thread.setDefaultUncaughtExceptionHandler(CrashRecorder(previous))
            if (log.enabled) {
                val about = about(context)
                log.writer.execute { log.log(Level.INFO, TAG, "Started · $about") }
            }
            return log
        }

        fun about(context: Context): String {
            val version = runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                    @Suppress("DEPRECATION") info.versionCode.toLong()
                }
                "Luna ${info.versionName} ($code)"
            }.getOrDefault("Luna")
            return "$version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"
        }

        fun tailForClipboard(text: String, limit: Int = CLIPBOARD_CHARS): Clip {
            val total = lineCount(text)
            if (text.length <= limit) return Clip(text, total, total)
            val cut = text.length - limit
            val start = text.indexOf('\n', cut).let { if (it < 0) cut else it + 1 }
            val tail = text.substring(start)
            return Clip(tail, lineCount(tail), total)
        }

        private fun lineCount(text: String): Int =
            text.count { it == '\n' } + if (text.isNotEmpty() && !text.endsWith('\n')) 1 else 0

        private fun logcat(level: Level, entry: String) {
            val priority = when (level) {
                Level.DEBUG -> Log.DEBUG
                Level.INFO -> Log.INFO
                Level.WARN -> Log.WARN
                Level.ERROR -> Log.ERROR
            }
            Log.println(priority, TAG, entry.trimEnd())
        }

        inline fun d(tag: String, message: () -> String) = emit(Level.DEBUG, tag, null, message)
        inline fun i(tag: String, message: () -> String) = emit(Level.INFO, tag, null, message)
        inline fun w(tag: String, error: Throwable? = null, message: () -> String) = emit(Level.WARN, tag, error, message)

        inline fun emit(level: Level, tag: String, error: Throwable?, message: () -> String) {
            val log = installed ?: return
            if (log.enabled) log.log(level, tag, message(), error)
        }

        fun millisSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000
    }
}
