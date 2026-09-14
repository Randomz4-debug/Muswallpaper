from pathlib import Path

wp = Path('app/src/main/java/com/muswall/app/wallpaper/MusicWallpaperService.kt')
s = wp.read_text()

marker = '        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL) }\n'
insert = marker + '        private val bassPaintCache = Paint(Paint.ANTI_ALIAS_FLAG)\n        private var cachedLyricsRaw = ""\n        private var cachedLyricLines: List<LyricLine> = emptyList()\n'
if 'private val bassPaintCache' not in s:
    if marker not in s:
        raise SystemExit('text paint marker not found')
    s = s.replace(marker, insert, 1)

start = s.find('        private fun currentLyricLines(): List<String> {')
end = s.find('\n        private fun ellipsize', start)
if start < 0 or end < 0:
    raise SystemExit('lyric function boundaries not found')
s = s[:start] + '''        private fun currentLyricLines(): List<String> {
            if (!prefs.showLyrics) return emptyList()
            val raw = prefs.lastLyrics.trim()
            if (raw.isBlank()) return emptyList()
            if (raw != cachedLyricsRaw) {
                cachedLyricsRaw = raw
                cachedLyricLines = parseLrc(raw)
            }
            val synced = cachedLyricLines
            if (synced.isNotEmpty()) {
                val index = synced.indexOfLast { it.time <= playbackPosition }.coerceAtLeast(0)
                val from = maxOf(0, index - 1)
                return synced.subList(from, minOf(synced.size, from + prefs.lyricsLines)).map { it.text }.filter { it.isNotBlank() }
            }
            val plain = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (plain.isEmpty()) return emptyList()
            val estimatedIndex = ((playbackPosition / 1000L) / maxOf(1L, raw.length / 12L)).toInt().coerceIn(0, plain.lastIndex)
            val from = maxOf(0, estimatedIndex - 1)
            return plain.subList(from, minOf(plain.size, from + prefs.lyricsLines))
        }
''' + s[end:]

start = s.find('        private fun bassPaint(canvas: Canvas): Paint {')
end = s.find('\n        private fun drawBass', start)
if start < 0 or end < 0:
    raise SystemExit('bass function boundaries not found')
s = s[:start] + '''        private fun bassPaint(canvas: Canvas): Paint {
            val c1 = parseColor(prefs.bassColor, Color.WHITE)
            val c2 = parseColor(prefs.bassColor2, Color.CYAN)
            val c3 = parseColor(prefs.bassColor3, Color.MAGENTA)
            val p = bassPaintCache
            p.reset()
            p.isAntiAlias = true
            p.alpha = 205
            p.color = c1
            p.shader = when (prefs.bassColorMode) {
                PreferencesManager.COLOR_GRADIENT, PreferencesManager.COLOR_DOUBLE -> LinearGradient(0f, 0f, canvas.width.toFloat(), 0f, c1, c2, Shader.TileMode.CLAMP)
                PreferencesManager.COLOR_RAINBOW -> LinearGradient(0f, 0f, canvas.width.toFloat(), 0f, intArrayOf(c1, c2, c3, Color.MAGENTA), null, Shader.TileMode.MIRROR)
                else -> null
            }
            return p
        }
''' + s[end:]

s = s.replace('                canvas = holder.lockCanvas() ?: return\n', '''                canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try { holder.lockHardwareCanvas() } catch (_: Throwable) { holder.lockCanvas() }
                } else holder.lockCanvas()
                if (canvas == null) return
''', 1)

s = s.replace('            cachedBitmap = null\n            drawThread.quitSafely()', '            cachedBitmap = null\n            cachedLyricsRaw = ""\n            cachedLyricLines = emptyList()\n            drawThread.quitSafely()', 1)
wp.write_text(s)

media = Path('app/src/main/java/com/muswall/app/service/MediaNotificationListenerService.kt')
m = media.read_text()
m = m.replace('    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)\n', '    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)\n', 1)
m = m.replace('            timelineHandler.postDelayed(this, 200L)\n', '            timelineHandler.postDelayed(this, 250L)\n', 1)
old = '''            prefs.liveMusicPlaying = false
            sendBroadcast(
                Intent(ACTION_LIVE_TICK)
                    .setPackage(packageName)
                    .putExtra(EXTRA_POSITION_MS, prefs.lyricsPosition)
            )
            refreshLive()
            broadcastWallpaperApplied("Music paused • wallpaper state updated")
'''
new = '''            prefs.liveMusicPlaying = false
            try { wallpaperHelper.restoreOriginalLock() } catch (t: Throwable) {
                Log.w("MusWallMedia", "Failed to restore original lock wallpaper", t)
            }
            sendBroadcast(
                Intent(ACTION_LIVE_TICK)
                    .setPackage(packageName)
                    .putExtra(EXTRA_POSITION_MS, prefs.lyricsPosition)
            )
            refreshLive()
            broadcastWallpaperApplied("Music paused • original wallpaper restored")
'''
if old in m:
    m = m.replace(old, new, 1)
media.write_text(m)
print('live performance fixes applied')
