package com.muswall.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import com.muswall.app.R
import com.muswall.app.data.PreferencesManager
import com.muswall.app.service.MediaNotificationListenerService
import com.muswall.app.ui.MainActivity
import java.io.File

class MusicWidgetProvider : AppWidgetProvider() {
    companion object {
        const val ACTION_WIDGET_PLAY = "com.muswall.app.widget.PLAY"
        const val ACTION_WIDGET_NEXT = "com.muswall.app.widget.NEXT"
        const val ACTION_WIDGET_PREV = "com.muswall.app.widget.PREV"
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAll(context, manager, ids)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_PLAY, ACTION_WIDGET_NEXT, ACTION_WIDGET_PREV -> {
                val action = when (intent.action) {
                    ACTION_WIDGET_NEXT -> MediaNotificationListenerService.ACTION_WIDGET_NEXT
                    ACTION_WIDGET_PREV -> MediaNotificationListenerService.ACTION_WIDGET_PREV
                    else -> MediaNotificationListenerService.ACTION_WIDGET_PLAY_PAUSE
                }
                context.sendBroadcast(Intent(action).setPackage(context.packageName))
                updateAll(context)
            }
            MediaNotificationListenerService.ACTION_TRACK_CHANGED,
            MediaNotificationListenerService.ACTION_PLAYBACK_STATE_CHANGED,
            MediaNotificationListenerService.ACTION_WALLPAPER_APPLIED -> updateAll(context)
        }
    }

    private fun updateAll(context: Context, manager: AppWidgetManager = AppWidgetManager.getInstance(context), ids: IntArray? = null) {
        val widgetIds = ids ?: manager.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
        widgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_music)
            val prefs = PreferencesManager.getInstance(context)
            views.setTextViewText(R.id.widgetTitle, prefs.lastTrackTitle.ifBlank { "No music playing" })
            views.setTextViewText(R.id.widgetArtist, prefs.lastArtist.ifBlank { "MusWall" })
            val art = File(context.filesDir, "muswall_last_artwork.jpg")
            if (art.exists()) BitmapFactory.decodeFile(art.absolutePath)?.let { views.setImageViewBitmap(R.id.widgetArt, it) }
            views.setOnClickPendingIntent(R.id.widgetRoot, pending(context, Intent(context, MainActivity::class.java), id, 0))
            views.setOnClickPendingIntent(R.id.widgetPlay, pending(context, Intent(ACTION_WIDGET_PLAY), id, 1))
            views.setOnClickPendingIntent(R.id.widgetNext, pending(context, Intent(ACTION_WIDGET_NEXT), id, 2))
            views.setOnClickPendingIntent(R.id.widgetPrev, pending(context, Intent(ACTION_WIDGET_PREV), id, 3))
            manager.updateAppWidget(id, views)
        }
    }

    private fun pending(context: Context, intent: Intent, id: Int, request: Int): PendingIntent = PendingIntent.getBroadcast(context, id * 10 + request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
