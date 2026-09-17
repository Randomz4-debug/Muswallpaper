package com.muswall.app.python

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.muswall.app.data.PreferencesManager
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

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

    private fun resolveRenderSize(requestedWidth: Int, requestedHeight: Int, artworkWidth: Int, artworkHeight: Int): Pair<Int, Int> {
        val prefs = PreferencesManager.getInstance(appContext)
        val deviceW = requestedWidth.coerceAtLeast(160)
        val deviceH = requestedHeight.coerceAtLeast(240)
        val portrait = deviceH >= deviceW
        val sourceW = artworkWidth.coerceAtLeast(1)
        val sourceH = artworkHeight.coerceAtLeast(1)
        fun byLongSide(longSide: Int): Pair<Int, Int> {
            val aspect = deviceW.toDouble() / deviceH.toDouble()
            return if (portrait) {
                (longSide * aspect).roundToInt().coerceAtLeast(1) to longSide
            } else {
                longSide to (longSide / aspect).roundToInt().coerceAtLeast(1)
            }
        }
        return when (prefs.wallpaperQuality) {
            PreferencesManager.WALLPAPER_QUALITY_ORIGINAL -> sourceW to sourceH
            PreferencesManager.WALLPAPER_QUALITY_4K -> byLongSide(3840)
            PreferencesManager.WALLPAPER_QUALITY_8K -> byLongSide(7680)
            PreferencesManager.WALLPAPER_QUALITY_AUTO -> if (sourceW >= deviceW && sourceH >= deviceH) sourceW to sourceH else deviceW to deviceH
            else -> when (prefs.renderResolution) {
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
                else -> deviceW to deviceH
            }
        }
    }

    private fun hashFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun trimCache(dir: File) {
        dir.listFiles { f -> f.isFile && f.extension.equals("jpg", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(8)
            ?.forEach { runCatching { it.delete() } }
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

        val prefs = PreferencesManager.getInstance(appContext)
        val (renderWidth, renderHeight) = resolveRenderSize(targetWidth, targetHeight, artwork.width, artwork.height)
        val pixels = renderWidth.toLong() * renderHeight.toLong()
        if (pixels > 45_000_000L) {
            android.util.Log.w(
                "MusWallPython",
                "Very high resolution selected: ${renderWidth}x${renderHeight}. This may use a large amount of RAM."
            )
        }

        val dir = File(appContext.cacheDir, "wallpaper").apply { mkdirs() }
        val cacheDir = File(appContext.cacheDir, "wallpaper_quality_cache").apply { mkdirs() }
        val source = File(dir, "source.jpg")
        val output = File(dir, "result.jpg")
        return try {
            source.outputStream().use { artwork.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val cacheKey = hashFile(source) + "_" + listOf(
                prefs.wallpaperQuality, renderWidth, renderHeight, blurRadius, darkness, artScale,
                cornerRadius, addShadow, effect, blurType, coverHeight, coverOffset,
                transitionHeight, showLyrics, lyrics, photoSource, customPhotoPath,
                prefs.backgroundMode, prefs.backgroundColor, prefs.backgroundColor2, prefs.accentColor
            ).joinToString("|").hashCode().toString()
            val cached = File(cacheDir, "$cacheKey.jpg")
            if (cached.exists() && cached.length() > 0L) return BitmapFactory.decodeFile(cached.absolutePath)
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
            if (!output.exists() || output.length() <= 0L) return null
            output.copyTo(cached, overwrite = true)
            trimCache(cacheDir)
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
