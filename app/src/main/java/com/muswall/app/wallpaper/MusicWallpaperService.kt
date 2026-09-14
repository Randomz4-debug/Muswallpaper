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
import java.io.File

/** Event-driven live wallpaper. Music changes replace only the live wallpaper frame. */
class MusicWallpaperService : WallpaperService() {
    companion object {
        const val ACTION_REFRESH = "com.muswall.app.REFRESH_LIVE_WALLPAPER"
    }

    override fun onCreateEngine(): Engine = MusicEngine()

    inner class MusicEngine : Engine() {
        private val drawThread = HandlerThread("MusWall-LiveDraw").apply { start() }
        private val drawHandler = Handler(drawThread.looper)
        private val mainHandler = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            isFilterBitmap = true
        }
        private val prefs = PreferencesManager.getInstance(applicationContext)
        private var visible = false
        private var receiverRegistered = false
        private var cachedFilePath = ""
        private var cachedModified = Long.MIN_VALUE
        private var cachedBitmap: Bitmap? = null

        private val refreshRunnable = Runnable { drawWallpaper() }
        private val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == ACTION_REFRESH) requestDraw()
            }
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) requestDraw()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            // Some Xiaomi/POCO builds create the lock-screen surface before the
            // visibility callback. Draw immediately so the first lock-screen frame
            // is not missed.
            requestDraw(force = true)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            requestDraw(force = true)
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            requestDraw(force = true)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            drawHandler.removeCallbacks(refreshRunnable)
            super.onSurfaceDestroyed(holder)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            try {
                val filter = android.content.IntentFilter(ACTION_REFRESH)
                if (Build.VERSION.SDK_INT >= 33) {
                    applicationContext.registerReceiver(
                        receiver,
                        filter,
                        android.content.Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    @Suppress("DEPRECATION")
                    applicationContext.registerReceiver(receiver, filter)
                }
                receiverRegistered = true
            } catch (t: Throwable) {
                android.util.Log.e("MusWallLive", "Receiver registration failed", t)
            }
            requestDraw(force = true)
        }

        override fun onWallpaperFlagsChanged(which: Int) {
            super.onWallpaperFlagsChanged(which)
            android.util.Log.d("MusWallLive", "Wallpaper flags changed: $which")
            requestDraw(force = true)
        }

        private fun requestDraw(force: Boolean = false) {
            drawHandler.removeCallbacks(refreshRunnable)
            if (force) {
                drawHandler.post(refreshRunnable)
            } else {
                drawHandler.post(refreshRunnable)
            }
        }

        private fun targetFlags(): Int {
            if (Build.VERSION.SDK_INT < 34) return WallpaperManager.FLAG_SYSTEM
            return runCatching { getWallpaperFlags() }
                .getOrDefault(WallpaperManager.FLAG_SYSTEM)
        }

        private fun sourceFile(): File? {
            // Music wallpaper is always the generated live frame while playback
            // is active. No static setBitmap() call is made for music changes.
            if (prefs.liveMusicPlaying || !prefs.restoreOnPause) {
                return File(applicationContext.filesDir, WallpaperHelper.FILE_CURRENT)
                    .takeIf { it.exists() }
            }

            // When playback stops, restore the correct original wallpaper for
            // the actual live-wallpaper surface. This is important when Xiaomi
            // creates a separate lock-screen Engine.
            val flags = targetFlags()
            val target = when {
                (flags and WallpaperManager.FLAG_LOCK) != 0 &&
                    (flags and WallpaperManager.FLAG_SYSTEM) == 0 -> WallpaperManager.FLAG_LOCK
                else -> WallpaperManager.FLAG_SYSTEM
            }
            return WallpaperHelper(applicationContext).liveWallpaperOriginal(target)
        }

        private fun loadBitmap(file: File): Bitmap? {
            val modified = file.lastModified()
            if (cachedBitmap != null && cachedFilePath == file.absolutePath && cachedModified == modified) {
                return cachedBitmap
            }
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inScaled = false
                inDither = true
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return cachedBitmap
            cachedBitmap?.let { if (!it.isRecycled) it.recycle() }
            cachedBitmap = decoded
            cachedFilePath = file.absolutePath
            cachedModified = modified
            return decoded
        }

        private fun drawWallpaper() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                // A lock-screen surface can request a redraw before Android marks
                // the engine visible. If a real surface exists, allow the draw.
                if (!visible && !holder.surface.isValid) return
                if (!holder.surface.isValid) return

                canvas = holder.lockCanvas()
                if (canvas == null) return
                canvas.drawColor(Color.BLACK)

                val file = sourceFile() ?: return
                val bitmap = loadBitmap(file) ?: return
                if (bitmap.isRecycled) return

                val scale = maxOf(
                    canvas.width.toFloat() / bitmap.width,
                    canvas.height.toFloat() / bitmap.height
                )
                val w = bitmap.width * scale
                val h = bitmap.height * scale
                val left = (canvas.width - w) / 2f
                val top = (canvas.height - h) / 2f
                canvas.drawBitmap(bitmap, null, RectF(left, top, left + w, top + h), paint)
            } catch (t: Throwable) {
                android.util.Log.w("MusWallLive", "Live wallpaper draw failed", t)
            } finally {
                if (canvas != null) {
                    try { holder.unlockCanvasAndPost(canvas) } catch (_: Throwable) {}
                }
            }
        }

        override fun onDestroy() {
            visible = false
            drawHandler.removeCallbacksAndMessages(null)
            if (receiverRegistered) {
                try { applicationContext.unregisterReceiver(receiver) } catch (_: Throwable) {}
                receiverRegistered = false
            }
            cachedBitmap?.let { if (!it.isRecycled) it.recycle() }
            cachedBitmap = null
            drawThread.quitSafely()
            mainHandler.removeCallbacksAndMessages(null)
            super.onDestroy()
        }
    }
}
