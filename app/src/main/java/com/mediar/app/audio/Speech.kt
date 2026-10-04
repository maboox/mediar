package com.mediar.app.audio

import android.content.Context
import android.media.*
import android.os.*
import android.speech.tts.*
import com.mediar.app.store
import java.io.File
import java.util.Locale

/** One audio owner per process prevents simultaneous scan and alarm speech. */
object AudioBus {
    private var owner=java.lang.ref.WeakReference<Speech>(null)
    @Synchronized fun claim(s: Speech){val previous=owner.get();if(previous!==s)previous?.stop();owner=java.lang.ref.WeakReference(s)}
    @Synchronized fun release(s: Speech){if(owner.get()===s)owner.clear()}
}
class Speech(context: Context) {
    private val context=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private var engine: TextToSpeech?=null
    private var ready=false
    private var initialized=false
    private var pending: (() -> Unit)?=null
    private var player: MediaPlayer?=null
    private var recorder: MediaRecorder?=null
    private var recording: File?=null
    private var recordingKey: String?=null
    private var done: (() -> Unit)?=null
    private var unavailable: (() -> Unit)?=null
    private var played=false
    var offline=false; private set
    private val db get()=context.store()
    private val audio=context.getSystemService(AudioManager::class.java)
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).setOnAudioFocusChangeListener { if(it==AudioManager.AUDIOFOCUS_LOSS)stop() }.build()
    init {
        engine=TextToSpeech(context.applicationContext){status -> main.post {
            initialized=true;ready=status==TextToSpeech.SUCCESS
            if(ready)refresh()
            pending?.invoke();pending=null
        }}
        engine?.setAudioAttributes(attributes)
        engine?.setOnUtteranceProgressListener(object: UtteranceProgressListener(){
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {main.post {finish()}}
            @Deprecated("legacy callback") override fun onError(id: String?) {main.post {finish()}}
        })
    }
    fun refresh() {
        if(!ready)return
        val lang=db.get("language","fa")
        val locale=if(lang=="en")Locale.ENGLISH else Locale("fa")
        val voice=engine?.voices?.filter {it.locale.language==locale.language && !it.isNetworkConnectionRequired}?.sortedByDescending {it.quality}?.firstOrNull()
        offline=voice!=null
        if(voice!=null){engine?.language=locale;engine?.voice=voice}
    }
    fun file(key: String): File {require(key.matches(Regex("[a-z_]{2,40}")));return File(File(context.filesDir,"voice").apply{mkdirs()},"$key.m4a")}
    fun speak(text: String, clip: String?=null, onUnavailable: ()->Unit = {}, completed: ()->Unit = {}) {
        stop();AudioBus.claim(this);done=completed;unavailable=onUnavailable;played=false
        if(db.get("mute","false")=="true"){finish();return}
        val action={refresh();audio.requestAudioFocus(focus);val f=clip?.let(::file)
            if(f?.isFile==true){played=true;playFile(f){say(text)}} else say(text)
        }
        if(initialized)action() else pending=action
    }
    private fun say(text: String) {
        if(!offline || text.isBlank()){if(!offline && !played)unavailable?.invoke();finish();return}
        val volume=db.get("volume","1").toFloatOrNull()?.coerceIn(0f,1f)?:1f
        engine?.setSpeechRate(db.get("speed","0.9").toFloatOrNull()?.coerceIn(.5f,1.5f)?:.9f)
        if(engine?.speak(text,TextToSpeech.QUEUE_FLUSH,Bundle().apply {putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,volume)},"mediar-${System.nanoTime()}")==TextToSpeech.ERROR) finish()
    }
    private fun playFile(f: File, after: ()->Unit) {
        runCatching {
            val p=MediaPlayer();player=p;p.setAudioAttributes(attributes);p.setDataSource(f.absolutePath)
            val v=db.get("volume","1").toFloatOrNull()?.coerceIn(0f,1f)?:1f;p.setVolume(v,v)
            p.setOnCompletionListener {it.release();player=null;after()}
            p.setOnErrorListener {m,_,_->m.release();player=null;after();true}
            p.prepare();p.start()
        }.onFailure {player?.release();player=null;after()}
    }
    fun play(key: String) {stop();AudioBus.claim(this);audio.requestAudioFocus(focus);if(file(key).exists())playFile(file(key)){finish()}}
    fun stop(){pending=null;done=null;unavailable=null;player?.release();player=null;engine?.stop();audio.abandonAudioFocusRequest(focus)}
    private fun finish(){val callback=done;done=null;audio.abandonAudioFocusRequest(focus);callback?.invoke()}
    @Suppress("DEPRECATION") fun record(key: String) {
        stop();cancelRecording()
        val temp=File(context.cacheDir,"voice_${System.nanoTime()}.m4a")
        val r=if(Build.VERSION.SDK_INT>=31)MediaRecorder(context) else MediaRecorder()
        recorder=r;recording=temp;recordingKey=key
        try {r.setAudioSource(MediaRecorder.AudioSource.MIC);r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);r.setMaxDuration(12_000);r.setOutputFile(temp.absolutePath);r.prepare();r.start()}
        catch(e:Exception){cancelRecording();throw e}
    }
    fun saveRecording(): Boolean {
        val r=recorder ?: return false
        val ok=runCatching {r.stop()}.isSuccess;r.release();recorder=null
        val f=recording;val key=recordingKey;recording=null;recordingKey=null
        if(ok && f!=null && key!=null && f.length()>100){val dest=file(key);if(!f.renameTo(dest)){f.copyTo(dest,overwrite=true);f.delete()};return true}
        f?.delete();return false
    }
    fun cancelRecording(){recorder?.let {runCatching {it.stop()};it.release()};recorder=null;recording?.delete();recording=null;recordingKey=null}
    fun release(){cancelRecording();stop();engine?.shutdown();AudioBus.release(this)}
}
