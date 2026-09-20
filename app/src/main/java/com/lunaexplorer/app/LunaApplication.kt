package com.lunaexplorer.app

import android.app.Application
import android.app.NotificationManager
import com.lunaexplorer.app.debug.DebugLog
import com.lunaexplorer.app.playback.PLAYBACK_NOTIFICATION_ID

class LunaApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // Install logging before creating providers or their libraries.
        DebugLog.install(this)
        // No player survives the process, so a playback notification still showing is stale.
        getSystemService(NotificationManager::class.java)?.cancel(PLAYBACK_NOTIFICATION_ID)
    }
}
