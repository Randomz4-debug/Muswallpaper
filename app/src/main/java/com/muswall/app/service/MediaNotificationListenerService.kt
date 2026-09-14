package com.muswall.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.service.notification.NotificationListenerService
import com.muswall.app.data.PreferencesManager
import com.muswall.app.python.PythonBridge
import com.muswall.app.wallpaper.MusicWallpaperService
import com.muswall.app.wallpaper.WallpaperHelper
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

/** Callback-driven music detection. No polling is used. */
class MediaNotificationListenerService : NotificationListenerService() {
    companion object {
        const val ACTION_TRACK_CHANGED = "com.muswall.app.ACTION_TRACK_CHANGED"
        const val ACTION_PLAYBACK_STATE_CHANGED = "com.muswall.app.ACTION_PLAYBACK_STATE_CHANGED"
        const val ACTION_WALLPAPER_APPLIED = "com.muswall.app.ACTION_WALLPAPER_APPLIED"
        const val EXTRA_TRACK_TITLE = "extra_track_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        const val EXTRA_STATUS_MESSAGE = "extra_status_message"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val generation = AtomicLong(0L)
    private lateinit var prefs: PreferencesManager
    private lateinit var wallpaperHelper: WallpaperHelper
    private var sessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var currentTrackId = ""
    private var playing = false
    private var generationJob: Job? = null

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = handlePlayback(state)
        override fun onMetadataChanged(metadata: MediaMetadata?) = handleMetadata(metadata, force = true)
        override fun onSessionDestroyed() {
            handlePlayback(null)
            refreshActiveSessions()
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        selectBestController(controllers)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager.getInstance(this)
        wallpaperHelper = WallpaperHelper(this)
        PythonBridge.initialize(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectSessions()
    }

    private fun connectSessions() {
        try {
            sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MediaNotificationListenerService::class.java)
            sessionManager?.addOnActiveSessionsChangedListener(sessionsListener, component)
            refreshActiveSessions()
        } catch (t: Throwable) {
            android.util.Log.w("MusWallMedia", "Media session connection failed", t)
            broadcastWallpaperApplied("Music detection is not connected. Enable Notification access.")
        }
    }

    private fun refreshActiveSessions() {
        try {
            val component = ComponentName(this, MediaNotificationListenerService::class.java)
            selectBestController(sessionManager?.getActiveSessions(component))
        } catch (t: Throwable) {
            android.util.Log.w("MusWallMedia", "Active session refresh failed", t)
        }
    }

    private fun selectBestController(controllers: List<MediaController>?) {
        val list = controllers.orEmpty()
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.metadata != null }
            ?: list.firstOrNull()
        switchController(best)
    }

    private fun switchController(controller: MediaController?) {
        if (controller?.sessionToken == activeController?.sessionToken) {
            controller?.playbackState?.let { handlePlayback(it) }
            controller?.metadata?.let { handleMetadata(it, force = false) }
            if (controller == null) handlePlayback(null)
            return
        }

        try { activeController?.unregisterCallback(callback) } catch (_: Throwable) {}
        activeController = controller
        currentTrackId = ""

        if (controller == null) {
            handlePlayback(null)
            broadcastTrack("No music detected", "Waiting for a music player")
            return
        }

        try { controller.registerCallback(callback) } catch (t: Throwable) {
            android.util.Log.w("MusWallMedia", "Controller callback registration failed", t)
        }
        handlePlayback(controller.playbackState)
        controller.metadata?.let { handleMetadata(it, force = true) }
    }

    private fun handlePlayback(state: PlaybackState?) {
        val now = state?.state == PlaybackState.STATE_PLAYING
        val was = playing
        playing = now
        broadcastPlaybackState(now)

        if (!now) {
            generation.incrementAndGet()
            generationJob?.cancel()
            currentTrackId = ""
            prefs.liveMusicPlaying = false
            if (prefs.liveWallpaperEnabled && prefs.restoreOnPause) {
                sendBroadcast(Intent(MusicWallpaperService.ACTION_REFRESH).setPackage(packageName))
                broadcastWallpaperApplied("Music stopped • original wallpaper restored")
            } else if (prefs.liveWallpaperEnabled) {
                sendBroadcast(Intent(MusicWallpaperService.ACTION_REFRESH).setPackage(packageName))
            }
            return
        }

        if (!was) activeController?.metadata?.let { handleMetadata(it, force = true) }
    }

