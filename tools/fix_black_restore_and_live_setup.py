from pathlib import Path

MAIN = Path('app/src/main/java/com/muswall/app/ui/MainActivity.kt')
HELPER = Path('app/src/main/java/com/muswall/app/wallpaper/WallpaperHelper.kt')
LIVE = Path('app/src/main/java/com/muswall/app/wallpaper/MusicWallpaperService.kt')
MEDIA = Path('app/src/main/java/com/muswall/app/service/MediaNotificationListenerService.kt')

m = MAIN.read_text()
old = 'findViewById<MaterialButton>(R.id.btnLiveWallpaper).setOnClickListener { prefs.liveWallpaperEnabled = true; wallpaperHelper.openLiveWallpaperPicker() }'
new = '''findViewById<MaterialButton>(R.id.btnLiveWallpaper).setOnClickListener {
            // Capture the user's real wallpaper BEFORE HyperOS switches to MusWall.
            // This is essential on Android 14+/HyperOS because the old lock wallpaper
            // may no longer be readable after a live wallpaper becomes active.
            uiScope.launch(Dispatchers.IO) {
                wallpaperHelper.prepareOriginalBeforeLiveWallpaper()
                withContext(Dispatchers.Main) {
                    prefs.liveWallpaperEnabled = true
                    wallpaperHelper.openLiveWallpaperPicker()
                }
            }
        }'''
if old in m:
    m = m.replace(old, new)
MAIN.write_text(m)

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
            // Prefer an image explicitly selected inside MusWall. This is the most reliable
            // restore source on Android 14+/HyperOS after live-wallpaper switching.
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
    h = h.replace(old, new)
HELPER.write_text(h)

# Fix the Kotlin newline parsing emitted by the earlier lyrics patch. Using lines()
# also handles CRLF safely and avoids Kotlin character-literal escape errors.
l = LIVE.read_text()
l = l.replace("raw.replace(\\"\\\\r\\", \\\"\\\").split('\\\\n').forEach", "raw.lines().forEach")
l = l.replace("raw.replace(\\"\\\\r\\", \\\"\\\").split('\\\\n').forEach", "raw.lines().forEach")

# Make the lyric renderer actually use enhanced-LRC word timestamps. Standard LRC
# remains line-synchronised; enhanced LRC gets a moving word highlight.
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
                textPaint.textSize = if (active) baseSize * 1.08f else baseSize * 0.92f
                textPaint.alpha = if (active) 255 else 125
                val yy = startY + index * lineHeight
                val line = item.first

                if (active && line.words.isNotEmpty()) {
                    // Draw enhanced-LRC words separately so the currently spoken word
                    // can be highlighted without changing the timing of the other words.
                    val total = line.words.sumOf { textPaint.measureText(it.text) } + (line.words.size - 1) * textPaint.measureText(" ")
                    val startX = x - total / 2f
                    var cursor = startX
                    line.words.forEach { word ->
                        val wordWidth = textPaint.measureText(word.text)
                        val center = cursor + wordWidth / 2f
                        val spoken = playbackPosition >= word.start && playbackPosition < word.end
                        textPaint.alpha = if (spoken) 255 else 135
                        textPaint.color = parseColor(if (spoken) prefs.lyricsColor else prefs.lyricsColor2, Color.WHITE)
                        applyTextShader(canvas.width.toFloat(), canvas.height.toFloat())
                        if (spoken || prefs.lyricsShadow) {
                            textPaint.setShadowLayer(if (spoken) 12f else 5f, 0f, 2f, Color.BLACK)
                            canvas.drawText(word.text, center, yy, textPaint)
                            textPaint.clearShadowLayer()
                        } else {
                            canvas.drawText(word.text, center, yy, textPaint)
                        }
                        textPaint.shader = null
                        cursor += wordWidth + textPaint.measureText(" ")
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

# Detect seeks/scrubs even when the playback state remains PLAYING. The lyric clock
# then jumps immediately instead of waiting for the next metadata event.
md = MEDIA.read_text()
needle = '    private var lastPlaybackState = PlaybackState.STATE_NONE\n'
insert = '    private var lastPlaybackState = PlaybackState.STATE_NONE\n    private var lastObservedPosition = -1L\n'
if needle in md and 'lastObservedPosition' not in md:
    md = md.replace(needle, insert)
old_tick = '''            val position = (basePosition + elapsed).coerceAtLeast(0L)
            // Only send a lightweight checkpoint. The wallpaper interpolates locally.
            sendBroadcast(Intent(ACTION_LIVE_TICK).setPackage(packageName).putExtra(EXTRA_POSITION_MS, position))'''
new_tick = '''            val position = (basePosition + elapsed).coerceAtLeast(0L)
            val jumped = lastObservedPosition >= 0L && kotlin.math.abs(position - lastObservedPosition) > 750L
            lastObservedPosition = position
            if (jumped) prefs.lyricsPosition = position
            // Lightweight checkpoints keep the wallpaper smooth while large jumps from
            // seeking/rewinding/fast-forwarding immediately resynchronise the lyrics.
            sendBroadcast(Intent(ACTION_LIVE_TICK).setPackage(packageName).putExtra(EXTRA_POSITION_MS, position))'''
if old_tick in md:
    md = md.replace(old_tick, new_tick)
MEDIA.write_text(md)

print('Fixed black restore, lyric parsing, seek/scrub resync, and enhanced-LRC word highlighting.')
