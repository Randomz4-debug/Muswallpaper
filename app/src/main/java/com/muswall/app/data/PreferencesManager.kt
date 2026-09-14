package com.muswall.app.data

import android.content.Context

class PreferencesManager private constructor(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("muswall", Context.MODE_PRIVATE)

    companion object {
        @Volatile private var INSTANCE: PreferencesManager? = null
        fun getInstance(context: Context): PreferencesManager = INSTANCE ?: synchronized(this) {
            INSTANCE ?: PreferencesManager(context).also { INSTANCE = it }
        }
        const val MODE_MUSIC = "music"
        const val MODE_STATIC = "static"
        const val EFFECT_BLUR = "blur"
        const val EFFECT_COVER = "cover"
        const val EFFECT_CD = "cd"
        const val EFFECT_SQUARE = "square"
        const val EFFECT_COVER_COLOR = "cover_color"
        const val EFFECT_KALEIDOSCOPE = "kaleidoscope"
        const val EFFECT_PULSE = "pulse"
        const val EFFECT_FLOAT = "float"
        const val BLUR_GAUSSIAN = "gaussian"
        const val BLUR_SOLID = "solid"
        const val BLUR_MOTION = "motion"
        const val BLUR_GLASS = "glass"
        const val BLUR_RADIAL = "radial"
        const val BACKGROUND_ART = "art"
        const val BACKGROUND_COLOR = "color"
        const val BACKGROUND_GRADIENT = "gradient"
        const val BACKGROUND_AUTO = "auto"
        const val PHOTO_AUTO = "auto"
        const val PHOTO_ALBUM = "album"
        const val PHOTO_ART = "art"
        const val PHOTO_DISPLAY_ICON = "display_icon"
        const val PHOTO_NOTIFICATION = "notification"
        const val PHOTO_CUSTOM = "custom"
        const val TARGET_HOME = "home"
        const val TARGET_LOCK = "lock"
        const val TARGET_BOTH = "both"
        const val RESOLUTION_DEVICE = "device"
        const val RESOLUTION_1080P = "1080p"
        const val RESOLUTION_1440P = "1440p"
        const val RESOLUTION_4K = "4k"
        const val RESOLUTION_8K = "8k"
        const val RESOLUTION_12K = "12k"
        const val RESOLUTION_16K = "16k"
        const val RESOLUTION_CUSTOM = "custom"
        const val COLOR_SINGLE = "single"
        const val COLOR_GRADIENT = "gradient"
        const val COLOR_DOUBLE = "double"
        const val COLOR_RAINBOW = "rainbow"
    }

    var isAutoEnabled: Boolean get() = sp.getBoolean("auto", true); set(v) = sp.edit().putBoolean("auto", v).apply()
    var restoreOnPause: Boolean get() = sp.getBoolean("restore_pause", true); set(v) = sp.edit().putBoolean("restore_pause", v).apply()
    var wallpaperMode: String get() = sp.getString("mode", MODE_MUSIC) ?: MODE_MUSIC; set(v) = sp.edit().putString("mode", v).apply()
    var staticWallpaperUri: String get() = sp.getString("static_uri", "") ?: ""; set(v) = sp.edit().putString("static_uri", v).apply()
    var targetScreen: String get() = sp.getString("target_screen", TARGET_BOTH) ?: TARGET_BOTH; set(v) = sp.edit().putString("target_screen", v).apply()
    var originalHomeWallpaperUri: String get() = sp.getString("original_home_uri", "") ?: ""; set(v) = sp.edit().putString("original_home_uri", v).apply()
    var originalLockWallpaperUri: String get() = sp.getString("original_lock_uri", "") ?: ""; set(v) = sp.edit().putString("original_lock_uri", v).apply()
    var originalBackedUp: Boolean get() = sp.getBoolean("original_backed_up", false); set(v) = sp.edit().putBoolean("original_backed_up", v).apply()
    var lastArtworkPath: String get() = sp.getString("last_artwork_path", "") ?: ""; set(v) = sp.edit().putString("last_artwork_path", v).apply()
    var liveWallpaperEnabled: Boolean get() = sp.getBoolean("live_enabled", false); set(v) = sp.edit().putBoolean("live_enabled", v).apply()
    var liveMusicPlaying: Boolean get() = sp.getBoolean("live_playing", false); set(v) = sp.edit().putBoolean("live_playing", v).apply()
    var lastPlaybackState: String get() = sp.getString("last_playback_state", "paused") ?: "paused"; set(v) = sp.edit().putString("last_playback_state", v).apply()
    var lastTrackTitle: String get() = sp.getString("last_title", "") ?: ""; set(v) = sp.edit().putString("last_title", v).apply()
    var lastArtist: String get() = sp.getString("last_artist", "") ?: ""; set(v) = sp.edit().putString("last_artist", v).apply()
    var lastLyrics: String get() = sp.getString("last_lyrics", "") ?: ""; set(v) = sp.edit().putString("last_lyrics", v).apply()
    var lyricsPosition: Long get() = sp.getLong("lyrics_position", 0L); set(v) = sp.edit().putLong("lyrics_position", v.coerceAtLeast(0L)).apply()
    var effect: String get() = sp.getString("effect", EFFECT_COVER) ?: EFFECT_COVER; set(v) = sp.edit().putString("effect", v).apply()
    var blurType: String get() = sp.getString("blur_type", BLUR_GAUSSIAN) ?: BLUR_GAUSSIAN; set(v) = sp.edit().putString("blur_type", v).apply()
    var blurRadius: Int get() = sp.getInt("blur_radius", 24); set(v) = sp.edit().putInt("blur_radius", v.coerceIn(0, 80)).apply()
    var darkness: Int get() = sp.getInt("darkness", 20); set(v) = sp.edit().putInt("darkness", v.coerceIn(0, 100)).apply()
    var artScale: Int get() = sp.getInt("art_scale", 82); set(v) = sp.edit().putInt("art_scale", v.coerceIn(20, 120)).apply()
    var coverHeight: Int get() = sp.getInt("cover_height", 46); set(v) = sp.edit().putInt("cover_height", v.coerceIn(10, 90)).apply()
    var coverOffset: Int get() = sp.getInt("cover_offset", 0); set(v) = sp.edit().putInt("cover_offset", v.coerceIn(-50, 50)).apply()
    var transitionHeight: Int get() = sp.getInt("transition_height", 28); set(v) = sp.edit().putInt("transition_height", v.coerceIn(0, 100)).apply()
    var backgroundMode: String get() = sp.getString("background_mode", BACKGROUND_ART) ?: BACKGROUND_ART; set(v) = sp.edit().putString("background_mode", v).apply()
    var backgroundColor: String get() = sp.getString("background_color", "#101010") ?: "#101010"; set(v) = sp.edit().putString("background_color", v).apply()
    var backgroundColor2: String get() = sp.getString("background_color2", "#303030") ?: "#303030"; set(v) = sp.edit().putString("background_color2", v).apply()
    var accentColor: String get() = sp.getString("accent_color", "#8B5CF6") ?: "#8B5CF6"; set(v) = sp.edit().putString("accent_color", v).apply()
    var showLyrics: Boolean get() = sp.getBoolean("show_lyrics", false); set(v) = sp.edit().putBoolean("show_lyrics", v).apply()
    var lyricsX: Int get() = sp.getInt("lyrics_x", 50); set(v) = sp.edit().putInt("lyrics_x", v.coerceIn(0, 100)).apply()
    var lyricsY: Int get() = sp.getInt("lyrics_y", 78); set(v) = sp.edit().putInt("lyrics_y", v.coerceIn(0, 100)).apply()
    var lyricsWidth: Int get() = sp.getInt("lyrics_width", 88); set(v) = sp.edit().putInt("lyrics_width", v.coerceIn(20, 100)).apply()
    var lyricsSize: Int get() = sp.getInt("lyrics_size", 22); set(v) = sp.edit().putInt("lyrics_size", v.coerceIn(10, 72)).apply()
    var lyricsLines: Int get() = sp.getInt("lyrics_lines", 3); set(v) = sp.edit().putInt("lyrics_lines", v.coerceIn(1, 6)).apply()
    var lyricsColor: String get() = sp.getString("lyrics_color", "#FFFFFF") ?: "#FFFFFF"; set(v) = sp.edit().putString("lyrics_color", v).apply()
    var lyricsColor2: String get() = sp.getString("lyrics_color2", "#A78BFA") ?: "#A78BFA"; set(v) = sp.edit().putString("lyrics_color2", v).apply()
    var lyricsColor3: String get() = sp.getString("lyrics_color3", "#22D3EE") ?: "#22D3EE"; set(v) = sp.edit().putString("lyrics_color3", v).apply()
    var lyricsColorMode: String get() = sp.getString("lyrics_color_mode", COLOR_SINGLE) ?: COLOR_SINGLE; set(v) = sp.edit().putString("lyrics_color_mode", v).apply()
    var lyricsLanguage: String get() = sp.getString("lyrics_language", "original") ?: "original"; set(v) = sp.edit().putString("lyrics_language", v).apply()
    var lyricsShadow: Boolean get() = sp.getBoolean("lyrics_shadow", true); set(v) = sp.edit().putBoolean("lyrics_shadow", v).apply()
    var bassEnabled: Boolean get() = sp.getBoolean("bass_enabled", true); set(v) = sp.edit().putBoolean("bass_enabled", v).apply()
    var bassX: Int get() = sp.getInt("bass_x", 50); set(v) = sp.edit().putInt("bass_x", v.coerceIn(0, 100)).apply()
    var bassY: Int get() = sp.getInt("bass_y", 90); set(v) = sp.edit().putInt("bass_y", v.coerceIn(0, 100)).apply()
    var bassWidth: Int get() = sp.getInt("bass_width", 82); set(v) = sp.edit().putInt("bass_width", v.coerceIn(20, 100)).apply()
    var bassHeight: Int get() = sp.getInt("bass_height", 8); set(v) = sp.edit().putInt("bass_height", v.coerceIn(2, 30)).apply()
    var bassSensitivity: Int get() = sp.getInt("bass_sensitivity", 70); set(v) = sp.edit().putInt("bass_sensitivity", v.coerceIn(0, 100)).apply()
    var bassColor: String get() = sp.getString("bass_color", "#FFFFFF") ?: "#FFFFFF"; set(v) = sp.edit().putString("bass_color", v).apply()
    var bassColor2: String get() = sp.getString("bass_color2", "#A78BFA") ?: "#A78BFA"; set(v) = sp.edit().putString("bass_color2", v).apply()
    var bassColor3: String get() = sp.getString("bass_color3", "#22D3EE") ?: "#22D3EE"; set(v) = sp.edit().putString("bass_color3", v).apply()
    var bassColorMode: String get() = sp.getString("bass_color_mode", COLOR_SINGLE) ?: COLOR_SINGLE; set(v) = sp.edit().putString("bass_color_mode", v).apply()
    var photoSource: String get() = sp.getString("photo_source", PHOTO_AUTO) ?: PHOTO_AUTO; set(v) = sp.edit().putString("photo_source", v).apply()
    var customPhotoUri: String get() = sp.getString("custom_photo_uri", "") ?: ""; set(v) = sp.edit().putString("custom_photo_uri", v).apply()
    var photoFallback: Boolean get() = sp.getBoolean("photo_fallback", true); set(v) = sp.edit().putBoolean("photo_fallback", v).apply()
    var restoreDelay: Int get() = sp.getInt("restore_delay", 0); set(v) = sp.edit().putInt("restore_delay", v).apply()
    var renderResolution: String get() = sp.getString("render_resolution", RESOLUTION_DEVICE) ?: RESOLUTION_DEVICE; set(v) = sp.edit().putString("render_resolution", v).apply()
    var customRenderWidth: Int get() = sp.getInt("render_width", 1080).coerceIn(160, 16384); set(v) = sp.edit().putInt("render_width", v.coerceIn(160, 16384)).apply()
    var customRenderHeight: Int get() = sp.getInt("render_height", 2400).coerceIn(240, 16384); set(v) = sp.edit().putInt("render_height", v.coerceIn(240, 16384)).apply()

    var widgetStyle: Int get() = sp.getInt("widget_style", 0); set(v) = sp.edit().putInt("widget_style", v.coerceIn(0, 2)).apply()
    var widgetOpacity: Int get() = sp.getInt("widget_opacity", 92); set(v) = sp.edit().putInt("widget_opacity", v.coerceIn(0, 100)).apply()
    var widgetShowArtwork: Boolean get() = sp.getBoolean("widget_show_artwork", true); set(v) = sp.edit().putBoolean("widget_show_artwork", v).apply()
    var widgetShowTitle: Boolean get() = sp.getBoolean("widget_show_title", true); set(v) = sp.edit().putBoolean("widget_show_title", v).apply()
    var widgetShowArtist: Boolean get() = sp.getBoolean("widget_show_artist", true); set(v) = sp.edit().putBoolean("widget_show_artist", v).apply()
    var widgetShowControls: Boolean get() = sp.getBoolean("widget_show_controls", true); set(v) = sp.edit().putBoolean("widget_show_controls", v).apply()
    var widgetShowProgress: Boolean get() = sp.getBoolean("widget_show_progress", true); set(v) = sp.edit().putBoolean("widget_show_progress", v).apply()
    var widgetShowCustomText: Boolean get() = sp.getBoolean("widget_show_custom_text", false); set(v) = sp.edit().putBoolean("widget_show_custom_text", v).apply()
    var widgetCustomText: String get() = sp.getString("widget_custom_text", "MusWall") ?: "MusWall"; set(v) = sp.edit().putString("widget_custom_text", v).apply()
    var widgetEmptyTitle: String get() = sp.getString("widget_empty_title", "No music playing") ?: "No music playing"; set(v) = sp.edit().putString("widget_empty_title", v).apply()
    var widgetTextColor: String get() = sp.getString("widget_text_color", "#FFFFFF") ?: "#FFFFFF"; set(v) = sp.edit().putString("widget_text_color", v).apply()
    var widgetSecondaryColor: String get() = sp.getString("widget_secondary_color", "#C7C7D0") ?: "#C7C7D0"; set(v) = sp.edit().putString("widget_secondary_color", v).apply()
    var widgetBackgroundColor: String get() = sp.getString("widget_background_color", "#17151F") ?: "#17151F"; set(v) = sp.edit().putString("widget_background_color", v).apply()
    var widgetTitleSize: Int get() = sp.getInt("widget_title_size", 15); set(v) = sp.edit().putInt("widget_title_size", v.coerceIn(10, 28)).apply()
    var widgetArtistSize: Int get() = sp.getInt("widget_artist_size", 12); set(v) = sp.edit().putInt("widget_artist_size", v.coerceIn(8, 22)).apply()
    var widgetCustomTextSize: Int get() = sp.getInt("widget_custom_text_size", 11); set(v) = sp.edit().putInt("widget_custom_text_size", v.coerceIn(8, 22)).apply()
    var widgetShowTime: Boolean get() = sp.getBoolean("widget_show_time", false); set(v) = sp.edit().putBoolean("widget_show_time", v).apply()
    var widgetCustomTimeLabel: String get() = sp.getString("widget_time_label", "Now Playing") ?: "Now Playing"; set(v) = sp.edit().putString("widget_time_label", v).apply()
    var widgetCornerRadius: Int get() = sp.getInt("widget_corner_radius", 22); set(v) = sp.edit().putInt("widget_corner_radius", v.coerceIn(0, 50)).apply()
    var widgetPadding: Int get() = sp.getInt("widget_padding", 10); set(v) = sp.edit().putInt("widget_padding", v.coerceIn(0, 30)).apply()
    var widgetArtworkSize: Int get() = sp.getInt("widget_artwork_size", 68); set(v) = sp.edit().putInt("widget_artwork_size", v.coerceIn(32, 140)).apply()
    var widgetLayout: Int get() = sp.getInt("widget_layout", 0); set(v) = sp.edit().putInt("widget_layout", v.coerceIn(0, 1)).apply()
}
