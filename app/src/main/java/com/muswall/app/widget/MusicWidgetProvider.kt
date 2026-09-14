package com.muswall.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.TypedValue
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
        private var lastTickUpdate = 0L
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAll(context, manager, ids)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        updateAll(context, manager, intArrayOf(id))
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
            MediaNotificationListenerService.ACTION_LIVE_TICK -> {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastTickUpdate >= 900L) {
                    lastTickUpdate = now
                    updateAll(context)
                }
            }
        }
    }

    private fun updateAll(
        context: Context,
        manager: AppWidgetManager = AppWidgetManager.getInstance(context),
        ids: IntArray? = null
    ) {
        val widgetIds = ids ?: manager.getAppWidgetIds(
            ComponentName(context, MusicWidgetProvider::class.java)
        )
        if (widgetIds.isEmpty()) return

        val prefs = PreferencesManager.getInstance(context)
        val bg = parseColor(prefs.widgetBackgroundColor, Color.rgb(23, 21, 31))
        val bar = parseColor(prefs.widgetBarColor, Color.WHITE)
        val textColor = parseColor(prefs.widgetTextColor, Color.WHITE)
        val secondary = parseColor(prefs.widgetSecondaryColor, Color.LTGRAY)
        val opacity = (255f * prefs.widgetOpacity.coerceIn(0, 100) / 100f).toInt()
        val barOpacity = (255f * prefs.widgetBarOpacity.coerceIn(0, 100) / 100f).toInt()

        widgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_music)

            views.setInt(
                R.id.widgetRoot,
                "setBackgroundColor",
                Color.argb(opacity, Color.red(bg), Color.green(bg), Color.blue(bg))
            )
            views.setViewPadding(
                R.id.widgetRoot,
                prefs.widgetPadding,
                prefs.widgetPadding,
                prefs.widgetPadding,
                prefs.widgetPadding
            )
            views.setViewOutlinePreferredRadius(
                R.id.widgetRoot,
                prefs.widgetCornerRadius.toFloat(),
                TypedValue.COMPLEX_UNIT_DIP
            )

            views.setInt(
                R.id.widgetGlassBar,
                "setBackgroundColor",
                Color.argb(barOpacity, Color.red(bar), Color.green(bar), Color.blue(bar))
            )
            views.setViewVisibility(
                R.id.widgetGlassBar,
                if (prefs.widgetBarEnabled) View.VISIBLE else View.GONE
            )

            val title = prefs.lastTrackTitle.ifBlank { prefs.widgetEmptyTitle }
            val artist = prefs.lastArtist.ifBlank { "MusWall" }
            val status = when (prefs.lastPlaybackState) {
                "playing" -> "● Playing"
                "paused" -> "Ⅱ Paused"
                else -> "Last played"
            }

            views.setTextViewText(R.id.widgetTitle, title)
            views.setTextViewText(R.id.widgetArtist, artist)
            views.setTextViewText(R.id.widgetCustomText, prefs.widgetCustomText)
            views.setTextViewText(
                R.id.widgetTime,
                if (prefs.widgetShowTime) "${prefs.widgetCustomTimeLabel} • $status" else status
            )

            views.setTextViewTextSize(R.id.widgetTitle, TypedValue.COMPLEX_UNIT_SP, prefs.widgetTitleSize.toFloat())
            views.setTextViewTextSize(R.id.widgetArtist, TypedValue.COMPLEX_UNIT_SP, prefs.widgetArtistSize.toFloat())
            views.setTextViewTextSize(R.id.widgetCustomText, TypedValue.COMPLEX_UNIT_SP, prefs.widgetCustomTextSize.toFloat())
            views.setTextColor(R.id.widgetTitle, textColor)
            views.setTextColor(R.id.widgetArtist, secondary)
            views.setTextColor(R.id.widgetCustomText, secondary)
            views.setTextColor(R.id.widgetTime, secondary)

            val artFile = File(context.filesDir, "last_album_art.jpg")
            if (prefs.widgetShowArtwork && artFile.exists()) {
                BitmapFactory.decodeFile(artFile.absolutePath)?.let { bitmap ->
                    views.setImageViewBitmap(R.id.widgetArt, bitmap)
                }
                views.setViewVisibility(R.id.widgetArt, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widgetArt, View.GONE)
            }

            views.setViewVisibility(R.id.widgetTitle, if (prefs.widgetShowTitle) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widgetArtist, if (prefs.widgetShowArtist) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widgetControls, if (prefs.widgetShowControls) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widgetProgress, if (prefs.widgetShowProgress) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widgetCustomText, if (prefs.widgetShowCustomText) View.VISIBLE else View.GONE)
            views.setViewVisibility(
                R.id.widgetTime,
                if (prefs.widgetShowTime || prefs.lastTrackTitle.isNotBlank()) View.VISIBLE else View.GONE
            )

            val duration = prefs.lastDuration.coerceAtLeast(0L)
            val position = prefs.lyricsPosition.coerceIn(0L, duration)
            val max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val progress = position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            views.setProgressBar(R.id.widgetProgress, max, progress, duration <= 0L)

            views.setImageViewResource(
                R.id.widgetPlay,
                if (prefs.lastPlaybackState == "playing") {
                    android.R.drawable.ic_media_pause
                } else {
                    android.R.drawable.ic_media_play
                }
            )

            views.setOnClickPendingIntent(
                R.id.widgetRoot,
                pendingActivity(context, id)
            )
            views.setOnClickPendingIntent(
                R.id.widgetPlay,
                pendingBroadcast(context, ACTION_WIDGET_PLAY, id, 1)
            )
            views.setOnClickPendingIntent(
                R.id.widgetNext,
                pendingBroadcast(context, ACTION_WIDGET_NEXT, id, 2)
            )
            views.setOnClickPendingIntent(
                R.id.widgetPrev,
                pendingBroadcast(context, ACTION_WIDGET_PREV, id, 3)
            )

            manager.updateAppWidget(id, views)
        }
    }

    private fun parseColor(value: String, fallback: Int): Int =
        runCatching { Color.parseColor(value) }.getOrDefault(fallback)

    private fun pendingActivity(context: Context, id: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            id * 10,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun pendingBroadcast(context: Context, action: String, id: Int, request: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id * 10 + request,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
