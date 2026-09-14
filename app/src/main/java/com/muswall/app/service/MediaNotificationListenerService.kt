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
    private lateinit var prefs: PreferencesManager
    private lateinit var wallpaperHelper: WallpaperHelper
    private var sessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var currentTrackId = ""
    private var playing = false
    private var applied = false
    private var generationJob: Job? = null

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = handlePlayback(state)
        override fun onMetadataChanged(metadata: MediaMetadata?) = handleMetadata(metadata)
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        val best = controllers?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers?.firstOrNull()
        switchController(best)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager.getInstance(this)
        // MusWallApp initializes the application context. Do not start Python here.
        wallpaperHelper = WallpaperHelper(this)
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
            val controllers = sessionManager?.getActiveSessions(component)
            switchController(controllers?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: controllers?.firstOrNull())
        } catch (_: SecurityException) {
            broadcastWallpaperApplied("Media access is not connected. Re-enable Notification access.")
        } catch (_: Exception) {
        }
    }

    private fun switchController(controller: MediaController?) {
        if (controller?.sessionToken == activeController?.sessionToken) {
            controller?.metadata?.let { handleMetadata(it) }
            controller?.playbackState?.let { handlePlayback(it) }
            return
        }
        activeController?.unregisterCallback(callback)
        activeController = controller
        controller?.registerCallback(callback)
        handlePlayback(controller?.playbackState)
        handleMetadata(controller?.metadata)
    }

    private fun handlePlayback(state: PlaybackState?) {
        val now = state?.state == PlaybackState.STATE_PLAYING
        val was = playing
        playing = now
        broadcastPlaybackState(now)
        if (was && !now && prefs.restoreOnPause && applied) {
            scope.launch {
                wallpaperHelper.copyOriginalToLiveCache()
                if (!prefs.liveWallpaperEnabled) wallpaperHelper.restoreOriginal()
                else sendBroadcast(Intent(MusicWallpaperService.ACTION_REFRESH).setPackage(packageName))
                applied = false
                broadcastWallpaperApplied("Original wallpaper restored")
            }
        }
    }

    private fun handleMetadata(metadata: MediaMetadata?) {
        if (metadata == null) return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty().ifEmpty { "Unknown title" }
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty().ifEmpty { "Unknown artist" }
        broadcastTrack(title, artist)
        prefs.lastTrackTitle = title
        prefs.lastArtist = artist

        val id = "$title\u0000$artist"
        if (id == currentTrackId) return
        currentTrackId = id
        if (!prefs.isAutoEnabled || !playing || prefs.wallpaperMode != PreferencesManager.MODE_MUSIC) return

        // Album-art extraction can be expensive on some players. Never do it
        // directly inside the MediaController callback on the main thread.
        generationJob?.cancel()
        generationJob = scope.launch(Dispatchers.Default) {
            val art = extractArtwork(metadata) ?: return@launch
            queueWallpaper(art)
        }
    }

    private fun queueWallpaper(artwork: Bitmap) {
        generationJob = scope.launch(Dispatchers.Default) {
            // Small debounce prevents several media-session callbacks from doing expensive Python work.
            delay(180)
            val dm = resources.displayMetrics
            val targetW = (dm.widthPixels * 0.75f).toInt().coerceIn(480, 900)
            val targetH = (dm.heightPixels * 0.75f).toInt().coerceIn(960, 1800)
            val result = PythonBridge.generateWallpaper(
                artwork,
                targetW,
                targetH,
                prefs.blurRadius.toFloat(),
                prefs.darkness / 100f,
                prefs.artScale / 100f,
                42,
                true,
                prefs.effect,
                prefs.blurType,
                prefs.coverHeight,
                prefs.coverOffset,
                prefs.transitionHeight
            )
            if (result == null) {
                broadcastWallpaperApplied("Could not render artwork")
                return@launch
            }
            wallpaperHelper.saveCurrentForLiveWallpaper(result)
            if (prefs.liveWallpaperEnabled) {
                sendBroadcast(Intent(MusicWallpaperService.ACTION_REFRESH).setPackage(packageName))
                applied = true
                broadcastWallpaperApplied("Live wallpaper updated")
            } else {
                val apply = wallpaperHelper.applyStatic(result, prefs.targetScreen)
                applied = apply.success
                broadcastWallpaperApplied(if (apply.success) "Wallpaper updated" else "Failed: ${apply.message ?: "unknown error"}")
            }
        }
    }

    private fun extractArtwork(metadata: MediaMetadata): Bitmap? {
        // Media callbacks may contain very large album-art bitmaps. Copying a
        // bounded bitmap here prevents memory spikes on low-RAM devices.
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { return downsampleArtwork(it) }
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { return downsampleArtwork(it) }
        val uriText = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
        if (!uriText.isNullOrBlank()) {
            return try {
                contentResolver.openInputStream(Uri.parse(uriText)).use {
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeStream(it, null, opts)?.let { bmp -> downsampleArtwork(bmp) }
                }
            } catch (_: Exception) { null }
        }
        return null
    }

    private fun downsampleArtwork(bitmap: Bitmap): Bitmap {
        val max = 900
        if (bitmap.width <= max && bitmap.height <= max) return bitmap
        val scale = minOf(max.toFloat() / bitmap.width, max.toFloat() / bitmap.height)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return try {
            val smaller = Bitmap.createScaledBitmap(bitmap, w, h, true)
            if (smaller !== bitmap) bitmap.recycle()
            smaller
        } catch (_: Throwable) {
            bitmap
        }
    }

    private fun broadcastTrack(title: String, artist: String) {
        sendBroadcast(Intent(ACTION_TRACK_CHANGED).setPackage(packageName)
            .putExtra(EXTRA_TRACK_TITLE, title).putExtra(EXTRA_ARTIST, artist))
    }

    private fun broadcastPlaybackState(isPlaying: Boolean) {
        sendBroadcast(Intent(ACTION_PLAYBACK_STATE_CHANGED).setPackage(packageName)
            .putExtra(EXTRA_IS_PLAYING, isPlaying))
    }

    private fun broadcastWallpaperApplied(message: String) {
        sendBroadcast(Intent(ACTION_WALLPAPER_APPLIED).setPackage(packageName)
            .putExtra(EXTRA_STATUS_MESSAGE, message))
    }

    override fun onDestroy() {
        generationJob?.cancel()
        try { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        activeController?.unregisterCallback(callback)
        activeController = null
        scope.cancel()
        super.onDestroy()
    }
}
