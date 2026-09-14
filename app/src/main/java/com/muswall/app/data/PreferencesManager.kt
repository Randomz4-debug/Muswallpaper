package com.muswall.app.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("muswall_preferences", Context.MODE_PRIVATE)

    companion object {
        const val TARGET_BOTH = "BOTH"
        const val TARGET_HOME = "HOME"
        const val TARGET_LOCK = "LOCK"

        const val MODE_MUSIC = "MUSIC"
        const val MODE_STATIC = "STATIC"

        const val EFFECT_BLUR = "BLUR"
        const val EFFECT_COVER = "COVER"
        const val EFFECT_CD = "CD"
        const val EFFECT_SQUARE = "SQUARE"
        const val EFFECT_COVER_COLOR = "COVER_COLOR"

        const val BLUR_GAUSSIAN = "GAUSSIAN"
        const val BLUR_SOLID = "SOLID"
        const val BLUR_MOTION = "MOTION"
        const val BLUR_GLASS = "GLASS"

        const val BACKGROUND_ART = "ART"
        const val BACKGROUND_COLOR = "COLOR"
        const val BACKGROUND_GRADIENT = "GRADIENT"
        const val BACKGROUND_AUTO = "AUTO"

        fun getInstance(context: Context) = PreferencesManager(context.applicationContext)
    }

    var isAutoEnabled: Boolean
        get() = prefs.getBoolean("auto_enabled", true)
        set(v) = prefs.edit().putBoolean("auto_enabled", v).apply()

    var restoreOnPause: Boolean
        get() = prefs.getBoolean("restore_on_pause", true)
        set(v) = prefs.edit().putBoolean("restore_on_pause", v).apply()

    var wallpaperMode: String
        get() = prefs.getString("wallpaper_mode", MODE_MUSIC) ?: MODE_MUSIC
        set(v) = prefs.edit().putString("wallpaper_mode", v).apply()

    var effect: String
        get() = prefs.getString("effect", EFFECT_BLUR) ?: EFFECT_BLUR
        set(v) = prefs.edit().putString("effect", v).apply()

    var blurType: String
        get() = prefs.getString("blur_type", BLUR_GAUSSIAN) ?: BLUR_GAUSSIAN
        set(v) = prefs.edit().putString("blur_type", v).apply()

    var blurRadius: Int
        get() = prefs.getInt("blur_radius", 80)
        set(v) = prefs.edit().putInt("blur_radius", v.coerceIn(0, 100)).apply()

    var darkness: Int
        get() = prefs.getInt("darkness", 0)
        set(v) = prefs.edit().putInt("darkness", v.coerceIn(0, 100)).apply()

    var artScale: Int
        get() = prefs.getInt("art_scale", 72)
        set(v) = prefs.edit().putInt("art_scale", v.coerceIn(20, 100)).apply()

    var coverHeight: Int
        get() = prefs.getInt("cover_height", 44)
        set(v) = prefs.edit().putInt("cover_height", v.coerceIn(0, 100)).apply()

    var coverOffset: Int
        get() = prefs.getInt("cover_offset", 50)
        set(v) = prefs.edit().putInt("cover_offset", v.coerceIn(0, 100)).apply()

    var transitionHeight: Int
        get() = prefs.getInt("transition_height", 20)
        set(v) = prefs.edit().putInt("transition_height", v.coerceIn(0, 100)).apply()

    var targetScreen: String
        get() = prefs.getString("target_screen", TARGET_BOTH) ?: TARGET_BOTH
        set(v) = prefs.edit().putString("target_screen", v).apply()

    var liveWallpaperEnabled: Boolean
        get() = prefs.getBoolean("live_wallpaper_enabled", false)
        set(v) = prefs.edit().putBoolean("live_wallpaper_enabled", v).apply()

    var liveMusicPlaying: Boolean
        get() = prefs.getBoolean("live_music_playing", false)
        set(v) = prefs.edit().putBoolean("live_music_playing", v).apply()

    var originalHomeWallpaperUri: String
        get() = prefs.getString("original_home_uri", "") ?: ""
        set(v) = prefs.edit().putString("original_home_uri", v).apply()

    var originalLockWallpaperUri: String
        get() = prefs.getString("original_lock_uri", "") ?: ""
        set(v) = prefs.edit().putString("original_lock_uri", v).apply()

    var originalBackedUp: Boolean
        get() = prefs.getBoolean("originals_backed_up", false)
        set(v) = prefs.edit().putBoolean("originals_backed_up", v).apply()

    var backgroundMode: String
        get() = prefs.getString("background_mode", BACKGROUND_ART) ?: BACKGROUND_ART
        set(v) = prefs.edit().putString("background_mode", v).apply()

    var backgroundColor: String
        get() = prefs.getString("background_color", "#111111") ?: "#111111"
        set(v) = prefs.edit().putString("background_color", v).apply()

    var backgroundColor2: String
        get() = prefs.getString("background_color_2", "#5E2CA5") ?: "#5E2CA5"
        set(v) = prefs.edit().putString("background_color_2", v).apply()

    var accentColor: String
        get() = prefs.getString("accent_color", "#7C00FF") ?: "#7C00FF"
        set(v) = prefs.edit().putString("accent_color", v).apply()

    var lastTrackTitle: String
        get() = prefs.getString("last_track", "") ?: ""
        set(v) = prefs.edit().putString("last_track", v).apply()

    var lastArtist: String
        get() = prefs.getString("last_artist", "") ?: ""
        set(v) = prefs.edit().putString("last_artist", v).apply()

    var lastArtworkPath: String
        get() = prefs.getString("last_artwork_path", "") ?: ""
        set(v) = prefs.edit().putString("last_artwork_path", v).apply()

    var staticWallpaperUri: String
        get() = prefs.getString("static_uri", "") ?: ""
        set(v) = prefs.edit().putString("static_uri", v).apply()
}
