from pathlib import Path

ROOT = Path('.')
MEDIA = ROOT/'app/src/main/java/com/muswall/app/service/MediaNotificationListenerService.kt'
WALL = ROOT/'app/src/main/java/com/muswall/app/wallpaper/MusicWallpaperService.kt'
HELP = ROOT/'app/src/main/java/com/muswall/app/wallpaper/WallpaperHelper.kt'

def must_replace(text, old, new, label):
    if old not in text:
        raise SystemExit(f'{label}: expected text not found')
    return text.replace(old, new, 1)

s = MEDIA.read_text()
s = must_replace(s, '''    private val timelineHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)''', '''    private val timelineHandler = Handler(Looper.getMainLooper())
    private val stopHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)''', 'media handlers')
s = must_replace(s, '''            if (position - prefs.lyricsPosition >= 250L || updateTime == 0L) prefs.lyricsPosition = position
            sendBroadcast(Intent(ACTION_LIVE_TICK).setPackage(packageName).putExtra(EXTRA_POSITION_MS, position))
            timelineHandler.postDelayed(this, 250L)''', '''            // Only send a lightweight checkpoint. The wallpaper interpolates locally.
            sendBroadcast(Intent(ACTION_LIVE_TICK).setPackage(packageName).putExtra(EXTRA_POSITION_MS, position))
            timelineHandler.postDelayed(this, 1000L)''', 'timeline loop')
s = must_replace(s, '''    private var currentTrackId = ""
    private var playing = false
    private var generationJob: Job? = null''', '''    private var currentTrackId = ""
    private var playing = false
    private var lastPlaybackState = PlaybackState.STATE_NONE
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
    }''', 'media state fields')
s = must_replace(s, '''            sessionManager?.addOnActiveSessionsChangedListener(sessionsListener, component)
            refreshActiveSessions()''', '''            sessionManager?.addOnActiveSessionsChangedListener(sessionsListener, component)
            refreshActiveSessions()
            timelineHandler.removeCallbacks(sessionPollRunnable)
            timelineHandler.post(sessionPollRunnable)''', 'session polling')

start = s.index('    private fun handlePlayback(state: PlaybackState?) {')
end = s.index('\n    private fun handleMetadata(', start)
new_handle = '''    private fun handlePlayback(state: PlaybackState?) {
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
'''
s = s[:start] + new_handle + s[end:]
s = must_replace(s, '''            wallpaperHelper.saveCurrentForLiveWallpaper(result)
            if (!isOurLockLiveWallpaper()) wallpaperHelper.applyLockFrame(result)
            prefs.liveMusicPlaying = true''', '''            wallpaperHelper.saveCurrentForLiveWallpaper(result)
            // Never replace the system wallpaper for a track. WallpaperService renders it.
            prefs.liveMusicPlaying = true''', 'remove static track wallpaper')
old_helper = '''    private fun isOurLockLiveWallpaper(): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT < 34) false
        else WallpaperManager.getInstance(this).getWallpaperInfo(WallpaperManager.FLAG_LOCK)?.component == ComponentName(this, MusicWallpaperService::class.java)
    } catch (_: Throwable) { false }

'''
s = s.replace(old_helper, '', 1)
old_destroy = '''    override fun onDestroy() {
        try { sharedPrefs.unregisterOnSharedPreferenceChangeListener(preferenceListener) } catch (_: Throwable) {}'''
new_destroy = '''    override fun onDestroy() {
        timelineHandler.removeCallbacks(timelineRunnable)
        timelineHandler.removeCallbacks(sessionPollRunnable)
        stopHandler.removeCallbacks(stableStopRunnable)
        try { sharedPrefs.unregisterOnSharedPreferenceChangeListener(preferenceListener) } catch (_: Throwable) {}'''
if old_destroy in s:
    s = s.replace(old_destroy, new_destroy, 1)
MEDIA.write_text(s)

s = WALL.read_text()
s = must_replace(s, '''        private var playbackPosition = prefs.lyricsPosition
        private val refreshRunnable = Runnable { drawWallpaper() }''', '''        private var playbackPosition = prefs.lyricsPosition
        private var playbackCheckpointElapsed = android.os.SystemClock.elapsedRealtime()
        private var playbackCheckpointPosition = playbackPosition
        private val refreshRunnable = Runnable { drawWallpaper() }
        private val animationRunnable = object : Runnable {
            override fun run() {
                if (!visible || !prefs.liveMusicPlaying) return
                drawWallpaper()
                val delay = when {
                    prefs.bassEnabled -> 33L
                    prefs.showLyrics -> 80L
                    else -> 250L
                }
                drawHandler.postDelayed(this, delay)
            }
        }''', 'live animation state')
s = must_replace(s, '''                    MediaNotificationListenerService.ACTION_LIVE_TICK -> {
                        playbackPosition = intent.getLongExtra(MediaNotificationListenerService.EXTRA_POSITION_MS, playbackPosition).coerceAtLeast(0L)
                        if (visible && (prefs.showLyrics || prefs.bassEnabled)) requestDraw()
                    }''', '''                    MediaNotificationListenerService.ACTION_LIVE_TICK -> {
                        playbackCheckpointPosition = intent.getLongExtra(MediaNotificationListenerService.EXTRA_POSITION_MS, playbackPosition).coerceAtLeast(0L)
                        playbackPosition = playbackCheckpointPosition
                        playbackCheckpointElapsed = android.os.SystemClock.elapsedRealtime()
                        requestDraw()
                    }''', 'live position checkpoints')
