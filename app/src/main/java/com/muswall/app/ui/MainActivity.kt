package com.muswall.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.muswall.app.R
import com.muswall.app.data.PreferencesManager
import com.muswall.app.wallpaper.WallpaperHelper

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: PreferencesManager
    private lateinit var wallpaperHelper: WallpaperHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = PreferencesManager.getInstance(this)
        wallpaperHelper = WallpaperHelper(this)

        val sliderBlur = findViewById<Slider>(R.id.sliderBlur)
        val sliderDarkness = findViewById<Slider>(R.id.sliderDarkness)
        val sliderArtScale = findViewById<Slider>(R.id.sliderArtScale)
        val switchAuto = findViewById<SwitchMaterial>(R.id.switchAutoWallpaper)
        val switchRestore = findViewById<SwitchMaterial>(R.id.switchRestoreOnPause)

        sliderBlur.value = prefs.blurRadius.toFloat()
        sliderDarkness.value = prefs.darkness.toFloat()
        sliderArtScale.value = prefs.artScale.toFloat()
        switchAuto.isChecked = prefs.isAutoEnabled
        switchRestore.isChecked = prefs.restoreOnPause

        sliderBlur.addOnChangeListener { _, value, _ ->
            prefs.blurRadius = value.toInt()
        }
        sliderDarkness.addOnChangeListener { _, value, _ ->
            prefs.darkness = value.toInt()
        }
        sliderArtScale.addOnChangeListener { _, value, _ ->
            prefs.artScale = value.toInt()
        }
        switchAuto.setOnCheckedChangeListener { _, isChecked ->
            prefs.isAutoEnabled = isChecked
        }
        switchRestore.setOnCheckedChangeListener { _, isChecked ->
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