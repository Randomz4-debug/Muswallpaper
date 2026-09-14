package com.muswall.app.ui

import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager.getInstance(this)
        wallpaperHelper = WallpaperHelper(this)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.themeRow).setOnClickListener { chooseTheme() }
        findViewById<TextView>(R.id.languageRow).setOnClickListener { chooseLanguage() }
        findViewById<TextView>(R.id.backgroundRow).setOnClickListener { chooseBackgroundMode() }
        findViewById<TextView>(R.id.backgroundColorRow).setOnClickListener { chooseColor(false) }
        findViewById<TextView>(R.id.gradientColorRow).setOnClickListener { chooseColor(true) }
        findViewById<TextView>(R.id.accentColorRow).setOnClickListener { chooseAccentColor() }
        findViewById<TextView>(R.id.restoreHomeRow).setOnClickListener { chooseOriginalWallpaper(WallpaperManager.FLAG_SYSTEM) }
        findViewById<TextView>(R.id.restoreLockRow).setOnClickListener { chooseOriginalWallpaper(WallpaperManager.FLAG_LOCK) }
        findViewById<TextView>(R.id.timingRow).setOnClickListener { chooseTiming() }
        findViewById<TextView>(R.id.permissionsRow).setOnClickListener { XiaomiHelper.openNotificationListenerSettings(this) }
        findViewById<TextView>(R.id.serviceRow).setOnClickListener { XiaomiHelper.openNotificationListenerSettings(this) }
        findViewById<TextView>(R.id.compatibilityRow).setOnClickListener {
            ToastCompat.show(this, "Compatibility mode uses the fast renderer and live wallpaper path.")
        }
        findViewById<TextView>(R.id.feedbackRow).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))) }
        }
        findViewById<TextView>(R.id.faqRow).setOnClickListener {
            AlertDialog.Builder(this).setTitle("MusWall FAQ")
                .setMessage("Music wallpapers are rendered inside the MusWall live wallpaper. Notification access detects playback. On POCO/Xiaomi, enable Autostart and set battery usage to No restrictions.")
                .setPositiveButton("OK", null).show()
        }
        findViewById<TextView>(R.id.updateRow).setOnClickListener {
            AlertDialog.Builder(this).setTitle("MusWall").setMessage("Version 1.9.6\nYou are running the local build.").setPositiveButton("OK", null).show()
        }
        findViewById<TextView>(R.id.widgetRow).setOnClickListener {
            ToastCompat.show(this, "Widget customization is not required for the live wallpaper.")
        }

        findViewById<SwitchCompat>(R.id.effectsRestore).apply {
            showText = false
            textOn = ""
            textOff = ""
            isChecked = prefs.effect != PreferencesManager.EFFECT_BLUR
            setOnCheckedChangeListener { _, checked -> if (!checked) prefs.effect = PreferencesManager.EFFECT_BLUR }
        }
    }

    private fun chooseTheme() {
        AlertDialog.Builder(this).setTitle("Theme")
            .setItems(arrayOf("Follow System", "Light", "Dark")) { _, which ->
                AppCompatDelegate.setDefaultNightMode(
                    when (which) {
                        1 -> AppCompatDelegate.MODE_NIGHT_NO
                        2 -> AppCompatDelegate.MODE_NIGHT_YES
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                )
            }.show()
    }

    private fun chooseLanguage() {
        AlertDialog.Builder(this).setTitle("Language")
            .setMessage("System Default\n\nThe interface follows your Android language.")
            .setPositiveButton("OK", null).show()
    }

    private fun chooseBackgroundMode() {
        val values = arrayOf("Album artwork (blurred)", "Solid color", "Gradient", "Auto color from album")
        AlertDialog.Builder(this).setTitle("Wallpaper background").setItems(values) { _, which ->
            prefs.backgroundMode = when (which) {
                1 -> PreferencesManager.BACKGROUND_COLOR
                2 -> PreferencesManager.BACKGROUND_GRADIENT
                3 -> PreferencesManager.BACKGROUND_AUTO
                else -> PreferencesManager.BACKGROUND_ART
            }
            ToastCompat.show(this, "Background style saved")
        }.show()
    }

    private fun chooseColor(second: Boolean) {
        val presets = arrayOf("Black #111111", "White #F7F7F7", "Purple #5E2CA5", "Blue #174EA6", "Green #176B3A", "Red #8B1E2D", "Orange #B85C00")
        val colors = arrayOf("#111111", "#F7F7F7", "#5E2CA5", "#174EA6", "#176B3A", "#8B1E2D", "#B85C00")
        AlertDialog.Builder(this).setTitle(if (second) "Gradient end color" else "Background color")
            .setItems(presets) { _, which ->
                if (second) prefs.backgroundColor2 = colors[which] else prefs.backgroundColor = colors[which]
                ToastCompat.show(this, "Color saved")
            }.show()
    }

    private fun chooseAccentColor() {
        val presets = arrayOf("Purple #7C00FF", "Cyan #00B8D9", "Green #36C95F", "Blue #4285F4", "Pink #E91E63", "White #FFFFFF")
        val colors = arrayOf("#7C00FF", "#00B8D9", "#36C95F", "#4285F4", "#E91E63", "#FFFFFF")
        AlertDialog.Builder(this).setTitle("Cover accent")
            .setItems(presets) { _, which ->
                prefs.accentColor = colors[which]
                ToastCompat.show(this, "Accent color saved")
            }.show()
    }

    private fun chooseOriginalWallpaper(which: Int) {
        val requestCode = if (which == WallpaperManager.FLAG_LOCK) REQUEST_ORIGINAL_LOCK else REQUEST_ORIGINAL_HOME
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, requestCode)
    }

    private fun chooseTiming() {
        AlertDialog.Builder(this).setTitle("Restore timing")
            .setItems(arrayOf("Immediately", "5 seconds", "10 seconds", "30 seconds"), null).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        val which = when (requestCode) {
            REQUEST_ORIGINAL_HOME -> WallpaperManager.FLAG_SYSTEM
            REQUEST_ORIGINAL_LOCK -> WallpaperManager.FLAG_LOCK
            else -> return
        }
        ioScope.launch {
            val ok = wallpaperHelper.setOriginalFromUri(uri, which)
            ToastCompat.show(this@SettingsActivity, if (ok) "Original ${if (which == WallpaperManager.FLAG_LOCK) "lock" else "home"} wallpaper saved" else "Could not save wallpaper")
        }
    }

    override fun onDestroy() {
        ioScope.cancel()
        super.onDestroy()
    }

    object ToastCompat {
        fun show(context: android.content.Context, message: String) =
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val REQUEST_ORIGINAL_HOME = 2002
        private const val REQUEST_ORIGINAL_LOCK = 2003
    }
}
