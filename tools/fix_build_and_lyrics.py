from pathlib import Path
import re

p = Path('app/src/main/java/com/muswall/app/ui/SettingsActivity.kt')
s = p.read_text(encoding='utf-8')
s = re.sub(r'private val customPhotoPicker=.*?\n', '''private val customPhotoPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){try{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Throwable){};prefs.customPhotoUri=uri.toString();prefs.photoSource=PreferencesManager.PHOTO_CUSTOM;updatePhotoLabels()}}\n''', s, count=1)
s = re.sub(r'private val originalWallpaperPicker=.*?\n', '''private val originalWallpaperPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){try{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Throwable){};ioScope.launch{val ok=wallpaperHelper.setOriginalFromUri(uri,originalTarget);ToastCompat.show(this@SettingsActivity,if(ok)"Original wallpaper saved" else "Could not save wallpaper")}}}\n''', s, count=1)
s = s.replace('if(uri==null){return};', 'if(uri!=null){')
s = s.replace('"else"', '" else "')
s = s.replace('AlertDialog.Builder(this).setTitle("Lyrics — live position, style & language").setView(root).setPositiveButton("Done",null)', 'AlertDialog.Builder(this).setTitle("Lyrics — live position, style & language").setView(root).setPositiveButton("Done"){_,_->prefs.lyricsX=x.value();prefs.lyricsY=y.value();prefs.lyricsWidth=w.value();prefs.lyricsSize=s.value();prefs.lyricsLines=l.value();updateLyricsLabel();refreshLive()}')
s = s.replace('AlertDialog.Builder(this).setTitle("Bass — live position & color").setView(root).setPositiveButton("Done",null)', 'AlertDialog.Builder(this).setTitle("Bass — live position & color").setView(root).setPositiveButton("Done"){_,_->prefs.bassX=x.value();prefs.bassY=y.value();prefs.bassWidth=w.value();prefs.bassHeight=h.value();prefs.bassSensitivity=se.value();updateBassLabel();refreshLive()}')
p.write_text(s, encoding='utf-8')

p = Path('app/src/main/java/com/muswall/app/service/MediaNotificationListenerService.kt')
s = p.read_text(encoding='utf-8')
s = s.replace('"else"', '" else "')
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
        val id=mediaId+"|"+title+"|"+artist+"|"+artUri
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
                if(prefs.showLyrics){prefs.lastLyrics=immediate?:"[00:00.00] Loading lyrics…";refreshLive()}
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
start = s.index('    private fun translateIfNeeded(')
end = s.index('    private fun fetchLyricsFromLrcLib(', start)
new_translation = '''    private fun translateIfNeeded(raw:String):String{
        val lang=prefs.lyricsLanguage.trim().lowercase()
        if(lang.isBlank()||lang=="original"||lang=="auto")return raw
        return try{
            val lines=raw.replace("\\r","").split('\\n')
            val out=ArrayList<String>()
            var i=0
            while(i<lines.size){
                val chunkLines=ArrayList<String>()
                var chars=0
                while(i<lines.size&&chars+lines[i].length<420){chunkLines+=lines[i];chars+=lines[i].length+1;i++}
                val prefixes=chunkLines.map{Regex("^(\\\\s*\\\\[[^]]+\\\\]\\\\s*)").find(it)?.value?:""}
                val texts=chunkLines.mapIndexed{idx,line->line.removePrefix(prefixes[idx])}
                val translated=translateChunk(texts.joinToString("\\n"),lang).split('\\n')
                if(translated.size==texts.size)chunkLines.forEachIndexed{idx,_->out+=prefixes[idx]+translated[idx]}else out+=chunkLines
            }
            out.joinToString("\\n")
        }catch(_:Throwable){raw}
    }
    private fun translateChunk(chunk:String,language:String):String{
        return try{
            val q=URLEncoder.encode(chunk,"UTF-8")
            val target=URLEncoder.encode(language,"UTF-8")
            val url=URL("https://api.mymemory.translated.net/get?q="+q+"&langpair=auto%7C"+target)
            val c=url.openConnection() as HttpURLConnection
            c.requestMethod="GET";c.connectTimeout=4000;c.readTimeout=5000;c.setRequestProperty("User-Agent","MusWall/4.0")
            try{
                if(c.responseCode !in 200..299)return chunk
                JSONObject(c.inputStream.bufferedReader().use{it.readText()}).optJSONObject("responseData")?.optString("translatedText")?.takeIf{it.isNotBlank()}?:chunk
            }finally{c.disconnect()}
        }catch(_:Throwable){chunk}
    }
'''
s = s[:start] + new_translation + s[end:]
p.write_text(s, encoding='utf-8')

p = Path('app/src/main/java/com/muswall/app/ui/WidgetSettingsActivity.kt')
s = p.read_text(encoding='utf-8')
s = s.replace('        palette.forEach { hex ->', '        lateinit var dialog: AlertDialog\n        palette.forEach { hex ->', 1)
s = s.replace('        lateinit var dialog: AlertDialog\n        dialog = AlertDialog.Builder(this)', '        dialog = AlertDialog.Builder(this)', 1)
p.write_text(s, encoding='utf-8')
print('Applied MusWall fixes')
