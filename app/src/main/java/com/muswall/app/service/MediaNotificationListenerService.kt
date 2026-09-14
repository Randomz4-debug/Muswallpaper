package com.muswall.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.muswall.app.data.PreferencesManager
import com.muswall.app.python.PythonBridge
import com.muswall.app.wallpaper.MusicWallpaperService
import com.muswall.app.wallpaper.WallpaperHelper
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Detects music through Android MediaSession callbacks and extracts album art.
 *
 * Album art is attempted in this order:
 * 1. MediaSession ALBUM_ART bitmap
 * 2. MediaSession ART bitmap
 * 3. MediaSession DISPLAY_ICON bitmap
 * 4. ALBUM_ART_URI / ART_URI / DISPLAY_ICON_URI using ContentResolver or HTTPS
 * 5. Media notification large icon (important for players that expose only the
 *    notification artwork, including some Xiaomi/Spotify-style players)
 *
 * There is no polling loop.
 */
class MediaNotificationListenerService : NotificationListenerService() {
    companion object {
        const val ACTION_TRACK_CHANGED = "com.muswall.app.ACTION_TRACK_CHANGED"
        const val ACTION_PLAYBACK_STATE_CHANGED = "com.muswall.app.ACTION_PLAYBACK_STATE_CHANGED"
        const val ACTION_WALLPAPER_APPLIED = "com.muswall.app.ACTION_WALLPAPER_APPLIED"
        const val EXTRA_TRACK_TITLE = "extra_track_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        const val EXTRA_STATUS_MESSAGE = "extra_status_message"
        private const val TAG = "MusWallMedia"
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

    // Notification artwork is a fallback for media apps whose MediaSession does
    // not expose a usable album-art URI/bitmap.
    private val notificationArtwork = ConcurrentHashMap<String, Bitmap>()

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
        // NotificationListenerService may connect after the music notification
        // already exists. Cache those notifications immediately instead of
        // waiting for another notification event.
        try { activeNotifications?.forEach { cacheNotificationArtwork(it) } } catch (t: Throwable) {
            android.util.Log.w(TAG, "Initial notification artwork scan failed", t)
        }
        connectSessions()
    }

