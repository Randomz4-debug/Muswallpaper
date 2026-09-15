from pathlib import Path

MAIN = Path('app/src/main/java/com/muswall/app/ui/MainActivity.kt')
HELPER = Path('app/src/main/java/com/muswall/app/wallpaper/WallpaperHelper.kt')
LIVE = Path('app/src/main/java/com/muswall/app/wallpaper/MusicWallpaperService.kt')
MEDIA = Path('app/src/main/java/com/muswall/app/service/MediaNotificationListenerService.kt')

# ---------- Reliable original-wallpaper backup ----------
m = MAIN.read_text()
old = 'findViewById<MaterialButton>(R.id.btnLiveWallpaper).setOnClickListener { prefs.liveWallpaperEnabled = true; wallpaperHelper.openLiveWallpaperPicker() }'
new = '''findViewById<MaterialButton>(R.id.btnLiveWallpaper).setOnClickListener {
            uiScope.launch(Dispatchers.IO) {
                wallpaperHelper.prepareOriginalBeforeLiveWallpaper()
                withContext(Dispatchers.Main) {
                    prefs.liveWallpaperEnabled = true
                    wallpaperHelper.openLiveWallpaperPicker()
                }
            }
        }'''
if old in m:
    MAIN.write_text(m.replace(old, new))

h = HELPER.read_text()
old = '''    suspend fun prepareOriginalBeforeLiveWallpaper(): Boolean = withContext(Dispatchers.IO) {
        try {
            backupOneIfMissingForPreparation(WallpaperManager.FLAG_SYSTEM)
            backupOneIfMissingForPreparation(WallpaperManager.FLAG_LOCK)
            originalFile(WallpaperManager.FLAG_LOCK).exists() || prefs.originalLockWallpaperUri.isNotBlank()
        } catch (_: Throwable) { false }
    }

    private fun backupOneIfMissingForPreparation(which: Int) {
        val destination=originalFile(which)
        if(destination.exists())return
        try {
            @Suppress("DEPRECATION")
            val drawable=wallpaperManager.peekDrawable(which)
            val source=(drawable as? BitmapDrawable)?.bitmap ?: return
            FileOutputStream(destination).use { source.compress(Bitmap.CompressFormat.PNG,100,it) }
        } catch (_: Throwable) {}
    }'''
new = '''    suspend fun prepareOriginalBeforeLiveWallpaper(): Boolean = withContext(Dispatchers.IO) {
        try {
            val selected = prefs.staticWallpaperUri.trim()
            if (selected.isNotBlank()) {
                val uri = runCatching { Uri.parse(selected) }.getOrNull()
                if (uri != null) {
                    copyUriIfMissing(uri, originalFile(WallpaperManager.FLAG_SYSTEM))
                    copyUriIfMissing(uri, originalFile(WallpaperManager.FLAG_LOCK))
                }
            }
            backupOneIfMissingForPreparation(WallpaperManager.FLAG_SYSTEM)
            backupOneIfMissingForPreparation(WallpaperManager.FLAG_LOCK)
            originalFile(WallpaperManager.FLAG_SYSTEM).exists() || originalFile(WallpaperManager.FLAG_LOCK).exists()
        } catch (_: Throwable) { false }
    }

    private fun copyUriIfMissing(uri: Uri, destination: File) {
        if (destination.exists()) return
        try {
            val tmp = File(context.filesDir, destination.name + ".tmp")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output, 64 * 1024) }
            } ?: return
            if (!tmp.renameTo(destination)) tmp.delete()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not copy selected wallpaper", t)
        }
    }

    private fun backupOneIfMissingForPreparation(which: Int) {
        val destination = originalFile(which)
        if (destination.exists()) return
        try {
            @Suppress("DEPRECATION")
            val drawable = wallpaperManager.peekDrawable(which)
            val source = (drawable as? BitmapDrawable)?.bitmap ?: return
            FileOutputStream(destination).use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not capture wallpaper before live mode", t)
        }
    }'''
if old in h:
    HELPER.write_text(h.replace(old, new))

# ---------- Lyrics parser: eliminate invalid Kotlin character escapes ----------
l = LIVE.read_text()
bad = '''raw.replace("\\\\r", "").split('\\\\n').forEach'''
if bad in l:
    l = l.replace(bad, "raw.lines().forEach")
# Handle the variant produced by older fixer versions as well.
bad2 = '''raw.replace("\\r", "").split('\\n').forEach'''
if bad2 in l:
    l = l.replace(bad2, "raw.lines().forEach")

