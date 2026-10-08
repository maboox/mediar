package com.mediar.app.nfc

import android.app.Activity
import android.nfc.*
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.security.SecureRandom
import java.util.concurrent.Executors

/** Reader mode while an activity is in front. A read hands (token, physical tag id) to the caller. */
class NfcController(private val activity: Activity, private val read: (String,String)->Unit, private val written: (String,Boolean)->Unit, private val error: ()->Unit) {
    private val adapter=NfcAdapter.getDefaultAdapter(activity)
    private val io=Executors.newSingleThreadExecutor()
    private data class WriteRequest(val token: String,val commit: ()->Unit)
    @Volatile private var writing: WriteRequest? = null
    private var resumed=false
    fun exists()=adapter!=null
    fun enabled()=adapter?.isEnabled==true
    fun start(){resumed=true;adapter?.enableReaderMode(activity,{process(it)},NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,null)}
    fun stop(){resumed=false;adapter?.disableReaderMode(activity);writing=null}
    fun dispose(){io.shutdown()}
    fun writeNext(token: String,commit: ()->Unit){require(TOKEN.matches(token));writing=WriteRequest(token,commit)}
    fun cancelWrite(){writing=null}
    fun process(tag: Tag){io.execute {
        val target=writing
        if(target!=null) {
            writing=null
            val ok=runCatching { writeTag(tag,target.token);target.commit() }.isSuccess
            activity.runOnUiThread {written(target.token,ok)}
            return@execute
        }
        val payload=readToken(tag)
        if(payload==null){activity.runOnUiThread {error()};return@execute}
        activity.runOnUiThread {read(payload,physical(tag))}
    }}
    private fun writeTag(tag: Tag,token: String) {
        val message=NdefMessage(arrayOf(NdefRecord.createMime(MIME,token.toByteArray(Charsets.UTF_8)),NdefRecord.createApplicationRecord("com.mediar.app")))
        val n=Ndef.get(tag)
        if(n!=null){n.connect();try {require(n.isWritable && n.maxSize>=message.toByteArray().size);n.writeNdefMessage(message)}finally{n.close()}}
        else {val f=NdefFormatable.get(tag)?:error("NDEF required");f.connect();try{f.format(message)}finally{f.close()}}
    }
    companion object {
        const val MIME="application/vnd.com.mediar.tag"
        fun tokenFrom(message: NdefMessage?): String? =
            message?.records?.firstOrNull { it.toMimeType()==MIME }?.payload?.toString(Charsets.UTF_8)?.takeIf { TOKEN.matches(it) }
        /** Reads the Mediar token from a tag (blocking I/O). */
        fun readToken(tag: Tag): String? = runCatching {
            Ndef.get(tag)?.let { n -> n.connect();try { tokenFrom(n.ndefMessage) } finally { n.close() } }
        }.getOrNull()
        fun physical(tag: Tag): String = tag.id?.joinToString(""){"%02x".format(it)} ?: ""
        val TOKEN=Regex("v1:[0-9a-f]{32}")
        fun token()="v1:"+ByteArray(16).also(SecureRandom()::nextBytes).joinToString(""){"%02x".format(it)}
    }
}
