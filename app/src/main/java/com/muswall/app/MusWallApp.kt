package com.muswall.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.muswall.app.python.PythonBridge

/**
 * Lightweight application startup.
 * Only stores the application context for lazy Python rendering; Python itself
 * is NOT started here. This also makes the media service independent of whether
 * MainActivity has ever been opened.
 */
class MusWallApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PythonBridge.initialize(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "muswall_service_channel",
                "MusWall Media Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
