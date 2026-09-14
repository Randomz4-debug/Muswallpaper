package com.muswall.app.wallpaper

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
        private val prefs = PreferencesManager.getInstance(applicationContext)
        private var visible = false
        private var receiverRegistered = false
        private var cachedFilePath = ""
        private var cachedModified = Long.MIN_VALUE
        private var cachedBitmap: Bitmap? = null
        private var playbackPosition = 0L
        private val refreshRunnable = Runnable { drawWallpaper() }
        private val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    ACTION_REFRESH -> requestDraw()
                    MediaNotificationListenerService.ACTION_LIVE_TICK -> {
                        playbackPosition = intent.getLongExtra(MediaNotificationListenerService.EXTRA_POSITION_MS, playbackPosition)
                        if (visible && (prefs.showLyrics || prefs.bassEnabled)) requestDraw()
                    }
                }
            }
        }

        override fun onVisibilityChanged(isVisible: Boolean) { visible = isVisible; if (isVisible) requestDraw(true) }
        override fun onSurfaceCreated(holder: SurfaceHolder) { super.onSurfaceCreated(holder); requestDraw(true) }
        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { super.onSurfaceChanged(holder, format, width, height); requestDraw(true) }
        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) { super.onSurfaceRedrawNeeded(holder); requestDraw(true) }
        override fun onSurfaceDestroyed(holder: SurfaceHolder) { visible = false; drawHandler.removeCallbacks(refreshRunnable); super.onSurfaceDestroyed(holder) }

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
        private fun requestDraw(force: Boolean = false) { drawHandler.removeCallbacks(refreshRunnable); drawHandler.post(refreshRunnable) }

        private fun targetFlags(): Int {
            if (Build.VERSION.SDK_INT < 34) return WallpaperManager.FLAG_SYSTEM
            return runCatching { getWallpaperFlags() }.getOrDefault(WallpaperManager.FLAG_SYSTEM)
        }
        private fun sourceFile(): File? {
            if (prefs.liveMusicPlaying || !prefs.restoreOnPause) return File(applicationContext.filesDir, WallpaperHelper.FILE_CURRENT).takeIf { it.exists() }
            val flags = targetFlags(); val target = if ((flags and WallpaperManager.FLAG_LOCK) != 0 && (flags and WallpaperManager.FLAG_SYSTEM) == 0) WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM
            return WallpaperHelper(applicationContext).liveWallpaperOriginal(target)
        }
        private fun loadBitmap(file: File): Bitmap? {
            val modified = file.lastModified()
            if (cachedBitmap != null && cachedFilePath == file.absolutePath && cachedModified == modified) return cachedBitmap
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565; inScaled = false; inDither = true }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return cachedBitmap
            cachedBitmap?.let { if (!it.isRecycled) it.recycle() }; cachedBitmap = decoded; cachedFilePath = file.absolutePath; cachedModified = modified
            return decoded
        }

        private fun parseLrc(raw: String): List<LyricLine> {
            val result = ArrayList<LyricLine>(); val regex = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]\\s*(.*)")
            raw.replace("\\r", "").split('\n').forEach { line ->
                val m = regex.matchEntire(line.trim()) ?: return@forEach
                val minutes = m.groupValues[1].toLongOrNull() ?: return@forEach; val seconds = m.groupValues[2].toLongOrNull() ?: 0L
                val fraction = m.groupValues[3].takeIf { it.isNotBlank() }?.let { when (it.length) { 1 -> it.toLong() * 100L; 2 -> it.toLong() * 10L; else -> it.take(3).toLong() } } ?: 0L
                result += LyricLine(minutes * 60000L + seconds * 1000L + fraction, m.groupValues[4].trim())
            }
            return result.sortedBy { it.time }
        }

        private fun currentLyricLines(): List<String> {
            if (!prefs.showLyrics) return emptyList(); val raw = prefs.lastLyrics.trim(); if (raw.isBlank()) return emptyList()
            val synced = parseLrc(raw)
            if (synced.isNotEmpty()) { val index = synced.indexOfLast { it.time <= playbackPosition }.coerceAtLeast(0); return synced.subList(maxOf(0, index - 1), minOf(synced.size, index + 2)).map { it.text }.filter { it.isNotBlank() } }
            val plain = raw.lines().map { it.trim() }.filter { it.isNotBlank() }; if (plain.isEmpty()) return emptyList()
            val estimatedIndex = ((playbackPosition / 1000L) / maxOf(1L, prefs.lastLyrics.length / 12L)).toInt().coerceIn(0, plain.lastIndex); val from = maxOf(0, estimatedIndex - 1)
            return plain.subList(from, minOf(plain.size, from + 3)).take(3)
        }

        private fun ellipsize(value: String, width: Float): String { var s = value; while (s.length > 4 && textPaint.measureText("$s…") > width) s = s.dropLast(1); return "$s…" }

        private fun drawLyrics(canvas: Canvas) {
            val lines = currentLyricLines(); if (lines.isEmpty()) return
            val x = canvas.width * prefs.lyricsX / 100f; val y = canvas.height * prefs.lyricsY / 100f; val maxWidth = canvas.width * prefs.lyricsWidth / 100f
            textPaint.textSize = (canvas.width * prefs.lyricsSize / 1000f).coerceIn(18f, 96f); textPaint.color = runCatching { Color.parseColor(prefs.lyricsColor) }.getOrDefault(Color.WHITE); textPaint.textAlign = Paint.Align.CENTER
            val lineHeight = textPaint.textSize * 1.28f; val start = y - ((lines.size - 1) * lineHeight / 2f)
            lines.take(prefs.lyricsLines).forEachIndexed { index, value -> val safe = if (textPaint.measureText(value) > maxWidth) ellipsize(value, maxWidth) else value; val yy = start + index * lineHeight; if (prefs.lyricsShadow) { textPaint.setShadowLayer(8f, 2f, 2f, Color.BLACK); canvas.drawText(safe, x, yy, textPaint); textPaint.clearShadowLayer() } else canvas.drawText(safe, x, yy, textPaint) }
        }

        private fun drawBass(canvas: Canvas) {
            if (!prefs.bassEnabled || !prefs.liveMusicPlaying) return
            val cx = canvas.width * prefs.bassX / 100f; val cy = canvas.height * prefs.bassY / 100f; val totalWidth = canvas.width * prefs.bassWidth / 100f; val barHeight = (canvas.height * prefs.bassHeight / 1000f).coerceIn(4f, 32f); val sensitivity = prefs.bassSensitivity / 100f
            val t = playbackPosition.toDouble() / 1000.0; val envelope = (0.45 + 0.35 * sin(t * 8.5) + 0.20 * sin(t * 17.0 + 0.8)).coerceIn(0.08, 1.0); val level = (0.15 + envelope * (0.55 + sensitivity * 0.65)).coerceIn(0.08, 1.0)
            val bassColorValue = runCatching { Color.parseColor(prefs.bassColor) }.getOrDefault(Color.WHITE); val bars = 28; val gap = maxOf(2f, totalWidth / 180f); val bw = (totalWidth - gap * (bars - 1)) / bars
            for (i in 0 until bars) { val wave = abs(sin(i * 0.72 + t * 5.0)).toFloat(); val h = barHeight * (0.25f + level.toFloat() * (0.55f + wave * 0.45f)); val left = cx - totalWidth / 2f + i * (bw + gap); val bassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bassColorValue; alpha = 180 }; canvas.drawRoundRect(RectF(left, cy - h, left + bw, cy + h), bw / 2f, bw / 2f, bassPaint) }
        }

        private fun drawWallpaper() {
            val holder = surfaceHolder; var canvas: Canvas? = null
            try {
                if (!holder.surface.isValid) return; canvas = holder.lockCanvas() ?: return; canvas.drawColor(Color.BLACK)
                val file = sourceFile() ?: return; val bitmap = loadBitmap(file) ?: return; if (bitmap.isRecycled) return
                val scale = maxOf(canvas.width.toFloat() / bitmap.width, canvas.height.toFloat() / bitmap.height); val w = bitmap.width * scale; val h = bitmap.height * scale; val left = (canvas.width - w) / 2f; val top = (canvas.height - h) / 2f
                canvas.drawBitmap(bitmap, null, RectF(left, top, left + w, top + h), paint); drawLyrics(canvas); drawBass(canvas)
            } catch (t: Throwable) { android.util.Log.w("MusWallLive", "Live wallpaper draw failed", t) }
            finally { if (canvas != null) try { holder.unlockCanvasAndPost(canvas) } catch (_: Throwable) {} }
        }

        override fun onDestroy() {
            visible = false; drawHandler.removeCallbacksAndMessages(null); if (receiverRegistered) try { applicationContext.unregisterReceiver(receiver) } catch (_: Throwable) {}; receiverRegistered = false
            cachedBitmap?.let { if (!it.isRecycled) it.recycle() }; cachedBitmap = null; drawThread.quitSafely(); mainHandler.removeCallbacksAndMessages(null); super.onDestroy()
        }
    }
}
