from pathlib import Path

# Fix SettingsActivity persistence and Kotlin parser issues.
p = Path('app/src/main/java/com/muswall/app/ui/SettingsActivity.kt')
s = p.read_text(encoding='utf-8')
s = s.replace('if(uri==null)return@registerForActivityResult;', 'if(uri==null){return};')
s = s.replace('AlertDialog.Builder(this).setTitle("Lyrics — live position, style & language").setView(root).setPositiveButton("Done",null)', 'AlertDialog.Builder(this).setTitle("Lyrics — live position, style & language").setView(root).setPositiveButton("Done"){_,_->prefs.lyricsX=x.value();prefs.lyricsY=y.value();prefs.lyricsWidth=w.value();prefs.lyricsSize=s.value();prefs.lyricsLines=l.value();updateLyricsLabel();refreshLive()}')
s = s.replace('AlertDialog.Builder(this).setTitle("Bass — live position & color").setView(root).setPositiveButton("Done",null)', 'AlertDialog.Builder(this).setTitle("Bass — live position & color").setView(root).setPositiveButton("Done"){_,_->prefs.bassX=x.value();prefs.bassY=y.value();prefs.bassWidth=w.value();prefs.bassHeight=h.value();prefs.bassSensitivity=se.value();updateBassLabel();refreshLive()}')
p.write_text(s, encoding='utf-8')

# Make lyric loading non-blocking: artwork/wallpaper is shown immediately while lyrics resolve.
p = Path('app/src/main/java/com/muswall/app/service/MediaNotificationListenerService.kt')
s = p.read_text(encoding='utf-8')
start = s.index('    private fun handleMetadata(')
end = s.index('    private fun refreshLive()', start)
new_handle = '''    private fun handleMetadata(metadata:MediaMetadata?,force:Boolean=false){
        if(metadata==null)return
        val title=metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty().ifEmpty{"Unknown title"}
        val artist=metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty().ifEmpty{"Unknown artist"}
        prefs.lastTrackTitle=title
        prefs.lastArtist=artist
        prefs.lastDuration=metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        broadcastTrack(title,artist)
        val artUri=metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)?:metadata.getString(MediaMetadata.METADATA_KEY_ART_URI).orEmpty()
        val mediaId=metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val id=mediaId+"\\u0000"+title+"\\u0000"+artist+"\\u0000"+artUri
        if(!force&&id==currentTrackId)return
        currentTrackId=id
        prefs.lyricsPosition=activeController?.playbackState?.position?.coerceAtLeast(0L)?:prefs.lyricsPosition
        if(!prefs.isAutoEnabled||!playing||prefs.wallpaperMode!=PreferencesManager.MODE_MUSIC)return
        if(!prefs.liveWallpaperEnabled){broadcastWallpaperApplied("Music detected • enable MusWall Live Wallpaper once");return}
        val token=generation.incrementAndGet()
        generationJob?.cancel()
        generationJob=scope.launch(Dispatchers.Default){
            val art=extractArtwork(metadata)
            try{
                val immediate=findImmediateLyrics(metadata)
                if(prefs.showLyrics){
                    prefs.lastLyrics=immediate?:"[00:00.00] Loading lyrics…"
                    refreshLive()
                }
                // Never wait for LRCLIB or translation before showing the wallpaper.
                if(art!=null)renderAndSendToLiveWallpaper(art,token,prefs.lastLyrics)else refreshLive()
                if(prefs.showLyrics){
                    val resolved=if(immediate!=null)immediate else resolveLyrics(metadata,title,artist)
                    if(token==generation.get()&&playing){
                        if(resolved.isNotBlank())prefs.lastLyrics=translateIfNeeded(resolved)
                        else if(prefs.lastLyrics.contains("Loading lyrics"))prefs.lastLyrics="Lyrics unavailable for this track"
                        refreshLive()
                    }
                }
            }finally{art?.let{if(!it.isRecycled)it.recycle()}}
        }
    }
    private fun findImmediateLyrics(metadata:MediaMetadata):String?{
        val direct=metadata.getString("android.media.metadata.LYRICS")?.trim().orEmpty()
        if(hasLrcTimestamps(direct))return direct
        for(key in metadata.keySet()){
            val value=metadata.getString(key)?.trim().orEmpty()
            if(key.contains("lyric",true)&&hasLrcTimestamps(value))return value
        }
        return null
    }
'''
s = s[:start] + new_handle + s[end:]
s = s.replace('val url=URL("https://api.mymemory.translated.net/get?q=$q&langpair=auto|${URLEncoder.encode(language,"UTF-8")}")', 'val target=URLEncoder.encode(language,"UTF-8");val url=URL("https://api.mymemory.translated.net/get?q="+q+"&langpair=auto%7C"+target)')
p.write_text(s, encoding='utf-8')
print('Applied MusWall slider persistence + non-blocking lyrics fixes')
