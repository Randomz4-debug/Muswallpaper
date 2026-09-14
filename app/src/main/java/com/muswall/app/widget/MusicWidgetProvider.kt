package com.muswall.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.View
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

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) {
        updateAll(context, appWidgetManager, intArrayOf(appWidgetId))
    }

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
            MediaNotificationListenerService.ACTION_WALLPAPER_APPLIED,
            MediaNotificationListenerService.ACTION_WIDGET_CHANGED,
            MediaNotificationListenerService.ACTION_SETTINGS_CHANGED -> updateAll(context)
        }
    }

    private fun updateAll(context: Context, manager: AppWidgetManager = AppWidgetManager.getInstance(context), ids: IntArray? = null) {
        val widgetIds = ids ?: manager.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
        val prefs = PreferencesManager.getInstance(context)
        widgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_music)
            val bg = runCatching { Color.parseColor(prefs.widgetBackgroundColor) }.getOrDefault(Color.rgb(23, 21, 31))
            views.setInt(R.id.widgetRoot, "setBackgroundColor", Color.argb((255 * prefs.widgetOpacity / 100f).toInt(), Color.red(bg), Color.green(bg), Color.blue(bg)))

            val title = prefs.lastTrackTitle.ifBlank { prefs.widgetEmptyTitle }
            val artist = prefs.lastArtist.ifBlank { "MusWall" }
            views.setTextViewText(R.id.widgetTitle, title)
            views.setTextViewText(R.id.widgetArtist, artist)
            views.setTextViewText(R.id.widgetCustomText, prefs.widgetCustomText)
            views.setTextViewText(R.id.widgetTime, prefs.widgetCustomTimeLabel)
            views.setTextViewTextSize(R.id.widgetTitle, android.util.TypedValue.COMPLEX_UNIT_SP, prefs.widgetTitleSize.toFloat())
            views.setTextViewTextSize(R.id.widgetArtist, android.util.TypedValue.COMPLEX_UNIT_SP, prefs.widgetArtistSize.toFloat())
            views.setTextViewTextSize(R.id.widgetCustomText, android.util.TypedValue.COMPLEX_UNIT_SP, prefs.widgetCustomTextSize.toFloat())
            val textColor = runCatching { Color.parseColor(prefs.widgetTextColor) }.getOrDefault(Color.WHITE)
            val secondary = runCatching { Color.parseColor(prefs.widgetSecondaryColor) }.getOrDefault(Color.LTGRAY)
            views.setTextColor(R.id.widgetTitle, textColor)
            views.setTextColor(R.id.widgetArtist, secondary)
            views.setTextColor(R.id.widgetCustomText, secondary)
            views.setTextColor(R.id.widgetTime, secondary)

            val art = File(context.filesDir, "muswall_last_artwork.jpg")
            if (prefs.widgetShowArtwork && art.exists()) {
                BitmapFactory.decodeFile(art.absolutePath)?.let { views.setImageViewBitmap(R.id.widgetArt, it) }
                views.setViewVisibility(R.id.widgetArt, View.VISIBLE)
            } else views.setViewVisibility(R.id.widgetArt, View.GONE)

            views.setViewVisibility(R.id.widgetTitle, if (prefs.widgetShowTitle) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widgetArtist, if (prefs.widgetShowArtist) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widgetControls, if (prefs.widgetShowControls) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widgetProgress, if (prefs.widgetShowProgress) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widgetCustomText, if (prefs.widgetShowCustomText) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widgetTime, if (prefs.widgetShowTime) View.VISIBLE else View.GONE)

            val playIcon = if (prefs.liveMusicPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            views.setImageViewResource(R.id.widgetPlay, playIcon)
            views.setOnClickPendingIntent(R.id.widgetRoot, pending(context, Intent(context, MainActivity::class.java), id, 0))
            views.setOnClickPendingIntent(R.id.widgetPlay, pending(context, Intent(ACTION_WIDGET_PLAY), id, 1))
            views.setOnClickPendingIntent(R.id.widgetNext, pending(context, Intent(ACTION_WIDGET_NEXT), id, 2))
            views.setOnClickPendingIntent(R.id.widgetPrev, pending(context, Intent(ACTION_WIDGET_PREV), id, 3))
            manager.updateAppWidget(id, views)
        }
    }

    private fun pending(context: Context, intent: Intent, id: Int, request: Int): PendingIntent =
        if (intent.action?.startsWith("com.muswall.app.widget") == true) {
            PendingIntent.getBroadcast(context, id * 10 + request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(context, id * 10 + request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
}
