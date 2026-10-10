package com.lunaexplorer.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lunaexplorer.app.terminal.TerminalConnection
import com.lunaexplorer.app.terminal.TerminalService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable

internal class LiveTerminal(
    val session: TerminalSession,
    val surface: TerminalSurfaceController,
    private val protection: Closeable,
) : Closeable {
    override fun close() {
        session.close()
        surface.close()
        protection.close()
    }
}

internal class TerminalWorkspaceModel(application: Application) : AndroidViewModel(application) {
    var active by mutableStateOf<LiveTerminal?>(null)
        private set

    fun start(
        surface: TerminalSurfaceController,
        title: String,
        accountId: String?,
        connect: suspend (Int, Int) -> TerminalConnection,
    ) {
        check(active == null)
        val protection = try { TerminalService.protect(getApplication(), title) }
        catch (error: Exception) { surface.close(); throw error }
        val session = TerminalSession(viewModelScope, title, accountId, surface::write) { columns, rows ->
            protection.ready.await()
            connect(columns, rows)
        }
        protection.onDisconnect = session::close
        surface.onFailure = session::fail
        active = LiveTerminal(session, surface, protection)
        viewModelScope.launch {
            try {
                try {
                    if (withTimeoutOrNull(5_000) { protection.ready.await(); true } != true) {
                        session.fail("Could not keep terminal running")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    ensureActive()
                    session.fail(error.message ?: "Could not keep terminal running")
                }
                session.running.first { !it }
            } finally { protection.close() }
        }
    }

    fun close(terminal: LiveTerminal) {
        if (active !== terminal) return
        active = null
        terminal.close()
    }

    override fun onCleared() {
        active?.let(::close)
    }
}
