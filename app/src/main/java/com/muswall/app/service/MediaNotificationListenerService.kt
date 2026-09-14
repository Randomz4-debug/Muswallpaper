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
        try { activeController?.unregisterCallback(callback) } catch (_: Throwable) {}
        activeController = controller
        try { controller?.registerCallback(callback) } catch (t: Throwable) {
            android.util.Log.w("MusWallMedia", "Controller callback failed", t)
        }
        try { handlePlayback(controller?.playbackState) } catch (t: Throwable) {
            android.util.Log.w("MusWallMedia", "Playback state failed", t)
        }
        try { handleMetadata(controller?.metadata, force = true) } catch (t: Throwable) {
            android.util.Log.w("MusWallMedia", "Metadata failed", t)
        }
    }

    private fun handlePlayback(state: PlaybackState?) {
        val now = state?.state == PlaybackState.STATE_PLAYING
        val was = playing
        playing = now
        broadcastPlaybackState(now)

        if (!now) {
            generationJob?.cancel()
            if (was && prefs.restoreOnPause && applied) {
                applied = false
                scope.launch(Dispatchers.IO) {
                    if (prefs.liveWallpaperEnabled) {
                        // Keep the live wallpaper installed. Its engine now draws the
                        // separately configured home/lock originals using getWallpaperFlags().
                        prefs.liveMusicPlaying = false
                        sendBroadcast(Intent(MusicWallpaperService.ACTION_REFRESH).setPackage(packageName))
                        broadcastWallpaperApplied("Original wallpaper restored")
                    } else {
                        val restored = wallpaperHelper.restoreOriginal()
                        broadcastWallpaperApplied(
                            if (restored.success) "Original home and lock wallpapers restored"
                            else "Restore incomplete${restored.message?.let { ": $it" } ?: ""}"
                        )
                    }
                }
            } else if (prefs.liveWallpaperEnabled) {
                prefs.liveMusicPlaying = false
                sendBroadcast(Intent(MusicWallpaperService.ACTION_REFRESH).setPackage(packageName))
            }
        } else if (!was) {
            // A resume does not necessarily emit a metadata callback. Force the
            // current track through the renderer so the live wallpaper starts immediately.
            activeController?.metadata?.let { handleMetadata(it, force = true) }
        }
    }

    private fun handleMetadata(metadata: MediaMetadata?, force: Boolean = false) {
        if (metadata == null) return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty().ifEmpty { "Unknown title" }
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty().ifEmpty { "Unknown artist" }
        broadcastTrack(title, artist)
        prefs.lastTrackTitle = title
        prefs.lastArtist = artist

        val id = "$title\u0000$artist"
        if (!force && id == currentTrackId) return
        currentTrackId = id
        if (!prefs.isAutoEnabled || !playing || prefs.wallpaperMode != PreferencesManager.MODE_MUSIC) return

        generationJob?.cancel()
        generationJob = scope.launch(Dispatchers.Default) {
            // No artificial transition delay: the expensive work is the render itself.
            val art = extractArtwork(metadata) ?: return@launch
            try {
                renderAndApply(art)
            } finally {
                if (!art.isRecycled) art.recycle()
            }
        }
    }

    private suspend fun renderAndApply(artwork: Bitmap) {
        val dm = resources.displayMetrics
        // Smaller live renders are intentionally used for low latency. The live
        // wallpaper scales the result to the display; static Apply keeps the UI quality.
        val targetW = (dm.widthPixels * 0.58f).toInt().coerceIn(480, 720)
        val targetH = (dm.heightPixels * 0.58f).toInt().coerceIn(900, 1440)
        val result = PythonBridge.generateWallpaper(
            artwork, targetW, targetH,
            prefs.blurRadius.toFloat(), prefs.darkness / 100f, prefs.artScale / 100f,
            42, true, prefs.effect, prefs.blurType,
            prefs.coverHeight, prefs.coverOffset, prefs.transitionHeight
        ) ?: run {
            broadcastWallpaperApplied("Could not render artwork")
            return
        }

        try {
            wallpaperHelper.saveCurrentForLiveWallpaper(result)
            if (prefs.liveWallpaperEnabled) {
                prefs.liveMusicPlaying = true
                sendBroadcast(Intent(MusicWallpaperService.ACTION_REFRESH).setPackage(packageName))
                applied = true
                broadcastWallpaperApplied("Live wallpaper updated")
            } else {
                val apply = wallpaperHelper.applyStatic(result, prefs.targetScreen)
                applied = apply.success
                broadcastWallpaperApplied(if (apply.success) "Wallpaper updated" else "Failed: ${apply.message ?: "unknown error"}")
            }
        } finally {
            if (!result.isRecycled) result.recycle()
        }
    }

    private fun extractArtwork(metadata: MediaMetadata): Bitmap? {
        return try {
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { downsampleArtwork(it) }?.let { return it }
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { downsampleArtwork(it) }?.let { return it }
            val uriText = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            if (!uriText.isNullOrBlank()) {
                contentResolver.openInputStream(Uri.parse(uriText)).use { input ->
                    if (input != null) {
                        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                        BitmapFactory.decodeStream(input, null, opts)?.let { bmp -> downsampleArtwork(bmp) }
                    } else null
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
        } catch (_: Throwable) {
            try { bitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { null }
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
        try { activeController?.unregisterCallback(callback) } catch (_: Throwable) {}
        activeController = null
        scope.cancel()
        super.onDestroy()
    }
}
