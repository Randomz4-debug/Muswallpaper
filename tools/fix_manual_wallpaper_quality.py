from pathlib import Path
import re

PREFS = Path("app/src/main/java/com/muswall/app/data/PreferencesManager.kt")
BRIDGE = Path("app/src/main/java/com/muswall/app/python/PythonBridge.kt")
SETTINGS = Path("app/src/main/java/com/muswall/app/ui/SettingsActivity.kt")
LIVE = Path("app/src/main/java/com/muswall/app/wallpaper/MusicWallpaperService.kt")
WORKFLOW = Path(".github/workflows/build-apk.yml")

# ---------- Preferences ----------
s = PREFS.read_text(encoding="utf-8")
s = s.replace(
    'const val TARGET_HOME="home"; const val TARGET_LOCK="lock"; const val TARGET_BOTH="both";',
    'const val TARGET_HOME="home"; const val TARGET_LOCK="lock"; const val TARGET_BOTH="both"; const val WALLPAPER_QUALITY_ORIGINAL="original"; const val WALLPAPER_QUALITY_AUTO="auto"; const val WALLPAPER_QUALITY_4K="4k"; const val WALLPAPER_QUALITY_8K="8k";'
)
if 'var wallpaperQuality:' not in s:
    needle = 'var photoSource:String get()=sp.getString("photo_source",PHOTO_AUTO)?:PHOTO_AUTO;'
    replacement = 'var wallpaperQuality:String get()=sp.getString("wallpaper_quality",WALLPAPER_QUALITY_AUTO)?:WALLPAPER_QUALITY_AUTO;set(v)=sp.edit().putString("wallpaper_quality",v).apply(); ' + needle
    s = s.replace(needle, replacement)
PREFS.write_text(s, encoding="utf-8")

# ---------- PythonBridge: quality-aware render size + persistent render cache ----------
s = BRIDGE.read_text(encoding="utf-8")
s = s.replace('import java.io.File\n', 'import java.io.File\nimport java.security.MessageDigest\n')

start = s.index('    private fun resolveRenderSize(')
end = s.index('\n    fun generateWallpaper(', start)
new_resolver = '''    private fun resolveRenderSize(requestedWidth: Int, requestedHeight: Int, artworkWidth: Int, artworkHeight: Int): Pair<Int, Int> {
        val prefs = PreferencesManager.getInstance(appContext)
        val deviceW = requestedWidth.coerceAtLeast(160)
        val deviceH = requestedHeight.coerceAtLeast(240)
        val portrait = deviceH >= deviceW
        val sourceW = artworkWidth.coerceAtLeast(1)
        val sourceH = artworkHeight.coerceAtLeast(1)

        fun targetForLongSide(longSide: Int): Pair<Int, Int> {
            val aspect = deviceW.toDouble() / deviceH.toDouble()
            return if (portrait) {
                val h = longSide
                val w = (h * aspect).roundToInt().coerceAtLeast(1)
                w to h
            } else {
                val w = longSide
                val h = (w / aspect).roundToInt().coerceAtLeast(1)
                w to h
            }
        }

        return when (prefs.wallpaperQuality) {
            PreferencesManager.WALLPAPER_QUALITY_ORIGINAL -> sourceW to sourceH
            PreferencesManager.WALLPAPER_QUALITY_4K -> targetForLongSide(3840)
            PreferencesManager.WALLPAPER_QUALITY_8K -> targetForLongSide(7680)
            else -> deviceW to deviceH
        }
    }

    private fun sha256(file: File): String {
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

    private fun safeCacheKey(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
'''
s = s[:start] + new_resolver + s[end:]
s = s.replace('import java.security.MessageDigest\n', 'import java.security.MessageDigest\nimport kotlin.math.roundToInt\n')

s = s.replace(
    'val (renderWidth, renderHeight) = resolveRenderSize(targetWidth, targetHeight)',
    'val (renderWidth, renderHeight) = resolveRenderSize(targetWidth, targetHeight, artwork.width, artwork.height)'
)

old_block = '''        val dir = File(appContext.cacheDir, "wallpaper").apply { mkdirs() }
        val source = File(dir, "source.jpg")
        val output = File(dir, "result.jpg")
        return try {
            source.outputStream().use { artwork.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val py = Python.getInstance()
            val module = py.getModule("wallpaper_engine")
            module.callAttr(
'''
new_block = '''        val dir = File(appContext.cacheDir, "wallpaper").apply { mkdirs() }
        val cacheDir = File(appContext.cacheDir, "wallpaper_quality_cache").apply { mkdirs() }
        val source = File(dir, "source.jpg")
        val output = File(dir, "result.jpg")
        return try {
            source.outputStream().use { artwork.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val sourceHash = sha256(source)
            val cacheKey = safeCacheKey(
                listOf(
                    sourceHash, prefs.wallpaperQuality, renderWidth, renderHeight,
                    blurRadius, darkness, artScale, cornerRadius, addShadow, effect, blurType,
                    coverHeight, coverOffset, transitionHeight, showLyrics, lyrics,
                    prefs.backgroundMode, prefs.backgroundColor, prefs.backgroundColor2, prefs.accentColor,
                    photoSource, customPhotoPath
                ).joinToString("|")
            )
            val cached = File(cacheDir, "$cacheKey.jpg")
            if (cached.exists() && cached.length() > 0L) {
                return BitmapFactory.decodeFile(cached.absolutePath)
            }

            val py = Python.getInstance()
            val module = py.getModule("wallpaper_engine")
            module.callAttr(
'''
if old_block not in s:
    raise SystemExit("PythonBridge render block anchor missing")
