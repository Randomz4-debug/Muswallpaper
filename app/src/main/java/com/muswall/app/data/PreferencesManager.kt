package com.muswall.app.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("muswall_preferences", Context.MODE_PRIVATE)

    companion object {
        const val TARGET_BOTH = "BOTH"
        const val TARGET_HOME = "HOME"
        const val TARGET_LOCK = "LOCK"
        fun getInstance(context: Context) = PreferencesManager(context)
    }

    var isAutoEnabled: Boolean
        get() = prefs.getBoolean("auto_enabled", true)
        set(v) = prefs.edit().putBoolean("auto_enabled", v).apply()

    var blurRadius: Int
        get() = prefs.getInt("blur_radius", 35)
        set(v) = prefs.edit().putInt("blur_radius", v.coerceIn(0, 100)).apply()

    var darkness: Int
        get() = prefs.getInt("darkness", 45)
        set(v) = prefs.edit().putInt("darkness", v.coerceIn(0, 100)).apply()

    var artScale: Int
        get() = prefs.getInt("art_scale", 72)
        set(v) = prefs.edit().putInt("art_scale", v.coerceIn(30, 95)).apply()

    var targetScreen: String
        get() = prefs.getString("target_screen", TARGET_BOTH) ?: TARGET_BOTH
        set(v) = prefs.edit().putString("target_screen", v).apply()

    var restoreOnPause: Boolean
        get() = prefs.getBoolean("restore_on_pause", true)
        set(v) = prefs.edit().putBoolean("restore_on_pause", v).apply()

    var isOriginalBackedUp: Boolean
        get() = prefs.getBoolean("originals_backed_up", false)
        set(v) = prefs.edit().putBoolean("originals_backed_up", v).apply()

    var hasSeparateLockWallpaper: Boolean
        get() = prefs.getBoolean("has_separate_lock", false)
        set(v) = prefs.edit().putBoolean("has_separate_lock", v).apply()

    var lastTrackTitle: String
        get() = prefs.getString("last_track", "") ?: ""
        set(v) = prefs.edit().putString("last_track", v).apply()

    var lastArtist: String
        get() = prefs.getString("last_artist", "") ?: ""
        set(v) = prefs.edit().putString("last_artist", v).apply()
}