package com.lunaexplorer.app.ui

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.lunaexplorer.app.terminal.TerminalConnection
import com.lunaexplorer.app.terminal.TerminalService
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class TerminalWorkspaceModelTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val store = ViewModelStore()
    private val workspace = TerminalWorkspaceModel(context).also { store.put("terminal", it) }
    private var controller: ServiceController<TerminalService>? = null

    @After fun cleanup() {
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
        controller?.destroy()
    }

    private fun service() = Robolectric.buildService(TerminalService::class.java).create()
        .also { controller = it }.get()

    @Test fun `startup waits for foreground promotion and clearing owner releases the connection`() {
        val connection = WaitingConnection()
        var opened = false
        workspace.start(TerminalSurfaceController(), "server", "id") { _, _ -> opened = true; connection }
        val live = requireNotNull(workspace.active)
        live.session.resize(80, 24)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(opened)
        val service = service()
        service.onStartCommand(shadowOf(context).nextStartedService, 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(opened)
        assertTrue(connection.reading.await(5, TimeUnit.SECONDS))
        assertSame(live, workspace.active)
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(connection.closed.await(5, TimeUnit.SECONDS))
        assertTrue(live.surface.isClosed)
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test fun `refused service promotion is visible and never connects the terminal`() {
        var opened = false
        workspace.start(TerminalSurfaceController(), "server", "id") { _, _ ->
            opened = true
            WaitingConnection()
        }
        val live = requireNotNull(workspace.active)
        val connecting = live.session.status.value
        live.session.resize(80, 24)
        val service = service()
        shadowOf(service).setThrowInStartForeground(SecurityException("refused"))
        service.onStartCommand(shadowOf(context).nextStartedService, 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(opened)
        assertFalse(live.session.running.value)
        assertTrue(live.session.status.value != connecting)
        assertFalse(live.surface.isClosed)
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test fun `late close cannot clear the new terminal`() {
        workspace.start(TerminalSurfaceController(), "old", "id") { _, _ -> WaitingConnection() }
        val old = requireNotNull(workspace.active)
        workspace.close(old)
        workspace.start(TerminalSurfaceController(), "new", "id") { _, _ -> WaitingConnection() }
        val current = requireNotNull(workspace.active)
        workspace.close(old)
        assertSame(current, workspace.active)
        assertFalse(current.surface.isClosed)
        assertTrue(current.session.running.value)
    }

    @Test fun `disconnect stops background protection and leaves the terminal output available`() {
        workspace.start(TerminalSurfaceController(), "server", "id") { _, _ -> WaitingConnection() }
        val live = requireNotNull(workspace.active)
        val previousStatus = live.session.status.value
        val service = service()
        service.onStartCommand(shadowOf(context).nextStartedService, 0, 1)
        val action = shadowOf(shadowOf(service).lastForegroundNotification.actions.single().actionIntent).savedIntent
        service.onStartCommand(action, 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(live.session.running.value)
        assertTrue(live.session.status.value != previousStatus)
        assertSame(live, workspace.active)
        assertFalse(live.surface.isClosed)
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    private class WaitingConnection : TerminalConnection {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override val input = object : InputStream() {
            override fun read(): Int {
                reading.countDown()
                check(closed.await(5, TimeUnit.SECONDS))
                return -1
            }
        }
        override val output = ByteArrayOutputStream()
        override val exitStatus: Int? = null
        override fun resize(columns: Int, rows: Int) = Unit
        override fun close() { closed.countDown() }
    }
}
