package com.muswall.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * Lightweight application startup. Python/Chaquopy is initialized lazily
 * only when wallpaper rendering is actually requested. This prevents
 * startup crashes and reduces launch time/memory usage.
 */
class MusWallApp : Application() {
    override fun onCreate() {
        super.onCreate()
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
