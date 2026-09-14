package com.muswall.app.python

import android.content.Context
import android.graphics.Bitmap
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.muswall.app.data.PreferencesManager
import java.io.File

object PythonBridge {
    private var started = false
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (!started) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
            started = true
        }
    }

    fun generateWallpaper(
        artwork: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        blurRadius: Float,
        darkness: Float,
        artScale: Float,
        cornerRadius: Int,
        addShadow: Boolean,
        effect: String,
        blurType: String,
        coverHeight: Int,
        coverOffset: Int,
        transitionHeight: Int,
        showLyrics: Boolean = false,
        lyrics: String = "",
        photoSource: String = PreferencesManager.PHOTO_ALBUM,
        customPhotoPath: String = ""
    ): Bitmap? {
        if (!started) initialize(appContext)
        val dir = File(appContext.cacheDir, "wallpaper").apply { mkdirs() }
        val source = File(dir, "source.jpg")
        val output = File(dir, "result.jpg")
        return try {
            source.outputStream().use { artwork.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val py = Python.getInstance()
            val module = py.getModule("wallpaper_engine")
            module.callAttr(
                "generate_wallpaper",
                source.absolutePath,
                output.absolutePath,
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
                appContext.getSharedPreferences("muswall", Context.MODE_PRIVATE).getString("background_mode", "art"),
                appContext.getSharedPreferences("muswall", Context.MODE_PRIVATE).getString("background_color", "#101010"),
                appContext.getSharedPreferences("muswall", Context.MODE_PRIVATE).getString("background_color2", "#303030"),
                appContext.getSharedPreferences("muswall", Context.MODE_PRIVATE).getString("accent_color", "#FFFFFF"),
                showLyrics,
                lyrics,
                photoSource,
                customPhotoPath
            )
            android.graphics.BitmapFactory.decodeFile(output.absolutePath)
        } catch (t: Throwable) {
            android.util.Log.e("MusWallPython", "Wallpaper render failed", t)
            null
        } finally {
            runCatching { source.delete() }
            runCatching { output.delete() }
        }
    }
}
