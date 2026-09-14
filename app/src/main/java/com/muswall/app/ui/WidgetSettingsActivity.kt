package com.muswall.app.ui

import android.app.Activity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager.getInstance(this)
        setContentView(R.layout.activity_widget_settings)
        bindSwitch(R.id.widgetArtworkSwitch, prefs.widgetShowArtwork) { prefs.widgetShowArtwork = it }
        bindSwitch(R.id.widgetTitleSwitch, prefs.widgetShowTitle) { prefs.widgetShowTitle = it }
        bindSwitch(R.id.widgetArtistSwitch, prefs.widgetShowArtist) { prefs.widgetShowArtist = it }
        bindSwitch(R.id.widgetControlsSwitch, prefs.widgetShowControls) { prefs.widgetShowControls = it }
        bindSwitch(R.id.widgetProgressSwitch, prefs.widgetShowProgress) { prefs.widgetShowProgress = it }
        bindSwitch(R.id.widgetCustomSwitch, prefs.widgetShowCustomText) { prefs.widgetShowCustomText = it }
        bindSwitch(R.id.widgetTimeSwitch, prefs.widgetShowTime) { prefs.widgetShowTime = it }
        findViewById<SeekBar>(R.id.widgetOpacity).apply { max = 100; progress = prefs.widgetOpacity; setOnSeekBarChangeListener(listener { prefs.widgetOpacity = it }) }
        findViewById<SeekBar>(R.id.widgetTitleSize).apply { max = 18; progress = prefs.widgetTitleSize - 10; setOnSeekBarChangeListener(listener { prefs.widgetTitleSize = it + 10 }) }
        findViewById<SeekBar>(R.id.widgetArtistSize).apply { max = 14; progress = prefs.widgetArtistSize - 8; setOnSeekBarChangeListener(listener { prefs.widgetArtistSize = it + 8 }) }
        findViewById<SeekBar>(R.id.widgetCustomSize).apply { max = 14; progress = prefs.widgetCustomTextSize - 8; setOnSeekBarChangeListener(listener { prefs.widgetCustomTextSize = it + 8 }) }
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
            sendBroadcast(android.content.Intent(MediaNotificationListenerService.ACTION_SETTINGS_CHANGED).setPackage(packageName))
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun bindSwitch(id: Int, checked: Boolean, save: (Boolean) -> Unit) {
        findViewById<SwitchCompat>(id).apply { isChecked = checked; setOnCheckedChangeListener { _, value -> save(value) } }
    }

    private fun listener(save: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) save(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
