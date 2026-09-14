package com.muswall.app.python

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.chaquo.python.PyObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Lazy, thread-safe bridge to the Python wallpaper renderer. */
object PythonBridge {
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private fun ensurePython(): Boolean {
        return try {
            val context = appContext ?: return false
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            true
        } catch (t: Throwable) {
            android.util.Log.e("MusWallPython", "Python startup failed", t)
            false
        }
    }

    suspend fun generateWallpaper(
        srcBitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        blurRadius: Float,
        darkness: Float,
        artScale: Float,
        cornerRadius: Int = 42,
        addShadow: Boolean = true,
        effect: String = "BLUR",
        blurType: String = "GAUSSIAN",
        coverHeight: Int = 44,
        coverOffset: Int = 50,
        transitionHeight: Int = 20
    ): Bitmap? = withContext(Dispatchers.Default) {
        try {
            if (!ensurePython()) return@withContext null

            val py = Python.getInstance()
            val module: PyObject = py.getModule("wallpaper_engine")
            val stream = ByteArrayOutputStream()
            // Avoid feeding oversized source bitmaps into Python.
            val source = if (srcBitmap.width > 1600 || srcBitmap.height > 1600) {
                val scale = minOf(1600f / srcBitmap.width, 1600f / srcBitmap.height)
                Bitmap.createScaledBitmap(
                    srcBitmap,
                    (srcBitmap.width * scale).toInt().coerceAtLeast(1),
                    (srcBitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else srcBitmap
            source.compress(Bitmap.CompressFormat.JPEG, 84, stream)
            if (source !== srcBitmap) source.recycle()

            val result = module.callAttr(
                "process_wallpaper",
                stream.toByteArray(),
                targetWidth.coerceIn(480, 900),
                targetHeight.coerceIn(960, 1800),
                blurRadius.coerceIn(0f, 100f).toDouble(),
                darkness.coerceIn(0f, 1f).toDouble(),
                artScale.coerceIn(0.2f, 1f).toDouble(),
                cornerRadius.coerceIn(0, 200),
                addShadow,
                effect,
                blurType,
                coverHeight.coerceIn(0, 100),
                coverOffset.coerceIn(0, 100),
                transitionHeight.coerceIn(0, 100),
                "JPEG",
                84
            )
            val bytes = result.toJava(ByteArray::class.java)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (t: Throwable) {
            android.util.Log.e("MusWallPython", "Wallpaper generation failed", t)
            null
        }
    }
}
