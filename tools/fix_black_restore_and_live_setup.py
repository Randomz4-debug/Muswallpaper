from pathlib import Path

MAIN = Path('app/src/main/java/com/muswall/app/ui/MainActivity.kt')
HELPER = Path('app/src/main/java/com/muswall/app/wallpaper/WallpaperHelper.kt')

m = MAIN.read_text()
old = 'findViewById<MaterialButton>(R.id.btnLiveWallpaper).setOnClickListener { prefs.liveWallpaperEnabled = true; wallpaperHelper.openLiveWallpaperPicker() }'
new = '''findViewById<MaterialButton>(R.id.btnLiveWallpaper).setOnClickListener {
            // Capture the user's real wallpaper BEFORE HyperOS switches to MusWall.
            // This is essential on Android 14+/HyperOS because the old lock wallpaper
            // may no longer be readable after a live wallpaper becomes active.
            uiScope.launch(Dispatchers.IO) {
                wallpaperHelper.prepareOriginalBeforeLiveWallpaper()
                withContext(Dispatchers.Main) {
                    prefs.liveWallpaperEnabled = true
                    wallpaperHelper.openLiveWallpaperPicker()
                }
            }
        }'''
if old not in m:
    raise SystemExit('MainActivity live-wallpaper button pattern not found')
m = m.replace(old, new)
MAIN.write_text(m)

h = HELPER.read_text()
old = '''    suspend fun prepareOriginalBeforeLiveWallpaper(): Boolean = withContext(Dispatchers.IO) {
        try {
            backupOneIfMissingForPreparation(WallpaperManager.FLAG_SYSTEM)
            backupOneIfMissingForPreparation(WallpaperManager.FLAG_LOCK)
            originalFile(WallpaperManager.FLAG_LOCK).exists() || prefs.originalLockWallpaperUri.isNotBlank()
        } catch (_: Throwable) { false }
    }

    private fun backupOneIfMissingForPreparation(which: Int) {
        val destination=originalFile(which)
        if(destination.exists())return
        try {
            @Suppress("DEPRECATION")
            val drawable=wallpaperManager.peekDrawable(which)
            val source=(drawable as? BitmapDrawable)?.bitmap ?: return
            FileOutputStream(destination).use { source.compress(Bitmap.CompressFormat.PNG,100,it) }
        } catch (_: Throwable) {}
    }'''
new = '''    suspend fun prepareOriginalBeforeLiveWallpaper(): Boolean = withContext(Dispatchers.IO) {
        try {
            // First prefer an image explicitly selected inside MusWall. This is the
            // most reliable restore source on Android 14+ and survives HyperOS live-wallpaper switching.
            val selected = prefs.staticWallpaperUri.trim()
            if (selected.isNotBlank()) {
                val uri = runCatching { Uri.parse(selected) }.getOrNull()
                if (uri != null) {
                    copyUriIfMissing(uri, originalFile(WallpaperManager.FLAG_SYSTEM))
                    copyUriIfMissing(uri, originalFile(WallpaperManager.FLAG_LOCK))
                }
            }

            // If no MusWall image was selected, capture the currently visible system
            // wallpapers while they are still accessible, BEFORE the live wallpaper picker opens.
            backupOneIfMissingForPreparation(WallpaperManager.FLAG_SYSTEM)
            backupOneIfMissingForPreparation(WallpaperManager.FLAG_LOCK)
            originalFile(WallpaperManager.FLAG_SYSTEM).exists() ||
                originalFile(WallpaperManager.FLAG_LOCK).exists()
        } catch (_: Throwable) { false }
    }

    private fun copyUriIfMissing(uri: Uri, destination: File) {
        if (destination.exists()) return
        try {
            val tmp = File(context.filesDir, destination.name + ".tmp")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output, 64 * 1024) }
            } ?: return
            if (!tmp.renameTo(destination)) {
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not copy selected wallpaper", t)
        }
    }

    private fun backupOneIfMissingForPreparation(which: Int) {
        val destination = originalFile(which)
        if (destination.exists()) return
        try {
            @Suppress("DEPRECATION")
            val drawable = wallpaperManager.peekDrawable(which)
            val source = (drawable as? BitmapDrawable)?.bitmap ?: return
            FileOutputStream(destination).use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not capture wallpaper before live mode", t)
        }
    }'''
if old not in h:
    raise SystemExit('WallpaperHelper preparation pattern not found')
h = h.replace(old, new)
HELPER.write_text(h)

print('Fixed pre-live wallpaper backup so pause/stop can never fall through to a black frame when an original image is available.')
