package com.muswall.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import android.widget.TextView
import com.muswall.app.R
import com.muswall.app.data.PreferencesManager
import com.muswall.app.service.MediaNotificationListenerService
import com.muswall.app.wallpaper.WallpaperHelper
import kotlinx.coroutines.*
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: PreferencesManager
    private lateinit var wallpaperHelper: WallpaperHelper
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var selectedUri: Uri? = null
    private var previewJob: Job? = null
    private var uiReady = false

    private lateinit var imageHome: ImageView
    private lateinit var imageLock: ImageView
    private lateinit var textTrack: TextView
    private lateinit var textArtist: TextView
    private lateinit var textStatus: TextView
    private lateinit var permissionText: TextView

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        selectedUri = uri
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        prefs.staticWallpaperUri = uri.toString()
        loadPreviewFromUri(uri)
        Toast.makeText(this, "Image selected", Toast.LENGTH_SHORT).show()
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MediaNotificationListenerService.ACTION_TRACK_CHANGED -> {
                    textTrack.text = intent.getStringExtra(MediaNotificationListenerService.EXTRA_TRACK_TITLE) ?: "Unknown title"
                    textArtist.text = intent.getStringExtra(MediaNotificationListenerService.EXTRA_ARTIST) ?: "Unknown artist"
                }
                MediaNotificationListenerService.ACTION_PLAYBACK_STATE_CHANGED -> {
                    val playing = intent.getBooleanExtra(MediaNotificationListenerService.EXTRA_IS_PLAYING, false)
                    textStatus.text = if (playing) "● Playing • MusWall is listening" else "Paused • waiting for playback"
                }
                MediaNotificationListenerService.ACTION_WALLPAPER_APPLIED -> {
                    textStatus.text = intent.getStringExtra(MediaNotificationListenerService.EXTRA_STATUS_MESSAGE) ?: "Wallpaper updated"
                    loadCurrentPreview()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = PreferencesManager.getInstance(this)
        wallpaperHelper = WallpaperHelper(this)
        com.muswall.app.python.PythonBridge.initialize(this)

        imageHome = findViewById(R.id.imageHomePreview)
        imageLock = findViewById(R.id.imageLockPreview)
        textTrack = findViewById(R.id.textTrack)
        textArtist = findViewById(R.id.textArtist)
        textStatus = findViewById(R.id.textServiceStatus)
        permissionText = findViewById(R.id.textPermissionStatus)
        uiReady = true

        safeUiInit("modes") { setupModes() }
        safeUiInit("effects") { setupEffects() }
        safeUiInit("sliders") { setupSliders() }
        safeUiInit("actions") { setupActions() }
        safeUiInit("state") {
            if (prefs.staticWallpaperUri.isNotBlank()) selectedUri = runCatching { Uri.parse(prefs.staticWallpaperUri) }.getOrNull()
            restoreUi()
        }
        safeUiInit("preview") {
            if (prefs.wallpaperMode == PreferencesManager.MODE_STATIC && selectedUri != null) loadPreviewFromUri(selectedUri!!)
            else loadCurrentPreview()
        }
    }

    private fun safeUiInit(name: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            android.util.Log.e("MusWall", "Optional UI component failed: $name", t)
            Toast.makeText(this, "Some $name controls are unavailable, but MusWall is still running.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupModes() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.modeGroup)
        group.check(if (prefs.wallpaperMode == PreferencesManager.MODE_STATIC) R.id.modeStatic else R.id.modeMusic)
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val music = id == R.id.modeMusic
            prefs.wallpaperMode = if (music) PreferencesManager.MODE_MUSIC else PreferencesManager.MODE_STATIC
            findViewById<TextView>(R.id.textModeDescription).text = if (music)
                "Music mode: the installed live wallpaper follows the currently playing track."
            else
                "Static mode: choose a fixed wallpaper from your gallery."

            if (music && !prefs.liveWallpaperEnabled) {
                // Music mode never falls back to setBitmap(). Android requires the
                // user to confirm a live wallpaper in the system picker, so launch
                // that picker once when Music mode is enabled.
                prefs.liveWallpaperEnabled = true
                Toast.makeText(this, "Choose MusWall Live Wallpaper to enable automatic music wallpapers.", Toast.LENGTH_LONG).show()
                wallpaperHelper.openLiveWallpaperPicker()
            }
        }
    }

    private fun setupEffects() {
        val effects = findViewById<ChipGroup>(R.id.effectGroup)
        val effectId = when (prefs.effect) {
            PreferencesManager.EFFECT_COVER -> R.id.effectCover
            PreferencesManager.EFFECT_CD -> R.id.effectCd
            PreferencesManager.EFFECT_SQUARE -> R.id.effectSquare
            PreferencesManager.EFFECT_COVER_COLOR -> R.id.effectCoverColor
            else -> R.id.effectBlur
        }
        effects.check(effectId)
        effects.setOnCheckedStateChangeListener { _, ids ->
            if (ids.isEmpty()) return@setOnCheckedStateChangeListener
            prefs.effect = when (ids[0]) {
                R.id.effectCover -> PreferencesManager.EFFECT_COVER
                R.id.effectCd -> PreferencesManager.EFFECT_CD
                R.id.effectSquare -> PreferencesManager.EFFECT_SQUARE
                R.id.effectCoverColor -> PreferencesManager.EFFECT_COVER_COLOR
                else -> PreferencesManager.EFFECT_BLUR
            }
            schedulePreview()
        }

        val blur = findViewById<ChipGroup>(R.id.blurGroup)
        blur.check(when (prefs.blurType) {
            PreferencesManager.BLUR_SOLID -> R.id.blurSolid
            PreferencesManager.BLUR_MOTION -> R.id.blurMotion
            PreferencesManager.BLUR_GLASS -> R.id.blurGlass
            else -> R.id.blurGaussian
        })
        blur.setOnCheckedStateChangeListener { _, ids ->
            if (ids.isEmpty()) return@setOnCheckedStateChangeListener
            prefs.blurType = when (ids[0]) {
                R.id.blurSolid -> PreferencesManager.BLUR_SOLID
                R.id.blurMotion -> PreferencesManager.BLUR_MOTION
                R.id.blurGlass -> PreferencesManager.BLUR_GLASS
                else -> PreferencesManager.BLUR_GAUSSIAN
            }
            schedulePreview()
        }
    }

    private fun setupSliders() {
        bindSlider(R.id.sliderBlur, R.id.labelBlur, "Blur") { prefs.blurRadius = it }
        bindSlider(R.id.sliderCoverHeight, R.id.labelCoverHeight, "Cover Height") { prefs.coverHeight = it }
        bindSlider(R.id.sliderCoverOffset, R.id.labelCoverOffset, "Cover Offset") { prefs.coverOffset = it }
        bindSlider(R.id.sliderTransition, R.id.labelTransition, "Transition height") { prefs.transitionHeight = it }
        bindSlider(R.id.sliderDarkness, R.id.labelDarkness, "Darken") { prefs.darkness = it }
        bindSlider(R.id.sliderArtScale, R.id.labelScale, "Cover Scale") { prefs.artScale = it }
    }

    private fun bindSlider(sliderId: Int, labelId: Int, name: String, save: (Int) -> Unit) {
        val slider = findViewById<Slider>(sliderId)
        val label = findViewById<TextView>(labelId)
        val savedValue = when (sliderId) {
            R.id.sliderBlur -> prefs.blurRadius
            R.id.sliderCoverHeight -> prefs.coverHeight
            R.id.sliderCoverOffset -> prefs.coverOffset
            R.id.sliderTransition -> prefs.transitionHeight
            R.id.sliderDarkness -> prefs.darkness
            else -> prefs.artScale
        }
        slider.value = savedValue.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
        label.text = "$name   ${slider.value.toInt()}"
        slider.addOnChangeListener { _, value, _ ->
            val n = value.toInt()
            save(n)
            label.text = "$name   $n"
            schedulePreview()
        }
    }

    private fun setupActions() {
        findViewById<View>(R.id.btnAddImage).setOnClickListener { pickImage.launch(arrayOf("image/*")) }
        findViewById<View>(R.id.galleryPlaceholder).setOnClickListener { pickImage.launch(arrayOf("image/*")) }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchAutoWallpaper).apply {
            isChecked = prefs.isAutoEnabled
            setOnCheckedChangeListener { _, checked -> prefs.isAutoEnabled = checked }
        }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchRestoreOnPause).apply {
            isChecked = prefs.restoreOnPause
            setOnCheckedChangeListener { _, checked -> prefs.restoreOnPause = checked }
        }
        findViewById<MaterialButton>(R.id.btnGrantPermission).setOnClickListener { XiaomiHelper.openNotificationListenerSettings(this) }
        findViewById<MaterialButton>(R.id.btnLiveWallpaper).setOnClickListener {
            prefs.liveWallpaperEnabled = true
            wallpaperHelper.openLiveWallpaperPicker()
        }
        findViewById<MaterialButton>(R.id.btnOpenAutostart).setOnClickListener { XiaomiHelper.openAutostartSettings(this) }
        findViewById<MaterialButton>(R.id.btnOpenBatterySaver).setOnClickListener { XiaomiHelper.openBatterySaverSettings(this) }
        findViewById<MaterialButton>(R.id.btnApply).setOnClickListener { applyCurrent() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        findViewById<View>(R.id.btnShare).setOnClickListener { shareCurrent() }
        findViewById<View>(R.id.btnPro).setOnClickListener {
            Toast.makeText(this, "Pro features are unlocked by the app design; no account is required for the core features.", Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreUi() {
        textTrack.text = prefs.lastTrackTitle.ifBlank { "No music detected" }
        textArtist.text = prefs.lastArtist.ifBlank { "Enable music detection below" }
        permissionText.text = if (isNotificationAccessEnabled()) "✓ Notification access enabled. Music detection is ready."
        else "Notification access is OFF. Enable it before automatic music wallpapers can work."
        textStatus.text = if (prefs.liveWallpaperEnabled) "Live wallpaper mode" else "Ready"
    }

    private fun schedulePreview() {
        previewJob?.cancel()
        previewJob = uiScope.launch {
            delay(80)
            if (prefs.wallpaperMode == PreferencesManager.MODE_STATIC && selectedUri != null) loadPreviewFromUri(selectedUri!!)
            else loadCurrentPreview()
        }
    }

    private fun decodeSampled(file: File, maxWidth: Int = 720, maxHeight: Int = 1280): android.graphics.Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > maxWidth || bounds.outHeight / sample > maxHeight) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            })
        } catch (t: Throwable) {
            android.util.Log.w("MusWall", "Image decode failed", t)
            null
        }
    }

    private fun loadCurrentPreview() {
        val file = File(filesDir, WallpaperHelper.FILE_CURRENT)
        if (!file.exists()) return
        val bitmap = decodeSampled(file) ?: return
        imageHome.setImageBitmap(bitmap)
        imageLock.setImageBitmap(bitmap)
    }

    private fun loadPreviewFromUri(uri: Uri) {
        uiScope.launch(Dispatchers.IO) {
            val bitmap = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 720 || bounds.outHeight / sample > 1280) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    })
                }
            } catch (t: Throwable) { null }
            withContext(Dispatchers.Main) {
                if (bitmap != null && !isFinishing && !isDestroyed) {
                    imageHome.setImageBitmap(bitmap)
                    imageLock.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun decodeUriForRender(uri: Uri): android.graphics.Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                })
            }
        } catch (t: Throwable) { null }
    }

    private fun applyCurrent() {
        uiScope.launch {
            if (prefs.wallpaperMode == PreferencesManager.MODE_MUSIC) {
                // Never replace a music wallpaper with a static wallpaper.
                prefs.liveWallpaperEnabled = true
                wallpaperHelper.openLiveWallpaperPicker()
                return@launch
            }

            val source = selectedUri?.let { decodeUriForRender(it) }
            if (source == null) {
                Toast.makeText(this@MainActivity, "Choose an image first", Toast.LENGTH_LONG).show()
                return@launch
            }
            val dm = resources.displayMetrics
            val rendered = com.muswall.app.python.PythonBridge.generateWallpaper(
                source,
                (dm.widthPixels * 0.75f).toInt().coerceIn(480, 900),
                (dm.heightPixels * 0.75f).toInt().coerceIn(960, 1800),
                prefs.blurRadius.toFloat(), prefs.darkness / 100f, prefs.artScale / 100f,
                42, true, prefs.effect, prefs.blurType, prefs.coverHeight, prefs.coverOffset, prefs.transitionHeight
            )
            if (rendered == null) {
                if (!source.isRecycled) source.recycle()
                Toast.makeText(this@MainActivity, "Could not render wallpaper", Toast.LENGTH_LONG).show()
                return@launch
            }
            val result = wallpaperHelper.applyStatic(rendered, prefs.targetScreen)
            if (result.success) {
                wallpaperHelper.saveCurrentForLiveWallpaper(rendered)
                if (!rendered.isRecycled) rendered.recycle()
                loadCurrentPreview()
            } else if (!rendered.isRecycled) rendered.recycle()
            if (!source.isRecycled) source.recycle()
            Toast.makeText(this@MainActivity, if (result.success) "Wallpaper applied" else "Failed: ${result.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Save wallpaper")
            menu.add("History")
            menu.add("Settings")
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    "Save wallpaper" -> saveCurrent()
                    "History" -> showHistory()
                    "Settings" -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
                true
            }
            show()
        }
    }

    private fun saveCurrent() {
        val src = File(filesDir, WallpaperHelper.FILE_CURRENT)
        if (!src.exists()) { Toast.makeText(this, "No generated wallpaper yet", Toast.LENGTH_SHORT).show(); return }
        val history = File(filesDir, "history").apply { mkdirs() }
        src.copyTo(File(history, "MusWall_${System.currentTimeMillis()}.jpg"), overwrite = true)
        Toast.makeText(this, "Saved to MusWall history", Toast.LENGTH_SHORT).show()
    }

    private fun showHistory() {
        val history = File(filesDir, "history")
        val items = history.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (items.isEmpty()) { Toast.makeText(this, "History is empty", Toast.LENGTH_SHORT).show(); return }
        android.app.AlertDialog.Builder(this).setTitle("History")
            .setMessage(items.take(20).joinToString("\n") { it.name })
            .setPositiveButton("OK", null).show()
    }

    private fun shareCurrent() {
        val file = File(filesDir, WallpaperHelper.FILE_CURRENT)
        if (!file.exists()) Toast.makeText(this, "No wallpaper to share", Toast.LENGTH_SHORT).show()
        else Toast.makeText(this, "Save the wallpaper first, then share it from your gallery.", Toast.LENGTH_LONG).show()
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val component = "$packageName/${MediaNotificationListenerService::class.java.name}"
        return try {
            val raw = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
            !TextUtils.isEmpty(raw) && raw.split(":").any { it.equals(component, true) }
        } catch (_: Exception) { false }
    }

    override fun onStart() {
        super.onStart()
        if (!uiReady) return
        try {
            val filter = IntentFilter().apply {
                addAction(MediaNotificationListenerService.ACTION_TRACK_CHANGED)
                addAction(MediaNotificationListenerService.ACTION_PLAYBACK_STATE_CHANGED)
                addAction(MediaNotificationListenerService.ACTION_WALLPAPER_APPLIED)
            }
            ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        if (uiReady) restoreUi()
    }

    override fun onStop() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onStop()
    }

    override fun onDestroy() {
        previewJob?.cancel()
        uiScope.cancel()
        super.onDestroy()
    }
}
