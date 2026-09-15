package com.muswall.app.wallpaper

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.muswall.app.data.PreferencesManager
import com.muswall.app.service.MediaNotificationListenerService
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

class MusicWallpaperService : WallpaperService() {
    companion object { const val ACTION_REFRESH = "com.muswall.app.REFRESH_LIVE_WALLPAPER" }
    private data class LyricLine(val time: Long, val text: String)
    override fun onCreateEngine(): Engine = MusicEngine()

    inner class MusicEngine : Engine() {
        private val drawThread = HandlerThread("MusWall-LiveDraw").apply { start() }
        private val drawHandler = Handler(drawThread.looper)
        private val mainHandler = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply { isFilterBitmap = true }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL) }
        private val bassPaintCache = Paint(Paint.ANTI_ALIAS_FLAG)
        private var cachedLyricsRaw = ""
        private var cachedLyricLines: List<LyricLine> = emptyList()
        private val prefs = PreferencesManager.getInstance(applicationContext)
        private var visible = false
        private var receiverRegistered = false
        private var cachedFilePath = ""
        private var cachedModified = Long.MIN_VALUE
        private var cachedBitmap: Bitmap? = null
        private var playbackPosition = prefs.lyricsPosition
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
        }
        private val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    ACTION_REFRESH -> requestDraw()
                    MediaNotificationListenerService.ACTION_LIVE_TICK -> {
                        playbackCheckpointPosition = intent.getLongExtra(MediaNotificationListenerService.EXTRA_POSITION_MS, playbackPosition).coerceAtLeast(0L)
                        playbackPosition = playbackCheckpointPosition
                        playbackCheckpointElapsed = android.os.SystemClock.elapsedRealtime()
                        requestDraw()
                    }
                }
            }
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) requestDraw(true) else drawHandler.removeCallbacks(animationRunnable)
        }
        override fun onSurfaceCreated(holder: SurfaceHolder) { super.onSurfaceCreated(holder); requestDraw(true) }
        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { super.onSurfaceChanged(holder, format, width, height); requestDraw(true) }
        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) { super.onSurfaceRedrawNeeded(holder); requestDraw(true) }
        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            drawHandler.removeCallbacks(refreshRunnable)
            drawHandler.removeCallbacks(animationRunnable)
            super.onSurfaceDestroyed(holder)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            try {
                val filter = android.content.IntentFilter().apply { addAction(ACTION_REFRESH); addAction(MediaNotificationListenerService.ACTION_LIVE_TICK) }
                if (Build.VERSION.SDK_INT >= 33) applicationContext.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
                else @Suppress("DEPRECATION") applicationContext.registerReceiver(receiver, filter)
                receiverRegistered = true
            } catch (t: Throwable) { android.util.Log.e("MusWallLive", "Receiver registration failed", t) }
            requestDraw(true)
        }

        override fun onWallpaperFlagsChanged(which: Int) { super.onWallpaperFlagsChanged(which); requestDraw(true) }
        private fun requestDraw(force: Boolean = false) {
            drawHandler.removeCallbacks(refreshRunnable)
            drawHandler.post(refreshRunnable)
            drawHandler.removeCallbacks(animationRunnable)
            if (visible && prefs.liveMusicPlaying && (prefs.bassEnabled || prefs.showLyrics)) {
                drawHandler.post(animationRunnable)
            }
        }

        private fun sourceFile(): File? {
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
        }

        private fun loadBitmap(file: File): Bitmap? {
            val modified = file.lastModified()
            if (cachedBitmap != null && cachedFilePath == file.absolutePath && cachedModified == modified) return cachedBitmap
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565; inScaled = false; inDither = true }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return cachedBitmap
            cachedBitmap?.let { if (!it.isRecycled) it.recycle() }
            cachedBitmap = decoded; cachedFilePath = file.absolutePath; cachedModified = modified
            return decoded
        }

        private fun parseLrc(raw: String): List<LyricLine> {
            val result = ArrayList<LyricLine>()
            val regex = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]\\s*(.*)")
            raw.replace("\\r", "").split('\n').forEach { line ->
                val m = regex.matchEntire(line.trim()) ?: return@forEach
                val minutes = m.groupValues[1].toLongOrNull() ?: return@forEach
                val seconds = m.groupValues[2].toLongOrNull() ?: 0L
                val fraction = m.groupValues[3].takeIf { it.isNotBlank() }?.let { when (it.length) { 1 -> it.toLong() * 100L; 2 -> it.toLong() * 10L; else -> it.take(3).toLong() } } ?: 0L
                result += LyricLine(minutes * 60000L + seconds * 1000L + fraction, m.groupValues[4].trim())
            }
            return result.sortedBy { it.time }
        }

        private fun currentLyricLines(): List<Pair<LyricLine, Boolean>> {
            if (!prefs.showLyrics) return emptyList()
            val raw = prefs.lastLyrics.trim()
            if (raw.isBlank()) return emptyList()
            if (raw != cachedLyricsRaw) {
                cachedLyricsRaw = raw
                cachedLyricLines = parseLrc(raw)
            }
            val synced = cachedLyricLines
            if (synced.isNotEmpty()) {
                val activeIndex = synced.indexOfLast { it.time <= playbackPosition }.let { if (it < 0) 0 else it }
                val half = maxOf(0, prefs.lyricsLines / 2)
                val from = (activeIndex - half).coerceAtLeast(0)
                val to = minOf(synced.size, from + prefs.lyricsLines)
                return synced.subList(from, to).mapIndexed { i, line -> Pair(line, from + i == activeIndex) }
            }
            val plain = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (plain.isEmpty()) return emptyList()
            val step = maxOf(1200L, (prefs.lastDuration / maxOf(1, plain.size)).coerceAtLeast(1200L))
            val activeIndex = (playbackPosition / step).toInt().coerceIn(0, plain.lastIndex)
            val half = maxOf(0, prefs.lyricsLines / 2)
            val from = (activeIndex - half).coerceAtLeast(0)
            val to = minOf(plain.size, from + prefs.lyricsLines)
            return plain.subList(from, to).mapIndexed { i, text -> Pair(LyricLine((from + i) * step, text), from + i == activeIndex) }
        }

        private fun ellipsize(value: String, width: Float): String {
            var s = value
            while (s.length > 4 && textPaint.measureText("$s…") > width) s = s.dropLast(1)
            return "$s…"
        }

        private fun parseColor(value: String, fallback: Int): Int = runCatching { Color.parseColor(value) }.getOrDefault(fallback)

        private fun applyTextShader(canvasWidth: Float, canvasHeight: Float) {
            val c1 = parseColor(prefs.lyricsColor, Color.WHITE)
            val c2 = parseColor(prefs.lyricsColor2, Color.CYAN)
            val c3 = parseColor(prefs.lyricsColor3, Color.MAGENTA)
            textPaint.shader = when (prefs.lyricsColorMode) {
                PreferencesManager.COLOR_GRADIENT -> LinearGradient(0f, 0f, canvasWidth, 0f, c1, c2, Shader.TileMode.CLAMP)
                PreferencesManager.COLOR_DOUBLE -> LinearGradient(0f, 0f, canvasWidth, 0f, c1, c2, Shader.TileMode.CLAMP)
                PreferencesManager.COLOR_RAINBOW -> LinearGradient(0f, 0f, canvasWidth, 0f, intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA), null, Shader.TileMode.MIRROR)
                else -> null
            }
            if (prefs.lyricsColorMode == PreferencesManager.COLOR_RAINBOW) textPaint.shader = LinearGradient(0f, 0f, canvasWidth, canvasHeight, intArrayOf(c1, c2, c3, Color.MAGENTA), null, Shader.TileMode.MIRROR)
        }

        private fun drawLyrics(canvas: Canvas) {
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
                textPaint.color = parseColor(if (active) prefs.lyricsColor else prefs.lyricsColor2, Color.WHITE)
                applyTextShader(canvas.width.toFloat(), canvas.height.toFloat())
                val safe = if (textPaint.measureText(item.first.text) > maxWidth) ellipsize(item.first.text, maxWidth) else item.first.text
                val yy = startY + index * lineHeight
                if (prefs.lyricsShadow || active) {
                    textPaint.setShadowLayer(if (active) 12f else 5f, 0f, 2f, Color.BLACK)
                    canvas.drawText(safe, x, yy, textPaint)
                    textPaint.clearShadowLayer()
                } else canvas.drawText(safe, x, yy, textPaint)
                textPaint.shader = null
            }
            textPaint.alpha = 255
        }

        private fun bassPaint(canvas: Canvas): Paint {
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

        private fun drawBass(canvas: Canvas) {
            if (!prefs.bassEnabled || !prefs.liveMusicPlaying) return
            val cx = canvas.width * prefs.bassX / 100f
            val cy = canvas.height * prefs.bassY / 100f
            val totalWidth = canvas.width * prefs.bassWidth / 100f
            val barHeight = (canvas.height * prefs.bassHeight / 1000f).coerceIn(4f, 32f)
            val sensitivity = prefs.bassSensitivity / 100f
            val t = playbackPosition.toDouble() / 1000.0
            val envelope = (0.45 + 0.35 * sin(t * 8.5) + 0.20 * sin(t * 17.0 + 0.8)).coerceIn(0.08, 1.0)
            val level = (0.15 + envelope * (0.55 + sensitivity * 0.65)).coerceIn(0.08, 1.0)
            val bars = 28
            val gap = maxOf(2f, totalWidth / 180f)
            val bw = (totalWidth - gap * (bars - 1)) / bars
            val bassPaint = bassPaint(canvas)
            for (i in 0 until bars) {
                val wave = abs(sin(i * 0.72 + t * 5.0)).toFloat()
                val h = barHeight * (0.25f + level.toFloat() * (0.55f + wave * 0.45f))
                val left = cx - totalWidth / 2f + i * (bw + gap)
                canvas.drawRoundRect(RectF(left, cy - h, left + bw, cy + h), bw / 2f, bw / 2f, bassPaint)
            }
        }

        private fun drawWallpaper() {
            if (prefs.liveMusicPlaying) {
                val elapsed = (android.os.SystemClock.elapsedRealtime() - playbackCheckpointElapsed).coerceAtLeast(0L)
                playbackPosition = playbackCheckpointPosition + elapsed
            } else {
                playbackPosition = prefs.lyricsPosition.coerceAtLeast(0L)
            }
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                if (!holder.surface.isValid) return
                canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try { holder.lockHardwareCanvas() } catch (_: Throwable) { holder.lockCanvas() }
                } else holder.lockCanvas()
                if (canvas == null) return
                canvas.drawColor(Color.BLACK)
                val file = sourceFile() ?: return
                val bitmap = loadBitmap(file) ?: return
                if (bitmap.isRecycled) return
                val scale = maxOf(canvas.width.toFloat() / bitmap.width, canvas.height.toFloat() / bitmap.height)
                val w = bitmap.width * scale; val h = bitmap.height * scale
                val left = (canvas.width - w) / 2f; val top = (canvas.height - h) / 2f
                canvas.drawBitmap(bitmap, null, RectF(left, top, left + w, top + h), paint)
                drawLyrics(canvas)
                drawBass(canvas)
            } catch (t: Throwable) { android.util.Log.w("MusWallLive", "Live wallpaper draw failed", t) }
            finally { if (canvas != null) try { holder.unlockCanvasAndPost(canvas) } catch (_: Throwable) {} }
        }

        override fun onDestroy() {
            visible = false
            drawHandler.removeCallbacksAndMessages(null)
            drawHandler.removeCallbacks(animationRunnable)
            if (receiverRegistered) try { applicationContext.unregisterReceiver(receiver) } catch (_: Throwable) {}
            receiverRegistered = false
            cachedBitmap?.let { if (!it.isRecycled) it.recycle() }
            cachedBitmap = null
            cachedLyricsRaw = ""
            cachedLyricLines = emptyList()
            drawThread.quitSafely(); mainHandler.removeCallbacksAndMessages(null)
            super.onDestroy()
        }
    }
}
