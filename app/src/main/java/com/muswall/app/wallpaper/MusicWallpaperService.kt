package com.muswall.app.wallpaper

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File

/**
 * Real Android live-wallpaper implementation.
 * The media listener writes a finished JPEG to app storage and this engine
 * redraws only when the artwork changes, avoiding a CPU-heavy animation loop.
 */
class MusicWallpaperService : WallpaperService() {
    companion object {
        const val ACTION_REFRESH = "com.muswall.app.REFRESH_LIVE_WALLPAPER"
    }

    override fun onCreateEngine(): Engine = MusicEngine()

    inner class MusicEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var visible = false
        private var receiverRegistered = false

        private val refreshRunnable = Runnable { draw() }
        private val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == ACTION_REFRESH) handler.removeCallbacksAndMessages(null).also { draw() }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) draw()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            draw()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            this.visible = false
            handler.removeCallbacks(refreshRunnable)
            super.onSurfaceDestroyed(holder)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            if (!receiverRegistered) {
                val filter = android.content.IntentFilter(ACTION_REFRESH)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    applicationContext.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION") applicationContext.registerReceiver(receiver, filter)
                }
                receiverRegistered = true
            }
        }

        override fun onDestroy() {
            handler.removeCallbacksAndMessages(null)
            if (receiverRegistered) {
                try { applicationContext.unregisterReceiver(receiver) } catch (_: Exception) {}
                receiverRegistered = false
            }
            super.onDestroy()
        }

        private fun draw() {
            if (!visible) return
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas == null) return
                canvas.drawColor(Color.BLACK)
                val file = File(applicationContext.filesDir, WallpaperHelper.FILE_CURRENT)
                val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
                if (bitmap != null && !bitmap.isRecycled) {
                    val scale = maxOf(canvas.width.toFloat() / bitmap.width, canvas.height.toFloat() / bitmap.height)
                    val w = bitmap.width * scale
                    val h = bitmap.height * scale
                    val left = (canvas.width - w) / 2f
                    val top = (canvas.height - h) / 2f
                    canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + w, top + h), paint)
                    bitmap.recycle()
                }
            } catch (_: Exception) {
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas)
            }
        }
    }
}
