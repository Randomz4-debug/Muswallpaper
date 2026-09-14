package com.muswall.app.ui

import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.Intent
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
        findViewById<TextView>(R.id.restoreHomeRow).setOnClickListener { chooseOriginalWallpaper(WallpaperManager.FLAG_SYSTEM) }
        findViewById<TextView>(R.id.restoreLockRow).setOnClickListener { chooseOriginalWallpaper(WallpaperManager.FLAG_LOCK) }
        findViewById<TextView>(R.id.timingRow).setOnClickListener { chooseTiming() }
        findViewById<TextView>(R.id.permissionsRow).setOnClickListener { XiaomiHelper.openNotificationListenerSettings(this) }
        findViewById<TextView>(R.id.serviceRow).setOnClickListener { XiaomiHelper.openNotificationListenerSettings(this) }
        findViewById<TextView>(R.id.compatibilityRow).setOnClickListener {
            ToastCompat.show(this, "Compatibility mode uses instant wallpaper switching and avoids transition work.")
        }
        findViewById<TextView>(R.id.feedbackRow).setOnClickListener {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            i.putExtra(Intent.EXTRA_SUBJECT, "MusWall feedback")
            runCatching { startActivity(i) }
        }
        findViewById<TextView>(R.id.faqRow).setOnClickListener {
            AlertDialog.Builder(this).setTitle("MusWall FAQ")
                .setMessage("Music wallpapers require Notification access. On POCO/Xiaomi, enable Autostart and set battery usage to No restrictions. Live wallpaper must be confirmed by Android's system picker.")
                .setPositiveButton("OK", null).show()
        }
        findViewById<TextView>(R.id.updateRow).setOnClickListener {
            AlertDialog.Builder(this).setTitle("MusWall").setMessage("Version 1.9.4\nYou are running the local build.").setPositiveButton("OK", null).show()
        }
        findViewById<TextView>(R.id.widgetRow).setOnClickListener {
            ToastCompat.show(this, "Widget customization is reserved for a future widget module; wallpaper controls are already available.")
        }

        // Use SwitchCompat with showText disabled. MaterialSwitch was triggering
        // the same StaticLayout/CharSequence crash seen on this POCO build.
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
                val mode = when (which) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                AppCompatDelegate.setDefaultNightMode(mode)
            }.show()
    }

    private fun chooseLanguage() {
        AlertDialog.Builder(this).setTitle("Language")
            .setMessage("System Default\n\nThe interface follows your Android language.")
            .setPositiveButton("OK", null).show()
    }

    private fun chooseOriginalWallpaper(which: Int) {
        val requestCode = if (which == WallpaperManager.FLAG_LOCK) REQUEST_ORIGINAL_LOCK else REQUEST_ORIGINAL_HOME
        val title = if (which == WallpaperManager.FLAG_LOCK) "Choose original lock-screen wallpaper" else "Choose original home-screen wallpaper"
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        ToastCompat.show(this, title)
        startActivityForResult(intent, requestCode)
    }

    private fun chooseTiming() {
        val values = arrayOf("Immediately", "5 seconds", "10 seconds", "30 seconds")
        AlertDialog.Builder(this).setTitle("Restore timing").setItems(values, null).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) {}

        val which = when (requestCode) {
            REQUEST_ORIGINAL_HOME -> WallpaperManager.FLAG_SYSTEM
            REQUEST_ORIGINAL_LOCK -> WallpaperManager.FLAG_LOCK
            else -> return
        }

        ioScope.launch {
            val ok = wallpaperHelper.setOriginalFromUri(uri, which)
            ToastCompat.show(
                this@SettingsActivity,
                if (ok) {
                    if (which == WallpaperManager.FLAG_LOCK) "Original lock-screen wallpaper saved"
                    else "Original home-screen wallpaper saved"
                } else "Could not save that wallpaper"
            )
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
