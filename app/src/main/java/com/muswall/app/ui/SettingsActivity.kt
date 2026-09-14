package com.muswall.app.ui

import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import com.muswall.app.R
import com.muswall.app.data.PreferencesManager
import com.muswall.app.wallpaper.WallpaperHelper
import kotlinx.coroutines.*

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: PreferencesManager
    private lateinit var wallpaperHelper: WallpaperHelper
    private val ioScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val customPhotoPicker=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()){uri->
        if(uri==null)return@registerForActivityResult
        try{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Throwable){}
        prefs.customPhotoUri=uri.toString();prefs.photoSource=PreferencesManager.PHOTO_CUSTOM
        findViewById<TextView>(R.id.photoSourceRow).text="Music photo\nCustom image"
        ToastCompat.show(this,"Custom music photo selected")
    }
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);prefs=PreferencesManager.getInstance(this);wallpaperHelper=WallpaperHelper(this);setContentView(R.layout.activity_settings)
        findViewById<TextView>(R.id.back).setOnClickListener{finish()};findViewById<TextView>(R.id.themeRow).setOnClickListener{chooseTheme()};findViewById<TextView>(R.id.languageRow).setOnClickListener{chooseLanguage()};findViewById<TextView>(R.id.backgroundRow).setOnClickListener{chooseBackgroundMode()};findViewById<TextView>(R.id.backgroundColorRow).setOnClickListener{chooseColor(false)};findViewById<TextView>(R.id.gradientColorRow).setOnClickListener{chooseColor(true)};findViewById<TextView>(R.id.accentColorRow).setOnClickListener{chooseAccentColor()};findViewById<TextView>(R.id.restoreHomeRow).setOnClickListener{chooseOriginalWallpaper(WallpaperManager.FLAG_SYSTEM)};findViewById<TextView>(R.id.restoreLockRow).setOnClickListener{chooseOriginalWallpaper(WallpaperManager.FLAG_LOCK)};findViewById<TextView>(R.id.timingRow).setOnClickListener{chooseTiming()};findViewById<TextView>(R.id.permissionsRow).setOnClickListener{XiaomiHelper.openNotificationListenerSettings(this)};findViewById<TextView>(R.id.serviceRow).setOnClickListener{XiaomiHelper.openNotificationListenerSettings(this)}
        findViewById<TextView>(R.id.compatibilityRow).setOnClickListener{ToastCompat.show(this,"Compatibility mode uses the fast live-wallpaper renderer.")};findViewById<TextView>(R.id.feedbackRow).setOnClickListener{runCatching{startActivity(Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:")))}};findViewById<TextView>(R.id.faqRow).setOnClickListener{AlertDialog.Builder(this).setTitle("MusWall FAQ").setMessage("Notification access detects music. Album Art, Track Art, Display Icon, Notification Art and Custom Image can be selected as the music photo. Lyrics are shown only when the music player exposes lyric metadata.").setPositiveButton("OK",null).show()};findViewById<TextView>(R.id.updateRow).setOnClickListener{AlertDialog.Builder(this).setTitle("MusWall").setMessage("Version 1.9.6").setPositiveButton("OK",null).show()};findViewById<TextView>(R.id.widgetRow).setOnClickListener{ToastCompat.show(this,"Widget customization is not required for the live wallpaper.")}
        findViewById<TextView>(R.id.photoSourceRow).setOnClickListener{choosePhotoSource()};findViewById<TextView>(R.id.customPhotoRow).setOnClickListener{customPhotoPicker.launch(arrayOf("image/*"))}
        findViewById<SwitchCompat>(R.id.lyricsSwitch).apply{showText=false;textOn="";textOff="";isChecked=prefs.showLyrics;setOnCheckedChangeListener{_,v->prefs.showLyrics=v}}
        findViewById<SwitchCompat>(R.id.photoFallbackSwitch).apply{showText=false;textOn="";textOff="";isChecked=prefs.photoFallback;setOnCheckedChangeListener{_,v->prefs.photoFallback=v}}
        findViewById<SwitchCompat>(R.id.effectsRestore).apply{showText=false;textOn="";textOff="";isChecked=prefs.effect!=PreferencesManager.EFFECT_BLUR;setOnCheckedChangeListener{_,v->if(!v)prefs.effect=PreferencesManager.EFFECT_BLUR}}
        updatePhotoLabels()
    }
    private fun updatePhotoLabels(){val name=when(prefs.photoSource){PreferencesManager.PHOTO_ART->"Track artwork (ART)";PreferencesManager.PHOTO_DISPLAY_ICON->"Display icon";PreferencesManager.PHOTO_NOTIFICATION->"Notification artwork";PreferencesManager.PHOTO_CUSTOM->"Custom image";else->"Album artwork (ALBUM_ART)"};findViewById<TextView>(R.id.photoSourceRow).text="Music photo\n$name"}
    private fun choosePhotoSource(){val labels=arrayOf("Album artwork — standard album cover","Track artwork — player ART field","Display icon — player display icon","Notification artwork — notification large icon","Custom image — one image for every track");val values=arrayOf(PreferencesManager.PHOTO_ALBUM,PreferencesManager.PHOTO_ART,PreferencesManager.PHOTO_DISPLAY_ICON,PreferencesManager.PHOTO_NOTIFICATION,PreferencesManager.PHOTO_CUSTOM);AlertDialog.Builder(this).setTitle("Which music photo should be used?").setItems(labels){_,i->if(values[i]==PreferencesManager.PHOTO_CUSTOM&&prefs.customPhotoUri.isBlank()){customPhotoPicker.launch(arrayOf("image/*"))}else{prefs.photoSource=values[i];updatePhotoLabels();ToastCompat.show(this,"Music photo source saved")}}.show()}
    private fun chooseTheme(){AlertDialog.Builder(this).setTitle("Theme").setItems(arrayOf("Follow System","Light","Dark")){_,w->AppCompatDelegate.setDefaultNightMode(when(w){1->AppCompatDelegate.MODE_NIGHT_NO;2->AppCompatDelegate.MODE_NIGHT_YES;else->AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM})}.show()}
    private fun chooseLanguage(){AlertDialog.Builder(this).setTitle("Language").setMessage("System Default\n\nThe interface follows your Android language.").setPositiveButton("OK",null).show()}
    private fun chooseBackgroundMode(){val v=arrayOf("Album artwork (blurred)","Solid color","Gradient","Auto color from album");AlertDialog.Builder(this).setTitle("Wallpaper background").setItems(v){_,w->prefs.backgroundMode=when(w){1->PreferencesManager.BACKGROUND_COLOR;2->PreferencesManager.BACKGROUND_GRADIENT;3->PreferencesManager.BACKGROUND_AUTO;else->PreferencesManager.BACKGROUND_ART};ToastCompat.show(this,"Background style saved")}.show()}
    private fun chooseColor(second:Boolean){val names=arrayOf("Black #111111","White #F7F7F7","Purple #5E2CA5","Blue #174EA6","Green #176B3A","Red #8B1E2D","Orange #B85C00");val colors=arrayOf("#111111","#F7F7F7","#5E2CA5","#174EA6","#176B3A","#8B1E2D","#B85C00");AlertDialog.Builder(this).setTitle(if(second)"Gradient end color"else"Background color").setItems(names){_,w->if(second)prefs.backgroundColor2=colors[w]else prefs.backgroundColor=colors[w];ToastCompat.show(this,"Color saved")}.show()}
    private fun chooseAccentColor(){val names=arrayOf("Purple #7C00FF","Cyan #00B8D9","Green #36C95F","Blue #4285F4","Pink #E91E63","White #FFFFFF");val colors=arrayOf("#7C00FF","#00B8D9","#36C95F","#4285F4","#E91E63","#FFFFFF");AlertDialog.Builder(this).setTitle("Cover accent").setItems(names){_,w->prefs.accentColor=colors[w];ToastCompat.show(this,"Accent color saved")}.show()}
    private fun chooseOriginalWallpaper(which:Int){val code=if(which==WallpaperManager.FLAG_LOCK)2003 else 2002;startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="image/*";addCategory(Intent.CATEGORY_OPENABLE);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)},code)}
    private fun chooseTiming(){val names=arrayOf("Immediately","5 seconds","10 seconds","30 seconds");AlertDialog.Builder(this).setTitle("Restore timing").setItems(names){_,w->prefs.restoreDelay=intArrayOf(0,5,10,30)[w];ToastCompat.show(this,"Restore timing saved")}.show()}
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(resultCode!=RESULT_OK)return;val uri=data?.data?:return;try{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Throwable){};val which=when(requestCode){2002->WallpaperManager.FLAG_SYSTEM;2003->WallpaperManager.FLAG_LOCK;else->return};ioScope.launch{val ok=wallpaperHelper.setOriginalFromUri(uri,which);ToastCompat.show(this@SettingsActivity,if(ok)"Original ${if(which==WallpaperManager.FLAG_LOCK)"lock"else"home"} wallpaper saved"else"Could not save wallpaper")}}
    override fun onDestroy(){ioScope.cancel();super.onDestroy()}
    object ToastCompat{fun show(c:android.content.Context,m:String)=android.widget.Toast.makeText(c,m,android.widget.Toast.LENGTH_LONG).show()}
}
