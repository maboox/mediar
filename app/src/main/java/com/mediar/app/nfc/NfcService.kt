package com.mediar.app.nfc

import android.app.Activity
import android.nfc.*
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Handler
import android.os.Looper
import com.mediar.app.logic.ScanGate
import java.security.SecureRandom

class NfcService(private val activity:Activity,private val onRead:(String,Boolean)->Unit,private val onWrite:(String)->Unit) {
    private val adapter=NfcAdapter.getDefaultAdapter(activity)
    private val gate=ScanGate()
    @Volatile private var writing:String?=null
    fun available()=adapter?.isEnabled==true
    fun newToken():String {val bytes=ByteArray(16);SecureRandom().nextBytes(bytes);return "v1:"+bytes.joinToString("") { "%02x".format(it) } }
    fun writeNext(token:String) { writing=token;gate.reset() }
    fun start() { adapter?.enableReaderMode(activity,{tag->process(tag)},NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,null) }
    fun stop() { adapter?.disableReaderMode(activity) }
    fun process(tag:Tag) {
        val target=writing
        if(target!=null) { writing=null;val result=runCatching { write(tag,target);"ok" }.getOrElse { "خطا در نوشتن: ${it.javaClass.simpleName}" };activity.runOnUiThread { onWrite(result) };return }
        val payload=runCatching { Ndef.get(tag)?.let { n->n.connect();try { n.ndefMessage?.records?.firstOrNull { it.toMimeType()==MIME }?.payload?.toString(Charsets.UTF_8) } finally { n.close() } } }.getOrNull() ?:return
        if(!TOKEN.matches(payload))return
        val second=gate.read(payload,android.os.SystemClock.elapsedRealtime())
        if(!second) { // A second tap needs a verified removal. Unsupported devices keep the button path.
            runCatching { adapter?.ignore(tag,300,{gate.removal(payload)},Handler(Looper.getMainLooper())) }
        }
        activity.runOnUiThread { onRead(payload,second) }
    }
    private fun write(tag:Tag,token:String) {
        val msg=NdefMessage(arrayOf(NdefRecord.createMime(MIME,token.toByteArray(Charsets.UTF_8))))
        val n=Ndef.get(tag)
        if(n!=null) {n.connect();try {require(n.isWritable && n.maxSize>=msg.toByteArray().size);n.writeNdefMessage(msg)}finally {n.close()} }
        else { val f=NdefFormatable.get(tag)?:error("Tag is not NDEF compatible");f.connect();try {f.format(msg)}finally {f.close()} }
    }
    companion object {const val MIME="application/vnd.com.mediar.tag";val TOKEN=Regex("v1:[0-9a-f]{32}")}
}
