package com.muswall.app.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import com.muswall.app.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class WallpaperHelper(private val context: Context) {
    private val wallpaperManager = WallpaperManager.getInstance(context)
    private val prefs = PreferencesManager.getInstance(context)

    companion object {
        private const val FILE_ORIGINAL_HOME = "original_home.png"
        private const val FILE_ORIGINAL_LOCK = "original_lock.png"

        fun isXiaomiOrPoco(): Boolean {
            val m = Build.MANUFACTURER.lowercase()
            val b = Build.BRAND.lowercase()
            return m.contains("xiaomi") || m.contains("redmi") || b.contains("poco")
        }
    }

    data class ApplyResult(val success: Boolean, val appliedTarget: String, val lockScreenSupported: Boolean, val errorMessage: String? = null)

    suspend fun backupOriginalWallpapersIfNeeded() = withContext(Dispatchers.IO) {
        if (prefs.isOriginalBackedUp) return@withContext
        try {
            val homeFile = File(context.filesDir, FILE_ORIGINAL_HOME)
            val lockFile = File(context.filesDir, FILE_ORIGINAL_LOCK)

            val currentHomeDrawable = wallpaperManager.drawable
            val homeBitmap = if (currentHomeDrawable is android.graphics.drawable.BitmapDrawable) currentHomeDrawable.bitmap else null
            homeBitmap?.let {
                FileOutputStream(homeFile).use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) }
                FileOutputStream(lockFile).use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) }
            }
            prefs.isOriginalBackedUp = true
        } catch (e: Exception) {
            Log.e("WallpaperHelper", "Backup failed: ${e.message}")
        }
    }

    suspend fun applyWallpaper(wallpaperBitmap: Bitmap, targetScreen: String): ApplyResult = withContext(Dispatchers.IO) {
        backupOriginalWallpapersIfNeeded()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            wallpaperManager.setBitmap(wallpaperBitmap)
            return@withContext ApplyResult(true, "BOTH", true)
        }

        var targetFlag = when (targetScreen) {
            PreferencesManager.TARGET_HOME -> WallpaperManager.FLAG_SYSTEM
            PreferencesManager.TARGET_LOCK -> WallpaperManager.FLAG_LOCK
            else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        }

        try {
            wallpaperManager.setBitmap(wallpaperBitmap, null, true, targetFlag)
            ApplyResult(true, targetScreen, true)
        } catch (se: SecurityException) {
            try {
                wallpaperManager.setBitmap(wallpaperBitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                ApplyResult(true, PreferencesManager.TARGET_HOME, false, "MIUI restricted lock screen; applied to home screen.")
            } catch (e: Exception) {
                ApplyResult(false, targetScreen, false, e.message)
            }
        }
    }

    suspend fun restoreOriginalWallpapers(): Boolean = withContext(Dispatchers.IO) {
        val homeFile = File(context.filesDir, FILE_ORIGINAL_HOME)
        val lockFile = File(context.filesDir, FILE_ORIGINAL_LOCK)
        try {
            if (homeFile.exists()) {
                val b = BitmapFactory.decodeFile(homeFile.absolutePath)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    wallpaperManager.setBitmap(b, null, true, WallpaperManager.FLAG_SYSTEM)
                } else {
                    wallpaperManager.setBitmap(b)
                }
            }
            if (lockFile.exists() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val b = BitmapFactory.decodeFile(lockFile.absolutePath)
                try {
                    wallpaperManager.setBitmap(b, null, true, WallpaperManager.FLAG_LOCK)
                } catch (ignored: Exception) {}
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}