# ---------- Spotify-style word highlighting for enhanced LRC ----------
start = l.index('        private fun drawLyrics(canvas: Canvas) {')
end = l.index('        private fun bassPaint', start)
replacement = '''        private fun drawLyrics(canvas: Canvas) {
            val lines = currentLyricLines()
            if (lines.isEmpty()) return
            val x = canvas.width * prefs.lyricsX / 100f
            val y = canvas.height * prefs.lyricsY / 100f
            val maxWidth = canvas.width * prefs.lyricsWidth / 100f
            val baseSize = (canvas.width * prefs.lyricsSize / 1000f).coerceIn(18f, 96f)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.style = Paint.Style.FILL
            textPaint.shader = null
            val lineHeight = baseSize * 1.35f
            val startY = y - ((lines.size - 1) * lineHeight / 2f)
            lines.forEachIndexed { index, item ->
                val active = item.second
                val line = item.first
                val yy = startY + index * lineHeight
                textPaint.textSize = if (active) baseSize * 1.08f else baseSize * 0.92f

                if (active && line.words.isNotEmpty()) {
                    val space = textPaint.measureText(" ")
                    val total = line.words.sumOf { textPaint.measureText(it.text).toDouble() }.toFloat() + space * (line.words.size - 1)
                    var cursor = x - total / 2f
                    line.words.forEach { word ->
                        val width = textPaint.measureText(word.text)
                        val spoken = playbackPosition >= word.start && playbackPosition < word.end
                        textPaint.alpha = if (spoken) 255 else 135
                        textPaint.color = parseColor(if (spoken) prefs.lyricsColor else prefs.lyricsColor2, Color.WHITE)
                        textPaint.shader = null
                        if (spoken || prefs.lyricsShadow) {
                            textPaint.setShadowLayer(if (spoken) 12f else 5f, 0f, 2f, Color.BLACK)
                            canvas.drawText(word.text, cursor + width / 2f, yy, textPaint)
                            textPaint.clearShadowLayer()
                        } else {
                            canvas.drawText(word.text, cursor + width / 2f, yy, textPaint)
                        }
                        cursor += width + space
                    }
                } else {
                    textPaint.alpha = if (active) 255 else 125
                    textPaint.color = parseColor(if (active) prefs.lyricsColor else prefs.lyricsColor2, Color.WHITE)
                    applyTextShader(canvas.width.toFloat(), canvas.height.toFloat())
                    val safe = if (textPaint.measureText(line.text) > maxWidth) ellipsize(line.text, maxWidth) else line.text
                    if (prefs.lyricsShadow || active) {
                        textPaint.setShadowLayer(if (active) 12f else 5f, 0f, 2f, Color.BLACK)
                        canvas.drawText(safe, x, yy, textPaint)
                        textPaint.clearShadowLayer()
                    } else {
                        canvas.drawText(safe, x, yy, textPaint)
                    }
                    textPaint.shader = null
                }
            }
            textPaint.alpha = 255
        }

'''
l = l[:start] + replacement + l[end:]
LIVE.write_text(l)

# ---------- Immediate resync when the user seeks/rewinds/fast-forwards ----------
md = MEDIA.read_text()
needle = '    private var lastPlaybackState = PlaybackState.STATE_NONE\n'
if 'private var lastObservedPosition = -1L' not in md and needle in md:
    md = md.replace(needle, needle + '    private var lastObservedPosition = -1L\n')
old_tick = '''            val position = (basePosition + elapsed).coerceAtLeast(0L)
            // Only send a lightweight checkpoint. The wallpaper interpolates locally.
            sendBroadcast(Intent(ACTION_LIVE_TICK).setPackage(packageName).putExtra(EXTRA_POSITION_MS, position))'''
new_tick = '''            val position = (basePosition + elapsed).coerceAtLeast(0L)
            val jumped = lastObservedPosition >= 0L && kotlin.math.abs(position - lastObservedPosition) > 750L
            lastObservedPosition = position
            if (jumped) prefs.lyricsPosition = position
            sendBroadcast(Intent(ACTION_LIVE_TICK).setPackage(packageName).putExtra(EXTRA_POSITION_MS, position))'''
if old_tick in md:
    md = md.replace(old_tick, new_tick)
MEDIA.write_text(md)

print('Fixed black restore, lyric parsing, seek/scrub resync, and enhanced-LRC word highlighting.')
