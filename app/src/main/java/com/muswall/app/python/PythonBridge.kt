package com.muswall.app.python

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.chaquo.python.PyObject
import android.content.Context
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PythonBridge {
    private var started = false

    private fun ensurePython(context: Context): Boolean {
        return try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
            started = true
            true
        } catch (_: Throwable) {
            started = false
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
            // Python is started only when rendering is needed.
            if (!ensurePython(AppContextHolder.context)) return@withContext null

            val py = Python.getInstance()
            val module: PyObject = py.getModule("wallpaper_engine")
            val stream = ByteArrayOutputStream()
            srcBitmap.compress(Bitmap.CompressFormat.JPEG, 86, stream)
            val result = module.callAttr(
                "process_wallpaper",
                stream.toByteArray(),
                targetWidth.coerceIn(480, 900),
                targetHeight.coerceIn(960, 1800),
                blurRadius.toDouble(),
                darkness.toDouble(),
                artScale.toDouble(),
                cornerRadius,
                addShadow,
                effect,
                blurType,
                coverHeight,
                coverOffset,
                transitionHeight,
                "JPEG",
                86
            )
            val bytes = result.toJava(ByteArray::class.java)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            null
        }
    }

    private object AppContextHolder {
        lateinit var context: Context
    }

    fun initialize(context: Context) {
        AppContextHolder.context = context.applicationContext
    }
}
