package com.lunaexplorer.app.terminal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lunaexplorer.app.MainActivity
import com.lunaexplorer.app.R
import kotlinx.coroutines.CompletableDeferred
import java.io.Closeable
import java.util.UUID

class TerminalService : Service() {
    private var protected: Lease? = null
    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Terminal", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            active?.takeIf { it.id == intent.getStringExtra(EXTRA_SESSION) }?.disconnect()
            if (active == null) stopCurrent()
        } else promote()
        return START_NOT_STICKY
    }

    private fun promote() {
        val lease = active
        protected = lease
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).setAction(ACTION_OPEN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_operation)
            .setContentTitle(lease?.title ?: "Terminal")
            .setContentText("Terminal running")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (lease != null) {
            val disconnect = PendingIntent.getService(this, 0,
                Intent(this, TerminalService::class.java).setAction(ACTION_DISCONNECT)
                    .setData(android.net.Uri.parse("luna-terminal:${lease.id}"))
                    .putExtra(EXTRA_SESSION, lease.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            notification.addAction(0, "Disconnect", disconnect)
        }
        try {
            // A cancelled start still has to promote before stopping.
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(NOTIFICATION_ID, notification.build())
            lease?.ready?.complete(Unit)
            if (lease == null) stopCurrent()
        } catch (_: RuntimeException) {
            lease?.ready?.completeExceptionally(IllegalStateException("Could not keep terminal running"))
            lease?.disconnect()
            stopCurrent()
        }
    }

    private fun stopCurrent() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        active?.disconnect()
        stopCurrent()
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            protected?.takeIf { active === it }?.disconnect()
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
        super.onDestroy()
    }

    internal class Lease(private val context: Context, val title: String) : Closeable {
        val id = UUID.randomUUID().toString()
        val ready = CompletableDeferred<Unit>()
        var onDisconnect: () -> Unit = {}

        fun disconnect() {
            val callback = onDisconnect
            close()
            callback()
        }

        override fun close() {
            onDisconnect = {}
            ready.cancel()
            if (active !== this) return
            active = null
            instance?.stopCurrent() ?: context.stopService(Intent(context, TerminalService::class.java))
        }
    }

    companion object {
        const val ACTION_OPEN = "com.lunaexplorer.app.OPEN_TERMINAL"
        private const val ACTION_DISCONNECT = "com.lunaexplorer.app.DISCONNECT_TERMINAL"
        private const val EXTRA_SESSION = "session"
        private const val CHANNEL = "terminal"
        private const val NOTIFICATION_ID = 105
        private var instance: TerminalService? = null
        private var active: Lease? = null

        fun refreshNotification() {
            val service = instance ?: return
            val lease = active ?: return
            if (service.protected === lease && lease.ready.isCompleted && !lease.ready.isCancelled) service.promote()
        }

        internal fun protect(context: Context, title: String): Lease {
            check(active == null)
            val application = context.applicationContext
            val lease = Lease(application, title)
            active = lease
            try {
                ContextCompat.startForegroundService(application, Intent(application, TerminalService::class.java))
            } catch (_: RuntimeException) {
                lease.close()
                throw IllegalStateException("Could not keep terminal running")
            }
            return lease
        }
    }
}
