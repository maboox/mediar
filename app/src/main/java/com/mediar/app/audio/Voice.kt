package com.mediar.app.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.Locale

class Voice(private val ctx:Context) {
    private var tts:TextToSpeech?=null
    private var player:MediaPlayer?=null
    private var recorder:MediaRecorder?=null
    @Volatile var offline=false;private set
    private fun lang()=if(ctx.getSharedPreferences("ui",0).getString("lang","fa")=="en")Locale.ENGLISH else Locale("fa")
    init { tts=TextToSpeech(ctx) { status->if(status==TextToSpeech.SUCCESS) android.os.Handler(android.os.Looper.getMainLooper()).post {refreshLanguage()} } }
    fun refreshLanguage() {val engine=tts?:return;engine.language=lang();val selected=engine.voices?.firstOrNull {it.locale.language==lang().language && !it.isNetworkConnectionRequired};offline=selected!=null;if(selected!=null)engine.voice=selected}
    fun file(key:String):File {require(key.matches(Regex("[a-z_]{2,32}")));return File(File(ctx.filesDir,"voice").apply {mkdirs()},"$key.m4a")}
    fun stop(){player?.release();player=null;tts?.stop()}
    fun speak(text:String,clip:String?=null) {
        if(ctx.getSharedPreferences("ui",0).getBoolean("mute",false))return
        stop();val rate=ctx.getSharedPreferences("ui",0).getFloat("speed",.9f);val volume=ctx.getSharedPreferences("ui",0).getFloat("volume",1f)
        val f=clip?.let(::file)
        if(f!=null && f.exists()) {val p=MediaPlayer();player=p;p.setDataSource(f.absolutePath);p.setVolume(volume,volume);p.setOnCompletionListener { it.release();player=null;tts?.setSpeechRate(rate);tts?.speak(text,TextToSpeech.QUEUE_FLUSH,android.os.Bundle().apply {putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,volume)},"mediar") };p.prepare();p.start() }
        else if(offline) {tts?.setSpeechRate(rate);tts?.speak(text,TextToSpeech.QUEUE_FLUSH,android.os.Bundle().apply {putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,volume)},"mediar")}
    }
    fun record(key:String) {stopRecording();val f=file(key);@Suppress("DEPRECATION") val r=MediaRecorder();recorder=r;r.setAudioSource(MediaRecorder.AudioSource.MIC);r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);r.setOutputFile(f.absolutePath);r.prepare();r.start()}
    fun stopRecording(){recorder?.let {runCatching {it.stop()};it.release()};recorder=null}
    fun play(key:String){stop();val f=file(key);if(f.exists()){player=MediaPlayer().apply {setDataSource(f.absolutePath);prepare();start();setOnCompletionListener {it.release();player=null}}}}
    fun release(){stopRecording();stop();tts?.shutdown()}
}
