package com.lunaexplorer.app.work

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import com.lunaexplorer.app.MainActivity
import com.lunaexplorer.core.OperationRequest
import com.lunaexplorer.core.OperationType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ProcedureNotificationsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val notifications get() = ProcedureNotifications(context)

    @Before fun allowNotifications() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
    }

    @Test fun `success notifications follow their own preference`() {
        val request = OperationRequest(type = OperationType.PROCEDURE, notifyOnSuccess = true)

        notifications.post(request, "File invoices", "FAILED", "A source was missing")
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        notifications.post(request, "File invoices", "SUCCEEDED", "Three files moved")

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("File invoices", posted.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Three files moved", posted.extras.getString(Notification.EXTRA_BIG_TEXT))
    }

    @Test fun `failures partial runs and interruptions follow the failure preference`() {
        val request = OperationRequest(type = OperationType.PROCEDURE, notifyOnFailure = true)
        notifications.post(request, "File invoices", "SUCCEEDED", "Completed")
        assertTrue(shadowOf(manager).allNotifications.isEmpty())

        val statuses = listOf("FAILED", "PARTIAL", "CANCELLED", "INTERRUPTED")
        for (status in statuses) {
            notifications.post(request.copy(id = status), "File invoices", status, "Review the run")
        }

        assertEquals(statuses.size, shadowOf(manager).allNotifications.size)
        statuses.forEach { assertNotNull(shadowOf(manager).getNotification(it, 0)) }
    }

    @Test fun `disabled preferences produce no result notification`() {
        val request = OperationRequest(type = OperationType.PROCEDURE)

        notifications.post(request, "File invoices", "SUCCEEDED", "Completed")
        notifications.post(request, "File invoices", "FAILED", "A source was missing")

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun `denied notification permission leaves the run silent`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val request = OperationRequest(type = OperationType.PROCEDURE, notifyOnSuccess = true, notifyOnFailure = true)

        notifications.post(request, "File invoices", "SUCCEEDED", "Completed")
        notifications.post(request, "File invoices", "FAILED", "A source was missing")

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun `system notification blocking leaves the run silent`() {
        shadowOf(manager).setNotificationsEnabled(false)
        val request = OperationRequest(type = OperationType.PROCEDURE, notifyOnSuccess = true, notifyOnFailure = true)

        notifications.post(request, "File invoices", "SUCCEEDED", "Completed")
        notifications.post(request, "File invoices", "FAILED", "A source was missing")

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun `each run keeps a distinct notification opening the operation queue`() {
        val first = OperationRequest(id = "procedure:first:42", type = OperationType.PROCEDURE, notifyOnSuccess = true)
        val second = first.copy(id = "procedure:second:43")

        notifications.post(first, "First", "SUCCEEDED", "First result")
        notifications.post(second, "Second", "SUCCEEDED", "Second result")

        assertEquals(2, shadowOf(manager).allNotifications.size)
        for (request in listOf(first, second)) {
            val notification = shadowOf(manager).getNotification(request.id, 0)!!
            val intent = shadowOf(notification.contentIntent).savedIntent
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertEquals(ProcedureNotifications.ACTION, intent.action)
            assertEquals(request.id, intent.data?.lastPathSegment)
        }
    }

    @Test @Config(sdk = [30]) fun `older Android versions do not require notification permission`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val request = OperationRequest(type = OperationType.PROCEDURE, notifyOnSuccess = true)

        notifications.post(request, "File invoices", "SUCCEEDED", "Completed")

        assertEquals(1, shadowOf(manager).allNotifications.size)
    }
}
