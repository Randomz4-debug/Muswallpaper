package com.muswall.app.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.muswall.app.R
import com.muswall.app.data.PreferencesManager
import com.muswall.app.service.MediaNotificationListenerService

class WidgetSettingsActivity : AppCompatActivity() {
    private lateinit var prefs: PreferencesManager
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager.getInstance(this)
        appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        setContentView(R.layout.activity_widget_settings)
        findViewById<TextView>(R.id.widgetBack).setOnClickListener { finish() }
        bindSwitch(R.id.widgetArtworkSwitch, prefs.widgetShowArtwork) { prefs.widgetShowArtwork = it }
        bindSwitch(R.id.widgetTitleSwitch, prefs.widgetShowTitle) { prefs.widgetShowTitle = it }
        bindSwitch(R.id.widgetArtistSwitch, prefs.widgetShowArtist) { prefs.widgetShowArtist = it }
        bindSwitch(R.id.widgetControlsSwitch, prefs.widgetShowControls) { prefs.widgetShowControls = it }
        bindSwitch(R.id.widgetProgressSwitch, prefs.widgetShowProgress) { prefs.widgetShowProgress = it }
        bindSwitch(R.id.widgetCustomSwitch, prefs.widgetShowCustomText) { prefs.widgetShowCustomText = it }
        bindSwitch(R.id.widgetTimeSwitch, prefs.widgetShowTime) { prefs.widgetShowTime = it }
        bindSeek(R.id.widgetOpacity, 0, 100, prefs.widgetOpacity) { prefs.widgetOpacity = it }
        bindSeek(R.id.widgetTitleSize, 10, 28, prefs.widgetTitleSize) { prefs.widgetTitleSize = it }
        bindSeek(R.id.widgetArtistSize, 8, 22, prefs.widgetArtistSize) { prefs.widgetArtistSize = it }
        bindSeek(R.id.widgetCustomSize, 8, 22, prefs.widgetCustomTextSize) { prefs.widgetCustomTextSize = it }
        bindSeek(R.id.widgetArtworkSize, 32, 140, prefs.widgetArtworkSize) { prefs.widgetArtworkSize = it }
        bindSeek(R.id.widgetCornerRadius, 0, 50, prefs.widgetCornerRadius) { prefs.widgetCornerRadius = it }
        bindSeek(R.id.widgetPadding, 0, 30, prefs.widgetPadding) { prefs.widgetPadding = it }
        findViewById<TextView>(R.id.widgetLayoutChoice).apply {
            text = if (prefs.widgetLayout == 0) "Horizontal" else "Compact"
            setOnClickListener {
                prefs.widgetLayout = if (prefs.widgetLayout == 0) 1 else 0
                text = if (prefs.widgetLayout == 0) "Horizontal" else "Compact"
                notifyWidgets()
            }
        }
        findViewById<EditText>(R.id.widgetCustomText).setText(prefs.widgetCustomText)
        findViewById<EditText>(R.id.widgetEmptyText).setText(prefs.widgetEmptyTitle)
        findViewById<EditText>(R.id.widgetTextColor).setText(prefs.widgetTextColor)
        findViewById<EditText>(R.id.widgetSecondaryColor).setText(prefs.widgetSecondaryColor)
        findViewById<EditText>(R.id.widgetBackgroundColor).setText(prefs.widgetBackgroundColor)
        findViewById<EditText>(R.id.widgetTimeLabel).setText(prefs.widgetCustomTimeLabel)
        findViewById<TextView>(R.id.widgetSave).setOnClickListener {
            prefs.widgetCustomText = findViewById<EditText>(R.id.widgetCustomText).text.toString().ifBlank { "MusWall" }
            prefs.widgetEmptyTitle = findViewById<EditText>(R.id.widgetEmptyText).text.toString().ifBlank { "No music playing" }
            prefs.widgetTextColor = findViewById<EditText>(R.id.widgetTextColor).text.toString().ifBlank { "#FFFFFF" }
            prefs.widgetSecondaryColor = findViewById<EditText>(R.id.widgetSecondaryColor).text.toString().ifBlank { "#C7C7D0" }
            prefs.widgetBackgroundColor = findViewById<EditText>(R.id.widgetBackgroundColor).text.toString().ifBlank { "#17151F" }
            prefs.widgetCustomTimeLabel = findViewById<EditText>(R.id.widgetTimeLabel).text.toString().ifBlank { "Now Playing" }
            notifyWidgets()
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }
    private fun bindSwitch(id: Int, checked: Boolean, save: (Boolean) -> Unit) { findViewById<SwitchCompat>(id).apply { isChecked = checked; setOnCheckedChangeListener { _, value -> save(value); notifyWidgets() } } }
    private fun bindSeek(id: Int, min: Int, max: Int, value: Int, save: (Int) -> Unit) {
        findViewById<SeekBar>(id).apply { this.max = max - min; progress = (value - min).coerceIn(0, max - min); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { save(p + min); notifyWidgets() } }; override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} }) }
    }
    private fun notifyWidgets() { sendBroadcast(Intent(MediaNotificationListenerService.ACTION_WIDGET_CHANGED).setPackage(packageName)) }
}
