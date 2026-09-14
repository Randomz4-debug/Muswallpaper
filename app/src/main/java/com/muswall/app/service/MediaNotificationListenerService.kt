package com.muswall.app.service

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import com.muswall.app.data.PreferencesManager
import com.muswall.app.python.PythonBridge
import com.muswall.app.wallpaper.MusicWallpaperService
import com.muswall.app.wallpaper.WallpaperHelper
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

class MediaNotificationListenerService : NotificationListenerService() {
    companion object {
        const val ACTION_TRACK_CHANGED = "com.muswall.app.ACTION_TRACK_CHANGED"
        const val ACTION_PLAYBACK_STATE_CHANGED = "com.muswall.app.ACTION_PLAYBACK_STATE_CHANGED"
        const val ACTION_WALLPAPER_APPLIED = "com.muswall.app.ACTION_WALLPAPER_APPLIED"
        const val ACTION_LIVE_TICK = "com.muswall.app.ACTION_LIVE_TICK"
        const val ACTION_SETTINGS_CHANGED = "com.muswall.app.ACTION_SETTINGS_CHANGED"
        const val ACTION_WIDGET_PLAY_PAUSE = "com.muswall.app.ACTION_WIDGET_PLAY_PAUSE"
        const val ACTION_WIDGET_NEXT = "com.muswall.app.ACTION_WIDGET_NEXT"
        const val ACTION_WIDGET_PREV = "com.muswall.app.ACTION_WIDGET_PREV"
        const val ACTION_WIDGET_CHANGED = "com.muswall.app.ACTION_WIDGET_CHANGED"
        const val EXTRA_TRACK_TITLE = "extra_track_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        const val EXTRA_STATUS_MESSAGE = "extra_status_message"
        const val EXTRA_POSITION_MS = "extra_position_ms"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val timelineHandler = Handler(Looper.getMainLooper())
    private val timelineRunnable = object : Runnable {
        override fun run() {
            if (playing) {
                val position = activeController?.playbackState?.position?.coerceAtLeast(0L) ?: prefs.lyricsPosition
                prefs.lyricsPosition = position
                sendBroadcast(Intent(ACTION_LIVE_TICK).setPackage(packageName).putExtra(EXTRA_POSITION_MS, position))
                timelineHandler.postDelayed(this, 200L)
            }
        }
    }
    private val generation = AtomicLong(0L)
    private lateinit var prefs: PreferencesManager
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var wallpaperHelper: WallpaperHelper
    private var sessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var currentTrackId = ""
    private var playing = false
    private var generationJob: Job? = null

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "show_lyrics", "lyrics_language" -> {
                if (playing) activeController?.metadata?.let { handleMetadata(it, true) }
                sendBroadcast(Intent(MusicWallpaperService.ACTION_REFRESH).setPackage(packageName))
            }
            "lyrics_x", "lyrics_y", "lyrics_width", "lyrics_size", "lyrics_lines", "lyrics_color", "lyrics_color2", "lyrics_color3", "lyrics_color_mode", "lyrics_shadow",
            "bass_enabled", "bass_x", "bass_y", "bass_width", "bass_height", "bass_sensitivity", "bass_color", "bass_color2", "bass_color3", "bass_color_mode" -> {
                sendBroadcast(Intent(MusicWallpaperService.ACTION_REFRESH).setPackage(packageName))
            }
            "widget_opacity", "widget_show_artwork", "widget_show_title", "widget_show_artist", "widget_show_controls", "widget_show_progress", "widget_show_custom_text",
            "widget_custom_text", "widget_empty_title", "widget_text_color", "widget_secondary_color", "widget_background_color", "widget_title_size", "widget_artist_size",
            "widget_custom_text_size", "widget_show_time", "widget_time_label", "widget_corner_radius", "widget_padding", "widget_artwork_size", "widget_layout", "widget_style" ->
                sendBroadcast(Intent(ACTION_WIDGET_CHANGED).setPackage(packageName))
        }
    }

    private val widgetControlReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val controls = activeController?.transportControls ?: return
            when (intent?.action) {
                ACTION_WIDGET_PLAY_PAUSE -> if (playing) controls.pause() else controls.play()
                ACTION_WIDGET_NEXT -> controls.skipToNext()
                ACTION_WIDGET_PREV -> controls.skipToPrevious()
            }
        }
    }

    private val settingsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_SETTINGS_CHANGED) return
            if (playing) {
                currentTrackId = ""
                activeController?.metadata?.let { handleMetadata(it, true) }
            }
            sendBroadcast(Intent(MusicWallpaperService.ACTION_REFRESH).setPackage(packageName))
            sendBroadcast(Intent(ACTION_WIDGET_CHANGED).setPackage(packageName))
        }
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = handlePlayback(state)
        override fun onMetadataChanged(metadata: MediaMetadata?) = handleMetadata(metadata, true)
        override fun onSessionDestroyed() { handlePlayback(null); refreshActiveSessions() }
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers -> selectBestController(controllers) }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager.getInstance(this)
        sharedPrefs = getSharedPreferences("muswall", Context.MODE_PRIVATE)
        sharedPrefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        wallpaperHelper = WallpaperHelper(this)
        PythonBridge.initialize(this)
        val filter = android.content.IntentFilter().apply { addAction(ACTION_WIDGET_PLAY_PAUSE); addAction(ACTION_WIDGET_NEXT); addAction(ACTION_WIDGET_PREV) }
        val settingsFilter = android.content.IntentFilter(ACTION_SETTINGS_CHANGED)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(widgetControlReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(settingsReceiver, settingsFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION") registerReceiver(widgetControlReceiver, filter)
                @Suppress("DEPRECATION") registerReceiver(settingsReceiver, settingsFilter)
            }
        } catch (t: Throwable) { android.util.Log.w("MusWallMedia", "Receiver registration failed", t) }
    }

    override fun onListenerConnected() { super.onListenerConnected(); connectSessions() }

    private fun connectSessions() {
        try {
            sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MediaNotificationListenerService::class.java)
            sessionManager?.addOnActiveSessionsChangedListener(sessionsListener, component)
            refreshActiveSessions()
        } catch (t: Throwable) { android.util.Log.w("MusWallMedia", "Media session connection failed", t) }
    }

    private fun refreshActiveSessions() {
        try { selectBestController(sessionManager?.getActiveSessions(ComponentName(this, MediaNotificationListenerService::class.java))) }
        catch (t: Throwable) { android.util.Log.w("MusWallMedia", "Active session refresh failed", t) }
    }

    private fun selectBestController(controllers: List<MediaController>?) {
        val list = controllers.orEmpty()
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.metadata != null } ?: list.firstOrNull()
        switchController(best)
    }

    private fun switchController(controller: MediaController?) {
        if (controller?.sessionToken == activeController?.sessionToken) {
            controller?.playbackState?.let { handlePlayback(it) }
            controller?.metadata?.let { handleMetadata(it, false) }
            if (controller == null) handlePlayback(null)
            return
        }
        try { activeController?.unregisterCallback(callback) } catch (_: Throwable) {}
        activeController = controller
        currentTrackId = ""
        if (controller == null) {
            handlePlayback(null)
            broadcastTrack(prefs.lastTrackTitle.ifBlank { "No music detected" }, prefs.lastArtist.ifBlank { "Last played" })
            return
        }
        try { controller.registerCallback(callback) } catch (t: Throwable) { android.util.Log.w("MusWallMedia", "Controller callback registration failed", t) }
        handlePlayback(controller.playbackState)
        controller.metadata?.let { handleMetadata(it, true) }
    }

    private fun handlePlayback(state: PlaybackState?) {
        val now = state?.state == PlaybackState.STATE_PLAYING
        val was = playing
        playing = now
        if (state != null) prefs.lyricsPosition = state.position.coerceAtLeast(0L)
        prefs.lastPlaybackState = if (now) "playing" else "paused"
        broadcastPlaybackState(now)
        if (now) {
            timelineHandler.removeCallbacks(timelineRunnable)
            timelineHandler.post(timelineRunnable)
            if (!was) activeController?.metadata?.let { handleMetadata(it, true) }
        } else {
            timelineHandler.removeCallbacks(timelineRunnable)
            generationJob?.cancel()
            prefs.liveMusicPlaying = false
            sendBroadcast(Intent(ACTION_LIVE_TICK).setPackage(packageName).putExtra(EXTRA_POSITION_MS, prefs.lyricsPosition))
            if (prefs.liveWallpaperEnabled) sendBroadcast(Intent(MusicWallpaperService.ACTION_REFRESH).setPackage(packageName))
            broadcastWallpaperApplied("Music paused • last music wallpaper kept")
        }
        sendBroadcast(Intent(ACTION_WIDGET_CHANGED).setPackage(packageName))
    }

    private fun handleMetadata(metadata: MediaMetadata?, force: Boolean = false) {
        if (metadata == null) return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty().ifEmpty { "Unknown title" }
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty().ifEmpty { "Unknown artist" }
        broadcastTrack(title, artist)
        prefs.lastTrackTitle = title
        prefs.lastArtist = artist
        val artUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI).orEmpty()
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val id = "$mediaId\u0000$title\u0000$artist\u0000$artUri"
        if (!force && id == currentTrackId) return
        currentTrackId = id
        prefs.lyricsPosition = activeController?.playbackState?.position?.coerceAtLeast(0L) ?: prefs.lyricsPosition
        if (!prefs.isAutoEnabled || !playing || prefs.wallpaperMode != PreferencesManager.MODE_MUSIC) return
        if (!prefs.liveWallpaperEnabled) {
            broadcastWallpaperApplied("Music detected • enable MusWall Live Wallpaper once")
            return
        }
        val token = generation.incrementAndGet()
        generationJob?.cancel()
        generationJob = scope.launch(Dispatchers.Default) {
            val art = extractArtwork(metadata)
            try {
                if (prefs.showLyrics) {
                    // Put an immediate placeholder on screen so the lyrics layer is never invisible while a network lookup runs.
                    prefs.lastLyrics = "[00:00.00] Loading lyrics…"
                    sendBroadcast(Intent(MusicWallpaperService.ACTION_REFRESH).setPackage(packageName))
                }
                val lyrics = if (prefs.showLyrics) resolveLyrics(metadata, title, artist) else prefs.lastLyrics
                if (prefs.showLyrics && lyrics.isNotBlank()) prefs.lastLyrics = lyrics
                if (art != null) renderAndSendToLiveWallpaper(art, token, lyrics)
                else sendBroadcast(Intent(MusicWallpaperService.ACTION_REFRESH).setPackage(packageName))
            } finally { art?.let { if (!it.isRecycled) it.recycle() } }
        }
    }

    private fun isOurLockLiveWallpaper(): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT < 34) false
        else WallpaperManager.getInstance(this).getWallpaperInfo(WallpaperManager.FLAG_LOCK)?.component == ComponentName(this, MusicWallpaperService::class.java)
    } catch (_: Throwable) { false }

    private suspend fun renderAndSendToLiveWallpaper(artwork: Bitmap, token: Long, lyrics: String) {
        if (token != generation.get() || !playing || !prefs.liveWallpaperEnabled) return
        val dm = resources.displayMetrics
        val targetW = (dm.widthPixels * 0.70f).toInt().coerceIn(480, 1080)
        val targetH = (dm.heightPixels * 0.70f).toInt().coerceIn(900, 1920)
        wallpaperHelper.saveLastArtwork(artwork)
        val result = PythonBridge.generateWallpaper(artwork, targetW, targetH, prefs.blurRadius.toFloat(), prefs.darkness / 100f, prefs.artScale / 100f, 42, true, prefs.effect, prefs.blurType, prefs.coverHeight, prefs.coverOffset, prefs.transitionHeight, false, "", prefs.photoSource, "") ?: return
        try {
            if (token != generation.get() || !playing || !prefs.liveWallpaperEnabled) return
            wallpaperHelper.saveCurrentForLiveWallpaper(result)
            if (!isOurLockLiveWallpaper()) wallpaperHelper.applyLockFrame(result)
            prefs.liveMusicPlaying = true
            sendBroadcast(Intent(MusicWallpaperService.ACTION_REFRESH).setPackage(packageName))
            sendBroadcast(Intent(ACTION_WIDGET_CHANGED).setPackage(packageName))
            broadcastWallpaperApplied(if (prefs.showLyrics && lyrics.isNotBlank()) "Live wallpaper + synced lyrics updated" else "Live wallpaper updated")
        } finally { if (!result.isRecycled) result.recycle() }
    }

    private fun hasLrcTimestamps(text: String): Boolean = Regex("\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?\\]").containsMatchIn(text)

    private fun resolveLyrics(metadata: MediaMetadata, title: String, artist: String): String {
        val direct = metadata.getString("android.media.metadata.LYRICS")?.trim().orEmpty()
        val directWithTimestamps = direct.takeIf { it.isNotBlank() && hasLrcTimestamps(it) }
        if (directWithTimestamps != null) return translateIfNeeded(directWithTimestamps)
        for (key in metadata.keySet()) {
            val value = metadata.getString(key)?.trim().orEmpty()
            if (key.contains("lyric", true) && value.isNotBlank() && hasLrcTimestamps(value)) return translateIfNeeded(value)
        }
        val synced = fetchLyricsFromLrcLib(metadata, title, artist, true)
        if (!synced.isNullOrBlank()) return translateIfNeeded(synced)
        val plain = direct.takeIf { it.isNotBlank() }
            ?: metadata.keySet().firstNotNullOfOrNull { key -> if (key.contains("lyric", true)) metadata.getString(key)?.trim()?.takeIf { it.isNotBlank() } else null }
        return translateIfNeeded(plain ?: fetchLyricsFromLrcLib(metadata, title, artist, false).orEmpty())
    }

    private fun translateIfNeeded(raw: String): String {
        val language = prefs.lyricsLanguage.trim().lowercase()
        if (language.isBlank() || language == "original" || language == "auto") return raw
        return try {
            val lines = raw.replace("\r", "").split('\n')
            val chunks = ArrayList<String>(); var current = StringBuilder()
            for (line in lines) {
                if (current.length + line.length + 1 > 450 && current.isNotEmpty()) { chunks += current.toString(); current = StringBuilder() }
                current.append(line).append('\n')
            }
            if (current.isNotEmpty()) chunks += current.toString()
            chunks.joinToString("") { chunk -> translateChunk(chunk, language) }
        } catch (_: Throwable) { raw }
    }

    private fun translateChunk(chunk: String, language: String): String {
        return try {
            val query = URLEncoder.encode(chunk, "UTF-8")
            val url = URL("https://api.mymemory.translated.net/get?q=$query&langpair=auto|${URLEncoder.encode(language, "UTF-8")}")
            val connection = (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 4000; readTimeout = 5000; setRequestProperty("User-Agent", "MusWall/4.0") }
            try {
                if (connection.responseCode !in 200..299) return chunk
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONObject("responseData")?.optString("translatedText")?.takeIf { it.isNotBlank() } ?: chunk
            } finally { connection.disconnect() }
        } catch (_: Throwable) { chunk }
    }

    private fun fetchLyricsFromLrcLib(metadata: MediaMetadata, title: String, artist: String, syncedOnly: Boolean): String? {
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        return try {
            val uri = Uri.Builder().scheme("https").authority("lrclib.net").appendPath("api").appendPath("get").appendQueryParameter("track_name", title).appendQueryParameter("artist_name", artist).apply {
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("album_name", it) }
                if (durationMs > 0L) appendQueryParameter("duration", (durationMs / 1000L).toString())
            }.build()
            val connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 3000; readTimeout = 4500; setRequestProperty("Accept", "application/json"); setRequestProperty("User-Agent", "MusWall/4.0") }
            try {
                if (connection.responseCode !in 200..299) return null
                val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                if (syncedOnly) root.optString("syncedLyrics").trim().takeIf { it.isNotBlank() }
                else root.optString("syncedLyrics").trim().takeIf { it.isNotBlank() } ?: root.optString("plainLyrics").trim().takeIf { it.isNotBlank() }
            } finally { connection.disconnect() }
        } catch (t: Throwable) { android.util.Log.d("MusWallLyrics", "Lyrics lookup failed", t); null }
    }

    private fun extractArtwork(metadata: MediaMetadata): Bitmap? {
        return try {
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { downsampleArtwork(it) }?.let { return it }
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { downsampleArtwork(it) }?.let { return it }
            val uriText = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            if (uriText.isNullOrBlank()) return null
            contentResolver.openInputStream(Uri.parse(uriText)).use { input ->
                if (input == null) return@use null
                val decoded = BitmapFactory.decodeStream(input) ?: return@use null
                val output = downsampleArtwork(decoded)
                if (output !== decoded && !decoded.isRecycled) decoded.recycle()
                output
            }
        } catch (t: Throwable) { android.util.Log.w("MusWallMedia", "Artwork extraction failed", t); null }
    }

    private fun downsampleArtwork(bitmap: Bitmap): Bitmap? {
        return try {
            val max = 900
            val source = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            if (source.width <= max && source.height <= max) return source
            val scale = minOf(max.toFloat() / source.width, max.toFloat() / source.height)
            val w = (source.width * scale).toInt().coerceAtLeast(1)
            val h = (source.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(source, w, h, true).also { if (it !== source && !source.isRecycled) source.recycle() }
        } catch (_: Throwable) { null }
    }

    private fun broadcastTrack(title: String, artist: String) = sendBroadcast(Intent(ACTION_TRACK_CHANGED).setPackage(packageName).putExtra(EXTRA_TRACK_TITLE, title).putExtra(EXTRA_ARTIST, artist))
    private fun broadcastPlaybackState(isPlaying: Boolean) = sendBroadcast(Intent(ACTION_PLAYBACK_STATE_CHANGED).setPackage(packageName).putExtra(EXTRA_IS_PLAYING, isPlaying))
    private fun broadcastWallpaperApplied(message: String) = sendBroadcast(Intent(ACTION_WALLPAPER_APPLIED).setPackage(packageName).putExtra(EXTRA_STATUS_MESSAGE, message))
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) { refreshActiveSessions() }
    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) { refreshActiveSessions() }
    override fun onDestroy() {
        generation.incrementAndGet(); generationJob?.cancel(); timelineHandler.removeCallbacksAndMessages(null)
        try { sharedPrefs.unregisterOnSharedPreferenceChangeListener(preferenceListener) } catch (_: Throwable) {}
        try { unregisterReceiver(widgetControlReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(settingsReceiver) } catch (_: Throwable) {}
        try { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Throwable) {}
        try { activeController?.unregisterCallback(callback) } catch (_: Throwable) {}
        activeController = null; scope.cancel(); super.onDestroy()
    }
}
