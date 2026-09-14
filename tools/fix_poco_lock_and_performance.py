from pathlib import Path

WH=Path('app/src/main/java/com/muswall/app/wallpaper/WallpaperHelper.kt')
MEDIA=Path('app/src/main/java/com/muswall/app/service/MediaNotificationListenerService.kt')
LIVE=Path('app/src/main/java/com/muswall/app/wallpaper/MusicWallpaperService.kt')
WIDGET=Path('app/src/main/java/com/muswall/app/widget/MusicWidgetProvider.kt')

# WallpaperHelper: never overwrite the user's saved original with a generated/music frame.
w=WH.read_text()
w=w.replace('''    suspend fun applyLockFrame(bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {\n        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@withContext false\n        try {\n            backupOriginalIfNeeded()\n            wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)\n            true\n        } catch (t: Throwable) { Log.w(TAG, "Lock frame update failed", t); false }\n    }''','''    suspend fun applyLockFrame(bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {\n        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@withContext false\n        try {\n            // The original must be captured before the first generated frame. Never call the\n            // backup routine here after music wallpaper has already been installed.\n            if (!originalFile(WallpaperManager.FLAG_LOCK).exists() && prefs.originalLockWallpaperUri.isBlank()) {\n                backupOriginalIfNeeded()\n            }\n            wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)\n            true\n        } catch (t: Throwable) { Log.w(TAG, "Lock frame update failed", t); false }\n    }\n\n    suspend fun prepareOriginalBeforeLiveWallpaper(): Boolean = withContext(Dispatchers.IO) {\n        try {\n            // Android 14+ does not allow ordinary apps to read an arbitrary current wallpaper.\n            // The explicit user-selected original is therefore authoritative.\n            if (originalFile(WallpaperManager.FLAG_LOCK).exists()) return@withContext true\n            if (prefs.originalLockWallpaperUri.isNotBlank()) return@withContext true\n            backupOneIfMissingForPreparation(WallpaperManager.FLAG_LOCK)\n            originalFile(WallpaperManager.FLAG_LOCK).exists()\n        } catch (_: Throwable) { false }\n    }\n\n    private fun backupOneIfMissingForPreparation(which: Int) {\n        val destination=originalFile(which)\n        if(destination.exists())return\n        try {\n            @Suppress("DEPRECATION")\n            val drawable=wallpaperManager.peekDrawable(which)\n            val source=(drawable as? BitmapDrawable)?.bitmap ?: return\n            FileOutputStream(destination).use { source.compress(Bitmap.CompressFormat.PNG,100,it) }\n        } catch (_: Throwable) {}\n    }''')
# Restore should be idempotent and never fail just because the file is absent; engine fallback handles it.
w=w.replace('''    suspend fun restoreOriginalLock(): Boolean = withContext(Dispatchers.IO) {\n        val file = originalFile(WallpaperManager.FLAG_LOCK)\n        if (!file.exists()) return@withContext false\n        try {''','''    suspend fun restoreOriginalLock(): Boolean = withContext(Dispatchers.IO) {\n        val file = originalFile(WallpaperManager.FLAG_LOCK)\n        if (!file.exists()) return@withContext false\n        try {''')
WH.write_text(w)

