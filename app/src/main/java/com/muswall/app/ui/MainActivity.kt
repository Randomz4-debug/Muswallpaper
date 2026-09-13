package com.muswall.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.muswall.app.R
import com.muswall.app.data.PreferencesManager
import com.muswall.app.python.PythonBridge
import com.muswall.app.service.MediaNotificationListenerService
import com.muswall.app.wallpaper.WallpaperHelper
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: PreferencesManager
    private lateinit var wallpaperHelper: WallpaperHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = PreferencesManager.getInstance(this)
        wallpaperHelper = WallpaperHelper(this)

        findViewById<Slider>(R.id.sliderBlur).addOnChangeListener { _, value, _ ->
            prefs.blurRadius = value.toInt()
        }
        findViewById<Slider>(R.id.sliderDarkness).addOnChangeListener { _, value, _ ->
            prefs.darkness = value.toInt()
        }
        findViewById<Slider>(R.id.sliderArtScale).addOnChangeListener { _, value, _ ->
            prefs.artScale = value.toInt()
        }
        findViewById<SwitchMaterial>(R.id.switchAutoWallpaper).setOnCheckedChangeListener { _, isChecked ->
            prefs.isAutoEnabled = isChecked
        }
        findViewById<SwitchMaterial>(R.id.switchRestoreOnPause).setOnCheckedChangeListener { _, isChecked ->
            prefs.restoreOnPause = isChecked
        }
        findViewById<MaterialButton>(R.id.btnGrantPermission).setOnClickListener {
            XiaomiHelper.openNotificationListenerSettings(this)
        }
        findViewById<MaterialButton>(R.id.btnOpenAutostart).setOnClickListener {
            XiaomiHelper.openAutostartSettings(this)
        }
        findViewById<MaterialButton>(R.id.btnOpenBatterySaver).setOnClickListener {
            XiaomiHelper.openBatterySaverSettings(this)
        }
    }
}