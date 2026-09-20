package com.lunaexplorer.app.storage

import android.app.Application
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.Closeable
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ExternalStreamServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val uri get() = Uri.parse("content://${context.packageName}.stream/smb/movie")
    private val resources = mutableListOf<Closeable>()
    private val controllers = mutableListOf<ServiceController<out ExternalStreamService>>()

    private fun keep(resource: Closeable?): Closeable = requireNotNull(resource).also { resources.add(it) }

    private fun <T : ExternalStreamService> service(type: Class<T>): T =
        Robolectric.buildService(type).create().also { controllers.add(it) }.get()

    @After fun cleanup() {
        resources.asReversed().forEach { it.close() }
        shadowOf(Looper.getMainLooper()).idle()
        controllers.forEach { it.destroy() }
    }

    @Test fun `external launch waits until media service is actually foreground`() = runBlocking {
        val prepared = async(Dispatchers.Main.immediate) {
            ExternalStreamService.prepare(context, uri, "movie.mkv", "video/x-matroska", false)
        }
        assertFalse(prepared.isCompleted)
        val started = shadowOf(context).nextStartedService
        assertEquals(ExternalStreamService::class.java.name, started.component!!.className)
        val service = service(ExternalStreamService::class.java)
        assertFalse(prepared.isCompleted)
        service.onStartCommand(started, 0, 1)
        keep(prepared.await())
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, service.foregroundServiceType)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `unopened handoff stops the service and removes its notification`() = runBlocking {
        val prepared = async(Dispatchers.Main.immediate) {
            ExternalStreamService.prepare(context, uri, "movie.mkv", "video/x-matroska", false)
        }
        val service = service(ExternalStreamService::class.java)
        service.onStartCommand(shadowOf(context).nextStartedService, 0, 1)
        keep(prepared.await())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ExternalStreamLeases.HANDOFF_TIMEOUT_MS))
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertNull(ExternalStreamService.acquire(context, uri))
    }

    @Test fun `open reader keeps foreground service until final descriptor closes`() = runBlocking {
        val prepared = async(Dispatchers.Main.immediate) {
            ExternalStreamService.prepare(context, uri, "movie.mkv", "video/x-matroska", false)
        }
        val service = service(ExternalStreamService::class.java)
        service.onStartCommand(shadowOf(context).nextStartedService, 0, 1)
        keep(prepared.await())
        val descriptor = keep(ExternalStreamService.acquire(context, uri))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofHours(8))
        assertFalse(shadowOf(service).isStoppedBySelf)
        descriptor.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ExternalStreamLeases.REOPEN_GRACE_MS))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `failed foreground promotion fails handoff and releases its reservation`() = runBlocking {
        val prepared = async(Dispatchers.Main.immediate) {
            runCatching { ExternalStreamService.prepare(context, uri, "movie.mkv", "video/x-matroska", false) }
        }
        val service = service(ExternalStreamService::class.java)
        shadowOf(service).setThrowInStartForeground(SecurityException("not allowed"))
        service.onStartCommand(shadowOf(context).nextStartedService, 0, 1)
        assertTrue(prepared.await().exceptionOrNull() is SecurityException)
        assertNull(ExternalStreamService.acquire(context, uri))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `transfer timeout stops only transfer component while VLC reader stays foreground`() = runBlocking {
        val mediaPrepared = async(Dispatchers.Main.immediate) {
            ExternalStreamService.prepare(context, uri, "movie.mkv", "video/x-matroska", false)
        }
        val media = service(ExternalStreamService::class.java)
        media.onStartCommand(shadowOf(context).nextStartedService, 0, 1)
        keep(mediaPrepared.await())
        keep(ExternalStreamService.acquire(context, uri))

        val document = Uri.parse("content://${context.packageName}.stream/smb/document")
        val transferPrepared = async(Dispatchers.Main.immediate) {
            ExternalStreamService.prepare(context, document, "document.pdf", "application/pdf", true)
        }
        val transferIntent = shadowOf(context).nextStartedService
        assertEquals(ExternalTransferService::class.java.name, transferIntent.component!!.className)
        val transfer = service(ExternalTransferService::class.java)
        transfer.onStartCommand(transferIntent, 0, 2)
        keep(transferPrepared.await())
        keep(ExternalStreamService.acquire(context, document))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, transfer.foregroundServiceType)

        transfer.onTimeout(2, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(transfer).isStoppedBySelf)
        assertFalse(shadowOf(media).isStoppedBySelf)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, media.foregroundServiceType)
        assertNull(ExternalStreamService.acquire(context, document))
        keep(ExternalStreamService.acquire(context, uri))
        Unit
    }

    @Test fun `files served directly by Android do not start a Luna stream service`() = runBlocking {
        val direct = Uri.parse("content://${context.packageName}.files/movie.mkv")
        assertNull(ExternalStreamService.prepare(context, direct, "movie.mkv", "video/x-matroska", false))
        assertNull(shadowOf(context).nextStartedService)
    }
}