s = must_replace(s, '''        override fun onVisibilityChanged(isVisible: Boolean) { visible = isVisible; if (isVisible) requestDraw(true) }''', '''        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) requestDraw(true) else drawHandler.removeCallbacks(animationRunnable)
        }''', 'visibility lifecycle')
s = must_replace(s, '''        override fun onSurfaceDestroyed(holder: SurfaceHolder) { visible = false; drawHandler.removeCallbacks(refreshRunnable); super.onSurfaceDestroyed(holder) }''', '''        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            drawHandler.removeCallbacks(refreshRunnable)
            drawHandler.removeCallbacks(animationRunnable)
            super.onSurfaceDestroyed(holder)
        }''', 'surface lifecycle')
s = must_replace(s, '''        private fun requestDraw(force: Boolean = false) {
            drawHandler.removeCallbacks(refreshRunnable)
            drawHandler.post(refreshRunnable)
            if (visible && (prefs.bassEnabled || prefs.showLyrics)) {
                drawHandler.postDelayed(refreshRunnable, if (prefs.bassEnabled) 50L else 80L)
            }
        }''', '''        private fun requestDraw(force: Boolean = false) {
            drawHandler.removeCallbacks(refreshRunnable)
            drawHandler.post(refreshRunnable)
            drawHandler.removeCallbacks(animationRunnable)
            if (visible && prefs.liveMusicPlaying && (prefs.bassEnabled || prefs.showLyrics)) {
                drawHandler.post(animationRunnable)
            }
        }''', 'draw scheduler')
s = must_replace(s, '''        private fun sourceFile(): File? {
            if (!prefs.liveMusicPlaying) {
                val original = File(applicationContext.filesDir, WallpaperHelper.FILE_ORIGINAL_LOCK)
                if (original.exists()) return original
                return null
            }
            val current = File(applicationContext.filesDir, WallpaperHelper.FILE_CURRENT)
            if (current.exists()) return current
            val last = File(applicationContext.filesDir, WallpaperHelper.FILE_LAST_ARTWORK)
            return last.takeIf { it.exists() }
        }''', '''        private fun sourceFile(): File? {
            if (!prefs.liveMusicPlaying) {
                val originalLock = File(applicationContext.filesDir, WallpaperHelper.FILE_ORIGINAL_LOCK)
                if (originalLock.exists()) return originalLock
                val originalHome = File(applicationContext.filesDir, WallpaperHelper.FILE_ORIGINAL_HOME)
                if (originalHome.exists()) return originalHome
                return null
            }
            val current = File(applicationContext.filesDir, WallpaperHelper.FILE_CURRENT)
            if (current.exists()) return current
            val last = File(applicationContext.filesDir, WallpaperHelper.FILE_LAST_ARTWORK)
            return last.takeIf { it.exists() }
        }''', 'original wallpaper fallback')
s = must_replace(s, '''        private fun drawWallpaper() {
            val holder = surfaceHolder''', '''        private fun drawWallpaper() {
            if (prefs.liveMusicPlaying) {
                val elapsed = (android.os.SystemClock.elapsedRealtime() - playbackCheckpointElapsed).coerceAtLeast(0L)
                playbackPosition = playbackCheckpointPosition + elapsed
            } else {
                playbackPosition = prefs.lyricsPosition.coerceAtLeast(0L)
            }
            val holder = surfaceHolder''', 'local playback interpolation')
s = s.replace('''            drawHandler.removeCallbacksAndMessages(null)
            if (receiverRegistered)''', '''            drawHandler.removeCallbacksAndMessages(null)
            drawHandler.removeCallbacks(animationRunnable)
            if (receiverRegistered)''', 1)
WALL.write_text(s)

s = HELP.read_text()
s = must_replace(s, '''    suspend fun prepareOriginalBeforeLiveWallpaper(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (originalFile(WallpaperManager.FLAG_LOCK).exists()) return@withContext true
            if (prefs.originalLockWallpaperUri.isNotBlank()) return@withContext true
            backupOneIfMissingForPreparation(WallpaperManager.FLAG_LOCK)
            originalFile(WallpaperManager.FLAG_LOCK).exists()
        } catch (_: Throwable) { false }
    }''', '''    suspend fun prepareOriginalBeforeLiveWallpaper(): Boolean = withContext(Dispatchers.IO) {
        try {
            backupOneIfMissingForPreparation(WallpaperManager.FLAG_SYSTEM)
            backupOneIfMissingForPreparation(WallpaperManager.FLAG_LOCK)
            originalFile(WallpaperManager.FLAG_LOCK).exists() || prefs.originalLockWallpaperUri.isNotBlank()
        } catch (_: Throwable) { false }
    }''', 'original wallpaper preparation')
HELP.write_text(s)

print('MusWall live-wallpaper architecture patch applied.')