    private fun handleMetadata(metadata: MediaMetadata?, force: Boolean = false) {
        if (metadata == null) return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty().ifEmpty { "Unknown title" }
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty().ifEmpty { "Unknown artist" }
        broadcastTrack(title, artist)
        prefs.lastTrackTitle = title
        prefs.lastArtist = artist

        val artUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI).orEmpty()
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val id = "$mediaId\u0000$title\u0000$artist\u0000$artUri"
        if (!force && id == currentTrackId) return
        currentTrackId = id

        if (!prefs.isAutoEnabled || !playing || prefs.wallpaperMode != PreferencesManager.MODE_MUSIC) return
        if (!prefs.liveWallpaperEnabled) {
            broadcastWallpaperApplied("Music detected • enable MusWall Live Wallpaper once")
            return
        }

        val token = generation.incrementAndGet()
        generationJob?.cancel()
        generationJob = scope.launch(Dispatchers.Default) {
            val art = extractArtwork(metadata) ?: run {
                broadcastWallpaperApplied("Track detected, but no album artwork was available")
                return@launch
            }
            try { renderAndSendToLiveWallpaper(art, token) }
            finally { if (!art.isRecycled) art.recycle() }
        }
    }

    private suspend fun renderAndSendToLiveWallpaper(artwork: Bitmap, token: Long) {
        val dm = resources.displayMetrics
        val targetW = (dm.widthPixels * 0.58f).toInt().coerceIn(480, 720)
        val targetH = (dm.heightPixels * 0.58f).toInt().coerceIn(900, 1440)
        if (token != generation.get() || !playing || !prefs.liveWallpaperEnabled) return
        wallpaperHelper.saveLastArtwork(artwork)
        val result = PythonBridge.generateWallpaper(
            artwork, targetW, targetH,
            prefs.blurRadius.toFloat(), prefs.darkness / 100f, prefs.artScale / 100f,
            42, true, prefs.effect, prefs.blurType,
            prefs.coverHeight, prefs.coverOffset, prefs.transitionHeight
        ) ?: return
        try {
            if (token != generation.get() || !playing || !prefs.liveWallpaperEnabled) return
            wallpaperHelper.saveCurrentForLiveWallpaper(result)
            if (token != generation.get() || !playing) return
            prefs.liveMusicPlaying = true
            sendBroadcast(Intent(MusicWallpaperService.ACTION_REFRESH).setPackage(packageName))
            broadcastWallpaperApplied("Live wallpaper updated instantly")
        } finally { if (!result.isRecycled) result.recycle() }
    }

    private fun extractArtwork(metadata: MediaMetadata): Bitmap? {
        return try {
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { downsampleArtwork(it) }?.let { return it }
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { downsampleArtwork(it) }?.let { return it }
            val uriText = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            if (!uriText.isNullOrBlank()) {
                contentResolver.openInputStream(Uri.parse(uriText)).use { input ->
                    if (input != null) BitmapFactory.decodeStream(input)?.let { downsampleArtwork(it) } else null
                }
            } else null
        } catch (t: Throwable) {
            android.util.Log.w("MusWallMedia", "Artwork extraction failed", t)
            null
        }
    }

    private fun downsampleArtwork(bitmap: Bitmap): Bitmap? {
        val max = 900
        return try {
            val source = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            if (source.width <= max && source.height <= max) return source
            val scale = minOf(max.toFloat() / source.width, max.toFloat() / source.height)
            val w = (source.width * scale).toInt().coerceAtLeast(1)
            val h = (source.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(source, w, h, true).also {
                if (it !== source && !source.isRecycled) source.recycle()
            }
        } catch (_: Throwable) { null }
    }

    private fun broadcastTrack(title: String, artist: String) =
        sendBroadcast(Intent(ACTION_TRACK_CHANGED).setPackage(packageName).putExtra(EXTRA_TRACK_TITLE, title).putExtra(EXTRA_ARTIST, artist))

    private fun broadcastPlaybackState(isPlaying: Boolean) =
        sendBroadcast(Intent(ACTION_PLAYBACK_STATE_CHANGED).setPackage(packageName).putExtra(EXTRA_IS_PLAYING, isPlaying))

    private fun broadcastWallpaperApplied(message: String) =
        sendBroadcast(Intent(ACTION_WALLPAPER_APPLIED).setPackage(packageName).putExtra(EXTRA_STATUS_MESSAGE, message))

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) = refreshActiveSessions()
    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) = refreshActiveSessions()

    override fun onDestroy() {
        generation.incrementAndGet()
        generationJob?.cancel()
        try { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Throwable) {}
        try { activeController?.unregisterCallback(callback) } catch (_: Throwable) {}
        activeController = null
        scope.cancel()
        super.onDestroy()
    }
}
