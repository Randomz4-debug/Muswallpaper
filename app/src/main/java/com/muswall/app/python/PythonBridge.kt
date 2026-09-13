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
        targetWidth: Int = 1080,
        targetHeight: Int = 2400,
        blurRadius: Float = 35f,
        darkness: Float = 0.45f,
        artScale: Float = 0.72f,
        cornerRadius: Int = 40,
        addShadow: Boolean = true
    ): Bitmap? = withContext(Dispatchers.Default) {
        try {
            val py = Python.getInstance()
            val module: PyObject = py.getModule("wallpaper_engine")

            val stream = ByteArrayOutputStream()
            srcBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val artworkBytes = stream.toByteArray()

            val resultPyObj: PyObject = module.callAttr(
                "process_wallpaper",
                artworkBytes,
                targetWidth,
                targetHeight,
                blurRadius.toDouble(),
                darkness.toDouble(),
                artScale.toDouble(),
                cornerRadius,
                addShadow,
                "JPEG",
                92
            )
            val outputBytes = resultPyObj.toJava(ByteArray::class.java)
            BitmapFactory.decodeByteArray(outputBytes, 0, outputBytes.size)
        } catch (e: Throwable) {
            null
        }
    }
}