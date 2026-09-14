package com.muswall.app.ui

import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import com.muswall.app.R
import com.muswall.app.data.PreferencesManager
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
        findViewById<TextView>(R.id.restoreHomeRow).setOnClickListener { chooseOriginalWallpaper(WallpaperManager.FLAG_SYSTEM) }
        findViewById<TextView>(R.id.restoreLockRow).setOnClickListener { chooseOriginalWallpaper(WallpaperManager.FLAG_LOCK) }
        findViewById<TextView>(R.id.timingRow).setOnClickListener { chooseTiming() }
        findViewById<TextView>(R.id.permissionsRow).setOnClickListener { XiaomiHelper.openNotificationListenerSettings(this) }
        findViewById<TextView>(R.id.serviceRow).setOnClickListener { XiaomiHelper.openNotificationListenerSettings(this) }
        findViewById<TextView>(R.id.compatibilityRow).setOnClickListener { ToastCompat.show(this, "POCO/MIUI compatibility keeps the Home live engine and updates the Lock wallpaper safely through FLAG_LOCK.") }
        findViewById<TextView>(R.id.feedbackRow).setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))) } }
        findViewById<TextView>(R.id.faqRow).setOnClickListener {
            AlertDialog.Builder(this).setTitle("MusWall FAQ").setMessage("Show Lyrics first uses lyric metadata exposed by the music player. If that is unavailable, MusWall tries LRCLIB using the current title, artist and album. Lyrics are rendered into the wallpaper frame. On POCO/MIUI the Lock screen follows the track by updating its static wallpaper frame because third-party apps cannot directly install a lock-only live engine through the public Android API.").setPositiveButton("OK", null).show()
        }
        findViewById<TextView>(R.id.updateRow).setOnClickListener { AlertDialog.Builder(this).setTitle("MusWall").setMessage("Version 1.9.7").setPositiveButton("OK", null).show() }
        findViewById<TextView>(R.id.widgetRow).setOnClickListener { ToastCompat.show(this, "Widget customization is not required for the live wallpaper.") }
        findViewById<TextView>(R.id.photoSourceRow).setOnClickListener { choosePhotoSource() }
        findViewById<TextView>(R.id.customPhotoRow).setOnClickListener { customPhotoPicker.launch(arrayOf("image/*")) }

        findViewById<SwitchCompat>(R.id.lyricsSwitch).apply {
            showText = false; textOn = ""; textOff = ""; isChecked = prefs.showLyrics
            setOnCheckedChangeListener { _, value -> prefs.showLyrics = value }
        }
        findViewById<SwitchCompat>(R.id.photoFallbackSwitch).apply {
            showText = false; textOn = ""; textOff = ""; isChecked = prefs.photoFallback
            setOnCheckedChangeListener { _, value -> prefs.photoFallback = value }
        }
        findViewById<SwitchCompat>(R.id.effectsRestore).apply {
            showText = false; textOn = ""; textOff = ""; isChecked = prefs.effect != PreferencesManager.EFFECT_BLUR
            setOnCheckedChangeListener { _, value -> if (!value) prefs.effect = PreferencesManager.EFFECT_BLUR }
        }
        updatePhotoLabels(); updateResolutionLabel()
    }

    private fun updatePhotoLabels() {
        val label = when (prefs.photoSource) {
            PreferencesManager.PHOTO_AUTO -> "Automatic — best available source"
            PreferencesManager.PHOTO_ALBUM -> "Album artwork (ALBUM_ART)"
            PreferencesManager.PHOTO_ART -> "Track artwork (ART)"
            PreferencesManager.PHOTO_DISPLAY_ICON -> "Display icon"
            PreferencesManager.PHOTO_NOTIFICATION -> "Notification artwork"
            PreferencesManager.PHOTO_CUSTOM -> "Custom image"
            else -> "Automatic"
        }
        findViewById<TextView>(R.id.photoSourceRow).text = "Music photo\n$label"
    }

    private fun choosePhotoSource() {
        val labels = arrayOf("Automatic — try all available sources", "Album artwork — standard album cover", "Track artwork — player ART field", "Display icon — player display icon", "Notification artwork — notification large icon", "Custom image — one image for every track")
        val values = arrayOf(PreferencesManager.PHOTO_AUTO, PreferencesManager.PHOTO_ALBUM, PreferencesManager.PHOTO_ART, PreferencesManager.PHOTO_DISPLAY_ICON, PreferencesManager.PHOTO_NOTIFICATION, PreferencesManager.PHOTO_CUSTOM)
        AlertDialog.Builder(this).setTitle("Which music photo should be used?").setItems(labels) { _, index ->
            if (values[index] == PreferencesManager.PHOTO_CUSTOM && prefs.customPhotoUri.isBlank()) customPhotoPicker.launch(arrayOf("image/*"))
            else { prefs.photoSource = values[index]; updatePhotoLabels(); ToastCompat.show(this, "Music photo source saved") }
        }.show()
    }

    private fun chooseResolution() {
        val labels = arrayOf("Device native — recommended for live wallpaper", "1080p — 1920 × 1080", "1440p — 2560 × 1440", "4K UHD — 3840 × 2160", "8K UHD — 7680 × 4320", "12K — 11520 × 6480", "16K — 15360 × 8640", "Custom — choose exact pixels")
        val values = arrayOf(PreferencesManager.RESOLUTION_DEVICE, PreferencesManager.RESOLUTION_1080P, PreferencesManager.RESOLUTION_1440P, PreferencesManager.RESOLUTION_4K, PreferencesManager.RESOLUTION_8K, PreferencesManager.RESOLUTION_12K, PreferencesManager.RESOLUTION_16K, PreferencesManager.RESOLUTION_CUSTOM)
        AlertDialog.Builder(this).setTitle("Wallpaper render resolution").setMessage("Higher resolutions create larger files and use much more RAM. Device native is recommended for live wallpaper.").setItems(labels) { _, index -> if (values[index] == PreferencesManager.RESOLUTION_CUSTOM) chooseCustomResolution() else { prefs.renderResolution = values[index]; updateResolutionLabel(); ToastCompat.show(this, "Resolution saved") } }.show()
    }

    private fun chooseCustomResolution() {
        val view = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 8, 48, 0) }
        val width = android.widget.EditText(this).apply { hint = "Width, e.g. 7680"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(prefs.customRenderWidth.toString()) }
        val height = android.widget.EditText(this).apply { hint = "Height, e.g. 4320"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(prefs.customRenderHeight.toString()) }
        view.addView(width); view.addView(height)
        AlertDialog.Builder(this).setTitle("Custom render resolution").setMessage("Maximum 16384 × 16384. Very large images may exceed your phone's RAM.").setView(view).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
            prefs.customRenderWidth = width.text.toString().toIntOrNull()?.coerceIn(160, 16384) ?: 1080
            prefs.customRenderHeight = height.text.toString().toIntOrNull()?.coerceIn(240, 16384) ?: 2400
            prefs.renderResolution = PreferencesManager.RESOLUTION_CUSTOM; updateResolutionLabel()
        }.show()
    }

    private fun updateResolutionLabel() {
        val label = when (prefs.renderResolution) {
            PreferencesManager.RESOLUTION_1080P -> "1080p — 1920 × 1080"
            PreferencesManager.RESOLUTION_1440P -> "1440p — 2560 × 1440"
            PreferencesManager.RESOLUTION_4K -> "4K UHD — 3840 × 2160"
            PreferencesManager.RESOLUTION_8K -> "8K UHD — 7680 × 4320"
            PreferencesManager.RESOLUTION_12K -> "12K — 11520 × 6480"
            PreferencesManager.RESOLUTION_16K -> "16K — 15360 × 8640"
            PreferencesManager.RESOLUTION_CUSTOM -> "Custom — ${prefs.customRenderWidth} × ${prefs.customRenderHeight}"
            else -> "Device native"
        }
        findViewById<TextView>(R.id.resolutionRow).text = "Render resolution\n$label"
    }

    private fun chooseTheme() { AlertDialog.Builder(this).setTitle("Theme").setItems(arrayOf("Follow System", "Light", "Dark")) { _, which -> AppCompatDelegate.setDefaultNightMode(when (which) { 1 -> AppCompatDelegate.MODE_NIGHT_NO; 2 -> AppCompatDelegate.MODE_NIGHT_YES; else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM }) }.show() }
    private fun chooseLanguage() { AlertDialog.Builder(this).setTitle("Language").setMessage("System Default\n\nThe interface follows your Android language.").setPositiveButton("OK", null).show() }
    private fun chooseBackgroundMode() { AlertDialog.Builder(this).setTitle("Wallpaper background").setItems(arrayOf("Album artwork (blurred)", "Solid color", "Gradient", "Auto color from album")) { _, which -> prefs.backgroundMode = when (which) { 1 -> PreferencesManager.BACKGROUND_COLOR; 2 -> PreferencesManager.BACKGROUND_GRADIENT; 3 -> PreferencesManager.BACKGROUND_AUTO; else -> PreferencesManager.BACKGROUND_ART } }.show() }
    private fun chooseColor(second: Boolean) { val colors = arrayOf("#111111", "#F7F7F7", "#5E2CA5", "#174EA6", "#176B3A", "#8B1E2D", "#B85C00"); AlertDialog.Builder(this).setTitle(if (second) "Gradient end color" else "Background color").setItems(colors) { _, which -> if (second) prefs.backgroundColor2 = colors[which] else prefs.backgroundColor = colors[which] }.show() }
    private fun chooseAccentColor() { val colors = arrayOf("#7C00FF", "#00B8D9", "#36C95F", "#4285F4", "#E91E63", "#FFFFFF"); AlertDialog.Builder(this).setTitle("Cover accent").setItems(colors) { _, which -> prefs.accentColor = colors[which] }.show() }
    private fun chooseOriginalWallpaper(which: Int) { originalTarget = which; originalWallpaperPicker.launch(arrayOf("image/*")) }
    private fun chooseTiming() { AlertDialog.Builder(this).setTitle("Restore timing").setItems(arrayOf("Immediately", "5 seconds", "10 seconds", "30 seconds")) { _, which -> prefs.restoreDelay = intArrayOf(0, 5, 10, 30)[which] }.show() }

    override fun onDestroy() { ioScope.cancel(); super.onDestroy() }
    object ToastCompat { fun show(context: android.content.Context, message: String) { android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show() } }
}
