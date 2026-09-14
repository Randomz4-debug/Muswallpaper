package com.muswall.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.muswall.app.R
import com.muswall.app.data.PreferencesManager
import com.muswall.app.python.PythonBridge
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
    private var renderJob: Job? = null
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
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
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
                    textStatus.text = if (playing) "● Playing" else "Paused"
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
        PythonBridge.initialize(this)

        imageHome = findViewById(R.id.imageHomePreview)
        imageLock = findViewById(R.id.imageLockPreview)
        textTrack = findViewById(R.id.textTrack)
        textArtist = findViewById(R.id.textArtist)
        textStatus = findViewById(R.id.textServiceStatus)
        permissionText = findViewById(R.id.textPermissionStatus)
        uiReady = true

        runUi("modes") { setupModes() }
        runUi("effects") { setupEffects() }
        runUi("sliders") { setupSliders() }
        runUi("actions") { setupActions() }
        runUi("state") { restoreUi() }
        runUi("preview") {
            selectedUri = prefs.staticWallpaperUri.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
            if (prefs.wallpaperMode == PreferencesManager.MODE_STATIC && selectedUri != null) loadPreviewFromUri(selectedUri!!)
            else loadCurrentPreview()
        }
    }

    private fun runUi(name: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            android.util.Log.e("MusWall", "UI init failed: $name", t)
            Toast.makeText(this, "MusWall could not load one $name control", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupModes() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.modeGroup)
        group.check(if (prefs.wallpaperMode == PreferencesManager.MODE_STATIC) R.id.modeStatic else R.id.modeMusic)
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            prefs.wallpaperMode = if (id == R.id.modeStatic) PreferencesManager.MODE_STATIC else PreferencesManager.MODE_MUSIC
            findViewById<TextView>(R.id.textModeDescription).text = if (prefs.wallpaperMode == PreferencesManager.MODE_STATIC)
                "Static mode: choose a fixed image and apply it with the Apply button."
            else "Music mode: the installed MusWall live wallpaper follows playback without replacing the system wallpaper."
            if (prefs.wallpaperMode == PreferencesManager.MODE_MUSIC && prefs.liveWallpaperEnabled) rerenderCurrentMusicWallpaper()
        }
    }

    private fun setupEffects() {
        val effects = findViewById<ChipGroup>(R.id.effectGroup)
        effects.check(when (prefs.effect) {
            PreferencesManager.EFFECT_COVER -> R.id.effectCover
            PreferencesManager.EFFECT_CD -> R.id.effectCd
            PreferencesManager.EFFECT_SQUARE -> R.id.effectSquare
            PreferencesManager.EFFECT_COVER_COLOR -> R.id.effectCoverColor
            else -> R.id.effectBlur
        })
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
        val saved = when (sliderId) {
            R.id.sliderBlur -> prefs.blurRadius
            R.id.sliderCoverHeight -> prefs.coverHeight
            R.id.sliderCoverOffset -> prefs.coverOffset
            R.id.sliderTransition -> prefs.transitionHeight
            R.id.sliderDarkness -> prefs.darkness
            else -> prefs.artScale
        }
        slider.value = saved.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
        label.text = "$name   ${slider.value.toInt()}"
        slider.addOnChangeListener { _, value, _ ->
            save(value.toInt())
            label.text = "$name   ${value.toInt()}"
            schedulePreview()
        }
    }

    private fun setupActions() {
        findViewById<View>(R.id.btnAddImage).setOnClickListener { pickImage.launch(arrayOf("image/*")) }
        findViewById<View>(R.id.galleryPlaceholder).setOnClickListener { pickImage.launch(arrayOf("image/*")) }
        findViewById<SwitchCompat>(R.id.switchAutoWallpaper).apply {
            isChecked = prefs.isAutoEnabled
            setOnCheckedChangeListener { _, checked -> prefs.isAutoEnabled = checked }
        }
        findViewById<SwitchCompat>(R.id.switchRestoreOnPause).apply {
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
        findViewById<View>(R.id.btnPro).setOnClickListener { Toast.makeText(this, "Core MusWall features are available without an account.", Toast.LENGTH_SHORT).show() }
    }

    private fun restoreUi() {
        textTrack.text = prefs.lastTrackTitle.ifBlank { "No music detected" }
        textArtist.text = prefs.lastArtist.ifBlank { "Enable music detection below" }
        permissionText.text = if (isNotificationAccessEnabled()) "✓ Notification access enabled. Music detection is ready." else "Notification access is OFF. Enable it before automatic music wallpapers can work."
        textStatus.text = if (prefs.liveWallpaperEnabled) "Live wallpaper" else "Ready"
    }

    private fun schedulePreview() {
        previewJob?.cancel()
        previewJob = uiScope.launch {
            delay(120)
            if (prefs.wallpaperMode == PreferencesManager.MODE_STATIC && selectedUri != null) {
                loadPreviewFromUri(selectedUri!!)
            } else if (prefs.wallpaperMode == PreferencesManager.MODE_MUSIC && prefs.liveWallpaperEnabled) {
                rerenderCurrentMusicWallpaper()
            } else {
                loadCurrentPreview()
            }
        }
    }

    private fun rerenderCurrentMusicWallpaper() {
        val file = wallpaperHelper.lastArtworkFile()
        if (!file.exists() || !prefs.liveWallpaperEnabled) return
        renderJob?.cancel()
        renderJob = uiScope.launch(Dispatchers.Default) {
            val source = decodeSampled(file, 900, 900) ?: return@launch
            try {
                val dm = resources.displayMetrics
                val result = PythonBridge.generateWallpaper(
                    source,
                    (dm.widthPixels * 0.58f).toInt().coerceIn(480, 720),
                    (dm.heightPixels * 0.58f).toInt().coerceIn(900, 1440),
                    prefs.blurRadius.toFloat(), prefs.darkness / 100f, prefs.artScale / 100f,
                    42, true, prefs.effect, prefs.blurType, prefs.coverHeight, prefs.coverOffset, prefs.transitionHeight
                ) ?: return@launch
                try {
                    wallpaperHelper.saveCurrentForLiveWallpaper(result)
                    if (prefs.liveMusicPlaying) sendBroadcast(Intent(com.muswall.app.wallpaper.MusicWallpaperService.ACTION_REFRESH).setPackage(packageName))
                    withContext(Dispatchers.Main) { loadCurrentPreview() }
                } finally { if (!result.isRecycled) result.recycle() }
            } finally { if (!source.isRecycled) source.recycle() }
        }
    }

    private fun decodeSampled(file: File, maxWidth: Int, maxHeight: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxWidth || bounds.outHeight / sample > maxHeight) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    } catch (t: Throwable) {
        android.util.Log.w("MusWall", "Image decode failed", t)
        null
    }

    private fun loadCurrentPreview() {
        val file = File(filesDir, WallpaperHelper.FILE_CURRENT)
        val bitmap = if (file.exists()) decodeSampled(file, 720, 1280) else null
        if (bitmap != null) {
            imageHome.setImageBitmap(bitmap)
            imageLock.setImageBitmap(bitmap)
        }
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
                        inPreferredConfig = Bitmap.Config.RGB_565
                    })
                }
            } catch (_: Throwable) { null }
            withContext(Dispatchers.Main) {
                if (bitmap != null && !isFinishing && !isDestroyed) {
                    imageHome.setImageBitmap(bitmap)
                    imageLock.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun decodeUriForRender(uri: Uri): Bitmap? = try {
        contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }
    } catch (_: Throwable) { null }

    private fun applyCurrent() {
        uiScope.launch {
            if (prefs.wallpaperMode == PreferencesManager.MODE_MUSIC) {
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
            val rendered = PythonBridge.generateWallpaper(
                source, (dm.widthPixels * 0.75f).toInt().coerceIn(480, 900), (dm.heightPixels * 0.75f).toInt().coerceIn(960, 1800),
                prefs.blurRadius.toFloat(), prefs.darkness / 100f, prefs.artScale / 100f,
                42, true, prefs.effect, prefs.blurType, prefs.coverHeight, prefs.coverOffset, prefs.transitionHeight
            )
            if (rendered == null) {
                if (!source.isRecycled) source.recycle()
                Toast.makeText(this@MainActivity, "Could not render wallpaper", Toast.LENGTH_LONG).show()
                return@launch
            }
            try {
                val result = wallpaperHelper.applyStatic(rendered, prefs.targetScreen)
                if (result.success) loadCurrentPreview()
                Toast.makeText(this@MainActivity, if (result.success) "Static wallpaper applied" else "Failed: ${result.message}", Toast.LENGTH_LONG).show()
            } finally {
                if (!rendered.isRecycled) rendered.recycle()
                if (!source.isRecycled) source.recycle()
            }
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
        Toast.makeText(this, if (items.isEmpty()) "History is empty" else "${items.size} wallpaper(s) in history", Toast.LENGTH_SHORT).show()
    }

    private fun shareCurrent() = Toast.makeText(this, "Save the wallpaper first, then share it from your gallery.", Toast.LENGTH_LONG).show()

    private fun isNotificationAccessEnabled(): Boolean {
        val component = "$packageName/${MediaNotificationListenerService::class.java.name}"
        return try {
            val raw = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
            !TextUtils.isEmpty(raw) && raw.split(":").any { it.equals(component, true) }
        } catch (_: Throwable) { false }
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
        } catch (t: Throwable) { android.util.Log.w("MusWall", "Receiver registration failed", t) }
    }

    override fun onResume() {
        super.onResume()
        if (uiReady) {
            restoreUi()
            if (prefs.wallpaperMode == PreferencesManager.MODE_MUSIC && prefs.liveWallpaperEnabled) loadCurrentPreview()
        }
    }

    override fun onStop() {
        try { unregisterReceiver(statusReceiver) } catch (_: Throwable) {}
        super.onStop()
    }

    override fun onDestroy() {
        previewJob?.cancel()
        renderJob?.cancel()
        uiScope.cancel()
        super.onDestroy()
    }
}
