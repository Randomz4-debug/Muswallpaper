package com.muswall.app.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
        private const val TAG = "MusWallWallpaper"
        private const val FILE_ORIGINAL = "original_wallpaper.png"
        const val FILE_CURRENT = "current_music_wallpaper.jpg"

        fun isXiaomiOrPoco(): Boolean {
            val m = Build.MANUFACTURER.lowercase()
            val b = Build.BRAND.lowercase()
            return m.contains("xiaomi") || m.contains("redmi") || b.contains("poco")
        }
    }

    fun currentWallpaperFile(): File = File(context.filesDir, FILE_CURRENT)

    suspend fun saveCurrentForLiveWallpaper(bitmap: Bitmap) = withContext(Dispatchers.IO) {
        val tmp = File(context.filesDir, "$FILE_CURRENT.tmp")
        val dst = currentWallpaperFile()
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (!tmp.renameTo(dst)) {
            dst.delete()
            tmp.renameTo(dst)
        }
        prefs.lastArtworkPath = dst.absolutePath
    }

    suspend fun backupOriginalIfNeeded() = withContext(Dispatchers.IO) {
        if (prefs.originalBackedUp) return@withContext
        try {
            val drawable = wallpaperManager.drawable
            val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (bitmap != null) {
                FileOutputStream(File(context.filesDir, FILE_ORIGINAL)).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                prefs.originalBackedUp = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
        }
    }

    suspend fun applyStatic(bitmap: Bitmap, target: String): ApplyResult = withContext(Dispatchers.IO) {
        backupOriginalIfNeeded()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val flags = when (target) {
                    PreferencesManager.TARGET_HOME -> WallpaperManager.FLAG_SYSTEM
                    PreferencesManager.TARGET_LOCK -> WallpaperManager.FLAG_LOCK
                    else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                }
                wallpaperManager.setBitmap(bitmap, null, true, flags)
                ApplyResult(true, target, null)
            } else {
                wallpaperManager.setBitmap(bitmap)
                ApplyResult(true, PreferencesManager.TARGET_BOTH, null)
            }
        } catch (e: SecurityException) {
            try {
                wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                ApplyResult(true, PreferencesManager.TARGET_HOME, "Lock-screen wallpaper is restricted by the device; home screen was updated.")
            } catch (fallback: Exception) {
                ApplyResult(false, target, fallback.message)
            }
        } catch (e: Exception) {
            ApplyResult(false, target, e.message)
        }
    }

    fun openLiveWallpaperPicker() {
        try {
            val component = ComponentName(context, MusicWallpaperService::class.java)
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    suspend fun copyOriginalToLiveCache(): Boolean = withContext(Dispatchers.IO) {
        val original = File(context.filesDir, FILE_ORIGINAL)
        if (!original.exists()) return@withContext false
        return@withContext try {
            original.copyTo(currentWallpaperFile(), overwrite = true)
            true
        } catch (_: Exception) { false }
    }

    suspend fun restoreOriginal(): Boolean = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, FILE_ORIGINAL)
        if (!file.exists()) return@withContext false
        return@withContext try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
            } else {
                wallpaperManager.setBitmap(bitmap)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    data class ApplyResult(val success: Boolean, val target: String, val message: String?)
}
