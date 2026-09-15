package com.muswall.app.service

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.app.Notification
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import com.muswall.app.data.PreferencesManager
import com.muswall.app.python.PythonBridge
import com.muswall.app.wallpaper.MusicWallpaperService
import com.muswall.app.wallpaper.WallpaperHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val timelineHandler = Handler(Looper.getMainLooper())
    private val stopHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    private val timelineRunnable = object : Runnable {
        override fun run() {
            if (!playing) return
            val controller = activeController
            val state = controller?.playbackState
            val basePosition = state?.position?.coerceAtLeast(0L) ?: prefs.lyricsPosition
            val updateTime = state?.lastPositionUpdateTime ?: 0L
            val elapsed = if (updateTime > 0L) (android.os.SystemClock.elapsedRealtime() - updateTime).coerceAtLeast(0L) else 0L
            val position = (basePosition + elapsed).coerceAtLeast(0L)
            val jumped = lastObservedPosition >= 0L && kotlin.math.abs(position - lastObservedPosition) > 750L
            lastObservedPosition = position
            if (jumped) prefs.lyricsPosition = position
            sendBroadcast(Intent(ACTION_LIVE_TICK).setPackage(packageName).putExtra(EXTRA_POSITION_MS, position))
            timelineHandler.postDelayed(this, 1000L)
        }
    }

    private lateinit var prefs: PreferencesManager
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var wallpaperHelper: WallpaperHelper
    private var sessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var currentTrackId = ""
    private var playing = false
    private var lastPlaybackState = PlaybackState.STATE_NONE
    private var lastObservedPosition = -1L
    private var generationJob: Job? = null
    private val stableStopRunnable = Runnable {
        val state = activeController?.playbackState?.state
        if (!playing && state != PlaybackState.STATE_PLAYING) finalizePlaybackStop()
    }
    private val sessionPollRunnable = object : Runnable {
        override fun run() {
            refreshActiveSessions()
            timelineHandler.postDelayed(this, 1200L)
        }
    }
    private var lyricsJob: Job? = null

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "show_lyrics", "lyrics_language" -> {
                if (playing) activeController?.metadata?.let { handleMetadata(it, true) }
                refreshLive()
            }
            "lyrics_x", "lyrics_y", "lyrics_width", "lyrics_size", "lyrics_lines",
            "lyrics_color", "lyrics_color2", "lyrics_color3", "lyrics_color_mode", "lyrics_shadow",
            "bass_enabled", "bass_x", "bass_y", "bass_width", "bass_height", "bass_sensitivity",
            "bass_color", "bass_color2", "bass_color3", "bass_color_mode" -> refreshLive()
            "widget_opacity", "widget_show_artwork", "widget_show_title", "widget_show_artist",
            "widget_show_controls", "widget_show_progress", "widget_show_custom_text", "widget_custom_text",
            "widget_empty_title", "widget_text_color", "widget_secondary_color", "widget_background_color",
            "widget_title_size", "widget_artist_size", "widget_custom_text_size", "widget_show_time",
            "widget_time_label", "widget_corner_radius", "widget_padding", "widget_artwork_size",
            "widget_layout", "widget_style" -> sendBroadcast(Intent(ACTION_WIDGET_CHANGED).setPackage(packageName))
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
            currentTrackId = ""
            activeController?.metadata?.let { handleMetadata(it, true) }
            refreshLive()
            sendBroadcast(Intent(ACTION_WIDGET_CHANGED).setPackage(packageName))
        }
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = handlePlayback(state)
        override fun onMetadataChanged(metadata: MediaMetadata?) = handleMetadata(metadata, true)
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
        sharedPrefs = getSharedPreferences("muswall", Context.MODE_PRIVATE)
        sharedPrefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        wallpaperHelper = WallpaperHelper(this)
        PythonBridge.initialize(this)

        val widgetFilter = android.content.IntentFilter().apply {
            addAction(ACTION_WIDGET_PLAY_PAUSE)
            addAction(ACTION_WIDGET_NEXT)
            addAction(ACTION_WIDGET_PREV)
        }
        val settingsFilter = android.content.IntentFilter(ACTION_SETTINGS_CHANGED)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(widgetControlReceiver, widgetFilter, Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(settingsReceiver, settingsFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION") registerReceiver(widgetControlReceiver, widgetFilter)
                @Suppress("DEPRECATION") registerReceiver(settingsReceiver, settingsFilter)
            }
        } catch (t: Throwable) {
            Log.w("MusWallMedia", "Receiver registration failed", t)
        }
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
            timelineHandler.removeCallbacks(sessionPollRunnable)
            timelineHandler.post(sessionPollRunnable)
        } catch (t: Throwable) {
            Log.w("MusWallMedia", "Media session connection failed", t)
        }
    }

    private fun refreshActiveSessions() {
        try {
            selectBestController(sessionManager?.getActiveSessions(ComponentName(this, MediaNotificationListenerService::class.java)))
        } catch (t: Throwable) {
            Log.w("MusWallMedia", "Active session refresh failed", t)
        }
    }

    private fun selectBestController(controllers: List<MediaController>?) {
        val list = controllers.orEmpty()
        switchController(
            list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: list.firstOrNull { it.metadata != null }
                ?: list.firstOrNull()
        )
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

        try { controller.registerCallback(callback) } catch (t: Throwable) {
            Log.w("MusWallMedia", "Controller callback registration failed", t)
        }
        handlePlayback(controller.playbackState)
        controller.metadata?.let { handleMetadata(it, true) }
    }

    private fun handlePlayback(state: PlaybackState?) {
        val stateCode = state?.state ?: PlaybackState.STATE_NONE
        val now = stateCode == PlaybackState.STATE_PLAYING
        val was = playing
        if (stateCode == lastPlaybackState && !(now && !was)) return
        lastPlaybackState = stateCode
        playing = now

        if (state != null) prefs.lyricsPosition = state.position.coerceAtLeast(0L)
        prefs.lastPlaybackState = if (now) "playing" else "paused"
        broadcastPlaybackState(now)

        if (now) {
            stopHandler.removeCallbacks(stableStopRunnable)
            timelineHandler.removeCallbacks(timelineRunnable)
            timelineHandler.post(timelineRunnable)
            if (!was) {
                // Some players do not emit metadata reliably when advancing to the next item.
                currentTrackId = ""
                activeController?.metadata?.let { handleMetadata(it, true) }
            }
        } else {
            timelineHandler.removeCallbacks(timelineRunnable)
            // Never call WallpaperManager for a temporary pause/buffer transition.
            stopHandler.removeCallbacks(stableStopRunnable)
            stopHandler.postDelayed(stableStopRunnable, 500L)
        }
        sendBroadcast(Intent(ACTION_WIDGET_CHANGED).setPackage(packageName))
    }

    private fun finalizePlaybackStop() {
        generation.incrementAndGet()
        generationJob?.cancel()
        lyricsJob?.cancel()
        prefs.liveMusicPlaying = false
        // The live WallpaperService renders the saved original image itself.
        sendBroadcast(Intent(ACTION_LIVE_TICK).setPackage(packageName).putExtra(EXTRA_POSITION_MS, prefs.lyricsPosition))
        refreshLive()
        sendBroadcast(Intent(ACTION_WIDGET_CHANGED).setPackage(packageName))
        broadcastWallpaperApplied("Music stopped • original wallpaper restored")
    }

    private fun handleMetadata(metadata:MediaMetadata?,force:Boolean=false){
        if(metadata==null)return
        val title=metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty().ifEmpty{"Unknown title"}
        val artist=metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty().ifEmpty{"Unknown artist"}
        prefs.lastTrackTitle=title
        prefs.lastArtist=artist
        prefs.lastDuration=metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        broadcastTrack(title,artist)
        val artUri=metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)?:metadata.getString(MediaMetadata.METADATA_KEY_ART_URI).orEmpty()
        val mediaId=metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val id=mediaId+"|"+title+"|"+artist+"|"+artUri
        if(!force&&id==currentTrackId)return
        currentTrackId=id
        prefs.lyricsPosition=activeController?.playbackState?.position?.coerceAtLeast(0L)?:prefs.lyricsPosition
        if(!prefs.isAutoEnabled||!playing||prefs.wallpaperMode!=PreferencesManager.MODE_MUSIC)return
        if(!prefs.liveWallpaperEnabled){broadcastWallpaperApplied("Music detected • enable MusWall Live Wallpaper once");return}
        scope.launch(Dispatchers.IO) { wallpaperHelper.prepareOriginalBeforeLiveWallpaper() }
        val token=generation.incrementAndGet()
        generationJob?.cancel()
        generationJob=scope.launch(Dispatchers.Default){
            val art=extractArtwork(metadata)
            try{
                val immediate=findImmediateLyrics(metadata)
                if(prefs.showLyrics){prefs.lastLyrics=immediate?:"[00:00.00] Loading lyrics…";refreshLive()}
                if(art!=null)renderAndSendToLiveWallpaper(art,token,prefs.lastLyrics)else refreshLive()
                if(prefs.showLyrics){
                    val resolved=if(immediate!=null)immediate else resolveLyrics(metadata,title,artist)
                    if(token==generation.get()&&playing){
                        if(resolved.isNotBlank())prefs.lastLyrics=translateIfNeeded(resolved)
                        else if(prefs.lastLyrics.contains("Loading lyrics"))prefs.lastLyrics="Lyrics unavailable for this track"
                        refreshLive()
                    }
                }
            }finally{art?.let{if(!it.isRecycled)it.recycle()}}
        }
    }
    private fun findImmediateLyrics(metadata:MediaMetadata):String?{
        val direct=metadata.getString("android.media.metadata.LYRICS")?.trim().orEmpty()
        if(hasLrcTimestamps(direct))return direct
        for(key in metadata.keySet()){
            val value=metadata.getString(key)?.trim().orEmpty()
            if(key.contains("lyric",true)&&hasLrcTimestamps(value))return value
        }
        return null
    }
    private fun refreshLive() {
        sendBroadcast(Intent(MusicWallpaperService.ACTION_REFRESH).setPackage(packageName))
        sendBroadcast(Intent(ACTION_WIDGET_CHANGED).setPackage(packageName))
    }

    private suspend fun renderAndSendToLiveWallpaper(artwork: Bitmap, token: Long, lyrics: String) {
        if (token != generation.get() || !playing || !prefs.liveWallpaperEnabled) return
        val dm = resources.displayMetrics
        val targetW = (dm.widthPixels * .70f).toInt().coerceIn(480, 1080)
        val targetH = (dm.heightPixels * .70f).toInt().coerceIn(900, 1920)
        wallpaperHelper.saveLastArtwork(artwork)
        val result = PythonBridge.generateWallpaper(artwork, targetW, targetH, prefs.blurRadius.toFloat(), prefs.darkness / 100f, prefs.artScale / 100f, 42, true, prefs.effect, prefs.blurType, prefs.coverHeight, prefs.coverOffset, prefs.transitionHeight, false, "", prefs.photoSource, "") ?: return
        try {
            if (token != generation.get() || !playing || !prefs.liveWallpaperEnabled) return
            wallpaperHelper.saveCurrentForLiveWallpaper(result)
            // Never replace the system wallpaper for a track. WallpaperService renders it.
            prefs.liveMusicPlaying = true
            refreshLive()
            broadcastWallpaperApplied(if (prefs.showLyrics && lyrics.isNotBlank()) "Live wallpaper + lyrics updated" else "Live wallpaper updated")
        } finally {
            if (!result.isRecycled) result.recycle()
        }
    }

    private fun hasLrcTimestamps(text: String): Boolean = Regex("\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?\\]").containsMatchIn(text)

    private fun resolveLyrics(metadata: MediaMetadata, title: String, artist: String): String {
        // Always prefer timestamped lyrics so the overlay can follow the exact playback position.
        val direct = metadata.getString("android.media.metadata.LYRICS")?.trim().orEmpty()
        if (hasLrcTimestamps(direct)) return direct
        for (key in metadata.keySet()) {
            if (!key.contains("lyric", ignoreCase = true)) continue
            val value = metadata.getString(key)?.trim().orEmpty()
            if (hasLrcTimestamps(value)) return value
        }

        // LRCLIB: exact match first, then search. Both attempts prefer syncedLyrics.
        fetchLyricsFromLrcLib(metadata, title, artist, syncedOnly = true)?.takeIf { it.isNotBlank() }?.let { return it }

        // Some players expose plain lyrics directly. Keep them as a last-resort fallback rather than
        // returning them before the synced LRCLIB lookup.
        if (direct.isNotBlank()) return direct
        for (key in metadata.keySet()) {
            if (!key.contains("lyric", ignoreCase = true)) continue
            val value = metadata.getString(key)?.trim().orEmpty()
            if (value.isNotBlank()) return value
        }

        // Final LRCLIB request can return plain lyrics if no synchronized copy exists.
        fetchLyricsFromLrcLib(metadata, title, artist, syncedOnly = false)?.takeIf { it.isNotBlank() }?.let { return it }

        // Independent plain-lyrics fallback. The live renderer will time plain lines
        // smoothly when no timestamped source exists.
        return fetchLyricsOvh(title, artist).orEmpty()
    }

    private fun translateIfNeeded(raw:String):String{
        val lang=prefs.lyricsLanguage.trim().lowercase()
        if(lang.isBlank()||lang=="original"||lang=="auto")return raw
        return try{
            val lines=raw.replace("\r","").split('\n')
            val out=ArrayList<String>()
            var i=0
            while(i<lines.size){
                val chunkLines=ArrayList<String>()
                var chars=0
                while(i<lines.size&&chars+lines[i].length<420){chunkLines+=lines[i];chars+=lines[i].length+1;i++}
                val prefixes=chunkLines.map{Regex("^(\\s*\\[[^]]+\\]\\s*)").find(it)?.value?:""}
                val texts=chunkLines.mapIndexed{idx,line->line.removePrefix(prefixes[idx])}
                val translated=translateChunk(texts.joinToString("\n"),lang).split('\n')
                if(translated.size==texts.size)chunkLines.forEachIndexed{idx,_->out+=prefixes[idx]+translated[idx]}else out+=chunkLines
            }
            out.joinToString("\n")
        }catch(_:Throwable){raw}
    }
    private fun translateChunk(chunk:String,language:String):String{
        return try{
            val q=URLEncoder.encode(chunk,"UTF-8")
            val target=URLEncoder.encode(language,"UTF-8")
            val url=URL("https://api.mymemory.translated.net/get?q="+q+"&langpair=auto%7C"+target)
            val c=url.openConnection() as HttpURLConnection
            c.requestMethod="GET";c.connectTimeout=4000;c.readTimeout=5000;c.setRequestProperty("User-Agent","MusWall/4.0")
            try{
                if(c.responseCode !in 200..299)return chunk
                JSONObject(c.inputStream.bufferedReader().use{it.readText()}).optJSONObject("responseData")?.optString("translatedText")?.takeIf{it.isNotBlank()}?:chunk
            }finally{c.disconnect()}
        }catch(_:Throwable){chunk}
    }
    private fun fetchLyricsFromLrcLib(metadata: MediaMetadata, title: String, artist: String, syncedOnly: Boolean): String? {
        if (title.isBlank() || artist.isBlank()) return null
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        val exact = tryLrcLibGet(metadata, title, artist, duration, syncedOnly)
        if (!exact.isNullOrBlank()) return exact
        return tryLrcLibSearch(title, artist, syncedOnly)
    }

    private fun tryLrcLibGet(metadata: MediaMetadata, title: String, artist: String, duration: Long, syncedOnly: Boolean): String? {
        return try {
            val builder = Uri.Builder().scheme("https").authority("lrclib.net").appendPath("api").appendPath("get").appendQueryParameter("track_name", title).appendQueryParameter("artist_name", artist)
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim()?.takeIf { it.isNotBlank() }?.let { builder.appendQueryParameter("album_name", it) }
            if (duration > 0) builder.appendQueryParameter("duration", (duration / 1000L).toString())
            val connection = (URL(builder.build().toString()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 3500; readTimeout = 5000; setRequestProperty("Accept", "application/json"); setRequestProperty("User-Agent", "MusWall/4.0")
            }
            try {
                if (connection.responseCode !in 200..299) return null
                val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                if (syncedOnly) root.optString("syncedLyrics").trim().takeIf { it.isNotBlank() }
                else root.optString("syncedLyrics").trim().takeIf { it.isNotBlank() } ?: root.optString("plainLyrics").trim().takeIf { it.isNotBlank() }
            } finally { connection.disconnect() }
        } catch (t: Throwable) { Log.d("MusWallLyrics", "LRCLIB exact lookup failed", t); null }
    }

    private fun tryLrcLibSearch(title: String, artist: String, syncedOnly: Boolean): String? {
        return try {
            val query = URLEncoder.encode("$title $artist", "UTF-8")
            val url = URL("https://lrclib.net/api/search?q=$query")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 3500; readTimeout = 6000; setRequestProperty("Accept", "application/json"); setRequestProperty("User-Agent", "MusWall/4.0")
            }
            try {
                if (connection.responseCode !in 200..299) return null
                val array = org.json.JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                var plainFallback: String? = null
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val synced = item.optString("syncedLyrics").trim()
                    val plain = item.optString("plainLyrics").trim()
                    if (synced.isNotBlank()) return synced
                    if (plainFallback == null && plain.isNotBlank()) plainFallback = plain
                }
                if (!syncedOnly) plainFallback else null
            } finally { connection.disconnect() }
        } catch (t: Throwable) { Log.d("MusWallLyrics", "LRCLIB search failed", t); null }
    }

    private fun fetchLyricsOvh(title: String, artist: String): String? {
        return try {
            val a = URLEncoder.encode(artist, "UTF-8")
            val t = URLEncoder.encode(title, "UTF-8")
            val connection = (URL("https://api.lyrics.ovh/v1/$a/$t").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 5000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "MusWall/4.0")
            }
            try {
                if (connection.responseCode !in 200..299) return null
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    .optString("lyrics").trim().takeIf { it.isNotBlank() }
            } finally { connection.disconnect() }
        } catch (t: Throwable) {
            Log.d("MusWallLyrics", "lyrics.ovh fallback failed", t)
            null
        }
    }

    /**
     * Resolve the artwork for the TRACK that is playing, not merely the album/playlist.
     *
     * Automatic priority:
     *   1. Current media notification artwork (usually the exact Now Playing image)
     *   2. METADATA_KEY_ART / ART_URI
     *   3. Display icon
     *   4. Album artwork as the final fallback
     *
     * The Settings > Music photo source selector can force any of these sources.
     */
    private fun extractArtwork(metadata: MediaMetadata): Bitmap? {
        return try {
            val source = prefs.photoSource
            val primary = when (source) {
                PreferencesManager.PHOTO_NOTIFICATION -> notificationArtwork(metadata)
                PreferencesManager.PHOTO_ART -> metadataArt(metadata)
                PreferencesManager.PHOTO_DISPLAY_ICON -> displayIconArtwork(metadata)
                PreferencesManager.PHOTO_ALBUM -> albumArtwork(metadata)
                PreferencesManager.PHOTO_CUSTOM -> customPhotoArtwork()
                else -> notificationArtwork(metadata)
                    ?: metadataArt(metadata)
                    ?: displayIconArtwork(metadata)
                    ?: albumArtwork(metadata)
            }

            if (primary != null) return primary
            if (!prefs.photoFallback) return null

            // If the selected source is unavailable, never leave the wallpaper blank.
            when (source) {
                PreferencesManager.PHOTO_NOTIFICATION -> metadataArt(metadata) ?: albumArtwork(metadata)
                PreferencesManager.PHOTO_ART -> notificationArtwork(metadata) ?: albumArtwork(metadata)
                PreferencesManager.PHOTO_DISPLAY_ICON -> notificationArtwork(metadata) ?: metadataArt(metadata) ?: albumArtwork(metadata)
                PreferencesManager.PHOTO_ALBUM -> notificationArtwork(metadata) ?: metadataArt(metadata)
                PreferencesManager.PHOTO_CUSTOM -> notificationArtwork(metadata) ?: metadataArt(metadata) ?: albumArtwork(metadata)
                else -> null
            }
        } catch (t: Throwable) {
            Log.w("MusWallMedia", "Artwork extraction failed", t)
            null
        }
    }

    private fun metadataArt(metadata: MediaMetadata): Bitmap? {
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { return downsampleArtwork(it) }
        val uri = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI).orEmpty()
        return bitmapFromUri(uri)
    }

    private fun albumArtwork(metadata: MediaMetadata): Bitmap? {
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { return downsampleArtwork(it) }
        val uri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI).orEmpty()
        return bitmapFromUri(uri)
    }

    private fun displayIconArtwork(metadata: MediaMetadata): Bitmap? {
        metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let { return downsampleArtwork(it) }
        val uri = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI).orEmpty()
        return bitmapFromUri(uri)
    }

    /** Prefer the media notification's large icon because many players put their actual
     * current-track artwork here even when MediaMetadata exposes album/playlist art. */
    private fun notificationArtwork(metadata: MediaMetadata): Bitmap? {
        val packageName = activeController?.packageName ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        val notifications = activeNotifications.orEmpty().filter { it.packageName == packageName }

        val matching = notifications.firstOrNull { sbn ->
            val extras = sbn.notification.extras
            val nTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val nText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            (title.isNotBlank() && nTitle.equals(title, ignoreCase = true)) ||
                (title.isNotBlank() && nText.contains(title, ignoreCase = true)) ||
                (artist.isNotBlank() && nText.contains(artist, ignoreCase = true))
        }
        val transport = notifications.firstOrNull { it.notification.category == Notification.CATEGORY_TRANSPORT }
        val candidates = listOfNotNull(matching, transport) + notifications

        for (sbn in candidates.distinctBy { it.key }) {
            val icon = runCatching { sbn.notification.getLargeIcon() }.getOrNull() ?: continue
            val drawable = runCatching { icon.loadDrawable(this) }.getOrNull() ?: continue
            val bitmap = drawableToBitmap(drawable) ?: continue
            return downsampleArtwork(bitmap)
        }
        return null
    }

    private fun customPhotoArtwork(): Bitmap? {
        val uriText = prefs.customPhotoUri.trim()
        return if (uriText.isBlank()) null else bitmapFromUri(uriText)
    }

    private fun bitmapFromUri(uriText: String): Bitmap? {
        if (uriText.isBlank()) return null
        return runCatching {
            contentResolver.openInputStream(Uri.parse(uriText)).use { input ->
                if (input == null) null else BitmapFactory.decodeStream(input)?.let { downsampleArtwork(it) }
            }
        }.getOrNull()
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 512
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 512
        val bitmap = Bitmap.createBitmap(width.coerceAtMost(2048), height.coerceAtMost(2048), Bitmap.Config.ARGB_8888)
        Canvas(bitmap).also { canvas ->
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
        return bitmap
    }

    private fun downsampleArtwork(bitmap: Bitmap): Bitmap? {
        return try {
            val max = 1600
            val scale = minOf(1f, max.toFloat() / maxOf(bitmap.width, bitmap.height))
            if (scale >= 0.999f) bitmap else Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        } catch (_: Throwable) { bitmap }
    }

    private fun broadcastTrack(title: String, artist: String) {
        sendBroadcast(Intent(ACTION_TRACK_CHANGED).setPackage(packageName).putExtra(EXTRA_TRACK_TITLE, title).putExtra(EXTRA_ARTIST, artist))
    }

    private fun broadcastPlaybackState(isPlaying: Boolean) {
        sendBroadcast(Intent(ACTION_PLAYBACK_STATE_CHANGED).setPackage(packageName).putExtra(EXTRA_IS_PLAYING, isPlaying))
    }

    private fun broadcastWallpaperApplied(message: String) {
        sendBroadcast(Intent(ACTION_WALLPAPER_APPLIED).setPackage(packageName).putExtra(EXTRA_STATUS_MESSAGE, message))
    }

    override fun onDestroy() {
        timelineHandler.removeCallbacks(timelineRunnable)
        try { activeController?.unregisterCallback(callback) } catch (_: Throwable) {}
        try { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Throwable) {}
        try { sharedPrefs.unregisterOnSharedPreferenceChangeListener(preferenceListener) } catch (_: Throwable) {}
        try { unregisterReceiver(widgetControlReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(settingsReceiver) } catch (_: Throwable) {}
        scope.cancel()
        super.onDestroy()
    }
}