    private fun connectSessions() {
        try {
            sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MediaNotificationListenerService::class.java)
            sessionManager?.addOnActiveSessionsChangedListener(sessionsListener, component)
            refreshActiveSessions()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Media session connection failed", t)
            broadcastWallpaperApplied("Music detection is not connected. Enable Notification access.")
        }
    }

    private fun refreshActiveSessions() {
        try {
            val component = ComponentName(this, MediaNotificationListenerService::class.java)
            selectBestController(sessionManager?.getActiveSessions(component))
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Active session refresh failed", t)
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

        try {
            controller.registerCallback(callback)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Controller callback registration failed", t)
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
            if (prefs.liveWallpaperEnabled) {
                sendBroadcast(Intent(MusicWallpaperService.ACTION_REFRESH).setPackage(packageName))
            }
            if (was && prefs.restoreOnPause) {
                broadcastWallpaperApplied("Music stopped • original wallpaper restored")
            }
            return
        }

        if (!was) activeController?.metadata?.let { handleMetadata(it, force = true) }
    }

    private fun handleMetadata(metadata: MediaMetadata?, force: Boolean = false) {
        if (metadata == null) return

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.trim().orEmpty().ifEmpty { "Unknown title" }
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.trim().orEmpty().ifEmpty { "Unknown artist" }
        broadcastTrack(title, artist)
        prefs.lastTrackTitle = title
        prefs.lastArtist = artist

        val artUri = firstNonBlank(
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
            metadata.getString(MediaMetadata.METADATA_KEY_ART_URI),
            if (Build.VERSION.SDK_INT >= 21) metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI) else null
        )
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val packageNameForTrack = activeController?.packageName.orEmpty()
        val id = "$packageNameForTrack\u0000$mediaId\u0000$title\u0000$artist\u0000$artUri"

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
            val art = extractArtwork(metadata, packageNameForTrack)
            if (art == null) {
                broadcastWallpaperApplied("Track detected, but no album artwork was available")
                return@launch
            }
            try {
                renderAndSendToLiveWallpaper(art, token)
            } finally {
                if (!art.isRecycled) art.recycle()
            }
        }
    }

    private suspend fun renderAndSendToLiveWallpaper(artwork: Bitmap, token: Long) {
        if (token != generation.get() || !playing || !prefs.liveWallpaperEnabled) return

        val dm = resources.displayMetrics
        val targetW = (dm.widthPixels * 0.58f).toInt().coerceIn(480, 720)
        val targetH = (dm.heightPixels * 0.58f).toInt().coerceIn(900, 1440)

        wallpaperHelper.saveLastArtwork(artwork)
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
        ) ?: return

        try {
            if (token != generation.get() || !playing || !prefs.liveWallpaperEnabled) return
            wallpaperHelper.saveCurrentForLiveWallpaper(result)
            if (token != generation.get() || !playing) return
            prefs.liveMusicPlaying = true
            sendBroadcast(Intent(MusicWallpaperService.ACTION_REFRESH).setPackage(packageName))
            broadcastWallpaperApplied("Live wallpaper updated instantly")
        } finally {
            if (!result.isRecycled) result.recycle()
        }
    }

    /** Extract album art using every common Android media-art mechanism. */
    private suspend fun extractArtwork(metadata: MediaMetadata, packageName: String): Bitmap? {
        // 1-3: Embedded MediaMetadata bitmaps.
        val bitmapKeys = arrayOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON
        )
        for (key in bitmapKeys) {
            try {
                metadata.getBitmap(key)?.let { downsampleArtwork(it)?.let { result -> return result } }
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "Metadata bitmap failed for key=$key", t)
            }
        }

        // 4: URI artwork. Handles content://, file:// and network https:// URLs.
        val uriStrings = listOfNotNull(
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
            metadata.getString(MediaMetadata.METADATA_KEY_ART_URI),
            if (Build.VERSION.SDK_INT >= 21) metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI) else null
        ).filter { it.isNotBlank() }.distinct()

        for (uriText in uriStrings) {
            try {
                val result = loadArtworkUri(uriText)
                if (result != null) return result
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "Artwork URI failed: $uriText", t)
            }
        }

        // 5: Media notification large icon fallback.
        notificationArtwork[packageName]?.let { cached ->
            try { downsampleArtwork(cached)?.let { return it } } catch (_: Throwable) {}
        }

        return null
    }

    private suspend fun loadArtworkUri(uriText: String): Bitmap? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriText)
        when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                var connection: HttpURLConnection? = null
                try {
                    connection = URL(uriText).openConnection() as HttpURLConnection
                    connection.connectTimeout = 2500
                    connection.readTimeout = 4000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", "MusWall/1.9")
                    connection.connect()
                    if (connection.responseCode !in 200..299) return@withContext null
                    connection.inputStream.use { input -> BitmapFactory.decodeStream(input) }
                } finally {
                    connection?.disconnect()
                }
            }
            else -> contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        }?.let { decoded -> downsampleArtwork(decoded) }
    }

    /** Capture album art from media notification largeIcon as a last-resort fallback. */
    private fun cacheNotificationArtwork(sbn: StatusBarNotification) {
        try {
            val icon = if (Build.VERSION.SDK_INT >= 23) sbn.notification.getLargeIcon() else null
            if (icon == null) return
            val drawable = icon.loadDrawable(this) ?: return
            val width = drawable.intrinsicWidth.coerceAtLeast(1).coerceAtMost(900)
            val height = drawable.intrinsicHeight.coerceAtLeast(1).coerceAtMost(900)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)

            val old = notificationArtwork.put(sbn.packageName, bitmap)
            if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Notification album-art extraction failed", t)
        }
    }

    private fun removeNotificationArtwork(sbn: StatusBarNotification) {
        val old = notificationArtwork.remove(sbn.packageName)
        if (old != null && !old.isRecycled) old.recycle()
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
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Artwork downsample failed", t)
            null
        }
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun broadcastTrack(title: String, artist: String) =
        sendBroadcast(
            Intent(ACTION_TRACK_CHANGED).setPackage(packageName)
                .putExtra(EXTRA_TRACK_TITLE, title)
                .putExtra(EXTRA_ARTIST, artist)
        )

    private fun broadcastPlaybackState(isPlaying: Boolean) =
        sendBroadcast(
            Intent(ACTION_PLAYBACK_STATE_CHANGED).setPackage(packageName)
                .putExtra(EXTRA_IS_PLAYING, isPlaying)
        )

    private fun broadcastWallpaperApplied(message: String) =
        sendBroadcast(
            Intent(ACTION_WALLPAPER_APPLIED).setPackage(packageName)
                .putExtra(EXTRA_STATUS_MESSAGE, message)
        )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        cacheNotificationArtwork(sbn)
        refreshActiveSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        removeNotificationArtwork(sbn)
        refreshActiveSessions()
    }

    override fun onDestroy() {
        generation.incrementAndGet()
        generationJob?.cancel()
        try { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Throwable) {}
        try { activeController?.unregisterCallback(callback) } catch (_: Throwable) {}
        activeController = null
        notificationArtwork.values.forEach { if (!it.isRecycled) it.recycle() }
        notificationArtwork.clear()
        scope.cancel()
        super.onDestroy()
    }
}
