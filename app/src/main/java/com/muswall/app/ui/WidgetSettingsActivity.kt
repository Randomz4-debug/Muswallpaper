package com.muswall.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
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
    private val palette = arrayOf("#FFFFFF","#000000","#FF3B30","#FF9500","#FFCC00","#34C759","#00C7BE","#30B0C7","#0A84FF","#5856D6","#AF52DE","#FF2D55","#A78BFA","#22D3EE","#F472B6","#10B981")

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
        bindSwitch(R.id.widgetBarSwitch, prefs.widgetBarEnabled) { prefs.widgetBarEnabled = it }
        bindSeek(R.id.widgetOpacity, 0, 100, prefs.widgetOpacity) { prefs.widgetOpacity = it }
        bindSeek(R.id.widgetBarOpacity, 0, 100, prefs.widgetBarOpacity) { prefs.widgetBarOpacity = it }
        bindSeek(R.id.widgetTitleSize, 10, 28, prefs.widgetTitleSize) { prefs.widgetTitleSize = it }
        bindSeek(R.id.widgetArtistSize, 8, 22, prefs.widgetArtistSize) { prefs.widgetArtistSize = it }
        bindSeek(R.id.widgetCustomSize, 8, 22, prefs.widgetCustomTextSize) { prefs.widgetCustomTextSize = it }
        bindSeek(R.id.widgetArtworkSize, 32, 140, prefs.widgetArtworkSize) { prefs.widgetArtworkSize = it }
        bindSeek(R.id.widgetCornerRadius, 0, 50, prefs.widgetCornerRadius) { prefs.widgetCornerRadius = it }
        bindSeek(R.id.widgetPadding, 0, 30, prefs.widgetPadding) { prefs.widgetPadding = it }
        findViewById<TextView>(R.id.widgetLayoutChoice).apply {
            text = if (prefs.widgetLayout == 0) "Horizontal" else "Compact"
            setOnClickListener { prefs.widgetLayout = if (prefs.widgetLayout == 0) 1 else 0; text = if (prefs.widgetLayout == 0) "Horizontal" else "Compact"; notifyWidgets() }
        }
        findViewById<EditText>(R.id.widgetCustomText).setText(prefs.widgetCustomText)
        findViewById<EditText>(R.id.widgetEmptyText).setText(prefs.widgetEmptyTitle)
        bindColor(R.id.widgetTextColor, R.id.widgetTextColorSwatch, "Title / custom text", { prefs.widgetTextColor }, { prefs.widgetTextColor = it })
        bindColor(R.id.widgetSecondaryColor, R.id.widgetSecondaryColorSwatch, "Artist / secondary text", { prefs.widgetSecondaryColor }, { prefs.widgetSecondaryColor = it })
        bindColor(R.id.widgetBackgroundColor, R.id.widgetBackgroundColorSwatch, "Widget background", { prefs.widgetBackgroundColor }, { prefs.widgetBackgroundColor = it })
        bindColor(R.id.widgetBarColor, R.id.widgetBarColorSwatch, "Translucent bar", { prefs.widgetBarColor }, { prefs.widgetBarColor = it })
        findViewById<EditText>(R.id.widgetTimeLabel).setText(prefs.widgetCustomTimeLabel)
        findViewById<TextView>(R.id.widgetSave).setOnClickListener {
            prefs.widgetCustomText = findViewById<EditText>(R.id.widgetCustomText).text.toString().ifBlank { "MusWall" }
            prefs.widgetEmptyTitle = findViewById<EditText>(R.id.widgetEmptyText).text.toString().ifBlank { "No music playing" }
            saveColor(R.id.widgetTextColor, { prefs.widgetTextColor = it })
            saveColor(R.id.widgetSecondaryColor, { prefs.widgetSecondaryColor = it })
            saveColor(R.id.widgetBackgroundColor, { prefs.widgetBackgroundColor = it })
            saveColor(R.id.widgetBarColor, { prefs.widgetBarColor = it })
            prefs.widgetCustomTimeLabel = findViewById<EditText>(R.id.widgetTimeLabel).text.toString().ifBlank { "Now Playing" }
            notifyWidgets()
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }

    private fun bindSwitch(id: Int, checked: Boolean, save: (Boolean) -> Unit) { findViewById<SwitchCompat>(id).apply { isChecked = checked; setOnCheckedChangeListener { _, value -> save(value); notifyWidgets() } } }
    private fun bindSeek(id: Int, min: Int, max: Int, value: Int, save: (Int) -> Unit) { findViewById<SeekBar>(id).apply { this.max = max - min; progress = (value - min).coerceIn(0, max - min); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { save(p + min); notifyWidgets() } }; override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} }) } }

    private fun bindColor(editId: Int, swatchId: Int, title: String, get: () -> String, save: (String) -> Unit) {
        val edit = findViewById<EditText>(editId)
        val swatch = findViewById<TextView>(swatchId)
        edit.setText(get())
        updateSwatch(swatch, get())
        val open = View.OnClickListener { showColorPalette(title, edit, swatch, save) }
        swatch.setOnClickListener(open)
        edit.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) { val c = validColor(edit.text.toString(), get()); edit.setText(c); save(c); updateSwatch(swatch, c); notifyWidgets() } }
    }

    private fun saveColor(editId: Int, save: (String) -> Unit) {
        val edit = findViewById<EditText>(editId)
        val fallback = when(editId) { R.id.widgetTextColor -> "#FFFFFF"; R.id.widgetSecondaryColor -> "#C7C7D0"; R.id.widgetBackgroundColor -> "#17151F"; else -> "#FFFFFF" }
        val c = validColor(edit.text.toString(), fallback); edit.setText(c); save(c)
    }

    private fun showColorPalette(title: String, edit: EditText, swatch: TextView, save: (String) -> Unit) {
        val box = GridLayout(this).apply { columnCount = 4; rowCount = 4; setPadding(18, 12, 18, 8) }
        palette.forEach { hex ->
            val chip = TextView(this).apply {
                text = "●"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(Color.parseColor(hex)); setPadding(6, 6, 6, 6)
                setOnClickListener { edit.setText(hex); save(hex); updateSwatch(swatch, hex); notifyWidgets(); dialog.dismiss() }
            }
            box.addView(chip, GridLayout.LayoutParams().apply { width = 68; height = 58; setMargins(2, 2, 2, 2) })
        }
        val custom = EditText(this).apply { hint = "#RRGGBB"; setSingleLine(true); setText(edit.text.toString()) }
        val container = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(16, 0, 16, 0); addView(box); addView(custom) }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(container).setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { val c=validColor(custom.text.toString(),edit.text.toString()); edit.setText(c); save(c); updateSwatch(swatch,c); notifyWidgets(); dialog.dismiss() } }
        dialog.show()
    }

    private fun validColor(value: String, fallback: String): String = runCatching { val c=Color.parseColor(if(value.trim().startsWith("#")) value.trim() else "#${value.trim()}"); String.format("#%06X", 0xFFFFFF and c) }.getOrDefault(fallback)
    private fun updateSwatch(view: TextView, hex: String) { val c=runCatching{Color.parseColor(hex)}.getOrDefault(Color.WHITE); view.text="●"; view.setTextColor(c); view.background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(Color.TRANSPARENT);setStroke(2,Color.WHITE)} }
    private fun notifyWidgets() { sendBroadcast(Intent(MediaNotificationListenerService.ACTION_WIDGET_CHANGED).setPackage(packageName)) }
}