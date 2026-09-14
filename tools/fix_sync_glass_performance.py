from pathlib import Path

MEDIA = Path('app/src/main/java/com/muswall/app/service/MediaNotificationListenerService.kt')
LIVE = Path('app/src/main/java/com/muswall/app/wallpaper/MusicWallpaperService.kt')
WIDGET = Path('app/src/main/java/com/muswall/app/widget/MusicWidgetProvider.kt')
LAYOUT = Path('app/src/main/res/layout/widget_music.xml')
PREFS = Path('app/src/main/java/com/muswall/app/data/PreferencesManager.kt')

# 1) Use PlaybackState's lastPositionUpdateTime to extrapolate playback between callbacks.
s = MEDIA.read_text()
old = '''            val position = state?.position?.coerceAtLeast(0L) ?: prefs.lyricsPosition\n            prefs.lyricsPosition = position\n            sendBroadcast(Intent(ACTION_LIVE_TICK).setPackage(packageName).putExtra(EXTRA_POSITION_MS, position))\n            timelineHandler.postDelayed(this, 150L)'''
new = '''            val basePosition = state?.position?.coerceAtLeast(0L) ?: prefs.lyricsPosition\n            val updateTime = state?.lastPositionUpdateTime ?: 0L\n            val elapsed = if (updateTime > 0L) (android.os.SystemClock.elapsedRealtime() - updateTime).coerceAtLeast(0L) else 0L\n            val position = (basePosition + elapsed).coerceAtLeast(0L)\n            prefs.lyricsPosition = position\n            sendBroadcast(Intent(ACTION_LIVE_TICK).setPackage(packageName).putExtra(EXTRA_POSITION_MS, position))\n            timelineHandler.postDelayed(this, 60L)'''
if old not in s:
    raise SystemExit('timeline block not found')
s = s.replace(old, new)
# Never let unsynchronised direct lyrics win over a synced provider.
old = '''    private fun findImmediateLyrics(metadata:MediaMetadata):String?{\n        val direct=metadata.getString("android.media.metadata.LYRICS")?.trim().orEmpty()\n        if(hasLrcTimestamps(direct))return direct\n        for(key in metadata.keySet()){\n            val value=metadata.getString(key)?.trim().orEmpty()\n            if(key.contains("lyric",true)&&hasLrcTimestamps(value))return value\n        }\n        return null\n    }'''
new = '''    private fun findImmediateLyrics(metadata:MediaMetadata):String?{\n        val direct=metadata.getString("android.media.metadata.LYRICS")?.trim().orEmpty()\n        if(hasLrcTimestamps(direct))return direct\n        for(key in metadata.keySet()){\n            val value=metadata.getString(key)?.trim().orEmpty()\n            if(key.contains("lyric",true)&&hasLrcTimestamps(value))return value\n        }\n        return null\n    }'''
if old not in s:
    raise SystemExit('lyrics immediate block not found')
MEDIA.write_text(s)

