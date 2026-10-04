package com.mediar.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.mediar.app.audio.Speech
import com.mediar.app.backup.*
import com.mediar.app.nfc.NfcController
import com.mediar.app.reminder.Reminders
import com.mediar.app.ui.MediarUi
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

class MainActivity: ComponentActivity() {
    private val model: AppModel by viewModels()
    lateinit var speech: Speech; private set
    lateinit var nfc: NfcController; private set
    private var password=charArrayOf()
    var writeToken: String?=null; private set
    private var writeKind="medicine"
    private var writeTarget=""
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){model.permissionTick++}
    private val microphonePermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){if(!it)model.toast=model.t("مجوز میکروفون داده نشد","Microphone permission was denied")}
    private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->if(uri!=null)fileAction {
        val bytes=Backup.create(this,store(),password);contentResolver.openOutputStream(uri)?.use {it.write(bytes)}?:error("No output")
    } else clearPassword()}
    private val restore=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)fileAction {
        val bytes=contentResolver.openInputStream(uri)?.use {stream->val out=ByteArrayOutputStream();val chunk=ByteArray(8192);while(true){val n=stream.read(chunk);if(n<0)break;require(out.size()+n<=40_000_000);out.write(chunk,0,n)};out.toByteArray()}?:error("No input")
        store().expectations().forEach{Reminders.cancel(this,it.id)}
        Backup.restore(this,store(),password,bytes)
        withContextOnMainLock()
    } else clearPassword()}
    private val report=registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")){uri->if(uri!=null)fileAction {
        contentResolver.openOutputStream(uri)?.use {it.write(Report.html(store().snapshot()).toByteArray(Charsets.UTF_8))}?:error("No output")
    }}
    private fun withContextOnMainLock(){runOnUiThread{model.lock()}}
    private fun clearPassword(){password.fill('\u0000');password=charArrayOf()}
    private fun fileAction(block: ()->Unit){model.fileBusy=true;lifecycleScope.launch {
        try {
            val result=withContext(Dispatchers.IO){runCatching(block)}
            model.refresh();Reminders.work{Reminders.rebuild(this@MainActivity)}
            model.toast=if(result.isSuccess)model.t("عملیات فایل انجام شد","File operation completed") else model.t("انجام نشد؛ گذرواژه، فایل یا فضای ذخیره‌سازی را بررسی کنید.","Could not complete. Check password, file, and storage.")
        } finally {clearPassword();model.fileBusy=false}
    }}
    fun backup(pass: String,import: Boolean){password=pass.toCharArray();if(import)restore.launch(arrayOf("*/*")) else export.launch("Mediar-${java.time.LocalDate.now()}.mediar")}
    fun report(){report.launch("Mediar-report-${java.time.LocalDate.now()}.html")}
    fun notifications(){if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))}
    fun exactAlarms(){if(Build.VERSION.SDK_INT>=31)startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName")))}
    fun microphone(): Boolean {if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)return true;microphonePermission.launch(Manifest.permission.RECORD_AUDIO);return false}
    fun write(kind: String,target: String){if(!model.admin || !nfc.enabled()){model.toast=model.t("NFC گوشی را روشن کنید","Enable NFC on this phone");return};writeToken=NfcController.token();model.prepareTag(writeToken!!,kind,target);val writeTokenForCommit=writeToken!!;nfc.writeNext(writeTokenForCommit){applicationContext.store().bind(writeTokenForCommit,kind,target)};model.toast=model.t("تگ را نزدیک گوشی نگه دارید","Hold the tag near your phone");model.permissionTick++}
    fun cancelWrite(){writeToken=null;nfc.cancelWrite();model.permissionTick++}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);speech=Speech(this)
        nfc=NfcController(this,model::scan,{token,ok->if(writeToken==token)writeToken=null;model.permissionTick++;model.finishTag(token,ok)},{model.toast=model.t("تگ مدیار خوانده نشد","Could not read a Mediar tag")})
        setContent {MediarUi(model,this)}
        lifecycleScope.launch {model.sounds.collect {(text,clip)->speech.speak(text,clip)}}
        if(savedInstanceState==null)consume(intent)
    }
    @Suppress("DEPRECATION") private fun consume(intent: Intent?) {
        if(intent?.action==NfcAdapter.ACTION_NDEF_DISCOVERED){intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)?.let{nfc.process(it)}}
        intent?.getStringExtra("expected")?.let(model::openExpected)
        intent?.removeExtra("expected");intent?.removeExtra(NfcAdapter.EXTRA_TAG)
    }
    override fun onNewIntent(intent: Intent){super.onNewIntent(intent);setIntent(intent);consume(intent)}
    override fun onResume(){super.onResume();nfc.start();speech.refresh();model.resumed()}
    override fun onPause(){nfc.stop();writeToken=null;model.permissionTick++;speech.cancelRecording();speech.stop();model.paused();super.onPause()}
    override fun onDestroy(){clearPassword();nfc.dispose();speech.release();super.onDestroy()}
}
