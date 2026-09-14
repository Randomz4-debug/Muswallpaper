package com.muswall.app.data

import android.content.Context

class PreferencesManager private constructor(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("muswall", Context.MODE_PRIVATE)

    companion object {
        @Volatile private var INSTANCE: PreferencesManager? = null
        fun getInstance(context: Context): PreferencesManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreferencesManager(context).also { INSTANCE = it }
            }

        const val MODE_MUSIC = "music"
        const val MODE_STATIC = "static"
        const val EFFECT_BLUR = "blur"
        const val EFFECT_COVER = "cover"
        const val EFFECT_CD = "cd"
        const val EFFECT_SQUARE = "square"
        const val EFFECT_COVER_COLOR = "cover_color"
        const val BLUR_GAUSSIAN = "gaussian"
        const val BLUR_SOLID = "solid"
        const val BLUR_MOTION = "motion"
        const val BLUR_GLASS = "glass"
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
    }

    var isAutoEnabled: Boolean
        get() = sp.getBoolean("auto", true)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    var restoreOnPause: Boolean
        get() = sp.getBoolean("restore_pause", true)
        set(v) = sp.edit().putBoolean("restore_pause", v).apply()

    var wallpaperMode: String
        get() = sp.getString("mode", MODE_MUSIC) ?: MODE_MUSIC
        set(v) = sp.edit().putString("mode", v).apply()

    var staticWallpaperUri: String
        get() = sp.getString("static_uri", "") ?: ""
        set(v) = sp.edit().putString("static_uri", v).apply()

    var targetScreen: String
        get() = sp.getString("target_screen", TARGET_BOTH) ?: TARGET_BOTH
        set(v) = sp.edit().putString("target_screen", v).apply()

    var originalHomeWallpaperUri: String
        get() = sp.getString("original_home_uri", "") ?: ""
        set(v) = sp.edit().putString("original_home_uri", v).apply()

    var originalLockWallpaperUri: String
        get() = sp.getString("original_lock_uri", "") ?: ""
        set(v) = sp.edit().putString("original_lock_uri", v).apply()

    var originalBackedUp: Boolean
        get() = sp.getBoolean("original_backed_up", false)
        set(v) = sp.edit().putBoolean("original_backed_up", v).apply()

    var lastArtworkPath: String
        get() = sp.getString("last_artwork_path", "") ?: ""
        set(v) = sp.edit().putString("last_artwork_path", v).apply()

    var liveWallpaperEnabled: Boolean
        get() = sp.getBoolean("live_enabled", false)
        set(v) = sp.edit().putBoolean("live_enabled", v).apply()

    var liveMusicPlaying: Boolean
        get() = sp.getBoolean("live_playing", false)
        set(v) = sp.edit().putBoolean("live_playing", v).apply()

    var lastTrackTitle: String
        get() = sp.getString("last_title", "") ?: ""
        set(v) = sp.edit().putString("last_title", v).apply()

    var lastArtist: String
        get() = sp.getString("last_artist", "") ?: ""
        set(v) = sp.edit().putString("last_artist", v).apply()

    var effect: String
        get() = sp.getString("effect", EFFECT_COVER) ?: EFFECT_COVER
        set(v) = sp.edit().putString("effect", v).apply()

    var blurType: String
        get() = sp.getString("blur_type", BLUR_GAUSSIAN) ?: BLUR_GAUSSIAN
        set(v) = sp.edit().putString("blur_type", v).apply()

    var blurRadius: Int
        get() = sp.getInt("blur_radius", 24)
        set(v) = sp.edit().putInt("blur_radius", v.coerceIn(0, 80)).apply()

    var darkness: Int
        get() = sp.getInt("darkness", 20)
        set(v) = sp.edit().putInt("darkness", v.coerceIn(0, 100)).apply()

    var artScale: Int
        get() = sp.getInt("art_scale", 82)
        set(v) = sp.edit().putInt("art_scale", v.coerceIn(20, 120)).apply()

    var coverHeight: Int
        get() = sp.getInt("cover_height", 46)
        set(v) = sp.edit().putInt("cover_height", v.coerceIn(10, 90)).apply()

    var coverOffset: Int
        get() = sp.getInt("cover_offset", 0)
        set(v) = sp.edit().putInt("cover_offset", v.coerceIn(-50, 50)).apply()

    var transitionHeight: Int
        get() = sp.getInt("transition_height", 28)
        set(v) = sp.edit().putInt("transition_height", v.coerceIn(0, 100)).apply()

    var backgroundMode: String
        get() = sp.getString("background_mode", BACKGROUND_ART) ?: BACKGROUND_ART
        set(v) = sp.edit().putString("background_mode", v).apply()

    var backgroundColor: String
        get() = sp.getString("background_color", "#101010") ?: "#101010"
        set(v) = sp.edit().putString("background_color", v).apply()

    var backgroundColor2: String
        get() = sp.getString("background_color2", "#303030") ?: "#303030"
        set(v) = sp.edit().putString("background_color2", v).apply()

    var accentColor: String
        get() = sp.getString("accent_color", "#FFFFFF") ?: "#FFFFFF"
        set(v) = sp.edit().putString("accent_color", v).apply()

    var showLyrics: Boolean
        get() = sp.getBoolean("show_lyrics", false)
        set(v) = sp.edit().putBoolean("show_lyrics", v).apply()

    var photoSource: String
        get() = sp.getString("photo_source", PHOTO_AUTO) ?: PHOTO_AUTO
        set(v) = sp.edit().putString("photo_source", v).apply()

    var customPhotoUri: String
        get() = sp.getString("custom_photo_uri", "") ?: ""
        set(v) = sp.edit().putString("custom_photo_uri", v).apply()

    var photoFallback: Boolean
        get() = sp.getBoolean("photo_fallback", true)
        set(v) = sp.edit().putBoolean("photo_fallback", v).apply()

    var restoreDelay: Int
        get() = sp.getInt("restore_delay", 0)
        set(v) = sp.edit().putInt("restore_delay", v).apply()
}
