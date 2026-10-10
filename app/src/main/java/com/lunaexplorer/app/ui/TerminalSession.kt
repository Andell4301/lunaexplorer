package com.lunaexplorer.app.ui

import com.lunaexplorer.app.terminal.TerminalConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class TerminalContext(val accountId: String?, val directory: String, val paths: List<String>)

internal class TerminalSession(
    parent: CoroutineScope,
    val title: String,
    val accountId: String?,
    private val render: suspend (ByteArray) -> Unit,
    connect: suspend (Int, Int) -> TerminalConnection,
) : Closeable {
    private val lifetime = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + lifetime)
    private val connection = AtomicReference<TerminalConnection?>()
    private val outgoing = Channel<ByteArray>(64)
    private val size = MutableStateFlow<Pair<Int, Int>?>(null)
    private val _status = MutableStateFlow("Connecting")
    val status = _status.asStateFlow()
    private val _running = MutableStateFlow(true)
    val running = _running.asStateFlow()

    init {
        lifetime.invokeOnCompletion {
            outgoing.close()
            _running.value = false
        }
        scope.launch {
            var opened: TerminalConnection? = null
            try {
                val initial = withTimeoutOrNull(15_000) { size.filterNotNull().first() }
                    ?: throw IOException("Terminal did not start")
                opened = connect(initial.first, initial.second)
                connection.set(opened)
                ensureActive()
                _status.value = "Connected"
                val terminal = opened
                coroutineScope {
                    val closing = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally {
                            withContext(NonCancellable + Dispatchers.IO) { release(terminal) }
                        }
                    }
                    val writer = launch(Dispatchers.IO) {
                        try {
                            for (bytes in outgoing) {
                                terminal.output.write(bytes)
                                terminal.output.flush()
                            }
                        } catch (error: Exception) {
                            ensureActive()
                            throw error
                        }
                    }
                    val resizing = launch(Dispatchers.IO) {
                        try { size.filterNotNull().collect { (columns, rows) -> terminal.resize(columns, rows) } }
                        catch (error: Exception) { ensureActive(); throw error }
                    }
                    val ended = AtomicBoolean()
                    val errors = terminal.error?.let { launch { pump(it, ended::get) } }
                    try {
                        pump(terminal.input)
                        ended.set(true)
                        writer.cancel()
                        resizing.cancel()
                        withContext(NonCancellable + Dispatchers.IO) { release(terminal) }
                        errors?.join()
                    } finally {
                        closing.cancel()
                        writer.cancel()
                        resizing.cancel()
                        errors?.cancel()
                    }
                }
                _status.value = terminal.exitStatus?.let { "Exited ($it)" } ?: "Disconnected"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ensureActive()
                _status.value = error.message ?: "Terminal disconnected"
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { opened?.let(::release) }
                outgoing.close()
                _running.value = false
                lifetime.complete()
            }
        }
    }

    private suspend fun pump(input: InputStream, ending: () -> Boolean = { false }) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(16_384)
        while (true) {
            ensureActive()
            val count = try { input.read(buffer) } catch (error: Exception) {
                if (error is CancellationException) throw error
                ensureActive()
                if (ending()) break else throw error
            }
            if (count < 0) break
            if (count > 0) render(buffer.copyOf(count))
        }
    }

    fun input(bytes: ByteArray) {
        if (!_running.value) return
        if (bytes.size > 1_048_576 || outgoing.trySend(bytes.copyOf()).isFailure) {
            _status.value = "Input buffer full"
            close()
        }
    }

    fun resize(columns: Int, rows: Int) { size.value = columns.coerceIn(2, 1000) to rows.coerceIn(2, 1000) }

    fun fail(message: String) {
        _status.value = message
        close()
    }

    private fun release(terminal: TerminalConnection) {
        if (connection.compareAndSet(terminal, null)) closeConnection(terminal)
    }

    override fun close() {
        if (_status.value == "Connected" || _status.value == "Connecting") _status.value = "Disconnected"
        scope.cancel()
        outgoing.close()
        _running.value = false
        connection.getAndSet(null)?.let { terminal ->
            // Closing the transport unblocks a reader that coroutine cancellation alone cannot wake.
            CoroutineScope(Dispatchers.IO).launch { closeConnection(terminal) }
        }
    }
}

private fun closeConnection(connection: TerminalConnection) {
    try { connection.close() }
    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { }
}
