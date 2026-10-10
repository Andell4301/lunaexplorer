package com.lunaexplorer.app.terminal

import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class TerminalServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val leases = mutableListOf<TerminalService.Lease>()
    private var controller: ServiceController<TerminalService>? = null

    private fun protect() = TerminalService.protect(context, "server").also { leases.add(it) }
    private fun service() = Robolectric.buildService(TerminalService::class.java).create()
        .also { controller = it }.get()

    @After fun cleanup() {
        leases.forEach { it.close() }
        controller?.destroy()
    }

    @Test fun `a connection waits until the service is foreground and ends its notification on close`() = runBlocking {
        val lease = protect()
        assertFalse(lease.ready.isCompleted)
        val started = shadowOf(context).nextStartedService
        val service = service()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(started, 0, 1))
        lease.ready.await()
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundServiceType)
        val notification = shadowOf(service).lastForegroundNotification
        assertNotNull(notification)
        assertEquals(TerminalService.ACTION_OPEN, shadowOf(notification.contentIntent).savedIntent.action)
        assertFalse(shadowOf(service).isStoppedBySelf)
        lease.close()
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `a notification disconnects its session and stale notification cannot stop its replacement`() {
        val first = protect()
        var firstClosed = 0
        first.onDisconnect = { firstClosed++ }
        val service = service()
        service.onStartCommand(shadowOf(context).nextStartedService, 0, 1)
        val oldDisconnect = shadowOf(shadowOf(service).lastForegroundNotification.actions.single().actionIntent).savedIntent
        service.onStartCommand(oldDisconnect, 0, 2)
        assertEquals(1, firstClosed)
        val second = protect()
        var secondClosed = 0
        second.onDisconnect = { secondClosed++ }
        service.onStartCommand(shadowOf(context).nextStartedService, 0, 3)
        service.onStartCommand(oldDisconnect, 0, 4)
        first.close()
        assertEquals(0, secondClosed)
        assertTrue(second.ready.isCompleted)
        val disconnect = shadowOf(shadowOf(service).lastForegroundNotification.actions.single().actionIntent).savedIntent
        service.onStartCommand(disconnect, 0, 5)
        assertEquals(1, secondClosed)
    }

    @Test fun `permission grant refreshes the current notification without replacing its session`() {
        val lease = protect()
        var disconnected = 0
        lease.onDisconnect = { disconnected++ }
        val service = service()
        service.onStartCommand(shadowOf(context).nextStartedService, 0, 1)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val originalAction = shadowOf(shadowOf(service).lastForegroundNotification.actions.single().actionIntent).savedIntent
        notifications.cancelAll()
        TerminalService.refreshNotification()
        val refreshed = shadowOf(notifications).allNotifications.single()
        val refreshedAction = shadowOf(refreshed.actions.single().actionIntent).savedIntent
        assertEquals(originalAction.data, refreshedAction.data)
        assertEquals(0, disconnected)
        assertTrue(lease.ready.isCompleted)
        service.onStartCommand(refreshedAction, 0, 2)
        assertEquals(1, disconnected)
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
        TerminalService.refreshNotification()
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    @Test fun `permission grant while a replacement awaits promotion does not revive the old service`() {
        val first = protect()
        val service = service()
        service.onStartCommand(shadowOf(context).nextStartedService, 0, 1)
        first.close()
        val second = protect()
        val notifications = context.getSystemService(NotificationManager::class.java)
        TerminalService.refreshNotification()
        assertFalse(second.ready.isCompleted)
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
        service.onStartCommand(shadowOf(context).nextStartedService, 0, 2)
        assertTrue(second.ready.isCompleted)
        assertEquals(1, shadowOf(notifications).allNotifications.size)
    }

    @Test fun `canceling before Android starts the service never leaves a foreground session`() {
        val lease = protect()
        val pending = shadowOf(context).nextStartedService
        lease.close()
        val service = service()
        service.onStartCommand(pending, 0, 1)
        assertTrue(lease.ready.isCancelled)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `destroying the previous service after replacement starts leaves its successor alive`() = runBlocking {
        val first = protect()
        val old = service()
        old.onStartCommand(shadowOf(context).nextStartedService, 0, 1)
        first.close()
        val second = protect()
        var secondClosed = 0
        second.onDisconnect = { secondClosed++ }
        val pending = shadowOf(context).nextStartedService
        controller!!.destroy()
        assertEquals(0, secondClosed)
        assertFalse(second.ready.isCompleted)
        val current = service()
        current.onStartCommand(pending, 0, 2)
        second.ready.await()
        assertEquals(0, secondClosed)
        assertFalse(shadowOf(current).isStoppedBySelf)
    }

    @Test fun `refused foreground promotion fails startup and releases the service`() = runBlocking {
        val lease = protect()
        val service = service()
        shadowOf(service).setThrowInStartForeground(SecurityException("refused"))
        service.onStartCommand(shadowOf(context).nextStartedService, 0, 1)
        var failed = false
        try { lease.ready.await() } catch (_: IllegalStateException) { failed = true }
        assertTrue(failed)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `destroying foreground service disconnects the live session without restarting it`() {
        val lease = protect()
        var closed = 0
        lease.onDisconnect = { closed++ }
        val service = service()
        service.onStartCommand(shadowOf(context).nextStartedService, 0, 1)
        controller!!.destroy()
        controller = null
        assertEquals(1, closed)
        val restarted = service()
        assertEquals(Service.START_NOT_STICKY, restarted.onStartCommand(null, 0, 2))
        assertTrue(shadowOf(restarted).isStoppedBySelf)
    }
}
