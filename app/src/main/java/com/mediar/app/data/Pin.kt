package com.mediar.app.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.util.Base64

object Pin {
    private fun hash(value: String, salt: String): String {
        val p = PBEKeySpec(value.toCharArray(), Base64.getDecoder().decode(salt), 120_000, 256)
        return try { Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(p).encoded) } finally { p.clearPassword() }
    }
    private fun same(a: String,b: String) = MessageDigest.isEqual(a.toByteArray(),b.toByteArray())
    fun create(db: Store,pin: String): String {
        require(pin.matches(Regex("[0-9]{4,8}")))
        val salt=Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
        val recovery=ByteArray(12).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        db.set("pin_salt_v3",salt);db.set("pin_v3",hash(pin,salt));db.set("recovery_v3",hash(recovery,salt));db.set("pin_attempts","0");db.set("pin_block","0")
        return recovery.chunked(4).joinToString("-")
    }
    fun exists(db: Store) = db.get("pin_v3").isNotEmpty() || db.get("pin_hash").isNotEmpty()
    fun verify(db: Store, value: String, recovery: Boolean = false): Boolean {
        if(System.currentTimeMillis()<db.get("pin_block","0").toLong()) return false
        val salt=db.get("pin_salt_v3")
        val ok=if(recovery) salt.isNotEmpty() && same(hash(value.lowercase().replace("-","").trim(),salt),db.get("recovery_v3"))
        else if(salt.isNotEmpty()) same(hash(value,salt),db.get("pin_v3"))
        else same(MessageDigest.getInstance("SHA-256").digest((db.get("pin_salt")+value).toByteArray()).joinToString("") { "%02x".format(it) },db.get("pin_hash"))
        if(ok) { db.set("pin_attempts","0");db.set("pin_block","0") }
        else {val n=db.get("pin_attempts","0").toInt()+1;db.set("pin_attempts",n.toString());if(n>=5){db.set("pin_block",(System.currentTimeMillis()+60_000).toString());db.set("pin_attempts","0")} }
        return ok
    }
}
