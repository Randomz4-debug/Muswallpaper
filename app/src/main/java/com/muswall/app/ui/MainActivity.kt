package com.muswall.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.muswall.app.R
import com.muswall.app.data.PreferencesManager
import com.muswall.app.service.MediaNotificationListenerService
import com.muswall.app.wallpaper.WallpaperHelper

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: PreferencesManager
    private lateinit var wallpaperHelper: WallpaperHelper
    private lateinit var textTrack: TextView
    private lateinit var textArtist: TextView
    private lateinit var textServiceStatus: TextView
    private lateinit var textPermissionStatus: TextView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MediaNotificationListenerService.ACTION_TRACK_CHANGED -> {
                    textTrack.text = intent.getStringExtra(MediaNotificationListenerService.EXTRA_TRACK_TITLE) ?: "Unknown track"
                    textArtist.text = intent.getStringExtra(MediaNotificationListenerService.EXTRA_ARTIST) ?: "Unknown artist"
                }
                MediaNotificationListenerService.ACTION_PLAYBACK_STATE_CHANGED -> {
                    val playing = intent.getBooleanExtra(MediaNotificationListenerService.EXTRA_IS_PLAYING, false)
                    textServiceStatus.text = if (playing) "● Music is playing • MusWall is listening" else "Music is paused • waiting for playback"
                }
                MediaNotificationListenerService.ACTION_WALLPAPER_APPLIED -> {
                    textServiceStatus.text = intent.getStringExtra(MediaNotificationListenerService.EXTRA_STATUS_MESSAGE) ?: "Wallpaper updated"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = PreferencesManager.getInstance(this)
        wallpaperHelper = WallpaperHelper(this)

        textTrack = findViewById(R.id.textTrack)
        textArtist = findViewById(R.id.textArtist)
        textServiceStatus = findViewById(R.id.textServiceStatus)
        textPermissionStatus = findViewById(R.id.textPermissionStatus)

        val sliderBlur = findViewById<Slider>(R.id.sliderBlur)
        val sliderDarkness = findViewById<Slider>(R.id.sliderDarkness)
        val sliderArtScale = findViewById<Slider>(R.id.sliderArtScale)
        val switchAuto = findViewById<SwitchMaterial>(R.id.switchAutoWallpaper)
        val switchRestore = findViewById<SwitchMaterial>(R.id.switchRestoreOnPause)
        val radioTarget = findViewById<RadioGroup>(R.id.radioTarget)

        sliderBlur.value = prefs.blurRadius.toFloat()
        sliderDarkness.value = prefs.darkness.toFloat()
        sliderArtScale.value = prefs.artScale.toFloat()
        switchAuto.isChecked = prefs.isAutoEnabled
        switchRestore.isChecked = prefs.restoreOnPause

        when (prefs.targetScreen) {
            PreferencesManager.TARGET_HOME -> radioTarget.check(R.id.radioHome)
            PreferencesManager.TARGET_LOCK -> radioTarget.check(R.id.radioLock)
            else -> radioTarget.check(R.id.radioBoth)
        }

        sliderBlur.addOnChangeListener { _, value, _ -> prefs.blurRadius = value.toInt() }
        sliderDarkness.addOnChangeListener { _, value, _ -> prefs.darkness = value.toInt() }
        sliderArtScale.addOnChangeListener { _, value, _ -> prefs.artScale = value.toInt() }
        switchAuto.setOnCheckedChangeListener { _, checked -> prefs.isAutoEnabled = checked }
        switchRestore.setOnCheckedChangeListener { _, checked -> prefs.restoreOnPause = checked }

        radioTarget.setOnCheckedChangeListener { _, checkedId ->
            prefs.targetScreen = when (checkedId) {
                R.id.radioHome -> PreferencesManager.TARGET_HOME
                R.id.radioLock -> PreferencesManager.TARGET_LOCK
                else -> PreferencesManager.TARGET_BOTH
            }
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
        findViewById<MaterialButton>(R.id.btnReset).setOnClickListener {
            prefs.blurRadius = 35
            prefs.darkness = 45
            prefs.artScale = 72
            sliderBlur.value = 35f
            sliderDarkness.value = 45f
            sliderArtScale.value = 72f
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(MediaNotificationListenerService.ACTION_TRACK_CHANGED)
            addAction(MediaNotificationListenerService.ACTION_PLAYBACK_STATE_CHANGED)
            addAction(MediaNotificationListenerService.ACTION_WALLPAPER_APPLIED)
        }
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val component = "${packageName}/${MediaNotificationListenerService::class.java.name}"
        val enabled = try {
            val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
            !TextUtils.isEmpty(listeners) && listeners.split(":").any { it.equals(component, ignoreCase = true) }
        } catch (_: Exception) {
            false
        }
        textPermissionStatus.text = if (enabled) {
            "✓ Notification access is enabled. MusWall can detect currently playing music."
        } else {
            "Notification access is OFF. Enable it before automatic music wallpapers can work."
        }
        textServiceStatus.text = if (enabled) "Music detection is ready" else "Music detection is not connected"
    }

    override fun onStop() {
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }
        super.onStop()
    }
}
