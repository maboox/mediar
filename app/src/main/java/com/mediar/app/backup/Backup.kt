package com.mediar.app.backup

import android.content.Context
import com.mediar.app.data.Store
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password authenticated export; no ZIP paths are extracted. */
object Backup {
    private val magic="MEDIAR02".toByteArray(Charsets.US_ASCII)
    private fun key(password:CharArray,salt:ByteArray):SecretKeySpec {val spec=PBEKeySpec(password,salt,210_000,256);return try {SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,"AES")}finally {spec.clearPassword()} }
    fun create(ctx:Context,db:Store,password:CharArray):ByteArray {
        require(password.size>=10)
        val o=db.exportJson();val clips=JSONObject();File(ctx.filesDir,"voice").listFiles()?.forEach {f->if(f.isFile && f.name.matches(Regex("[a-z_]{2,32}\\.m4a")) && f.length()<4_000_000)clips.put(f.name,Base64.getEncoder().encodeToString(f.readBytes())) };o.put("voice",clips)
        val prefs=ctx.getSharedPreferences("ui",0);o.put("prefs",JSONObject().put("lang",prefs.getString("lang","fa")).put("english",prefs.getBoolean("english",false)).put("dark",prefs.getBoolean("dark",false)).put("mute",prefs.getBoolean("mute",false)).put("speed",prefs.getFloat("speed",.9f).toDouble()).put("volume",prefs.getFloat("volume",1f).toDouble()))
        val salt=ByteArray(16).also(SecureRandom()::nextBytes);val iv=ByteArray(12).also(SecureRandom()::nextBytes)
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key(password,salt),GCMParameterSpec(128,iv));c.updateAAD(magic)
        return magic+salt+iv+c.doFinal(o.toString().toByteArray(Charsets.UTF_8))
    }
    fun restore(ctx:Context,db:Store,password:CharArray,bytes:ByteArray) {
        require(bytes.size in 45..40_000_000 && bytes.copyOfRange(0,8).contentEquals(magic)) {"Invalid backup"}
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(password,bytes.copyOfRange(8,24)),GCMParameterSpec(128,bytes.copyOfRange(24,36)));c.updateAAD(magic)
        val json=JSONObject(String(c.doFinal(bytes.copyOfRange(36,bytes.size)),Charsets.UTF_8))
        require(json.getInt("format")==2)
        val clips=json.getJSONObject("voice");val stage=File(ctx.cacheDir,"restore_${System.nanoTime()}").apply {mkdirs()}
        try {
            val names=clips.keys().asSequence().toList();require(names.size<=20)
            names.forEach {name->require(name.matches(Regex("[a-z_]{2,32}\\.m4a")));val data=Base64.getDecoder().decode(clips.getString(name));require(data.size<4_000_000);File(stage,name).writeBytes(data)}
            val voice=File(ctx.filesDir,"voice")
            val previous=File(ctx.filesDir,"voice_old_${System.nanoTime()}")
            if(voice.exists()) require(voice.renameTo(previous)) {"Cannot stage current audio"}
            if(!stage.renameTo(voice)) { previous.renameTo(voice);error("Cannot prepare restored audio") }
            try {
                db.importJson(json)
                val pref=json.optJSONObject("prefs")
                if(pref!=null) ctx.getSharedPreferences("ui",0).edit().putString("lang",pref.optString("lang","fa")).putBoolean("english",pref.optBoolean("english",false)).putBoolean("dark",pref.optBoolean("dark",false)).putBoolean("mute",pref.optBoolean("mute",false)).putFloat("speed",pref.optDouble("speed",.9).toFloat()).putFloat("volume",pref.optDouble("volume",1.0).toFloat()).apply()
                previous.deleteRecursively()
            } catch(e:Exception) {
                voice.deleteRecursively();previous.renameTo(voice);throw e
            }
        } finally {stage.deleteRecursively()}
    }
}
