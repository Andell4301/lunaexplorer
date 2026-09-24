package com.lunaexplorer.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lunaexplorer.app.MainActivity
import com.lunaexplorer.app.R
import com.lunaexplorer.core.OperationRequest

internal class ProcedureNotifications(private val context: Context) {
    fun post(request: OperationRequest, title: String, status: String, detail: String) {
        if (if (status == "SUCCEEDED") !request.notifyOnSuccess else !request.notifyOnFailure) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Procedure results", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(context, MainActivity::class.java).apply {
            action = ACTION
            data = Uri.Builder().scheme("luna").authority("procedure-run").appendPath(request.id).build()
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_operation).setContentTitle(title)
            .setContentText(status.lowercase().replaceFirstChar { it.uppercase() })
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).build()
        try { manager.notify(request.id, 0, notification) } catch (_: SecurityException) { }
    }

    companion object {
        const val ACTION = "com.lunaexplorer.app.PROCEDURE_RESULT"
        private const val CHANNEL = "procedure_results"
    }
}
