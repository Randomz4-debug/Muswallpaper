from pathlib import Path

MEDIA = Path('app/src/main/java/com/muswall/app/service/MediaNotificationListenerService.kt')

s = MEDIA.read_text()

# Imports required for converting notification/display icons to bitmaps.
s = s.replace(
    'import android.graphics.BitmapFactory\n',
    'import android.graphics.BitmapFactory\nimport android.graphics.Canvas\nimport android.graphics.drawable.Drawable\n'
)
s = s.replace(
    'import android.media.MediaMetadata\n',
    'import android.media.MediaMetadata\nimport android.app.Notification\n'
)

start = s.index('    private fun extractArtwork(metadata: MediaMetadata): Bitmap? {')
end = s.index('\n    private fun downsampleArtwork', start)

replacement = '''    /**
     * Resolve the artwork for the TRACK that is playing, not merely the album/playlist.
     *
     * Automatic priority:
     *   1. Current media notification artwork (usually the exact Now Playing image)
     *   2. METADATA_KEY_ART / ART_URI
     *   3. Display icon
     *   4. Album artwork as the final fallback
     *
     * The Settings > Music photo source selector can force any of these sources.
     */
    private fun extractArtwork(metadata: MediaMetadata): Bitmap? {
        return try {
            val source = prefs.photoSource
            val primary = when (source) {
                PreferencesManager.PHOTO_NOTIFICATION -> notificationArtwork(metadata)
                PreferencesManager.PHOTO_ART -> metadataArt(metadata)
                PreferencesManager.PHOTO_DISPLAY_ICON -> displayIconArtwork(metadata)
                PreferencesManager.PHOTO_ALBUM -> albumArtwork(metadata)
                PreferencesManager.PHOTO_CUSTOM -> customPhotoArtwork()
                else -> notificationArtwork(metadata)
                    ?: metadataArt(metadata)
                    ?: displayIconArtwork(metadata)
                    ?: albumArtwork(metadata)
            }

            if (primary != null) return primary
            if (!prefs.photoFallback) return null

            // If the selected source is unavailable, never leave the wallpaper blank.
            when (source) {
                PreferencesManager.PHOTO_NOTIFICATION -> metadataArt(metadata) ?: albumArtwork(metadata)
                PreferencesManager.PHOTO_ART -> notificationArtwork(metadata) ?: albumArtwork(metadata)
                PreferencesManager.PHOTO_DISPLAY_ICON -> notificationArtwork(metadata) ?: metadataArt(metadata) ?: albumArtwork(metadata)
                PreferencesManager.PHOTO_ALBUM -> notificationArtwork(metadata) ?: metadataArt(metadata)
                PreferencesManager.PHOTO_CUSTOM -> notificationArtwork(metadata) ?: metadataArt(metadata) ?: albumArtwork(metadata)
                else -> null
            }
        } catch (t: Throwable) {
            Log.w("MusWallMedia", "Artwork extraction failed", t)
            null
        }
    }

    private fun metadataArt(metadata: MediaMetadata): Bitmap? {
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { return downsampleArtwork(it) }
        val uri = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI).orEmpty()
        return bitmapFromUri(uri)
    }

    private fun albumArtwork(metadata: MediaMetadata): Bitmap? {
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { return downsampleArtwork(it) }
        val uri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI).orEmpty()
        return bitmapFromUri(uri)
    }

    private fun displayIconArtwork(metadata: MediaMetadata): Bitmap? {
        metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let { return downsampleArtwork(it) }
        val uri = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI).orEmpty()
        return bitmapFromUri(uri)
    }

    /** Prefer the media notification's large icon because many players put their actual
     * current-track artwork here even when MediaMetadata exposes album/playlist art. */
    private fun notificationArtwork(metadata: MediaMetadata): Bitmap? {
        val packageName = activeController?.packageName ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        val notifications = activeNotifications.orEmpty().filter { it.packageName == packageName }

        val matching = notifications.firstOrNull { sbn ->
            val extras = sbn.notification.extras
            val nTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val nText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            (title.isNotBlank() && nTitle.equals(title, ignoreCase = true)) ||
                (title.isNotBlank() && nText.contains(title, ignoreCase = true)) ||
                (artist.isNotBlank() && nText.contains(artist, ignoreCase = true))
        }
        val transport = notifications.firstOrNull { it.notification.category == Notification.CATEGORY_TRANSPORT }
        val candidates = listOfNotNull(matching, transport) + notifications

        for (sbn in candidates.distinctBy { it.key }) {
            val icon = runCatching { sbn.notification.getLargeIcon() }.getOrNull() ?: continue
            val drawable = runCatching { icon.loadDrawable(this) }.getOrNull() ?: continue
            val bitmap = drawableToBitmap(drawable) ?: continue
            return downsampleArtwork(bitmap)
        }
        return null
    }

    private fun customPhotoArtwork(): Bitmap? {
        val uriText = prefs.customPhotoUri.trim()
        return if (uriText.isBlank()) null else bitmapFromUri(uriText)
    }

    private fun bitmapFromUri(uriText: String): Bitmap? {
        if (uriText.isBlank()) return null
        return runCatching {
            contentResolver.openInputStream(Uri.parse(uriText)).use { input ->
                if (input == null) null else BitmapFactory.decodeStream(input)?.let { downsampleArtwork(it) }
            }
        }.getOrNull()
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 512
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 512
        val bitmap = Bitmap.createBitmap(width.coerceAtMost(2048), height.coerceAtMost(2048), Bitmap.Config.ARGB_8888)
        Canvas(bitmap).also { canvas ->
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
        return bitmap
    }
'''

s = s[:start] + replacement + s[end:]
# Normalize imports so repeated GitHub Actions runs remain idempotent.
lines = MEDIA.read_text().splitlines()
seen = set()
normalized = []
for line in lines:
    if line.startswith("import "):
        if line in seen:
            continue
        seen.add(line)
    normalized.append(line)
MEDIA.write_text("\n".join(normalized) + "\n")
print('Current-track artwork selection patched: notification > track ART > display icon > album, with selectable source modes and fallbacks.')
