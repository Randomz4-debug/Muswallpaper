package com.muswall.app.python

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object PythonBridge {
    suspend fun generateWallpaper(
        srcBitmap: Bitmap,
        targetWidth: Int = 720,
        targetHeight: Int = 1600,
        blurRadius: Float = 80f,
        darkness: Float = 0f,
        artScale: Float = 0.72f,
        cornerRadius: Int = 42,
        addShadow: Boolean = true,
        effect: String = "BLUR",
        blurType: String = "GAUSSIAN",
        coverHeight: Int = 44,
        coverOffset: Int = 50,
        transitionHeight: Int = 20
    ): Bitmap? = withContext(Dispatchers.Default) {
        try {
            val py = Python.getInstance()
            val module: PyObject = py.getModule("wallpaper_engine")
            val stream = ByteArrayOutputStream()
            srcBitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)
            val result = module.callAttr(
                "process_wallpaper",
                stream.toByteArray(),
                targetWidth,
                targetHeight,
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
                88
            )
            val bytes = result.toJava(ByteArray::class.java)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            null
        }
    }
}
