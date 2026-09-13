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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: PreferencesManager
    private lateinit var wallpaperHelper: WallpaperHelper
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var currentTrackId: String = ""
    private var isPlaying = false
    private var hasAppliedMusicWallpaper = false

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            super.onPlaybackStateChanged(state)
            handlePlaybackState(state)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            super.onMetadataChanged(metadata)
            handleMetadata(metadata)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager.getInstance(this)
        wallpaperHelper = WallpaperHelper(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        setupMediaSessionManager()
    }

    private fun handlePlaybackState(state: PlaybackState?) {
        val playbackState = state?.state ?: PlaybackState.STATE_NONE
        val nowPlaying = (playbackState == PlaybackState.STATE_PLAYING)
        val wasPlaying = isPlaying
        isPlaying = nowPlaying
        broadcastPlaybackState(isPlaying)

        // REQUIREMENT: Restore separate original wallpapers when music stops or pauses
        if (wasPlaying && !nowPlaying && prefs.restoreOnPause && hasAppliedMusicWallpaper) {
            serviceScope.launch {
                wallpaperHelper.restoreOriginalWallpapers()
                hasAppliedMusicWallpaper = false
                broadcastWallpaperApplied("Restored original wallpaper(s)")
            }
        }
    }

    private fun handleMetadata(metadata: MediaMetadata?) {
        if (metadata == null) return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown Title"
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown Artist"
        val trackIdentifier = "$title - $artist"
        broadcastTrackInfo(title, artist)

        if (trackIdentifier == currentTrackId && hasAppliedMusicWallpaper) return
        currentTrackId = trackIdentifier

        if (!prefs.isAutoEnabled || !isPlaying) return

        val artworkBitmap = extractArtwork(metadata) ?: return
        processAndApplyWallpaper(artworkBitmap, title, artist)
    }

    fun processAndApplyWallpaper(artwork: Bitmap, title: String, artist: String) {
        serviceScope.launch {
            val displayMetrics = resources.displayMetrics
            val targetW = if (displayMetrics.widthPixels > 0) displayMetrics.widthPixels else 1080
            val targetH = if (displayMetrics.heightPixels > 0) displayMetrics.heightPixels else 2400

            val wallpaperBitmap = PythonBridge.generateWallpaper(
                srcBitmap = artwork,
                targetWidth = targetW,
                targetHeight = targetH,
                blurRadius = prefs.blurRadius.toFloat(),
                darkness = prefs.darkness / 100f,
                artScale = prefs.artScale / 100f,
                cornerRadius = 40,
                addShadow = true
            )

            if (wallpaperBitmap != null) {
                val result = wallpaperHelper.applyWallpaper(wallpaperBitmap, prefs.targetScreen)
                if (result.success) {
                    hasAppliedMusicWallpaper = true
                    broadcastWallpaperApplied("Wallpaper applied to ${prefs.targetScreen}")
                } else {
                    broadcastWallpaperApplied("Failed: ${result.errorMessage}")
                }
            }
        }
    }

    private fun extractArtwork(metadata: MediaMetadata): Bitmap? {
        var bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (bitmap != null) return bitmap

        val uriString = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
        if (!uriString.isNullOrEmpty()) {
            try {
                val stream = contentResolver.openInputStream(Uri.parse(uriString))
                bitmap = BitmapFactory.decodeStream(stream)
                stream?.close()
                return bitmap
            } catch (ignored: Exception) {}
        }
        return null
    }

    private fun setupMediaSessionManager() {
        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val componentName = ComponentName(this, MediaNotificationListenerService::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(activeSessionsListener, componentName)
            val controllers = mediaSessionManager?.getActiveSessions(componentName)
            val playingController = controllers?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: controllers?.firstOrNull()
            updateActiveController(playingController)
        } catch (ignored: Exception) {}
    }

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        val playingController = controllers?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers?.firstOrNull()
        updateActiveController(playingController)
    }

    private fun updateActiveController(newController: MediaController?) {
        if (newController == null || newController.sessionToken == activeController?.sessionToken) return
        activeController?.unregisterCallback(mediaControllerCallback)
        activeController = newController
        activeController?.registerCallback(mediaControllerCallback)
        handlePlaybackState(newController.playbackState)
        handleMetadata(newController.metadata)
    }

    private fun broadcastTrackInfo(title: String, artist: String) {
        sendBroadcast(Intent(ACTION_TRACK_CHANGED).putExtra(EXTRA_TRACK_TITLE, title).putExtra(EXTRA_ARTIST, artist).setPackage(packageName))
    }
    private fun broadcastPlaybackState(playing: Boolean) {
        sendBroadcast(Intent(ACTION_PLAYBACK_STATE_CHANGED).putExtra(EXTRA_IS_PLAYING, playing).setPackage(packageName))
    }
    private fun broadcastWallpaperApplied(msg: String) {
        sendBroadcast(Intent(ACTION_WALLPAPER_APPLIED).putExtra(EXTRA_STATUS_MESSAGE, msg).setPackage(packageName))
    }

    override fun onDestroy() {
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
        } catch (ignored: Exception) {}
        activeController?.unregisterCallback(mediaControllerCallback)
        activeController = null
        serviceScope.cancel()
        super.onDestroy()
    }
}