s = s.replace(old_block, new_block)

s = s.replace(
    '            BitmapFactory.decodeFile(output.absolutePath)\n        } catch',
    '''            if (!output.exists() || output.length() <= 0L) return null
            runCatching {
                output.copyTo(cached, overwrite = true)
                trimRenderCache(cacheDir)
            }
            BitmapFactory.decodeFile(output.absolutePath)
        } catch'''
)

if 'private fun trimRenderCache' not in s:
    insert = '''    private fun trimRenderCache(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("jpg", true) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        // Keep a small LRU-like cache so 4K/8K wallpapers do not fill storage.
        files.drop(8).forEach { runCatching { it.delete() } }
    }

'''
    s = s.replace('\n    fun generateWallpaper(', '\n' + insert + '    fun generateWallpaper(', 1)
BRIDGE.write_text(s, encoding="utf-8")

# ---------- Settings: manual Original / Auto / 4K / 8K picker ----------
s = SETTINGS.read_text(encoding="utf-8")
s = s.replace('findViewById<TextView>(R.id.resolutionRow).setOnClickListener{chooseResolution()}',
              'findViewById<TextView>(R.id.resolutionRow).setOnClickListener{chooseWallpaperQuality()}')
s = s.replace('updateResolutionLabel()', 'updateWallpaperQualityLabel()')
pattern = r'    private fun chooseResolution\(\)\{.*?\};private fun updateResolutionLabel\(\)\{.*?\}'
replacement = '''    private fun chooseWallpaperQuality(){
        val labels=arrayOf("Original","Auto (device resolution)","4K","8K")
        val values=arrayOf(
            PreferencesManager.WALLPAPER_QUALITY_ORIGINAL,
            PreferencesManager.WALLPAPER_QUALITY_AUTO,
            PreferencesManager.WALLPAPER_QUALITY_4K,
            PreferencesManager.WALLPAPER_QUALITY_8K
        )
        AlertDialog.Builder(this)
            .setTitle("Wallpaper quality")
            .setSingleChoiceItems(labels, values.indexOf(prefs.wallpaperQuality).coerceAtLeast(0)){dialog,which->
                prefs.wallpaperQuality=values[which]
                updateWallpaperQualityLabel()
                dialog.dismiss()
                refreshLive()
            }.show()
    }
    private fun updateWallpaperQualityLabel(){
        val label=when(prefs.wallpaperQuality){
            PreferencesManager.WALLPAPER_QUALITY_ORIGINAL->"Original artwork resolution"
            PreferencesManager.WALLPAPER_QUALITY_4K->"4K • 3840px long side"
            PreferencesManager.WALLPAPER_QUALITY_8K->"8K • 7680px long side"
            else->"Auto • device resolution"
        }
        findViewById<TextView>(R.id.resolutionRow).text="Wallpaper quality\n$label"
    }'''
s2, n = re.subn(pattern, replacement, s, count=1, flags=re.S)
if n != 1:
    raise SystemExit("Settings resolution function anchor missing")
SETTINGS.write_text(s2, encoding="utf-8")

# ---------- Live wallpaper: decode the cached 4K/8K file near screen size ----------
s = LIVE.read_text(encoding="utf-8")
old = '''        private fun loadBitmap(file: File): Bitmap? {
            val modified = file.lastModified()
            if (cachedBitmap != null && cachedFilePath == file.absolutePath && cachedModified == modified) return cachedBitmap
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565; inScaled = false; inDither = true }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return cachedBitmap
            cachedBitmap?.let { if (!it.isRecycled) it.recycle() }
            cachedBitmap = decoded; cachedFilePath = file.absolutePath; cachedModified = modified
            return decoded
        }'''
new = '''        private fun loadBitmap(file: File): Bitmap? {
            val modified = file.lastModified()
            if (cachedBitmap != null && cachedFilePath == file.absolutePath && cachedModified == modified) return cachedBitmap

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val surfaceW = surfaceHolder.surfaceFrame.width().coerceAtLeast(1)
            val surfaceH = surfaceHolder.surfaceFrame.height().coerceAtLeast(1)
            var sample = 1
            while ((bounds.outWidth / (sample * 2)) >= surfaceW && (bounds.outHeight / (sample * 2)) >= surfaceH) {
                sample *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inScaled = false
                inDither = true
                inSampleSize = sample
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return cachedBitmap
            cachedBitmap?.let { if (!it.isRecycled) it.recycle() }
            cachedBitmap = decoded; cachedFilePath = file.absolutePath; cachedModified = modified
            return decoded
        }'''
if old not in s:
    raise SystemExit("Live loadBitmap anchor missing")
s = s.replace(old, new)
LIVE.write_text(s, encoding="utf-8")


# ---------- Auto quality must use the actual display size ----------
m = Path("app/src/main/java/com/muswall/app/service/MediaNotificationListenerService.kt").read_text(encoding="utf-8")
m = m.replace(
    'val targetW = (dm.widthPixels * .70f).toInt().coerceIn(480, 1080)\\n        val targetH = (dm.heightPixels * .70f).toInt().coerceIn(900, 1920)',
    'val targetW = dm.widthPixels.coerceAtLeast(480)\\n        val targetH = dm.heightPixels.coerceAtLeast(900)'
)
Path("app/src/main/java/com/muswall/app/service/MediaNotificationListenerService.kt").write_text(m, encoding="utf-8")

print("Added manual Original/Auto/4K/8K wallpaper quality, aspect-aware sizing, disk caching, background rendering compatibility, and memory-safe live decoding.")
