package com.lunaexplorer.app.storage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lunaexplorer.app.MainActivity
import com.lunaexplorer.app.R
import com.lunaexplorer.app.debug.DebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable

// Promote the service before launching another app; an open descriptor alone gives no foreground priority.
open class ExternalStreamService : Service() {
    internal open val purpose = ExternalStreamLeases.Purpose.MEDIA
    private val refresh = Runnable { updateForeground() }

    override fun onCreate() {
        super.onCreate()
        instances[purpose] = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Files open in other apps", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService requires a startForeground call even if the handoff was cancelled meanwhile.
        updateForeground(mustPromote = true)
        return START_NOT_STICKY
    }

    private fun updateForeground(mustPromote: Boolean = false) {
        main.removeCallbacks(refresh)
        val snapshot = leases.snapshot()
        val active = snapshot.active.filter { it.purpose == purpose }
        if (active.isEmpty() && !mustPromote) {
            DebugLog.i(TAG) { "No external readers or pending handoffs; stopping foreground streaming" }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_operation)
            .setContentTitle("File open in another app")
            .setContentText(active.firstOrNull()?.name ?: "Finishing file access")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).build()
        val notificationId = NOTIFICATION_ID + purpose.ordinal
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val type = if (purpose == ExternalStreamLeases.Purpose.MEDIA)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(notificationId, notification, type)
            } else startForeground(notificationId, notification)
        } catch (error: Exception) {
            DebugLog.w(TAG, error) { "Could not protect external streaming in the background" }
            active.forEach { ready.remove(it.id)?.completeExceptionally(error) }
            leases.clear(purpose)
            stopSelf()
            return
        }
        active.forEach { ready.remove(it.id)?.complete(Unit) }
        if (active.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            snapshot.nextExpiry?.let { main.postDelayed(refresh, (it - SystemClock.elapsedRealtime()).coerceAtLeast(1)) }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        DebugLog.w(TAG) { "Android ended foreground file transfer after its background time limit" }
        leases.snapshot().active.filter { it.purpose == purpose }.forEach {
            ready.remove(it.id)?.completeExceptionally(IllegalStateException("Android's background file transfer time limit was reached"))
        }
        leases.clear(purpose)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(refresh)
        if (instances[purpose] === this) instances.remove(purpose)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StreamService"
        private const val CHANNEL = "external_streams"
        private const val NOTIFICATION_ID = 102
        private val main = Handler(Looper.getMainLooper())
        private val leases = ExternalStreamLeases(SystemClock::elapsedRealtime)
        private val ready = mutableMapOf<Long, CompletableDeferred<Unit>>()
        private val instances = mutableMapOf<ExternalStreamLeases.Purpose, ExternalStreamService>()

        /** Returns a handle that cancels the handoff; close it only if launching the other app fails. */
        suspend fun prepare(context: Context, uri: Uri, name: String, mimeType: String, share: Boolean): Closeable? =
            withContext(Dispatchers.Main.immediate) {
                if (uri.authority != "${context.packageName}.stream") return@withContext null
                val media = !share && (mimeType.startsWith("video/") || mimeType.startsWith("audio/"))
                val id = leases.prepare(uri.toString(), name,
                    if (media) ExternalStreamLeases.Purpose.MEDIA else ExternalStreamLeases.Purpose.TRANSFER)
                val foreground = CompletableDeferred<Unit>()
                ready[id] = foreground
                val cancel = Closeable {
                    leases.cancel(id)
                    main.post {
                        ready.remove(id)?.cancel()
                        instances.values.toList().forEach { it.updateForeground() }
                    }
                }
                try {
                    val serviceClass = if (media) ExternalStreamService::class.java else ExternalTransferService::class.java
                    ContextCompat.startForegroundService(context, Intent(context, serviceClass))
                    withTimeout(5_000) { foreground.await() }
                    DebugLog.i(TAG) { "Foreground streaming ready for $name" }
                    cancel
                } catch (error: TimeoutCancellationException) {
                    cancel.close()
                    throw IllegalStateException("Android did not start background file access in time", error)
                } catch (error: Exception) {
                    cancel.close()
                    throw error
                }
            }

        fun acquire(context: Context, uri: Uri): Closeable? {
            if (uri.authority != "${context.packageName}.stream") return null
            val lease = leases.acquire(uri.toString()) { changed() }
            if (lease != null) changed()
            return lease
        }

        private fun changed() { main.post { instances.values.toList().forEach { it.updateForeground() } } }
    }
}

/** A separate dataSync service, so Android 15's dataSync time limit does not end media playback streams. */
class ExternalTransferService : ExternalStreamService() {
    internal override val purpose = ExternalStreamLeases.Purpose.TRANSFER
}
