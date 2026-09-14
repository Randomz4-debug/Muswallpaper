package com.muswall.app.service

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.MediaSessionManager
import android.media.MediaMetadata
import androidx.annotation.RequiresApi
import com.muswall.app.data.PreferencesManager
import com.muswall.app.python.PythonBridge
import com.muswall.app.wallpaper.MusicWallpaperService
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

class MediaNotificationListenerService : NotificationListenerService() {
    companion object {
        const val ACTION_TRACK_CHANGED = "com.muswall.app.TRACK_CHANGED"
        const val ACTION_PLAYBACK_STATE_CHANGED = "com.muswall.app.PLAYBACK_STATE_CHANGED"
        const val ACTION_WALLPAPER_APPLIED = "com.muswall.app.WALLPAPER_APPLIED"
        const val EXTRA_TRACK_TITLE = "track_title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_STATUS_MESSAGE = "status_message"
        private const val TAG = "MusWallMedia"
    }

    private lateinit var prefs: PreferencesManager
    private lateinit var sessionManager: MediaSessionManager
    private var activeController: MediaControllerCompat? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var generationJob: Job? = null
    private val generation = AtomicLong(0)
    private val notificationArtwork = ConcurrentHashMap<String, Bitmap>()

    private val callback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            handlePlayback(state)
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            handleMetadata(activeController, metadata, force = true)
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        chooseController(controllers)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager.getInstance(this)
        sessionManager = getSystemService(MediaSessionManager::class.java)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, ComponentName(this, javaClass))
        }
        refreshActiveSessions()
    }

    private fun refreshActiveSessions() {
        runCatching {
            chooseController(sessionManager.getActiveSessions(ComponentName(this, javaClass)))
        }.onFailure { android.util.Log.w(TAG, "Could not query active sessions", it) }
    }

    private fun chooseController(controllers: List<MediaControllerCompat>?) {
        val list = controllers.orEmpty()
        val selected = list.firstOrNull { isPlaying(it) }
            ?: list.firstOrNull { it.metadata != null }
            ?: list.firstOrNull()

        if (selected?.sessionToken != activeController?.sessionToken) {
            runCatching { activeController?.unregisterCallback(callback) }
            activeController = selected
            runCatching { selected?.registerCallback(callback) }
        }

        selected?.let {
            handlePlayback(it.playbackState)
            handleMetadata(it, it.metadata, force = true)
        }
    }

    private fun isPlaying(controller: MediaControllerCompat): Boolean =
        controller.playbackState?.state == PlaybackStateCompat.STATE_PLAYING

    private fun handlePlayback(state: PlaybackStateCompat?) {
        val playing = state?.state == PlaybackStateCompat.STATE_PLAYING
        if (!playing) {
            generation.incrementAndGet()
            generationJob?.cancel()
            prefs.liveMusicPlaying = false
            broadcastPlaybackState(false)
            refreshLive()
            return
        }

        prefs.liveMusicPlaying = true
        broadcastPlaybackState(true)
        activeController?.let { handleMetadata(it, it.metadata, force = true) }
    }

    private fun handleMetadata(controller: MediaControllerCompat?, metadata: MediaMetadata?, force: Boolean) {
        if (metadata == null) return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        val packageName = controller?.packageName.orEmpty()
        val id = listOf(packageName, metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(), title, artist,
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI).orEmpty(),
            metadata.getString(MediaMetadata.METADATA_KEY_ART_URI).orEmpty()).joinToString("|")
        if (!force && id == prefs.lastTrackTitle + "|" + prefs.lastArtist) return
        prefs.lastTrackTitle = title
        prefs.lastArtist = artist
        broadcastTrack(title, artist)

        if (prefs.wallpaperMode != PreferencesManager.MODE_MUSIC || !prefs.liveWallpaperEnabled) {
            return
        }

        val token = generation.incrementAndGet()
        generationJob?.cancel()
        generationJob = scope.launch {
            val artwork = withContext(Dispatchers.IO) { extractArtwork(metadata, packageName) }
            if (token != generation.get()) return@launch
            if (artwork == null) {
                broadcastWallpaperApplied("No music artwork found")
                return@launch
            }
            val display = resources.displayMetrics
            val maxW = min(720, display.widthPixels.coerceAtLeast(480))
            val maxH = min(1440, display.heightPixels.coerceAtLeast(900))
            val bridge = PythonBridge.getInstance(this@MediaNotificationListenerService)
            val lyrics = buildLyrics(metadata)
            val output = withContext(Dispatchers.Default) {
                bridge.generateWallpaper(artwork, maxW, maxH, prefs.blurRadius, prefs.darkness,
                    prefs.artScale, 28, true, prefs.effect, prefs.blurType, prefs.coverHeight,
                    prefs.coverOffset, prefs.transitionHeight, prefs.showLyrics, lyrics,
                    prefs.photoSource, prefs.customPhotoUri)
            }
            if (token != generation.get() || output == null) return@launch
            WallpaperHelperCompat.saveCurrent(this@MediaNotificationListenerService, output)
            refreshLive()
            broadcastWallpaperApplied("Music wallpaper updated")
        }
    }

    private fun buildLyrics(metadata: MediaMetadata): String? {
        return metadata.getString(MediaMetadata.METADATA_KEY_LYRICS)?.takeIf { it.isNotBlank() }
    }

    private fun extractArtwork(metadata: MediaMetadata, pkg: String): Bitmap? {
        return try {
            when (prefs.photoSource) {
                PreferencesManager.PHOTO_CUSTOM -> loadArtworkUri(prefs.customPhotoUri)
                PreferencesManager.PHOTO_ALBUM -> metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { downsampleArtwork(it) }
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)?.let { loadArtworkUri(it) }
                PreferencesManager.PHOTO_ART -> metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { downsampleArtwork(it) }
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)?.let { loadArtworkUri(it) }
                PreferencesManager.PHOTO_DISPLAY_ICON -> if (Build.VERSION.SDK_INT >= 21) {
                    metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let { downsampleArtwork(it) }
                        ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)?.let { loadArtworkUri(it) }
                } else null
                PreferencesManager.PHOTO_NOTIFICATION -> notificationArtwork[pkg]?.let { downsampleArtwork(it) }
                else -> {
                    metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { downsampleArtwork(it) }
                        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { downsampleArtwork(it) }
                        ?: if (Build.VERSION.SDK_INT >= 21) metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let { downsampleArtwork(it) } else null
                        ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)?.let { loadArtworkUri(it) }
                        ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)?.let { loadArtworkUri(it) }
                        ?: if (Build.VERSION.SDK_INT >= 21) metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)?.let { loadArtworkUri(it) } else null
                        ?: if (prefs.photoFallback) notificationArtwork[pkg]?.let { downsampleArtwork(it) } else null
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "art extraction failed", t)
            null
        }
    }

    private suspend fun loadArtworkUri(text: String): Bitmap? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        try {
            val uri = Uri.parse(text)
            val bitmap = when (uri.scheme?.lowercase()) {
                "http", "https" -> {
                    var connection: HttpURLConnection? = null
                    try {
                        connection = URL(text).openConnection() as HttpURLConnection
                        connection.connectTimeout = 2500
                        connection.readTimeout = 4000
                        connection.setRequestProperty("User-Agent", "MusWall/1.9")
                        connection.connect()
                        if (connection.responseCode !in 200..299) return@withContext null
                        connection.inputStream.use { BitmapFactory.decodeStream(it) }
                    } finally {
                        connection?.disconnect()
                    }
                }
                else -> contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
            bitmap?.let { downsampleArtwork(it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun cacheNotificationArtwork(sbn: StatusBarNotification) {
        try {
            val icon = if (Build.VERSION.SDK_INT >= 23) sbn.notification.getLargeIcon() else null
            if (icon == null) return
            val drawable = icon.loadDrawable(this) ?: return
            val w = drawable.intrinsicWidth.coerceAtLeast(1).coerceAtMost(900)
            val h = drawable.intrinsicHeight.coerceAtLeast(1).coerceAtMost(900)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).also { canvas ->
                drawable.setBounds(0, 0, w, h)
                drawable.draw(canvas)
            }
            notificationArtwork.put(sbn.packageName, bitmap)?.let { if (!it.isRecycled) it.recycle() }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "notification art failed", t)
        }
    }

    private fun downsampleArtwork(bitmap: Bitmap): Bitmap? {
        return try {
            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            if (copy.width <= 900 && copy.height <= 900) return copy
            val scale = minOf(900f / copy.width, 900f / copy.height)
            Bitmap.createScaledBitmap(copy,
                (copy.width * scale).toInt().coerceAtLeast(1),
                (copy.height * scale).toInt().coerceAtLeast(1), true).also {
                if (it !== copy && !copy.isRecycled) copy.recycle()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun refreshLive() {
        sendBroadcast(Intent(MusicWallpaperService.ACTION_REFRESH).setPackage(packageName))
    }

    private fun broadcastTrack(title: String, artist: String) {
        sendBroadcast(Intent(ACTION_TRACK_CHANGED).setPackage(packageName)
            .putExtra(EXTRA_TRACK_TITLE, title).putExtra(EXTRA_ARTIST, artist))
    }

    private fun broadcastPlaybackState(playing: Boolean) {
        sendBroadcast(Intent(ACTION_PLAYBACK_STATE_CHANGED).setPackage(packageName)
            .putExtra(EXTRA_IS_PLAYING, playing))
    }

    private fun broadcastWallpaperApplied(message: String) {
        sendBroadcast(Intent(ACTION_WALLPAPER_APPLIED).setPackage(packageName)
            .putExtra(EXTRA_STATUS_MESSAGE, message))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { cacheNotificationArtwork(it) }
        refreshActiveSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshActiveSessions()
    }

    override fun onDestroy() {
        generation.incrementAndGet()
        generationJob?.cancel()
        runCatching { sessionManager.removeOnActiveSessionsChangedListener(sessionsListener) }
        runCatching { activeController?.unregisterCallback(callback) }
        notificationArtwork.values.forEach { if (!it.isRecycled) it.recycle() }
        notificationArtwork.clear()
        scope.cancel()
        super.onDestroy()
    }

    private object WallpaperHelperCompat {
        fun saveCurrent(context: android.content.Context, bitmap: Bitmap) {
            com.muswall.app.wallpaper.WallpaperHelper(context).saveCurrentForLiveWallpaper(bitmap)
        }
    }
}
