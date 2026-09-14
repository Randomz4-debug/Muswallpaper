package com.muswall.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.muswall.app.data.PreferencesManager
import com.muswall.app.wallpaper.MusicWallpaperService
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Universal lyrics fallback.
 *
 * The media listener supplies the title/artist from whatever music app is playing
 * (Spotify, YouTube Music, Apple Music, Amazon Music, VLC, Poweramp, local players, etc.).
 * This receiver then searches independent lyrics providers so the app does not depend
 * on a specific music player's lyrics implementation.
 */
class UniversalLyricsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MediaNotificationListenerService.ACTION_TRACK_CHANGED) return

        val title = intent.getStringExtra(MediaNotificationListenerService.EXTRA_TRACK_TITLE)
            ?.trim().orEmpty()
        val artist = intent.getStringExtra(MediaNotificationListenerService.EXTRA_ARTIST)
            ?.trim().orEmpty()
        if (title.isBlank() || artist.isBlank() || title == "Unknown title") return

        val pending = goAsync()
        Thread {
            try {
                Thread.sleep(450L)
                val prefs = PreferencesManager.getInstance(context)
                if (prefs.lastTrackTitle != title || prefs.lastArtist != artist) return@Thread

                val lyrics = fetchLyrics(title, artist)
                if (!lyrics.isNullOrBlank()) {
                    // Do not overwrite a newer track that started while the request was running.
                    if (prefs.lastTrackTitle == title && prefs.lastArtist == artist) {
                        prefs.lastLyrics = lyrics
                        context.sendBroadcast(
                            Intent(MusicWallpaperService.ACTION_REFRESH)
                                .setPackage(context.packageName)
                        )
                    }
                }
            } catch (_: Throwable) {
                // Lyrics are optional; never let a network failure affect playback or wallpaper.
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun fetchLyrics(title: String, artist: String): String? {
        // 1) LRCLIB: prefers synchronized lyrics and also has a large plain-lyrics fallback.
        fetchLrcLib(title, artist, synced = true)?.let { return it }
        fetchLrcLib(title, artist, synced = false)?.let { return it }

        // 2) lyrics.ovh: simple plain-lyrics fallback for songs not indexed by LRCLIB.
        fetchLyricsOvh(title, artist)?.let { return it }

        return null
    }

    private fun fetchLrcLib(title: String, artist: String, synced: Boolean): String? {
        val qTitle = URLEncoder.encode(title, "UTF-8")
        val qArtist = URLEncoder.encode(artist, "UTF-8")
        val url = URL(
            "https://lrclib.net/api/search?track_name=$qTitle&artist_name=$qArtist"
        )
        val json = httpGet(url) ?: return null
        val array = try { JSONArray(json) } catch (_: Throwable) { return null }
        if (array.length() == 0) return null

        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val track = item.optString("trackName").trim()
            val itemArtist = item.optString("artistName").trim()
            val text = if (synced) item.optString("syncedLyrics") else item.optString("plainLyrics")
            if (text.isBlank()) continue

            var score = 0
            if (track.equals(title, ignoreCase = true)) score += 100
            else if (normalize(track) == normalize(title)) score += 80
            if (itemArtist.equals(artist, ignoreCase = true)) score += 80
            else if (normalize(itemArtist) == normalize(artist)) score += 60
            if (synced && text.contains("[00:")) score += 10

            if (score > bestScore) {
                bestScore = score
                best = item
            }
        }

        val chosen = best ?: return null
        return chosen.optString(if (synced) "syncedLyrics" else "plainLyrics")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun fetchLyricsOvh(title: String, artist: String): String? {
        val qArtist = URLEncoder.encode(artist, "UTF-8")
        val qTitle = URLEncoder.encode(title, "UTF-8")
        val url = URL("https://api.lyrics.ovh/v1/$qArtist/$qTitle")
        val json = httpGet(url) ?: return null
        return try {
            JSONObject(json).optString("lyrics").trim().takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun httpGet(url: URL): String? {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 7000
            setRequestProperty("User-Agent", "MusWall/4.0 (https://github.com/Randomz4-debug/Muswallpaper)")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("\\([^)]*\\)|\\[[^]]*\\]"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}