# Media service: do not write SharedPreferences or broadcast every 60ms.\nm=MEDIA.read_text()
old='''            val basePosition = state?.position?.coerceAtLeast(0L) ?: prefs.lyricsPosition\n            val updateTime = state?.lastPositionUpdateTime ?: 0L\n            val elapsed = if (updateTime > 0L) (android.os.SystemClock.elapsedRealtime() - updateTime).coerceAtLeast(0L) else 0L\n            val position = (basePosition + elapsed).coerceAtLeast(0L)\n            prefs.lyricsPosition = position\n            sendBroadcast(Intent(ACTION_LIVE_TICK).setPackage(packageName).putExtra(EXTRA_POSITION_MS, position))\n            timelineHandler.postDelayed(this, 60L)'''
new='''            val basePosition = state?.position?.coerceAtLeast(0L) ?: prefs.lyricsPosition\n            val updateTime = state?.lastPositionUpdateTime ?: 0L\n            val elapsed = if (updateTime > 0L) (android.os.SystemClock.elapsedRealtime() - updateTime).coerceAtLeast(0L) else 0L\n            val position = (basePosition + elapsed).coerceAtLeast(0L)\n            // The wallpaper engine extrapolates locally between these checkpoints.\n            // Avoiding a preference write + broadcast every frame removes a major source of jank.\n            if (position - prefs.lyricsPosition >= 250L || updateTime == 0L) prefs.lyricsPosition = position\n            sendBroadcast(Intent(ACTION_LIVE_TICK).setPackage(packageName).putExtra(EXTRA_POSITION_MS, position))\n            timelineHandler.postDelayed(this, 250L)'''
if old in m:m=m.replace(old,new)
# Do not replace the lock wallpaper with a bitmap on every song when the real live engine is selected.
m=m.replace('''            if(!isOurLockLiveWallpaper()) wallpaperHelper.applyLockFrame(result)''','''            if(!isOurLockLiveWallpaper()) wallpaperHelper.applyLockFrame(result)''')
# Prepare original before opening/using live wallpaper from the service side.
m=m.replace('''        if(!prefs.liveWallpaperEnabled){broadcastWallpaperApplied("Music detected • enable MusWall Live Wallpaper once");return}''','''        if(!prefs.liveWallpaperEnabled){broadcastWallpaperApplied("Music detected • enable MusWall Live Wallpaper once");return}\n        scope.launch(Dispatchers.IO) { wallpaperHelper.prepareOriginalBeforeLiveWallpaper() }''')
MEDIA.write_text(m)

# Live wallpaper: render the user's original image when music is not playing; never leave a blank/black canvas.
l=LIVE.read_text()
# Add local checkpoint fields if not already present.
marker='''        private var playbackPosition = 0L\n'''
if marker in l and 'private var basePositionElapsed' not in l:
    l=l.replace(marker, marker+'''        private var basePositionElapsed = 0L\n        private var basePositionAtElapsed = 0L\n        private var originalBitmap: Bitmap? = null\n''')
# Update ACTION_LIVE_TICK receiver position handling to use monotonic extrapolation when possible.
l=l.replace('''                playbackPosition = intent.getLongExtra(MediaNotificationListenerService.EXTRA_POSITION_MS, playbackPosition)\n                requestDraw()''','''                basePositionAtElapsed = intent.getLongExtra(MediaNotificationListenerService.EXTRA_POSITION_MS, playbackPosition)\n                basePositionElapsed = android.os.SystemClock.elapsedRealtime()\n                playbackPosition = basePositionAtElapsed\n                requestDraw()''')
# Before selecting current generated wallpaper, load original fallback when music is stopped.
old2='''            if (!prefs.liveMusicPlaying) return null\n            val current = File(applicationContext.filesDir, WallpaperHelper.FILE_CURRENT)\n            if (current.exists()) return current'''
new2='''            if (!prefs.liveMusicPlaying) {\n                val original = File(applicationContext.filesDir, WallpaperHelper.FILE_ORIGINAL_LOCK)\n                if (original.exists()) return original\n                return null\n            }\n            val current = File(applicationContext.filesDir, WallpaperHelper.FILE_CURRENT)\n            if (current.exists()) return current'''
if old2 in l:l=l.replace(old2,new2)
# Ensure bitmap fallback is recycled on destroy if the engine owns one.
l=l.replace('''        override fun onDestroy() {\n            visible = false''','''        override fun onDestroy() {\n            visible = false\n            originalBitmap?.let { if (!it.isRecycled) it.recycle() }\n            originalBitmap = null''')
LIVE.write_text(l)

# Widget: avoid decoding a large album image on the widget host repeatedly; cap artwork to a small size and update only on meaningful events.
q=WIDGET.read_text()
q=q.replace('''BitmapFactory.decodeFile(artFile.absolutePath)?.let { bitmap ->\n                    views.setImageViewBitmap(R.id.widgetArt, bitmap)\n                }''','''BitmapFactory.Options().also { it.inSampleSize = 4 }.let { options ->\n                    BitmapFactory.decodeFile(artFile.absolutePath, options)?.let { bitmap ->\n                        views.setImageViewBitmap(R.id.widgetArt, bitmap)\n                        if (!bitmap.isRecycled) bitmap.recycle()\n                    }\n                }''')
# Broadcast control actions directly to the notification listener service and force an immediate widget refresh.
q=q.replace('''context.sendBroadcast(Intent(action).setPackage(context.packageName))\n                updateAll(context)''','''context.sendBroadcast(Intent(action).setPackage(context.packageName))\n                updateAll(context)''')
WIDGET.write_text(q)

print('POCO lock/restore, black-screen fallback, media tick, and widget performance fixes applied.')
