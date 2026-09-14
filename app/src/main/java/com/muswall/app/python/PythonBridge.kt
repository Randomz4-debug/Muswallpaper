package com.muswall.app.python

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.muswall.app.data.PreferencesManager
import java.io.File

object PythonBridge {
    @Volatile
    private var started = false
    private lateinit var appContext: Context

    @Synchronized
    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (!started) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
            started = true
        }
    }

    private fun resolveRenderSize(requestedWidth: Int, requestedHeight: Int): Pair<Int, Int> {
        val prefs = PreferencesManager.getInstance(appContext)
        val portrait = requestedHeight >= requestedWidth
        return when (prefs.renderResolution) {
            PreferencesManager.RESOLUTION_1080P -> if (portrait) 1080 to 1920 else 1920 to 1080
            PreferencesManager.RESOLUTION_1440P -> if (portrait) 1440 to 2560 else 2560 to 1440
            PreferencesManager.RESOLUTION_4K -> if (portrait) 2160 to 3840 else 3840 to 2160
            PreferencesManager.RESOLUTION_8K -> if (portrait) 4320 to 7680 else 7680 to 4320
            PreferencesManager.RESOLUTION_12K -> if (portrait) 6480 to 11520 else 11520 to 6480
            PreferencesManager.RESOLUTION_16K -> if (portrait) 8640 to 15360 else 15360 to 8640
            PreferencesManager.RESOLUTION_CUSTOM -> {
                val w = prefs.customRenderWidth
                val h = prefs.customRenderHeight
                if (portrait) minOf(w, h) to maxOf(w, h) else maxOf(w, h) to minOf(w, h)
            }
            else -> requestedWidth.coerceAtLeast(160) to requestedHeight.coerceAtLeast(240)
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
        if (!::appContext.isInitialized) {
            android.util.Log.e("MusWallPython", "PythonBridge used before initialize()")
            return null
        }

        val (renderWidth, renderHeight) = resolveRenderSize(targetWidth, targetHeight)
        val pixels = renderWidth.toLong() * renderHeight.toLong()
        if (pixels > 45_000_000L) {
            android.util.Log.w(
                "MusWallPython",
                "Very high resolution selected: ${renderWidth}x${renderHeight}. This may use a large amount of RAM."
            )
        }

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
                renderWidth,
                renderHeight,
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
            BitmapFactory.decodeFile(output.absolutePath)
        } catch (t: Throwable) {
            android.util.Log.e("MusWallPython", "Wallpaper render failed", t)
            null
        } finally {
            runCatching { source.delete() }
            runCatching { output.delete() }
        }
    }
}
