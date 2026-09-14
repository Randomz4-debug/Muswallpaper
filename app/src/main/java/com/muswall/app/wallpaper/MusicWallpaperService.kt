package com.muswall.app.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File

/**
 * Real Android live-wallpaper service.
 * It is event driven: the engine redraws only after MusWall produces a new
 * wallpaper, rather than running a permanent animation loop.
 */
class MusicWallpaperService : WallpaperService() {
    companion object {
        const val ACTION_REFRESH = "com.muswall.app.REFRESH_LIVE_WALLPAPER"
    }

    override fun onCreateEngine(): Engine = MusicEngine()

    inner class MusicEngine : Engine() {
        private val drawThread = HandlerThread("MusWall-LiveDraw").apply { start() }
        private val drawHandler = Handler(drawThread.looper)
        private val mainHandler = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var visible = false
        private var receiverRegistered = false

        private val refreshRunnable = Runnable { drawWallpaper() }
        private val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == ACTION_REFRESH) {
                    drawHandler.removeCallbacks(refreshRunnable)
                    drawHandler.post(refreshRunnable)
                }
            }
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) requestDraw()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            requestDraw()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            drawHandler.removeCallbacksAndMessages(null)
            super.onSurfaceDestroyed(holder)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            try {
                val filter = android.content.IntentFilter(ACTION_REFRESH)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
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
        }

        private fun requestDraw() {
            drawHandler.removeCallbacks(refreshRunnable)
            drawHandler.post(refreshRunnable)
        }

        private fun drawWallpaper() {
            if (!visible) return
            val holder = surfaceHolder
            var canvas: Canvas? = null
            var bitmap: Bitmap? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas == null) return
                canvas.drawColor(Color.BLACK)

                val file = File(applicationContext.filesDir, WallpaperHelper.FILE_CURRENT)
                if (!file.exists()) return

                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inSampleSize = 1
                }
                bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
                if (bitmap == null || bitmap!!.isRecycled) return

                val b = bitmap!!
                val scale = maxOf(
                    canvas.width.toFloat() / b.width,
                    canvas.height.toFloat() / b.height
                )
                val w = b.width * scale
                val h = b.height * scale
                val left = (canvas.width - w) / 2f
                val top = (canvas.height - h) / 2f
                canvas.drawBitmap(
                    b,
                    null,
                    android.graphics.RectF(left, top, left + w, top + h),
                    paint
                )
            } catch (t: Throwable) {
                android.util.Log.w("MusWallLive", "Live wallpaper draw failed", t)
            } finally {
                bitmap?.recycle()
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
            drawThread.quitSafely()
            super.onDestroy()
        }
    }
}