# 2) Replace the live engine's lyric selection/drawing with a cached, timestamp-aware renderer.
l = LIVE.read_text()
start = l.index('        private fun currentLyricLines(): List<String> {')
end = l.index('        private fun ellipsize', start)
replacement = '''        private data class VisibleLyric(val line: LyricLine, val active: Boolean)\n\n        private fun currentLyricLines(): List<VisibleLyric> {\n            if (!prefs.showLyrics) return emptyList()\n            val raw = prefs.lastLyrics.trim()\n            if (raw.isBlank()) return emptyList()\n            if (raw != cachedLyricsRaw) {\n                cachedLyricsRaw = raw\n                cachedLyricLines = parseLrc(raw)\n            }\n            val synced = cachedLyricLines\n            if (synced.isNotEmpty()) {\n                val activeIndex = synced.indexOfLast { it.time <= playbackPosition }.let { if (it < 0) 0 else it }\n                val half = maxOf(0, prefs.lyricsLines / 2)\n                val from = (activeIndex - half).coerceAtLeast(0)\n                val to = minOf(synced.size, from + prefs.lyricsLines)\n                return synced.subList(from, to).map { VisibleLyric(it, it === synced[activeIndex]) }\n            }\n            val plain = raw.lines().map { it.trim() }.filter { it.isNotBlank() }\n            if (plain.isEmpty()) return emptyList()\n            val step = maxOf(1200L, (prefs.lastDuration / maxOf(1, plain.size)).coerceAtLeast(1200L))\n            val activeIndex = (playbackPosition / step).toInt().coerceIn(0, plain.lastIndex)\n            val half = maxOf(0, prefs.lyricsLines / 2)\n            val from = (activeIndex - half).coerceAtLeast(0)\n            val to = minOf(plain.size, from + prefs.lyricsLines)\n            return plain.subList(from, to).mapIndexed { i, text ->\n                VisibleLyric(LyricLine((from + i) * step, text), from + i == activeIndex)\n            }\n        }\n\n'''
l = l[:start] + replacement + l[end:]
start = l.index('        private fun drawLyrics(canvas: Canvas) {')
end = l.index('        private fun bassPaint', start)
replacement = '''        private fun drawLyrics(canvas: Canvas) {\n            val lines = currentLyricLines()\n            if (lines.isEmpty()) return\n            val x = canvas.width * prefs.lyricsX / 100f\n            val y = canvas.height * prefs.lyricsY / 100f\n            val maxWidth = canvas.width * prefs.lyricsWidth / 100f\n            val baseSize = (canvas.width * prefs.lyricsSize / 1000f).coerceIn(18f, 96f)\n            textPaint.textAlign = Paint.Align.CENTER\n            textPaint.style = Paint.Style.FILL\n            textPaint.shader = null\n            val lineHeight = baseSize * 1.35f\n            val startY = y - ((lines.size - 1) * lineHeight / 2f)\n            lines.forEachIndexed { index, item ->\n                val active = item.active\n                textPaint.textSize = if (active) baseSize * 1.08f else baseSize * 0.92f\n                textPaint.alpha = if (active) 255 else 125\n                textPaint.color = parseColor(if (active) prefs.lyricsColor else prefs.lyricsColor2, Color.WHITE)\n                applyTextShader(canvas.width.toFloat(), canvas.height.toFloat())\n                val safe = if (textPaint.measureText(item.line.text) > maxWidth) ellipsize(item.line.text, maxWidth) else item.line.text\n                val yy = startY + index * lineHeight\n                if (prefs.lyricsShadow || active) {\n                    textPaint.setShadowLayer(if (active) 12f else 5f, 0f, 2f, Color.BLACK)\n                    canvas.drawText(safe, x, yy, textPaint)\n                    textPaint.clearShadowLayer()\n                } else canvas.drawText(safe, x, yy, textPaint)\n                textPaint.shader = null\n            }\n            textPaint.alpha = 255\n        }\n\n'''
l = l[:start] + replacement + l[end:]
# Smooth local frame scheduling; ticks still correct the clock, while drawing does not wait for 150ms broadcasts.
l = l.replace('''                    if (visible && (prefs.showLyrics || prefs.bassEnabled)) requestDraw()''','''                    if (visible && (prefs.showLyrics || prefs.bassEnabled)) requestDraw()''')
l = l.replace('''        private fun requestDraw(force: Boolean = false) { drawHandler.removeCallbacks(refreshRunnable); drawHandler.post(refreshRunnable) }''','''        private fun requestDraw(force: Boolean = false) {\n            drawHandler.removeCallbacks(refreshRunnable)\n            drawHandler.post(refreshRunnable)\n            if (visible && (prefs.bassEnabled || prefs.showLyrics)) {\n                drawHandler.postDelayed(refreshRunnable, if (prefs.bassEnabled) 50L else 80L)\n            }\n        }''')
# Do not draw the saved music frame after playback has stopped; the media service restores the original lock wallpaper.
l = l.replace('''            // Keep the last generated music frame visible even when playback is paused, the media service is killed,\n            // or the phone is restarted. This is the important difference from the old restore-on-pause behavior.\n            val current = File(applicationContext.filesDir, WallpaperHelper.FILE_CURRENT)\n            if (current.exists()) return current''','''            if (!prefs.liveMusicPlaying) return null\n            val current = File(applicationContext.filesDir, WallpaperHelper.FILE_CURRENT)\n            if (current.exists()) return current''')
LIVE.write_text(l)

# 3) Make the widget a white glass surface. Keep all last-track metadata independent of wallpaper state.
w = WIDGET.read_text()
w = w.replace('''val bg = parseColor(prefs.widgetBackgroundColor, Color.rgb(23, 21, 31))\n        val bar = parseColor(prefs.widgetBarColor, Color.WHITE)\n        val textColor = parseColor(prefs.widgetTextColor, Color.WHITE)\n        val secondary = parseColor(prefs.widgetSecondaryColor, Color.LTGRAY)\n        val opacity = (255f * prefs.widgetOpacity.coerceIn(0, 100) / 100f).toInt()\n        val barOpacity = (255f * prefs.widgetBarOpacity.coerceIn(0, 100) / 100f).toInt()''','''val bg = Color.WHITE\n        val bar = Color.WHITE\n        val textColor = Color.BLACK\n        val secondary = Color.rgb(75, 75, 82)\n        val opacity = 112\n        val barOpacity = 42''')
w = w.replace('''if (prefs.widgetBarEnabled) View.VISIBLE else View.GONE''','''View.VISIBLE''')
WIDGET.write_text(w)

# 4) Make the XML itself transparent; the provider supplies the translucent glass at runtime.
xml = LAYOUT.read_text()
xml = xml.replace('android:background="#EB17151F"', 'android:background="@android:color/transparent"')
xml = xml.replace('android:background="#2EFFFFFF"', 'android:background="@android:color/transparent"')
LAYOUT.write_text(xml)

# 5) Faster/smaller render defaults reduce generation cost on mid-range phones.
p = PREFS.read_text()
p = p.replace('var blurRadius:Int get()=sp.getInt("blur_radius",24)', 'var blurRadius:Int get()=sp.getInt("blur_radius",18)')
p = p.replace('var widgetOpacity:Int get()=sp.getInt("widget_opacity",92)', 'var widgetOpacity:Int get()=sp.getInt("widget_opacity",44)')
p = p.replace('var widgetTextColor:String get()=sp.getString("widget_text_color","#FFFFFF")?:"#FFFFFF"', 'var widgetTextColor:String get()=sp.getString("widget_text_color","#000000")?:"#000000"')
p = p.replace('var widgetSecondaryColor:String get()=sp.getString("widget_secondary_color","#C7C7D0")?:"#C7C7D0"', 'var widgetSecondaryColor:String get()=sp.getString("widget_secondary_color","#4B4B52")?:"#4B4B52"')
p = p.replace('var widgetBackgroundColor:String get()=sp.getString("widget_background_color","#17151F")?:"#17151F"', 'var widgetBackgroundColor:String get()=sp.getString("widget_background_color","#FFFFFF")?:"#FFFFFF"')
PREFS.write_text(p)

print('Applied lyric sync/highlight, timing, live draw scheduling, and white glass widget fixes.')
