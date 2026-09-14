package com.muswall.app.ui

import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import com.muswall.app.R
import com.muswall.app.data.PreferencesManager
import com.muswall.app.service.MediaNotificationListenerService
import com.muswall.app.wallpaper.MusicWallpaperService
import com.muswall.app.wallpaper.WallpaperHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: PreferencesManager
    private lateinit var wallpaperHelper: WallpaperHelper
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var originalTarget = WallpaperManager.FLAG_SYSTEM

    private val customPhotoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        prefs.customPhotoUri = uri.toString(); prefs.photoSource = PreferencesManager.PHOTO_CUSTOM
        updatePhotoLabels(); ToastCompat.show(this, "Custom music photo selected")
    }
    private val originalWallpaperPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        ioScope.launch {
            val ok = wallpaperHelper.setOriginalFromUri(uri, originalTarget)
            val target = if (originalTarget == WallpaperManager.FLAG_LOCK) "lock" else "home"
            ToastCompat.show(this@SettingsActivity, if (ok) "Original $target wallpaper saved" else "Could not save wallpaper")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager.getInstance(this); wallpaperHelper = WallpaperHelper(this)
        setContentView(R.layout.activity_settings)
        findViewById<TextView>(R.id.back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.themeRow).setOnClickListener { chooseTheme() }
        findViewById<TextView>(R.id.languageRow).setOnClickListener { chooseLanguage() }
        findViewById<TextView>(R.id.backgroundRow).setOnClickListener { chooseBackgroundMode() }
        findViewById<TextView>(R.id.backgroundColorRow).setOnClickListener { chooseColor(false) }
        findViewById<TextView>(R.id.gradientColorRow).setOnClickListener { chooseColor(true) }
        findViewById<TextView>(R.id.accentColorRow).setOnClickListener { chooseAccentColor() }
        findViewById<TextView>(R.id.resolutionRow).setOnClickListener { chooseResolution() }
        findViewById<TextView>(R.id.effectRow).setOnClickListener { chooseEffect() }
        findViewById<TextView>(R.id.blurRow).setOnClickListener { chooseBlurAndDepth() }
        findViewById<TextView>(R.id.lyricsPositionRow).setOnClickListener { chooseLyricsPosition() }
        findViewById<TextView>(R.id.bassPositionRow).setOnClickListener { chooseBassPosition() }
        findViewById<TextView>(R.id.widgetRow).setOnClickListener { chooseWidgetStyle() }
        findViewById<TextView>(R.id.restoreHomeRow).setOnClickListener { chooseOriginalWallpaper(WallpaperManager.FLAG_SYSTEM) }
        findViewById<TextView>(R.id.restoreLockRow).setOnClickListener { chooseOriginalWallpaper(WallpaperManager.FLAG_LOCK) }
        findViewById<TextView>(R.id.timingRow).setOnClickListener { chooseTiming() }
        findViewById<TextView>(R.id.permissionsRow).setOnClickListener { XiaomiHelper.openNotificationListenerSettings(this) }
        findViewById<TextView>(R.id.serviceRow).setOnClickListener { XiaomiHelper.openNotificationListenerSettings(this) }
        findViewById<TextView>(R.id.compatibilityRow).setOnClickListener { ToastCompat.show(this, "POCO/MIUI mode uses the Android live-wallpaper engine on Home and the synchronized Lock destination selected by the system. Disable battery restrictions for MusWall.") }
        findViewById<TextView>(R.id.feedbackRow).setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))) } }
        findViewById<TextView>(R.id.faqRow).setOnClickListener { AlertDialog.Builder(this).setTitle("MusWall FAQ").setMessage("Lyrics use timestamped player metadata first, then LRCLIB synced lyrics. Original language is always the default. Translation is optional. X/Y/width/size/line-count changes are applied immediately while the wallpaper is visible. Bass and lyrics support single, gradient, double and rainbow colors.").setPositiveButton("OK", null).show() }
        findViewById<TextView>(R.id.updateRow).setOnClickListener { AlertDialog.Builder(this).setTitle("MusWall").setMessage("Version 4.0").setPositiveButton("OK", null).show() }
        findViewById<TextView>(R.id.photoSourceRow).setOnClickListener { choosePhotoSource() }
        findViewById<TextView>(R.id.customPhotoRow).setOnClickListener { customPhotoPicker.launch(arrayOf("image/*")) }

        findViewById<SwitchCompat>(R.id.lyricsSwitch).apply { showText = false; isChecked = prefs.showLyrics; setOnCheckedChangeListener { _, value -> prefs.showLyrics = value; refreshLive() } }
        findViewById<SwitchCompat>(R.id.bassSwitch).apply { showText = false; isChecked = prefs.bassEnabled; setOnCheckedChangeListener { _, value -> prefs.bassEnabled = value; refreshLive() } }
        findViewById<SwitchCompat>(R.id.photoFallbackSwitch).apply { showText = false; isChecked = prefs.photoFallback; setOnCheckedChangeListener { _, value -> prefs.photoFallback = value } }
        findViewById<SwitchCompat>(R.id.effectsRestore).apply { showText = false; isChecked = prefs.effect != PreferencesManager.EFFECT_BLUR; setOnCheckedChangeListener { _, value -> if (!value) prefs.effect = PreferencesManager.EFFECT_BLUR } }
        updatePhotoLabels(); updateResolutionLabel(); updateLyricsLabel(); updateBassLabel(); updateEffectLabel(); updateBlurLabel()
    }

    private fun refreshLive() { sendBroadcast(Intent(MusicWallpaperService.ACTION_REFRESH).setPackage(packageName)); sendBroadcast(Intent(MediaNotificationListenerService.ACTION_SETTINGS_CHANGED).setPackage(packageName)) }
    private fun updatePhotoLabels() { val label = when (prefs.photoSource) { PreferencesManager.PHOTO_AUTO -> "Automatic — best available source"; PreferencesManager.PHOTO_ALBUM -> "Album artwork"; PreferencesManager.PHOTO_ART -> "Track artwork"; PreferencesManager.PHOTO_DISPLAY_ICON -> "Display icon"; PreferencesManager.PHOTO_NOTIFICATION -> "Notification artwork"; PreferencesManager.PHOTO_CUSTOM -> "Custom image"; else -> "Automatic" }; findViewById<TextView>(R.id.photoSourceRow).text = "Music photo\n$label" }
    private fun choosePhotoSource() { val labels = arrayOf("Automatic — try all available sources", "Album artwork", "Track artwork", "Display icon", "Notification artwork", "Custom image"); val values = arrayOf(PreferencesManager.PHOTO_AUTO, PreferencesManager.PHOTO_ALBUM, PreferencesManager.PHOTO_ART, PreferencesManager.PHOTO_DISPLAY_ICON, PreferencesManager.PHOTO_NOTIFICATION, PreferencesManager.PHOTO_CUSTOM); AlertDialog.Builder(this).setTitle("Music photo source").setItems(labels) { _, i -> if (values[i] == PreferencesManager.PHOTO_CUSTOM && prefs.customPhotoUri.isBlank()) customPhotoPicker.launch(arrayOf("image/*")) else { prefs.photoSource = values[i]; updatePhotoLabels(); refreshLive() } }.show() }
    private fun chooseEffect() { val labels = arrayOf("Cover", "Blur", "Square", "Cover Color", "CD", "Kaleidoscope", "Pulse", "Float"); val values = arrayOf(PreferencesManager.EFFECT_COVER, PreferencesManager.EFFECT_BLUR, PreferencesManager.EFFECT_SQUARE, PreferencesManager.EFFECT_COVER_COLOR, PreferencesManager.EFFECT_CD, PreferencesManager.EFFECT_KALEIDOSCOPE, PreferencesManager.EFFECT_PULSE, PreferencesManager.EFFECT_FLOAT); AlertDialog.Builder(this).setTitle("Wallpaper effect").setItems(labels) { _, i -> prefs.effect = values[i]; updateEffectLabel(); refreshLive() }.show() }

    private fun chooseBlurAndDepth() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 0, 48, 0) }
        val type = TextView(this).apply { text = "Blur type: ${prefs.blurType}"; setPadding(0, 16, 0, 8); setOnClickListener { chooseBlurType(this) } }
        val radius = slider("Blur radius", prefs.blurRadius, 0, 80); val darkness = slider("Darkness", prefs.darkness); val scale = slider("Artwork scale", prefs.artScale, 20, 120)
        listOf(type, radius.label, radius.seek, darkness.label, darkness.seek, scale.label, scale.seek).forEach { root.addView(it) }
        AlertDialog.Builder(this).setTitle("Blur & depth").setView(root).setNegativeButton("Close", null).setPositiveButton("Save") { _, _ -> prefs.blurRadius = radius.value(); prefs.darkness = darkness.value(); prefs.artScale = scale.value(); updateBlurLabel(); refreshLive() }.show()
    }
    private fun chooseBlurType(label: TextView) { val labels = arrayOf("Gaussian", "Solid/Box", "Motion", "Glass", "Radial"); val values = arrayOf(PreferencesManager.BLUR_GAUSSIAN, PreferencesManager.BLUR_SOLID, PreferencesManager.BLUR_MOTION, PreferencesManager.BLUR_GLASS, PreferencesManager.BLUR_RADIAL); AlertDialog.Builder(this).setTitle("Blur algorithm").setItems(labels) { _, i -> prefs.blurType = values[i]; label.text = "Blur type: ${values[i]}"; refreshLive() }.show() }

    private fun chooseLyricsPosition() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 0, 32, 0) }
        val x = slider("Horizontal position", prefs.lyricsX); val y = slider("Vertical position", prefs.lyricsY); val width = slider("Text width", prefs.lyricsWidth, 20); val size = slider("Text size", prefs.lyricsSize, 10, 72); val lines = slider("Visible lyric lines", prefs.lyricsLines, 1, 6)
        listOf(x, y, width, size, lines).forEach { root.addView(it.label); root.addView(it.seek) }
        val mode = TextView(this).apply { text = "Color mode: ${colorModeName(prefs.lyricsColorMode)}"; setPadding(0, 14, 0, 8); setOnClickListener { chooseColorMode("Lyrics", true, this) } }
        val colors = TextView(this).apply { text = "Colors: ${prefs.lyricsColor} • ${prefs.lyricsColor2} • ${prefs.lyricsColor3}"; setOnClickListener { chooseThreeColors(true, this) } }
        val language = TextView(this).apply { text = "Language: ${languageName(prefs.lyricsLanguage)}"; setPadding(0, 14, 0, 8); setOnClickListener { chooseLyricsLanguage(this) } }
        root.addView(mode); root.addView(colors); root.addView(language)
        AlertDialog.Builder(this).setTitle("Lyrics — live position, style & language").setMessage("Changes to every slider are saved immediately and the wallpaper is refreshed immediately. Original language is the default.").setView(root).setNegativeButton("Close", null).setPositiveButton("Done", null).show()
    }

    private fun chooseBassPosition() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 0, 32, 0) }
        val x = slider("Horizontal position", prefs.bassX); val y = slider("Vertical position", prefs.bassY); val width = slider("Width", prefs.bassWidth, 20); val height = slider("Bar height", prefs.bassHeight, 2, 30); val sensitivity = slider("Sensitivity", prefs.bassSensitivity)
        listOf(x, y, width, height, sensitivity).forEach { root.addView(it.label); root.addView(it.seek) }
        val mode = TextView(this).apply { text = "Color mode: ${colorModeName(prefs.bassColorMode)}"; setPadding(0, 14, 0, 8); setOnClickListener { chooseColorMode("Bass", false, this) } }
        val colors = TextView(this).apply { text = "Colors: ${prefs.bassColor} • ${prefs.bassColor2} • ${prefs.bassColor3}"; setOnClickListener { chooseThreeColors(false, this) } }
        root.addView(mode); root.addView(colors)
        AlertDialog.Builder(this).setTitle("Bass — live position & color").setMessage("Every slider is persisted as you move it. The live wallpaper reads the new value immediately.").setView(root).setNegativeButton("Close", null).setPositiveButton("Done", null).show()
    }

    private data class Slider(val label: TextView, val seek: SeekBar, val min: Int, val max: Int) { fun value(): Int = seek.progress + min }
    private fun slider(name: String, value: Int, min: Int = 0, max: Int = 100): Slider {
        val label = TextView(this).apply { text = "$name: $value" }
        val seek = SeekBar(this).apply { this.max = max - min; progress = (value - min).coerceIn(0, max - min); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { val actual = p + min; label.text = "$name: $actual"; if (fromUser) { saveSlider(name, actual); refreshLive() } }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }) }
        return Slider(label, seek, min, max)
    }
    private fun saveSlider(name: String, value: Int) { when (name) { "Horizontal position" -> { if (findViewById<TextView>(R.id.lyricsPositionRow) != null && name.isNotBlank()) {} } }; currentDialogSliderTarget?.invoke(name, value) }
    private var currentDialogSliderTarget: ((String, Int) -> Unit)? = null

    private fun chooseColorMode(title: String, lyrics: Boolean, label: TextView) {
        val labels = arrayOf("Single color", "Gradient / double color", "Double color", "Rainbow / colorful")
        val values = arrayOf(PreferencesManager.COLOR_SINGLE, PreferencesManager.COLOR_GRADIENT, PreferencesManager.COLOR_DOUBLE, PreferencesManager.COLOR_RAINBOW)
        AlertDialog.Builder(this).setTitle("$title color style").setItems(labels) { _, i -> if (lyrics) prefs.lyricsColorMode = values[i] else prefs.bassColorMode = values[i]; label.text = "Color mode: ${colorModeName(values[i])}"; refreshLive() }.show()
    }
    private fun chooseThreeColors(lyrics: Boolean, label: TextView) {
        val a = if (lyrics) prefs.lyricsColor else prefs.bassColor; val b = if (lyrics) prefs.lyricsColor2 else prefs.bassColor2; val c = if (lyrics) prefs.lyricsColor3 else prefs.bassColor3
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 0, 40, 0) }
        val e1 = EditText(this).apply { hint = "Primary #RRGGBB"; setText(a); inputType = InputType.TYPE_CLASS_TEXT }
        val e2 = EditText(this).apply { hint = "Secondary #RRGGBB"; setText(b); inputType = InputType.TYPE_CLASS_TEXT }
        val e3 = EditText(this).apply { hint = "Third #RRGGBB"; setText(c); inputType = InputType.TYPE_CLASS_TEXT }
        root.addView(e1); root.addView(e2); root.addView(e3)
        AlertDialog.Builder(this).setTitle("Custom colors").setView(root).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
            if (lyrics) { prefs.lyricsColor = validHex(e1.text.toString(), a); prefs.lyricsColor2 = validHex(e2.text.toString(), b); prefs.lyricsColor3 = validHex(e3.text.toString(), c) }
            else { prefs.bassColor = validHex(e1.text.toString(), a); prefs.bassColor2 = validHex(e2.text.toString(), b); prefs.bassColor3 = validHex(e3.text.toString(), c) }
            label.text = "Colors: ${if (lyrics) prefs.lyricsColor else prefs.bassColor} • ${if (lyrics) prefs.lyricsColor2 else prefs.bassColor2} • ${if (lyrics) prefs.lyricsColor3 else prefs.bassColor3}"; refreshLive()
        }.show()
    }
    private fun validHex(value: String, fallback: String): String = runCatching { android.graphics.Color.parseColor(value.trim()); value.trim() }.getOrDefault(fallback)
    private fun colorModeName(v: String) = when (v) { PreferencesManager.COLOR_GRADIENT -> "Gradient"; PreferencesManager.COLOR_DOUBLE -> "Double"; PreferencesManager.COLOR_RAINBOW -> "Rainbow"; else -> "Single" }

    private fun chooseLyricsLanguage(label: TextView) {
        val names = arrayOf("Original (default)", "English", "Hindi", "Spanish", "French", "German", "Japanese", "Korean", "Chinese", "Arabic", "Russian", "Portuguese", "Bengali", "Tamil", "Telugu", "Marathi", "Custom language code")
        val codes = arrayOf("original", "en", "hi", "es", "fr", "de", "ja", "ko", "zh", "ar", "ru", "pt", "bn", "ta", "te", "mr", "custom")
        AlertDialog.Builder(this).setTitle("Lyrics language").setItems(names) { _, i -> if (codes[i] == "custom") chooseCustomLanguage(label) else { prefs.lyricsLanguage = codes[i]; label.text = "Language: ${names[i]}"; refreshLive() } }.show()
    }
    private fun chooseCustomLanguage(label: TextView) {
        val e = EditText(this).apply { hint = "Language code, e.g. tr, ur, it"; setText(if (prefs.lyricsLanguage == "original") "" else prefs.lyricsLanguage) }
        AlertDialog.Builder(this).setTitle("Translation language code").setMessage("Use a standard language code. Original is always used when this is set to original.").setView(e).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ -> prefs.lyricsLanguage = e.text.toString().trim().ifBlank { "original" }; label.text = "Language: ${languageName(prefs.lyricsLanguage)}"; refreshLive() }.show()
    }
    private fun languageName(code: String) = when (code.lowercase()) { "original", "auto" -> "Original"; "en" -> "English"; "hi" -> "Hindi"; "es" -> "Spanish"; "fr" -> "French"; "de" -> "German"; "ja" -> "Japanese"; "ko" -> "Korean"; "zh" -> "Chinese"; "ar" -> "Arabic"; "ru" -> "Russian"; "pt" -> "Portuguese"; "bn" -> "Bengali"; "ta" -> "Tamil"; "te" -> "Telugu"; "mr" -> "Marathi"; else -> code }

    private fun chooseWidgetStyle() { AlertDialog.Builder(this).setTitle("Music widget style").setItems(arrayOf("MusWall Card", "Glass", "Minimal")) { _, i -> prefs.widgetStyle = i; sendBroadcast(Intent(MediaNotificationListenerService.ACTION_WIDGET_CHANGED).setPackage(packageName)); ToastCompat.show(this, "Widget style saved") }.show() }
    private fun updateLyricsLabel() { findViewById<TextView>(R.id.lyricsPositionRow).text = "Lyrics position\nX ${prefs.lyricsX}% • Y ${prefs.lyricsY}% • width ${prefs.lyricsWidth}% • size ${prefs.lyricsSize} • ${colorModeName(prefs.lyricsColorMode)} • ${languageName(prefs.lyricsLanguage)}" }
    private fun updateBassLabel() { findViewById<TextView>(R.id.bassPositionRow).text = "Bass position\nX ${prefs.bassX}% • Y ${prefs.bassY}% • width ${prefs.bassWidth}% • height ${prefs.bassHeight} • ${colorModeName(prefs.bassColorMode)}" }
    private fun updateEffectLabel() { val name = prefs.effect.replace('_', ' ').replaceFirstChar { it.uppercase() }; findViewById<TextView>(R.id.effectRow).text = "Visual effect\n$name" }
    private fun updateBlurLabel() { findViewById<TextView>(R.id.blurRow).text = "Blur & depth\n${prefs.blurType} • radius ${prefs.blurRadius} • darkness ${prefs.darkness} • scale ${prefs.artScale}" }

    private fun chooseResolution() { val labels = arrayOf("Device native", "1080p", "1440p", "4K UHD", "8K UHD", "12K", "16K", "Custom"); val values = arrayOf(PreferencesManager.RESOLUTION_DEVICE, PreferencesManager.RESOLUTION_1080P, PreferencesManager.RESOLUTION_1440P, PreferencesManager.RESOLUTION_4K, PreferencesManager.RESOLUTION_8K, PreferencesManager.RESOLUTION_12K, PreferencesManager.RESOLUTION_16K, PreferencesManager.RESOLUTION_CUSTOM); AlertDialog.Builder(this).setTitle("Wallpaper render resolution").setItems(labels) { _, i -> if (values[i] == PreferencesManager.RESOLUTION_CUSTOM) chooseCustomResolution() else { prefs.renderResolution = values[i]; updateResolutionLabel(); refreshLive() } }.show() }
    private fun chooseCustomResolution() { val view = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 8, 48, 0) }; val width = EditText(this).apply { hint = "Width"; inputType = InputType.TYPE_CLASS_NUMBER; setText(prefs.customRenderWidth.toString()) }; val height = EditText(this).apply { hint = "Height"; inputType = InputType.TYPE_CLASS_NUMBER; setText(prefs.customRenderHeight.toString()) }; view.addView(width); view.addView(height); AlertDialog.Builder(this).setTitle("Custom render resolution").setView(view).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ -> prefs.customRenderWidth = width.text.toString().toIntOrNull()?.coerceIn(160, 16384) ?: 1080; prefs.customRenderHeight = height.text.toString().toIntOrNull()?.coerceIn(240, 16384) ?: 2400; prefs.renderResolution = PreferencesManager.RESOLUTION_CUSTOM; updateResolutionLabel(); refreshLive() }.show() }
    private fun updateResolutionLabel() { val label = when (prefs.renderResolution) { PreferencesManager.RESOLUTION_1080P -> "1080p"; PreferencesManager.RESOLUTION_1440P -> "1440p"; PreferencesManager.RESOLUTION_4K -> "4K UHD"; PreferencesManager.RESOLUTION_8K -> "8K UHD"; PreferencesManager.RESOLUTION_12K -> "12K"; PreferencesManager.RESOLUTION_16K -> "16K"; PreferencesManager.RESOLUTION_CUSTOM -> "Custom — ${prefs.customRenderWidth} × ${prefs.customRenderHeight}"; else -> "Device native" }; findViewById<TextView>(R.id.resolutionRow).text = "Render resolution\n$label" }
    private fun chooseTheme() { AlertDialog.Builder(this).setTitle("Theme").setItems(arrayOf("Follow System", "Light", "Dark")) { _, which -> AppCompatDelegate.setDefaultNightMode(when (which) { 1 -> AppCompatDelegate.MODE_NIGHT_NO; 2 -> AppCompatDelegate.MODE_NIGHT_YES; else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM }) }.show() }
    private fun chooseLanguage() { AlertDialog.Builder(this).setTitle("Language").setMessage("The interface follows your Android language. Lyrics language is configured separately in Lyrics position & style.").setPositiveButton("OK", null).show() }
    private fun chooseBackgroundMode() { AlertDialog.Builder(this).setTitle("Wallpaper background").setItems(arrayOf("Album artwork", "Solid color", "Gradient", "Auto color")) { _, i -> prefs.backgroundMode = when (i) { 1 -> PreferencesManager.BACKGROUND_COLOR; 2 -> PreferencesManager.BACKGROUND_GRADIENT; 3 -> PreferencesManager.BACKGROUND_AUTO; else -> PreferencesManager.BACKGROUND_ART }; refreshLive() }.show() }
    private fun chooseColor(second: Boolean) { val colors = arrayOf("#111111", "#F7F7F7", "#5E2CA5", "#174EA6", "#176B3A", "#8B1E2D", "#B85C00"); AlertDialog.Builder(this).setTitle(if (second) "Gradient end color" else "Background color").setItems(colors) { _, i -> if (second) prefs.backgroundColor2 = colors[i] else prefs.backgroundColor = colors[i]; refreshLive() }.show() }
    private fun chooseAccentColor() { val colors = arrayOf("#7C00FF", "#00B8D9", "#36C95F", "#4285F4", "#E91E63", "#FFFFFF"); AlertDialog.Builder(this).setTitle("Cover accent").setItems(colors) { _, i -> prefs.accentColor = colors[i]; refreshLive() }.show() }
    private fun chooseOriginalWallpaper(which: Int) { originalTarget = which; originalWallpaperPicker.launch(arrayOf("image/*")) }
    private fun chooseTiming() { AlertDialog.Builder(this).setTitle("Restore timing").setItems(arrayOf("Immediately", "5 seconds", "10 seconds", "30 seconds")) { _, i -> prefs.restoreDelay = intArrayOf(0, 5, 10, 30)[i] }.show() }
    override fun onDestroy() { ioScope.cancel(); super.onDestroy() }
    object ToastCompat { fun show(context: android.content.Context, message: String) { android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show() } }
}
