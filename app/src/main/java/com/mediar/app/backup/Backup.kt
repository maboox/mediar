package com.mediar.app.backup

import android.content.Context
import com.mediar.app.data.Store
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.*

object Backup {
    private val magic="MEDIAR02".toByteArray(Charsets.US_ASCII)
    private fun key(password: CharArray,salt: ByteArray): SecretKeySpec {
        val p=PBEKeySpec(password,salt,210_000,256)
        return try{SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(p).encoded,"AES")}finally{p.clearPassword()}
    }
    fun create(context: Context,db: Store,password: CharArray): ByteArray {
        require(password.size>=10)
        val o=db.exportJson();val clips=JSONObject()
        File(context.filesDir,"voice").listFiles()?.filter{it.isFile}?.forEach {f->
            require(f.name.matches(Regex("[a-z_]{2,40}\\.m4a")) && f.length()<4_000_000)
            clips.put(f.name,Base64.getEncoder().encodeToString(f.readBytes()))
        }
        o.put("voice",clips)
        val plain=o.toString().toByteArray(Charsets.UTF_8);require(plain.size<32_000_000)
        val salt=ByteArray(16).also(SecureRandom()::nextBytes);val iv=ByteArray(12).also(SecureRandom()::nextBytes)
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key(password,salt),GCMParameterSpec(128,iv));c.updateAAD(magic)
        return magic+salt+iv+c.doFinal(plain)
    }
    fun restore(context: Context,db: Store,password: CharArray,bytes: ByteArray) {
        require(bytes.size in 52..40_000_000 && bytes.copyOfRange(0,8).contentEquals(magic))
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(password,bytes.copyOfRange(8,24)),GCMParameterSpec(128,bytes.copyOfRange(24,36)));c.updateAAD(magic)
        val o=JSONObject(String(c.doFinal(bytes.copyOfRange(36,bytes.size)),Charsets.UTF_8))
        require(o.getInt("format") in 2..3)
        val clips=o.optJSONObject("voice")?:JSONObject()
        val stage=File(context.filesDir,"restore_${System.nanoTime()}").apply{mkdirs()}
        val voice=File(context.filesDir,"voice")
        val old=File(context.filesDir,"voice_old_${System.nanoTime()}")
        try {
            val names=clips.keys().asSequence().toList();require(names.size<=20)
            var size=0
            names.forEach {name->
                require(name.matches(Regex("[a-z_]{2,40}\\.m4a")))
                val data=Base64.getDecoder().decode(clips.getString(name));size+=data.size;require(data.size<4_000_000 && size<16_000_000)
                File(stage,name).writeBytes(data)
            }
            if(voice.exists())require(voice.renameTo(old))
            if(!stage.renameTo(voice)){old.renameTo(voice);error("Storage unavailable")}
            try{db.importJson(o);old.deleteRecursively()}
            catch(e:Exception){voice.deleteRecursively();old.renameTo(voice);throw e}
        }finally{stage.deleteRecursively()}
    }
}
