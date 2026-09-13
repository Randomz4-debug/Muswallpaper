package com.muswall.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.muswall.app.R
import com.muswall.app.data.PreferencesManager

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager.getInstance(this)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.themeRow).setOnClickListener { chooseTheme() }
        findViewById<TextView>(R.id.languageRow).setOnClickListener { chooseLanguage() }
        findViewById<TextView>(R.id.restoreRow).setOnClickListener { chooseRestoreWallpaper() }
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
            AlertDialog.Builder(this).setTitle("MusWall").setMessage("Version 1.9.0\nYou are running the local build.").setPositiveButton("OK", null).show()
        }
        findViewById<TextView>(R.id.widgetRow).setOnClickListener {
            ToastCompat.show(this, "Widget customization is reserved for a future widget module; wallpaper controls are already available.")
        }
        findViewById<MaterialSwitch>(R.id.effectsRestore).apply {
            isChecked = prefs.effect != PreferencesManager.EFFECT_BLUR
            setOnCheckedChangeListener { _, checked -> if (!checked) prefs.effect = PreferencesManager.EFFECT_BLUR }
        }
    }

    private fun chooseTheme() {
        AlertDialog.Builder(this).setTitle("Theme")
            .setItems(arrayOf("Follow System", "Light", "Dark")) { _, which ->
                val mode = when (which) { 1 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO; 2 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES; else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM }
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
            }.show()
    }

    private fun chooseLanguage() {
        AlertDialog.Builder(this).setTitle("Language").setMessage("System Default\n\nThe interface follows your Android language. Additional translations can be added later without changing the wallpaper engine.").setPositiveButton("OK", null).show()
    }

    private fun chooseRestoreWallpaper() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }
        startActivityForResult(i, 2001)
    }

    private fun chooseTiming() {
        val values = arrayOf("Immediately", "5 seconds", "10 seconds", "30 seconds")
        AlertDialog.Builder(this).setTitle("Restore timing").setItems(values, null).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK && data?.data != null) {
            prefs.staticWallpaperUri = data.data.toString()
        }
    }

    object ToastCompat {
        fun show(context: android.content.Context, message: String) = android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
    }
}
