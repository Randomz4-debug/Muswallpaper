package com.muswall.app.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
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
        const val FILE_ORIGINAL_HOME = "original_home_wallpaper.png"
        const val FILE_ORIGINAL_LOCK = "original_lock_wallpaper.png"
        const val FILE_CURRENT = "current_music_wallpaper.jpg"

        fun isXiaomiOrPoco(): Boolean {
            val m = Build.MANUFACTURER.lowercase()
            val b = Build.BRAND.lowercase()
            return m.contains("xiaomi") || m.contains("redmi") || b.contains("poco")
        }
    }

    fun currentWallpaperFile(): File = File(context.filesDir, FILE_CURRENT)

    fun originalFile(which: Int): File = File(
        context.filesDir,
        if (which == WallpaperManager.FLAG_LOCK) FILE_ORIGINAL_LOCK else FILE_ORIGINAL_HOME
    )

    suspend fun saveCurrentForLiveWallpaper(bitmap: Bitmap) = withContext(Dispatchers.IO) {
        val tmp = File(context.filesDir, "$FILE_CURRENT.tmp")
        val dst = currentWallpaperFile()
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 86, it) }
        if (!tmp.renameTo(dst)) {
            dst.delete()
            tmp.renameTo(dst)
        }
        prefs.lastArtworkPath = dst.absolutePath
    }

    suspend fun setOriginalFromUri(uri: Uri, which: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            try { context.contentResolver.takePersistableUriPermission(uri, flags) } catch (_: Throwable) {}

            val destination = originalFile(which)
            val tmp = File(context.filesDir, "${destination.name}.tmp")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output, 64 * 1024) }
            } ?: return@withContext false

            if (!tmp.renameTo(destination)) {
                destination.delete()
                tmp.renameTo(destination)
            }

            if (which == WallpaperManager.FLAG_LOCK) prefs.originalLockWallpaperUri = uri.toString()
            else prefs.originalHomeWallpaperUri = uri.toString()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Saving original wallpaper failed", t)
            false
        }
    }

    /** Best-effort backup; Android 14+ may restrict reading the current wallpaper. */
    suspend fun backupOriginalIfNeeded() = withContext(Dispatchers.IO) {
        backupOneIfMissing(WallpaperManager.FLAG_SYSTEM)
        backupOneIfMissing(WallpaperManager.FLAG_LOCK)
        prefs.originalBackedUp = originalFile(WallpaperManager.FLAG_SYSTEM).exists() ||
            originalFile(WallpaperManager.FLAG_LOCK).exists()
    }

    private fun backupOneIfMissing(which: Int) {
        val destination = originalFile(which)
        val uriSet = if (which == WallpaperManager.FLAG_LOCK) {
            prefs.originalLockWallpaperUri.isNotBlank()
        } else {
            prefs.originalHomeWallpaperUri.isNotBlank()
        }
        if (uriSet || destination.exists()) return

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val drawable = wallpaperManager.getDrawable(which)
                val source = (drawable as? BitmapDrawable)?.bitmap ?: return
                FileOutputStream(destination).use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } else if (which == WallpaperManager.FLAG_SYSTEM) {
                @Suppress("DEPRECATION")
                val drawable = wallpaperManager.drawable
                val source = (drawable as? BitmapDrawable)?.bitmap ?: return
                FileOutputStream(destination).use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not automatically back up wallpaper $which", t)
        }
    }

    suspend fun applyStatic(bitmap: Bitmap, target: String): ApplyResult = withContext(Dispatchers.IO) {
        backupOriginalIfNeeded()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return@withContext try {
                wallpaperManager.setBitmap(bitmap)
                ApplyResult(true, PreferencesManager.TARGET_BOTH, null)
            } catch (t: Throwable) {
                ApplyResult(false, PreferencesManager.TARGET_BOTH, t.message)
            }
        }

        val requested = when (target) {
            PreferencesManager.TARGET_HOME -> listOf(WallpaperManager.FLAG_SYSTEM)
            PreferencesManager.TARGET_LOCK -> listOf(WallpaperManager.FLAG_LOCK)
            else -> listOf(WallpaperManager.FLAG_SYSTEM, WallpaperManager.FLAG_LOCK)
        }

        var successCount = 0
        val errors = mutableListOf<String>()
        for (which in requested) {
            try {
                wallpaperManager.setBitmap(bitmap, null, true, which)
                successCount++
            } catch (t: Throwable) {
                val name = if (which == WallpaperManager.FLAG_LOCK) "lock screen" else "home screen"
                errors += "$name: ${t.message ?: t.javaClass.simpleName}"
                Log.e(TAG, "Wallpaper apply failed for $name", t)
            }
        }

        val success = successCount == requested.size
        val message = when {
            success -> null
            successCount > 0 -> "${if (successCount == 1) "One wallpaper" else "Some wallpapers"} applied; ${errors.joinToString("; ")}"
            else -> errors.joinToString("; ").ifBlank { "Wallpaper was rejected by the device" }
        }
        ApplyResult(success, target, message)
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

    suspend fun restoreOriginal(): RestoreResult = withContext(Dispatchers.IO) {
        val results = mutableListOf<Boolean>()
        val errors = mutableListOf<String>()
        for (which in listOf(WallpaperManager.FLAG_SYSTEM, WallpaperManager.FLAG_LOCK)) {
            val file = originalFile(which)
            if (!file.exists()) continue
            try {
                BitmapFactory.decodeFile(file.absolutePath)?.let { bitmap ->
                    try {
                        wallpaperManager.setBitmap(bitmap, null, true, which)
                        results += true
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                } ?: errors.add("Could not decode ${if (which == WallpaperManager.FLAG_LOCK) "lock" else "home"} original")
            } catch (t: Throwable) {
                errors += "${if (which == WallpaperManager.FLAG_LOCK) "lock" else "home"}: ${t.message ?: t.javaClass.simpleName}"
            }
        }
        RestoreResult(results.isNotEmpty() && results.all { it }, errors.joinToString("; ").ifBlank { null })
    }

    fun liveWallpaperOriginal(which: Int): File? = originalFile(which).takeIf { it.exists() }

    data class ApplyResult(val success: Boolean, val target: String, val message: String?)
    data class RestoreResult(val success: Boolean, val message: String?)
}
